package dev.jaeyoung.fileloom.pdf.render

data class PdfRenderTile(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class PdfRenderRequest(
    val page: Int,
    val targetWidth: Int,
    val generation: Long,
    val reason: PdfRenderReason,
    val tile: PdfRenderTile? = null,
)

enum class PdfRenderReason(val priority: Int) {
    VISIBLE(0),
    VISIBLE_HIGH_RES(1),
    ADJACENT_PREFETCH(2),
    IDLE_PREFETCH(3),
    BACKGROUND_METADATA(4),
}

data class PdfRenderRequestKey(
    val page: Int,
    val targetWidth: Int,
    val tile: PdfRenderTile?,
)

fun PdfRenderRequest.key(): PdfRenderRequestKey =
    PdfRenderRequestKey(page = page, targetWidth = targetWidth, tile = tile)
