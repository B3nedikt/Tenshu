package app.tenshu.timeline.data.capture

import kotlinx.coroutines.flow.Flow

/**
 * The **live-capture acquisition seam** (`docs/proposals/timeline-ingestion.md` §4 +
 * `timeline-movement-capture.md` §6) — a platform source of detected [CaptureEvent]s (dwells **and**
 * movement) that [LocationCaptureEngine] persists through the shared `TimelineImportWriter`.
 * **DI-bound, not `expect`/`actual`** (mirrors Fit's `HealthImporter`): each platform binds its own
 * impl, avoiding premature `actual` stubs.
 *
 * **Detected events are the common currency**, deliberately, because the platforms disagree on the
 * level: Android emits a raw fix stream segmented ([segment], M1) into dwells + moves *inside* its
 * impl; iOS `CLVisit` already *is* an arrival/departure event (→ `Dwell`), with movement from
 * significant-location-change (M4, §6). Publishing events — not raw fixes — keeps the engine and
 * every consumer platform-agnostic. (Movement capture M3 widened this from `visits()` to [events];
 * iOS's direct-write path stays on `CapturedVisitWriter`/`CapturedMoveWriter`, unaffected.)
 *
 * Off by default: [events] is cold and only ever collected while capture is explicitly enabled (the
 * Android foreground service, started after the user grants location — the opt-in gate, §6).
 * [isAvailable] reports whether the platform can capture at all (sensor present + permission held);
 * a source that can't returns `false` and an empty [events] rather than throwing.
 */
interface LocationCaptureSource {
    fun isAvailable(): Boolean
    fun events(): Flow<CaptureEvent>
}
