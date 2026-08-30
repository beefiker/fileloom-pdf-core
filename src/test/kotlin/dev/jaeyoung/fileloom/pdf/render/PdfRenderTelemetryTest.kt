package dev.jaeyoung.fileloom.pdf.render

import kotlin.test.Test
import kotlin.test.assertEquals

class PdfRenderTelemetryTest {
    @Test
    fun noOpTelemetrySinkDoesNotThrow() {
        NoOpPdfRenderTelemetrySink.record(
            PdfRenderTelemetryEvent(
                page = 0,
                requestedWidth = 1080,
                renderedWidth = 1080,
                renderedHeight = 1528,
                cacheHit = false,
                usedFallbackWidth = false,
                waitForLockMs = 0,
                openPageMs = 1,
                allocateBitmapMs = 2,
                renderMs = 3,
                totalMs = 6,
                result = PdfRenderTelemetryResult.SUCCESS,
            )
        )
    }

    @Test
    fun telemetryEventCarriesFailureOutcomes() {
        val event = PdfRenderTelemetryEvent(
            page = 0,
            requestedWidth = 1080,
            renderedWidth = null,
            renderedHeight = null,
            cacheHit = false,
            usedFallbackWidth = false,
            waitForLockMs = 7,
            openPageMs = 0,
            allocateBitmapMs = 0,
            renderMs = 0,
            totalMs = 7,
            result = PdfRenderTelemetryResult.STALE,
        )

        assertEquals(PdfRenderTelemetryResult.STALE, event.result)
        assertEquals(7, event.waitForLockMs)
    }
}
