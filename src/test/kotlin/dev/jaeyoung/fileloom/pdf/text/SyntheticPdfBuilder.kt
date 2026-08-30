package dev.jaeyoung.fileloom.pdf.text

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater

/**
 * Hand-crafted minimal PDFs for tests. We can't use any external PDF writer
 * (the library is supposed to be self-contained), so we build raw byte
 * sequences that match the PDF 1.4 structural minimum.
 */
internal object SyntheticPdfBuilder {

    fun helloWorld(): ByteArray = buildSimplePdf(
        content = "BT /F1 12 Tf 100 700 Td (Hello, World!) Tj ET",
    )

    fun twoLines(): ByteArray = buildSimplePdf(
        content = "BT /F1 12 Tf 100 700 Td (Line one) Tj 0 -20 Td (Line two) Tj ET",
    )

    fun encryptedStub(): ByteArray = buildSimplePdf(
        content = "BT /F1 12 Tf 100 700 Td (Should not see) Tj ET",
        includeEncryptStub = true,
    )

    /**
     * Emits a PDF whose trailer keyword and dict-open `<<` share a single
     * line — the exact pattern emitted by pdflatex / arXiv-style toolchains
     * that broke `fileloom-pdf-parser-core` 0.3.0's xref reader.
     *
     * Used by the regression test for the lenient `PdfDocument` introduced
     * in `fileloom-pdf-core` 0.1.1.
     */
    fun singleLineTrailer(): ByteArray = buildSimplePdf(
        content = "BT /F1 12 Tf 100 700 Td (Inline trailer page) Tj ET",
        singleLineTrailer = true,
    )

