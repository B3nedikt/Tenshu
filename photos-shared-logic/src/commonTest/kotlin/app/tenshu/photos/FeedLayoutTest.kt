package app.tenshu.photos

import app.tenshu.photos.model.FeedItem
import app.tenshu.photos.model.Photo
import app.tenshu.photos.data.TenshuPhotosRepository
import app.tenshu.photos.ui.screens.feed.columnsAt
import app.tenshu.photos.ui.screens.feed.FeedDensity
import app.tenshu.photos.ui.screens.feed.FeedLayout
import app.tenshu.photos.ui.screens.feed.FeedLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The feed's lines (`photos-app.md` §32) — the mosaic, and the rows every density is drawn as.
 *
 * ⚠⚠ The two properties the design rests on are the first two tests: every paged index is drawn by
 * exactly one line, and a feature takes the same items a row does. A layout that broke the first
 * would drop or repeat photographs; one that broke the second would reshuffle the grid each time a
 * page landed. Neither is visible in a single screenshot.
 */
class FeedLayoutTest {

    private companion object {
        const val DAY = 86_400_000L
    }

    private fun header(day: Long) = FeedItem.DayHeader(day * DAY)

    private fun tiles(day: Long, count: Int) = List(count) { i ->
        FeedItem.Tile(Photo("/d$day-$i.jpg", day * DAY + (count - i), isVideo = false))
    }

    /** A header and [count] photographs for each `day to count` pair, newest day first. */
    private fun feed(vararg days: Pair<Long, Int>): List<FeedItem> =
        days.flatMap { (day, count) -> listOf(header(day)) + tiles(day, count) }

    private fun lines(layout: FeedLayout) = List(layout.lineCount) { layout[it] }

    private fun layout(
        items: List<FeedItem>,
        density: FeedDensity,
        before: Int = 0,
        after: Int = 0,
        columns: Int = density.columns,
    ) = FeedLayout.of(before, items, after, density, columns)

    @Test
    fun aPhotographIsFoundByIdentityWhenPhotographsArriveAheadOfIt() {
        // The top photograph is not kept across a reload by its library ORDINAL: a backup burst or
        // a deletion ahead of it shifts every ordinal, so a reload would restore another
        // photograph. The path is the identity; the ordinal is resolved from it in the new layout.
        val before = layout(feed(10L to 7, 9L to 5), FeedDensity.Day)
        val tracked = "/d9-2.jpg"
        val was = assertNotNullInt(before.photoOrdinalOf(tracked))

        val after = layout(feed(11L to 4, 10L to 7, 9L to 5), FeedDensity.Day) // four arrive ahead
        val now = assertNotNullInt(after.photoOrdinalOf(tracked))
        assertEquals(was + 4, now, "four photographs ahead move it four places")

        // And the line that ordinal names really draws the tracked photograph.
        val line = after.lineOfPhoto(now)
        val first = assertNotNullInt(after.photoAt(line))
        assertTrue(now >= first && now < first + after[line].size, "line $line draws ordinal $now")
        assertEquals(null, after.photoOrdinalOf("/not-loaded.jpg"))
    }

    @Test
    fun theFilesPathIsItsIdentity_aCopyIsItsOwnPhotograph_aMoveIsANewOne() {
        // The platform's rule (`file-content-addressing.md` §3.3.1): a file is keyed by its path. A copy
        // shares the original's contentId (the BYTES) but not its path, so each copy is its own
        // photograph; a move is a new identity, so the old path finds nothing.
        fun tile(path: String, id: String, at: Long) = FeedItem.Tile(Photo(path, at, isVideo = false, contentId = id))
        val items = listOf(header(9L), tile("/a/original.jpg", "same", 9 * DAY + 3), tile("/b/copy.jpg", "same", 9 * DAY + 2),
                           tile("/c/moved-here.jpg", "one", 9 * DAY + 1))
        val layout = layout(items, FeedDensity.Day)
        assertEquals(0, layout.photoOrdinalOf("/a/original.jpg"))
        assertEquals(1, layout.photoOrdinalOf("/b/copy.jpg"), "the tapped copy, not the first file with its bytes")
        assertEquals(null, layout.photoOrdinalOf("/c/was-here.jpg"), "a moved photograph is a new identity")
    }

    @Test
    fun aLayoutIsCompleteOnlyWithNoPlaceholders() {
        // What licenses concluding a photograph is GONE: with placeholders on either
        // side, "not loaded here" may just mean "pages further down".
        val items = feed(9L to 3)
        assertTrue(layout(items, FeedDensity.Day).isComplete)
        assertTrue(!layout(items, FeedDensity.Day, before = 5).isComplete)
        assertTrue(!layout(items, FeedDensity.Day, after = 5).isComplete)
    }

    private fun assertNotNullInt(value: Int?): Int {
        kotlin.test.assertNotNull(value)
        return value
    }

