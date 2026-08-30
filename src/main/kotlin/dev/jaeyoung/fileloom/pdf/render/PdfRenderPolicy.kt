package dev.jaeyoung.fileloom.pdf.render

import kotlin.math.roundToInt
import kotlin.math.sqrt

object PdfRenderPolicy {
    const val RENDER_SCALE = 1.25f
    const val MIN_RENDER_WIDTH = 720
    const val MAX_RENDER_WIDTH = 2200
    const val MIN_FALLBACK_WIDTH = 360
    const val MAX_RENDER_PIXELS = 8_000_000L
    const val MAX_RENDER_DIMENSION = 4096
    const val PREVIEW_RENDER_WIDTH = 220

    fun targetWidthFor(contentWidthPx: Int): Int? {
        if (contentWidthPx <= 0) return null
        val requested = (contentWidthPx * RENDER_SCALE)
            .roundToInt()
            .coerceIn(MIN_FALLBACK_WIDTH, MAX_RENDER_WIDTH)
        val quantized = ((requested + 63) / 128) * 128
        return bucketWidthFor(quantized.coerceIn(MIN_RENDER_WIDTH, MAX_RENDER_WIDTH))
    }

    fun fallbackWidths(requestedWidth: Int): List<Int> {
        val width = requestedWidth.coerceAtLeast(1)
        val candidates = linkedSetOf(width)
        candidates += (width * 0.85f).roundToInt().coerceAtLeast(MIN_FALLBACK_WIDTH)
        candidates += (width * 0.7f).roundToInt().coerceAtLeast(MIN_FALLBACK_WIDTH)
        candidates += (width * 0.55f).roundToInt().coerceAtLeast(MIN_FALLBACK_WIDTH)
        candidates += (width * 0.4f).roundToInt().coerceAtLeast(MIN_FALLBACK_WIDTH)
        candidates += MIN_FALLBACK_WIDTH
        return candidates.filter { it <= width }
    }

    fun safeRenderDimensions(
        requestedWidth: Int,
        pageWidth: Int,
        pageHeight: Int,
    ): Pair<Int, Int>? {
        if (pageWidth <= 0 || pageHeight <= 0) return null
        val aspectInv = pageHeight.toDouble() / pageWidth.toDouble()
        if (aspectInv <= 0.0) return null

        var width = requestedWidth.coerceIn(1, MAX_RENDER_DIMENSION)
        val maxWidthByPixels = sqrt(MAX_RENDER_PIXELS / aspectInv).toInt().coerceAtLeast(1)
        val maxWidthByHeight = (MAX_RENDER_DIMENSION / aspectInv).toInt().coerceAtLeast(1)
        width = width.coerceAtMost(maxWidthByPixels).coerceAtMost(maxWidthByHeight)
        if (width <= 0) return null

        var height = (width * aspectInv).roundToInt().coerceAtLeast(1)
        while (width > 1 && width.toLong() * height.toLong() > MAX_RENDER_PIXELS) {
            width -= 1
            height = (width * aspectInv).roundToInt().coerceAtLeast(1)
        }
        if (height > MAX_RENDER_DIMENSION) return null
        if (width.toLong() * height.toLong() > MAX_RENDER_PIXELS) return null
        return width to height
    }

    fun bucketWidthFor(requestedWidth: Int): Int {
        val safe = requestedWidth.coerceAtLeast(1)
        return PdfRenderBucket.entries
            .map { it.width }
            .firstOrNull { it >= safe }
            ?: MAX_RENDER_WIDTH
    }

    fun isBitmapWidthAcceptable(bitmapWidth: Int, targetWidth: Int): Boolean {
        if (bitmapWidth <= 0 || targetWidth <= 0) return false
        val minimumWidth = minOf(targetWidth, MIN_FALLBACK_WIDTH).coerceAtLeast(1)
        return bitmapWidth >= minimumWidth
    }

    fun clampTileToRenderBounds(
        tile: PdfRenderTile,
        renderWidth: Int,
        renderHeight: Int,
    ): PdfRenderTile? {
        if (tile.width <= 0 || tile.height <= 0 || renderWidth <= 0 || renderHeight <= 0) {
            return null
        }
        val left = tile.x.coerceIn(0, renderWidth)
        val top = tile.y.coerceIn(0, renderHeight)
        val right = (tile.x.toLong() + tile.width.toLong())
            .coerceIn(left.toLong(), renderWidth.toLong())
            .toInt()
        val bottom = (tile.y.toLong() + tile.height.toLong())
            .coerceIn(top.toLong(), renderHeight.toLong())
            .toInt()
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null
        return PdfRenderTile(x = left, y = top, width = width, height = height)
    }
}

enum class PdfRenderBucket(val width: Int) {
    PREVIEW(PdfRenderPolicy.PREVIEW_RENDER_WIDTH),
    SMALL(720),
    MEDIUM(1080),
    LARGE(1440),
    XL(1800),
    MAX(PdfRenderPolicy.MAX_RENDER_WIDTH),
}
