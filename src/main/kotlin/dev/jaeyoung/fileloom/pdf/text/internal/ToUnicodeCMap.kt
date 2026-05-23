package dev.jaeyoung.fileloom.pdf.text.internal

/**
 * Parser for the subset of CMap files used as PDF /ToUnicode entries.
 *
 * A ToUnicode CMap maps PDF character codes (1 or 2 bytes, depending on the
 * font) to Unicode strings. The relevant directives:
 *
 *   beginbfchar
 *     <00> <0041>
 *     <01> <0042>
 *   endbfchar
 *
 *   beginbfrange
 *     <10> <12> <0050>             ; mapped to successive codepoints
 *     <20> <22> [<0060> <0061> <0062>] ; explicit per-code mapping
 *   endbfrange
 *
 *   codespacerange  (we use the first range's width as the default code width)
 *
 * Other CMap content (CIDInit dictionary boilerplate, comments, etc.) is
 * skipped — we only care about `bfchar` and `bfrange`.
 */
internal class ToUnicodeCMap(
    private val singleByteMap: Map<Int, String>,
    private val doubleByteMap: Map<Int, String>,
    val codeWidth: Int,
) {
    fun lookup(code: Int, width: Int): String? {
        return when {
            width == 1 -> singleByteMap[code] ?: doubleByteMap[code]
            width == 2 -> doubleByteMap[code] ?: singleByteMap[code]
            else -> singleByteMap[code] ?: doubleByteMap[code]
        }
    }

    companion object {
        fun parse(cmapBytes: ByteArray): ToUnicodeCMap {
            val text = cmapBytes.toString(Charsets.ISO_8859_1)
            val single = mutableMapOf<Int, String>()
            val double = mutableMapOf<Int, String>()
            var codeWidth = 1

            // codespacerange — detects code width.
            CODE_SPACE_RANGE.find(text)?.let { match ->
                val body = match.groupValues[1]
                val hex = HEX_LITERAL.findAll(body).map { it.groupValues[1] }.toList()
                if (hex.isNotEmpty()) {
                    codeWidth = (hex[0].length / 2).coerceIn(1, 4)
                }
            }

            // bfchar entries
            BF_CHAR_SECTION.findAll(text).forEach { section ->
                val body = section.groupValues[1]
                val entries = HEX_LITERAL.findAll(body).map { it.groupValues[1] }.toList()
                var i = 0
                while (i + 1 < entries.size) {
                    val code = entries[i].hexAsInt() ?: break
                    val target = decodeHexString(entries[i + 1])
                    val width = (entries[i].length / 2).coerceAtLeast(1)
                    if (width == 1) single[code] = target else double[code] = target
                    i += 2
                }
            }

            // bfrange entries
            BF_RANGE_SECTION.findAll(text).forEach { section ->
                parseBfRange(section.groupValues[1], single, double)
            }

            return ToUnicodeCMap(
                singleByteMap = single,
                doubleByteMap = double,
                codeWidth = codeWidth,
            )
        }

        private fun parseBfRange(
            body: String,
            single: MutableMap<Int, String>,
            double: MutableMap<Int, String>,
        ) {
            // bfrange has two forms:
            //   <start> <end> <unicodeStart>
            //   <start> <end> [<u0> <u1> <u2> ...]
            // Tokenize the body manually because arrays interleave with hex literals.

            val tokens = tokenizeBfRange(body)
            var i = 0
            while (i + 2 < tokens.size) {
                val startToken = tokens[i] as? BfToken.Hex ?: run { i += 1; continue }
                val endToken = tokens[i + 1] as? BfToken.Hex ?: run { i += 1; continue }
                val targetToken = tokens[i + 2]
                val codeStart = startToken.value.hexAsInt() ?: run { i += 1; continue }
                val codeEnd = endToken.value.hexAsInt() ?: run { i += 1; continue }
                val width = (startToken.value.length / 2).coerceAtLeast(1)
                val map = if (width == 1) single else double

                when (targetToken) {
                    is BfToken.Hex -> {
                        val baseCodepoint = targetToken.value.hexAsInt()
                        if (baseCodepoint != null) {
                            var current = baseCodepoint
                            for (code in codeStart..codeEnd) {
                                map[code] = codepointToString(current)
                                current += 1
                            }
                        }
                        i += 3
                    }
                    is BfToken.Array -> {
                        val targets = targetToken.entries
                        var code = codeStart
                        for (entry in targets) {
                            if (code > codeEnd) break
                            map[code] = decodeHexString(entry)
                            code += 1
                        }
                        i += 3
                    }
                }
            }
        }

        private fun tokenizeBfRange(body: String): List<BfToken> {
            val tokens = mutableListOf<BfToken>()
            var idx = 0
            while (idx < body.length) {
                val ch = body[idx]
                when {
                    ch.isWhitespace() -> idx += 1
                    ch == '<' -> {
                        val end = body.indexOf('>', idx + 1)
                        if (end < 0) break
                        tokens += BfToken.Hex(body.substring(idx + 1, end))
                        idx = end + 1
                    }
                    ch == '[' -> {
                        val end = body.indexOf(']', idx + 1)
                        if (end < 0) break
                        val arrayBody = body.substring(idx + 1, end)
                        val entries = HEX_LITERAL.findAll(arrayBody).map { it.groupValues[1] }.toList()
                        tokens += BfToken.Array(entries)
                        idx = end + 1
                    }
                    else -> idx += 1
                }
            }
            return tokens
        }

        private sealed class BfToken {
            data class Hex(val value: String) : BfToken()
            data class Array(val entries: List<String>) : BfToken()
        }

        private fun decodeHexString(hex: String): String {
            if (hex.length % 2 != 0) return ""
            val builder = StringBuilder(hex.length / 4)
            var i = 0
            while (i + 3 < hex.length || i + 1 < hex.length) {
                if (i + 3 < hex.length) {
                    val cp = hex.substring(i, i + 4).hexAsInt() ?: return builder.toString()
                    builder.append(codepointToString(cp))
                    i += 4
                } else {
                    val cp = hex.substring(i, i + 2).hexAsInt() ?: return builder.toString()
                    builder.append(codepointToString(cp))
                    i += 2
                }
            }
            return builder.toString()
        }

        private fun codepointToString(codepoint: Int): String {
            if (codepoint <= 0) return ""
            if (codepoint <= 0xFFFF) return codepoint.toChar().toString()
            // Surrogate pair for astral codepoints.
            return String(Character.toChars(codepoint))
        }

        private fun String.hexAsInt(): Int? =
            if (this.isEmpty() || this.length > 8) null else runCatching { this.toInt(16) }.getOrNull()

        private val CODE_SPACE_RANGE = Regex("begincodespacerange([\\s\\S]*?)endcodespacerange")
        private val BF_CHAR_SECTION = Regex("beginbfchar([\\s\\S]*?)endbfchar")
        private val BF_RANGE_SECTION = Regex("beginbfrange([\\s\\S]*?)endbfrange")
        private val HEX_LITERAL = Regex("<([0-9a-fA-F]+)>")
    }
}