    @Test
    fun everyPagedIndexIsDrawnByExactlyOneLine_inOrder() {
        val items = feed(10L to 7, 9L to 1, 8L to 3, 7L to 13, 6L to 2)
        for (density in FeedDensity.entries) {
            for ((before, after) in listOf(0 to 0, 5 to 0, 0 to 4, 7 to 11, 1 to 1)) {
                val drawn = lines(layout(items, density, before, after))
                    .flatMap { line -> (line.start until line.start + line.size).toList() }

                assertEquals(
                    (0 until before + items.size + after).toList(),
                    drawn,
                    "$density with $before placeholders before and $after after",
                )
            }
        }
    }

    @Test
    fun aFeatureTakesTheSameItemsAsARow_soNoOtherLineMoves() {
        // ⚠⚠ The mosaic and the plain day view cut the same list at the same indices. That is what
        // lets a row become a feature when its third photograph loads without a single line below
        // it changing — the property that keeps the grid still while pages arrive.
        val items = feed(10L to 25, 9L to 4, 8L to 3)

        val mosaic = lines(layout(items, FeedDensity.Mosaic)).map { it.start }
        val day = lines(layout(items, FeedDensity.Day)).map { it.start }

        assertEquals(day, mosaic)
        assertTrue(
            lines(layout(items, FeedDensity.Mosaic)).any { it is FeedLine.Feature },
            "and the comparison is not vacuous — the mosaic did draw features",
        )
    }

    @Test
    fun featuresRepeatTwoThenThreeRowsApart() {
        // ⚠ At the top of the library, so the first line is slot 0 of the rhythm.
        val items = feed(10L to 33)

        val kinds = lines(layout(items, FeedDensity.Mosaic)).map {
            when (it) {
                is FeedLine.Header -> "H"
                is FeedLine.Feature -> "F"
                is FeedLine.Row -> "R${it.size}"
            }
        }

        assertEquals(listOf("H", "F", "R3", "R3", "F", "R3", "R3", "R3", "F", "R3", "R3", "F"), kinds)
    }

    @Test
    fun consecutiveHeroesAlternateSides() {
        val features = lines(layout(feed(10L to 33), FeedDensity.Mosaic))
            .filterIsInstance<FeedLine.Feature>()

        assertEquals(listOf(true, false, true, false), features.map { it.heroFirst })
        assertTrue(
            features.all { it.isHero(if (it.heroFirst) it.start else it.start + 1) },
            "a right-hand hero is the middle item, so reading order stays the paged order",
        )
    }

    /**
     * ⚠⚠ A scrubber jump leaves the loaded window in the middle of a long day; the 60-photo page
     * above it then lands. Counting the rhythm from the window's first item would move every loaded
     * line 20 slots through that page and turn features on screen into rows. Every line the window
     * already drew must come back the same kind at the same place.
     */
    @Test
    fun aPageLandingAboveTheWindowChangesNoLineItAlreadyDrew() {
        val day = feed(10L to 120)
        val header = 1

        // After the jump: the header and the day's first 60 photographs are still unloaded.
        val jumped = layout(day.drop(header + 60), FeedDensity.Mosaic, before = 60)
        // After the page above lands, the window reaches the top of the library.
        val landed = layout(day, FeedDensity.Mosaic)

        fun loadedLines(layout: FeedLayout, firstPhotoIndex: Int) = lines(layout)
            .filter { it !is FeedLine.Header && it.start >= firstPhotoIndex }
            .map { (it.start - firstPhotoIndex) to it::class.simpleName }

        val before = loadedLines(jumped, firstPhotoIndex = 60)
        assertEquals(before, loadedLines(landed, firstPhotoIndex = header + 60))
        assertTrue(before.any { it.second == "Feature" }, "and the window did draw features to lose")
    }

    /**
     * ⚠⚠ The same property at every column count a wide grid picks. ⚠ With a control
     * at nine columns, which the page size does not divide, so the test is shown able to fail.
     */
    @Test
    fun aPageLandingAboveChangesNoLine_atEveryColumnCountAWideGridPicks() {
        val day = feed(10L to 180)
        val header = 1
        val page = TenshuPhotosRepository.DEFAULT_PAGE_SIZE

        fun lostLines(columns: Int): Boolean {
            val jumped = layout(day.drop(header + page), FeedDensity.Mosaic, before = page, columns = columns)
            val landed = layout(day, FeedDensity.Mosaic, columns = columns)
            fun loaded(layout: FeedLayout, first: Int) = lines(layout)
                .filter { it !is FeedLine.Header && it.start >= first }
                .map { (it.start - first) to it::class.simpleName }
            return loaded(jumped, page) != loaded(landed, header + page)
        }

        val picked = (200..6_000 step 13).map { FeedDensity.Mosaic.columnsAt(it.toFloat()) }.toSet()
        picked.forEach { assertTrue(!lostLines(it), "a page landing above moved lines at $it columns") }
        assertTrue(lostLines(9), "control: nine columns against a $page-photograph page does move them")
    }

