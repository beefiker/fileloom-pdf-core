package dev.jaeyoung.fileloom.pdf.selection

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * A normalized rectangle in Fileloom's top-left PDF page coordinate space.
 *
 * Fileloom's Android renderer maps page pixels and PDF framework text bounds in
 * top-left coordinates before passing them through the app. This type mirrors
 * that space so callers can compute/persist highlight geometry without pulling
 * in Android classes.
 */
data class PdfSelectionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isUsable: Boolean get() = width > 0f && height > 0f

    fun normalized(): PdfSelectionRect = PdfSelectionRect(
        left = min(left, right),
        top = min(top, bottom),
        right = max(left, right),
        bottom = max(top, bottom),
    )
}

/** Character offsets for a single selected word inside a PDF text fragment. */
data class PdfTextWordRange(
    val startOffset: Int,
    val endExclusive: Int,
) {
    val length: Int get() = endExclusive - startOffset
    val isUsable: Boolean get() = startOffset >= 0 && endExclusive > startOffset

    fun selectedTextFrom(text: String): String =
        if (!isUsable || startOffset >= text.length) {
            ""
        } else {
            text.substring(startOffset, endExclusive.coerceAtMost(text.length))
        }
}

/**
 * Shared PDF text-selection helpers used by Fileloom's PDF viewer.
 *
 * These helpers intentionally follow the same broad model used by pdf.js and
 * react-pdf-highlighter: first resolve a precise text range, then draw/store the
 * tight rects for that range. A full line/block rectangle should be used only
 * for hit-testing or menu placement, not as highlight geometry.
 */
object PdfTextSelectionGeometry {
    private const val MIN_WORD_RECT_HEIGHT = 8f
    private const val TIGHT_FRAGMENT_HEIGHT = 18f
    private const val MAX_NORMALIZED_WORD_RECT_HEIGHT = 20f
    private const val MAX_WORD_HEIGHT_TO_WIDTH = 0.45f
    private const val MAX_WORD_HEIGHT_TO_FRAGMENT = 0.42f
    private const val OVERSIZED_FRAGMENT_THRESHOLD = 1.15f
    private const val MAX_WORD_SNAP_DISTANCE = 3

    /**
     * Resolve the single word nearest [localOffset] without ever expanding to
     * the whole sentence/line.
     *
     * Android's `PdfPageTextContent` often gives Fileloom a whole line or a
     * large text fragment. When a tap lands in small PDF-coordinate error or in
     * whitespace next to a word, this snaps to the nearest word within a short
     * distance. If no word is close enough, returns null instead of selecting a
     * broad fallback range.
     */
    @JvmStatic
    fun resolveWordRange(
        text: String,
        localOffset: Int,
        locale: Locale = Locale.getDefault(),
    ): PdfTextWordRange? {
        if (text.isEmpty()) return null
        val wordRanges = collectWordRanges(text, locale)
        if (wordRanges.isEmpty()) return null

        val offset = localOffset.coerceIn(0, text.length)
        val containing = wordRanges.firstOrNull { range ->
            // Include endExclusive as a zero-distance snap to the previous word;
            // this handles taps that quantize to the whitespace just after a word.
            offset in range.startOffset..range.endExclusive
        }
        if (containing != null) return containing

        val nearest = wordRanges.minByOrNull { it.distanceTo(offset) } ?: return null
        return nearest.takeIf { it.distanceTo(offset) <= MAX_WORD_SNAP_DISTANCE }
    }

