package dev.jaeyoung.fileloom.pdf.text.internal

/**
 * PDF 1.7 standard encodings (Annex D). Each maps a single byte (0..255) to a
 * Unicode codepoint, or 0 (NUL) if the slot is unused.
 *
 * We only ship the three that account for the vast majority of standard-font
 * PDFs in the wild. MacExpertEncoding is intentionally omitted (rare, expert
 * use only).
 *
 * Tables sourced from the Adobe glyph list mapping (AGL). Differences from
 * the PDF spec's Annex D are documented inline.
 */
internal object StandardEncodings {

    fun get(name: String?): IntArray? = when (name) {
        "WinAnsiEncoding" -> winAnsi
        "MacRomanEncoding" -> macRoman
        "StandardEncoding" -> standard
        "PDFDocEncoding" -> pdfDoc
        else -> null
    }

    /** WinAnsiEncoding — used by most modern PDFs with Type1/TrueType fonts. */
    private val winAnsi: IntArray = buildEncoding {
        // 0x20..0x7E: printable ASCII (identity).
        for (i in 0x20..0x7E) this[i] = i
        // 0x80..0x9F: Windows-1252 extensions.
        this[0x80] = 0x20AC // EURO
        this[0x82] = 0x201A // SINGLE LOW-9 QUOTATION MARK
        this[0x83] = 0x0192 // LATIN SMALL LETTER F WITH HOOK
        this[0x84] = 0x201E // DOUBLE LOW-9 QUOTATION MARK
        this[0x85] = 0x2026 // HORIZONTAL ELLIPSIS
        this[0x86] = 0x2020 // DAGGER
        this[0x87] = 0x2021 // DOUBLE DAGGER
        this[0x88] = 0x02C6 // MODIFIER LETTER CIRCUMFLEX ACCENT
        this[0x89] = 0x2030 // PER MILLE SIGN
        this[0x8A] = 0x0160 // LATIN CAPITAL LETTER S WITH CARON
        this[0x8B] = 0x2039 // SINGLE LEFT-POINTING ANGLE QUOTATION MARK
        this[0x8C] = 0x0152 // LATIN CAPITAL LIGATURE OE
        this[0x8E] = 0x017D // LATIN CAPITAL LETTER Z WITH CARON
        this[0x91] = 0x2018 // LEFT SINGLE QUOTATION MARK
        this[0x92] = 0x2019 // RIGHT SINGLE QUOTATION MARK
        this[0x93] = 0x201C // LEFT DOUBLE QUOTATION MARK
        this[0x94] = 0x201D // RIGHT DOUBLE QUOTATION MARK
        this[0x95] = 0x2022 // BULLET
        this[0x96] = 0x2013 // EN DASH
        this[0x97] = 0x2014 // EM DASH
        this[0x98] = 0x02DC // SMALL TILDE
        this[0x99] = 0x2122 // TRADE MARK SIGN
        this[0x9A] = 0x0161 // LATIN SMALL LETTER S WITH CARON
        this[0x9B] = 0x203A // SINGLE RIGHT-POINTING ANGLE QUOTATION MARK
        this[0x9C] = 0x0153 // LATIN SMALL LIGATURE OE
        this[0x9E] = 0x017E // LATIN SMALL LETTER Z WITH CARON
        this[0x9F] = 0x0178 // LATIN CAPITAL LETTER Y WITH DIAERESIS
        // 0xA0..0xFF: identical to ISO-8859-1 (Latin-1).
        for (i in 0xA0..0xFF) this[i] = i
    }

    /** MacRomanEncoding — older Mac PDFs. */
    private val macRoman: IntArray = buildEncoding {
        for (i in 0x20..0x7E) this[i] = i
        // High range (0x80..0xFF) for Mac Roman.
        val highRange = intArrayOf(
            0x00C4, 0x00C5, 0x00C7, 0x00C9, 0x00D1, 0x00D6, 0x00DC, 0x00E1,
            0x00E0, 0x00E2, 0x00E4, 0x00E3, 0x00E5, 0x00E7, 0x00E9, 0x00E8,
            0x00EA, 0x00EB, 0x00ED, 0x00EC, 0x00EE, 0x00EF, 0x00F1, 0x00F3,
            0x00F2, 0x00F4, 0x00F6, 0x00F5, 0x00FA, 0x00F9, 0x00FB, 0x00FC,
            0x2020, 0x00B0, 0x00A2, 0x00A3, 0x00A7, 0x2022, 0x00B6, 0x00DF,
            0x00AE, 0x00A9, 0x2122, 0x00B4, 0x00A8, 0x2260, 0x00C6, 0x00D8,
            0x221E, 0x00B1, 0x2264, 0x2265, 0x00A5, 0x00B5, 0x2202, 0x2211,
            0x220F, 0x03C0, 0x222B, 0x00AA, 0x00BA, 0x03A9, 0x00E6, 0x00F8,
            0x00BF, 0x00A1, 0x00AC, 0x221A, 0x0192, 0x2248, 0x2206, 0x00AB,
            0x00BB, 0x2026, 0x00A0, 0x00C0, 0x00C3, 0x00D5, 0x0152, 0x0153,
            0x2013, 0x2014, 0x201C, 0x201D, 0x2018, 0x2019, 0x00F7, 0x25CA,
            0x00FF, 0x0178, 0x2044, 0x20AC, 0x2039, 0x203A, 0xFB01, 0xFB02,
            0x2021, 0x00B7, 0x201A, 0x201E, 0x2030, 0x00C2, 0x00CA, 0x00C1,
            0x00CB, 0x00C8, 0x00CD, 0x00CE, 0x00CF, 0x00CC, 0x00D3, 0x00D4,
            0xF8FF, 0x00D2, 0x00DA, 0x00DB, 0x00D9, 0x0131, 0x02C6, 0x02DC,
            0x00AF, 0x02D8, 0x02D9, 0x02DA, 0x00B8, 0x02DD, 0x02DB, 0x02C7,
        )
        for ((index, value) in highRange.withIndex()) {
            this[0x80 + index] = value
        }
    }

    /** StandardEncoding — original Adobe Type1 default. Mostly ASCII overlap. */
    private val standard: IntArray = buildEncoding {
        for (i in 0x20..0x7E) this[i] = i
        // Standard adds a handful of high-bit slots, but for v0.1 we accept
        // imperfect coverage and let the Identity fallback handle the rest.
    }

    /** PDFDocEncoding — used for metadata strings (Title, Author, etc.). */
    private val pdfDoc: IntArray = buildEncoding {
        for (i in 0x20..0x7E) this[i] = i
        for (i in 0xA1..0xFF) this[i] = i // matches ISO-8859-1 for the high range
        this[0x18] = 0x02D8
        this[0x19] = 0x02C7
        this[0x1A] = 0x02C6
        this[0x1B] = 0x02D9
        this[0x1C] = 0x02DD
        this[0x1D] = 0x02DB
        this[0x1E] = 0x02DA
        this[0x1F] = 0x02DC
    }

    private inline fun buildEncoding(block: IntArray.() -> Unit): IntArray {
        val arr = IntArray(256)
        arr.block()
        return arr
    }
}
