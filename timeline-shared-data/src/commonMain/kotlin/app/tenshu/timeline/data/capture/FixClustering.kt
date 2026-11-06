package app.tenshu.timeline.data.capture

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The **streaming counterpart of [VisitDetector.detect]** (I3b, `docs/proposals/timeline-ingestion.md`
 * §4): an online stay-point detector over a *live* fix stream. Where the batch detector clusters a
 * whole list, this emits a [CapturedVisit] the moment the sensor reports a fix outside the current
 * dwell's radius (i.e. the user has left) — the natural cadence for continuous capture, where a visit
 * is only complete on departure. The Android [LocationCaptureSource] composes it as
 * `fixes().detectVisits()`, so the platform layer stays a thin fix emitter and the clustering stays
 * shared, pure `commonMain`, and unit-testable with no hardware.
 *
 * It mirrors the batch algorithm exactly — same anchor-radius run + `minDwellMs` threshold + centroid
 * (via the shared [capturedDistanceMeters] / [toCentroidVisit]) — so a stream of fixes yields the same
 * visits their `listOf` would through [VisitDetector.detect]. **Assumes fixes arrive in time order**
 * (live sensors do); it does not buffer-and-sort like the batch path. A never-ending upstream keeps a
 * single open dwell in memory until departure — bounded by the dwell length, not the whole trace.
 */
fun Flow<CapturedFix>.detectVisits(
    config: VisitDetectionConfig = VisitDetectionConfig(),
): Flow<CapturedVisit> = flow {
    var anchor: CapturedFix? = null
    val cluster = ArrayList<CapturedFix>()

    suspend fun closeCluster() {
        if (cluster.size >= 2 &&
            cluster.last().timestampMs - cluster.first().timestampMs >= config.minDwellMs
        ) {
            emit(cluster.toCentroidVisit())
        }
    }

    collect { fix ->
        val a = anchor
        when {
            a == null -> {
                anchor = fix; cluster.clear(); cluster += fix
            }
            config.holdsInCluster(a, fix) -> {
                cluster += fix
            }
            else -> {
                // Left the anchor's radius → the dwell (if any) is complete; start a new run here.
                closeCluster()
                anchor = fix; cluster.clear(); cluster += fix
            }
        }
    }
    // Upstream ended (capture stopped) → flush the final open dwell.
    closeCluster()
}
