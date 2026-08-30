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
        strictFlate: Boolean = false,
        resolve: (PdfObject) -> PdfObject?,
    ): ByteArray? {
        val rawFilterEntry = streamDictionary.entries["Filter"]
        val filterEntry = rawFilterEntry?.let { resolveIfNeeded(it, resolve) } ?: return rawBytes
        val filters = collectFilterNames(filterEntry, resolve) ?: return null
        val parmsEntry = streamDictionary.entries["DecodeParms"]?.let { resolveIfNeeded(it, resolve) }
        val parmsList = collectParms(parmsEntry, filters.size, resolve)

        var current = rawBytes
        for ((index, filter) in filters.withIndex()) {
            val parms = parmsList.getOrNull(index)
            current = applyFilter(filter, current, parms, strictFlate) ?: return null
        }
        return current
    }

    private fun applyFilter(
        filter: String,
        bytes: ByteArray,
        parms: PdfObject.Dictionary?,
        strictFlate: Boolean,
    ): ByteArray? = when (filter) {
        "FlateDecode", "Fl" -> flateDecode(bytes, strictFlate)?.let { applyPredictor(it, parms) }
        "ASCIIHexDecode", "AHx" -> asciiHexDecode(bytes)
        "ASCII85Decode", "A85" -> ascii85Decode(bytes)
        else -> null // LZWDecode, RunLengthDecode, CCITTFaxDecode, JBIG2Decode, DCTDecode, JPXDecode
    }

    private fun flateDecode(bytes: ByteArray, strict: Boolean): ByteArray? {
        val inflater = Inflater()
        inflater.setInput(bytes)
        val output = ByteArrayOutputStream(bytes.size * 4)
        val buffer = ByteArray(4096)
        var failed = false
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
            failed = true
        } finally {
            val complete = inflater.finished()
            inflater.end()
            if (strict && (failed || !complete)) return null
        }
        return output.toByteArray()
    }

    private fun applyPredictor(bytes: ByteArray, parms: PdfObject.Dictionary?): ByteArray? {
        if (parms == null) return bytes
        val predictor = (parms.entries["Predictor"] as? PdfObject.IntegerValue)?.value?.toInt() ?: 1
        if (predictor <= 1) return bytes
        val colors = (parms.entries["Colors"] as? PdfObject.IntegerValue)?.value?.toInt() ?: 1
        val columns = (parms.entries["Columns"] as? PdfObject.IntegerValue)?.value?.toInt() ?: 1
        val bitsPerComponent = (parms.entries["BitsPerComponent"] as? PdfObject.IntegerValue)
            ?.value
            ?.toInt()
            ?: 8
        if (colors <= 0 || columns <= 0 || bitsPerComponent <= 0) return null
        val rowBits = colors.toLong() * columns.toLong() * bitsPerComponent.toLong()
        val pixelBits = colors.toLong() * bitsPerComponent.toLong()
        val rowBytes = ((rowBits + 7L) / 8L).takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: return null
        val bytesPerPixel = ((pixelBits + 7L) / 8L).takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: return null
        return when (predictor) {
            2 -> decodeTiffPredictor(bytes, rowBytes, colors, columns, bitsPerComponent)
            in 10..15 -> decodePngPredictor(bytes, rowBytes, bytesPerPixel, predictor)
            else -> null
        }
    }

    private fun decodeTiffPredictor(
        bytes: ByteArray,
        rowBytes: Int,
        colors: Int,
        columns: Int,
        bitsPerComponent: Int,
    ): ByteArray? {
        if (bitsPerComponent !in setOf(1, 2, 4, 8, 16) || bytes.size % rowBytes != 0) return null
        val samplesPerRow = colors.toLong() * columns.toLong()
        if (samplesPerRow !in 1..Int.MAX_VALUE) return null
        val sampleCount = samplesPerRow.toInt()
        val sampleMask = (1 shl bitsPerComponent) - 1
        val decoded = ByteArray(bytes.size)
        for (rowStart in bytes.indices step rowBytes) {
            val samples = IntArray(sampleCount)
            for (sampleIndex in 0 until sampleCount) {
                samples[sampleIndex] = readBits(
                    bytes = bytes,
                    bitOffset = rowStart * 8 + sampleIndex * bitsPerComponent,
                    bitCount = bitsPerComponent,
                )
                if (sampleIndex >= colors) {
                    samples[sampleIndex] = (samples[sampleIndex] + samples[sampleIndex - colors]) and sampleMask
                }
                writeBits(
                    bytes = decoded,
                    bitOffset = rowStart * 8 + sampleIndex * bitsPerComponent,
                    bitCount = bitsPerComponent,
                    value = samples[sampleIndex],
                )
            }
        }
        return decoded
    }

    private fun readBits(bytes: ByteArray, bitOffset: Int, bitCount: Int): Int {
        var value = 0
        repeat(bitCount) { relativeBit ->
            val absoluteBit = bitOffset + relativeBit
            val bit = (bytes[absoluteBit / 8].toInt() ushr (7 - absoluteBit % 8)) and 1
            value = (value shl 1) or bit
        }
        return value
    }

    private fun writeBits(bytes: ByteArray, bitOffset: Int, bitCount: Int, value: Int) {
        repeat(bitCount) { relativeBit ->
            val absoluteBit = bitOffset + relativeBit
            val mask = 1 shl (7 - absoluteBit % 8)
            val bit = (value ushr (bitCount - relativeBit - 1)) and 1
            val byteIndex = absoluteBit / 8
            bytes[byteIndex] = if (bit == 1) {
                (bytes[byteIndex].toInt() or mask).toByte()
            } else {
                (bytes[byteIndex].toInt() and mask.inv()).toByte()
            }
        }
    }

    private fun decodePngPredictor(
        bytes: ByteArray,
        rowBytes: Int,
        bytesPerPixel: Int,
        predictor: Int,
    ): ByteArray? {
        val encodedRowBytes = rowBytes + 1
        if (encodedRowBytes <= 0 || bytes.size % encodedRowBytes != 0) return null
        val rowCount = bytes.size / encodedRowBytes
        val decodedSize = rowCount.toLong() * rowBytes.toLong()
        if (decodedSize > Int.MAX_VALUE) return null
        val decoded = ByteArray(decodedSize.toInt())
        var sourceOffset = 0
        for (row in 0 until rowCount) {
            val filter = bytes[sourceOffset++].toInt() and 0xff
            if (filter !in 0..4) return null
            val rowStart = row * rowBytes
            for (column in 0 until rowBytes) {
                val raw = bytes[sourceOffset++].toInt() and 0xff
                val left = if (column >= bytesPerPixel) {
                    decoded[rowStart + column - bytesPerPixel].toInt() and 0xff
                } else {
                    0
                }
                val up = if (row > 0) decoded[rowStart + column - rowBytes].toInt() and 0xff else 0
                val upperLeft = if (row > 0 && column >= bytesPerPixel) {
                    decoded[rowStart + column - rowBytes - bytesPerPixel].toInt() and 0xff
                } else {
                    0
                }
                val predicted = when (filter) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> paethPredictor(left, up, upperLeft)
                    else -> return null
                }
                decoded[rowStart + column] = ((raw + predicted) and 0xff).toByte()
            }
        }
        return decoded
    }

    private fun paethPredictor(left: Int, up: Int, upperLeft: Int): Int {
        val estimate = left + up - upperLeft
        val leftDistance = kotlin.math.abs(estimate - left)
        val upDistance = kotlin.math.abs(estimate - up)
        val upperLeftDistance = kotlin.math.abs(estimate - upperLeft)
        return when {
            leftDistance <= upDistance && leftDistance <= upperLeftDistance -> left
            upDistance <= upperLeftDistance -> up
            else -> upperLeft
        }
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

    private fun collectFilterNames(
        value: PdfObject?,
        resolve: (PdfObject) -> PdfObject?,
    ): List<String>? {
        return when (value) {
            null -> null
            is PdfObject.Name -> listOf(value.value)
            is PdfObject.ArrayValue -> value.items.map { item ->
                (resolveIfNeeded(item, resolve) as? PdfObject.Name)?.value ?: return null
            }
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