    /**
     * ⚠⚠ The coordinate a density change re-anchors on. For every density and every
     * placeholder shape: each photograph is found on the line that draws it, and each line reports
     * the photograph it starts with — so the pair round-trips wherever the window sits.
     */
    @Test
    fun aPhotographsLibraryPositionFindsTheLineThatDrawsIt() {
        val all = feed(10L to 7, 9L to 1, 8L to 3, 7L to 13, 6L to 2)
        val photos = all.count { it is FeedItem.Tile }
        for (density in FeedDensity.entries) {
            // A window over the middle of the list, with the photographs either side unloaded.
            val firstLoaded = all.indexOfFirst { it is FeedItem.Tile && it.photo.path == "/d8-0.jpg" }
            val lastLoaded = all.indexOfLast { it is FeedItem.Tile && it.photo.path == "/d7-9.jpg" }
            val photosBefore = all.take(firstLoaded).count { it is FeedItem.Tile }
            val window = all.subList(firstLoaded, lastLoaded + 1)
            val photosAfter = photos - photosBefore - window.count { it is FeedItem.Tile }

            for (layout in listOf(layout(all, density), layout(window, density, photosBefore, photosAfter))) {
                for (photo in 0 until photos) {
                    val line = layout.lineOfPhoto(photo)
                    val first = layout.photoAt(line)!!
                    val next = (line + 1 until layout.lineCount)
                        .firstNotNullOfOrNull { l -> layout.photoAt(l)?.takeIf { it > first } } ?: photos
                    assertTrue(photo in first until next, "$density: photograph $photo on line $line [$first, $next)")
                }
            }
        }
    }

    @Test
    fun theSamePhotographLeadsTheLineFoundForItAtEveryDensity() {
        // Line 40 is a very different photograph at three columns than at seven; its position is not.
        val items = feed(10L to 60, 9L to 60, 8L to 60, 7L to 60)
        val day = layout(items, FeedDensity.Day)
        val photo = day.photoAt(40)!!

        for (density in FeedDensity.entries) {
            val other = layout(items, density)
            val found = other.photoAt(other.lineOfPhoto(photo))!!
            assertTrue(photo - found in 0 until density.columns, "$density: $photo led by $found")
        }
    }

    @Test
    fun aReAnchorThatBeganOnAHeaderLandsOnThatHeader() {
        // ⚠ The feed opens with a header on top, and a density change must not re-anchor to the row
        // under it — scrolling away the header the user was looking at.
        val layout = layout(feed(10L to 5, 9L to 5), FeedDensity.Day)
        // Lines: header, row of three, row of two, header, row of three, row of two.

        assertEquals(0, layout.lineOfPhoto(0, header = true), "the first header, from the top")
        assertEquals(1, layout.lineOfPhoto(0), "the row, when it was the row that was on top")
        assertEquals(3, layout.lineOfPhoto(5, header = true), "the second day's header")
        assertEquals(2, layout.lineOfPhoto(3, header = true), "and no header for a mid-day photograph")
    }

    /**
     * ⚠⚠ The scrubber's year pills are placed by PHOTOGRAPHS, and the grid's items
     * are lines — a header is a line of its own and three photographs are one. Where photographs per
     * day change between eras, a seek in lines lands a whole era away from its pill.
     */
    @Test
    fun theScrubberMovesThroughPhotographs_notLines() {
        // A sparse era (one photograph a day) above a dense one (fifteen a day).
        val sparse = (400L downTo 251L).map { it to 1 }
        val dense = (250L downTo 221L).map { it to 15 }
        val items = feed(*(sparse + dense).toTypedArray())
        val layout = layout(items, FeedDensity.Mosaic)
        val denseStarts = 150

        // Five photographs into the dense era, as a fraction of the library — where its pill sits.
        val fraction = (denseStarts + 5).toFloat() / (layout.photoCount - 1)
        val photo = layout.photoAt(layout.lineAtProgress(fraction))!!
        assertTrue(photo in denseStarts until denseStarts + 15, "the seek lands in the dense era's first day: $photo")

        // The control: the same fraction of the LINES is far up the sparse era.
        val byLines = layout.photoAt((fraction * (layout.lineCount - 1)).toInt())!!
        assertTrue(byLines < denseStarts - 30, "and in lines it would not have: $byLines")

        for (line in 0 until layout.lineCount) {
            if (layout[line] is FeedLine.Header) continue
            assertEquals(line, layout.lineAtProgress(layout.progressAt(line, 0f)), "line $line round-trips")
        }
    }

