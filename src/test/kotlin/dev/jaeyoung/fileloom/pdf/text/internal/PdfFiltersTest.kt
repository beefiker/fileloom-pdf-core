package dev.jaeyoung.fileloom.pdf.text.internal

import dev.jaeyoung.fileloom.pdf.syntax.PdfObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

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
}
