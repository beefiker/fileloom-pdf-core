package dev.jaeyoung.fileloom.pdf.text.internal

import dev.jaeyoung.fileloom.pdf.source.ByteArrayPdfByteSource
import dev.jaeyoung.fileloom.pdf.syntax.PdfLexer
import dev.jaeyoung.fileloom.pdf.syntax.PdfObject
import dev.jaeyoung.fileloom.pdf.syntax.PdfObjectParser
import dev.jaeyoung.fileloom.pdf.syntax.PdfToken

/**
 * Walks a decoded PDF content stream, dispatching text operators to track an
 * approximate text matrix and emit Unicode text in reading order.
 *
 * v0.1 scope: best-effort line breaks based on cumulative Y deltas; no column
 * separation. Inline images, path painting, color operators etc. are ignored.
 *
 * References:
 *  - Operator set: PDFBox PDFTextStripper (which operators move text vs not).
 *  - Reading-order heuristic: pdf.js text-layer extractor (group by Y band).
 */
internal class ContentStreamInterpreter(private val resources: PageResources) {

    private val operandStack = mutableListOf<PdfObject>()
    private val output = StringBuilder()

    private var currentFont: PdfFont? = null
    private var fontSize: Float = 1f
    private var charSpacing: Float = 0f
    private var wordSpacing: Float = 0f
    private var leading: Float = 0f
    private var textY: Float = 0f
    private var textX: Float = 0f
    private var lastEmittedY: Float? = null
    private var pendingSpace = false

    fun interpret(stream: ByteArray): String {
        if (stream.isEmpty()) return ""
        val source = ByteArrayPdfByteSource(stream)
        val lexer = PdfLexer(source)

        while (true) {
            val token = lexer.nextToken() ?: break
            when (token) {
                is PdfToken.Keyword -> dispatch(token.value)
                is PdfToken.IntegerNumber -> operandStack += PdfObject.IntegerValue(token.value)
                is PdfToken.RealNumber -> operandStack += PdfObject.RealValue(token.value)
                is PdfToken.Name -> operandStack += PdfObject.Name(token.value)
                is PdfToken.LiteralString -> operandStack += PdfObject.StringValue(token.bytes)
                is PdfToken.HexString -> operandStack += PdfObject.StringValue(token.bytes)
                is PdfToken.StartArray -> operandStack += parseArrayFromLexer(lexer)
                is PdfToken.StartDictionary -> skipDictionary(lexer)
                is PdfToken.EndArray, is PdfToken.EndDictionary -> {
                    // Stray closers — ignore for resilience.
                }
            }
        }

        return output.toString()
    }

    private fun dispatch(op: String) {
        when (op) {
            "BT" -> {
                textX = 0f
                textY = 0f
                lastEmittedY = null
            }
            "ET" -> {
                // End text object — emit a newline so following text doesn't bleed.
                if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
                lastEmittedY = null
            }
            "Tf" -> {
                fontSize = popFloat() ?: 1f
                val name = (popOperand() as? PdfObject.Name)?.value
                currentFont = name?.let { resources.fontByName(it) }
            }
            "Tj" -> {
                val bytes = popStringBytes()
                if (bytes != null) emitTextBytes(bytes)
            }
            "TJ" -> {
                val array = popOperand() as? PdfObject.ArrayValue ?: return clearOperands()
                array.items.forEach { item ->
                    when (item) {
                        is PdfObject.StringValue -> emitTextBytes(item.bytes)
                        is PdfObject.IntegerValue -> {
                            // Negative adjustment = extra space; large positive = word boundary.
                            val units = item.value
                            if (units <= -100) pendingSpace = true
                        }
                        is PdfObject.RealValue -> {
                            if (item.value <= -100.0) pendingSpace = true
                        }
                        else -> Unit
                    }
                }
            }
            "'" -> {
                val bytes = popStringBytes()
                applyLineFeed()
                if (bytes != null) emitTextBytes(bytes)
            }
            "\"" -> {
                val bytes = popStringBytes()
                val wordSpace = popFloat()
                val charSpace = popFloat()
                if (charSpace != null) charSpacing = charSpace
                if (wordSpace != null) wordSpacing = wordSpace
                applyLineFeed()
                if (bytes != null) emitTextBytes(bytes)
            }
            "Td" -> {
                val ty = popFloat() ?: 0f
                val tx = popFloat() ?: 0f
                applyOffset(tx, ty)
            }
            "TD" -> {
                val ty = popFloat() ?: 0f
                val tx = popFloat() ?: 0f
                leading = -ty
                applyOffset(tx, ty)
            }
            "Tm" -> {
                // 6-number matrix: a b c d e f. We only track e (tx) and f (ty).
                val ty = popFloat() ?: 0f
                val tx = popFloat() ?: 0f
                popFloat(); popFloat(); popFloat(); popFloat()
                textX = tx
                textY = ty
                checkLineBreakOnPosition()
            }
            "T*" -> applyLineFeed()
            "Tw" -> wordSpacing = popFloat() ?: 0f
            "Tc" -> charSpacing = popFloat() ?: 0f
            "TL" -> leading = popFloat() ?: 0f
            else -> Unit // Ignore graphics-state, color, path ops, etc.
        }
        clearOperands()
    }

