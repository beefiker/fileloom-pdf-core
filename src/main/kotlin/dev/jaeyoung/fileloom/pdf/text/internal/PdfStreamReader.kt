package dev.jaeyoung.fileloom.pdf.text.internal

import dev.jaeyoung.fileloom.pdf.document.PdfObjectId
import dev.jaeyoung.fileloom.pdf.document.PdfXrefEntry
import dev.jaeyoung.fileloom.pdf.source.PdfByteSource
import dev.jaeyoung.fileloom.pdf.syntax.PdfLexer
import dev.jaeyoung.fileloom.pdf.syntax.PdfObject
import dev.jaeyoung.fileloom.pdf.syntax.PdfObjectParser
import dev.jaeyoung.fileloom.pdf.syntax.PdfToken

/**
 * Reads raw stream bytes for an indirect stream object and applies the
 * `/Filter` pipeline.
 */
internal object PdfStreamReader {

    fun extractContentStream(
        document: PdfDocument,
        contents: PdfObject?,
    ): ByteArray? {
        val references = collectStreamReferences(contents) ?: return null
        if (references.isEmpty()) return ByteArray(0)
        val pieces = mutableListOf<ByteArray>()
        for (reference in references) {
            val piece = extractSingleStream(document, reference) ?: return null
            pieces += piece
        }
        return joinWithNewline(pieces)
    }

    fun extractStream(
        document: PdfDocument,
        reference: PdfObject.Reference,
    ): ByteArray? = extractSingleStream(document, reference)

    private fun collectStreamReferences(contents: PdfObject?): List<PdfObject.Reference>? {
        return when (contents) {
            null -> emptyList()
            is PdfObject.Reference -> listOf(contents)
            is PdfObject.ArrayValue -> contents.items.mapNotNull { it as? PdfObject.Reference }
            else -> null
        }
    }

    private fun extractSingleStream(
        document: PdfDocument,
        reference: PdfObject.Reference,
    ): ByteArray? {
        val id = PdfObjectId(reference.objectNumber, reference.generationNumber)
        val xrefEntry = document.xrefEntries[id] as? PdfXrefEntry.InUse ?: return null

        val source = document.source
        val lexer = PdfLexer(source, startPosition = xrefEntry.offset)

        if (lexer.nextToken() !is PdfToken.IntegerNumber) return null
        if (lexer.nextToken() !is PdfToken.IntegerNumber) return null
        val objKeyword = lexer.nextToken() as? PdfToken.Keyword ?: return null
        if (objKeyword.value != "obj") return null

        val first = lexer.nextToken() as? PdfToken.StartDictionary ?: return null
        val dictionary = parseDictionaryStartingAt(source, first.offset) ?: return null
        val length = streamLength(dictionary) { ref -> document.resolve(ref) }
        if (length <= 0) return ByteArray(0)

        val endOffsetInclusive = locateDictionaryEnd(source, first.offset)
        val payloadStart = locateStreamPayloadStart(source, endOffsetInclusive)
        val rawBytes = readBytes(source, payloadStart, length)

        return PdfFilters.decode(rawBytes, dictionary, resolveAny(document))
    }

    private fun resolveAny(document: PdfDocument): (PdfObject) -> PdfObject? = { value ->
        document.deref(value)
    }

    private fun parseDictionaryStartingAt(source: PdfByteSource, startOffset: Long): PdfObject.Dictionary? {
        val lexer = PdfLexer(source, startPosition = startOffset)
        val parser = PdfObjectParser(lexer)
        return runCatching { parser.parseObject() as? PdfObject.Dictionary }.getOrNull()
    }

    private fun locateDictionaryEnd(source: PdfByteSource, startOffset: Long): Long {
        val lexer = PdfLexer(source, startPosition = startOffset)
        var depth = 0
        while (true) {
            val token = lexer.nextToken() ?: error("unexpected EOF in dictionary")
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
        val window = ByteArray(64)
        val remaining = (source.length - dictionaryEnd).toInt()
        val read = source.read(dictionaryEnd, window, 0, window.size.coerceAtMost(remaining))
        if (read <= 0) return dictionaryEnd

        val needle = byteArrayOf(
            's'.code.toByte(), 't'.code.toByte(), 'r'.code.toByte(),
            'e'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
        )
        var found = -1
        for (i in 0..read - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (window[i + j] != needle[j]) { match = false; break }
            }
            if (match) { found = i; break }
        }
        if (found < 0) return dictionaryEnd

        var cursor = dictionaryEnd + found + needle.size
        val one = ByteArray(1)
        if (source.read(cursor, one, 0, 1) == 1 && one[0] == 0x0d.toByte()) cursor += 1
        if (source.read(cursor, one, 0, 1) == 1 && one[0] == 0x0a.toByte()) cursor += 1
        return cursor
    }

    private fun readBytes(source: PdfByteSource, start: Long, count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = source.read(start + read, out, read, count - read)
            if (n <= 0) break
            read += n
        }
        return if (read == count) out else out.copyOf(read)
    }

    private fun streamLength(
        dictionary: PdfObject.Dictionary,
        resolve: (PdfObject.Reference) -> PdfObject?,
    ): Int {
        val raw = dictionary.entries["Length"] ?: return 0
        val resolved = if (raw is PdfObject.Reference) resolve(raw) ?: return 0 else raw
        return when (resolved) {
            is PdfObject.IntegerValue -> resolved.value.toInt().coerceAtLeast(0)
            else -> 0
        }
    }

    private fun joinWithNewline(pieces: List<ByteArray>): ByteArray {
        if (pieces.size == 1) return pieces[0]
        val totalSize = pieces.sumOf { it.size } + pieces.size - 1
        val out = ByteArray(totalSize)
        var pos = 0
        pieces.forEachIndexed { index, bytes ->
            System.arraycopy(bytes, 0, out, pos, bytes.size)
            pos += bytes.size
            if (index < pieces.size - 1) {
                out[pos] = '\n'.code.toByte()
                pos += 1
            }
        }
        return out
    }
}