    @Test
    fun pastHalfAFeature_itsLowerSmallPhotographLeadsWhatIsVisible() {
        // ⚠ A feature is two rows tall, so the line being on top does not mean its
        // first photograph is — and a density change anchored there scrolls back half a feature.
        val layout = layout(feed(10L to 12), FeedDensity.Mosaic)
        val feature = (0 until layout.lineCount).first { layout[it] is FeedLine.Feature }
        val first = layout.photoAt(feature)!!

        assertEquals(first, layout.photoAt(feature, 0f))
        assertEquals(first, layout.photoAt(feature, 0.4f), "the upper row is still on screen")
        assertEquals(first + 2, layout.photoAt(feature, 0.6f), "past it, the lower small one leads")

        val row = (0 until layout.lineCount).first { layout[it] is FeedLine.Row && layout[it].size == 3 }
        assertEquals(layout.photoAt(row), layout.photoAt(row, 0.9f), "a row is one line tall")
    }

    /**
     * §33: a grid wider than a phone's multiplies the columns, and the two properties the design
     * rests on have to survive it — at 6 and 9 columns as at 3.
     */
    @Test
    fun aWiderGridKeepsEveryIndexDrawnOnce_andAFeatureStillTakesARow() {
        val items = feed(10L to 40, 9L to 7, 8L to 13, 7L to 2)
        for (scale in 1..3) {
            for ((before, after) in listOf(0 to 0, 7 to 11)) {
                val mosaic = lines(layout(items, FeedDensity.Mosaic, before, after, columns = 3 * scale))
                val day = lines(layout(items, FeedDensity.Day, before, after, columns = 3 * scale))
                assertEquals(
                    (0 until before + items.size + after).toList(),
                    mosaic.flatMap { line -> (line.start until line.start + line.size).toList() },
                    "every index once at ${3 * scale} columns, $before before and $after after",
                )
                assertEquals(day.map { it.start }, mosaic.map { it.start }, "a feature cuts where a row does at ${3 * scale} columns")
                assertTrue(mosaic.any { it is FeedLine.Feature }, "and the mosaic drew features at ${3 * scale} columns")
            }
        }
    }

    @Test
    fun aFeatureOnAWiderGridIsWholeBlocks_whoseHeroesAlternate() {
        val feature = lines(layout(feed(10L to 60), FeedDensity.Mosaic, columns = 9))
            .filterIsInstance<FeedLine.Feature>()
            .first()

        assertEquals(3, feature.blocks)
        assertEquals(9, feature.size)
        val heroes = (feature.start until feature.start + feature.size).filter { feature.isHero(it) }
        assertEquals(
            listOf(feature.start, feature.start + 4, feature.start + 6),
            heroes,
            "left, right, left — each block's hero where a single feature's would be",
        )
        assertEquals(
            listOf(null, 0, 1, 0, null, 1, null, 0, 1),
            (feature.start until feature.start + feature.size).map { feature.rowOf(it) },
        )
    }

    @Test
    fun aColumnCountThatIsNotWholeBlocksDrawsNoFeature() {
        // ⚠ Never produced by `columnsAt`, and that is exactly why it is pinned: a feature at five
        // columns would take three photographs where a row takes five, and every line below it
        // would move when the page landed.
        val drawn = lines(layout(feed(10L to 60), FeedDensity.Mosaic, columns = 5))
        assertTrue(drawn.none { it is FeedLine.Feature })
    }

    @Test
    fun aFeaturesSmallPhotographsSitInItsTwoRows_andTheHeroSpansBoth() {
        val left = FeedLine.Feature(start = 10, heroFirst = true)
        val right = FeedLine.Feature(start = 10, heroFirst = false)

        assertEquals(listOf(null, 0, 1), (10..12).map { left.rowOf(it) })
        assertEquals(listOf(0, null, 1), (10..12).map { right.rowOf(it) })
    }

    @Test
    fun aSectionTooSmallForAFeatureIsAPlainRow() {
        // A day with two photographs gets two small tiles, not one giant beside a hole.
        val lines = lines(layout(feed(10L to 2, 9L to 1), FeedDensity.Mosaic))

        assertEquals(
            listOf(FeedLine.Header(0), FeedLine.Row(1, 2), FeedLine.Header(3), FeedLine.Row(4, 1)),
            lines,
        )
    }

    @Test
    fun onlyTheMosaicDrawsFeatures() {
        val items = feed(10L to 40)
        for (density in FeedDensity.entries - FeedDensity.Mosaic) {
            assertTrue(
                lines(layout(items, density)).none { it is FeedLine.Feature },
                "$density draws every photograph the same size",
            )
        }
    }

    @Test
    fun rowsHoldTheDensitysColumnCount() {
        val items = feed(10L to 40)
        for (density in FeedDensity.entries) {
            val rows = lines(layout(items, density)).filterIsInstance<FeedLine.Row>()
            assertTrue(rows.dropLast(1).all { it.size == density.columns }, "$density: $rows")
        }
    }

