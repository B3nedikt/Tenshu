package app.tenshu.timeline.data.capture

import app.tenshu.timeline.data.ImportedMoment
import app.tenshu.timeline.data.ImportedMovementTrace
import app.tenshu.timeline.data.ImportedTimeline
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.timeline.common.MomentSourceTag
import kotlin.math.roundToLong

/**
 * Maps detected [CapturedMove]s onto the neutral [ImportedTimeline] the shared `TimelineImportWriter`
 * persists (M2, `docs/proposals/timeline-movement-capture.md` §6) — the movement counterpart of
 * `VisitDetector.capturedVisitsToTimeline`. Each move becomes **two** content-addressed rows: a
 * `MOVEMENT` [ImportedMoment] on the ordinary moment spine, plus its [ImportedMovementTrace] subject
 * carrying the summary + the decimated, encoded polyline (§2.1, §4). Idempotent by `startMs`, so a
 * re-emit of the same segment folds to the same rows.
 */
object MovementMapper {

    fun capturedMovesToTimeline(
        moves: List<CapturedMove>,
        config: MovementDetectionConfig = MovementDetectionConfig(),
    ): ImportedTimeline {
        val moments = ArrayList<ImportedMoment>(moves.size)
        val traces = ArrayList<ImportedMovementTrace>(moves.size)

        for (m in moves) {
            val traceId = "cap-trace:${m.startMs}"
            val distanceM = m.distanceM.roundToLong()
            val simplified = MovementTracePolyline.simplify(m.fixes, config)

            moments += ImportedMoment(
                momentId = "cap-move:${m.startMs}",
                timestampMs = m.startMs,
                kind = Moment.MomentKind.MOVEMENT,
                title = moveTitle(m.mode, distanceM),
                subjectKind = SubjectKind.TRACE,
                subjectId = traceId,
                durationSec = m.durationMs / 1000,
                sources = listOf(MomentSourceTag.CAPTURE),
            )
            traces += ImportedMovementTrace(
                traceId = traceId,
                mode = m.mode,
                classifierVersion = m.classification.version.toLong(),
                modeConfidence = m.classification.confidence.toLong(),
                sourceActivityType = "", // live capture has no external label — our classifier owns `mode`
                medianSpeedMmPerS = m.classification.medianSpeedMmPerS,
                distanceM = distanceM,
                startMs = m.startMs,
                endMs = m.endMs,
                pointCount = simplified.size.toLong(),
                rawPointCount = m.fixes.size.toLong(),
                minLatE7 = m.fixes.minOf { it.latE7 },
                minLngE7 = m.fixes.minOf { it.lngE7 },
                maxLatE7 = m.fixes.maxOf { it.latE7 },
                maxLngE7 = m.fixes.maxOf { it.lngE7 },
                startLatE7 = m.fixes.first().latE7,
                startLngE7 = m.fixes.first().lngE7,
                endLatE7 = m.fixes.last().latE7,
                endLngE7 = m.fixes.last().lngE7,
                polyline = MovementTracePolyline.encode(simplified),
                steps = stepsOver(m.fixes),
            )
        }
        return ImportedTimeline(moments = moments, traces = traces)
    }

    /**
     * Total steps walked over [fixes] (FA-4), or `-1` when nobody counted.
     *
     * ⚠ Sums `fixes[1..]`, never `fixes[0]`: `stepsSinceLastFix` is the interval *ending* at a fix,
     * so the first fix's reading belongs to whatever came before the move — and [ModeSplitter]
     * hands neighbouring moves a **shared** boundary fix, which would otherwise count twice. The
     * window is the half-open `(first, last]`, the same one `LegMode.cadenceOver` uses.
     *
     * ⚠⚠ Any contributing `null` makes the whole sum `-1`. Summing around a gap would report a
     * walk whose pedometer missed one sample as fewer steps than it took — a number no sensor said.
     *
     * ⚠ Adds each fix's [CapturedFix.stepsDroppedBefore]: §22 hygiene drops fixes (out of order,
     * coarse interior vertices) whose pedometer deltas were still walked, and carries them there.
     */
    internal fun stepsOver(fixes: List<CapturedFix>): Long {
        if (fixes.size < 2) return -1
        var steps = 0L
        for (i in 1 until fixes.size) {
            steps += fixes[i].stepsSinceLastFix ?: return -1
            steps += fixes[i].stepsDroppedBefore ?: return -1
        }
        return steps
    }

    /** Neutral fallback title, e.g. "Walk · 2.4 km" — [movementTitle], the one generator (`:timeline-projection`). */
    internal fun moveTitle(mode: String, distanceM: Long): String = movementTitle(mode, distanceM)
}
