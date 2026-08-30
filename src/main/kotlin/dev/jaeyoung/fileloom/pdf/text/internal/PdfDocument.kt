package dev.jaeyoung.fileloom.pdf.text.internal

import dev.jaeyoung.fileloom.pdf.document.PdfObjectId
import dev.jaeyoung.fileloom.pdf.document.PdfXrefEntry
import dev.jaeyoung.fileloom.pdf.source.ByteArrayPdfByteSource
import dev.jaeyoung.fileloom.pdf.source.PdfByteSource
import dev.jaeyoung.fileloom.pdf.syntax.PdfLexer
import dev.jaeyoung.fileloom.pdf.syntax.PdfObject
import dev.jaeyoung.fileloom.pdf.syntax.PdfObjectParser
import dev.jaeyoung.fileloom.pdf.syntax.PdfParseException
import dev.jaeyoung.fileloom.pdf.syntax.PdfToken
import java.nio.charset.StandardCharsets

/**
 * Lenient PDF document reader for the text-extraction path.
 *
 * Replaces `dev.jaeyoung.fileloom.pdf.document.PdfDocumentReader` from
 * `fileloom-pdf-parser-core` 0.3.0, which is strict in places that break on
 * real-world PDFs:
 *  - bails when the `trailer` keyword and the trailer dict open `<<` sit on
 *    the same line (common in LaTeX/arXiv output);
 *  - has no public constructor for `PdfLowLevelDocument`, so we cannot
 *    construct one ourselves to work around the above.
 *
 * The lexer + object parser primitives from parser-core remain in use — only
 * the document layer is replaced. This layer accepts both classic xref tables
 * and the common PDF 1.5 xref/object stream form, while still failing softly on
 * encrypted files or stream filters outside this lightweight parser's scope.
 */
