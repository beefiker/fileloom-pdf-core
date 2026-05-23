package dev.jaeyoung.fileloom.pdf.text.internal

import dev.jaeyoung.fileloom.pdf.syntax.PdfObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Applies a PDF `/Filter` chain to a stream's raw bytes.
 *
 * Supported (v0.1): `FlateDecode`, `ASCIIHexDecode`, `ASCII85Decode`.
 * Unsupported filters return null so the caller can skip the page gracefully.
 */
internal object PdfFilters {

    fun decode(
        rawBytes: ByteArray,
        streamDictionary: PdfObject.Dictionary,
        resolve: (PdfObject) -> PdfObject?,
    ): ByteArray? {
        val filterEntry = streamDictionary.entries["Filter"]?.let { resolveIfNeeded(it, resolve) }
        val filters = collectFilterNames(filterEntry) ?: return rawBytes
        val parmsEntry = streamDictionary.entries["DecodeParms"]?.let { resolveIfNeeded(it, resolve) }
        val parmsList = collectParms(parmsEntry, filters.size, resolve)

        var current = rawBytes
        for ((index, filter) in filters.withIndex()) {
            val parms = parmsList.getOrNull(index)
            current = applyFilter(filter, current, parms) ?: return null
        }
        return current
    }

    private fun applyFilter(
        filter: String,
        bytes: ByteArray,
        parms: PdfObject.Dictionary?,
    ): ByteArray? = when (filter) {
        "FlateDecode", "Fl" -> applyPredictor(flateDecode(bytes), parms)
        "ASCIIHexDecode", "AHx" -> asciiHexDecode(bytes)
        "ASCII85Decode", "A85" -> ascii85Decode(bytes)
        else -> null // LZWDecode, RunLengthDecode, CCITTFaxDecode, JBIG2Decode, DCTDecode, JPXDecode
    }

    private fun flateDecode(bytes: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(bytes)
        val output = ByteArrayOutputStream(bytes.size * 4)
        val buffer = ByteArray(4096)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                if (n <= 0) break
                output.write(buffer, 0, n)
            }
        } catch (_: java.util.zip.DataFormatException) {
            // Tolerate trailing garbage some encoders emit; return what we have.
        } finally {
            inflater.end()
        }
        return output.toByteArray()
    }

    /**
     * PDF PNG-up predictor (12-15) used after FlateDecode for some streams.
     * Only "predictor 1" (no prediction) and "predictor 12" (PNG up) are
     * commonly seen on text content streams; we support no-op (1) and
     * pass-through for anything else (cross fingers — most content streams
     * don't use predictors at all).
     */
    private fun applyPredictor(bytes: ByteArray, parms: PdfObject.Dictionary?): ByteArray {
        if (parms == null) return bytes
        val predictor = (parms.entries["Predictor"] as? PdfObject.IntegerValue)?.value?.toInt() ?: 1
        if (predictor <= 1) return bytes
        // Predictor 12: each row begins with a 1-byte tag, followed by `columns` bytes;
        // we just strip the row tags and concatenate. Imperfect but adequate for text.
        val columns = (parms.entries["Columns"] as? PdfObject.IntegerValue)?.value?.toInt() ?: 1
        if (columns <= 0) return bytes
        val rowSize = columns + 1
        if (bytes.size < rowSize) return bytes
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + rowSize, bytes.size)
            if (end - i > 1) out.write(bytes, i + 1, end - i - 1)
            i += rowSize
        }
        return out.toByteArray()
    }

    private fun asciiHexDecode(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 2)
        var pending = -1
        for (b in bytes) {
            val v = b.toInt() and 0xff
            if (v.toChar() == '>') break
            val digit = hexDigit(v)
            if (digit < 0) continue
            if (pending < 0) {
                pending = digit
            } else {
                out.write((pending shl 4) or digit)
                pending = -1
            }
        }
        if (pending >= 0) out.write(pending shl 4)
        return out.toByteArray()
    }

    private fun hexDigit(byte: Int): Int = when (byte.toChar()) {
        in '0'..'9' -> byte - '0'.code
        in 'a'..'f' -> byte - 'a'.code + 10
        in 'A'..'F' -> byte - 'A'.code + 10
        else -> -1
    }

    private fun ascii85Decode(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        val stream = ByteArrayInputStream(bytes)
        if (stream.read() == '<'.code) {
            if (stream.read() != '~'.code) stream.reset()
        } else {
            stream.reset()
        }
        val group = IntArray(5)
        var groupSize = 0
        loop@ while (true) {
            val ch = stream.read()
            if (ch < 0) break
            when (val c = ch.toChar()) {
                in '!'..'u' -> {
                    group[groupSize++] = c.code - '!'.code
                    if (groupSize == 5) {
                        emitAscii85Group(out, group, 5)
                        groupSize = 0
                    }
                }
                'z' -> {
                    if (groupSize != 0) return out.toByteArray() // spec violation
                    out.write(0); out.write(0); out.write(0); out.write(0)
                }
                '~' -> {
                    // ~> terminator
                    break@loop
                }
                in " \t\n\r" -> Unit
                else -> Unit
            }
        }
        if (groupSize > 1) emitAscii85Group(out, group, groupSize)
        return out.toByteArray()
    }

    private fun emitAscii85Group(out: ByteArrayOutputStream, group: IntArray, size: Int) {
        val padded = IntArray(5) { if (it < size) group[it] else 84 } // 84 = 'u' - '!'
        var value = 0L
        for (i in 0..4) {
            value = value * 85 + padded[i]
        }
        val bytes = byteArrayOf(
            ((value shr 24) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            (value and 0xff).toByte(),
        )
        val emit = size - 1
        out.write(bytes, 0, emit)
    }

    private fun collectFilterNames(value: PdfObject?): List<String>? {
        return when (value) {
            null -> null
            is PdfObject.Name -> listOf(value.value)
            is PdfObject.ArrayValue -> value.items.mapNotNull { (it as? PdfObject.Name)?.value }
            else -> null
        }
    }

    private fun collectParms(
        value: PdfObject?,
        filterCount: Int,
        resolve: (PdfObject) -> PdfObject?,
    ): List<PdfObject.Dictionary?> {
        return when (value) {
            null -> emptyList()
            is PdfObject.Dictionary -> listOf(value)
            is PdfObject.ArrayValue -> value.items.map { item ->
                resolveIfNeeded(item, resolve) as? PdfObject.Dictionary
            }
            else -> List(filterCount) { null }
        }
    }

    private fun resolveIfNeeded(value: PdfObject, resolve: (PdfObject) -> PdfObject?): PdfObject =
        if (value is PdfObject.Reference) resolve(value) ?: value else value
}
