package dev.jaeyoung.fileloom.pdf.outline

import dev.jaeyoung.fileloom.pdf.source.ByteArrayPdfByteSource
import dev.jaeyoung.fileloom.pdf.text.SyntheticPdfBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PdfOutlineExtractorTest {

    @Test
    fun extractsNestedOutlineTreeWithPageIndexes() {
        val extractor = PdfOutlineExtractor.open(ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutline()))
        assertNotNull(extractor)

        extractor.use {
            val toc = it.extractTableOfContents()

            assertEquals(2, toc.size)
            assertEquals(PdfTocEntry("Chapter 1", pageIndex = 0, children = listOf(
                PdfTocEntry("Section 1.1", pageIndex = 1)
            )), toc[0])
            assertEquals(PdfTocEntry("Chapter 2", pageIndex = 1), toc[1])
        }
    }
}
