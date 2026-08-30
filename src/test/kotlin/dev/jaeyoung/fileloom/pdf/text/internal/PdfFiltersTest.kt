package dev.jaeyoung.fileloom.pdf.text.internal

import dev.jaeyoung.fileloom.pdf.syntax.PdfObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals

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
