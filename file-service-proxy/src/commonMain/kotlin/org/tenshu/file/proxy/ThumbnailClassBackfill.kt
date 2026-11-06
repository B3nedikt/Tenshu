package org.tenshu.file.proxy

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.tenshu.core.common.util.sha256
import org.tenshu.core.common.util.toHexLower
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.ThumbnailPreferences

/**
 * ⭐ **The pass that reaches files uploaded before a thumbnail class existed**
 * (`drive-synced-thumbnails.md` §15.3 D-6, §15.11) —
 * pure, so it is testable without a proxy; every collaborator is injected, in the
 * `ContentDeclarationBackfill` shape.
 *
 * ```
 * for each File row this device holds bytes for:
 *     for each class in wanted(realm) − present(file) − declined(file):
 *         if the file's category cannot carry the class   → skip     (MOTION is Video-only)
 *         if this platform cannot mint the class           → skip     (MOTION: Android only)
 *         if the realm has no quota room for it            → STOP the run
 *         mint (the upload's own render) → write → emit (the upload's own emit)
 *         a render that declined                           → RECORD it, so it is never asked again
 * ```
 *
 * ## ⚠⚠ The walk runs every tick; the marker is a report, not a gate (§15.11)
 *
 * A gate that skipped the walk once a marker for the wanted set had latched would be wrong:
 * `hasLocalBytes` is the thing that changes over time (a file whose bytes arrive *after* the latch
 * would never be revisited), and a latch keyed on the wanted set alone cannot see it. The walk
 * is a bounded type scan plus one stat per file, which is milliseconds at thousands of rows and a
 * second at a hundred thousand — cheaper than any second signal that would have to re-arm it. So
 * every tick walks, and the marker written after a clean run says only *"this wanted set was fully
 * served once"*, for diagnostics.
 *
 * ## ⚠⚠ A decline is persisted, and it is the only way a decline may be terminal
 *
 * A render that *declined* (`GetPreview` returned null, the clip encoder said no) is terminal for
 * that file and class: the upload path would have stored nothing either, and re-decoding an
 * unrenderable file every night is the cost §15.3 named. Unpersisted, each tick would re-render
 * every declining file *and* charge it to the per-run budget, and enough of them would stall the
 * walk for ever below their own marker. So a decline is recorded through [recordDecline] and
 * [declinedClasses] hides it from the next walk; the render happens exactly once. ⚠ It still counts toward the budget on the
 * tick it happens, deliberately: a library of ten thousand never-seen declining files is drained
 * across ticks like any other work, not rendered in one night.
 *
 * A render that *failed* — threw — is a transient: counted, not recorded, and the marker is not
 * written after a run that had one. Quota and budget stops withhold it too.
 *
 * ## ⚠⚠ Never correctness-bearing
 *
 * §12.6's rule holds: nothing may depend on this having run. Every outcome
 * here is a *report*; the resolver keeps every fall-through it has, the rotation keeps skipping a
 * tile with no clip, and a device that never idles on a charger simply has a library that moves
 * less.
 */
