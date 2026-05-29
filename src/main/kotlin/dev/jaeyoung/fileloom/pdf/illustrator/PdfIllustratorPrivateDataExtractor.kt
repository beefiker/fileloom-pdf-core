package dev.jaeyoung.fileloom.pdf.illustrator

import dev.jaeyoung.fileloom.pdf.source.PdfByteSource
import dev.jaeyoung.fileloom.pdf.syntax.PdfObject
import dev.jaeyoung.fileloom.pdf.text.internal.PdfDocument
import dev.jaeyoung.fileloom.pdf.text.internal.PdfPageWalker
import dev.jaeyoung.fileloom.pdf.text.internal.PdfStreamReader

object PdfIllustratorPrivateDataExtractor {
    fun extractBlocks(
        source: PdfByteSource,
        pageIndex: Int = 0,
    ): List<ByteArray> {
        return PdfDocument.open(source)?.use { document ->
            extractBlocks(document, pageIndex)
        } ?: emptyList()
    }

    fun extractFirstAvailableBlocks(source: PdfByteSource): List<ByteArray> {
        return PdfDocument.open(source)?.use { document ->
            PdfPageWalker(document).collect()
                .asSequence()
                .map { page -> extractBlocks(document, page.pageDictionary) }
                .firstOrNull { blocks -> blocks.isNotEmpty() }
                ?: emptyList()
        } ?: emptyList()
    }

    private fun extractBlocks(
        document: PdfDocument,
        pageIndex: Int,
    ): List<ByteArray> {
        val page = PdfPageWalker(document).collect().getOrNull(pageIndex) ?: return emptyList()
        return extractBlocks(document, page.pageDictionary)
    }

    private fun extractBlocks(
        document: PdfDocument,
        pageDictionary: PdfObject.Dictionary,
    ): List<ByteArray> {
        val pieceInfo = document.deref(pageDictionary.entries["PieceInfo"]) as? PdfObject.Dictionary
            ?: return emptyList()
        val illustrator = document.deref(pieceInfo.entries["Illustrator"]) as? PdfObject.Dictionary
            ?: return emptyList()
        val privateData = document.deref(illustrator.entries["Private"]) as? PdfObject.Dictionary
            ?: return emptyList()
        val blockNumbers = privateData.blockNumbers()
        val numbersFromCount = privateData.numBlock(document)?.let { count ->
            (1..count).filter { number -> number in blockNumbers }
        }
        val orderedNumbers = if (numbersFromCount != null && numbersFromCount.size == blockNumbers.size) {
            numbersFromCount
        } else {
            blockNumbers.sorted()
        }
        return orderedNumbers.mapNotNull { number ->
            privateData.entries["AIPrivateData$number"]?.let { value ->
                readBytes(document, value)
            }
        }
    }

    private fun PdfObject.Dictionary.blockNumbers(): Set<Int> {
        return entries.keys
            .mapNotNull { key ->
                blockNamePattern.matchEntire(key)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            .toSet()
    }

    private fun PdfObject.Dictionary.numBlock(document: PdfDocument): Int? {
        return when (val value = document.deref(entries["NumBlock"])) {
            is PdfObject.IntegerValue -> value.value.toInt()
            else -> null
        }?.coerceAtLeast(0)
    }

    private fun readBytes(
        document: PdfDocument,
        value: PdfObject,
    ): ByteArray? {
        return when (value) {
            is PdfObject.StringValue -> value.bytes
            is PdfObject.Reference -> {
                when (val resolved = document.resolve(value)) {
                    is PdfObject.StringValue -> resolved.bytes
                    else -> PdfStreamReader.extractStream(document, value)
                }
            }
            else -> null
        }
    }

    private val blockNamePattern = Regex("""AIPrivateData(\d+)""")
}
