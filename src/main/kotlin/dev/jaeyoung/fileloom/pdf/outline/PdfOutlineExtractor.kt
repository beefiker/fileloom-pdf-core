package dev.jaeyoung.fileloom.pdf.outline

import dev.jaeyoung.fileloom.pdf.source.PdfByteSource
import dev.jaeyoung.fileloom.pdf.syntax.PdfObject
import dev.jaeyoung.fileloom.pdf.text.internal.PdfDocument
import dev.jaeyoung.fileloom.pdf.text.internal.PdfPageWalker
import dev.jaeyoung.fileloom.pdf.text.internal.asArray
import dev.jaeyoung.fileloom.pdf.text.internal.asDictionary
import dev.jaeyoung.fileloom.pdf.text.internal.decodePdfTextString

data class PdfTocEntry(
    val title: String,
    val pageIndex: Int?,
    val children: List<PdfTocEntry> = emptyList(),
)

class PdfOutlineExtractor private constructor(
    private val document: PdfDocument,
    private val pageIndexes: Map<PdfObject.Reference, Int>,
) : AutoCloseable {

    fun extractTableOfContents(): List<PdfTocEntry> {
        val root = document.deref(document.trailer.entries["Root"]).asDictionary() ?: return emptyList()
        val outlines = document.deref(root.entries["Outlines"]).asDictionary() ?: return emptyList()
        return walkSiblings(outlines.entries["First"], visited = mutableSetOf())
    }

    private fun walkSiblings(
        first: PdfObject?,
        visited: MutableSet<PdfObject.Reference>,
    ): List<PdfTocEntry> {
        val entries = mutableListOf<PdfTocEntry>()
        var current = first
        while (current != null) {
            val reference = current as? PdfObject.Reference
            if (reference != null && !visited.add(reference)) break
            val dictionary = document.deref(current).asDictionary() ?: break
            val title = (dictionary.entries["Title"] as? PdfObject.StringValue)
                ?.decodePdfTextString()
                ?.trim()
                .orEmpty()
            if (title.isNotBlank()) {
                entries += PdfTocEntry(
                    title = title,
                    pageIndex = resolveDestinationPage(dictionary),
                    children = walkSiblings(dictionary.entries["First"], visited),
                )
            }
            current = dictionary.entries["Next"]
        }
        return entries
    }

    private fun resolveDestinationPage(dictionary: PdfObject.Dictionary): Int? {
        val directDest = dictionary.entries["Dest"]
        val actionDest = document.deref(dictionary.entries["A"])
            .asDictionary()
            ?.entries
            ?.get("D")
        val destination = document.deref(directDest ?: actionDest)
        val pageObject = destination.asArray()?.items?.firstOrNull() ?: destination
        return when (pageObject) {
            is PdfObject.Reference -> pageIndexes[pageObject]
            is PdfObject.IntegerValue -> pageObject.value.toInt().takeIf { it >= 0 }
            else -> null
        }
    }

    override fun close() {
        document.close()
    }

    companion object {
        fun open(source: PdfByteSource): PdfOutlineExtractor? {
            val document = PdfDocument.open(source) ?: return null
            val pageIndexes = PdfPageWalker(document)
                .collect()
                .mapIndexedNotNull { index, page ->
                    page.pageReference?.let { it to index }
                }
                .toMap()
            return PdfOutlineExtractor(document, pageIndexes)
        }
    }
}