    @Test
    fun theLoadedWindowStartsAFreshLine_andThePlaceholdersAboveItAreCutFromTheirEnd() {
        // ⚠ Five unknown indices above a window that starts mid-section. Cut from the end they are
        // a row of two then a full row, so the first loaded photograph opens a line of its own.
        val items = tiles(10L, 6)
        val lines = lines(layout(items, FeedDensity.Day, before = 5))

        assertEquals(FeedLine.Row(0, 2), lines[0])
        assertEquals(FeedLine.Row(2, 3), lines[1])
        assertEquals(FeedLine.Row(5, 3), lines[2])
    }

    @Test
    fun theWindowsLastRowRunsOnIntoTheUnloadedIndicesAfterIt() {
        // Four photographs loaded, ten unknown after them: the row holding the fourth fills with two
        // placeholders rather than stopping short and leaving a hole that closes when the page lands.
        val lines = lines(layout(tiles(10L, 4), FeedDensity.Day, after = 10))

        assertEquals(FeedLine.Row(0, 3), lines[0])
        assertEquals(FeedLine.Row(3, 3), lines[1])
        assertEquals(FeedLine.Row(6, 3), lines[2])
        assertEquals(FeedLine.Row(12, 2), lines.last())
    }

    @Test
    fun aFeatureNeverDrawsAPlaceholder() {
        // The window ends one photograph into the section's second feature slot (its fourth line);
        // that line must stay a row until the rest of it is known, and become a feature over the
        // SAME indices once it is.
        val partial = layout(feed(10L to 10), FeedDensity.Mosaic, after = 9)
        val full = layout(feed(10L to 19), FeedDensity.Mosaic)

        assertEquals(FeedLine.Row(10, 3), partial[4])
        assertEquals(FeedLine.Feature(10, (full[4] as FeedLine.Feature).heroFirst), full[4])
    }

    @Test
    fun keysFollowTheFirstItem_notTheLinesPosition() {
        // ⚠ A page landing above the viewport renumbers every line; the key must not move with it.
        val items = feed(10L to 3)
        val atTop = layout(items, FeedDensity.Mosaic)
        val belowAWindow = layout(items, FeedDensity.Mosaic, before = 60)

        assertEquals("day-" + 10 * DAY, atTop.key(0))
        assertEquals(atTop.key(1), belowAWindow.key(belowAWindow.lineCount - 1))
        assertEquals("placeholder-0", belowAWindow.key(0))
        assertEquals(
            belowAWindow.lineCount,
            List(belowAWindow.lineCount) { belowAWindow.key(it) }.distinct().size,
            "and no two lines share one — a lazy grid throws on a duplicate key",
        )
    }

    // ── line identity (`photos-ios.md` §8.20) ────────────────────────────────────────────────

    private fun ids(layout: FeedLayout) = layout.lineIds()

    private fun stampsById(layout: FeedLayout) = layout.lineIds().zip(layout.lineStamps()).toMap()

    @Test
    fun lineIdentitiesAreDistinct_evenWhenTheListRepeatsAPath() {
        val window = layout(feed(12L to 8, 11L to 5), FeedDensity.Mosaic, before = 61, after = 200)
        assertEquals(window.lineCount, ids(window).toSet().size)

        // A row served twice across a page seam: two lines whose first photograph has one path.
        val twice = FeedItem.Tile(Photo("/same.jpg", 9 * DAY, isVideo = false))
        val repeated = layout(listOf(header(9L)) + List(6) { twice }, FeedDensity.Day)
        assertEquals(repeated.lineCount, ids(repeated).toSet().size, "a diffing data source traps on a duplicate")
    }

    @Test
    fun aPhotographArrivingAtTheHeadRenamesOnlyItsOwnSectionsLines() {
        val older = feed(10L to 7, 9L to 5)
        val before = layout(feed(11L to 4) + older, FeedDensity.Day, after = 300)
        val arrival = FeedItem.Tile(Photo("/new.jpg", 11 * DAY + 99, isVideo = false))
        val after = layout(listOf(header(11L), arrival) + tiles(11L, 4) + older, FeedDensity.Day, after = 300)

        val was = ids(before)
        val now = ids(after)
        // The newest day is re-cut — /new, 0, 1 | 2, 3 — so its rows are other lines now …
        assertEquals("day-" + 11 * DAY, now[0])
        assertEquals("/new.jpg", now[1])
        assertTrue(now[2] !in was, "a row that starts on another photograph is another line")
        // … and nothing after it is renamed: not the older days, and not ONE placeholder line.
        val olderLines = was.drop(3)
        assertEquals(olderLines, now.takeLast(olderLines.size))
        assertTrue(olderLines.count { it.startsWith("tail-") } >= 99)
    }

