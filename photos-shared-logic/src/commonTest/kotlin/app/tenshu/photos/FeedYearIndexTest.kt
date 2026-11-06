package app.tenshu.photos

import app.tenshu.photos.ui.screens.feed.FeedYearMark
import app.tenshu.photos.ui.screens.feed.ScrubberPill
import app.tenshu.photos.ui.screens.feed.feedYearMarks
import app.tenshu.photos.ui.screens.feed.hideUnderThumb
import app.tenshu.photos.ui.screens.feed.scrubberPills
import app.tenshu.photos.ui.screens.feed.thinToFit
import app.tenshu.photos.ui.screens.feed.trackFractions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scrubber's track (PH-17a/PH-17c′, `photos-feed-scrubber.md`).
 *
 * ⚠⚠ Every assertion here is about `startIndex` and `count` — **positions in the feed, not
 * dates**. The reference apps space their year markers by how many photographs each year holds,
 * so a mark whose position came from a date would put an empty decade in the middle of the track.
 * Testing the years alone would pass for exactly that implementation.
 */
class FeedYearIndexTest {

    private companion object {
        const val YEAR_MS = 31_556_952_000L
        const val DAY_MS = 86_400_000L
    }

    /**
     * ⚠ A crude but monotonic year → epoch, matching `SmartAlbumsTest`'s: the caller supplies both
     * halves, so the arithmetic stays honest and no date library is dragged in.
     */
    private fun at(year: Int, day: Int = 0): Long = (year - 1970) * YEAR_MS + day * DAY_MS

    private val yearOf: (Long) -> Int = { ms -> (ms / YEAR_MS + 1970).toInt() }

    private fun marks(times: List<Long>) = feedYearMarks(times, yearOf)

    @Test
    fun eachRunOfAYearBecomesOneMarkAtItsFirstPhotograph() {
        val feed = listOf(
            at(2026, 2), at(2026, 1), at(2026, 0),
            at(2024, 0),
            at(2021, 1), at(2021, 0),
        )

        assertEquals(
            listOf(
                FeedYearMark(year = 2026, startIndex = 0, count = 3),
                FeedYearMark(year = 2024, startIndex = 3, count = 1),
                FeedYearMark(year = 2021, startIndex = 4, count = 2),
            ),
            marks(feed),
        )
    }

    @Test
    fun aYearWithNoPhotographsGetsNoMark() {
        // ⚠⚠ The property the whole feature rests on. 2025 and 2022 have nothing, and the track must
        // not reserve space for them — that is the difference between a map of the user's library
        // and a calendar. A mark per year between the extremes would pass a "years are in order"
        // test and be wrong on screen.
        val feed = listOf(at(2026), at(2023))

        assertEquals(listOf(2026, 2023), marks(feed).map { it.year })
    }

    @Test
    fun theMarksCoverEveryPhotographExactlyOnce() {
        // ⚠ The invariant that makes `startIndex` usable as a scroll target: the runs must tile the
        // feed with no gap and no overlap. A gap is a stretch of feed no year claims; an overlap is
        // two marks pointing into the same rows.
        val feed = listOf(
            at(2026, 1), at(2026, 0),
            at(2025, 0),
            at(2024, 2), at(2024, 1), at(2024, 0),
        )

        val found = marks(feed)
        assertEquals(feed.size, found.sumOf { it.count }, "the counts must add up to the feed")
        var expected = 0
        found.forEach { mark ->
            assertEquals(expected, mark.startIndex, "a gap or overlap before ${mark.year}")
            expected += mark.count
        }
    }

    @Test
    fun aYearThatRecursLaterIsItsOwnMark() {
        // ⚠ Not a real feed — it is newest-first, so a year cannot come back — but the function is
        // given a list and must not silently merge non-adjacent runs into one mark whose
        // `startIndex` then points at rows a different year owns. Fail loudly on bad input rather
        // than produce a plausible wrong track.
        val feed = listOf(at(2026, 1), at(2024), at(2026, 0))

        assertEquals(
            listOf(
                FeedYearMark(2026, startIndex = 0, count = 1),
                FeedYearMark(2024, startIndex = 1, count = 1),
                FeedYearMark(2026, startIndex = 2, count = 1),
            ),
            marks(feed),
        )
    }

    @Test
    fun anEmptyFeedHasNoTrack() {
        assertEquals(emptyList(), marks(emptyList()))
    }

    @Test
    fun oneYearIsOneMarkSpanningEverything() {
        val feed = List(5) { at(2026, it) }

        assertEquals(
            listOf(FeedYearMark(2026, startIndex = 0, count = 5)),
            marks(feed),
        )
    }

