package dev.jaeyoung.fileloom.pdf.annotation

import dev.jaeyoung.fileloom.pdf.source.ByteArrayPdfByteSource
import dev.jaeyoung.fileloom.pdf.text.PdfTextExtractor
import dev.jaeyoung.fileloom.pdf.text.SyntheticPdfBuilder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class PdfAnnotationWriterTest {

    @Test
    fun appendsHighlightAndStickyNoteAnnotationsWithoutBreakingReadablePdf() {
        val original = SyntheticPdfBuilder.helloWorld()

        val annotated = PdfAnnotationWriter.appendAnnotations(
            pdfBytes = original,
            annotations = listOf(
                PdfAnnotation.Highlight(
                    pageIndex = 0,
                    rects = listOf(PdfAnnotationRect(left = 96f, top = 84f, right = 260f, bottom = 110f)),
                    color = PdfAnnotationColor(red = 1f, green = 0.92f, blue = 0.23f),
                    contents = "Important"
                ),
                PdfAnnotation.StickyNote(
                    pageIndex = 0,
                    x = 280f,
                    y = 96f,
                    color = PdfAnnotationColor(red = 0.2f, green = 0.56f, blue = 1f),
                    contents = "Review this"
                )
            )
        )

        val raw = annotated.toString(StandardCharsets.ISO_8859_1)
        assertTrue(raw.contains("/Subtype /Highlight"), "expected a PDF highlight annotation object")
        assertTrue(raw.contains("/Subtype /Text"), "expected a PDF sticky note annotation object")
        assertTrue(raw.contains("/Annots"), "expected the target page to receive an /Annots array")

        val extractor = PdfTextExtractor.open(ByteArrayPdfByteSource(annotated))
        assertNotNull(extractor, "annotated incremental PDF should still be readable")
        extractor.use {
            assertTrue("Hello, World!" in it.extractTextForPage(0))
        }
    }

    @Test
    fun allocatesAnnotationObjectsAfterHighCompressedObjectNumbers() {
        val annotated = PdfAnnotationWriter.appendAnnotations(
            pdfBytes = SyntheticPdfBuilder.onePagePdfWithHighCompressedObjectNumber(),
            annotations = listOf(
                PdfAnnotation.StickyNote(
                    pageIndex = 0,
                    x = 120f,
                    y = 140f,
                    color = PdfAnnotationColor(red = 1f, green = 0.8f, blue = 0.1f),
                    contents = "High object number",
                )
            ),
        )

        val raw = annotated.toString(StandardCharsets.ISO_8859_1)
        assertTrue(raw.contains("51 0 obj\n<< /Type /Annot"))
        assertTrue(raw.contains("/Size 52"))
    }

    @Test
    fun refusesAnnotationExportWhenObjectNumberSpaceIsExhausted() {
        val original = SyntheticPdfBuilder.onePagePdfWithExhaustedObjectNumberSpace()

        val annotated = PdfAnnotationWriter.appendAnnotations(
            pdfBytes = original,
            annotations = listOf(
                PdfAnnotation.StickyNote(
                    pageIndex = 0,
                    x = 100f,
                    y = 100f,
                    color = PdfAnnotationColor(1f, 0.8f, 0.1f),
                    contents = "Cannot allocate",
                )
            ),
        )

        assertContentEquals(original, annotated)
    }
}
