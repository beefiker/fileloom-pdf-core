package dev.jaeyoung.fileloom.pdf.text.internal

import dev.jaeyoung.fileloom.pdf.syntax.PdfObject

/**
 * Walks the /Pages tree (handling nested /Kids and inherited /Resources) into
 * a flat list of leaf page dictionaries paired with their inherited resources.
 */
internal class PdfPageWalker(private val document: PdfDocument) {

    fun collect(): List<WalkedPage> {
        val trailer = document.trailer
        val root = document.deref(trailer.entries["Root"]) as? PdfObject.Dictionary ?: return emptyList()
        val pages = root.entries["Pages"] ?: return emptyList()
        val output = mutableListOf<WalkedPage>()
        val visited = mutableSetOf<PdfObject.Reference>()
        collectNode(
            value = pages,
            inheritedResources = null,
            output = output,
            visited = visited,
        )
        return output
    }

    private fun collectNode(
        value: PdfObject,
        inheritedResources: PdfObject.Dictionary?,
        output: MutableList<WalkedPage>,
        visited: MutableSet<PdfObject.Reference>,
    ) {
        val reference = value as? PdfObject.Reference
        if (reference != null && !visited.add(reference)) return

        val dictionary = document.deref(value) as? PdfObject.Dictionary ?: return
        val resources = (document.deref(dictionary.entries["Resources"]) as? PdfObject.Dictionary)
            ?: inheritedResources

        val typeName = (dictionary.entries["Type"] as? PdfObject.Name)?.value
        if (typeName == "Page") {
            output += WalkedPage(
                pageDictionary = dictionary,
                resources = resources,
            )
            return
        }

        val kids = document.deref(dictionary.entries["Kids"]) as? PdfObject.ArrayValue ?: return
        kids.items.forEach { kid ->
            collectNode(
                value = kid,
                inheritedResources = resources,
                output = output,
                visited = visited,
            )
        }
    }
}

internal data class WalkedPage(
    val pageDictionary: PdfObject.Dictionary,
    val resources: PdfObject.Dictionary?,
)
