package app.tenshu.timeline.data.capture

import app.tenshu.timeline.data.ImportedTimeline
import app.tenshu.timeline.data.TimelineImportWriter
import app.tenshu.timeline.data.geocode.PlaceLabelEnricher
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.core.TContext

/**
 * The **event-based visit write primitive** (`docs/proposals/timeline-ingestion.md` §4): maps one or
 * more detected [CapturedVisit]s onto the neutral `ImportedTimeline` (`VisitDetector.capturedVisitsToTimeline`)
 * and persists them through the shared [TimelineImportWriter] — the single write path all capture
 * sources converge on, so a captured visit reads back through the projector identically. Idempotent
 * (visits are content-addressed by `(place, arrival)`).
 *
 * Two capture models share it: the Android pull model — [LocationCaptureEngine] collects a fix-derived
 * `Flow<CapturedVisit>` and forwards each here; and the **iOS event model (I4)** — `CLVisit` wakes the
 * app per arrival/departure, so the native delegate hands each visit *directly* to [write] and awaits
 * it. A direct, awaitable write fits iOS's brief, event-driven background execution better than a
 * long-lived `Flow` + collector (which might not drain before the OS re-suspends the app), so this is
 * the iOS-facing seam across the SDK boundary (§4.2).
 *
 * **Durability (tech-debt "silent visit loss before onboarding").** With an [outbox] bound, a
 * detected visit is journalled durably *before* the record ops are attempted and removed only on
 * confirmed write; [writeAll] then **never throws** (a failed or hung op — bounded by
 * [writeTimeoutMs] — retains the entry and returns), so one bad write can no longer kill the
 * capture engine and silently stop the foreground service. Pending entries are replayed
 * oldest-first ahead of every new write and via [drainOutbox] (app start); the record ops'
 * content-addressed ids make replay idempotent. Without an outbox the legacy direct path remains
 * (fixtures/tests), still shielded from throwing.
 */
class CapturedVisitWriter internal constructor(
    private val config: VisitDetectionConfig,
    private val durable: DurableImportWriter,
) {
    /** Test seam: the internal constructor `CapturedVisitWriterOutboxTest` drives directly. */
    internal constructor(
        config: VisitDetectionConfig,
        enricher: PlaceLabelEnricher?,
        outbox: CapturedVisitOutbox?,
        writeTimeoutMs: Long,
        writeTimeline: suspend (ImportedTimeline) -> Unit,
        writesHeld: () -> Boolean,
    ) : this(config, DurableImportWriter(outbox, enricher, writeTimeoutMs, writeTimeline, writesHeld))

    constructor(
        client: TenshuClient,
        context: TContext,
        config: VisitDetectionConfig = VisitDetectionConfig(),
        enricher: PlaceLabelEnricher? = null,
        outbox: CapturedVisitOutbox? = null,
        writeTimeoutMs: Long = 30_000L,
        /** ADR 0025 §Decision 8.6 write gate — see [DurableImportWriter]. ⚠ Required. */
        writesHeld: () -> Boolean,
    ) : this(config, DurableImportWriter(outbox, enricher, writeTimeoutMs, TimelineImportWriter(client, context, beforeEachWrite = gateOf(writesHeld))::write, writesHeld))

    /**
     * Persist a single detected visit (e.g. one iOS `CLVisit`). [placeIdOverride] (Wi-Fi recognition,
     * §4.5) folds the visit onto that place instead of its grid-snapped one; `null` = grid-snap.
     */
    suspend fun write(visit: CapturedVisit, placeIdOverride: String? = null) =
        writeAll(listOf(visit)) { placeIdOverride }

    /** Persist a batch of visits — folds repeat visits to one place, idempotent. Never throws. */
    suspend fun writeAll(
        visits: List<CapturedVisit>,
        placeIdOverride: (CapturedVisit) -> String? = { null },
    ) {
        if (visits.isEmpty()) return
        durable.submit(VisitDetector.capturedVisitsToTimeline(visits, config, placeIdOverride))
    }

    /** Replay pending outbox entries oldest-first (app start / after a recovered proxy). Never throws. */
    suspend fun drainOutbox() = durable.drain()
}
