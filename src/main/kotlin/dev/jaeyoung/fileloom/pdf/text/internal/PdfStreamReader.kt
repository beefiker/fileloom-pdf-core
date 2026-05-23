package dev.jaeyoung.fileloom.pdf.text.internal

import dev.jaeyoung.fileloom.pdf.document.PdfLowLevelDocument
import dev.jaeyoung.fileloom.pdf.document.PdfObjectId
import dev.jaeyoung.fileloom.pdf.document.PdfXrefEntry
import dev.jaeyoung.fileloom.pdf.source.PdfByteSource
import dev.jaeyoung.fileloom.pdf.syntax.PdfLexer
import dev.jaeyoung.fileloom.pdf.syntax.PdfObject
import dev.jaeyoung.fileloom.pdf.syntax.PdfObjectParser
import dev.jaeyoung.fileloom.pdf.syntax.PdfToken

/**
 * Reads raw stream bytes for an indirect stream object. Uses PdfLexer to
 * locate `>>` (end of dictionary header) and then reads /Length raw bytes.
 *
 * The parser-core library exposes [PdfLowLevelDocument.resolve] which returns
 * just the dictionary part of a stream object — there is no public API for the
 * payload bytes. We bridge that gap here.
 */
internal object PdfStreamReader {

    /**
     * Resolve and decode the contents stream(s) for a page.
     *
     * /Contents may be a single Reference or an Array of References. The bytes
     * are concatenated (with a newline separator to be safe) before returning.
     * Streams that use unsupported filters yield `null`, and the caller may
     * choose to skip the page.
     */
    fun extractContentStream(
        document: PdfLowLevelDocument,
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

    private fun collectStreamReferences(contents: PdfObject?): List<PdfObject.Reference>? {
        return when (contents) {
            null -> emptyList()
            is PdfObject.Reference -> listOf(contents)
            is PdfObject.ArrayValue -> contents.items.mapNotNull { it as? PdfObject.Reference }
            else -> null
        }
    }

    private fun extractSingleStream(
        document: PdfLowLevelDocument,
        reference: PdfObject.Reference,
    ): ByteArray? {
        val id = PdfObjectId(reference.objectNumber, reference.generationNumber)
        val xrefEntry = document.xrefEntries[id] as? PdfXrefEntry.InUse ?: return null

        val source = currentSource(document) ?: return null
        val lexer = PdfLexer(source, startPosition = xrefEntry.offset)

        // `N G obj` header
        if (lexer.nextToken() !is PdfToken.IntegerNumber) return null
        if (lexer.nextToken() !is PdfToken.IntegerNumber) return null
        val objKeyword = lexer.nextToken() as? PdfToken.Keyword ?: return null
        if (objKeyword.value != "obj") return null

        // Now parse the value. Streams begin with a dictionary, so we look for `<<`.
        val first = lexer.nextToken() as? PdfToken.StartDictionary ?: return null

        val dictionary = parseDictionaryStartingAt(source, first.offset) ?: return null
        val length = streamLength(dictionary) { ref ->
            runCatching { document.resolve(ref) }.getOrNull()
        }
        if (length <= 0) {
            // Either a non-stream object or an unknown length; treat as empty.
            return ByteArray(0)
        }

        // Re-lex from the dictionary start to walk to the EndDictionary token offset.
        val endOffsetInclusive = locateDictionaryEnd(source, first.offset)
        val payloadStart = locateStreamPayloadStart(source, endOffsetInclusive)
        val rawBytes = readBytes(source, payloadStart, length)

        return PdfFilters.decode(rawBytes, dictionary, resolveAny(document))
    }

    private fun resolveAny(document: PdfLowLevelDocument): (PdfObject) -> PdfObject? = { value ->
        if (value is PdfObject.Reference) {
            runCatching { document.resolve(value) }.getOrNull()
        } else value
    }

    /**
     * Re-parse the dictionary at [startOffset] using PdfObjectParser. The
     * parser-core caches resolved indirects, so this only parses raw bytes
     * once per request and stays fast.
     */
    private fun parseDictionaryStartingAt(source: PdfByteSource, startOffset: Long): PdfObject.Dictionary? {
        val lexer = PdfLexer(source, startPosition = startOffset)
        val parser = PdfObjectParser(lexer)
        return runCatching { parser.parseObject() as? PdfObject.Dictionary }.getOrNull()
    }

    /**
     * Walks the lexer through the dictionary at [startOffset] and returns the
     * byte offset of the closing `>>` token.
     */
    private fun locateDictionaryEnd(source: PdfByteSource, startOffset: Long): Long {
        val lexer = PdfLexer(source, startPosition = startOffset)
        var depth = 0
        while (true) {
            val token = lexer.nextToken() ?: error("unexpected EOF in dictionary")
            when (token) {
                is PdfToken.StartDictionary -> depth += 1
                is PdfToken.EndDictionary -> {
                    depth -= 1
                    if (depth == 0) return token.offset + 2 // ">>" is two bytes
                }
                else -> Unit
            }
        }
    }

    /**
     * Skip the EOL after `stream` per PDF spec § 7.3.8.1 — either CRLF or LF.
     * The byte immediately after `>>` should be whitespace, then `stream`,
     * then EOL.
     */
    private fun locateStreamPayloadStart(source: PdfByteSource, dictionaryEnd: Long): Long {
        val window = ByteArray(64)
        val read = source.read(dictionaryEnd, window, 0, window.size.coerceAtMost((source.length - dictionaryEnd).toInt()))
        if (read <= 0) return dictionaryEnd

        // Find "stream".
        val needle = byteArrayOf(
            's'.code.toByte(), 't'.code.toByte(), 'r'.code.toByte(),
            'e'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
        )
        var found = -1
        for (i in 0..read - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (window[i + j] != needle[j]) {
                    match = false; break
                }
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

    /**
     * Extract the [PdfByteSource] backing the document. The parser-core stores
     * it as a private field; we access it through the public API by reading
     * a known-good xref slot and recovering the source via the lexer used
     * during the lookup. Falls back to null if reflection-free access is
     * impossible. We use reflection because this is the only practical
     * interop point with parser-core's private state.
     */
    private fun currentSource(document: PdfLowLevelDocument): PdfByteSource? {
        return try {
            val field = PdfLowLevelDocument::class.java.getDeclaredField("source")
            field.isAccessible = true
            field.get(document) as? PdfByteSource
        } catch (_: Throwable) {
            null
        }
    }
}