    // ── PH-17c′: where a mark lands on the track ─────────────────────────────────────────────


    @Test
    fun aMarkSitsAtItsShareOfThePhotographsAboveIt() {
        // ⚠⚠ The arithmetic the pills stand on, and the property that makes them a DENSITY map
        // rather than a calendar: 2025 and 2024 hold one photograph each, so they sit at the very
        // bottom of the track together — not at a third and two-thirds of the way down.
        //
        // ⚠⚠ **THREE years with UNEVEN counts, and both halves are load-bearing.** With only two
        // marks — or with three of equal size — evenly spacing the years gives the SAME answer as
        // spacing them by density, so the fixture cannot tell the two apart. The first version of
        // this test had five 2026s and one 2024 and passed against a calendar track; only a
        // mutation sweep found it, and only because a UI test happened to catch what this one
        // could not.
        val feed = listOf(
            at(2026, 7), at(2026, 6), at(2026, 5), at(2026, 4),
            at(2026, 3), at(2026, 2), at(2026, 1), at(2026, 0),
            at(2025, 0),
            at(2024, 0),
        )

        val fractions = trackFractions(marks(feed))
        assertEquals(listOf(2026, 2025, 2024), fractions.map { it.first.year })
        assertEquals(0f, fractions[0].second, "the newest year is the top of the track")
        assertEquals(8f / 9f, fractions[1].second, "2025 sits after eight of the nine gaps")
        assertEquals(1f, fractions[2].second, "the oldest photograph is the bottom")

        // ⚠ Stated as a comparison as well as a number, because the number alone reads as
        // arithmetic while this reads as the feature: a thin year sits near the thick one that
        // precedes it, not at its own share of the calendar.
        assertTrue(
            fractions[1].second > 0.5f,
            "a year holding one photograph of ten must sit near the bottom, not the middle",
        )
    }

    @Test
    fun theFractionsSpanTheWholeTrackRatherThanBunchingAtTheTop() {
        // ⚠ The control for the obvious wrong denominator. Dividing by the photograph COUNT rather
        // than `count - 1` leaves the last mark short of the bottom, which reads as a track that
        // stops early — visible only on a library with few years, which is every test fixture.
        val feed = listOf(at(2026), at(2025), at(2024), at(2023))

        assertEquals(
            listOf(0f, 1f / 3f, 2f / 3f, 1f),
            trackFractions(marks(feed)).map { it.second },
        )
    }

    @Test
    fun crowdedPillsAreThinnedToWhatFitsOnTheTrack() {
        // ⚠⚠ The shape the reference has and the shape a real library produces: a recent year with
        // most of the photographs, then a run of thin years bunched at the bottom. Without thinning
        // those three draw on top of each other.
        val fractions = listOf(
            FeedYearMark(2026, 0, 90) to 0.00f,
            FeedYearMark(2023, 90, 4) to 0.90f,
            FeedYearMark(2019, 94, 3) to 0.94f,
            FeedYearMark(2017, 97, 3) to 0.97f,
        )

        assertEquals(
            listOf(2026, 2023),
            thinToFit(fractions, minGap = 0.08f).map { it.first.year },
            "a pill within one pill-height of the one above it must be dropped",
        )
    }

    @Test
    fun thinningKeepsTheNewestOfACrowdedRun_notTheOldest() {
        // ⚠ The choice, pinned. Keeping the LAST of a cluster would put the label below the
        // boundary the user is dragging towards, naming a year they have already passed.
        val fractions = listOf(
            FeedYearMark(2026, 0, 1) to 0.50f,
            FeedYearMark(2025, 1, 1) to 0.51f,
            FeedYearMark(2024, 2, 1) to 0.52f,
        )

        assertEquals(listOf(2026), thinToFit(fractions, minGap = 0.1f).map { it.first.year })
    }

    @Test
    fun aTrackWithRoomKeepsEveryPill() {
        // ⚠ The control. A thinning rule that dropped pills on an uncrowded track would be
        // indistinguishable, in the crowded test above, from one that works.
        val fractions = listOf(
            FeedYearMark(2026, 0, 1) to 0.0f,
            FeedYearMark(2025, 1, 1) to 0.5f,
            FeedYearMark(2024, 2, 1) to 1.0f,
        )

        assertEquals(
            listOf(2026, 2025, 2024),
            thinToFit(fractions, minGap = 0.1f).map { it.first.year },
        )
    }