    /**
     * Normalize a selected word's highlight rect so oversized line/block bounds
     * become a tight word-height overlay.
     *
     * [fragmentBounds] may be a whole text-item or line rect from a PDF engine.
     * [selectedLeft]/[selectedRight] are the horizontal bounds for the resolved
     * word. [pressY] anchors the vertical slice around the user's press point so
     * the highlight stays over the actual glyph row even when a fragment spans
     * multiple visual rows.
     */
    @JvmStatic
    fun normalizeWordHighlightRect(
        fragmentBounds: PdfSelectionRect,
        selectedLeft: Float,
        selectedRight: Float,
        pressY: Float,
    ): PdfSelectionRect {
        val bounds = fragmentBounds.normalized()
        val fragmentWidth = bounds.width.coerceAtLeast(1f)
        val fragmentHeight = bounds.height.coerceAtLeast(1f)
        val minimumWidth = min(18f, fragmentWidth).coerceAtLeast(1f)
        val left = min(selectedLeft, selectedRight - 1f)
            .coerceIn(bounds.left, (bounds.right - minimumWidth).coerceAtLeast(bounds.left))
        val right = selectedRight
            .coerceAtLeast(left + minimumWidth)
            .coerceIn(left + 1f, bounds.right)
        val selectedWidth = (right - left).coerceAtLeast(1f)

        val naturalHeight = max(
            MIN_WORD_RECT_HEIGHT,
            minOf(
                MAX_NORMALIZED_WORD_RECT_HEIGHT,
                selectedWidth * MAX_WORD_HEIGHT_TO_WIDTH,
                fragmentHeight * MAX_WORD_HEIGHT_TO_FRAGMENT,
            ),
        ).coerceAtMost(fragmentHeight)

        val shouldNormalizeHeight =
            fragmentHeight > TIGHT_FRAGMENT_HEIGHT &&
                fragmentHeight > naturalHeight * OVERSIZED_FRAGMENT_THRESHOLD
        if (!shouldNormalizeHeight) {
            return PdfSelectionRect(left = left, top = bounds.top, right = right, bottom = bounds.bottom)
        }

        val centerY = when {
            pressY.isFinite() -> pressY.coerceIn(bounds.top, bounds.bottom)
            else -> (bounds.top + bounds.bottom) / 2f
        }
        val top = (centerY - (naturalHeight / 2f))
            .coerceIn(bounds.top, bounds.bottom - naturalHeight)
        return PdfSelectionRect(left = left, top = top, right = right, bottom = top + naturalHeight)
    }

    private fun collectWordRanges(text: String, locale: Locale): List<PdfTextWordRange> {
        val iterator = BreakIterator.getWordInstance(locale)
        iterator.setText(text)
        val ranges = mutableListOf<PdfTextWordRange>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            addWordRuns(text, start, end, ranges)
            start = end
            end = iterator.next()
        }
        return mergeConnectorSeparatedRanges(text, ranges)
    }

    private fun mergeConnectorSeparatedRanges(
        text: String,
        ranges: List<PdfTextWordRange>,
    ): List<PdfTextWordRange> {
        if (ranges.size < 2) return ranges
        val merged = mutableListOf<PdfTextWordRange>()
        var current = ranges.first()
        for (next in ranges.drop(1)) {
            val connectorIndex = current.endExclusive
            val shouldMerge = connectorIndex + 1 == next.startOffset &&
                connectorIndex in text.indices &&
                isWordConnector(text[connectorIndex])
            if (shouldMerge) {
                current = PdfTextWordRange(current.startOffset, next.endExclusive)
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    private fun addWordRuns(
        text: String,
        segmentStart: Int,
        segmentEnd: Int,
        output: MutableList<PdfTextWordRange>,
    ) {
        var index = segmentStart.coerceAtLeast(0)
        val safeEnd = segmentEnd.coerceAtMost(text.length)
        while (index < safeEnd) {
            while (index < safeEnd && !isWordBodyChar(text[index])) index += 1
            val wordStart = index
            while (index < safeEnd) {
                val char = text[index]
                val connectorInsideWord = isWordConnector(char) &&
                    index + 1 < safeEnd &&
                    index - 1 >= wordStart &&
                    isWordBodyChar(text[index - 1]) &&
                    isWordBodyChar(text[index + 1])
                if (!isWordBodyChar(char) && !connectorInsideWord) break
                index += 1
            }
            if (wordStart < index) {
                output += PdfTextWordRange(wordStart, index)
            }
            index += 1
        }
    }

    private fun PdfTextWordRange.distanceTo(offset: Int): Int = when {
        offset < startOffset -> startOffset - offset
        offset > endExclusive -> offset - endExclusive
        else -> 0
    }

    private fun isWordBodyChar(char: Char): Boolean {
        if (char.isLetterOrDigit()) return true
        return when (Character.getType(char)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            Character.LETTER_NUMBER.toInt(),
            Character.OTHER_LETTER.toInt() -> true
            else -> false
        }
    }

    private fun isWordConnector(char: Char): Boolean =
        char == '\'' || char == '’' || char == '-' || char == '_'
}
