package dev.jaeyoung.fileloom.pdf.annotation

import dev.jaeyoung.fileloom.pdf.document.PdfObjectId
import dev.jaeyoung.fileloom.pdf.document.PdfXrefEntry
import dev.jaeyoung.fileloom.pdf.source.ByteArrayPdfByteSource
import dev.jaeyoung.fileloom.pdf.syntax.PdfObject
import dev.jaeyoung.fileloom.pdf.text.internal.PdfDocument
import dev.jaeyoung.fileloom.pdf.text.internal.PdfPageWalker
import dev.jaeyoung.fileloom.pdf.text.internal.WalkedPage
import dev.jaeyoung.fileloom.pdf.text.internal.asArray
import dev.jaeyoung.fileloom.pdf.text.internal.asNumber
import dev.jaeyoung.fileloom.pdf.text.internal.escapePdfLiteralString
import dev.jaeyoung.fileloom.pdf.text.internal.formatPdfNumber
import dev.jaeyoung.fileloom.pdf.text.internal.serializePdfObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class PdfAnnotationColor(
    val red: Float,
    val green: Float,
    val blue: Float,
) {
    internal fun normalized(): PdfAnnotationColor = PdfAnnotationColor(
        red = red.coerceIn(0f, 1f),
        green = green.coerceIn(0f, 1f),
        blue = blue.coerceIn(0f, 1f),
    )
}

data class PdfAnnotationRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

sealed class PdfAnnotation {
    abstract val pageIndex: Int
    abstract val color: PdfAnnotationColor
    abstract val contents: String?

    data class Highlight(
        override val pageIndex: Int,
        val rects: List<PdfAnnotationRect>,
        override val color: PdfAnnotationColor,
        override val contents: String? = null,
    ) : PdfAnnotation()

    data class StickyNote(
        override val pageIndex: Int,
        val x: Float,
        val y: Float,
        override val color: PdfAnnotationColor,
        override val contents: String,
    ) : PdfAnnotation()
}

object PdfAnnotationWriter {
    fun appendAnnotations(
        pdfBytes: ByteArray,
        annotations: List<PdfAnnotation>,
    ): ByteArray {
        if (annotations.isEmpty()) return pdfBytes
        val document = PdfDocument.open(ByteArrayPdfByteSource(pdfBytes)) ?: return pdfBytes
        document.use {
            val pages = PdfPageWalker(document).collect()
            val annotationsByPage = annotations
                .filter { it.pageIndex in pages.indices }
                .groupBy { it.pageIndex }
            if (annotationsByPage.isEmpty()) return pdfBytes

            var nextObjectNumber = document.nextAvailableObjectNumber() ?: return pdfBytes
            val objectBodies = linkedMapOf<PdfObjectId, String>()

            annotationsByPage.forEach { (pageIndex, pageAnnotations) ->
                val page = pages[pageIndex]
                val pageReference = page.pageReference ?: return@forEach
                val pageHeight = page.pageHeight()
                val newAnnotationRefs = pageAnnotations.mapNotNull { annotation ->
                    val body = annotation.toPdfObject(pageHeight) ?: return@mapNotNull null
                    val reference = PdfObject.Reference(nextObjectNumber++, 0)
                    objectBodies[PdfObjectId(reference.objectNumber, reference.generationNumber)] = body
                    reference
                }
                if (newAnnotationRefs.isEmpty()) return@forEach

                val existingAnnots = document.deref(page.pageDictionary.entries["Annots"])
                    .asArray()
                    ?.items
                    .orEmpty()
                val updatedPage = page.pageDictionary.copy(
                    entries = page.pageDictionary.entries + (
                        "Annots" to PdfObject.ArrayValue(existingAnnots + newAnnotationRefs)
                        )
                )
                objectBodies[PdfObjectId(pageReference.objectNumber, pageReference.generationNumber)] =
                    serializePdfObject(updatedPage)
            }

            if (objectBodies.isEmpty()) return pdfBytes
            return appendIncrementalUpdate(
                original = pdfBytes,
                previousStartXref = document.startXref,
                root = document.trailer.entries["Root"],
                size = nextObjectNumber,
                objectBodies = objectBodies,
            )
        }
    }

    private fun PdfAnnotation.toPdfObject(pageHeight: Float): String? = when (this) {
        is PdfAnnotation.Highlight -> toPdfHighlightObject(pageHeight)
        is PdfAnnotation.StickyNote -> toPdfStickyNoteObject(pageHeight)
    }

