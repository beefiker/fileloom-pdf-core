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

    /**
     * Regression for the arXiv/LaTeX-style trailer layout that
     * `fileloom-pdf-parser-core:0.3.0`'s `PdfDocumentReader` rejected with
     * "invalid xref subsection header 'trailer << ...'". Real-world
     * surfaced by `Evaluating AGENTS.md.pdf` in 0.1.0; fixed in 0.1.1 by
     * the lenient internal `PdfDocument`.
     */
    @Test
    fun handlesTrailerKeywordOnSameLineAsDictionary() {
        val pdfBytes = SyntheticPdfBuilder.singleLineTrailer()
        val extractor = PdfTextExtractor.open(ByteArrayPdfByteSource(pdfBytes))
        assertNotNull(extractor, "single-line trailer PDFs must open (regression for 0.1.0 bug)")
        extractor.use {
            assertEquals(1, it.pageCount)
            val text = it.extractTextForPage(0)
            assertTrue(
                "Inline trailer page" in text,
                "expected extracted text from single-line-trailer PDF, got: '$text'",
            )
        }
    }
}