    fun twoPageOutline(): ByteArray {
        val pageOneContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter one page) Tj ET")
        val pageTwoContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter two page) Tj ET")
        return buildPdfObjects(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R /Outlines 8 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                pageOneContent,
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 7 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                pageTwoContent,
                "<< /Type /Outlines /First 9 0 R /Last 10 0 R /Count 2 >>",
                "<< /Title (Chapter 1) /Parent 8 0 R /Dest [3 0 R /XYZ null null null] /Next 10 0 R /First 11 0 R /Last 11 0 R /Count 1 >>",
                "<< /Title (Chapter 2) /Parent 8 0 R /Dest [6 0 R /Fit] /Prev 9 0 R >>",
                "<< /Title (Section 1.1) /Parent 9 0 R /Dest [6 0 R /Fit] >>",
            )
        )
    }

    fun twoPageOutlineWithTrailingBytes(trailingByteCount: Int): ByteArray {
        require(trailingByteCount >= 0)
        return twoPageOutline() +
            "\n% Fileloom outline compatibility tail\n".toByteArray(StandardCharsets.ISO_8859_1) +
            ByteArray(trailingByteCount) { ' '.code.toByte() }
    }

    fun twoPageOutlineWithIndirectTitles(): ByteArray {
        val pageOneContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter one page) Tj ET")
        val pageTwoContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter two page) Tj ET")
        return buildPdfObjects(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R /Outlines 8 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                pageOneContent,
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 7 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                pageTwoContent,
                "<< /Type /Outlines /First 9 0 R /Last 10 0 R /Count 2 >>",
                "<< /Title 12 0 R /Parent 8 0 R /Dest [3 0 R /XYZ null null null] /Next 10 0 R /First 11 0 R /Last 11 0 R /Count 1 >>",
                "<< /Title 13 0 R /Parent 8 0 R /Dest [6 0 R /Fit] /Prev 9 0 R >>",
                "<< /Title 14 0 R /Parent 9 0 R /Dest [6 0 R /Fit] >>",
                "(Indirect chapter 1)",
                "(Indirect chapter 2)",
                "(Indirect section)",
            )
        )
    }

    fun twoPageOutlineWithLegacyNamedDestinations(): ByteArray {
        val pageOneContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter one page) Tj ET")
        val pageTwoContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter two page) Tj ET")
        return buildPdfObjects(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R /Outlines 8 0 R /Dests 11 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                pageOneContent,
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 7 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                pageTwoContent,
                "<< /Type /Outlines /First 9 0 R /Last 10 0 R /Count 2 >>",
                "<< /Title (Named chapter 1) /Parent 8 0 R /Dest /ChapterOne /Next 10 0 R >>",
                "<< /Title (Named chapter 2) /Parent 8 0 R /A << /S /GoTo /D /ChapterTwo >> /Prev 9 0 R >>",
                "<< /ChapterOne [3 0 R /XYZ null null null] /ChapterTwo [6 0 R /Fit] >>",
            )
        )
    }

    fun twoPageOutlineWithNameTreeDestinations(): ByteArray {
        val pageOneContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter one page) Tj ET")
        val pageTwoContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter two page) Tj ET")
        return buildPdfObjects(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R /Outlines 8 0 R /Names << /Dests 11 0 R >> >>",
                "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                pageOneContent,
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 7 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                pageTwoContent,
                "<< /Type /Outlines /First 9 0 R /Last 10 0 R /Count 2 >>",
                "<< /Title (Name-tree chapter 1) /Parent 8 0 R /Dest (ChapterOne) /Next 10 0 R >>",
                "<< /Title (Name-tree chapter 2) /Parent 8 0 R /A << /S /GoTo /D (ChapterTwo) >> /Prev 9 0 R >>",
                "<< /Names [(ChapterOne) [3 0 R /XYZ null null null] (ChapterTwo) 12 0 R] >>",
                "<< /D [6 0 R /Fit] >>",
            )
        )
    }

    fun twoPageOutlineWithNamedActions(): ByteArray {
        val pageOneContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter one page) Tj ET")
        val pageTwoContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter two page) Tj ET")
        return buildPdfObjects(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R /Outlines 8 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                pageOneContent,
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 7 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                pageTwoContent,
                "<< /Type /Outlines /First 9 0 R /Last 11 0 R /Count 3 >>",
                "<< /Title (First page action) /Parent 8 0 R /A << /S /Named /N /FirstPage >> /Next 10 0 R >>",
                "<< /Title (Last page action) /Parent 8 0 R /A << /S /Named /N /LastPage >> /Prev 9 0 R /Next 11 0 R >>",
                "<< /Title (Remote action) /Parent 8 0 R /A << /S /GoToR /F (other.pdf) /D [1 /Fit] >> /Prev 10 0 R >>",
            )
        )
    }

    fun twoPageOutlineWithOutOfRangeIntegerDestination(): ByteArray {
        val pageOneContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter one page) Tj ET")
        val pageTwoContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter two page) Tj ET")
        return buildPdfObjects(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R /Outlines 8 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                pageOneContent,
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 7 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                pageTwoContent,
                "<< /Type /Outlines /First 9 0 R /Last 9 0 R /Count 1 >>",
                "<< /Title (Clamped chapter) /Parent 8 0 R /Dest [99 /Fit] >>",
            )
        )
    }

    fun twoPageOutlineWithXrefStream(): ByteArray {
        val pageOneContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter one page) Tj ET")
        val pageTwoContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter two page) Tj ET")
        return buildPdfObjectsWithXrefStream(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R /Outlines 8 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                pageOneContent,
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 7 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                pageTwoContent,
                "<< /Type /Outlines /First 9 0 R /Last 10 0 R /Count 2 >>",
                "<< /Title (Chapter 1) /Parent 8 0 R /Dest [3 0 R /XYZ null null null] /Next 10 0 R /First 11 0 R /Last 11 0 R /Count 1 >>",
                "<< /Title (Chapter 2) /Parent 8 0 R /Dest [6 0 R /Fit] /Prev 9 0 R >>",
                "<< /Title (Section 1.1) /Parent 9 0 R /Dest [6 0 R /Fit] >>",
            )
        )
    }

    fun twoPageOutlineWithXrefAndObjectStreams(): ByteArray {
        val pageOneContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter one page) Tj ET")
        val pageTwoContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter two page) Tj ET")
        return buildPdfObjectsWithCompressedOutline(
            regularObjects = listOf(
                1 to "<< /Type /Catalog /Pages 2 0 R /Outlines 8 0 R >>",
                2 to "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>",
                3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                4 to pageOneContent,
                5 to "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
                6 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 7 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                7 to pageTwoContent,
            ),
            compressedObjects = listOf(
                8 to "<< /Type /Outlines /First 9 0 R /Last 10 0 R /Count 2 >>",
                9 to "<< /Title (Compressed chapter 1) /Parent 8 0 R /Dest [3 0 R /XYZ null null null] /Next 10 0 R /First 11 0 R /Last 11 0 R /Count 1 >>",
                10 to "<< /Title (Compressed chapter 2) /Parent 8 0 R /Dest [6 0 R /Fit] /Prev 9 0 R >>",
                11 to "<< /Title (Compressed section) /Parent 9 0 R /Dest [6 0 R /Fit] >>",
            )
        )
    }

    fun twoPageOutlineWithFlateXrefAndObjectStreams(): ByteArray {
        val pageOneContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter one page) Tj ET")
        val pageTwoContent = streamObject("BT /F1 12 Tf 100 700 Td (Chapter two page) Tj ET")
        return buildPdfObjectsWithCompressedOutline(
            regularObjects = listOf(
                1 to "<< /Type /Catalog /Pages 2 0 R /Outlines 8 0 R >>",
                2 to "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>",
                3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                4 to pageOneContent,
                5 to "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
                6 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 7 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                7 to pageTwoContent,
            ),
            compressedObjects = listOf(
                8 to "<< /Type /Outlines /First 9 0 R /Last 10 0 R /Count 2 >>",
                9 to "<< /Title (Compressed chapter 1) /Parent 8 0 R /Dest [3 0 R /XYZ null null null] /Next 10 0 R /First 11 0 R /Last 11 0 R /Count 1 >>",
                10 to "<< /Title (Compressed chapter 2) /Parent 8 0 R /Dest [6 0 R /Fit] /Prev 9 0 R >>",
                11 to "<< /Title (Compressed section) /Parent 9 0 R /Dest [6 0 R /Fit] >>",
            ),
            flateObjectStream = true,
            flateXrefStream = true,
        )
    }

    private fun buildSimplePdf(
        content: String,
        includeEncryptStub: Boolean = false,
        singleLineTrailer: Boolean = false,
    ): ByteArray {
        val contentBytes = content.toByteArray(StandardCharsets.ISO_8859_1)
        val compressed = deflate(contentBytes)

        val objects = mutableListOf<String>()
        // 1: Catalog
        val trailerEncryptEntry = if (includeEncryptStub) " /Encrypt 7 0 R" else ""
        objects += "<< /Type /Catalog /Pages 2 0 R >>"
        // 2: Pages
        objects += "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
        // 3: Page
        objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>"
        // 4: Content stream (FlateDecode)
        val contentObj = StringBuilder().apply {
            append("<< /Length ${compressed.size} /Filter /FlateDecode >>\n")
            append("stream\n")
        }.toString()
        // 5: Font
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"

        // Now assemble the actual binary stream.
        val output = ByteArrayOutputStream()
        val offsets = mutableListOf<Long>()
        output.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(StandardCharsets.ISO_8859_1))

        // Write each object, capturing its offset.
        offsets += output.size().toLong()
        output.write("1 0 obj\n${objects[0]}\nendobj\n".toByteArray(StandardCharsets.ISO_8859_1))

        offsets += output.size().toLong()
        output.write("2 0 obj\n${objects[1]}\nendobj\n".toByteArray(StandardCharsets.ISO_8859_1))

        offsets += output.size().toLong()
        output.write("3 0 obj\n${objects[2]}\nendobj\n".toByteArray(StandardCharsets.ISO_8859_1))

        // Object 4: content stream with binary payload
        offsets += output.size().toLong()
        output.write("4 0 obj\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write(contentObj.toByteArray(StandardCharsets.ISO_8859_1))
        output.write(compressed)
        output.write("\nendstream\nendobj\n".toByteArray(StandardCharsets.ISO_8859_1))

        offsets += output.size().toLong()
        output.write("5 0 obj\n${objects[3]}\nendobj\n".toByteArray(StandardCharsets.ISO_8859_1))

        // Optional encrypt stub
        if (includeEncryptStub) {
            offsets += output.size().toLong()
            output.write("6 0 obj\n<< >>\nendobj\n".toByteArray(StandardCharsets.ISO_8859_1))
            offsets += output.size().toLong()
            output.write("7 0 obj\n<< /Filter /Standard /V 1 /R 2 /Length 40 >>\nendobj\n"
                .toByteArray(StandardCharsets.ISO_8859_1))
        }

        // xref
        val xrefOffset = output.size().toLong()
        val totalObjects = offsets.size + 1
        val xrefBuilder = StringBuilder()
        xrefBuilder.append("xref\n")
        xrefBuilder.append("0 $totalObjects\n")
        xrefBuilder.append("0000000000 65535 f \n")
        for (offset in offsets) {
            xrefBuilder.append(offset.toString().padStart(10, '0')).append(" 00000 n \n")
        }
        output.write(xrefBuilder.toString().toByteArray(StandardCharsets.ISO_8859_1))

        // trailer
        val trailerBuilder = StringBuilder()
        if (singleLineTrailer) {
            trailerBuilder.append("trailer << /Size $totalObjects /Root 1 0 R$trailerEncryptEntry >>\n")
        } else {
            trailerBuilder.append("trailer\n")
            trailerBuilder.append("<< /Size $totalObjects /Root 1 0 R$trailerEncryptEntry >>\n")
        }
        trailerBuilder.append("startxref\n")
        trailerBuilder.append("$xrefOffset\n")
        trailerBuilder.append("%%EOF\n")
        output.write(trailerBuilder.toString().toByteArray(StandardCharsets.ISO_8859_1))

        return output.toByteArray()
    }

    private fun streamObject(content: String): String {
        val compressed = deflate(content.toByteArray(StandardCharsets.ISO_8859_1))
        return buildString {
            append("<< /Length ${compressed.size} /Filter /FlateDecode >>\n")
            append("stream\n")
            append(compressed.toString(StandardCharsets.ISO_8859_1))
            append("\nendstream")
        }
    }

    private fun buildPdfObjects(objects: List<String>): ByteArray {
        val output = ByteArrayOutputStream()
        val offsets = mutableListOf<Long>()
        output.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(StandardCharsets.ISO_8859_1))

        objects.forEachIndexed { index, body ->
            offsets += output.size().toLong()
            output.write("${index + 1} 0 obj\n$body\nendobj\n".toByteArray(StandardCharsets.ISO_8859_1))
        }

        val xrefOffset = output.size().toLong()
        val totalObjects = objects.size + 1
        val xrefBuilder = StringBuilder()
        xrefBuilder.append("xref\n")
        xrefBuilder.append("0 $totalObjects\n")
        xrefBuilder.append("0000000000 65535 f \n")
        for (offset in offsets) {
            xrefBuilder.append(offset.toString().padStart(10, '0')).append(" 00000 n \n")
        }
        output.write(xrefBuilder.toString().toByteArray(StandardCharsets.ISO_8859_1))

        val trailer = buildString {
            append("trailer\n")
            append("<< /Size $totalObjects /Root 1 0 R >>\n")
            append("startxref\n")
            append("$xrefOffset\n")
            append("%%EOF\n")
        }
        output.write(trailer.toByteArray(StandardCharsets.ISO_8859_1))
        return output.toByteArray()
    }

    private fun buildPdfObjectsWithXrefStream(objects: List<String>): ByteArray {
        val output = ByteArrayOutputStream()
        val offsets = mutableListOf<Long>()
        output.write("%PDF-1.5\n%âãÏÓ\n".toByteArray(StandardCharsets.ISO_8859_1))

        objects.forEachIndexed { index, body ->
            offsets += output.size().toLong()
            output.write("${index + 1} 0 obj\n$body\nendobj\n".toByteArray(StandardCharsets.ISO_8859_1))
        }

        val xrefObjectNumber = objects.size + 1
        val xrefOffset = output.size().toLong()
        val totalObjects = xrefObjectNumber + 1
        val xrefEntries = ByteArrayOutputStream()
        writeXrefStreamEntry(xrefEntries, type = 0, field1 = 0, field2 = 65535)
        offsets.forEach { objectOffset ->
            writeXrefStreamEntry(xrefEntries, type = 1, field1 = objectOffset, field2 = 0)
        }
        writeXrefStreamEntry(xrefEntries, type = 1, field1 = xrefOffset, field2 = 0)
        val xrefBytes = xrefEntries.toByteArray()

        output.write("$xrefObjectNumber 0 obj\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write(
            "<< /Type /XRef /Size $totalObjects /Root 1 0 R /W [1 4 2] /Index [0 $totalObjects] /Length ${xrefBytes.size} >>\n"
                .toByteArray(StandardCharsets.ISO_8859_1)
        )
        output.write("stream\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write(xrefBytes)
        output.write("\nendstream\nendobj\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write("startxref\n$xrefOffset\n%%EOF\n".toByteArray(StandardCharsets.ISO_8859_1))
        return output.toByteArray()
    }

    private fun buildPdfObjectsWithCompressedOutline(
        regularObjects: List<Pair<Int, String>>,
        compressedObjects: List<Pair<Int, String>>,
        flateObjectStream: Boolean = false,
        flateXrefStream: Boolean = false,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val objectOffsets = linkedMapOf<Int, Long>()
        output.write("%PDF-1.5\n%âãÏÓ\n".toByteArray(StandardCharsets.ISO_8859_1))

        regularObjects.forEach { (objectNumber, body) ->
            objectOffsets[objectNumber] = output.size().toLong()
            output.write("$objectNumber 0 obj\n$body\nendobj\n".toByteArray(StandardCharsets.ISO_8859_1))
        }

        val objectStreamNumber = 12
        val objectStreamBytes = buildObjectStreamBytes(compressedObjects)
        val objectStreamPayload = if (flateObjectStream) deflate(objectStreamBytes.second) else objectStreamBytes.second
        val objectStreamFilter = if (flateObjectStream) " /Filter /FlateDecode" else ""
        objectOffsets[objectStreamNumber] = output.size().toLong()
        output.write("$objectStreamNumber 0 obj\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write(
            "<< /Type /ObjStm /N ${compressedObjects.size} /First ${objectStreamBytes.first} /Length ${objectStreamPayload.size}$objectStreamFilter >>\n"
                .toByteArray(StandardCharsets.ISO_8859_1)
        )
        output.write("stream\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write(objectStreamPayload)
        output.write("\nendstream\nendobj\n".toByteArray(StandardCharsets.ISO_8859_1))

        val xrefObjectNumber = 13
        val xrefOffset = output.size().toLong()
        val totalObjects = xrefObjectNumber + 1
        val xrefEntries = ByteArrayOutputStream()
        writeXrefStreamEntry(xrefEntries, type = 0, field1 = 0, field2 = 65535)
        for (objectNumber in 1 until xrefObjectNumber) {
            val regularOffset = objectOffsets[objectNumber]
            if (regularOffset != null) {
                writeXrefStreamEntry(xrefEntries, type = 1, field1 = regularOffset, field2 = 0)
            } else {
                val compressedIndex = compressedObjects.indexOfFirst { it.first == objectNumber }
                require(compressedIndex >= 0) { "missing test object $objectNumber" }
                writeXrefStreamEntry(xrefEntries, type = 2, field1 = objectStreamNumber.toLong(), field2 = compressedIndex)
            }
        }
        writeXrefStreamEntry(xrefEntries, type = 1, field1 = xrefOffset, field2 = 0)
        val rawXrefBytes = xrefEntries.toByteArray()
        val xrefBytes = if (flateXrefStream) deflate(rawXrefBytes) else rawXrefBytes
        val xrefStreamFilter = if (flateXrefStream) " /Filter /FlateDecode" else ""

        output.write("$xrefObjectNumber 0 obj\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write(
            "<< /Type /XRef /Size $totalObjects /Root 1 0 R /W [1 4 2] /Index [0 $totalObjects] /Length ${xrefBytes.size}$xrefStreamFilter >>\n"
                .toByteArray(StandardCharsets.ISO_8859_1)
        )
        output.write("stream\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write(xrefBytes)
        output.write("\nendstream\nendobj\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write("startxref\n$xrefOffset\n%%EOF\n".toByteArray(StandardCharsets.ISO_8859_1))
        return output.toByteArray()
    }

    private fun buildObjectStreamBytes(objects: List<Pair<Int, String>>): Pair<Int, ByteArray> {
        val bodies = objects.map { (_, body) -> body.toByteArray(StandardCharsets.ISO_8859_1) }
        var offset = 0
        val offsets = bodies.map { body ->
            val current = offset
            offset += body.size + 1
            current
        }
        val header = objects.mapIndexed { index, (objectNumber, _) ->
            "$objectNumber ${offsets[index]}"
        }.joinToString(separator = " ", postfix = " ")
        val headerBytes = header.toByteArray(StandardCharsets.ISO_8859_1)
        val output = ByteArrayOutputStream()
        output.write(headerBytes)
        bodies.forEachIndexed { index, body ->
            if (index > 0) output.write(' '.code)
            output.write(body)
        }
        return headerBytes.size to output.toByteArray()
    }

    private fun writeXrefStreamEntry(
        output: ByteArrayOutputStream,
        type: Int,
        field1: Long,
        field2: Int,
    ) {
        output.write(type)
        output.write(((field1 shr 24) and 0xff).toInt())
        output.write(((field1 shr 16) and 0xff).toInt())
        output.write(((field1 shr 8) and 0xff).toInt())
        output.write((field1 and 0xff).toInt())
        output.write((field2 shr 8) and 0xff)
        output.write(field2 and 0xff)
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(input)
        deflater.finish()
        val buffer = ByteArray(input.size * 2 + 64)
        val total = deflater.deflate(buffer)
        deflater.end()
        return buffer.copyOf(total)
    }
}
