package dev.jaeyoung.fileloom.pdf.illustrator

import dev.jaeyoung.fileloom.pdf.source.ByteArrayPdfByteSource
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfIllustratorPrivateDataExtractorTest {
    @Test
    fun extractsDirectPrivateDataBlocksInNumericOrder() {
        val pdf = syntheticPdf(
            privateDictionary = """
                /NumBlock 2
                /AIPrivateData2 (block-two)
                /AIPrivateData1 (block-one)
            """.trimIndent()
        )

        val blocks = PdfIllustratorPrivateDataExtractor.extractBlocks(ByteArrayPdfByteSource(pdf))

        assertEquals(
            listOf("block-one", "block-two"),
            blocks.map { it.toString(StandardCharsets.ISO_8859_1) }
        )
    }

    @Test
    fun fallsBackToNumericBlockNamesWhenNumBlockIsInconsistent() {
        val pdf = syntheticPdf(
            privateDictionary = """
                /NumBlock 0
                /AIPrivateData2 (block-two)
                /AIPrivateData1 (block-one)
            """.trimIndent()
        )

        val blocks = PdfIllustratorPrivateDataExtractor.extractBlocks(ByteArrayPdfByteSource(pdf))

        assertEquals(
            listOf("block-one", "block-two"),
            blocks.map { it.toString(StandardCharsets.ISO_8859_1) }
        )
    }

    @Test
    fun fallsBackToNumericBlockNamesWhenNumBlockCountIsTooSmall() {
        val pdf = syntheticPdf(
            privateDictionary = """
                /NumBlock 1
                /AIPrivateData2 (block-two)
                /AIPrivateData1 (block-one)
            """.trimIndent()
        )

        val blocks = PdfIllustratorPrivateDataExtractor.extractBlocks(ByteArrayPdfByteSource(pdf))

        assertEquals(
            listOf("block-one", "block-two"),
            blocks.map { it.toString(StandardCharsets.ISO_8859_1) }
        )
    }

    @Test
    fun extractsAndDecodesIndirectStreamBlocks() {
        val compressed = deflate("stream-block".toByteArray(StandardCharsets.ISO_8859_1))
        val pdf = syntheticPdf(
            privateDictionary = """
                /NumBlock 1
                /AIPrivateData1 4 0 R
            """.trimIndent(),
            extraObjects = listOf(streamObject(objectNumber = 4, bytes = compressed, filter = "/FlateDecode"))
        )

        val blocks = PdfIllustratorPrivateDataExtractor.extractBlocks(ByteArrayPdfByteSource(pdf))

        assertEquals(listOf("stream-block"), blocks.map { it.toString(StandardCharsets.ISO_8859_1) })
    }

    @Test
    fun decodesStreamBlocksWithIndirectFilterNamesInArrays() {
        val compressed = deflate("indirect-filter-block".toByteArray(StandardCharsets.ISO_8859_1))
        val pdf = syntheticPdf(
            privateDictionary = """
                /NumBlock 1
                /AIPrivateData1 4 0 R
            """.trimIndent(),
            extraObjects = listOf(
                streamObject(objectNumber = 4, bytes = compressed, filter = "[5 0 R]"),
                "5 0 obj\n/FlateDecode\nendobj\n"
            )
        )

        val blocks = PdfIllustratorPrivateDataExtractor.extractBlocks(ByteArrayPdfByteSource(pdf))

        assertEquals(listOf("indirect-filter-block"), blocks.map { it.toString(StandardCharsets.ISO_8859_1) })
    }

    @Test
    fun extractsPrivateDataWhenPieceInfoIllustratorAndPrivateDictionariesAreIndirect() {
        val pdf = syntheticPdfWithIndirectPrivateDictionaries(
            privateDictionary = """
                /NumBlock 1
                /AIPrivateData1 (indirect-private-dictionary-block)
            """.trimIndent()
        )

        val blocks = PdfIllustratorPrivateDataExtractor.extractBlocks(ByteArrayPdfByteSource(pdf))

        assertEquals(
            listOf("indirect-private-dictionary-block"),
            blocks.map { it.toString(StandardCharsets.ISO_8859_1) }
        )
    }

    @Test
    fun extractsFirstAvailablePrivateDataWhenFirstPageHasNoIllustratorMetadata() {
        val pdf = syntheticTwoPagePdf(
            secondPagePrivateDictionary = """
                /NumBlock 1
                /AIPrivateData1 (second-page-block)
            """.trimIndent()
        )

        val blocks = PdfIllustratorPrivateDataExtractor.extractFirstAvailableBlocks(ByteArrayPdfByteSource(pdf))

        assertEquals(listOf("second-page-block"), blocks.map { it.toString(StandardCharsets.ISO_8859_1) })
    }

    private fun syntheticPdf(
        privateDictionary: String,
        extraObjects: List<String> = emptyList(),
    ): ByteArray {
        val objects = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            """
                3 0 obj
                << /Type /Page /Parent 2 0 R /MediaBox [0 0 10 10]
                   /PieceInfo <<
                     /Illustrator <<
                       /Private <<
                         $privateDictionary
                       >>
                     >>
                   >>
                >>
                endobj
            """.trimIndent() + "\n",
        ) + extraObjects

        return writePdf(objects)
    }

    private fun syntheticPdfWithIndirectPrivateDictionaries(privateDictionary: String): ByteArray {
        val objects = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            """
                3 0 obj
                << /Type /Page /Parent 2 0 R /MediaBox [0 0 10 10]
                   /PieceInfo 4 0 R
                >>
                endobj
            """.trimIndent() + "\n",
            "4 0 obj\n<< /Illustrator 5 0 R >>\nendobj\n",
            "5 0 obj\n<< /Private 6 0 R >>\nendobj\n",
            """
                6 0 obj
                <<
                  $privateDictionary
                >>
                endobj
            """.trimIndent() + "\n",
        )
        return writePdf(objects)
    }

    private fun syntheticTwoPagePdf(secondPagePrivateDictionary: String): ByteArray {
        val objects = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>\nendobj\n",
            """
                3 0 obj
                << /Type /Page /Parent 2 0 R /MediaBox [0 0 10 10] >>
                endobj
            """.trimIndent() + "\n",
            """
                4 0 obj
                << /Type /Page /Parent 2 0 R /MediaBox [0 0 10 10]
                   /PieceInfo <<
                     /Illustrator <<
                       /Private <<
                         $secondPagePrivateDictionary
                       >>
                     >>
                   >>
                >>
                endobj
            """.trimIndent() + "\n",
        )
        return writePdf(objects)
    }

    private fun writePdf(objects: List<String>): ByteArray {
        val output = ByteArrayOutputStream()
        val offsets = mutableListOf<Long>()
        output.write("%PDF-1.4\n".toByteArray(StandardCharsets.ISO_8859_1))
        objects.forEach { objectText ->
            offsets += output.size().toLong()
            output.write(objectText.toByteArray(StandardCharsets.ISO_8859_1))
        }

        val xrefOffset = output.size().toLong()
        output.write("xref\n0 ${objects.size + 1}\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write("0000000000 65535 f \n".toByteArray(StandardCharsets.ISO_8859_1))
        offsets.forEach { offset ->
            output.write("${offset.toString().padStart(10, '0')} 00000 n \n".toByteArray(StandardCharsets.ISO_8859_1))
        }
        output.write(
            """
                trailer
                << /Size ${objects.size + 1} /Root 1 0 R >>
                startxref
                $xrefOffset
                %%EOF
            """.trimIndent().toByteArray(StandardCharsets.ISO_8859_1)
        )
        return output.toByteArray()
    }

    private fun streamObject(
        objectNumber: Int,
        bytes: ByteArray,
        filter: String,
    ): String {
        return buildString {
            append("$objectNumber 0 obj\n")
            append("<< /Length ${bytes.size} /Filter $filter >>\n")
            append("stream\n")
            append(bytes.toString(StandardCharsets.ISO_8859_1))
            append("\nendstream\nendobj\n")
        }
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val output = ByteArray(bytes.size * 2 + 64)
        val length = deflater.deflate(output)
        deflater.end()
        return output.copyOf(length)
    }
}
