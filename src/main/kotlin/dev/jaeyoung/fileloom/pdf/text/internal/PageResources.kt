package dev.jaeyoung.fileloom.pdf.text.internal

import dev.jaeyoung.fileloom.pdf.document.PdfLowLevelDocument
import dev.jaeyoung.fileloom.pdf.syntax.PdfObject

/**
 * Lazily-loaded font lookups for a single page.
 *
 * A page's /Resources /Font subdictionary maps short names (e.g. `/F1`) to
 * font dictionaries. The interpreter requests a font by name via `Tf`; we
 * resolve it on demand and cache.
 */
internal class PageResources(
    private val resources: PdfObject.Dictionary?,
    private val document: PdfLowLevelDocument,
) {
    private val fontCache = mutableMapOf<String, PdfFont?>()

    fun fontByName(name: String): PdfFont? {
        fontCache[name]?.let { return it }
        if (fontCache.containsKey(name)) return null
        val font = loadFont(name)
        fontCache[name] = font
        return font
    }

    private fun loadFont(name: String): PdfFont? {
        val fontDictMap = resolve(resources?.entries?.get("Font")) as? PdfObject.Dictionary ?: return null
        val fontEntry = fontDictMap.entries[name] ?: return null
        val fontDict = resolve(fontEntry) as? PdfObject.Dictionary ?: return null
        return PdfFont.load(fontDict, document)
    }

    private fun resolve(value: PdfObject?): PdfObject? {
        if (value == null) return null
        return if (value is PdfObject.Reference) {
            runCatching { document.resolve(value) }.getOrNull()
        } else value
    }
}
