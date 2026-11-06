package org.tenshu.timeline.common

/**
 * The provenance tags written into `Moment.sources` (I6, `timeline-ingestion.md` §7.1;
 * `timeline-movement-capture.md` §12.3). Plain strings, like `MomentKind` — an unknown tag from a
 * newer peer must degrade gracefully rather than fail a read.
 *
 * Lives in `-common` because there are genuinely **two** consumers over a neutral input: the capture
 * and import mappers (`:timeline-shared-data`) that *write* the tags, and [ReconciliationConfig]'s
 * [ReconciliationConfig.sourcePriority] that *ranks* them. Those were separate string literals that
 * had to agree without anything enforcing it.
 *
 * ⚠ Until M6 **nothing set these**: `ImportedMoment` had no `sources` field and
 * `TimelineImportWriter` never passed one, so `Moment.sources` was empty on every row in the system.
 * The visible cost was in [MomentReconciler]'s canonical election, whose **source-priority tie-break
 * had no signal** and silently fell through to "earliest start, then smallest id" — so when a
 * Google-imported visit met a live-captured one in the same place cell and time bucket, which
 * survived was an accident of ordering rather than a decision. Populating these is a prerequisite
 * for M7 (Google activity import), the slice that first makes the two sources coexist at scale.
 */
object MomentSourceTag {

    /** On-device live capture (`LocationCaptureEngine` — dwells and movement segments). */
    const val CAPTURE = "capture"

    /** A Google Takeout / Timeline export parsed by `GoogleTimelineParser`. */
    const val GOOGLE = "google"

    /** In-process fixture seeding (`ReferenceTimelineSource`) and other generic file imports. */
    const val IMPORT = "import"

    /** Canonical election order, highest priority first: live capture > Google import > file import. */
    val PRIORITY: List<String> = listOf(CAPTURE, GOOGLE, IMPORT)
}