class ThumbnailClassBackfill(
    /** `wanted(realm)`; `null` when the store could not be read — never treated as empty. */
    private val wantedClasses: suspend () -> Set<String>?,
    /** Every `File` row, schema-driven and bounded; `null` when the store cannot enumerate. */
    private val candidates: () -> Sequence<Candidate>?,
    /**
     * The classes [Candidate] already has here — one stat per class, plus (rarely) a relay check.
     *
     * ⚠ Suspend because a class the keeper evicted counts as present on the strength of a sentinel,
     * and an old sentinel is revalidated against the relay before it is believed
     * (`drive-xxlarge-thumbnail-rung.md` §4.7).
     */
    private val presentClasses: suspend (Candidate) -> Set<String>,
    /** The classes a previous run recorded as declined for [Candidate] — one stat per class. */
    private val declinedClasses: (Candidate) -> Set<String>,
    /** Category **and** platform gating: can this device mint [thumbnailClass] for this file at all? */
    private val canMint: (thumbnailClass: String, candidate: Candidate) -> Boolean,
    /** `ensureWithinQuota` for one blob of [thumbnailClass]'s cap; `false` stops the run. */
    private val hasRoomFor: suspend (thumbnailClass: String) -> Boolean,
    private val mint: suspend (candidate: Candidate, thumbnailClass: String) -> Mint,
    /** Persist a decline so [declinedClasses] hides it from every later walk. */
    private val recordDecline: suspend (candidate: Candidate, thumbnailClass: String) -> Unit,
    /** Written after a clean run with the wanted set's marker — a report, never read by the pass. */
    private val writeMarker: suspend (String) -> Unit,
    /**
     * Whether the content plane can take bytes right now (the metered gate is open, the relay is not
     * refusing on quota).
     *
     * ⚠⚠ Checked **before** anything is rendered, and it ends the run rather than skipping a file. A
     * pass that mints into a closed gate fills the device's disk with blobs that can only wait in the
     * journal or be evicted, up to the whole budget, every tick the gate stays shut. Nothing is lost
     * by waiting: the walk runs again next tick.
     */
    private val canPublish: suspend () -> Boolean = { true },
    private val mintFormat: String = MINT_FORMAT,
    private val maxFilesPerRun: Int = DEFAULT_MAX_FILES_PER_RUN,
    private val maxBytesPerRun: Long = DEFAULT_MAX_BYTES_PER_RUN,
    private val report: (String) -> Unit = {},
) {

    /** One `File` row as the pass sees it. ⚠ [hasLocalBytes] is a stat, not a sync-rule read. */
    data class Candidate(
        val path: String,
        val contentId: String,
        val category: FileCategory,
        val hasLocalBytes: Boolean,
    )

    sealed interface Mint {
        /** Written and emitted; [bytes] counts against the run's budget. */
        data class Stored(val bytes: Int) : Mint

        /** The file cannot carry the class — terminal, recorded, and not a failure. */
        data object Declined : Mint

        /** The render or the write threw — a transient; the marker is withheld and the next tick retries. */
        data object Failed : Mint
    }

    /**
     * [complete] is the marker's condition, not "nothing went wrong": true only when the walk
     * finished, nothing stopped it, and no mint failed. [stoppedBy] names the budget or the quota
     * when one ended the run early. [declined] counts declines recorded on *this* run; files
     * declined on earlier runs are invisible to it, which is the point.
     */
    data class Outcome(
        val skipped: String? = null,
        val filesTouched: Int = 0,
        val classesMinted: Int = 0,
        val bytesMinted: Long = 0,
        val declined: Int = 0,
        val failed: Int = 0,
        val stoppedBy: String? = null,
        val complete: Boolean = false,
    )

    suspend fun run(): Outcome {
        val wanted = wantedClasses()
            ?: return Outcome(skipped = "the wanted set could not be read").also(::log)
        // ⚠ `LIST` and `LARGE` are wanted everywhere implicitly and minted at upload
        // unconditionally; a row that names them is filtered, never acted on.
        val target = ThumbnailPreferences.declarable(wanted)
        val marker = markerFor(target, mintFormat)
        if (target.isEmpty()) {
            writeMarker(marker)
            return Outcome(skipped = "no device wants a class beyond the defaults", complete = true).also(::log)
        }

        if (!canPublish()) {
            return Outcome(skipped = "content cannot be published right now").also(::log)
        }

        val files = candidates()
            ?: return Outcome(skipped = "the store cannot enumerate files").also(::log)

        var filesTouched = 0
        var classesMinted = 0
        var bytesMinted = 0L
        var declined = 0
        var failed = 0
        var stoppedBy: String? = null

        files@ for (candidate in files) {
            currentCoroutineContext().ensureActive()
            if (!candidate.hasLocalBytes) continue
            val absent = target - presentClasses(candidate)
            if (absent.isEmpty()) continue
            // ⚠ Declines are consulted only for a class still absent, so a served library costs
            // one stat per class per row and never looks for a sentinel.
            val missing = absent - declinedClasses(candidate)
            if (missing.isEmpty()) continue
            val mintable = missing.filter { canMint(it, candidate) }
            if (mintable.isEmpty()) continue
            // ⚠ Budget is checked when a file with work is found, so a library of thousands is
            // drained across ticks rather than in one — and a library with nothing left costs
            // one stat per row per class, never a stop.
            if (filesTouched >= maxFilesPerRun || bytesMinted >= maxBytesPerRun) {
                stoppedBy = "budget"
                break@files
            }
            filesTouched++
            for (thumbnailClass in mintable) {
                if (!hasRoomFor(thumbnailClass)) {
                    stoppedBy = "quota"
                    break@files
                }
                when (val result = mint(candidate, thumbnailClass)) {
                    is Mint.Stored -> {
                        classesMinted++
                        bytesMinted += result.bytes
                    }
                    Mint.Declined -> {
                        declined++
                        recordDecline(candidate, thumbnailClass)
                    }
                    Mint.Failed -> failed++
                }
            }
        }

        val complete = stoppedBy == null && failed == 0
        if (complete) writeMarker(marker)

        return Outcome(
            filesTouched = filesTouched,
            classesMinted = classesMinted,
            bytesMinted = bytesMinted,
            declined = declined,
            failed = failed,
            stoppedBy = stoppedBy,
            complete = complete,
        ).also(::log)
    }

    private fun log(outcome: Outcome) {
        report(
            "ThumbnailClassBackfill -> " +
                (outcome.skipped?.let { "skipped: $it" }
                    ?: ("minted ${outcome.classesMinted} classes (${outcome.bytesMinted} B) over " +
                        "${outcome.filesTouched} files" +
                        (if (outcome.declined > 0) ", ${outcome.declined} declined (recorded)" else "") +
                        (if (outcome.failed > 0) ", ${outcome.failed} FAILED" else "") +
                        (outcome.stoppedBy?.let { ", stopped by $it" } ?: ""))) +
                (if (outcome.complete) " — complete for this wanted set" else " — incomplete, resumes next tick"),
        )
    }

    companion object {
        /**
         * Bump to make every device re-try what it declined — a new encoder, a cap change, a fix
         * to a render that said no. A recorded decline carries the format it was recorded
         * under, and a stale one is re-tried rather than trusted. Also hashed into the marker.
         *
         * `v2`: the still mint steps JPEG quality down before declining, and the hero cap is 512 KB
         * (`drive-synced-thumbnails.md` §17) — so a frame a `v1` pass recorded as over its 256 KB cap
         * is asked again.
         */
        const val MINT_FORMAT = "v2"

        /**
         * Files touched per run. ⚠ Unmeasured (§15.8 *Open*): a first guess that keeps one tick to
         * a few minutes on a phone, sized so a 5 000-photo library drains in a few weeks of
         * nightly charging rather than one long night.
         */
        const val DEFAULT_MAX_FILES_PER_RUN = 250

        /** Bytes minted per run — ≈ 230 `XLARGE` stills at their measured ~139 KB. Unmeasured, as above. */
        const val DEFAULT_MAX_BYTES_PER_RUN = 32L * 1024 * 1024

        /**
         * The marker for [wanted] under [mintFormat]: a hash, so the stored row is one short opaque
         * value whatever the set grows to, and a set that changes in any way stops matching.
         */
        fun markerFor(wanted: Set<String>, mintFormat: String = MINT_FORMAT): String =
            sha256("$mintFormat|${wanted.sorted().joinToString(",")}".encodeToByteArray()).toHexLower()
    }
}