    private fun PdfAnnotation.Highlight.toPdfHighlightObject(pageHeight: Float): String? {
        val safeRects = rects.mapNotNull { it.normalized().takeIf { rect -> rect.right > rect.left && rect.bottom > rect.top } }
        if (safeRects.isEmpty()) return null
        val union = safeRects.reduce { acc, rect ->
            PdfAnnotationRect(
                left = minOf(acc.left, rect.left),
                top = minOf(acc.top, rect.top),
                right = maxOf(acc.right, rect.right),
                bottom = maxOf(acc.bottom, rect.bottom),
            )
        }
        val pdfRect = union.toPdfRect(pageHeight)
        val quads = safeRects.flatMap { it.toPdfQuadPoints(pageHeight) }
        return buildString {
            append("<< /Type /Annot /Subtype /Highlight")
            append(" /Rect ${pdfRect.toPdfArray()}")
            append(" /QuadPoints ${quads.toPdfArray()}")
            appendColor(color)
            append(" /F 4")
            contents?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(" /Contents (${escapePdfLiteralString(it)})")
            }
            append(" >>")
        }
    }

    private fun PdfAnnotation.StickyNote.toPdfStickyNoteObject(pageHeight: Float): String {
        val size = 22f
        val rect = PdfAnnotationRect(
            left = x,
            top = y,
            right = x + size,
            bottom = y + size,
        ).normalized().toPdfRect(pageHeight)
        return buildString {
            append("<< /Type /Annot /Subtype /Text")
            append(" /Rect ${rect.toPdfArray()}")
            appendColor(color)
            append(" /Name /Comment /Open false /F 4")
            append(" /Contents (${escapePdfLiteralString(contents.trim())})")
            append(" >>")
        }
    }

    private fun appendIncrementalUpdate(
        original: ByteArray,
        previousStartXref: Long,
        root: PdfObject?,
        size: Int,
        objectBodies: Map<PdfObjectId, String>,
    ): ByteArray {
        val output = ByteArrayOutputStream(original.size + 2048)
        output.write(original)
        if (original.lastOrNull()?.toInt()?.toChar()?.isWhitespace() != true) {
            output.write('\n'.code)
        }

        val objectOffsets = linkedMapOf<PdfObjectId, Long>()
        objectBodies.toSortedMap(compareBy<PdfObjectId> { it.objectNumber }.thenBy { it.generationNumber })
            .forEach { (id, body) ->
                objectOffsets[id] = output.size().toLong()
                output.write("${id.objectNumber} ${id.generationNumber} obj\n$body\nendobj\n".toByteArray(StandardCharsets.ISO_8859_1))
            }

        val xrefOffset = output.size().toLong()
        output.write("xref\n".toByteArray(StandardCharsets.ISO_8859_1))
        writeXrefSections(output, objectOffsets)

        val rootEntry = root?.let { " /Root ${serializePdfObject(it)}" }.orEmpty()
        val trailer = "trailer\n<< /Size $size$rootEntry /Prev $previousStartXref >>\n" +
            "startxref\n$xrefOffset\n%%EOF\n"
        output.write(trailer.toByteArray(StandardCharsets.ISO_8859_1))
        return output.toByteArray()
    }

    private fun writeXrefSections(
        output: ByteArrayOutputStream,
        objectOffsets: Map<PdfObjectId, Long>,
    ) {
        val sorted = objectOffsets.entries.sortedWith(
            compareBy<Map.Entry<PdfObjectId, Long>> { it.key.objectNumber }
                .thenBy { it.key.generationNumber }
        )
        var index = 0
        while (index < sorted.size) {
            val first = sorted[index]
            val section = mutableListOf(first)
            index += 1
            while (
                index < sorted.size &&
                sorted[index].key.generationNumber == first.key.generationNumber &&
                sorted[index].key.objectNumber == section.last().key.objectNumber + 1
            ) {
                section += sorted[index]
                index += 1
            }
            output.write("${first.key.objectNumber} ${section.size}\n".toByteArray(StandardCharsets.ISO_8859_1))
            section.forEach { (id, offset) ->
                output.write(
                    "${offset.toString().padStart(10, '0')} ${id.generationNumber.toString().padStart(5, '0')} n \n"
                        .toByteArray(StandardCharsets.ISO_8859_1)
                )
            }
        }
    }

    private fun StringBuilder.appendColor(color: PdfAnnotationColor) {
        val safe = color.normalized()
        append(" /C [${formatPdfNumber(safe.red)} ${formatPdfNumber(safe.green)} ${formatPdfNumber(safe.blue)}]")
    }

    private fun WalkedPage.pageHeight(): Float {
        val mediaBox = pageDictionary.entries["MediaBox"].asArray()?.items
        val bottom = mediaBox?.getOrNull(1).asNumber()?.toFloat() ?: 0f
        val top = mediaBox?.getOrNull(3).asNumber()?.toFloat() ?: 792f
        return (top - bottom).takeIf { it > 0f } ?: 792f
    }

    private fun PdfAnnotationRect.normalized(): PdfAnnotationRect = PdfAnnotationRect(
        left = minOf(left, right),
        top = minOf(top, bottom),
        right = maxOf(left, right),
        bottom = maxOf(top, bottom),
    )

    private fun PdfAnnotationRect.toPdfRect(pageHeight: Float): List<Float> = listOf(
        left,
        pageHeight - bottom,
        right,
        pageHeight - top,
    )

    private fun PdfAnnotationRect.toPdfQuadPoints(pageHeight: Float): List<Float> = listOf(
        left,
        pageHeight - top,
        right,
        pageHeight - top,
        left,
        pageHeight - bottom,
        right,
        pageHeight - bottom,
    )

    private fun List<Float>.toPdfArray(): String =
        joinToString(prefix = "[", postfix = "]", separator = " ") { formatPdfNumber(it) }
}
