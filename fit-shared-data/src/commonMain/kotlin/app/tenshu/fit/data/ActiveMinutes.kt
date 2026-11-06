package app.tenshu.fit.data

/**
 * WHO **moderate+vigorous active-minutes** from a heart-rate series — the medically-grounded
 * derivation behind the coarse steps proxy (`FitProjection.estimateActiveMinutes`,
 * docs/proposals/fit-app.md §"No Heart Points": *Active Minutes = WHO moderate+vigorous, computed
 * on-device*). Kept **pure** (no client, no platform, no import types) so it unit-tests in
 * isolation and any importer can reduce a day's raw HR series to the single per-day figure it
 * stores on `DailyActivity.activeMinutes`.
 *
 * **Why import-time, per-day int (not a stored series):** a day's HR series is hundreds–thousands
 * of samples; persisting it as synced `HealthSample` rows would bloat the E2E store. The importer
 * holds the raw series transiently anyway, so it computes the figure once and stores only the int —
 * which the projection's per-field merge already prefers over the workout-derived / steps fallbacks
 * (see `FitProjection.dailyAggregates`). No sync weight, no projection plumbing.
 */
object ActiveMinutes {

    /**
     * Intensity floor as a fraction of max HR. WHO/ACSM put **moderate** intensity at ~64% of HRmax
     * and vigorous higher; moderate-*and*-above is therefore everything at or beyond this — exactly
     * the "moderate + vigorous" the headline metric counts.
     */
    private const val MODERATE_FLOOR_FRACTION = 0.64

    /**
     * Cap on the time a single interval between consecutive samples may credit. Dense exercise
     * sampling (every few seconds) is unaffected; this only bounds sparse background sampling so one
     * in-zone reading before a long gap can't credit hours of "active" time. Each sample thus stands
     * for at most ~1 minute.
     */
    private const val MAX_SAMPLE_GAP_MS = 60_000L

    /** A single timestamped heart-rate reading (epoch millis + bpm). */
    data class HrPoint(val timeMs: Long, val bpm: Int)

    /** Standard age-predicted maximum heart rate (bpm): `220 − age`. */
    fun maxHeartRate(age: Int): Int = 220 - age

    /**
     * Moderate+vigorous minutes from a day's HR series against [maxHr]. Samples are ordered by time;
     * each consecutive interval is classified by its **leading** sample (a step function) and, when
     * that sample is at or above [MODERATE_FLOOR_FRACTION] of [maxHr], credits its duration capped at
     * [MAX_SAMPLE_GAP_MS]. Returns whole minutes (floored). A series shorter than two samples, or a
     * non-positive [maxHr], yields 0.
     */
    fun hrZoneMinutes(samples: List<HrPoint>, maxHr: Int): Int {
        if (samples.size < 2 || maxHr <= 0) return 0
        val floorBpm = maxHr * MODERATE_FLOOR_FRACTION
        val ordered = samples.sortedBy { it.timeMs }
        var activeMs = 0L
        for (i in 0 until ordered.size - 1) {
            val lead = ordered[i]
            if (lead.bpm >= floorBpm) {
                val gap = ordered[i + 1].timeMs - lead.timeMs
                activeMs += gap.coerceIn(0L, MAX_SAMPLE_GAP_MS)
            }
        }
        return (activeMs / 60_000L).toInt()
    }
}
