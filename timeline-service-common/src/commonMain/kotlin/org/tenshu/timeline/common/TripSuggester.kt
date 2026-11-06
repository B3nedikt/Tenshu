package org.tenshu.timeline.common

import kotlin.math.roundToLong

/**
 * The **device-local trip-suggestion engine** (I5c, `docs/proposals/timeline-services.md` §5.2): turns
 * a device's moments into "read as a trip?" suggestions. It runs entirely on data the device already
 * holds and the suggestions **do not sync** (§5.2 — a suggestion is a cheap idempotent function of
 * local moments; only the accepted `Trip` syncs, then converges via `TripMerger`).
 *
 * Two jobs on top of the pure [TripDetector]:
 * 1. **Derive home** — the distance-from-home heuristic needs a home coordinate, and none is stored
 *    yet (§10). We derive it locally as **the most-frequented location** (the grid cell holding the
 *    most moments), so the suggester is self-contained; a stored home setting can override this later.
 * 2. **Shape suggestions** — each detected candidate becomes a [TripSuggestion] with a stable
 *    (content-addressed) `suggestedTripId` — so accepting the same suggestion twice folds to one
 *    `Trip` — and a title from the dominant place label among its members. Date formatting is left to
 *    the caller (the app has the datetime formatter; this module deliberately stays dep-free).
 *
 * Pure `commonMain`. The caller (a device-local op / repository) reads moments + their resolved place
 * coordinates, calls [suggest], renders the results, and on accept records a `Trip` with the
 * suggestion's `memberMomentIds` as membership.
 */
object TripSuggester {

    /** Suggest trips from the device's moments. Empty when no home can be derived or nothing is away. */
    fun suggest(
        moments: List<TripSuggestionInput>,
        config: TripSuggestionConfig = TripSuggestionConfig(),
        /**
         * The user's **stored** home coordinate, when a place carries a confirmed `home` role
         * (`place-roles.md` PR-4). Takes precedence over [deriveHome] — this is what the KDoc there
         * has been asking for since I5c.
         */
        storedHome: Pair<Long, Long>? = null,
    ): List<TripSuggestion> {
        // A stated home beats an inferred one. The heuristic answers "where is this device usually?",
        // which is a decent proxy and a bad definition: someone who works six days a week has their
        // most-frequented cell at the office, and every trip would then be measured from there.
        val home = storedHome ?: deriveHome(moments, config.homeGridE7) ?: return emptyList()

        val detectorInputs = moments.map { TripMomentInput(it.momentId, it.timestampMs, it.latE7, it.lngE7) }
        val candidates = TripDetector.detect(detectorInputs, home.first, home.second, config.detection)

        val labelByMoment = moments.associate { it.momentId to it.placeLabel }
        return candidates.map { candidate ->
            TripSuggestion(
                suggestedTripId = "suggested-trip:${candidate.startMs}",
                title = dominantLabel(candidate.memberMomentIds, labelByMoment),
                memberMomentIds = candidate.memberMomentIds,
                startMs = candidate.startMs,
                endMs = candidate.endMs,
            )
        }
    }

    /**
     * The user's home coordinate, derived as the centroid of the **most-frequented grid cell** (where
     * the device holds the most moments). Null when there are no moments.
     *
     * ⚠ This is the **fallback**. A stored home — a place the user confirmed as `home`
     * (`place-roles.md`) — takes precedence, and [suggest] applies it via its `storedHome` parameter.
     * Kept because it is what runs before any role exists, which on a fresh device is most of the
     * time: roles need visit history the device does not have yet.
     */
    fun deriveHome(moments: List<TripSuggestionInput>, gridE7: Long): Pair<Long, Long>? {
        if (moments.isEmpty()) return null
        val homeCell = moments
            .groupBy { snap(it.latE7, gridE7) to snap(it.lngE7, gridE7) }
            .maxByOrNull { it.value.size }
            ?.value
            ?: return null
        return homeCell.map { it.latE7 }.average().roundToLong() to
            homeCell.map { it.lngE7 }.average().roundToLong()
    }

    /** The most common non-blank place label among the members, or "Trip" when none is known. */
    private fun dominantLabel(memberIds: List<String>, labelByMoment: Map<String, String>): String =
        memberIds
            .mapNotNull { labelByMoment[it]?.ifBlank { null } }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: "Trip"

    private fun snap(e7: Long, gridE7: Long): Long = (e7.toDouble() / gridE7).roundToLong() * gridE7
}

/** A moment reduced to what suggestion needs: id, time, place coordinate (E7), and the place's label. */
data class TripSuggestionInput(
    val momentId: String,
    val timestampMs: Long,
    val latE7: Long,
    val lngE7: Long,
    val placeLabel: String = "",
)

/**
 * A "read as a trip?" suggestion. [suggestedTripId] is content-addressed off the candidate so
 * accepting twice folds; [startMs]/[endMs] are raw (the caller formats the human date range).
 */
data class TripSuggestion(
    val suggestedTripId: String,
    val title: String,
    val memberMomentIds: List<String>,
    val startMs: Long,
    val endMs: Long,
)

/** Tuning for [TripSuggester]. */
data class TripSuggestionConfig(
    val detection: TripDetectionConfig = TripDetectionConfig(),
    /** Grid (E7) for home clustering — ~111 m, so a home neighbourhood folds to one cell. */
    val homeGridE7: Long = 10_000L,
)
