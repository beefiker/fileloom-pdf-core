package dev.jaeyoung.fileloom.pdf.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfRenderPolicyTest {
    @Test
    fun fallbackWidthsPreserveRequestedWidthAndMinimumFallback() {
        val widths = PdfRenderPolicy.fallbackWidths(1440)

        assertEquals(1440, widths.first())
        assertEquals(PdfRenderPolicy.MIN_FALLBACK_WIDTH, widths.last())
    }

    @Test
    fun previewFallbackWidthsNeverIncreaseTheRequestedAllocation() {
        val widths = PdfRenderPolicy.fallbackWidths(PdfRenderPolicy.PREVIEW_RENDER_WIDTH)

        assertEquals(listOf(PdfRenderPolicy.PREVIEW_RENDER_WIDTH), widths)
    }

    @Test
    fun safeRenderDimensionsRejectInvalidPageSizesAndCapHugePages() {
        assertNull(PdfRenderPolicy.safeRenderDimensions(1080, pageWidth = 0, pageHeight = 792))

        val huge = requireNotNull(
            PdfRenderPolicy.safeRenderDimensions(
                requestedWidth = PdfRenderPolicy.MAX_RENDER_WIDTH,
                pageWidth = 100,
                pageHeight = 50_000,
            )
        )
        assertTrue(huge.first <= PdfRenderPolicy.MAX_RENDER_DIMENSION)
        assertTrue(huge.second <= PdfRenderPolicy.MAX_RENDER_DIMENSION)
        assertTrue(huge.first.toLong() * huge.second.toLong() <= PdfRenderPolicy.MAX_RENDER_PIXELS)
    }

    @Test
    fun normalPageDimensionsAreAccepted() {
        assertNotNull(PdfRenderPolicy.safeRenderDimensions(1080, pageWidth = 612, pageHeight = 792))
    }

    @Test
    fun targetWidthsMapToStableBuckets() {
        assertEquals(1080, PdfRenderPolicy.targetWidthFor(800))
        assertEquals(1440, PdfRenderPolicy.targetWidthFor(960))
        assertEquals(PdfRenderPolicy.MAX_RENDER_WIDTH, PdfRenderPolicy.targetWidthFor(10_000))
    }

    @Test
    fun nearbyWidthsShareBucketsAndPreviewStaysExact() {
        assertEquals(PdfRenderPolicy.PREVIEW_RENDER_WIDTH, PdfRenderPolicy.bucketWidthFor(220))
        assertEquals(1080, PdfRenderPolicy.bucketWidthFor(1024))
        assertEquals(1080, PdfRenderPolicy.bucketWidthFor(1060))
        assertEquals(1440, PdfRenderPolicy.bucketWidthFor(1281))
    }

    @Test
    fun tileBoundsAreClampedToRenderBitmap() {
        val clamped = requireNotNull(
            PdfRenderPolicy.clampTileToRenderBounds(
                tile = PdfRenderTile(x = -20, y = 80, width = 180, height = 200),
                renderWidth = 120,
                renderHeight = 180,
            )
        )

        assertEquals(PdfRenderTile(x = 0, y = 80, width = 120, height = 100), clamped)
        assertNull(
            PdfRenderPolicy.clampTileToRenderBounds(
                tile = PdfRenderTile(x = 130, y = 0, width = 20, height = 20),
                renderWidth = 120,
                renderHeight = 180,
            )
        )
    }
}
