package dev.jaeyoung.fileloom.pdf.text.internal

import dev.jaeyoung.fileloom.pdf.document.PdfObjectId
import dev.jaeyoung.fileloom.pdf.document.PdfXrefEntry
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
 * the document layer is replaced. Cross-reference streams (PDF 1.5+ binary
 * xref) are still out of scope; PDFs that rely on those will yield an empty
 * text result, matching the previous behaviour.
 */
internal class PdfDocument internal constructor(
    val source: PdfByteSource,
    val xrefEntries: Map<PdfObjectId, PdfXrefEntry>,
    val trailer: PdfObject.Dictionary,
    val startXref: Long,
) : AutoCloseable {

    private val resolvedCache = mutableMapOf<PdfObjectId, PdfObject>()

    fun resolve(reference: PdfObject.Reference): PdfObject? {
        val id = PdfObjectId(reference.objectNumber, reference.generationNumber)
        resolvedCache[id]?.let { return it }
        val entry = xrefEntries[id] as? PdfXrefEntry.InUse ?: return null
        val lexer = PdfLexer(source, startPosition = entry.offset)
        val parser = PdfObjectParser(lexer)
        val indirect = runCatching { parser.parseIndirectObject() }.getOrNull() ?: return null
        if (indirect.id != id) return null
        resolvedCache[id] = indirect.value
        return indirect.value
    }

    /** Resolve [value] if it's a [PdfObject.Reference], else return it. */
    fun deref(value: PdfObject?): PdfObject? {
        if (value == null) return null
        return if (value is PdfObject.Reference) resolve(value) else value
    }

    override fun close() {
        source.close()
    }

    companion object {
        /**
         * Open a PDF byte source. Returns null for PDFs we cannot handle
         * (encrypted, cross-reference stream only, malformed header).
         */
        fun open(source: PdfByteSource): PdfDocument? {
            return runCatching { openOrThrow(source) }.getOrNull()
        }

        private fun openOrThrow(source: PdfByteSource): PdfDocument? {
            validateHeader(source)
            val startXref = findStartXref(source)
            val parsed = readClassicXrefAndTrailer(source, startXref) ?: return null
            if (parsed.trailer.entries.containsKey("Encrypt")) return null
            return PdfDocument(
                source = source,
                xrefEntries = parsed.entries,
                trailer = parsed.trailer,
                startXref = startXref,
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
            val tailLength = minOf(4096, source.length.toInt())
            val tailStart = source.length - tailLength
            val tail = readSlice(source, start = tailStart, count = tailLength)
                .toString(StandardCharsets.ISO_8859_1)

            val markerIndex = tail.lastIndexOf("startxref")
            if (markerIndex < 0) {
                throw PdfParseException("missing startxref", offset = tailStart)
            }

            var index = markerIndex + "startxref".length
            while (index < tail.length && tail[index].isWhitespace()) index += 1
            val numberStart = index
            while (index < tail.length && tail[index].isDigit()) index += 1
            if (numberStart == index) {
                throw PdfParseException("missing startxref offset", offset = tailStart + numberStart)
            }
            return tail.substring(numberStart, index).toLong()
        }

        /**
         * Parse the xref subsections and trailer dictionary.
         *
         * Differences from parser-core 0.3.0:
         *  - the `trailer` keyword may share a line with the trailer dict
         *    open `<<`;
         *  - extra whitespace/blank lines between subsections are tolerated;
         *  - subsection entry lines that are missing the trailing space (some
         *    encoders) still parse.
         *
         * Returns null when the xref turns out to be a cross-reference stream
         * (the first token is a number whose object is a `/Type /XRef` stream)
         * — we don't support that variant yet.
         */
        private fun readClassicXrefAndTrailer(
            source: PdfByteSource,
            xrefOffset: Long,
            visitedOffsets: MutableSet<Long> = mutableSetOf(),
        ): ParsedXref? {
            if (!visitedOffsets.add(xrefOffset)) return null
            val firstByte = readByteAt(source, xrefOffset)
            if (firstByte != 'x'.code) {
                // Likely a cross-reference stream (PDF 1.5+) starting with an
                // object number. Out of scope.
                return null
            }

            val reader = LineReader(source, xrefOffset)
            val firstLine = reader.readNonBlankLine()
                ?: throw PdfParseException("missing xref keyword", xrefOffset)
            val firstLineTrimmed = firstLine.text.trim()
            if (firstLineTrimmed != "xref" && !firstLineTrimmed.startsWith("xref")) {
                throw PdfParseException("expected xref keyword, got '$firstLineTrimmed'", firstLine.offset)
            }

            val entries = linkedMapOf<PdfObjectId, PdfXrefEntry>()
            var trailerKeywordEndOffset: Long? = null

            outer@ while (true) {
                val line = reader.readNonBlankLine()
                    ?: throw PdfParseException("missing trailer", reader.position)
                val trimmed = line.text.trim()
                if (trimmed.startsWith("trailer")) {
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

            val previousXrefOffset = (trailer.entries["Prev"] as? PdfObject.IntegerValue)?.value
            if (previousXrefOffset == null) {
                return ParsedXref(entries, trailer)
            }

            val previous = readClassicXrefAndTrailer(source, previousXrefOffset, visitedOffsets)
                ?: return ParsedXref(entries, trailer)
            val mergedEntries = linkedMapOf<PdfObjectId, PdfXrefEntry>()
            mergedEntries.putAll(previous.entries)
            mergedEntries.putAll(entries)
            val mergedTrailer = PdfObject.Dictionary(previous.trailer.entries + trailer.entries)
            return ParsedXref(mergedEntries, mergedTrailer)
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

        private val WHITESPACE_REGEX = Regex("\\s+")

        private data class ParsedXref(
            val entries: Map<PdfObjectId, PdfXrefEntry>,
            val trailer: PdfObject.Dictionary,
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
