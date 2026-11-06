package app.tenshu.photos.ui.screens.feed

/**
 * One year's stretch of the feed, for the scrubber's track (PH-17a).
 *
 * ⚠⚠ [startIndex] is a position in the FEED, not a date, and that is the whole point. The reference
 * apps space their year markers by **how many photographs each year holds**, not evenly across the
 * timeline — measured on Google Photos, where 2026 and 2025 sit far apart while 2023, 2019 and 2017
 * bunch together at the bottom (`photos-feed-scrubber.md` §1). A track drawn from dates would put
 * an empty decade in the middle of the screen; a track drawn from counts puts the user's thumb
 * where their photographs actually are.
 */
data class FeedYearMark(
    val year: Int,
    /** Index of this year's newest photograph in the feed ordering, counting photographs only. */
    val startIndex: Int,
    /** How many photographs the year holds. */
    val count: Int,
)

/**
 * Group an ordered feed into one mark per year.
 *
 * ⚠⚠ **Takes capture times rather than reading them.** Where the complete list comes from was the
 * open question in `photos-feed-scrubber.md` §3 — the feed pages a partial copy while
 * `RealmContentIndex` holds the whole library — and keeping the grouping pure meant the marks could
 * be tested, and later fed from whichever list won, without rework. PH-17c′ feeds it from
 * `PhotosRepository.feedCaptureTimes()`: one read over the feed's own window projecting nothing but
 * the capture time.
 *
 * ⚠ It used to take `List<Photo>`. A capture time is the only column a density map reads, and
 * projecting a whole row to get one number is what made §5 file this phase as blocked on the shared
 * content index.
 *
 * ⚠ [capturedAtMs] must already be in feed order (newest first). This does not sort: sorting here
 * would hide a caller that handed over an unordered list, and the marks would be silently wrong
 * rather than obviously so — a year that appears twice in the input produces two marks, which is
 * visible, instead of one mark with a fabricated span.
 *
 * ⚠ [yearOf] is injected for the same reason `CollectionsViewModel` injects it — a year is a
 * *local* year, and injection keeps this testable with a fixed one. Shells pass `PhotoDates::yearOf`.
 */
fun feedYearMarks(capturedAtMs: List<Long>, yearOf: (Long) -> Int): List<FeedYearMark> {
    if (capturedAtMs.isEmpty()) return emptyList()

    val marks = mutableListOf<FeedYearMark>()
    var year = yearOf(capturedAtMs.first())
    var start = 0

    for (i in 1..capturedAtMs.size) {
        // ⚠ The loop runs one past the end so the final run is closed by the same branch that
        // closes every other one — an "and now handle the last group" tail is where an off-by-one
        // lives.
        val next = if (i < capturedAtMs.size) yearOf(capturedAtMs[i]) else null
        if (next != year) {
            marks += FeedYearMark(year = year, startIndex = start, count = i - start)
            if (next == null) break
            year = next
            start = i
        }
    }
    return marks
}

/**
 * Where each mark sits on the scrubber's track, from 0 (top) to 1 (bottom).
 *
 * ⚠⚠ **Measured in photographs, and the track it is drawn on is measured in grid items** — which
 * also hold a full-span day header before each new day. The two agree only to the extent that
 * headers are spread evenly through the library, because the drag seeks with
 * `fraction × liveGridItems` and the pills are placed with `photographsAbove / photographs`. Where
 * a stretch of the library is denser in *days* than the average — one photograph a day for a year,
 * against a holiday's fifty a day — its pill sits slightly above the rows it names.
 *
 * ⚠ The alternative, counting headers into the offsets, is **not** more accurate: the seek runs in
 * the grid's LIVE item space, and the live count carries headers only for the pages Paging holds
 * right now. Placing pills against a fully-grouped total would make them disagree with the seek by
 * every header not yet loaded — a much larger error than the density deviation, and one that
 * changes as the user scrolls. Photograph space is the only scale on which both ends agree.
 *
 * ⚠ `photographs` is the feed's own total, not `marks.size`: an empty list of marks and a
 * single-photograph library must both come back without dividing by zero.
 */
fun trackFractions(marks: List<FeedYearMark>): List<Pair<FeedYearMark, Float>> {
    val photographs = marks.sumOf { it.count }
    if (photographs <= 1) return marks.map { it to 0f }
    return marks.map { mark -> mark to mark.startIndex.toFloat() / (photographs - 1) }
}

