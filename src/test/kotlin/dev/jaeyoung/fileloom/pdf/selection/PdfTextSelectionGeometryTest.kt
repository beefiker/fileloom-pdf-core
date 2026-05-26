package dev.jaeyoung.fileloom.pdf.selection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfTextSelectionGeometryTest {

    @Test
    fun resolvesPressedWordWithoutSelectingWholeLine() {
        val text = "Clear saved highlights"

        val range = PdfTextSelectionGeometry.resolveWordRange(text, localOffset = 2)

        assertNotNull(range)
        assertEquals("Clear", range.selectedTextFrom(text))
    }

    @Test
    fun snapsAdjacentWhitespaceBackToNearestWord() {
        val text = "Clear saved highlights"

        val range = PdfTextSelectionGeometry.resolveWordRange(text, localOffset = 5)

        assertNotNull(range)
        assertEquals("Clear", range.selectedTextFrom(text))
    }

    @Test
    fun doesNotPromoteWhitespaceToSentenceSelection() {
        val text = "Clear saved highlights"

        val range = PdfTextSelectionGeometry.resolveWordRange(text, localOffset = 6)

        assertNotNull(range)
        assertEquals("saved", range.selectedTextFrom(text))
        assertTrue(range.selectedTextFrom(text) != text)
    }

    @Test
    fun returnsNullForTextWithoutNearbyWordCharacters() {
        val text = "     •     "

        assertNull(PdfTextSelectionGeometry.resolveWordRange(text, localOffset = 5))
    }

    @Test
    fun keepsApostrophesInsideWords() {
        val text = "don’t clear"

        val range = PdfTextSelectionGeometry.resolveWordRange(text, localOffset = 3)

        assertNotNull(range)
        assertEquals("don’t", range.selectedTextFrom(text))
    }

    @Test
    fun normalizesOversizedFragmentHeightToTightWordHeight() {
        val rect = PdfTextSelectionGeometry.normalizeWordHighlightRect(
            fragmentBounds = PdfSelectionRect(left = 20f, top = 80f, right = 220f, bottom = 160f),
            selectedLeft = 72f,
            selectedRight = 112f,
            pressY = 121f,
        )

        assertEquals(72f, rect.left, 0.001f)
        assertEquals(112f, rect.right, 0.001f)
        assertTrue(rect.height <= 20f, "expected tight word-height highlight, got ${rect.height}")
        assertTrue(rect.top >= 80f && rect.bottom <= 160f)
        assertEquals(121f, (rect.top + rect.bottom) / 2f, 0.001f)
    }

    @Test
    fun keepsAlreadyTightFragmentHeight() {
        val rect = PdfTextSelectionGeometry.normalizeWordHighlightRect(
            fragmentBounds = PdfSelectionRect(left = 20f, top = 80f, right = 220f, bottom = 96f),
            selectedLeft = 72f,
            selectedRight = 112f,
            pressY = 88f,
        )

        assertEquals(80f, rect.top, 0.001f)
        assertEquals(96f, rect.bottom, 0.001f)
    }
}