    private fun applyOffset(tx: Float, ty: Float) {
        textX += tx
        textY += ty
        checkLineBreakOnPosition()
    }

    private fun applyLineFeed() {
        textX = 0f
        textY -= leading.takeIf { it != 0f } ?: fontSize
        checkLineBreakOnPosition()
    }

    private fun checkLineBreakOnPosition() {
        val previous = lastEmittedY ?: return
        if (kotlin.math.abs(previous - textY) > LINE_BREAK_THRESHOLD) {
            if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
            lastEmittedY = textY
            pendingSpace = false
        }
    }

    private fun emitTextBytes(bytes: ByteArray) {
        val font = currentFont
        val text = font?.decode(bytes) ?: bytes.toString(Charsets.ISO_8859_1)
        if (text.isEmpty()) return
        if (pendingSpace && output.isNotEmpty() && !output.last().isWhitespace()) {
            output.append(' ')
        }
        pendingSpace = false
        output.append(text)
        if (lastEmittedY == null) lastEmittedY = textY
    }

    private fun popOperand(): PdfObject? =
        if (operandStack.isEmpty()) null else operandStack.removeAt(operandStack.size - 1)

    private fun popFloat(): Float? {
        return when (val v = popOperand()) {
            is PdfObject.IntegerValue -> v.value.toFloat()
            is PdfObject.RealValue -> v.value.toFloat()
            null -> null
            else -> null
        }
    }

    private fun popStringBytes(): ByteArray? =
        (popOperand() as? PdfObject.StringValue)?.bytes

    private fun clearOperands() {
        operandStack.clear()
    }

    /**
     * Parse an inline `[ ... ]` array from the lexer stream. PdfObjectParser
     * isn't reusable here because operators are interleaved with operands, so
     * we re-implement just the array case.
     */
    private fun parseArrayFromLexer(lexer: PdfLexer): PdfObject.ArrayValue {
        val items = mutableListOf<PdfObject>()
        while (true) {
            val token = lexer.nextToken() ?: break
            when (token) {
                is PdfToken.EndArray -> return PdfObject.ArrayValue(items)
                is PdfToken.IntegerNumber -> items += PdfObject.IntegerValue(token.value)
                is PdfToken.RealNumber -> items += PdfObject.RealValue(token.value)
                is PdfToken.Name -> items += PdfObject.Name(token.value)
                is PdfToken.LiteralString -> items += PdfObject.StringValue(token.bytes)
                is PdfToken.HexString -> items += PdfObject.StringValue(token.bytes)
                is PdfToken.StartArray -> items += parseArrayFromLexer(lexer)
                else -> Unit
            }
        }
        return PdfObject.ArrayValue(items)
    }

    /**
     * Skip a dictionary in the lexer stream (used for inline-image headers).
     * We don't actually need the keys/values — just need to consume to `>>`.
     */
    private fun skipDictionary(lexer: PdfLexer) {
        var depth = 1
        while (depth > 0) {
            val token = lexer.nextToken() ?: return
            when (token) {
                is PdfToken.StartDictionary -> depth += 1
                is PdfToken.EndDictionary -> depth -= 1
                else -> Unit
            }
        }
    }

    private companion object {
        // Y delta beyond which we assume a new line. PDF coordinates have Y
        // increasing upward, so a downward move is negative; we compare absolute.
        const val LINE_BREAK_THRESHOLD = 5f
    }
}
