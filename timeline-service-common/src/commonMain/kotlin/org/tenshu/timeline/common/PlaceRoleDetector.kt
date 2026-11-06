package org.tenshu.timeline.common

/**
 * The **device-local Home / Work detector** (`docs/proposals/place-roles.md` D5): turns a window of
 * visit dwells into at most one `home` and at most one `work` candidate.
 *
 * De-Googled and model-free — this is a sum over two time-of-day windows, not a classifier. Home is
 * where the nights are spent; Work is where the weekday daytime is spent. Everything else is gates.
 *
 * ## The window is the point, not a shortcut
 *
 * The caller passes a **bounded recent window** of visits rather than all history, and that is a
 * design decision rather than a performance compromise (proposal D3): Home and Work are questions
 * about *current* life, so a flat you moved out of a year ago must stop being Home. A consequence
 * worth naming — because it removes a whole class of bug the maintained-rollup alternative would
 * have carried — is that a verdict is a **pure function of its window**. Re-running is free, and
 * self-correcting: there is no incremental aggregate to drift out of step with reality, and no
 * generation counter needed to repair one.
 *
 * ## ⚠ Local time is the caller's job
 *
 * [VisitDwell.localStartMs] is **wall-clock** milliseconds — epoch millis already shifted by the
 * UTC offset in force at that instant — not an epoch timestamp. Bucketing by hour-of-day is
 * meaningless in UTC, and this module stays dependency-free (the [TripSuggester] precedent), so it
 * cannot resolve a zone itself.
 *
 * Pushing the conversion to the boundary is deliberate beyond dependency hygiene: `Moment` stores no
 * timezone or UTC offset, so *some* caller has to choose one, and that choice is the known soft spot
 * in this feature (proposal D6). Making it an explicit input keeps it visible and testable instead of
 * buried in an hour-of-day helper. Travel is the failure mode: a week abroad buckets under whatever
 * offset the caller applied, so a hotel accrues "night" dwell. [minDistinctDays] is what stops a trip
 * unseating a real home, and re-running on a later window is what repairs it if one ever does.
 */
object PlaceRoleDetector {

    /**
     * One visit, already localised. [durationSec] of dwell starting at wall-clock [localStartMs];
     * a visit that spans a window edge is split across buckets proportionally, so a 22:00–02:00 stay
     * contributes its whole length to the night bucket and an 05:00–09:00 one contributes only the
     * part before 06:00.
     */
    data class VisitDwell(
        val placeId: String,
        val localStartMs: Long,
        val durationSec: Long,
    )

    /** A detected role for a place. [share] is its fraction of all dwell in that role's window. */
    data class RoleCandidate(
        val placeId: String,
        val role: String,
        val share: Double,
    )

    /**
     * @param minDistinctDays how many distinct local dates must contribute before a place can win a
     *   role. The single gate doing the most work: one hotel week, or a fortnight at a friend's, is
     *   dense but *narrow*, and counting days rather than hours is what tells the two apart.
     * @param minShare the fraction of the window's total dwell in that role's bucket the winner must
     *   hold. Below it the evidence is split between candidates, and the honest answer is silence —
     *   a wrong confident guess costs more than none, because the user must then actively refuse it.
     */
    data class Config(
        val minDistinctDays: Int = 5,
        val minShare: Double = 0.30,
    )

    /**
     * At most one `home` and at most one `work` candidate, or an empty list when nothing clears the
     * gates. Work is resolved after Home and **cannot be the same place**: a night-shift worker or
     * someone working from home would otherwise have one place win both, and reporting "Home → Home"
     * for every commute is worse than reporting nothing for Work.
     */
    fun detect(
        visits: List<VisitDwell>,
        config: Config = Config(),
    ): List<RoleCandidate> {
        if (visits.isEmpty()) return emptyList()

        val home = winner(visits, NIGHT, weekdaysOnly = false, config = config, excluding = null)
        val work = winner(visits, WORK_DAY, weekdaysOnly = true, config = config, excluding = home?.placeId)

        return listOfNotNull(
            home?.let { RoleCandidate(it.placeId, PlaceRoleNames.HOME, it.share) },
            work?.let { RoleCandidate(it.placeId, PlaceRoleNames.WORK, it.share) },
        )
    }

    // ── internals ──────────────────────────────────────────────────────────────

    /** 22:00 → 06:00. Wraps midnight, which is why bucketing walks day-slices rather than hours. */
    private val NIGHT = HourWindow(fromHour = 22, toHour = 6)

    /** 09:00 → 17:00, weekdays only. */
    private val WORK_DAY = HourWindow(fromHour = 9, toHour = 17)

