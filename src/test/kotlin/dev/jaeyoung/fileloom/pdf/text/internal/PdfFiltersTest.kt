package dev.jaeyoung.fileloom.pdf.text.internal

import com.sun.management.ThreadMXBean
import dev.jaeyoung.fileloom.pdf.syntax.PdfObject
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfFiltersTest {
    @Test
    fun decodesOneBitTiffHorizontalDifferencing() {
        val original = byteArrayOf(0b11010011.toByte())
        val encoded = byteArrayOf(0b10111010.toByte())
        val dictionary = PdfObject.Dictionary(
            mapOf(
                "Filter" to PdfObject.Name("FlateDecode"),
                "DecodeParms" to PdfObject.Dictionary(
                    mapOf(
                        "Predictor" to PdfObject.IntegerValue(2),
                        "Colors" to PdfObject.IntegerValue(1),
                        "Columns" to PdfObject.IntegerValue(8),
                        "BitsPerComponent" to PdfObject.IntegerValue(1),
                    )
                ),
            )
        )

        val decoded = PdfFilters.decode(deflate(encoded), dictionary) { null }

        assertContentEquals(original, decoded)
    }

    @Test
    fun rejectsFlateOutputBeyondConfiguredLimit() {
        val dictionary = PdfObject.Dictionary(
            mapOf("Filter" to PdfObject.Name("FlateDecode"))
        )

        val decoded = PdfFilters.decode(
            rawBytes = deflate(ByteArray(4096)),
            streamDictionary = dictionary,
            maxDecodedBytes = 128,
            resolve = { null },
        )

        assertNull(decoded)
    }

    @Test
    fun allowsPngPredictorFramingBeyondFinalDecodedLimit() {
        val decodedRow = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val encodedRow = byteArrayOf(0) + decodedRow
        val dictionary = PdfObject.Dictionary(
            mapOf(
                "Filter" to PdfObject.Name("FlateDecode"),
                "DecodeParms" to PdfObject.Dictionary(
                    mapOf(
                        "Predictor" to PdfObject.IntegerValue(12),
                        "Colors" to PdfObject.IntegerValue(1),
                        "Columns" to PdfObject.IntegerValue(7),
                        "BitsPerComponent" to PdfObject.IntegerValue(8),
                    )
                ),
            )
        )

        val decoded = PdfFilters.decode(
            rawBytes = deflate(encodedRow),
            streamDictionary = dictionary,
            maxDecodedBytes = decodedRow.size,
            resolve = { null },
        )

        assertContentEquals(decodedRow, decoded)
    }

    @Test
    fun allowsChainedFilterIntermediateOutputBeyondFinalLimit() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val dictionary = PdfObject.Dictionary(
            mapOf(
                "Filter" to PdfObject.ArrayValue(
                    listOf(
                        PdfObject.Name("ASCII85Decode"),
                        PdfObject.Name("FlateDecode"),
                    )
                )
            )
        )

        val decoded = PdfFilters.decode(
            rawBytes = ascii85Encode(deflate(original)),
            streamDictionary = dictionary,
            strictFlate = true,
            maxDecodedBytes = original.size,
            resolve = { null },
        )

        assertContentEquals(original, decoded)
    }

    @Test
    fun wideOneBitTiffPredictorKeepsAllocationProportionalToDecodedBytes() {
        val columns = 1_000_000
        val encoded = ByteArray((columns + 7) / 8)
        val dictionary = PdfObject.Dictionary(
            mapOf(
                "Filter" to PdfObject.Name("FlateDecode"),
                "DecodeParms" to PdfObject.Dictionary(
                    mapOf(
                        "Predictor" to PdfObject.IntegerValue(2),
                        "Colors" to PdfObject.IntegerValue(1),
                        "Columns" to PdfObject.IntegerValue(columns.toLong()),
                        "BitsPerComponent" to PdfObject.IntegerValue(1),
                    )
                ),
            )
        )
        val compressed = deflate(encoded)
        val allocationBean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return
        if (!allocationBean.isThreadAllocatedMemorySupported) return
        if (!allocationBean.isThreadAllocatedMemoryEnabled) {
            allocationBean.isThreadAllocatedMemoryEnabled = true
        }
        PdfFilters.decode(
            rawBytes = compressed,
            streamDictionary = dictionary,
            maxDecodedBytes = encoded.size,
            resolve = { null },
        )

        val before = allocationBean.getThreadAllocatedBytes(Thread.currentThread().id)
        val decoded = PdfFilters.decode(
            rawBytes = compressed,
            streamDictionary = dictionary,
            maxDecodedBytes = encoded.size,
            resolve = { null },
        )
        val allocatedBytes = allocationBean.getThreadAllocatedBytes(Thread.currentThread().id) - before
        val maxAllocatedBytes = encoded.size.toLong() * 32L

        assertContentEquals(encoded, decoded)
        assertTrue(
            allocatedBytes < maxAllocatedBytes,
            "expected allocation below $maxAllocatedBytes bytes, allocated $allocatedBytes",
        )
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.write(buffer, 0, count)
        }
        deflater.end()
        return output.toByteArray()
    }

    private fun ascii85Encode(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(4, bytes.size - offset)
            var value = 0L
            repeat(4) { index ->
                val byte = if (index < count) bytes[offset + index].toInt() and 0xff else 0
                value = (value shl 8) or byte.toLong()
            }
            val digits = IntArray(5)
            for (index in 4 downTo 0) {
                digits[index] = (value % 85L).toInt() + '!'.code
                value /= 85L
            }
            repeat(count + 1) { output.write(digits[it]) }
            offset += count
        }
        return output.toByteArray()
    }
}