    @Test
    fun aPageLandingRenamesNoLineItLeavesStanding() {
        // The window grows by a page on each side; every line that is still there keeps its name —
        // loaded ones by their photograph, unloaded ones by their distance from the library's end.
        val page = feed(10L to 60)
        val small = layout(page, FeedDensity.Mosaic, before = 600, after = 900)
        val grown = layout(feed(11L to 60) + page + feed(9L to 60), FeedDensity.Mosaic, before = 540, after = 840)

        val now = ids(grown).toSet()
        val lost = ids(small).filter { it !in now }
        // 60 photographs a side at three columns: the 20 placeholder lines nearest the window, each side.
        assertEquals((180..199).map { "lead-$it" }, lost.filter { it.startsWith("lead-") })
        assertEquals(20, lost.count { it.startsWith("tail-") })
        assertEquals(40, lost.size, "and no loaded line: $lost")
    }

    @Test
    fun placeholderLinesKeepTheirNamesWhenThePhaseOfTheCutShifts() {
        // Seven columns and a 60-photograph page: 60 is not a multiple of 7, so every placeholder
        // line above the window draws other indices once the page lands. Named by index they would
        // ALL be renamed; counted from the top, only the ones the page replaced are gone.
        val before = layout(feed(10L to 60), FeedDensity.Year, before = 700, columns = 7)
        val after = layout(feed(10L to 120), FeedDensity.Year, before = 640, columns = 7)
        val was = ids(before).filter { it.startsWith("lead-") }
        val now = ids(after).filter { it.startsWith("lead-") }.toSet()
        assertEquals(100, was.size)
        assertTrue(was.count { it !in now } <= 9, "only the lines the page replaced")
    }

    @Test
    fun aLinesStampChangesExactlyWhenWhatItDrawsDoes() {
        val base = stampsById(layout(feed(10L to 7, 9L to 5), FeedDensity.Day, after = 9))
        assertEquals(base, stampsById(layout(feed(10L to 7, 9L to 5), FeedDensity.Day, after = 9)), "equal layouts, equal stamps")

        // One photograph replaced in place — same path, another row.
        val edited = feed(10L to 7, 9L to 5).map {
            if (it is FeedItem.Tile && it.photo.path == "/d9-1.jpg") FeedItem.Tile(it.photo.copy(isVideo = true)) else it
        }
        val changed = stampsById(layout(edited, FeedDensity.Day, after = 9)).filter { (id, stamp) -> base[id] != stamp }
        assertEquals(setOf("/d9-0.jpg"), changed.keys, "only the line that draws it")

        // A placeholder filling in: the window's last row ran on into unloaded indices.
        val full = layout(feed(10L to 9), FeedDensity.Day)
        val partial = layout(feed(10L to 9).dropLast(2), FeedDensity.Day, after = 2)
        assertEquals(ids(partial), ids(full))
        assertEquals(listOf("/d10-6.jpg"), ids(full).filter { stampsById(partial)[it] != stampsById(full)[it] })
    }

    @Test
    fun aRowThatBecomesAFeatureIsTheSameLineWithOtherContent() {
        val partial = layout(feed(10L to 10), FeedDensity.Mosaic, after = 9)
        val full = layout(feed(10L to 19), FeedDensity.Mosaic)
        assertEquals(ids(partial)[4], ids(full)[4])
        assertTrue(partial.lineStamps()[4] != full.lineStamps()[4])
    }

    @Test
    fun aHeadersStampIsItsGrouping_andAPlaceholderLinesItsWidth() {
        val day = layout(feed(10L to 3), FeedDensity.Day, before = 4)
        val mosaic = layout(feed(10L to 3), FeedDensity.Mosaic, before = 4)
        assertEquals(ids(day), ids(mosaic))
        val header = ids(day).indexOf("day-" + 10 * DAY)
        assertTrue(day.lineStamps()[header] != mosaic.lineStamps()[header], "its label is formatted per grouping")
        assertEquals(listOf("p1", "p3"), day.lineStamps().take(2), "four unloaded above, cut from their end")
    }

    @Test
    fun theLoadedLinesSitBetweenTwoRunsOfPlainRows() {
        // What a shell measures line by line is the loaded window; either side is rows, by count.
        val window = layout(feed(12L to 8, 11L to 5), FeedDensity.Mosaic, before = 61, after = 200)
        val loaded = window.leadingLineCount until window.leadingLineCount + window.loadedLineCount
        assertEquals(21, window.leadingLineCount)
        assertTrue(lines(window).filterIndexed { line, _ -> line !in loaded }.all { it is FeedLine.Row && window.itemAt(it.start) == null })
        assertTrue(loaded.all { window.itemAt(window[it].start) != null }, "and every loaded line starts on a loaded item")
        assertTrue(window[loaded.first] is FeedLine.Header)
    }

    @Test
    fun aPhotographsPagedIndexIsFoundByItsPath() {
        val window = layout(feed(10L to 3), FeedDensity.Day, before = 60)
        assertEquals(62, window.positionOf("/d10-1.jpg"))
        assertEquals(null, window.positionOf("/not-loaded.jpg"))
    }

    // ── the change set a shell applies (`FeedLayout.changesFrom`) ─────────────────────────────

