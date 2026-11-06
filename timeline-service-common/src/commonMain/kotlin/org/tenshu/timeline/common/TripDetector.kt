package org.tenshu.timeline.common

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The **device-independent trip detector** (I5, `docs/proposals/timeline-services.md` §5.2): groups a
 * device's moments into candidate trips by **distance-from-home + time-gap**. A trip is a run of
 * moments that are *away* from home (beyond [TripDetectionConfig.awayRadiusM]) and close together in
 * time (no gap over [TripDetectionConfig.maxGapMs]), long/large enough to be a real journey.
 *
 * Pure `commonMain` math — no schema, service, or IO dependency — so it is reused **at both
 * detect-time and merge-time** (§5.2): the app suggests a trip from local moments, and the
 * `TripMerger` re-runs the *same* function over a receiving device's local moments against an
 * incoming trip's span to union any moments that device already held. Keeping it a pure function of
 * its inputs is what lets it live inside a `SemanticMerger.merge` (which may not do service IO).
 *
 * ⚠ **Home is an input, deliberately** — the distance-from-home heuristic needs the user's home
 * coordinate, but a merger may not read a service to get it (§10 purity constraint). Passing home in
 * resolves that: the *caller* supplies it from local state, and the detector stays pure. Coordinates
 * are E7 integers; instants are epoch-millis.
 */
object TripDetector {

    /**
     * Detect candidate trips. [moments] need not be sorted. Returns time-ordered candidates; moments
     * near home, and runs too short/small to be a trip, are excluded.
     */
    fun detect(
        moments: List<TripMomentInput>,
        homeLatE7: Long,
        homeLngE7: Long,
        config: TripDetectionConfig = TripDetectionConfig(),
    ): List<TripCandidate> {
        val away = moments
            .filter { haversineM(it.latE7, it.lngE7, homeLatE7, homeLngE7) > config.awayRadiusM }
            .sortedBy { it.timestampMs }
        if (away.isEmpty()) return emptyList()

        val candidates = ArrayList<TripCandidate>()
        var group = ArrayList<TripMomentInput>()

        fun flush() {
            if (group.size >= config.minMoments &&
                group.last().timestampMs - group.first().timestampMs >= config.minDurationMs
            ) {
                candidates += TripCandidate(
                    memberMomentIds = group.map { it.momentId },
                    startMs = group.first().timestampMs,
                    endMs = group.last().timestampMs,
                )
            }
        }

        for (m in away) {
            if (group.isEmpty() || m.timestampMs - group.last().timestampMs <= config.maxGapMs) {
                group += m
            } else {
                flush()
                group = arrayListOf(m)
            }
        }
        flush()
        return candidates
    }

    /**
     * Merge-time helper (§5.2): given an incoming trip's span, return the ids of [moments] that fall
     * inside it and are away from home — i.e. the members a receiving device would add to the trip.
     * The union of these with the incoming membership is how a trip gets *more complete* as devices
     * converge, without re-prompting "is this a trip?".
     */
    fun membersWithinSpan(
        moments: List<TripMomentInput>,
        startMs: Long,
        endMs: Long,
        homeLatE7: Long,
        homeLngE7: Long,
        config: TripDetectionConfig = TripDetectionConfig(),
    ): List<String> = moments
        .filter { it.timestampMs in startMs..endMs }
        .filter { haversineM(it.latE7, it.lngE7, homeLatE7, homeLngE7) > config.awayRadiusM }
        .sortedBy { it.timestampMs }
        .map { it.momentId }

    private fun haversineM(latE7a: Long, lngE7a: Long, latE7b: Long, lngE7b: Long): Double {
        val earthRadiusM = 6_371_000.0
        val lat1 = latE7a / 1e7
        val lon1 = lngE7a / 1e7
        val lat2 = latE7b / 1e7
        val lon2 = lngE7b / 1e7
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2) * sin(dLon / 2)
        return earthRadiusM * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
}

/** A moment reduced to what trip detection needs: its id, time, and place coordinate (E7). */
data class TripMomentInput(
    val momentId: String,
    val timestampMs: Long,
    val latE7: Long,
    val lngE7: Long,
)

/** A detected candidate journey: its member moments (time-ordered) and span. */
data class TripCandidate(
    val memberMomentIds: List<String>,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Trip-detection tuning. Conservative first guesses (`timeline-ingestion.md` §8 — real values need
 * captured history); a "trip" is travel meaningfully away from home, not a local outing.
 */
data class TripDetectionConfig(
    /** Beyond this distance from home a moment counts as "away" (metres). Default ~100 km. */
    val awayRadiusM: Double = 100_000.0,
    /** Away-moments more than this far apart in time belong to different trips. Default 36 h. */
    val maxGapMs: Long = 36 * 60 * 60 * 1000L,
    /** A trip needs at least this many away-moments. */
    val minMoments: Int = 2,
    /** …and must span at least this long. Default 12 h. */
    val minDurationMs: Long = 12 * 60 * 60 * 1000L,
)
