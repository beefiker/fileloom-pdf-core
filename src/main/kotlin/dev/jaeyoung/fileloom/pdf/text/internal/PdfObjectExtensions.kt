package dev.jaeyoung.fileloom.pdf.text.internal

import dev.jaeyoung.fileloom.pdf.syntax.PdfObject
import java.nio.charset.StandardCharsets

internal fun PdfObject?.asDictionary(): PdfObject.Dictionary? = this as? PdfObject.Dictionary

internal fun PdfObject?.asArray(): PdfObject.ArrayValue? = this as? PdfObject.ArrayValue

internal fun PdfObject?.asName(): String? = (this as? PdfObject.Name)?.value

internal fun PdfObject?.asNumber(): Double? = when (this) {
    is PdfObject.IntegerValue -> value.toDouble()
    is PdfObject.RealValue -> value
    else -> null
}

internal fun PdfObject?.asInt(): Int? = (this as? PdfObject.IntegerValue)?.value?.toInt()

internal fun PdfObject.StringValue.decodePdfTextString(): String {
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
    }
    return bytes.toString(StandardCharsets.ISO_8859_1)
}

internal fun serializePdfObject(value: PdfObject): String = when (value) {
    PdfObject.Null -> "null"
    is PdfObject.BooleanValue -> if (value.value) "true" else "false"
    is PdfObject.IntegerValue -> value.value.toString()
    is PdfObject.RealValue -> formatPdfNumber(value.value)
    is PdfObject.Name -> "/${escapePdfName(value.value)}"
    is PdfObject.StringValue -> value.bytes.joinToString(prefix = "<", postfix = ">") {
        "%02X".format(it.toInt() and 0xff)
    }
    is PdfObject.ArrayValue -> value.items.joinToString(prefix = "[", postfix = "]", separator = " ") {
        serializePdfObject(it)
    }
    is PdfObject.Dictionary -> value.entries.entries.joinToString(prefix = "<<", postfix = ">>", separator = " ") {
        "/${escapePdfName(it.key)} ${serializePdfObject(it.value)}"
    }
    is PdfObject.Reference -> "${value.objectNumber} ${value.generationNumber} R"
}

internal fun formatPdfNumber(value: Float): String = formatPdfNumber(value.toDouble())

internal fun formatPdfNumber(value: Double): String {
    val normalized = if (kotlin.math.abs(value) < 0.0001) 0.0 else value
    val integer = normalized.toLong()
    if (normalized == integer.toDouble()) return integer.toString()
    return "%.4f".format(java.util.Locale.US, normalized).trimEnd('0').trimEnd('.')
}

internal fun escapePdfLiteralString(value: String): String {
    val builder = StringBuilder(value.length)
    value.forEach { char ->
        when (char) {
            '\\' -> builder.append("\\\\")
            '(' -> builder.append("\\(")
            ')' -> builder.append("\\)")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            else -> builder.append(char)
        }
    }
    return builder.toString()
}

private fun escapePdfName(value: String): String = buildString(value.length) {
    value.forEach { char ->
        if (char.code in 33..126 && char !in "#/%()<>[]{}") {
            append(char)
        } else {
            append('#')
            append(char.code.toString(16).uppercase().padStart(2, '0'))
        }
    }
}
