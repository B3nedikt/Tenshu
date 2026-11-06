package app.tenshu.timeline.data.capture

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test

/**
 * **Replay a real recorded day through the live segmenter** (`timeline-movement-capture.md` §18.9).
 *
 * The instrument the track has wanted since M1: [RawFixJournal] records the fix stream verbatim, and
 * this re-reads it so *"how does today's pipeline read a journey I actually made?"* becomes a
 * question with an answer. [SegmentationReplay] already compares two **configs** over a corpus; this
 * asks the other question — what the *current* rules produce, leg by leg, on each day.
 *
 * ⚠⚠ **Skipped unless pointed at a corpus**, like `EmbeddingHarness` and `MvtRealArchiveTest`. The
 * journal is debug-only, opt-in and app-private (threat-model **A35**), so there is no fixture to
 * commit and there must never be one:
 *
 * ```
 * adb exec-out run-as app.tenshu.timeline.android cat files/raw_fix_journal/fixes.csv > /tmp/fixes.csv
 * ./gradlew :timeline-shared-data:desktopTest --tests '*CommuteReplay*' \
 *   -Dtenshu.timeline.fixes=/tmp/fixes.csv
 * ```
 *
 * ⚠⚠ **It prints no coordinates, ever.** A raw fix stream is the most revealing artefact the app
 * produces — strictly more so than anything capture stores, because it is the un-generalised record.
 * Modes, distances, durations and clock times are enough to read a regression; latitudes would make
 * every console log and pasted report a location disclosure. The one place a coordinate would help
 * (*which* station) is exactly the place it must not go.
 *
 * ⚠ It **prints**, it does not assert. What a real journey "should" segment into is a maintainer's
 * judgement about their own day, not a fact this file can hold — and a test that pinned one person's
 * commute would fail for everyone else. `SegmentationReplayTest` is where the assertions live.
 */
class CommuteReplay {

    @Test
    fun replay() {
        val path = System.getProperty("tenshu.timeline.fixes")
        if (path == null) {
            println(
                "CommuteReplay: skipped — pass -Dtenshu.timeline.fixes=/path/to/fixes.csv " +
                    "(adb exec-out run-as … cat files/raw_fix_journal/fixes.csv)",
            )
            return
        }

        val fixes = File(path).readLines().mapNotNull(RawFixCodec::decode).sortedBy { it.timestampMs }
        if (fixes.isEmpty()) {
            println("CommuteReplay: no decodable fixes in $path")
            return
        }

        // ⚠ Step counts are the cycle-vs-run signal and the corpus may predate S3, so the split is
        // reported rather than assumed: a replay over stepless fixes exercises the speed-only
        // fallback, which is a different rule from the one that ran live.
        val withSteps = fixes.count { it.stepsSinceLastFix != null }
        println(
            "CommuteReplay: ${fixes.size} fixes, $withSteps with pedometer " +
                "(${100 * withSteps / fixes.size}%), " +
                "${format(fixes.first().timestampMs)} → ${format(fixes.last().timestampMs)}",
        )

        // ⚠⚠ Grouped by LOCAL day, not by UTC. A commute that starts at 07:00 local sits in the
        // previous UTC day for part of the year, which would silently split every morning in two and
        // make the first leg of each day look like a fragment.
        val byDay = fixes.groupBy { Instant.ofEpochMilli(it.timestampMs).atZone(zone).toLocalDate() }

        byDay.toSortedMap().forEach { (day, dayFixes) ->
            // ⚠ A fresh segmenter per day. Carrying one across midnight would let the previous
            // evening's open move absorb the next morning's departure.
            val segmenter = MovementSegmenter(
                VisitDetectionConfig(),
                MovementDetectionConfig(),
                OpenMoveJournal.None,
            )
            val events = buildList {
                dayFixes.forEach { addAll(segmenter.onFix(it)) }
                addAll(segmenter.onEnd())
            }
            val moves = events.filterIsInstance<CaptureEvent.Move>().map { it.move }
            val dwells = events.filterIsInstance<CaptureEvent.Dwell>()
                .map { it.visit.arrivalMs }.distinct()

            println("── ${day.dayOfWeek.name.take(3)} $day — ${dayFixes.size} fixes, " +
                "${dwells.size} stays, ${moves.size} legs")
            // ⚠⚠ Two adjacent legs mean two COMPLETELY different defects, and the printed list
            // cannot tell them apart. A `ModeSplitter` cut leaves legs that abut exactly — one
            // buffer, several runs, no stay between (§34.2's disease). A segmentation split leaves
            // a GAP, because the segmenter closed the move on a dwell it believed in. One is fixed
            // in the splitter, the other in the visit detector, and reading the wrong one wastes
            // the whole investigation.
            var previousEnd: Long? = null
            moves.forEach { move ->
                val minutes = move.durationMs / 60_000
                val km = move.distanceM / 1000.0
                val kmh = if (move.durationMs > 0) km / (move.durationMs / 3_600_000.0) else 0.0
                println(
                    "   ${format(move.startMs)}–${format(move.endMs)}  " +
                        "${move.mode.padEnd(9)} ${"%6.2f".format(km)} km  " +
                        "${minutes.toString().padStart(3)} min  " +
                        "${"%5.1f".format(kmh)} km/h  " +
                        "conf=${move.classification.confidence}  " +
                        "v${move.classification.version}  " +
                        "fixes=${move.fixes.size}  " +
                        // ⚠⚠ The classifier's OTHER input, printed beside its verdict. §12.1's rule
                        // is "store the inputs, not only the verdict", and mode disputes are almost
                        // never about speed — two legs at the same km/h with different step counts
                        // get different labels, and without this column that looks like caprice.
                        // `null` (no pedometer) and `0` (counted, nobody walked) are different
                        // claims, so they are printed differently.
                        "steps=${stepSummary(move)}",
                )
                previousEnd?.let { end ->
                    val gapSec = (move.startMs - end) / 1000
                    println(
                        if (gapSec <= 1) {
                            "        ↑ SPLITTER CUT — abuts the previous leg, no stay between"
                        } else {
                            "        ↑ after a ${gapSec / 60}m${gapSec % 60}s gap (a stay closed the previous leg)"
                        },
                    )
                }
                previousEnd = move.endMs
            }
        }
    }

    /**
     * `none` = no pedometer on any fix; otherwise total steps, and **where in the leg they fell**.
     *
     * ⚠⚠ The thirds are the load-bearing part. A leg whose steps are spread evenly is one activity;
     * a leg whose steps pile into the first and last third is a **composite** — walk, ride, walk —
     * that the segmenter emitted as one move, and whose averaged cadence then describes neither
     * half. Totals alone cannot tell those apart, and the verdict differs.
     */
    private fun stepSummary(move: CapturedMove): String {
        val reported = move.fixes.mapNotNull { it.stepsSinceLastFix }
        if (reported.isEmpty()) return "none"
        val third = (move.fixes.size / 3).coerceAtLeast(1)
        val head = move.fixes.take(third).sumOf { it.stepsSinceLastFix ?: 0 }
        val tail = move.fixes.takeLast(third).sumOf { it.stepsSinceLastFix ?: 0 }
        val middle = reported.sum() - head - tail
        return "${reported.sum()} [$head|$middle|$tail]"
    }

    private fun format(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(timeFormat)

    private companion object {
        /** ⚠ The device's own zone, because a commute is a local-clock event. */
        val zone: ZoneId = ZoneId.systemDefault()
        val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