    /**
     * Apply [FeedLayout.changesFrom] to [old]'s lines the way a batch update does — deletes by old
     * line number, then inserts by new — and require exactly [new]'s lines, with exactly the kept
     * lines whose content differs marked changed. ⚠ A miscount here is a trap in UIKit.
     */
    private fun assertChangesTurn(old: FeedLayout, new: FeedLayout, what: String) {
        val changes = new.changesFrom(old)
        assertTrue(!changes.reload, "$what: a difference, not a reload")
        val wanted = new.lineIds()
        val lines = old.lineIds().toMutableList()
        assertEquals(changes.deleted.sorted(), changes.deleted, "$what: deletes ascending")
        assertEquals(changes.inserted.sorted(), changes.inserted, "$what: inserts ascending")
        changes.deleted.asReversed().forEach { lines.removeAt(it) }
        changes.inserted.forEach { lines.add(it, wanted[it]) }
        assertEquals(wanted, lines, "$what: the lines after the update")

        val was = stampsById(old)
        val now = new.lineStamps()
        val differing = wanted.indices.filter { was[wanted[it]] != null && was[wanted[it]] != now[it] }
        assertEquals(differing, changes.changed, "$what: redrawn exactly where the content differs")
    }

    @Test
    fun theChangeSetTurnsOneLayoutIntoTheNext() {
        val older = feed(10L to 7, 9L to 5)
        val arrival = FeedItem.Tile(Photo("/new.jpg", 11 * DAY + 99, isVideo = false))
        assertChangesTurn(
            layout(feed(11L to 4) + older, FeedDensity.Mosaic, after = 300),
            layout(listOf(header(11L), arrival) + tiles(11L, 4) + older, FeedDensity.Mosaic, after = 300),
            "a photograph arrives at the head",
        )
        assertChangesTurn(
            layout(feed(11L to 4) + older, FeedDensity.Day, after = 300),
            layout(feed(12L to 1, 11L to 4) + older, FeedDensity.Day, after = 300),
            "a photograph opens a new day",
        )
        val page = feed(10L to 60)
        assertChangesTurn(
            layout(page, FeedDensity.Mosaic, before = 600, after = 900),
            layout(feed(11L to 60) + page + feed(9L to 60), FeedDensity.Mosaic, before = 540, after = 840),
            "a page lands on each side",
        )
        assertChangesTurn(
            layout(feed(11L to 60) + page + feed(9L to 60), FeedDensity.Mosaic, before = 540, after = 840),
            layout(page, FeedDensity.Mosaic, before = 601, after = 900),
            "the window is refreshed smaller, one photograph on",
        )
        assertChangesTurn(
            layout(feed(10L to 60), FeedDensity.Year, before = 700, after = 33, columns = 7),
            layout(feed(10L to 120), FeedDensity.Year, before = 640, after = 33, columns = 7),
            "a page shifts the phase of the cut above it",
        )
        assertChangesTurn(
            layout(feed(10L to 10), FeedDensity.Mosaic, after = 9),
            layout(feed(10L to 19), FeedDensity.Mosaic),
            "the last placeholders fill in, and a row becomes a feature",
        )
        assertChangesTurn(
            layout(feed(10L to 9), FeedDensity.Day, before = 6, after = 6),
            layout(feed(10L to 9).filterNot { it is FeedItem.Tile && it.photo.path == "/d10-4.jpg" }, FeedDensity.Day, before = 6, after = 6),
            "a photograph is deleted from the middle",
        )
    }

    @Test
    fun theChangeSetHoldsOverManyArrivalsAndWindows() {
        // Every pairing of a few libraries and windows: the bookkeeping must never miscount.
        fun library(arrived: Int): List<FeedItem> {
            val photos = List(arrived) { Photo("/new-$it.jpg", 40 * DAY + 500 - it, isVideo = false) }.reversed() +
                (0 until 12).flatMap { d -> tiles(30L - d, 3 + (d * 5) % 11).map { it.photo } }
            return photos.sortedByDescending { it.capturedAtMs }.flatMapIndexed { i, photo ->
                val day = photo.capturedAtMs / DAY
                val opens = i == 0 || photos.sortedByDescending { it.capturedAtMs }[i - 1].capturedAtMs / DAY != day
                if (opens) listOf(header(day), FeedItem.Tile(photo)) else listOf(FeedItem.Tile(photo))
            }
        }
        fun window(arrived: Int, from: Int, count: Int): FeedLayout {
            val all = library(arrived)
            val photos = all.filterIsInstance<FeedItem.Tile>()
            val start = from.coerceIn(0, photos.size - 1)
            val end = (start + count).coerceAtMost(photos.size)
            // The loaded items: from the photograph at [start] to the one before [end], headers between.
            val first = all.indexOf(photos[start])
            val last = all.indexOf(photos[end - 1])
            return layout(all.subList(first, last + 1), FeedDensity.Mosaic, before = start, after = photos.size - end + 3_000)
        }
        val shapes = listOf(0 to 30, 0 to 60, 7 to 30, 20 to 45, 40 to 20)
        for (was in 0..3) for (now in was..was + 4) for ((from, count) in shapes) for ((from2, count2) in shapes) {
            assertChangesTurn(window(was, from, count), window(now, from2, count2), "$was→$now arrivals, $from+$count → $from2+$count2")
        }
    }