/**
 * Drop the pills that would overlap the one above them (PH-17c′).
 *
 * ⚠⚠ **A crowded track is what makes this necessary, and crowding is the normal case.** The pills
 * are spaced by density, so the years a user photographed little bunch together — measured on
 * Google Photos, 2023, 2019 and 2017 sit within a thumb's width of each other. Drawing all of them
 * gives an unreadable stack of overlapping labels exactly where the track is hardest to aim at.
 *
 * ⚠ Greedy from the top, keeping the newest of a crowded run. That is a real choice and not an
 * arbitrary one: the fractions are ascending, so keeping the first of a cluster puts the surviving
 * label at the boundary the user is dragging *towards*, and the label then names the first year
 * they will reach rather than the last.
 *
 * ⚠ [minGap] is a fraction of the track, not pixels — the caller measures the track and the label,
 * because only the UI knows either. A [minGap] of zero keeps everything.
 */
fun thinToFit(
    fractions: List<Pair<FeedYearMark, Float>>,
    minGap: Float,
): List<Pair<FeedYearMark, Float>> {
    if (fractions.isEmpty()) return fractions
    val kept = mutableListOf(fractions.first())
    fractions.drop(1).forEach { candidate ->
        if (candidate.second - kept.last().second >= minGap) kept += candidate
    }
    return kept
}

/**
 * Drop the pill the thumb is sitting on (PH-17c′).
 *
 * ⚠⚠ **Found by rendering the control rather than by reasoning about it.** The thumb carries
 * PH-17d's date bubble, whose background is 8 % transparent to match the thumb — so a pill behind
 * it does not disappear, it bleeds through the label as a smudge exactly where the user is looking.
 * A screenshot of a realistic library showed "2025" ghosting through "13 September 2021".
 *
 * ⚠ Applied AFTER [thinToFit], not before. Thinning first and hiding second leaves a clean gap;
 * hiding first lets thinning promote the next pill down into the same covered spot, which is the
 * same collision one row later.
 *
 * ⚠ The rule is also the honest one independent of the overlap: the thumb is where the user
 * already is, and the bubble already names that position exactly. A pill there says less, worse.
 *
 * [thumbFraction] is the thumb's position on the track and [reach] how much of the track it
 * covers, both as fractions — the caller measures, because only the UI knows the thumb's height.
 */
fun hideUnderThumb(
    fractions: List<Pair<FeedYearMark, Float>>,
    thumbFraction: Float,
    reach: Float,
): List<Pair<FeedYearMark, Float>> {
    if (reach <= 0f) return fractions
    return fractions.filterNot { (_, fraction) ->
        val distance = fraction - thumbFraction
        (if (distance < 0f) -distance else distance) < reach
    }
}

/** A year pill to draw, and how far down the track its top edge sits, in the caller's own units. */
data class ScrubberPill(val year: Int, val top: Float)

/**
 * The year pills a held thumb shows, placed — [trackFractions], then [thinToFit], then
 * [hideUnderThumb], then onto the track (PIO-2 slice 3).
 *
 * ⚠⚠ One function for both shells. Compose's `FeedScrubber` and the iOS thumb each used to be
 * able to hold this arithmetic; two copies of "which pills survive and where" drift apart silently,
 * and the drift is a pill naming a year the thumb is not at.
 *
 * All lengths are in ONE unit the caller picks (pixels on Compose, points on iOS): [track] is the
 * whole track, [thumb] the thumb's height and [pill] a pill's. [thumbFraction] is where the thumb
 * sits, 0 (top) to 1 (bottom).
 *
 * ⚠ Placed against `travel = track - thumb`, the same span the thumb moves over, and centred on the
 * thumb's mid-line: a pill placed against the raw track would drift from the thumb by half a thumb
 * at the top and a whole one at the bottom — worst where the oldest years bunch.
 *
 * ⚠ Empty when the track is no taller than the thumb: there is nowhere to aim.
 */
fun scrubberPills(
    marks: List<FeedYearMark>,
    track: Float,
    thumb: Float,
    pill: Float,
    thumbFraction: Float,
): List<ScrubberPill> {
    val travel = track - thumb
    if (travel <= 0f) return emptyList()
    return hideUnderThumb(
        thinToFit(trackFractions(marks), minGap = (pill / travel).coerceIn(0f, 1f)),
        thumbFraction = thumbFraction,
        // ⚠ Half the thumb either side, which is what the date bubble spans vertically — not the
        // whole thumb, which would blank a pill the user can still read.
        reach = (thumb / 2f / travel).coerceIn(0f, 1f),
    ).map { (mark, fraction) -> ScrubberPill(mark.year, fraction * travel + (thumb - pill) / 2f) }
}
