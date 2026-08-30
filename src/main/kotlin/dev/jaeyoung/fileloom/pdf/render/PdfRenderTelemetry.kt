package dev.jaeyoung.fileloom.pdf.render

data class PdfRenderTelemetryEvent(
    val page: Int,
    val requestedWidth: Int,
    val renderedWidth: Int?,
    val renderedHeight: Int?,
    val cacheHit: Boolean,
    val usedFallbackWidth: Boolean,
    val waitForLockMs: Long,
    val openPageMs: Long,
    val allocateBitmapMs: Long,
    val renderMs: Long,
    val totalMs: Long,
    val result: PdfRenderTelemetryResult,
)

enum class PdfRenderTelemetryResult {
    SUCCESS,
    FAILURE,
    OUT_OF_MEMORY,
    DOCUMENT_CLOSED,
    INVALID_PAGE,
    STALE,
}

fun interface PdfRenderTelemetrySink {
    fun record(event: PdfRenderTelemetryEvent)
}

object NoOpPdfRenderTelemetrySink : PdfRenderTelemetrySink {
    override fun record(event: PdfRenderTelemetryEvent) = Unit
}
