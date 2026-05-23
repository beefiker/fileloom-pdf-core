package dev.jaeyoung.fileloom.pdf.text.internal

import dev.jaeyoung.fileloom.pdf.document.PdfLowLevelDocument
import dev.jaeyoung.fileloom.pdf.syntax.PdfObject

/**
 * Loaded representation of a PDF font for the purposes of text extraction.
 *
 * The only thing we care about: turn a sequence of bytes from a Tj/TJ string
 * argument into a Unicode string. Glyph metrics, rendering, and embedded
 * outlines are ignored.
 */
internal class PdfFont(
    private val codeWidth: Int,
    private val toUnicode: ToUnicodeCMap?,
    private val encodingTable: IntArray?,
    private val differences: Map<Int, String>,
) {

    fun decode(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val code = if (codeWidth == 2 && i + 1 < bytes.size) {
                ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)
            } else {
                bytes[i].toInt() and 0xff
            }
            val read = if (codeWidth == 2 && i + 1 < bytes.size) 2 else 1
            i += read

            val mapped = toUnicode?.lookup(code, read)
            if (mapped != null) {
                builder.append(mapped)
                continue
            }

            val glyphName = differences[code]
            if (glyphName != null) {
                val cp = AdobeGlyphList.glyphNameToUnicode(glyphName)
                if (cp != null) builder.appendCodepoint(cp)
                continue
            }

            if (encodingTable != null) {
                val cp = encodingTable.getOrElse(code) { 0 }
                if (cp != 0) {
                    builder.appendCodepoint(cp)
                    continue
                }
            }

            // Identity fallback: treat the byte as Latin-1 if printable.
            if (toUnicode == null && code in 0x20..0xFF) {
                builder.append(code.toChar())
            }
        }
        return builder.toString()
    }

    private fun StringBuilder.appendCodepoint(cp: Int) {
        if (cp <= 0xFFFF) append(cp.toChar()) else append(Character.toChars(cp))
    }

    companion object {
        fun load(
            fontDict: PdfObject.Dictionary,
            document: PdfLowLevelDocument,
        ): PdfFont {
            val resolve: (PdfObject?) -> PdfObject? = { value ->
                if (value is PdfObject.Reference) {
                    runCatching { document.resolve(value) }.getOrNull()
                } else value
            }

            val subtype = (fontDict.entries["Subtype"] as? PdfObject.Name)?.value
            val isCompositeFont = subtype == "Type0"

            val toUnicode = loadToUnicode(fontDict, document, resolve)
            val codeWidth = when {
                toUnicode != null && toUnicode.codeWidth >= 2 -> 2
                isCompositeFont -> 2
                else -> 1
            }

            val (encodingTable, differences) = loadEncoding(fontDict, isCompositeFont, resolve)

            return PdfFont(
                codeWidth = codeWidth,
                toUnicode = toUnicode,
                encodingTable = encodingTable,
                differences = differences,
            )
        }

        private fun loadToUnicode(
            fontDict: PdfObject.Dictionary,
            document: PdfLowLevelDocument,
            resolve: (PdfObject?) -> PdfObject?,
        ): ToUnicodeCMap? {
            val raw = fontDict.entries["ToUnicode"] ?: return null
            val reference = raw as? PdfObject.Reference ?: return null
            val streamBytes = PdfStreamReader.extractContentStream(document, reference) ?: return null
            if (streamBytes.isEmpty()) return null
            return runCatching { ToUnicodeCMap.parse(streamBytes) }.getOrNull()
        }

        private fun loadEncoding(
            fontDict: PdfObject.Dictionary,
            isCompositeFont: Boolean,
            resolve: (PdfObject?) -> PdfObject?,
        ): Pair<IntArray?, Map<Int, String>> {
            if (isCompositeFont) return null to emptyMap()
            val raw = fontDict.entries["Encoding"]
            val resolved = resolve(raw) ?: raw

            return when (resolved) {
                is PdfObject.Name -> StandardEncodings.get(resolved.value) to emptyMap()
                is PdfObject.Dictionary -> {
                    val baseName = (resolved.entries["BaseEncoding"] as? PdfObject.Name)?.value
                    val base = StandardEncodings.get(baseName) ?: StandardEncodings.get("WinAnsiEncoding")
                    val differences = parseDifferences(resolved.entries["Differences"])
                    base to differences
                }
                else -> StandardEncodings.get("WinAnsiEncoding") to emptyMap()
            }
        }

        private fun parseDifferences(value: PdfObject?): Map<Int, String> {
            val array = value as? PdfObject.ArrayValue ?: return emptyMap()
            val output = mutableMapOf<Int, String>()
            var currentCode = -1
            for (item in array.items) {
                when (item) {
                    is PdfObject.IntegerValue -> currentCode = item.value.toInt()
                    is PdfObject.Name -> {
                        if (currentCode >= 0) {
                            output[currentCode] = item.value
                            currentCode += 1
                        }
                    }
                    else -> Unit
                }
            }
            return output
        }
    }
}
