package app.tenshu.timeline.data

/**
 * The **acquisition side of the Timeline ingestion seam** (`docs/proposals/timeline-ingestion.md`
 * §2) — a bulk source that produces an [ImportedTimeline] the shared [TimelineImportWriter]
 * persists. DI-bound per environment (the in-process [ReferenceTimelineSource] today; a real
 * platform/import source later), **not** `expect`/`actual` — mirrors Fit's `HealthImporter`
 * decision, avoiding premature `actual` stubs.
 *
 * Two ingestion shapes fall *outside* this bulk contract and get their own seams as they land:
 * the Google `Timeline.json` **file parser** (I2) is user-hands-bytes, not a pollable source (as
 * Fit's `FileHealthImporter` is not a `HealthImporter`); and live **`LocationCaptureSource`** (I3)
 * is a streaming `Flow` of arrival/departure events, not a one-shot load. All three converge on
 * the same [ImportedTimeline] → [TimelineImportWriter] write path.
 */
interface TimelineImportSource {
    suspend fun load(): ImportedTimeline
}
