package dev.jaeyoung.fileloom.pdf.text.internal

/**
 * Minimal subset of the Adobe Glyph List (AGL) used to resolve glyph names
 * from a font's /Differences array to Unicode codepoints.
 *
 * Covering every name in the full AGL would be ~4000 entries. We ship the
 * common Latin set + ligatures + standard punctuation, which covers the vast
 * majority of /Differences encodings emitted by mainstream PDF producers.
 * Unknown glyph names yield null and the caller falls back to encoding-table
 * lookup or skips the byte.
 *
 * Also handles two derivable forms:
 *  - "uni0041", "uni04D0" → parse hex codepoint after "uni" (4 hex chars)
 *  - "u0041", "u1F600" → parse hex codepoint after "u" (4..6 hex chars)
 */
internal object AdobeGlyphList {

    fun glyphNameToUnicode(name: String): Int? {
        named[name]?.let { return it }
        if (name.startsWith("uni") && name.length >= 7) {
            val hex = name.substring(3)
            if (hex.length % 4 == 0) {
                val first = hex.substring(0, 4)
                return first.toIntOrNull(16)
            }
        }
        if (name.startsWith("u") && name.length in 5..7) {
            val hex = name.substring(1)
            return hex.toIntOrNull(16)
        }
        return null
    }

