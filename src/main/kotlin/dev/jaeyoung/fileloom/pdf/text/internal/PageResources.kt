package dev.jaeyoung.fileloom.pdf.text.internal

import dev.jaeyoung.fileloom.pdf.syntax.PdfObject

internal class PageResources(
    private val resources: PdfObject.Dictionary?,
    private val document: PdfDocument,
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
        val fontDictMap = document.deref(resources?.entries?.get("Font")) as? PdfObject.Dictionary ?: return null
        val fontEntry = fontDictMap.entries[name] ?: return null
        val fontDict = document.deref(fontEntry) as? PdfObject.Dictionary ?: return null
        return PdfFont.load(fontDict, document)
    }
}
