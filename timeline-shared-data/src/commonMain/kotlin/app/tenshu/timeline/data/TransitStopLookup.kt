package app.tenshu.timeline.data

/**
 * One transit leg's resolved names — its two stops and its line — plus **how many times we have
 * asked** (`timeline-movement-capture.md` §36.6a).
 *
 * ### ⚠⚠ The defect this exists to close: a failed lookup was memoised as the answer
 *
 * `TimelineProjectionRepository.withTransitStops` cached its result unconditionally, under the
 * comment *"a trace's endpoints never move"*. That is true of the **coordinates** and was read as
 * though it were true of the **lookup**: `stopNear` collapses every failure to `""`, so one tile
 * range-read that failed left the leg unnameable for the life of the process. Observed on a real
 * device — a leg finishing at München Ost rendered as a bare `Transit` for hours, and the *same
 * stored trace with the same coordinates* came back `Transit to München Ost` after a reinstall.
 *
 * ⭐⭐ The general rule: **a memo keyed on immutable input is only sound while the function is
 * total.** Once it can fail, the cache has to separate *"asked, and the answer is nothing"* from
 * *"asked, and could not tell"*.
 *
 * ### ⚠⚠ Why we cannot simply stop caching blanks
 *
 * The obvious fix — never memoise a blank, re-ask every time — is **worse than the bug**, and not
 * for performance reasons. `NearestTransitStop` is tiles-first with a **network geocoder fallback**,
 * so an unnameable leg that is re-asked on every emission sends its coordinate to a third party over
 * and over. A leg whose endpoint sits mid-line between two stations is *legitimately* nameless and
 * very common, so that is the normal case, not the edge one. Repeated egress on the timeline's most
 * revealing rows is exactly what §28.2 routed this through the proxy to avoid.
 *
 * ⚠ And we cannot distinguish the two cases at this layer even in principle: the op returns `""` for
 * *both* "no tier could answer" and "no stop within the radius", by explicit design — its own KDoc
 * says the caller must read blank as "no stop, never an error". Fixing it there means changing a
 * service operation's contract, which is a different and much larger change.
 *
 * So the answer is neither "cache forever" nor "never cache": **ask a bounded number of times.**
 */
internal data class StopLookup(
    val originStop: String,
    val destinationStop: String,
    val line: String,
    /** How many times the lookup has been run for this trace, in this process. */
    val attempts: Int,
) {

    /**
     * Did the last attempt name everything there is to name?
     *
     * ⚠ A leg with no line but both stops is *not* resolved by this test, and that is deliberate
     * rather than sloppy: §33 L5 makes the line an independent lookup (joined by coordinate, not by
     * stop), so either can succeed while the other fails. Treating "partly named" as final would
     * re-introduce the bug for whichever half was unlucky. The cost of being wrong the other way is
     * bounded by [MAX_ATTEMPTS] — a genuinely line-less leg is asked a few times and then settles.
     */
    val isResolved: Boolean
        get() = originStop.isNotBlank() && destinationStop.isNotBlank() && line.isNotBlank()

    /**
     * This attempt's answers laid over [previous]'s, blank losing to non-blank.
     *
     * ⚠⚠ **A retry must never take a name away.** The three lookups fail independently, so an attempt
     * that resolves the origin and not the destination can be followed by one that resolves neither —
     * and writing that over the cache would make the row *lose* a name it had already shown. Retrying
     * has to be monotone in what is known, or a self-healing cache becomes a flickering one.
     */
    fun mergedOver(previous: StopLookup?): StopLookup = when (previous) {
        null -> this
        else -> copy(
            originStop = originStop.ifBlank { previous.originStop },
            destinationStop = destinationStop.ifBlank { previous.destinationStop },
            line = line.ifBlank { previous.line },
        )
    }

    companion object {

        /**
         * How many times a leg's names are looked up before the blank is accepted as the answer.
         *
         * ⚠ Three, and the bound is what makes the network fallback affordable: a nameless leg costs
         * at most three lookups per process instead of one-per-emission. ⚠⚠ Attempts are spread over
         * however often the traces flow emits — which is store-change driven, so minutes apart in
         * practice rather than a tight loop. That is what gives a transient failure time to clear;
         * a burst of emissions in one second would use the budget up without ever re-testing the
         * condition, which is this rule's honest limitation and the reason the bound is not 2.
         */
        const val MAX_ATTEMPTS = 3

        /**
         * Should the lookup run for a trace whose cache entry is [cached]?
         *
         * Pure, so the policy is testable without standing up a `TenshuClient` — the same reason
         * `ModeSplitter.holdsAStandstill` and `TransitEvidence` are exposed.
         */
        fun shouldAsk(cached: StopLookup?): Boolean =
            cached == null || (!cached.isResolved && cached.attempts < MAX_ATTEMPTS)
    }
}