internal class PdfDocument internal constructor(
    val source: PdfByteSource,
    val xrefEntries: Map<PdfObjectId, PdfXrefEntry>,
    val trailer: PdfObject.Dictionary,
    val startXref: Long,
    private val compressedXrefEntries: Map<PdfObjectId, CompressedXrefEntry> = emptyMap(),
) : AutoCloseable {

    private val resolvedCache = mutableMapOf<PdfObjectId, PdfObject>()
    private val objectStreamCache = mutableMapOf<Int, List<Pair<Int, PdfObject>>>()

    fun resolve(reference: PdfObject.Reference): PdfObject? {
        val id = PdfObjectId(reference.objectNumber, reference.generationNumber)
        resolvedCache[id]?.let { return it }
        val inUse = xrefEntries[id] as? PdfXrefEntry.InUse
        if (inUse != null) {
            val lexer = PdfLexer(source, startPosition = inUse.offset)
            val parser = PdfObjectParser(lexer)
        val indirect = runCatching {
            parser.parseIndirectObject()
        }.getOrNull() ?: runCatching {
            parseIndirectStreamHeader(source, inUse.offset)
                ?.takeIf { it.id == id }
                ?.dictionary
                ?.let { dictionary ->
                    dev.jaeyoung.fileloom.pdf.document.PdfIndirectObject(id, dictionary)
                }
        }.getOrNull()
                ?: return null
            if (indirect.id != id) return null
            resolvedCache[id] = indirect.value
            return indirect.value
        }
        val compressed = compressedXrefEntries[id] ?: return null
        val value = loadCompressedObject(
            objectStreamNumber = compressed.objectStreamNumber,
            objectIndex = compressed.objectIndex,
            objectNumber = reference.objectNumber,
        )
            ?: return null
        resolvedCache[id] = value
        return value
    }

    /** Resolve [value] if it's a [PdfObject.Reference], else return it. */
    fun deref(value: PdfObject?): PdfObject? {
        if (value == null) return null
        return if (value is PdfObject.Reference) resolve(value) else value
    }

    internal fun nextAvailableObjectNumber(requiredNewObjects: Int): Int? {
        if (requiredNewObjects <= 0) return null
        val trailerSize = (trailer.entries["Size"] as? PdfObject.IntegerValue)?.value ?: 0L
        val regularNext = (xrefEntries.keys.maxOfOrNull(PdfObjectId::objectNumber)?.toLong() ?: 0L) + 1L
        val compressedNext = (
            compressedXrefEntries.keys.maxOfOrNull(PdfObjectId::objectNumber)?.toLong() ?: 0L
            ) + 1L
        return maxOf(1L, trailerSize, regularNext, compressedNext)
            .takeIf { firstObjectNumber ->
                firstObjectNumber + requiredNewObjects.toLong() <= Int.MAX_VALUE.toLong()
            }
            ?.toInt()
    }

    private fun loadCompressedObject(
        objectStreamNumber: Int,
        objectIndex: Int,
        objectNumber: Int,
    ): PdfObject? {
        val parsed = objectStreamCache[objectStreamNumber]
            ?: parseObjectStream(objectStreamNumber).orEmpty().also { parsed ->
                objectStreamCache[objectStreamNumber] = parsed
            }
        val selected = parsed.getOrNull(objectIndex) ?: return null
        return selected.second.takeIf { selected.first == objectNumber }
    }

    private fun parseObjectStream(objectStreamNumber: Int): List<Pair<Int, PdfObject>>? {
        val reference = PdfObject.Reference(objectStreamNumber, 0)
        val dictionary = parseIndirectObjectDictionary(reference) ?: return null
        val type = (dictionary.entries["Type"] as? PdfObject.Name)?.value
        if (type != "ObjStm") return null
        val declaredCount = (dictionary.entries["N"] as? PdfObject.IntegerValue)?.value ?: return null
        if (declaredCount !in 0..MAX_OBJECT_STREAM_OBJECTS.toLong()) return null
        val count = declaredCount.toInt()
        val first = (dictionary.entries["First"] as? PdfObject.IntegerValue)?.value?.toLong()?.coerceAtLeast(0)
            ?: return null
        val stream = PdfStreamReader.extractStream(
            document = this,
            reference = reference,
            maxRawBytes = MAX_OBJECT_STREAM_BYTES,
            maxDecodedBytes = MAX_OBJECT_STREAM_BYTES,
        ) ?: return null
        val streamSource = ByteArrayPdfByteSource(stream)
        val headerParser = PdfObjectParser(PdfLexer(streamSource))
        val objectHeaders = mutableListOf<Pair<Int, Long>>()
        repeat(count) {
            val nestedObjectNumber = (runCatching { headerParser.parseObject() }.getOrNull() as? PdfObject.IntegerValue)
                ?.value
                ?.toInt()
                ?: return null
            val nestedObjectOffset = (runCatching { headerParser.parseObject() }.getOrNull() as? PdfObject.IntegerValue)
                ?.value
                ?: return null
            objectHeaders += nestedObjectNumber to nestedObjectOffset
        }
        return objectHeaders.map { (nestedObjectNumber, nestedObjectOffset) ->
            val objectOffset = first + nestedObjectOffset
            val parser = PdfObjectParser(PdfLexer(ByteArrayPdfByteSource(stream), startPosition = objectOffset))
            val value = runCatching { parser.parseObject() }.getOrNull() ?: return null
            nestedObjectNumber to value
        }
    }

    private fun parseIndirectObjectDictionary(reference: PdfObject.Reference): PdfObject.Dictionary? {
        val id = PdfObjectId(reference.objectNumber, reference.generationNumber)
        val entry = xrefEntries[id] as? PdfXrefEntry.InUse ?: return null
        return parseIndirectStreamHeader(source, entry.offset)
            ?.takeIf { it.id == id }
            ?.dictionary
    }

    override fun close() {
        source.close()
    }

    companion object {
        /**
         * Open a PDF byte source. Returns null for PDFs we cannot handle
         * (encrypted, malformed header, unsupported stream filters).
         */
        fun open(source: PdfByteSource): PdfDocument? {
            return runCatching { openOrThrow(source) }.getOrNull()
        }

        private fun openOrThrow(source: PdfByteSource): PdfDocument? {
            validateHeader(source)
            val startXref = findStartXref(source)
            val parsed = readXrefAndTrailer(source, startXref) ?: return null
            if (parsed.trailer.entries.containsKey("Encrypt")) return null
            return PdfDocument(
                source = source,
                xrefEntries = parsed.entries,
                trailer = parsed.trailer,
                startXref = startXref,
                compressedXrefEntries = parsed.compressedEntries,
            )
        }

        private fun validateHeader(source: PdfByteSource) {
            val headerBytes = readSlice(source, start = 0, count = minOf(1024, source.length.toInt()))
            val header = headerBytes.toString(StandardCharsets.ISO_8859_1)
            val markerIndex = header.indexOf("%PDF-")
            if (markerIndex < 0) {
                throw PdfParseException("missing PDF header", offset = 0)
            }
        }

        private fun findStartXref(source: PdfByteSource): Long {
            val lowerBound = (source.length - MAX_STARTXREF_TAIL_BYTES).coerceAtLeast(0L)
            var logicalEnd = source.length
            var greatestValidOffset: Long? = null
            while (logicalEnd > lowerBound) {
                val windowStart = (logicalEnd - STARTXREF_WINDOW_BYTES).coerceAtLeast(lowerBound)
                val readEnd = (logicalEnd + STARTXREF_WINDOW_OVERLAP_BYTES)
                    .coerceAtMost(source.length)
                val window = readSlice(
                    source = source,
                    start = windowStart,
                    count = (readEnd - windowStart).toInt(),
                ).toString(StandardCharsets.ISO_8859_1)
                parseGreatestValidStartXref(
                    source = source,
                    window = window,
                    windowStart = windowStart,
                    logicalEnd = logicalEnd,
                    sourceLength = source.length,
                )?.let { candidate ->
                    greatestValidOffset = maxOf(greatestValidOffset ?: candidate, candidate)
                }
                logicalEnd = windowStart
            }
            return greatestValidOffset
                ?: throw PdfParseException("missing startxref", offset = lowerBound)
        }

        private fun parseGreatestValidStartXref(
            source: PdfByteSource,
            window: String,
            windowStart: Long,
            logicalEnd: Long,
            sourceLength: Long,
        ): Long? {
            var searchFrom = window.lastIndex
            var greatestValidOffset: Long? = null
            while (searchFrom >= 0) {
                val markerIndex = window.lastIndexOf(STARTXREF_MARKER, startIndex = searchFrom)
                if (markerIndex < 0) return greatestValidOffset
                val absoluteMarkerOffset = windowStart + markerIndex
                if (absoluteMarkerOffset < logicalEnd) {
                    var index = markerIndex + STARTXREF_MARKER.length
                    while (index < window.length && window[index].isWhitespace()) index += 1
                    val numberStart = index
                    while (index < window.length && window[index].isDigit()) index += 1
                    if (numberStart < index) {
                        val offset = window.substring(numberStart, index).toLongOrNull()
                        if (
                            offset != null &&
                            offset in 0 until sourceLength &&
                            isParseableXrefAt(source, offset)
                        ) {
                            greatestValidOffset = maxOf(greatestValidOffset ?: offset, offset)
                        }
                    }
                }
                searchFrom = markerIndex - 1
            }
            return greatestValidOffset
        }

        private fun isParseableXrefAt(source: PdfByteSource, offset: Long): Boolean =
            runCatching { readXrefAndTrailer(source, offset) != null }.getOrDefault(false)

        private fun readXrefAndTrailer(
            source: PdfByteSource,
            xrefOffset: Long,
            visitedOffsets: MutableSet<Long> = mutableSetOf(),
        ): ParsedXref? {
            if (!visitedOffsets.add(xrefOffset)) return null
            val firstByte = readByteAt(source, xrefOffset)
            return if (firstByte == 'x'.code) {
                readClassicXrefAndTrailer(source, xrefOffset, visitedOffsets)
            } else {
                readXrefStreamAndTrailer(source, xrefOffset, visitedOffsets)
            }
        }

        /**
         * Parse classic xref subsections and trailer dictionary.
         *
         * Differences from parser-core 0.3.0:
         *  - the `trailer` keyword may share a line with the trailer dict
         *    open `<<`;
         *  - extra whitespace/blank lines between subsections are tolerated;
         *  - subsection entry lines that are missing the trailing space (some
         *    encoders) still parse.
         */
        private fun readClassicXrefAndTrailer(
            source: PdfByteSource,
            xrefOffset: Long,
            visitedOffsets: MutableSet<Long>,
        ): ParsedXref? {
            val reader = LineReader(source, xrefOffset)
            val firstLine = reader.readNonBlankLine()
                ?: throw PdfParseException("missing xref keyword", xrefOffset)
            val firstLineTrimmed = firstLine.text.trim()
            if (firstLineTrimmed != "xref") {
                throw PdfParseException("expected xref keyword, got '$firstLineTrimmed'", firstLine.offset)
            }

            val entries = linkedMapOf<PdfObjectId, PdfXrefEntry>()
            var trailerKeywordEndOffset: Long? = null

            outer@ while (true) {
                val line = reader.readNonBlankLine()
                    ?: throw PdfParseException("missing trailer", reader.position)
                val trimmed = line.text.trim()
                if (trimmed.startsWithPdfKeyword("trailer")) {
                    // Compute the byte offset immediately after the "trailer"
                    // keyword on the original line — the `<<` may follow on
                    // the same line or on a subsequent line.
                    val keywordIdxInLine = line.text.indexOf("trailer")
                    trailerKeywordEndOffset = line.offset + keywordIdxInLine + "trailer".length
                    break@outer
                }

                val parts = trimmed.split(WHITESPACE_REGEX)
                if (parts.size != 2) {
                    throw PdfParseException("invalid xref subsection header '$trimmed'", line.offset)
                }
                val firstObjectNumber = parts[0].toIntOrNull()
                    ?: throw PdfParseException("invalid xref subsection object number", line.offset)
                val count = parts[1].toIntOrNull()
                    ?: throw PdfParseException("invalid xref subsection count", line.offset)

                repeat(count) { relativeIndex ->
                    val entryLine = reader.readNonBlankLine()
                        ?: throw PdfParseException("unexpected end of xref subsection", reader.position)
                    val entry = parseXrefEntry(entryLine)
                    val objectNumber = firstObjectNumber + relativeIndex
                    val id = PdfObjectId(objectNumber, entry.generationNumber)
                    entries[id] = entry
                }
            }

            // Find the start of the trailer dictionary by scanning forward
            // from the end of the "trailer" keyword for the first `<<`.
            val dictionaryOffset = locateNextDictOpen(source, trailerKeywordEndOffset!!)
            val trailerLexer = PdfLexer(source, startPosition = dictionaryOffset)
            val trailerParser = PdfObjectParser(trailerLexer)
            val trailer = trailerParser.parseObject() as? PdfObject.Dictionary
                ?: throw PdfParseException("expected trailer dictionary", dictionaryOffset)

            var current = ParsedXref(entries, emptyMap(), trailer)
            val supplementalXrefOffset = (trailer.entries["XRefStm"] as? PdfObject.IntegerValue)?.value
            if (supplementalXrefOffset != null && visitedOffsets.add(supplementalXrefOffset)) {
                val supplemental = readXrefStreamAndTrailer(
                    source = source,
                    xrefOffset = supplementalXrefOffset,
                    visitedOffsets = visitedOffsets,
                    followPrevious = false,
                )
                if (supplemental != null) {
                    current = mergeParsedXref(older = supplemental, newer = current)
                }
            }

            val previousXrefOffset = (trailer.entries["Prev"] as? PdfObject.IntegerValue)?.value
                ?: return current
            val previous = readXrefAndTrailer(source, previousXrefOffset, visitedOffsets)
                ?: return current
            return mergeParsedXref(older = previous, newer = current)
        }

        private fun readXrefStreamAndTrailer(
            source: PdfByteSource,
            xrefOffset: Long,
            visitedOffsets: MutableSet<Long>,
            followPrevious: Boolean = true,
        ): ParsedXref? {
            val header = parseIndirectStreamHeader(source, xrefOffset) ?: return null
            val trailer = header.dictionary
            val type = (trailer.entries["Type"] as? PdfObject.Name)?.value
            if (type != "XRef") return null

            val size = (trailer.entries["Size"] as? PdfObject.IntegerValue)?.value?.toInt()
                ?: return null
            val widths = trailer.entries["W"].asXrefStreamWidths() ?: return null
            val indexes = trailer.entries["Index"].asXrefStreamIndexPairs() ?: listOf(0 to size)
            val entryWidth = widths.sum()
            if (entryWidth <= 0) return null
            val expectedDecodedBytes = expectedXrefDecodedBytes(indexes, entryWidth) ?: return null
            val length = (trailer.entries["Length"] as? PdfObject.IntegerValue)?.value?.toInt()?.coerceAtLeast(0)
                ?: return null
            val availablePayloadBytes = source.length - header.streamPayloadStart
            if (
                length > MAX_XREF_STREAM_BYTES ||
                availablePayloadBytes < 0L ||
                length.toLong() > availablePayloadBytes
            ) {
                return null
            }
            val rawBytes = readSlice(source, header.streamPayloadStart, length)
            val decoded = PdfFilters.decode(
                rawBytes = rawBytes,
                streamDictionary = trailer,
                strictFlate = true,
                maxDecodedBytes = expectedDecodedBytes,
                resolve = { null },
            ) ?: return null
            if (decoded.size != expectedDecodedBytes) return null

            val entries = linkedMapOf<PdfObjectId, PdfXrefEntry>()
            val compressedEntries = linkedMapOf<PdfObjectId, CompressedXrefEntry>()
            var cursor = 0
            indexes.forEach { (firstObjectNumber, count) ->
                repeat(count) { relativeIndex ->
                    if (cursor + entryWidth > decoded.size) return null
                    val typeValue = readXrefStreamField(decoded, cursor, widths[0])
                    cursor += widths[0]
                    val field1 = readXrefStreamField(decoded, cursor, widths[1])
                    cursor += widths[1]
                    val field2 = readXrefStreamField(decoded, cursor, widths[2])
                    cursor += widths[2]
                    val objectNumber = firstObjectNumber + relativeIndex
                    when (if (widths[0] == 0) 1L else typeValue) {
                        0L -> entries[PdfObjectId(objectNumber, field2.toInt())] = PdfXrefEntry.Free(
                            nextFreeObjectNumber = field1.toInt(),
                            generationNumber = field2.toInt(),
                        )
                        1L -> entries[PdfObjectId(objectNumber, field2.toInt())] = PdfXrefEntry.InUse(
                            offset = field1,
                            generationNumber = field2.toInt(),
                        )
                        2L -> compressedEntries[PdfObjectId(objectNumber, 0)] = CompressedXrefEntry(
                            objectStreamNumber = field1.toInt(),
                            objectIndex = field2.toInt(),
                        )
                    }
                }
            }
            entries[header.id] = PdfXrefEntry.InUse(
                offset = xrefOffset,
                generationNumber = header.id.generationNumber,
            )

            val current = ParsedXref(entries, compressedEntries, trailer)
            if (!followPrevious) return current

            val previousXrefOffset = (trailer.entries["Prev"] as? PdfObject.IntegerValue)?.value
                ?: return current
            val previous = readXrefAndTrailer(source, previousXrefOffset, visitedOffsets)
                ?: return current
            return mergeParsedXref(older = previous, newer = current)
        }

        private fun mergeParsedXref(older: ParsedXref, newer: ParsedXref): ParsedXref {
            val newerObjectNumbers = buildSet {
                newer.entries.keys.forEach { add(it.objectNumber) }
                newer.compressedEntries.keys.forEach { add(it.objectNumber) }
            }
            val entries = linkedMapOf<PdfObjectId, PdfXrefEntry>()
            older.entries
                .filterKeys { it.objectNumber !in newerObjectNumbers }
                .forEach(entries::put)
            newer.entries.forEach(entries::put)
            val compressedEntries = linkedMapOf<PdfObjectId, CompressedXrefEntry>()
            older.compressedEntries
                .filterKeys { it.objectNumber !in newerObjectNumbers }
                .forEach(compressedEntries::put)
            newer.compressedEntries.forEach(compressedEntries::put)
            return ParsedXref(
                entries = entries,
                compressedEntries = compressedEntries,
                trailer = PdfObject.Dictionary(older.trailer.entries + newer.trailer.entries),
            )
        }

        private fun parseXrefEntry(line: LineReader.Line): PdfXrefEntry {
            val parts = line.text.trim().split(WHITESPACE_REGEX)
            if (parts.size < 3) {
                throw PdfParseException("invalid xref entry '${line.text}'", line.offset)
            }
            val offsetOrNextFree = parts[0].toLongOrNull()
                ?: throw PdfParseException("invalid xref entry offset", line.offset)
            val generation = parts[1].toIntOrNull()
                ?: throw PdfParseException("invalid xref entry generation", line.offset)
            return when (parts[2]) {
                "n" -> PdfXrefEntry.InUse(offset = offsetOrNextFree, generationNumber = generation)
                "f" -> PdfXrefEntry.Free(
                    nextFreeObjectNumber = offsetOrNextFree.toInt(),
                    generationNumber = generation,
                )
                else -> throw PdfParseException("invalid xref entry flag '${parts[2]}'", line.offset)
            }
        }

        private fun locateNextDictOpen(source: PdfByteSource, from: Long): Long {
            var position = from
            while (position < source.length - 1) {
                val b0 = readByteAt(source, position)
                if (b0 == '<'.code) {
                    val b1 = readByteAt(source, position + 1)
                    if (b1 == '<'.code) return position
                }
                position += 1
            }
            throw PdfParseException("trailer dictionary not found", from)
        }

        private fun readByteAt(source: PdfByteSource, position: Long): Int {
            val byte = ByteArray(1)
            val read = source.read(position, byte, 0, 1)
            if (read != 1) throw PdfParseException("unexpected end of file", position)
            return byte[0].toInt() and 0xff
        }

        private fun readSlice(source: PdfByteSource, start: Long, count: Int): ByteArray {
            val bytes = ByteArray(count)
            var total = 0
            while (total < count) {
                val read = source.read(start + total, bytes, total, count - total)
                if (read <= 0) break
                total += read
            }
            return if (total == count) bytes else bytes.copyOf(total)
        }

        private fun parseIndirectStreamHeader(
            source: PdfByteSource,
            offset: Long,
        ): IndirectStreamHeader? {
            val lexer = PdfLexer(source, startPosition = offset)
            val objectNumber = lexer.nextToken() as? PdfToken.IntegerNumber ?: return null
            val generationNumber = lexer.nextToken() as? PdfToken.IntegerNumber ?: return null
            val objKeyword = lexer.nextToken() as? PdfToken.Keyword ?: return null
            if (objKeyword.value != "obj") return null
            val first = lexer.nextToken() as? PdfToken.StartDictionary ?: return null
            val dictionary = PdfObjectParser(PdfLexer(source, startPosition = first.offset))
                .parseObject() as? PdfObject.Dictionary ?: return null
            val dictionaryEnd = locateDictionaryEnd(source, first.offset)
            return IndirectStreamHeader(
                id = PdfObjectId(
                    objectNumber = objectNumber.value.toReferencePart(),
                    generationNumber = generationNumber.value.toReferencePart(),
                ),
                dictionary = dictionary,
                streamPayloadStart = locateStreamPayloadStart(source, dictionaryEnd),
            )
        }

        private fun locateDictionaryEnd(source: PdfByteSource, startOffset: Long): Long {
            val lexer = PdfLexer(source, startPosition = startOffset)
            var depth = 0
            while (true) {
                val token = lexer.nextToken() ?: throw PdfParseException("unexpected EOF in dictionary", startOffset)
                when (token) {
                    is PdfToken.StartDictionary -> depth += 1
                    is PdfToken.EndDictionary -> {
                        depth -= 1
                        if (depth == 0) return token.offset + 2
                    }
                    else -> Unit
                }
            }
        }

        private fun locateStreamPayloadStart(source: PdfByteSource, dictionaryEnd: Long): Long {
            val token = PdfLexer(source, startPosition = dictionaryEnd).nextToken() as? PdfToken.Keyword
                ?: throw PdfParseException("missing stream keyword", dictionaryEnd)
            if (token.value != "stream") {
                throw PdfParseException("expected stream keyword", token.offset)
            }
            val afterKeyword = token.offset + "stream".length
            return when (readByteAt(source, afterKeyword)) {
                '\n'.code -> afterKeyword + 1L
                '\r'.code -> if (
                    afterKeyword + 1L < source.length &&
                    readByteAt(source, afterKeyword + 1L) == '\n'.code
                ) {
                    afterKeyword + 2L
                } else {
                    afterKeyword + 1L
                }
                else -> throw PdfParseException("stream keyword is not followed by an EOL", afterKeyword)
            }
        }

        private fun PdfObject?.asXrefStreamWidths(): IntArray? {
            val widths = this as? PdfObject.ArrayValue ?: return null
            if (widths.items.size < 3) return null
            return IntArray(3) { index ->
                val width = (widths.items[index] as? PdfObject.IntegerValue)?.value?.toInt()
                    ?: return null
                width.takeIf { it in 0..8 } ?: return null
            }
        }

        private fun Int.isPdfWhitespace(): Boolean =
            this == 0 || this == 9 || this == 10 || this == 12 || this == 13 || this == 32

        private fun String.startsWithPdfKeyword(keyword: String): Boolean {
            if (!startsWith(keyword)) return false
            val boundary = getOrNull(keyword.length) ?: return true
            return boundary.code.isPdfWhitespace() || boundary in "()<>[]{}/%"
        }

        private fun PdfObject?.asXrefStreamIndexPairs(): List<Pair<Int, Int>>? {
            val index = this ?: return null
            val array = index as? PdfObject.ArrayValue ?: return null
            if (array.items.size % 2 != 0) return null
            return array.items.chunked(2).map { pair ->
                val start = (pair[0] as? PdfObject.IntegerValue)?.value?.toInt() ?: return null
                val count = (pair[1] as? PdfObject.IntegerValue)?.value?.toInt() ?: return null
                start to count
            }
        }

        private fun expectedXrefDecodedBytes(indexes: List<Pair<Int, Int>>, entryWidth: Int): Int? {
            var entryCount = 0L
            indexes.forEach { (firstObjectNumber, count) ->
                if (firstObjectNumber < 0 || count < 0) return null
                if (firstObjectNumber.toLong() + count.toLong() > Int.MAX_VALUE.toLong() + 1L) return null
                entryCount += count.toLong()
                if (entryCount > MAX_XREF_ENTRIES.toLong()) return null
                if (entryCount * entryWidth.toLong() > MAX_XREF_STREAM_BYTES.toLong()) return null
            }
            return (entryCount * entryWidth.toLong()).toInt()
        }

        private fun readXrefStreamField(bytes: ByteArray, offset: Int, width: Int): Long {
            var value = 0L
            repeat(width) { index ->
                value = (value shl 8) or ((bytes.getOrNull(offset + index)?.toInt() ?: 0) and 0xff).toLong()
            }
            return value
        }

        private fun Long.toReferencePart(): Int =
            takeIf { it >= 0 && it <= Int.MAX_VALUE }?.toInt() ?: 0

        private val WHITESPACE_REGEX = Regex("\\s+")
        private const val STARTXREF_WINDOW_BYTES = 64 * 1024
        private const val STARTXREF_WINDOW_OVERLAP_BYTES = 128
        private const val MAX_STARTXREF_TAIL_BYTES = 1024 * 1024
        private const val MAX_OBJECT_STREAM_BYTES = 64 * 1024 * 1024
        private const val MAX_OBJECT_STREAM_OBJECTS = 100_000
        private const val MAX_XREF_ENTRIES = 100_000
        private const val MAX_XREF_STREAM_BYTES = 64 * 1024 * 1024
        private const val STARTXREF_MARKER = "startxref"

        private data class ParsedXref(
            val entries: Map<PdfObjectId, PdfXrefEntry>,
            val compressedEntries: Map<PdfObjectId, CompressedXrefEntry>,
            val trailer: PdfObject.Dictionary,
        )

        private data class IndirectStreamHeader(
            val id: PdfObjectId,
            val dictionary: PdfObject.Dictionary,
            val streamPayloadStart: Long,
        )
    }

    /**
     * Line-by-line reader over a PdfByteSource. Handles CRLF / CR / LF
     * line endings; reports the byte offset of the start of each line so
     * we can compute precise positions for downstream parsing.
     */
    private class LineReader(
        private val source: PdfByteSource,
        startPosition: Long,
    ) {
        private val one = ByteArray(1)
        var position: Long = startPosition
            private set

        data class Line(val text: String, val offset: Long)

        fun readNonBlankLine(): Line? {
            while (true) {
                val line = readLine() ?: return null
                if (line.text.isNotBlank()) return line
            }
        }

        private fun readLine(): Line? {
            if (position >= source.length) return null
            val start = position
            val bytes = mutableListOf<Byte>()
            while (position < source.length) {
                val read = source.read(position, one, 0, 1)
                if (read != 1) break
                val byte = one[0].toInt() and 0xff
                position += 1
                if (byte == '\n'.code) break
                if (byte == '\r'.code) {
                    if (position < source.length) {
                        val next = source.read(position, one, 0, 1)
                        if (next == 1 && (one[0].toInt() and 0xff) == '\n'.code) position += 1
                    }
                    break
                }
                bytes += byte.toByte()
            }
            return Line(
                text = bytes.toByteArray().toString(StandardCharsets.ISO_8859_1),
                offset = start,
            )
        }
    }
}

internal data class CompressedXrefEntry(
    val objectStreamNumber: Int,
    val objectIndex: Int,
)
