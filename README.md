# fileloom-pdf-core

PDF content/text extraction core library for Fileloom.

Coordinates:

```kotlin
implementation("dev.jaeyoung:fileloom-pdf-core:0.1.0")
```

## Goal

Provide a pure Kotlin/JVM layer that consumes a PDF and exposes its text content in reading order, so Fileloom can:

1. Surface PDF text to TalkBack (Android Screen Reader) via Compose semantics overlays on top of the existing `PdfRenderer` bitmap.
2. Feed the EPUB TTS engine arbitrary `String` content for "read aloud" on PDFs.

This is the text-extraction half of the broader PDF roadmap. Outline (TOC) walking and PDF writing (image-only pages) are planned as separate modules in the same artifact.

## Scope (v0.1)

In scope:

- Page tree walking (handles inherited `MediaBox`, `CropBox`, `Rotate`, nested `Kids`).
- Content-stream extraction (find `stream...endstream`, apply `/Filter` pipeline).
- Filter pipeline: `FlateDecode` (zlib), `ASCIIHexDecode`, `ASCII85Decode`. (LZW deferred — uncommon in modern PDFs.)
- Content-stream tokenizer + text operators: `Tj`, `TJ`, `'`, `"`, `Tf`, `Tm`, `Td`, `TD`, `T*`, `Tw`, `Tc`, `TL`, `BT`, `ET`.
- Font dictionary parsing: `Type0`, `Type1`, `TrueType`, `MMType1` (treated like Type1).
- **ToUnicode CMap** parser (`bfchar` + `bfrange` entries). This is the load-bearing piece — without it, glyph codes don't map back to readable Unicode.
- Standard-14 encoding fallback (`WinAnsiEncoding`, `MacRomanEncoding`, `StandardEncoding`).
- Heuristic reading-order: top-to-bottom by Y, left-to-right by X within a line band.

Explicitly **out of scope** for v0.1:

- Encrypted PDFs (use `dev.jaeyoung:fileloom-pdf-security-core` first to decrypt to a plaintext temp file).
- Cross-reference streams (PDF 1.5+ binary xref). The dependency `fileloom-pdf-parser-core:0.3.0` only handles classic xref tables; PDFs that use xref streams will be skipped with a graceful empty result.
- LZW/CCITT/JBIG2/DCT filters.
- Embedded-font glyph rendering (we only need glyph code -> Unicode, never code -> shape).
- OCR for scanned/image-only PDFs.
- Text formatting preservation (paragraphs, columns, tables). Output is plain text in approximate reading order.

## Reference research

Per the Fileloom in-house library policy, the design borrows from these reference implementations (each used for *learning*, not copy-paste):

| Rank | Library | Lang | License | Notes on what was studied |
|---|---|---|---|---|
| 1 | [Apache PDFBox](https://github.com/apache/pdfbox) | Java | Apache 2.0 | `org.apache.pdfbox.contentstream.PDFStreamEngine` for the operator-dispatcher pattern. Text-extraction utility (`PDFTextStripper`) for line-bucketing logic. PDFBox is overkill for our scope but its operator table maps 1:1 to the spec. |
| 2 | [pdf.js (Mozilla)](https://github.com/mozilla/pdf.js) | JS | Apache 2.0 | `core/evaluator.js` for ToUnicode CMap handling — the cleanest readable reference for `bfchar`/`bfrange`. `core/cmap.js` parsing approach informed our CMap tokenizer. Their fallback chain (ToUnicode → embedded encoding → standard encoding) is what we mirror. |
| 3 | [MuPDF](https://github.com/ArtifexSoftware/mupdf) | C | AGPL | `pdf-cmap-parse.c` for low-level CMap tokenizer edge cases (hex escapes inside bf entries). AGPL — read for learning only. |
| 4 | [pdf-rs](https://github.com/pdf-rs/pdf) | Rust | MIT | `pdf::content` operator dispatch using a sealed enum maps cleanly to Kotlin's sealed-class pattern. Object-model layering inspired the split between `parser-core` (lexer/object model) and `pdf-core` (content semantics). |
| 5 | [PdfBox-Android (Tom Roush)](https://github.com/TomRoush/PdfBox-Android) | Java | Apache 2.0 | Showed which PDFBox modules are safely droppable on Android (no AWT/Java2D). Validated that pure-Kotlin text extraction doesn't need any AWT-shaped APIs. |

### Design decisions traced to references

- **Operator dispatch via sealed-enum table** — pdf-rs.
- **Two-pass reading order: collect (x,y,text) tuples, then bucket by line band** — PDFBox's `PDFTextStripper` strategy.
- **ToUnicode fallback chain** (ToUnicode → embedded font encoding → standard-14 named encoding → raw byte) — pdf.js `core/evaluator.js`.
- **CMap `bfrange` with array form (`<0001> <0003> [<...> <...> <...>]`)** — both pdf.js and MuPDF handle this; we model the same.
- **Skip cross-reference streams gracefully** — borrows the "fail soft, return empty text" stance from PdfBox-Android in handling unsupported PDF variants.

## Layering

This library sits on top of `dev.jaeyoung:fileloom-pdf-parser-core:0.3.0`, which provides:

- `PdfLexer`, `PdfObjectParser`, `PdfObject` (sealed hierarchy)
- `PdfDocumentReader.open(source)` → `PdfLowLevelDocument`
- `PdfLowLevelDocument.resolve(reference)` — indirect-object resolution
- `PdfByteSource` / `ByteArrayPdfByteSource`

We add on top:

- `dev.jaeyoung.fileloom.pdf.text.PdfTextExtractor` — public entry point.
- `dev.jaeyoung.fileloom.pdf.text.internal.*` — content stream + font + CMap.

## Public API

```kotlin
val source = ByteArrayPdfByteSource(pdfBytes)
val extractor = PdfTextExtractor.open(source)
val pageCount = extractor.pageCount
val pageText: String = extractor.extractTextForPage(pageIndex = 0)
extractor.close()
```

## Requirements

- JDK 17
- Gradle 8.13+ (wrapper provided)

Run tests:

```bash
./gradlew test
```

## Publishing

Local development:

```bash
./gradlew publishToMavenLocal
```

Maven Central bundle (signs/checksums to be added manually before upload):

```bash
./gradlew publishToMavenCentralBundle
# Then GPG-sign each artifact + add .md5/.sha1 checksums per the policy in
# Fileloom's FEEDBACK_TODO.md "Publication target" section.
```

## License

MIT. See `LICENSE`.
