package app.tenshu.timeline.data.capture

import app.tenshu.timeline.data.ImportedTimeline
import app.tenshu.timeline.data.TimelineImportWriter
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.core.TContext

/**
 * The **movement-segment write primitive** (M2, `docs/proposals/timeline-movement-capture.md` §6) —
 * the move counterpart of [CapturedVisitWriter]. Maps one or more detected [CapturedMove]s onto the
 * neutral `ImportedTimeline` ([MovementMapper]) — a `MOVEMENT` moment + its `MovementTrace` subject
 * per move — and persists through the shared [TimelineImportWriter], so a captured move reads back
 * through the projector identically. Idempotent (content-addressed by `startMs`).
 *
 * Shares the durable, never-throwing [DurableImportWriter] core with [CapturedVisitWriter] (same
 * outbox, same replay), so a move detected while the proxy has no realm / is unreachable / hangs is
 * journalled and replayed rather than lost, and a bad write can't kill the capture engine. Android's
 * pull engine forwards each `CaptureEvent.Move` here (M3); the seam is symmetric with the visit path.
 */
class CapturedMoveWriter internal constructor(
    private val config: MovementDetectionConfig,
    private val durable: DurableImportWriter,
) {
    constructor(
        client: TenshuClient,
        context: TContext,
        config: MovementDetectionConfig = MovementDetectionConfig(),
        outbox: CapturedVisitOutbox? = null,
        writeTimeoutMs: Long = 30_000L,
        /** ADR 0025 §Decision 8.6 write gate — see [DurableImportWriter]. ⚠ Required. */
        writesHeld: () -> Boolean,
    ) : this(config, DurableImportWriter(outbox, enricher = null, writeTimeoutMs, TimelineImportWriter(client, context, beforeEachWrite = gateOf(writesHeld))::write, writesHeld))

    /** Persist a single detected move. */
    suspend fun write(move: CapturedMove) = writeAll(listOf(move))

    /** Persist a batch of moves — idempotent, never throws. */
    suspend fun writeAll(moves: List<CapturedMove>) {
        if (moves.isEmpty()) return
        durable.submit(MovementMapper.capturedMovesToTimeline(moves, config))
    }

    /** Replay pending outbox entries oldest-first. Never throws. */
    suspend fun drainOutbox() = durable.drain()
}
