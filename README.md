# fileloom-pdf-core

PDF text, outline, and annotation core library for Fileloom.

Coordinates:

```kotlin
implementation("dev.jaeyoung:fileloom-pdf-core:0.2.0")
```

## Goal

Provide a pure Kotlin/JVM layer that consumes a PDF and exposes reader-focused metadata, so Fileloom can:

1. Surface PDF text to TalkBack (Android Screen Reader) via Compose semantics overlays on top of the existing `PdfRenderer` bitmap.
2. Feed the EPUB TTS engine arbitrary `String` content for "read aloud" on PDFs.
3. Show PDF outline trees as a table of contents.
4. Export Fileloom's persisted highlights and sticky notes as standard PDF annotations.
5. Share small renderer policy primitives so the app can keep Android
   `PdfRenderer` work bounded, bucketed, and measurable.

This remains intentionally small: Fileloom still renders pages with Android `PdfRenderer`, while this library handles the metadata and write-back paths that the platform renderer does not expose consistently.

## Scope (v0.2)

In scope:

- Page tree walking (handles inherited `MediaBox`, `CropBox`, `Rotate`, nested `Kids`).
- Content-stream extraction (find `stream...endstream`, apply `/Filter` pipeline).
- Filter pipeline: `FlateDecode` (zlib), `ASCIIHexDecode`, `ASCII85Decode`. (LZW deferred — uncommon in modern PDFs.)
- Content-stream tokenizer + text operators: `Tj`, `TJ`, `'`, `"`, `Tf`, `Tm`, `Td`, `TD`, `T*`, `Tw`, `Tc`, `TL`, `BT`, `ET`.
- Font dictionary parsing: `Type0`, `Type1`, `TrueType`, `MMType1` (treated like Type1).
- **ToUnicode CMap** parser (`bfchar` + `bfrange` entries). This is the load-bearing piece — without it, glyph codes don't map back to readable Unicode.
- Standard-14 encoding fallback (`WinAnsiEncoding`, `MacRomanEncoding`, `StandardEncoding`).
- Heuristic reading-order: top-to-bottom by Y, left-to-right by X within a line band.
- Outline extraction: walks `/Outlines` linked lists, preserves nested children, resolves named/internal actions, and supports classic xref tables plus PDF 1.5 xref/object streams.
- Incremental annotation export: appends Highlight and Text/sticky-note annotation objects, updates page `/Annots`, preserves the original bytes, and links the new xref to the previous one with `/Prev`.
- Render policy primitives: stable width buckets, safe render dimensions, fallback widths, request keys, tile keys, priority reasons, and telemetry event models. The Android renderer and coroutine scheduler remain app-side.

Explicitly **out of scope** for v0.2:

- Encrypted PDFs (use `dev.jaeyoung:fileloom-pdf-security-core` first to decrypt to a plaintext temp file).
- LZW/CCITT/JBIG2/DCT filters.
- Embedded-font glyph rendering (we only need glyph code -> Unicode, never code -> shape).
- OCR for scanned/image-only PDFs.
- Text formatting preservation (paragraphs, columns, tables). Output is plain text in approximate reading order.
- Full annotation editing, ink/stylus pressure, underline/strikethrough export, form filling, and optional-content/layer browser support.
- Android `PdfRenderer` ownership, bitmap allocation, coroutine scheduling, and clipped/tile rendering execution.

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
- **Fail softly on unsupported PDF variants** — borrows the "return empty text" stance from PdfBox-Android for encrypted files, malformed structures, or streams whose filters are outside this lightweight parser's scope.

## Layering

This library sits on top of `dev.jaeyoung:fileloom-pdf-parser-core:0.3.0`, which provides:

- `PdfLexer`, `PdfObjectParser`, `PdfObject` (sealed hierarchy)
- `PdfDocumentReader.open(source)` → `PdfLowLevelDocument`
- `PdfLowLevelDocument.resolve(reference)` — indirect-object resolution
- `PdfByteSource` / `ByteArrayPdfByteSource`

We add on top:

- `dev.jaeyoung.fileloom.pdf.text.PdfTextExtractor` — public entry point.
- `dev.jaeyoung.fileloom.pdf.outline.PdfOutlineExtractor` — outline / TOC entry point.
- `dev.jaeyoung.fileloom.pdf.annotation.PdfAnnotationWriter` — incremental Highlight and sticky-note export.
- `dev.jaeyoung.fileloom.pdf.render.PdfRenderPolicy` — pure render sizing and cache-key policy.
- `dev.jaeyoung.fileloom.pdf.render.PdfRenderRequest` — pure render request/key model.
- `dev.jaeyoung.fileloom.pdf.render.PdfRenderTelemetryEvent` — pure render measurement model.
- `dev.jaeyoung.fileloom.pdf.text.internal.*` — content stream + font + CMap.

## Public API

```kotlin
val source = ByteArrayPdfByteSource(pdfBytes)
val extractor = PdfTextExtractor.open(source)
val pageCount = extractor.pageCount
val pageText: String = extractor.extractTextForPage(pageIndex = 0)
extractor.close()
```

```kotlin
PdfOutlineExtractor.open(ByteArrayPdfByteSource(pdfBytes)).use { extractor ->
    val toc: List<PdfTocEntry> = extractor?.extractTableOfContents().orEmpty()
}
```

```kotlin
val annotatedBytes = PdfAnnotationWriter.appendAnnotations(
    pdfBytes = originalBytes,
    annotations = listOf(
        PdfAnnotation.Highlight(
            pageIndex = 0,
            rects = listOf(PdfAnnotationRect(left = 96f, top = 84f, right = 260f, bottom = 110f)),
            color = PdfAnnotationColor(red = 1f, green = 0.92f, blue = 0.23f),
            contents = "Important"
        )
    )
)
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

Maven Central bundle ZIP:

```bash
./gradlew publishToMavenCentralBundle
```

For non-interactive signing, pass the GPG passphrase explicitly:

```bash
SIGNING_GNUPG_PASSPHRASE='your-passphrase' ./gradlew publishToMavenCentralBundle -Pversion=0.2.3
```

The task writes `build/maven-central-bundle/fileloom-pdf-core-<version>-maven-central-bundle.zip`.
It stages the jar, sources jar, javadoc jar, and POM; creates `.md5` and `.sha1`
checksums; and GPG-signs each artifact with `gpg --detach-sign --armor`.
Set `-Psigning.gnupg.keyName=<KEY_ID>` to force a specific key, or
`-Psigning.gnupg.passphrase=<PASSPHRASE>` instead of the environment variable
above. Otherwise GPG's default secret key is used. This task only creates the
ZIP; upload to Maven Central is a separate manual step.

## License

MIT. See `LICENSE`.
