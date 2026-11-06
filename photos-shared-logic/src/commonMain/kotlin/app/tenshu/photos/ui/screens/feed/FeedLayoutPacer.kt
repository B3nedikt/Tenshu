package app.tenshu.photos.ui.screens.feed

/**
 * How often a non-Compose shell DRAWS the feed's layout while layouts arrive in a burst
 * (`photos-ios.md` §8.20).
 *
 * A sync delivers photographs one at a time, and each is a new [FeedLayout]; drawing every one
 * re-cuts the newest day's rows several times a second. So: the first layout after a quiet spell is
 * drawn at once, the ones that follow within [minIntervalMs] are HELD — the latest replacing the one
 * before — and the held one is drawn when the interval is up.
 *
 * ⚠⚠ **The last layout is always drawn.** A held layout stays held until [release]; the shell owes
 * that call at [heldDueInMs], and must not drop it when its own observation is cancelled.
 *
 * ⚠⚠ **Nothing the user did waits.** A layout at another density or column count is theirs, and so
 * is any layout while the caller says `urgent` — a re-anchor in flight, a finger on the list. Those
 * are drawn at once, and a shell must [release] before it performs a scroll request: a request
 * names a line in the newest layout, never in one this is holding back.
 *
 * ⚠ No clock and no timer of its own — the shell passes `nowMs` (any monotonic clock) and owns the
 * wake-up — so the rule is a pure state machine a JVM test can drive. Main-thread confined.
 */
class FeedLayoutPacer(private val minIntervalMs: Long = MIN_INTERVAL_MS) {
    private var drawn: FeedLayout? = null
    private var drawnAtMs = 0L
    private var held: FeedLayout? = null
    private var expedite = false

    /** The layout to draw NOW — [layout] itself — or `null` when it is held for [heldDueInMs]. */
    fun offer(layout: FeedLayout, nowMs: Long, urgent: Boolean): FeedLayout? {
        val last = drawn
        val theirs = urgent || expedite || last == null ||
            last.density != layout.density || last.columns != layout.columns
        if (theirs || nowMs - drawnAtMs >= minIntervalMs) return draw(layout, nowMs)
        held = layout
        return null
    }

    /** How long until the held layout is due — `0` when it already is — or `null` with none held. */
    fun heldDueInMs(nowMs: Long): Long? =
        if (held == null) null else (drawnAtMs + minIntervalMs - nowMs).coerceIn(0L, minIntervalMs)

    /** The held layout, to draw now — when it is due, or early because something urgent needs it. */
    fun release(nowMs: Long): FeedLayout? = held?.let { draw(it, nowMs) }

    /**
     * The next layout offered is the user's, whatever it looks like — a width change re-cuts the
     * rows at the same column count, which [offer] cannot see.
     */
    fun expediteNext() {
        expedite = true
    }

    private fun draw(layout: FeedLayout, nowMs: Long): FeedLayout {
        held = null
        expedite = false
        drawn = layout
        drawnAtMs = nowMs
        return layout
    }

    companion object {
        /** Three draws a second: slow enough to read as stillness, fast enough to read as live. */
        const val MIN_INTERVAL_MS: Long = 300
    }
}