    private data class HourWindow(val fromHour: Int, val toHour: Int) {
        val wrapsMidnight: Boolean get() = fromHour > toHour
    }

    private data class Tally(val placeId: String, val ms: Long, val days: Int, val share: Double)

    private fun winner(
        visits: List<VisitDwell>,
        window: HourWindow,
        weekdaysOnly: Boolean,
        config: Config,
        excluding: String?,
    ): Tally? {
        val msByPlace = HashMap<String, Long>()
        val daysByPlace = HashMap<String, MutableSet<Long>>()

        visits.forEach { visit ->
            slices(visit, window, weekdaysOnly).forEach { (dayIndex, ms) ->
                if (ms <= 0) return@forEach
                msByPlace[visit.placeId] = (msByPlace[visit.placeId] ?: 0L) + ms
                daysByPlace.getOrPut(visit.placeId) { HashSet() }.add(dayIndex)
            }
        }

        // ⚠ The denominator is the whole window's dwell INCLUDING the excluded place. Re-normalising
        // over the remainder would let a distant second win Work on a large share of a small
        // leftover — the exclusion removes a candidate, it does not remove the evidence against one.
        val total = msByPlace.values.sum()
        if (total <= 0L) return null

        return msByPlace.entries
            .asSequence()
            .filter { it.key != excluding && it.key.isNotBlank() }
            .map { (placeId, ms) ->
                Tally(placeId, ms, daysByPlace[placeId]?.size ?: 0, ms.toDouble() / total)
            }
            // Deterministic under a tie: more dwell, then more days, then the smaller id. Two devices
            // holding the same window must reach the same verdict, or they write over each other.
            .sortedWith(compareByDescending<Tally> { it.ms }.thenByDescending { it.days }.thenBy { it.placeId })
            .firstOrNull { it.days >= config.minDistinctDays && it.share >= config.minShare }
    }

    /**
     * The visit's dwell split into `(localDayIndex, millisInWindow)` pairs — one per calendar day the
     * visit touches. Walking days rather than computing an intersection directly is what makes a
     * midnight-wrapping window and a multi-day stay fall out of the same code.
     */
    private fun slices(
        visit: VisitDwell,
        window: HourWindow,
        weekdaysOnly: Boolean,
    ): List<Pair<Long, Long>> {
        val startMs = visit.localStartMs
        val endMs = startMs + visit.durationSec * 1_000L
        if (endMs <= startMs) return emptyList()

        val out = ArrayList<Pair<Long, Long>>()
        var day = floorDiv(startMs, DAY_MS)
        val lastDay = floorDiv(endMs - 1, DAY_MS)
        while (day <= lastDay) {
            val dayStart = day * DAY_MS
            if (!weekdaysOnly || isWeekday(day)) {
                val overlap = windowRangesOf(dayStart, window)
                    .sumOf { (from, to) -> overlapMs(startMs, endMs, from, to) }
                if (overlap > 0) out += day to overlap
            }
            day++
        }
        return out
    }

    /**
     * The window's absolute ranges for one local day. A midnight-wrapping window yields two — the
     * early-hours part belonging to *this* day and the late-evening part — so a stay is attributed to
     * the calendar day it actually occurred on rather than to the night it started.
     */
    private fun windowRangesOf(dayStart: Long, window: HourWindow): List<Pair<Long, Long>> =
        if (window.wrapsMidnight) {
            listOf(
                dayStart to dayStart + window.toHour * HOUR_MS,
                dayStart + window.fromHour * HOUR_MS to dayStart + DAY_MS,
            )
        } else {
            listOf(dayStart + window.fromHour * HOUR_MS to dayStart + window.toHour * HOUR_MS)
        }

    private fun overlapMs(aFrom: Long, aTo: Long, bFrom: Long, bTo: Long): Long =
        (minOf(aTo, bTo) - maxOf(aFrom, bFrom)).coerceAtLeast(0L)

    /** Epoch day 0 (1970-01-01) was a Thursday, so `+3` puts Monday at 0 and weekdays at `0..4`. */
    private fun isWeekday(dayIndex: Long): Boolean = floorMod(dayIndex + 3, 7) in 0..4

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if (a % b != 0L && ((a xor b) < 0)) q - 1 else q
    }

    private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L
}

/**
 * The role names this detector emits.
 *
 * ⚠ Deliberately **not** a reference to `PlaceRoles` in `:place-service`: this module is the pure
 * algorithm layer and does not depend on the place schema. The two must agree, which is why the
 * proxy pass that bridges them asserts it — see `PlaceRoleInferencePass`.
 */
object PlaceRoleNames {
    const val HOME = "home"
    const val WORK = "work"
}
