package app.tenshu.timeline.data.capture

import app.tenshu.timeline.data.ImportedTimeline
import app.tenshu.timeline.data.geocode.PlaceLabelEnricher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/**
 * The **durable, never-throwing write core** shared by every capture writer
 * (`CapturedVisitWriter`, `CapturedMoveWriter`). Given an [outbox], a submitted [ImportedTimeline]
 * is journalled durably *before* the record ops are attempted and removed only on confirmed write;
 * [submit]/[drain] then **never throw** (a failed or hung op — bounded by [writeTimeoutMs] — retains
 * the entry and returns), so one bad write can't kill the capture engine. Pending entries replay
 * oldest-first ahead of every submit and via [drain] (app start); the record ops' content-addressed
 * ids make replay idempotent. Without an outbox the legacy direct path remains (fixtures/tests),
 * still shielded from throwing.
 *
 * This factors out the durability contract proven by `CapturedVisitWriterOutboxTest` so the visit
 * and move writers stay thin mappers over the one implementation — no second copy to drift.
 */
internal class DurableImportWriter(
    private val outbox: CapturedVisitOutbox?,
    private val enricher: PlaceLabelEnricher?,
    private val writeTimeoutMs: Long,
    private val writeTimeline: suspend (ImportedTimeline) -> Unit,
    /**
     * `true` while writes must wait — `ControlReachability.realmWritesHeld` (`ADR 0025`
     * §Decision 8.6): Control's device is known to have no realm, or the realm read is pending.
     * Journalled entries then stay in the outbox (nothing is attempted, so nothing is lost) until a
     * drain runs after the gate opens. ⚠ Required, never defaulted (`{ false }` is an explicit open gate).
     */
    private val writesHeld: () -> Boolean,
) {
    /**
     * The write boundary for everything this writer does (ADR 0025 §8.6): handed to the timeline
     * writer (see the constructors) and to the enrichment, and run before EACH synced write. A
     * refusal fails [writeSafely], so the outbox entry is kept — it is removed only after every
     * write of the entry, enrichment included, has landed.
     */
    private val gate: suspend () -> Unit = gateOf(writesHeld)

    /** Persist [timeline] durably (or directly, no outbox), then drain oldest-first. Never throws. */
    suspend fun submit(timeline: ImportedTimeline) {
        if (timeline.isEmpty) return
        if (outbox == null) {
            writeSafely(timeline)
            return
        }
        outbox.persist(timeline)
        drain()
    }

    /**
     * Replay pending entries oldest-first, removing each on confirmed write. Stops at the first
     * failure (a dead proxy isn't hammered once per entry); the rest stay for the next trigger.
     * A no-op while [writesHeld] — the entries wait for the drain that follows the gate opening.
     */
    suspend fun drain() {
        val outbox = outbox ?: return
        outbox.drainLock.withLock {
            for ((id, timeline) in outbox.pending()) {
                // No check of its own (ADR 0025 §8.6): every write goes through [gate], and a
                // refusal returns false here — the entry stays, the drain stops.
                if (!writeSafely(timeline)) return
                outbox.remove(id)
            }
        }
    }

    private suspend fun writeSafely(timeline: ImportedTimeline): Boolean =
        try {
            // The gate before the write too: a test-seam writer has no boundary of its own.
            gate()
            withTimeout(writeTimeoutMs) { writeTimeline(timeline) }
            // Best-effort label enrichment for freshly written places (place-geocoding.md §3);
            // present only where a platform binds a geocoder, consent-gated inside. Empty for moves.
            // ⚠ Through the same boundary, per write (Codex P1 on 6ae61f405).
            enricher?.enrich(timeline.places.map { it.placeId }, beforeEachWrite = gate)
            true
        } catch (e: app.tenshu.reachability.RealmWritesHeldException) {
            println("DurableImportWriter: a write was refused by the realm gate — entry retained")
            false
        } catch (e: TimeoutCancellationException) {
            // ⚠ Must precede the CancellationException rethrow — TimeoutCancellationException IS
            // one, but here it means "the proxy hung", not "our caller was cancelled".
            println("DurableImportWriter: write timed out after ${writeTimeoutMs}ms, retained for replay")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("DurableImportWriter: write failed, retained for replay (${e.message})")
            false
        }
}

/** The write-boundary gate built from a `writesHeld` predicate (ADR 0025 §8.6). */
internal fun gateOf(writesHeld: () -> Boolean): suspend () -> Unit = {
    if (writesHeld()) throw app.tenshu.reachability.RealmWritesHeldException()
}
