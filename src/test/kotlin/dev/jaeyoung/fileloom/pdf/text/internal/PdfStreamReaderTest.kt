package dev.jaeyoung.fileloom.pdf.text.internal

import dev.jaeyoung.fileloom.pdf.source.ByteArrayPdfByteSource
import dev.jaeyoung.fileloom.pdf.syntax.PdfObject
import dev.jaeyoung.fileloom.pdf.text.SyntheticPdfBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PdfStreamReaderTest {
    @Test
    fun objectStreamDecodeHonorsConfiguredOutputLimit() {
        val document = PdfDocument.open(
            ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithFlateXrefAndObjectStreams())
        )
        assertNotNull(document)

        document.use {
            assertNull(
                PdfStreamReader.extractStream(
                    document = it,
                    reference = PdfObject.Reference(12, 0),
                    maxRawBytes = 1024 * 1024,
                    maxDecodedBytes = 128,
                )
            )
        }
    }
}
