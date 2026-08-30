package dev.jaeyoung.fileloom.pdf.outline

import dev.jaeyoung.fileloom.pdf.source.ByteArrayPdfByteSource
import dev.jaeyoung.fileloom.pdf.text.SyntheticPdfBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PdfOutlineExtractorTest {

    @Test
    fun opensOutlineWhenStartXrefIsBeyondLegacyTailWindow() {
        val extractor = PdfOutlineExtractor.open(
            ByteArrayPdfByteSource(
                SyntheticPdfBuilder.twoPageOutlineWithTrailingBytes(8 * 1024)
            )
        )
        assertNotNull(extractor)

        extractor.use {
            assertEquals(
                listOf("Chapter 1", "Chapter 2"),
                it.extractTableOfContents().map(PdfTocEntry::title),
            )
        }
    }

    @Test
    fun findsStartXrefWhenMarkerCrossesAWindowBoundary() {
        val bytes = SyntheticPdfBuilder.twoPageOutlineWithStartXrefDistanceFromEof(
            64 * 1024 + 4
        )
        val extractor = PdfOutlineExtractor.open(ByteArrayPdfByteSource(bytes))
        assertNotNull(extractor)

        extractor.use {
            assertEquals(2, it.extractTableOfContents().size)
        }
    }

    @Test
    fun refusesToScanBeyondOneMiBTailBudget() {
        val bytes = SyntheticPdfBuilder.twoPageOutlineWithStartXrefDistanceFromEof(
            1024 * 1024 + 256
        )

        assertNull(PdfOutlineExtractor.open(ByteArrayPdfByteSource(bytes)))
    }

    @Test
    fun skipsTrailingStartXrefTextThatDoesNotPointToAnXrefStructure() {
        val extractor = PdfOutlineExtractor.open(
            ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithTrailingBogusStartXref())
        )
        assertNotNull(extractor)

        extractor.use {
            assertEquals(listOf("Chapter 1", "Chapter 2"), it.extractTableOfContents().map(PdfTocEntry::title))
        }
    }

    @Test
    fun skipsTrailingStartXrefTargetThatOnlyStartsWithXrefText() {
        val extractor = PdfOutlineExtractor.open(
            ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithTrailingXrefPrefixTarget())
        )
        assertNotNull(extractor)

        extractor.use {
            assertEquals(listOf("Chapter 1", "Chapter 2"), it.extractTableOfContents().map(PdfTocEntry::title))
        }
    }

    @Test
    fun extractsNestedOutlineTreeWithPageIndexes() {
        val extractor = PdfOutlineExtractor.open(ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutline()))
        assertNotNull(extractor)

        extractor.use {
            val toc = it.extractTableOfContents()

            assertEquals(2, toc.size)
            assertEquals(PdfTocEntry("Chapter 1", pageIndex = 0, children = listOf(
                PdfTocEntry("Section 1.1", pageIndex = 1)
            )), toc[0])
            assertEquals(PdfTocEntry("Chapter 2", pageIndex = 1), toc[1])
        }
    }

    @Test
    fun extractsOutlineEntriesWhoseTitlesAreIndirectStringObjects() {
        val extractor = PdfOutlineExtractor.open(ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithIndirectTitles()))
        assertNotNull(extractor)

        extractor.use {
            val toc = it.extractTableOfContents()

            assertEquals(
                listOf(
                    PdfTocEntry("Indirect chapter 1", pageIndex = 0, children = listOf(
                        PdfTocEntry("Indirect section", pageIndex = 1)
                    )),
                    PdfTocEntry("Indirect chapter 2", pageIndex = 1),
                ),
                toc
            )
        }
    }

    @Test
    fun resolvesOutlineNamedDestinationsFromLegacyDestsDictionary() {
        val extractor = PdfOutlineExtractor.open(
            ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithLegacyNamedDestinations())
        )
        assertNotNull(extractor)

        extractor.use {
            val toc = it.extractTableOfContents()

            assertEquals(
                listOf(
                    PdfTocEntry("Named chapter 1", pageIndex = 0),
                    PdfTocEntry("Named chapter 2", pageIndex = 1),
                ),
                toc
            )
        }
    }

    @Test
    fun resolvesOutlineNamedDestinationsFromNameTree() {
        val extractor = PdfOutlineExtractor.open(
            ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithNameTreeDestinations())
        )
        assertNotNull(extractor)

        extractor.use {
            val toc = it.extractTableOfContents()

            assertEquals(
                listOf(
                    PdfTocEntry("Name-tree chapter 1", pageIndex = 0),
                    PdfTocEntry("Name-tree chapter 2", pageIndex = 1),
                ),
                toc
            )
        }
    }

    @Test
    fun extractsOutlineFromXrefStreamPdf() {
        val extractor = PdfOutlineExtractor.open(ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithXrefStream()))
        assertNotNull(extractor)

        extractor.use {
            val toc = it.extractTableOfContents()

            assertEquals(
                listOf(
                    PdfTocEntry("Chapter 1", pageIndex = 0, children = listOf(
                        PdfTocEntry("Section 1.1", pageIndex = 1)
                    )),
                    PdfTocEntry("Chapter 2", pageIndex = 1),
                ),
                toc
            )
        }
    }

    @Test
    fun extractsOutlineFromPngUpPredictedXrefStream() {
        val extractor = PdfOutlineExtractor.open(
            ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithPngUpPredictedXrefStream())
        )
        assertNotNull(extractor)

        extractor.use {
            assertEquals(
                listOf("Predicted chapter 1", "Predicted chapter 2"),
                it.extractTableOfContents().map(PdfTocEntry::title),
            )
        }
    }

    @Test
    fun rejectsXrefStreamFieldWidthThatCannotFitInLong() {
        assertNull(
            PdfOutlineExtractor.open(
                ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithOversizedDeclaredXrefWidth())
            )
        )
    }

    @Test
    fun locatesXrefStreamKeywordAfterCommentsAndLongWhitespace() {
        val extractor = PdfOutlineExtractor.open(
            ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithCommentBeforeXrefStreamKeyword())
        )
        assertNotNull(extractor)

        extractor.use {
            assertEquals(
                listOf("Comment chapter 1", "Comment chapter 2"),
                it.extractTableOfContents().map(PdfTocEntry::title),
            )
        }
    }

    @Test
    fun extractsCompressedOutlineFromHybridXrefStream() {
        val extractor = PdfOutlineExtractor.open(
            ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithHybridXref())
        )
        assertNotNull(extractor)

        extractor.use {
            assertEquals(
                listOf("Hybrid chapter 1", "Hybrid chapter 2"),
                it.extractTableOfContents().map(PdfTocEntry::title),
            )
        }
    }

    @Test
    fun newestCompressedRevisionSupersedesOlderRegularObject() {
        val extractor = PdfOutlineExtractor.open(
            ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithCompressedReplacementRevision())
        )
        assertNotNull(extractor)

        extractor.use {
            assertEquals("Replacement chapter 1", it.extractTableOfContents().first().title)
        }
    }

    @Test
    fun extractsOutlineObjectsStoredInObjectStream() {
        val extractor = PdfOutlineExtractor.open(
            ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithXrefAndObjectStreams())
        )
        assertNotNull(extractor)

        extractor.use {
            val toc = it.extractTableOfContents()

            assertEquals(
                listOf(
                    PdfTocEntry("Compressed chapter 1", pageIndex = 0, children = listOf(
                        PdfTocEntry("Compressed section", pageIndex = 1)
                    )),
                    PdfTocEntry("Compressed chapter 2", pageIndex = 1),
                ),
                toc
            )
        }
    }

    @Test
    fun extractsOutlineFromFlateEncodedXrefAndObjectStreams() {
        val extractor = PdfOutlineExtractor.open(
            ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithFlateXrefAndObjectStreams())
        )
        assertNotNull(extractor)

        extractor.use {
            val toc = it.extractTableOfContents()

            assertEquals(
                listOf(
                    PdfTocEntry("Compressed chapter 1", pageIndex = 0, children = listOf(
                        PdfTocEntry("Compressed section", pageIndex = 1)
                    )),
                    PdfTocEntry("Compressed chapter 2", pageIndex = 1),
                ),
                toc
            )
        }
    }

    @Test
    fun resolvesNamedPageActionsWithoutTreatingRemoteActionsAsLocalPages() {
        val extractor = PdfOutlineExtractor.open(ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithNamedActions()))
        assertNotNull(extractor)

        extractor.use {
            val toc = it.extractTableOfContents()

            assertEquals(
                listOf(
                    PdfTocEntry("First page action", pageIndex = 0),
                    PdfTocEntry("Last page action", pageIndex = 1),
                    PdfTocEntry("Remote action", pageIndex = null),
                ),
                toc
            )
        }
    }

    @Test
    fun resolvesNamedPageActionsWhoseNamesAreIndirectObjects() {
        val extractor = PdfOutlineExtractor.open(
            ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithIndirectNamedActions())
        )
        assertNotNull(extractor)

        extractor.use {
            assertEquals(
                listOf(
                    PdfTocEntry("Indirect first page action", pageIndex = 0),
                    PdfTocEntry("Indirect last page action", pageIndex = 1),
                ),
                it.extractTableOfContents(),
            )
        }
    }

    @Test
    fun clampsIntegerPageDestinationsToExistingPages() {
        val extractor = PdfOutlineExtractor.open(ByteArrayPdfByteSource(SyntheticPdfBuilder.twoPageOutlineWithOutOfRangeIntegerDestination()))
        assertNotNull(extractor)

        extractor.use {
            val toc = it.extractTableOfContents()

            assertEquals(
                listOf(PdfTocEntry("Clamped chapter", pageIndex = 1)),
                toc
            )
        }
    }
}
