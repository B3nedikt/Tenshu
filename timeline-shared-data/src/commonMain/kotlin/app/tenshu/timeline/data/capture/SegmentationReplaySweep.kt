package app.tenshu.timeline.data.capture

import app.tenshu.diagnostics.Diagnostics
import kotlinx.coroutines.flow.first
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.id
import org.tenshu.core.messageOrNull
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.entity.Entity
import org.tenshu.core.fold
import org.tenshu.core.number.TInteger
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.timeline.MovementTrace
import org.tenshu.timeline.client.timelineService

/**
 * The IO half of [SegmentationReplay] — reads the two corpora and reports
 * (`timeline-movement-capture.md` §18.9).
 *
 * Modelled directly on `RailRefinementSweep`, which already reads every stored trace through
 * `TenshuClient` and decodes its polyline, **minus every write**: this sweep is strictly read-only.
 * It answers a question about the rules; it must not become another thing that can change the data
 * it is measuring.
 *
 * Output goes to [Diagnostics], which Settings already renders with copy-to-clipboard — so a run is
 * readable on the device with no export, no `adb`, and no pulling Control's multi-hundred-megabyte
 * store off the phone.
 */
class SegmentationReplaySweep(
    private val client: TenshuClient,
    private val journal: RawFixJournal = RawFixJournal.None,
) {

    /** What one run found, so a caller (or a test) can assert on it rather than on log text. */
    data class Result(
        val traces: SegmentationReplay.TraceReport,
        /** `null` when the raw-fix journal is empty — the S6a half simply has no corpus yet. */
        val fixes: SegmentationReplay.FixReport?,
    )

    suspend fun run(): Result {
        val traces = readTracePaths()
        val traceReport = SegmentationReplay.replayStoredTraces(traces)

        Diagnostics.i(
            "replay",
            "stored traces: ${traceReport.examined} examined, ${traceReport.rejectedCount} would now " +
                "be rejected as jitter, ${traceReport.undecidable} undecidable",
            code = "replay_traces",
        )
        // Per-trace detail for the rejected ones only — these are the §18.3 phantoms, and each line
        // carries the numbers the verdict rested on so a threshold argument can be had from the log
        // rather than from a re-run.
        traceReport.rejected.forEach { v ->
            Diagnostics.d(
                "replay",
                "phantom ${v.trace.traceId} mode=${v.trace.mode} storedDist=${v.trace.storedDistanceM}m " +
                    "extent=${v.extentM.toInt()}m bar=${v.extentFloorM.toInt()}m " +
                    "meanAcc=${v.meanAccuracyM.toInt()}m pts=${v.trace.path.size}/${v.trace.rawPointCount}",
                code = "replay_trace",
            )
        }

        val recorded = journal.readAll()
        val fixReport = if (recorded.size < 3) {
            // ⚠ Said out loud rather than left as a missing line: "no raw-fix corpus" and "the
            // corpus showed no change" are completely different answers, and only one of them is
            // evidence about S6a.
            Diagnostics.i(
                "replay",
                "raw-fix corpus: ${recorded.size} fixes — too few to replay; " +
                    "enable raw-fix recording and capture for a while (the S6a half needs it)",
                code = "replay_no_corpus",
            )
            null
        } else {
            SegmentationReplay.replayRawFixes(recorded).also { r ->
                Diagnostics.i(
                    "replay",
                    "raw-fix corpus: ${r.fixCount} fixes over ${r.spanMs / 3_600_000}h — " +
                        "dwells ${r.before.dwellCount}→${r.after.dwellCount} (${r.dwellsMerged} merged), " +
                        "moves ${r.before.moveCount}→${r.after.moveCount} (${r.movesSuppressed} suppressed)",
                    code = "replay_fixes",
                )
            }
        }
        return Result(traces = traceReport, fixes = fixReport)
    }

    /**
     * ⚠ Traces whose polyline fails to decode are **dropped, not counted as passing** — the same
     * posture [SegmentationReplay.TraceReport.undecidable] takes. An unreadable blob is an unknown,
     * and an instrument that reports unknowns as successes is worse than no instrument.
     */
    private suspend fun readTracePaths(): List<SegmentationReplay.StoredTracePath> =
        client.timelineService.traces
            .select { fields(*TRACE_FIELDS) }
            .flow().first()
            .fold(
                onSuccess = { rows -> rows.toList().mapNotNull(::tracePathOrNull) },
                // ⚠ Same posture as above, one level out: a failed read is an *unknown*, so it is
                // named rather than rendered as "no traces". This is a diagnostic surface the user
                // reads in Settings, and "0 traces" for an IPC failure is exactly the misreport that
                // hid the live `RailRefinementSweep` defect for three days.
                onFailure = { error ->
                    Diagnostics.w(
                        "replay",
                        "trace read failed: ${error.id}${error.messageOrNull()?.let { " — $it" }.orEmpty()}",
                        code = "replay_sweep_read_error",
                    )
                    emptyList()
                },
            )

    private fun tracePathOrNull(e: Entity<out MovementTrace>): SegmentationReplay.StoredTracePath? =
        runCatching {
            fun str(f: String) = e.getFieldValue<TString>(f).get()
            fun int(f: String) = e.getFieldValue<TInteger>(f).get()
            SegmentationReplay.StoredTracePath(
                traceId = str(MovementTrace.Fields.TRACE_ID),
                startMs = e.getFieldValue<UnixInstant>(MovementTrace.Fields.START_AT).toEpochMilliseconds(),
                mode = str(MovementTrace.Fields.MODE),
                storedDistanceM = int(MovementTrace.Fields.DISTANCE_M),
                rawPointCount = int(MovementTrace.Fields.RAW_POINT_COUNT),
                path = MovementTracePolyline.decode(
                    e.getFieldValue<TByteArray>(MovementTrace.Fields.POLYLINE).get(),
                ),
            )
        }.getOrNull()

    private companion object {
        /**
         * ⚠ Deliberately **narrower** than `RailRefinementSweep`'s projection: this sweep needs the
         * geometry and the two length figures, nothing else. A field absent from a projection reads
         * back empty, so widening it "just in case" would cost every row's blob for no question
         * asked.
         */
        private val TRACE_FIELDS = arrayOf(
            MovementTrace.Fields.TRACE_ID,
            MovementTrace.Fields.MODE,
            MovementTrace.Fields.START_AT,
            MovementTrace.Fields.DISTANCE_M,
            MovementTrace.Fields.RAW_POINT_COUNT,
            MovementTrace.Fields.POLYLINE,
        )
    }
}