    private val named: Map<String, Int> = buildMap {
        // ASCII letters
        for (c in 'A'..'Z') put(c.toString(), c.code)
        for (c in 'a'..'z') put(c.toString(), c.code)
        // Digits
        for (c in '0'..'9') put(c.toString(), c.code)
        // Punctuation & symbols (named per AGL).
        val pairs = listOf(
            "space" to 0x20, "exclam" to 0x21, "quotedbl" to 0x22, "numbersign" to 0x23,
            "dollar" to 0x24, "percent" to 0x25, "ampersand" to 0x26, "quoteright" to 0x2019,
            "parenleft" to 0x28, "parenright" to 0x29, "asterisk" to 0x2A, "plus" to 0x2B,
            "comma" to 0x2C, "hyphen" to 0x2D, "period" to 0x2E, "slash" to 0x2F,
            "colon" to 0x3A, "semicolon" to 0x3B, "less" to 0x3C, "equal" to 0x3D,
            "greater" to 0x3E, "question" to 0x3F, "at" to 0x40,
            "bracketleft" to 0x5B, "backslash" to 0x5C, "bracketright" to 0x5D,
            "asciicircum" to 0x5E, "underscore" to 0x5F, "quoteleft" to 0x2018,
            "braceleft" to 0x7B, "bar" to 0x7C, "braceright" to 0x7D, "asciitilde" to 0x7E,
            "exclamdown" to 0xA1, "cent" to 0xA2, "sterling" to 0xA3, "currency" to 0xA4,
            "yen" to 0xA5, "brokenbar" to 0xA6, "section" to 0xA7, "dieresis" to 0xA8,
            "copyright" to 0xA9, "ordfeminine" to 0xAA, "guillemotleft" to 0xAB,
            "logicalnot" to 0xAC, "registered" to 0xAE, "macron" to 0xAF,
            "degree" to 0xB0, "plusminus" to 0xB1, "twosuperior" to 0xB2, "threesuperior" to 0xB3,
            "acute" to 0xB4, "mu" to 0xB5, "paragraph" to 0xB6, "periodcentered" to 0xB7,
            "cedilla" to 0xB8, "onesuperior" to 0xB9, "ordmasculine" to 0xBA,
            "guillemotright" to 0xBB, "onequarter" to 0xBC, "onehalf" to 0xBD,
            "threequarters" to 0xBE, "questiondown" to 0xBF,
            // Latin accented letters (0xC0..0xFF).
            "Agrave" to 0xC0, "Aacute" to 0xC1, "Acircumflex" to 0xC2, "Atilde" to 0xC3,
            "Adieresis" to 0xC4, "Aring" to 0xC5, "AE" to 0xC6, "Ccedilla" to 0xC7,
            "Egrave" to 0xC8, "Eacute" to 0xC9, "Ecircumflex" to 0xCA, "Edieresis" to 0xCB,
            "Igrave" to 0xCC, "Iacute" to 0xCD, "Icircumflex" to 0xCE, "Idieresis" to 0xCF,
            "Eth" to 0xD0, "Ntilde" to 0xD1, "Ograve" to 0xD2, "Oacute" to 0xD3,
            "Ocircumflex" to 0xD4, "Otilde" to 0xD5, "Odieresis" to 0xD6, "multiply" to 0xD7,
            "Oslash" to 0xD8, "Ugrave" to 0xD9, "Uacute" to 0xDA, "Ucircumflex" to 0xDB,
            "Udieresis" to 0xDC, "Yacute" to 0xDD, "Thorn" to 0xDE, "germandbls" to 0xDF,
            "agrave" to 0xE0, "aacute" to 0xE1, "acircumflex" to 0xE2, "atilde" to 0xE3,
            "adieresis" to 0xE4, "aring" to 0xE5, "ae" to 0xE6, "ccedilla" to 0xE7,
            "egrave" to 0xE8, "eacute" to 0xE9, "ecircumflex" to 0xEA, "edieresis" to 0xEB,
            "igrave" to 0xEC, "iacute" to 0xED, "icircumflex" to 0xEE, "idieresis" to 0xEF,
            "eth" to 0xF0, "ntilde" to 0xF1, "ograve" to 0xF2, "oacute" to 0xF3,
            "ocircumflex" to 0xF4, "otilde" to 0xF5, "odieresis" to 0xF6, "divide" to 0xF7,
            "oslash" to 0xF8, "ugrave" to 0xF9, "uacute" to 0xFA, "ucircumflex" to 0xFB,
            "udieresis" to 0xFC, "yacute" to 0xFD, "thorn" to 0xFE, "ydieresis" to 0xFF,
            // Common typographic glyphs
            "endash" to 0x2013, "emdash" to 0x2014, "quotedblleft" to 0x201C,
            "quotedblright" to 0x201D, "quotesinglbase" to 0x201A, "quotedblbase" to 0x201E,
            "ellipsis" to 0x2026, "bullet" to 0x2022, "dagger" to 0x2020, "daggerdbl" to 0x2021,
            "perthousand" to 0x2030, "trademark" to 0x2122, "Euro" to 0x20AC,
            "fi" to 0xFB01, "fl" to 0xFB02, "ffi" to 0xFB03, "ffl" to 0xFB04,
            "OE" to 0x0152, "oe" to 0x0153, "Scaron" to 0x0160, "scaron" to 0x0161,
            "Ydieresis" to 0x0178, "Zcaron" to 0x017D, "zcaron" to 0x017E,
            "florin" to 0x0192, "circumflex" to 0x02C6, "caron" to 0x02C7,
            "tilde" to 0x02DC, "breve" to 0x02D8, "dotaccent" to 0x02D9,
            "ring" to 0x02DA, "hungarumlaut" to 0x02DD, "ogonek" to 0x02DB,
            "guilsinglleft" to 0x2039, "guilsinglright" to 0x203A,
            "fraction" to 0x2044, "minus" to 0x2212,
            "infinity" to 0x221E, "integral" to 0x222B, "approxequal" to 0x2248,
            "notequal" to 0x2260, "lessequal" to 0x2264, "greaterequal" to 0x2265,
            "lozenge" to 0x25CA, "nobreakspace" to 0xA0, "softhyphen" to 0xAD,
            // Greek (occasionally referenced)
            "Omega" to 0x03A9, "pi" to 0x03C0, "Pi" to 0x220F, "partialdiff" to 0x2202,
            "summation" to 0x2211, "radical" to 0x221A, "Delta" to 0x2206,
        )
        for ((name, codepoint) in pairs) put(name, codepoint)
    }
}
