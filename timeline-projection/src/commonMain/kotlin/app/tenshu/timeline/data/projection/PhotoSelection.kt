package app.tenshu.timeline.data.projection

/**
 * When a moment's photos were taken (`timeline-photos.md` T-P1/T-P2).
 *
 * ⚠ **The window is the only part of the join that is still Timeline's.** T-P1 also selected and
 * ranked here, over every file row shipped across IPC; T-P2 moved that into `photosCapturedBetween`,
 * where the rows already are. What remains is the question the file service has no business
 * answering: what "during this moment" means.
 */
object PhotoSelection {

    /**
     * The half-open window `[start, end)` a moment's photos are drawn from: the moment's own start,
     * running for its duration, never shorter than [minWindowMs].
     *
     * ⚠ The floor is what makes a zero-duration moment (an Event) work at all — no photo is taken in
     * the same millisecond a moment is stamped. Half-open so two adjacent moments cannot both claim
     * a photo taken exactly on their boundary.
     */
    fun window(startMs: Long, durationSec: Long, minWindowMs: Long): LongRange {
        val span = maxOf(durationSec * 1_000L, minWindowMs)
        return startMs until (startMs + span)
    }

    /**
     * The shortest window a moment's photos are drawn from.
     *
     * An Event has no duration at all, and a zero-width window would match nothing — no photo is
     * taken in the same millisecond a moment is stamped. Fifteen minutes rather than an hour
     * because the cost is asymmetric: a photo attached to the *wrong* moment is far more visible
     * than a missing one.
     */
    const val MIN_WINDOW_MS = 15 * 60 * 1000L

    /**
     * How many photos one moment carries into the feed (§7 Q2).
     *
     * A day at the beach is two hundred photos and the cluster shows a handful; the rest are not
     * lost, they are simply not what a timeline row is for. Six is the carousel's comfortable
     * reach — enough that a stay reads as "several", few enough that a row never becomes a gallery.
     */
    const val MAX_PER_MOMENT = 6

    /**
     * How far from a moment's place a photo may have been taken and still belong to it (T-P4).
     *
     * 250 m is generous, deliberately: a visit's coordinate is a dwell cluster's centre, indoor
     * fixes are ±100 m on their own, and a photo may be taken from across the square. What this
     * rules out is the other side of town — a screenshot saved during a visit, or a picture
     * received in a chat — which is the whole difference between "taken then" and "taken there".
     */
    const val NEARBY_RADIUS_M = 250

    /**
     * The tint a photo carries until real extraction lands *proxy-side*. Since T-P2 the hero's real
     * colour is averaged from the bitmap the UI decodes anyway, so this is the fallback for a photo
     * whose bytes have not arrived — and it matches the placeholder the rest of the projection uses,
     * so a moment does not change colour when they do.
     */
    const val PLACEHOLDER_COLOR = "#8B7BB8"
}