    @Test
    fun theChangeSetCostsTheLoadedWindow_notTheLibrary() {
        // ⚠⚠ A billion unloaded photographs each side. Anything that named, walked or
        // allocated per placeholder line would not finish; the placeholder runs are arithmetic.
        val vast = 1_000_000_000
        val older = feed(10L to 7, 9L to 5)
        val arrival = FeedItem.Tile(Photo("/new.jpg", 11 * DAY + 99, isVideo = false))
        val before = layout(feed(11L to 4) + older, FeedDensity.Mosaic, before = vast, after = vast)
        val after = layout(listOf(header(11L), arrival) + tiles(11L, 4) + older, FeedDensity.Mosaic, before = vast, after = vast)

        val changes = after.changesFrom(before)
        assertTrue(!changes.reload)
        assertTrue(changes.deleted.size + changes.inserted.size + changes.changed.size <= 2 * 12, "a handful of lines: $changes")

        // A jump moves the window across the library: thousands of lines either side, so a reload —
        // decided from the two counts, without listing them.
        val jumped = layout(feed(11L to 4) + older, FeedDensity.Mosaic, before = vast / 2, after = vast + vast / 2)
        assertTrue(jumped.changesFrom(before).reload)
    }

    @Test
    fun theChangeSetIsAReloadWhereThereIsNoDifferenceToApply() {
        val drawn = layout(feed(10L to 7), FeedDensity.Day, after = 30)
        assertTrue(layout(feed(10L to 7), FeedDensity.Month, after = 30).changesFrom(drawn).reload, "another grouping")
        assertTrue(layout(feed(10L to 7), FeedDensity.Day, after = 30, columns = 6).changesFrom(drawn).reload, "another column count")
        assertTrue(drawn.changesFrom(layout(emptyList(), FeedDensity.Day)).reload, "nothing was drawn")
        // Kept lines in another order — a batch of deletes and inserts cannot express a move.
        val swapped = layout(listOf(header(10L)) + tiles(10L, 7).let { it.subList(3, 6) + it.take(3) + it.drop(6) }, FeedDensity.Day, after = 30)
        assertTrue(swapped.changesFrom(drawn).reload, "lines changed places")
        // More lines coming and going than a batch is worth.
        val far = layout(feed(10L to 7), FeedDensity.Day, before = 3 * (FeedLayout.MAX_LINE_CHANGES + 1), after = 30)
        assertTrue(far.changesFrom(drawn).reload)

        val same = layout(feed(10L to 7), FeedDensity.Day, after = 30).changesFrom(drawn)
        assertTrue(!same.reload && same.deleted.isEmpty() && same.inserted.isEmpty() && same.changed.isEmpty(), "an equal layout changes nothing")
    }

    /**
     * ⚠⚠ What an arrival really costs the Mosaic — the number a diffing shell is judged by.
     *
     * The rows of the section the photograph lands in are all re-cut (each starts one photograph
     * earlier), so they are other lines. Every later section keeps its lines — but whether a line is
     * a feature follows its photograph's position in the LIBRARY, so a section whose position
     * crosses a row boundary has its features move down a line. One arrival does that to the
     * sections at one phase in three, three arrivals to every one of them.
     */
    @Test
    fun anArrivalReCutsItsOwnSection_andMovesTheFeaturesOfSomeLaterOnes() {
        fun library(extra: Int) = (0 until 30).flatMap { d ->
            val day = 100L - d
            listOf(header(day)) + tiles(day, (if (d == 0) extra else 0) + 4 + (d * 5) % 23)
        }
        fun untouched(extra: Int): Pair<Int, Int> {
            val was = stampsById(layout(library(0), FeedDensity.Mosaic))
            val now = stampsById(layout(library(extra), FeedDensity.Mosaic))
            // The sections AFTER the newest day: every line survives by name.
            val later = ids(layout(library(0), FeedDensity.Mosaic)).dropWhile { it != "day-" + 99 * DAY }
            assertTrue(later.all { it in now }, "a later section's lines keep their names")
            return later.count { was[it] == now[it] } to later.size
        }
        val (one, total) = untouched(1)
        val (three, _) = untouched(3)
        // Measured on this library: 168 and 106 of 181.
        assertTrue(one > total * 85 / 100, "one arrival left $one of $total later lines untouched")
        assertTrue(three in (total * 45 / 100)..(total * 75 / 100), "three arrivals left $three of $total untouched")
    }
}
