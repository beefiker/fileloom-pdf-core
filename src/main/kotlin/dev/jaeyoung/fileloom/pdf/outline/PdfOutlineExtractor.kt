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
        return walkSiblings(
            first = outlines.entries["First"],
            root = root,
            visited = mutableSetOf(),
        )
    }

    private fun walkSiblings(
        first: PdfObject?,
        root: PdfObject.Dictionary,
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
                    pageIndex = resolveDestinationPage(dictionary, root),
                    children = walkSiblings(
                        first = dictionary.entries["First"],
                        root = root,
                        visited = visited,
                    ),
                )
            }
            current = dictionary.entries["Next"]
        }
        return entries
    }

    private fun resolveDestinationPage(
        dictionary: PdfObject.Dictionary,
        root: PdfObject.Dictionary,
    ): Int? {
        val directDest = dictionary.entries["Dest"]
        val actionDest = document.deref(dictionary.entries["A"])
            .asDictionary()
            ?.entries
            ?.get("D")
        return resolveDestinationObjectToPage(
            value = directDest ?: actionDest,
            root = root,
            visitedNames = mutableSetOf(),
        )
    }

    private fun resolveDestinationObjectToPage(
        value: PdfObject?,
        root: PdfObject.Dictionary,
        visitedNames: MutableSet<String>,
    ): Int? {
        val destination = document.deref(value)
        val destinationDictionary = destination.asDictionary()
        if (destinationDictionary != null) {
            destinationDictionary.entries["D"]?.let { nestedDestination ->
                return resolveDestinationObjectToPage(
                    value = nestedDestination,
                    root = root,
                    visitedNames = visitedNames,
                )
            }
        }
        destination.destinationName()?.let { name ->
            if (!visitedNames.add(name)) return null
            return resolveNamedDestination(root, name)?.let { namedDestination ->
                resolveDestinationObjectToPage(
                    value = namedDestination,
                    root = root,
                    visitedNames = visitedNames,
                )
            }
        }
        val pageObject = destination.asArray()?.items?.firstOrNull() ?: destination
        return when (pageObject) {
            is PdfObject.Reference -> pageIndexes[pageObject]
            is PdfObject.IntegerValue -> pageObject.value.toInt().takeIf { it >= 0 }
            else -> null
        }
    }

    private fun resolveNamedDestination(
        root: PdfObject.Dictionary,
        name: String,
    ): PdfObject? {
        val legacyDests = document.deref(root.entries["Dests"]).asDictionary()
        legacyDests?.entries?.get(name)?.let { return it }

        val names = document.deref(root.entries["Names"]).asDictionary()
        return findNamedDestinationInNameTree(
            node = names?.entries?.get("Dests"),
            name = name,
            visited = mutableSetOf(),
        )
    }

    private fun findNamedDestinationInNameTree(
        node: PdfObject?,
        name: String,
        visited: MutableSet<PdfObject.Reference>,
    ): PdfObject? {
        val reference = node as? PdfObject.Reference
        if (reference != null && !visited.add(reference)) return null
        val dictionary = document.deref(node).asDictionary() ?: return null

        val names = document.deref(dictionary.entries["Names"]).asArray()
        if (names != null) {
            names.items.chunked(2).forEach { pair ->
                val key = pair.getOrNull(0)?.destinationName() ?: return@forEach
                if (key == name) return pair.getOrNull(1)
            }
        }

        val kids = document.deref(dictionary.entries["Kids"]).asArray() ?: return null
        kids.items.forEach { kid ->
            findNamedDestinationInNameTree(
                node = kid,
                name = name,
                visited = visited,
            )?.let { return it }
        }
        return null
    }

    private fun PdfObject?.destinationName(): String? = when (this) {
        is PdfObject.Name -> value
        is PdfObject.StringValue -> decodePdfTextString()
        else -> null
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
