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
    private val pageCount: Int,
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
            val title = (document.deref(dictionary.entries["Title"]) as? PdfObject.StringValue)
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
        dictionary.entries["Dest"]?.let { directDest ->
            return resolveDestinationObjectToPage(
                value = directDest,
                root = root,
                visitedNames = mutableSetOf(),
            )
        }
        val action = document.deref(dictionary.entries["A"]).asDictionary()
            ?: return null
        return resolveActionDestinationPage(action, root)
    }

    private fun resolveActionDestinationPage(
        action: PdfObject.Dictionary,
        root: PdfObject.Dictionary,
    ): Int? {
        val subtype = (document.deref(action.entries["S"]) as? PdfObject.Name)?.value
        return when (subtype) {
            null, "GoTo" -> resolveDestinationObjectToPage(
                value = action.entries["D"],
                root = root,
                visitedNames = mutableSetOf(),
            )
            "Named" -> when (document.deref(action.entries["N"]).destinationName()) {
                "FirstPage" -> coercePageIndex(0)
                "LastPage" -> coercePageIndex(pageCount - 1)
                else -> null
            }
            else -> null
        }
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
            is PdfObject.Reference -> pageIndexes[pageObject]?.let(::coercePageIndex)
            is PdfObject.IntegerValue -> coercePageIndex(pageObject.value.toInt())
            else -> null
        }
    }

    private fun coercePageIndex(pageIndex: Int): Int? {
        if (pageCount <= 0 || pageIndex < 0) return null
        return pageIndex.coerceAtMost(pageCount - 1)
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
            val pages = PdfPageWalker(document).collect()
            val pageIndexes = pages
                .mapIndexedNotNull { index, page ->
                    page.pageReference?.let { it to index }
                }
                .toMap()
            return PdfOutlineExtractor(document, pageIndexes, pages.size)
        }
    }
}
