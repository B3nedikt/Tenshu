package app.tenshu.timeline.data

import app.tenshu.takeout.TakeoutArchive
import app.tenshu.takeout.TakeoutEntryKind
import app.tenshu.takeout.TakeoutRouter
import app.tenshu.timeline.data.geocode.PlaceLabelEnricher
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.core.TContext
import org.tenshu.sync.client.withBulkWriteTask

/** C4: the `GenericTaskSpec` kind id the Google-Timeline file import registers its lane under. */
private const val TIMELINE_FILE_IMPORT_TASK_KIND = "timeline/file-import"

/**
 * The **Google-Timeline file-import entry point** (I2, `docs/proposals/timeline-ingestion.md` §3):
 * parses picked [TimelineImportFile]s via [GoogleTimelineParser] and persists the result through
 * the shared [TimelineImportWriter] — the same write path the reference / live-capture sources use,
 * so an imported visit reads back through the projector identically. Idempotent (every entity is
 * content-addressed, so re-importing the same export folds to the same rows).
 *
 * Not a [TimelineImportSource]: import is a *user-hands-us-bytes* action, not a pollable source, so
 * it stays a parallel path over the writer rather than distorting the bulk-source seam — exactly as
 * Fit's `FileHealthImporter` sits beside its `HealthImporter`. The picker wizard (I2b) calls
 * [importFiles].
 */
/**
 * What importing a whole export produced (B1, TB-D2).
 *
 * ⚠⚠ [understood] and [total] are reported separately on purpose: "imported 4 200 entries from 412
 * files" hides that 32 files were not understood, and a user who exported in German and sees
 * "understood 0 of 412" can tell us something useful. A number that only ever counts successes
 * cannot say that anything went wrong.
 */
data class TakeoutImportResult(
    /** Records written — places, moments and traces, the same count [FileTimelineImporter.importFiles] returns. */
    val imported: Int,
    /** Entries in the archive. */
    val total: Int,
    /** Entries whose kind we recognised (of any sort, not only timeline). */
    val understood: Int,
    /** Timeline exports found inside it. */
    val timelineFiles: Int,
    /** A sample of what we could not place — never the whole list. */
    val unmatched: List<String>,
    /** The photo half (B5), or [TakeoutPhotoResult.None] when the archive held none. */
    val photos: TakeoutPhotoResult = TakeoutPhotoResult.None,
)

class FileTimelineImporter(
    private val client: TenshuClient,
    private val context: TContext,
    private val enricher: PlaceLabelEnricher? = null,
) {
    /**
     * Import a whole **Takeout archive** (B1) — the unit the user actually has.
     *
     * ⚠ Only the timeline entries are read. The photos in the same archive are B5's business, and
     * reading them here would pull tens of gigabytes through a path built for JSON.
     *
     * ⚠ The sniff reads a *prefix* of each candidate JSON, not the file: an export's monthly
     * timeline files run to tens of megabytes each, and the marker is in the first bytes.
     *
     * ⚠⚠ Idempotent, like every other import path here (TB-D8): every entity is content-addressed
     * off the export's own invariants, so re-running a half-finished import folds to the same rows
     * instead of duplicating them. That is what makes it safe for a big archive to fail halfway.
     */
    suspend fun importArchive(archive: TakeoutArchive): TakeoutImportResult {
        var total = 0
        var understood = 0
        val unmatched = mutableListOf<String>()
        val files = mutableListOf<TimelineImportFile>()

        // ⚠⚠ ONE pass, and the name decides before the bytes are read: only entries the name cannot
        // settle (the JSONs) are read at all. That is what lets the same code run over a `content://`
        // stream on Android — the photos beside them are counted and never touched.
        archive.walk(
            wanted = { name ->
                total++
                val byName = TakeoutRouter.kindOfName(name)
                if (byName != null) {
                    if (byName != TakeoutEntryKind.UNKNOWN) understood++ else remember(unmatched, name)
                }
                byName == null
            },
        ) { name, bytes ->
            when (TakeoutRouter.kindOfContent(bytes?.copyOf(minOf(SNIFF_BYTES, bytes.size)))) {
                TakeoutEntryKind.TIMELINE -> {
                    understood++
                    // ⚠ Kept, because a deflated entry cannot be partially inflated: the sniff
                    // already cost the whole file, and reading it again to parse would double the
                    // work on the largest files in the archive.
                    bytes?.let { files += TimelineImportFile(name, it) }
                }

                else -> remember(unmatched, name)
            }
        }

        return TakeoutImportResult(
            imported = if (files.isEmpty()) 0 else importFiles(files),
            total = total,
            understood = understood,
            timelineFiles = files.size,
            unmatched = unmatched,
        )
    }

    private fun remember(unmatched: MutableList<String>, name: String) {
        if (unmatched.size < TakeoutRouter.UNMATCHED_SAMPLE) unmatched += name
    }

    /** Parse + persist the exports; returns the number of records written (0 if nothing parsed). */
    suspend fun importFiles(files: List<TimelineImportFile>): Int {
        val imported = GoogleTimelineParser.parseAll(files)
        if (imported.isEmpty) return 0
        // C4 (`sync-device-catch-up.md` §11.7): the whole write phase is ONE bulk task, so a
        // Takeout export's thousands of places/moments/traces ride the relay as batched deltas
        // instead of a `SyncBlock` each. Fit's file import (TS-4) has done this since it landed;
        // Timeline's — the *larger* of the two, since an export carries years of moments — was
        // simply never wrapped, which is the gap C4's audit existed to find.
        //
        // ⚠ Only the writer is inside. The parse is pure, and the enricher below reaches the
        // network per place: holding the lane open across it would keep a lease alive for the
        // duration of a geocoding run, and its label writes are better off as ordinary saves
        // (they trickle in over minutes, so there is nothing to batch).
        //
        // ⚠ The capture path deliberately does NOT get this treatment: `CapturedVisitWriter` and
        // `CapturedMoveWriter` share this same [TimelineImportWriter], but they call it with one
        // event at a time — a lane there would add a registration round trip per visit to batch a
        // single row.
        client.withBulkWriteTask(TIMELINE_FILE_IMPORT_TASK_KIND) {
            // Explicitly open: a file import is a user act behind the wall (ADR 0025 §8.6).
            TimelineImportWriter(client, context, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(imported)
        }
        // Best-effort label enrichment for the freshly imported places (place-geocoding.md §3):
        // present only where a platform binds a geocoder, consent-gated + never-throwing inside.
        enricher?.enrich(imported.places.map { it.placeId }, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)
        return imported.count
    }
}

/**
 * ⚠ Enough to carry the top-level key both vintages open with, and small enough that sniffing a
 * few hundred candidates costs nothing.
 */
private const val SNIFF_BYTES = 512

/**
 * A whole Takeout import: the timeline, then — only if the caller asked — the photos (B5).
 *
 * ⚠⚠ **The photo half is opt-in and never implicit.** "I imported my location history" must not
 * quietly upload forty gigabytes of photographs; the switch that turns this on says so.
 *
 * ⚠ Timeline first, deliberately. The timeline half is megabytes and minutes, the photo half can be
 * hours — and a user who closes the app in between should still have their history.
 */
suspend fun importTakeout(
    archive: TakeoutArchive,
    timeline: FileTimelineImporter,
    photos: TakeoutPhotoImporter?,
): TakeoutImportResult {
    val result = timeline.importArchive(archive)
    val photoResult = photos?.import(archive) ?: TakeoutPhotoResult.None
    return result.copy(photos = photoResult)
}