    @Test
    fun thePillTheThumbIsSittingOnIsHidden() {
        // ⚠⚠ Found by rendering the control, not by reasoning about it: PH-17d's date bubble is 8 %
        // transparent to match the thumb, so a pill behind it bleeds through the label as a smudge
        // — "2025" ghosting through "13 September 2021" in a shot of a realistic library.
        val fractions = listOf(
            FeedYearMark(2026, 0, 1) to 0.0f,
            FeedYearMark(2025, 1, 1) to 0.40f,
            FeedYearMark(2024, 2, 1) to 0.90f,
        )

        assertEquals(
            listOf(2026, 2024),
            hideUnderThumb(fractions, thumbFraction = 0.42f, reach = 0.05f).map { it.first.year },
        )
    }

    @Test
    fun aPillIsHiddenWhetherTheThumbIsAboveOrBelowIt() {
        // ⚠ The distance is absolute. Comparing without the sign hides only the pills BELOW the
        // thumb, which looks correct on a downward drag and leaves the smudge on the way back up.
        val fractions = listOf(FeedYearMark(2025, 0, 1) to 0.40f)

        assertEquals(emptyList(), hideUnderThumb(fractions, 0.38f, reach = 0.05f))
        assertEquals(emptyList(), hideUnderThumb(fractions, 0.42f, reach = 0.05f))
    }

    @Test
    fun pillsClearOfTheThumbAreLeftAlone() {
        // ⚠ The control: a rule that hid everything would satisfy the assertion above.
        val fractions = listOf(
            FeedYearMark(2026, 0, 1) to 0.0f,
            FeedYearMark(2024, 1, 1) to 1.0f,
        )

        assertEquals(
            listOf(2026, 2024),
            hideUnderThumb(fractions, thumbFraction = 0.5f, reach = 0.05f).map { it.first.year },
        )
    }

    @Test
    fun aTrackWithNoThumbReachHidesNothing() {
        // ⚠ `reach` is measured from the thumb's height over the track's travel, and a track that
        // has not been laid out yet reports zero — which must not blank every pill.
        val fractions = listOf(FeedYearMark(2026, 0, 1) to 0.5f)

        assertEquals(fractions, hideUnderThumb(fractions, thumbFraction = 0.5f, reach = 0f))
    }

    @Test
    fun thinningAnEmptyTrackIsEmptyRatherThanACrash() {
        // ⚠ `first()` on an empty list is the obvious way to write this and throws.
        assertEquals(emptyList(), thinToFit(emptyList(), minGap = 0.1f))
    }

    @Test
    fun aSingleItemTrackHasNoDivisionByZero() {
        // ⚠ `count - 1` is the divisor, so a one-photograph library is the degenerate case.
        assertEquals(listOf(0f), trackFractions(marks(listOf(at(2026)))).map { it.second })
        assertEquals(emptyList(), trackFractions(emptyList()))
    }

    // ── scrubberPills: the one placement both shells draw (PIO-2 slice 3) ─────────────────────

    private val threeYears = listOf(
        FeedYearMark(2026, startIndex = 0, count = 50),
        FeedYearMark(2025, startIndex = 50, count = 30),
        FeedYearMark(2024, startIndex = 80, count = 20),
    )

    @Test
    fun pillsSitOnTheThumbsTravel_centredOnItsMidLine() {
        // track 1000, thumb 100 → travel 900; a 20-tall pill is centred on the thumb: +40.
        val pills = scrubberPills(threeYears, track = 1000f, thumb = 100f, pill = 20f, thumbFraction = 1f)

        assertEquals(
            listOf(
                ScrubberPill(2026, 40f),
                ScrubberPill(2025, 50f / 99f * 900f + 40f),
            ),
            pills.take(2),
        )
    }

    @Test
    fun thePillUnderTheThumbIsHidden_andOnlyThatOne() {
        val pills = scrubberPills(threeYears, track = 1000f, thumb = 100f, pill = 20f, thumbFraction = 0f)

        assertEquals(listOf(2025, 2024), pills.map { it.year })
    }

    @Test
    fun crowdedPillsKeepTheNewestOfTheRun() {
        // 2025 and 2024 sit 30/99 of the track apart; a pill taller than that gap cannot show both.
        val pills = scrubberPills(threeYears, track = 1000f, thumb = 100f, pill = 300f, thumbFraction = 0f)

        assertEquals(listOf(2025), pills.map { it.year })
    }

    @Test
    fun aTrackNoTallerThanTheThumbHasNoPills() {
        // ⚠ Before the first layout pass the track measures zero — nothing to aim at, and no NaN.
        assertEquals(emptyList(), scrubberPills(threeYears, track = 0f, thumb = 56f, pill = 20f, thumbFraction = 0f))
        assertEquals(emptyList(), scrubberPills(threeYears, track = 56f, thumb = 56f, pill = 20f, thumbFraction = 0f))
    }
}
