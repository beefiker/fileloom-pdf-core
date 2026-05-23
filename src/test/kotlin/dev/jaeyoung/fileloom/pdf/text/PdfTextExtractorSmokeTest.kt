package dev.jaeyoung.fileloom.pdf.text

import dev.jaeyoung.fileloom.pdf.source.ByteArrayPdfByteSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PdfTextExtractorSmokeTest {

    @Test
    fun extractsSimpleHelloWorldText() {
        val pdfBytes = SyntheticPdfBuilder.helloWorld()
        val extractor = PdfTextExtractor.open(ByteArrayPdfByteSource(pdfBytes))
        assertNotNull(extractor, "extractor should open synthetic PDF")
        extractor.use {
            assertEquals(1, it.pageCount)
            val text = it.extractTextForPage(0)
            assertTrue("Hello, World!" in text, "expected 'Hello, World!' in extracted text, got: '$text'")
        }
    }

    @Test
    fun returnsEmptyStringForOutOfBoundsPage() {
        val pdfBytes = SyntheticPdfBuilder.helloWorld()
        val extractor = PdfTextExtractor.open(ByteArrayPdfByteSource(pdfBytes))
        assertNotNull(extractor)
        extractor.use {
            assertEquals("", it.extractTextForPage(99))
        }
    }

    @Test
    fun handlesMultiLineText() {
        val pdfBytes = SyntheticPdfBuilder.twoLines()
        val extractor = PdfTextExtractor.open(ByteArrayPdfByteSource(pdfBytes))
        assertNotNull(extractor)
        extractor.use {
            val text = it.extractTextForPage(0)
            assertTrue("Line one" in text, "missing Line one in '$text'")
            assertTrue("Line two" in text, "missing Line two in '$text'")
        }
    }

    @Test
    fun refusesEncryptedPdf() {
        val pdfBytes = SyntheticPdfBuilder.encryptedStub()
        val extractor = PdfTextExtractor.open(ByteArrayPdfByteSource(pdfBytes))
        assertEquals(null, extractor, "encrypted PDFs should yield null")
    }
}
