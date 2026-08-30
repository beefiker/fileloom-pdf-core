package dev.jaeyoung.fileloom.pdf.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PdfRenderRequestTest {
    @Test
    fun requestKeyIgnoresGenerationAndReason() {
        val visible = PdfRenderRequest(
            page = 1,
            targetWidth = 1080,
            generation = 1,
            reason = PdfRenderReason.VISIBLE,
        )
        val prefetch = visible.copy(generation = 2, reason = PdfRenderReason.ADJACENT_PREFETCH)

        assertEquals(visible.key(), prefetch.key())
    }

    @Test
    fun tileRequestsHaveSeparateKeys() {
        val fullPage = PdfRenderRequest(
            page = 1,
            targetWidth = 1080,
            generation = 1,
            reason = PdfRenderReason.VISIBLE,
        )
        val tile = fullPage.copy(tile = PdfRenderTile(x = 0, y = 0, width = 540, height = 540))

        assertNotEquals(fullPage.key(), tile.key())
    }

    @Test
    fun visiblePriorityComesBeforePrefetchAndBackground() {
        assertTrue(PdfRenderReason.VISIBLE.priority < PdfRenderReason.ADJACENT_PREFETCH.priority)
        assertTrue(PdfRenderReason.ADJACENT_PREFETCH.priority < PdfRenderReason.BACKGROUND_METADATA.priority)
    }
}
