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
