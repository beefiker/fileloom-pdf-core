package dev.jaeyoung.fileloom.pdf.text

import dev.jaeyoung.fileloom.pdf.source.PdfByteSource
import dev.jaeyoung.fileloom.pdf.text.internal.ContentStreamInterpreter
import dev.jaeyoung.fileloom.pdf.text.internal.PageResources
import dev.jaeyoung.fileloom.pdf.text.internal.PdfDocument
import dev.jaeyoung.fileloom.pdf.text.internal.PdfPageWalker
import dev.jaeyoung.fileloom.pdf.text.internal.PdfStreamReader
import dev.jaeyoung.fileloom.pdf.text.internal.WalkedPage

/**
 * Extracts text content from a PDF in approximate reading order.
 *
 * Typical usage:
 *
 * ```kotlin
 * PdfTextExtractor.open(ByteArrayPdfByteSource(pdfBytes)).use { extractor ->
 *     for (page in 0 until extractor.pageCount) {
 *         val text = extractor.extractTextForPage(page)
 *         println(text)
 *     }
 * }
 * ```
 *
 * Unsupported PDFs (encrypted, xref-stream-only, exotic filters) yield empty
 * strings rather than throwing. Callers should check for blank output and
 * surface a "no readable text" affordance to the user.
 */
class PdfTextExtractor private constructor(
    private val document: PdfDocument,
    private val pages: List<WalkedPage>,
) : AutoCloseable {

    val pageCount: Int get() = pages.size

    fun extractTextForPage(pageIndex: Int): String {
        val page = pages.getOrNull(pageIndex) ?: return ""
        val contents = page.pageDictionary.entries["Contents"]
        val streamBytes = PdfStreamReader.extractContentStream(document, contents) ?: return ""
        if (streamBytes.isEmpty()) return ""

        val resources = PageResources(page.resources, document)
        val interpreter = ContentStreamInterpreter(resources)
        return interpreter.interpret(streamBytes).trim()
    }

    override fun close() {
        document.close()
    }

    companion object {
        /**
         * Open a PDF for text extraction. Returns null if the file isn't a
         * supported PDF variant (encrypted, malformed header, xref-stream-only,
         * etc.). Never throws.
         */
        fun open(source: PdfByteSource): PdfTextExtractor? {
            val document = PdfDocument.open(source) ?: return null
            val pages = PdfPageWalker(document).collect()
            return PdfTextExtractor(document, pages)
        }
    }
}
