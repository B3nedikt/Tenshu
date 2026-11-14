package org.tenshu.inference.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.get
import org.tenshu.inference.INDEX_MARKER_LABEL
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.MediaLabel
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.vocabulary.LabelVocabulary
import org.tenshu.inference.proxy.vocabulary.selectLabels

/**
 * One file to look at, and how to get its bytes — resolved lazily, so a skip costs no read.
 *
 * @param preview a fallback for when [bytes] has nothing — the photograph is stored only on the
 *   relay — so the pass can still index it from a preview. `null` = no fallback.
 */
data class IndexableMedia(
    val filePath: String,
    val preview: (suspend () -> PreviewBytes?)? = null,
    // ⚠ LAST, so `IndexableMedia(path) { bytes }` keeps binding its trailing lambda here.
    val bytes: suspend () -> ByteArray?,
)

/**
 * A preview to index from when the original is not on this device.
 *
 * ⚠⚠ **Upright, so no orientation travels with it.** A thumbnail rung is stored the way it is
 * shown (`ExifUpright` in `:file-service-proxy`). Applying the original's EXIF orientation to it
 * would turn every iPhone-minted rung — upright from the start — a second time, and a face lying
 * on its side is mostly not found.
 */
class PreviewBytes(val bytes: ByteArray)

/** What one pass did. ⚠ [skipped] is reported, not swallowed: a pass that indexed nothing has a reason. */
data class IndexPassResult(
    val labelled: Int = 0,
    val alreadyCurrent: Int = 0,
    /**
     * Photographs whose bytes are not on this device — `IndexableMedia.bytes` answered `null`.
     *
     * ⚠⚠ Not "unreadable": a bytes-less file is a photograph stored only on the relay, not a
     * broken one, and on a phone whose library lives mostly on the relay it is most of the
     * library. A file whose bytes ARE here but do
     * not decode is recorded by the model half (`Unreadable` → visited), not counted here.
     */
    val notOnDevice: Int = 0,
    /** Photographs indexed from a PREVIEW because the original is not here. */
    val fromPreview: Int = 0,
    val skippedNoConsent: Int = 0,
    val skippedNoBackend: Int = 0,
    /** Vectors written to the [EmbeddingStore]. ⚠ Zero is normal: no sink, or no consent
     * for search even though tagging is on — the two capabilities are separately switched. ⚠ A
     * file this device cannot decode is neither embedded nor rejected: it is recorded as visited. */
    val embedded: Int = 0,
    /** ⚠ Vectors the sink REFUSED — a foreign model stamp or a changed width. Counted rather than
     * thrown, like [notOnDevice]: a pass that meets one mid-library should finish and say so. */
    val embeddingsRejected: Int = 0,
    /**
     * ⚠ Photos a model was ASKED about and failed on this pass. Never remembered against the photo,
     * so the next pass tries them again.
     *
     * ⚠⚠ A count of photos, not a verdict on the model: one photo the model cannot embed in a pass
     * that embedded 500 is `embeddingsFailed = 1` and [embeddingStoodDown] `false`. For the vector
     * half it excludes [embeddingsNotAttempted]. ⚠⚠ The vector half ONLY — the face half has
     * [facesFailed]: one shared counter printed `embedFailed=29` over a SigLIP that was fine, when
     * only the face detector had latched.
     */
    val embeddingsFailed: Int = 0,
    /**
     * Photos whose vector was never asked for, because the vector half had stood down
     * ([embeddingStoodDown]). Not remembered either: the next pass asks.
     */
    val embeddingsNotAttempted: Int = 0,
    /**
     * The pass stopped asking for vectors: [EMBED_FAILURE_STREAK_LIMIT] photos in a row failed with
     * no vector made in between, which is read as "the model is failing", not "these photos are bad".
     *
     * ⚠ Its own field because the counts cannot say it. A streak that completes on the last photos
     * of the walk leaves [embeddingsNotAttempted] at 0, and [embeddingsFailed] alone cannot tell
     * three bad photos from a dead model.
     */
    val embeddingStoodDown: Boolean = false,
    /**
     * Images this pass actually ran the model over — the denominator for [labelFiringCounts].
     *
     * ⚠ Not the library size and not [labelled]: it excludes everything skipped, so a pass over an
     * already-indexed library considers almost nothing and its rates say almost nothing.
     */
    val consideredImages: Int = 0,
    /**
     * Faces the detector found, across every photograph it looked at.
     *
     * ⚠ Counted apart from [facesEmbedded]: a detector that finds faces the embedder then refuses
     * is a very different failure from one that finds none, and a single number hides which.
     */
    val facesFound: Int = 0,
    /**
     * Photographs the face half could not do because its model could not run — a `Failed` detection,
     * an embedder that returned `null` — and the ones it skipped after that latched. ⚠ The face
     * half's own: [embeddingsFailed] is the vector half's.
     */
    val facesFailed: Int = 0,
    /** Face records the [FaceTemplateStore] REFUSED (a foreign stamp or width), and the ones skipped after. ⚠ [embeddingsRejected] is the vector half's. */
    val facesRejected: Int = 0,
    /**
     * Photographs whose face record the store ACCEPTED this pass — with faces, or the empty record of a
     * photograph with none. ⚠ Not [facesEmbedded]: a first pass over a faceless library writes 0
     * templates and N records, and People then answers "nobody found", not "index first".
     */
    val faceRecordsWritten: Int = 0,
    /** Templates actually written to the [FaceTemplateStore]. */
    val facesEmbedded: Int = 0,
    /** Detections the embedder judged not a human face and left out (§12.15) — a cat, a doll, food. */
    val facesNotHuman: Int = 0,
    /**
     * Photographs the detector was run over.
     *
     * ⚠⚠ The denominator that makes the other two readable — and it includes the ones with **no**
     * faces, which is most of a real library. "0 faces from 4 000 photographs" and "0 faces from 3"
     * are the same headline and completely different situations.
     */
    val photosSearchedForFaces: Int = 0,
    /**
     * How many of [consideredImages] each label fired on (§7.7's *"true and useless"* check).
     *
     * ⚠⚠ Counted here because this is the only place that sees the aggregate. Per image every
     * result looks reasonable — `indoors` on an indoor photograph is simply correct — and the
     * defect exists only across the library: `indoors` reached the top 3 for **55–60 %** of a real
     * one, which is a label carrying no information at the cost of a row on every photograph.
     */
    val labelFiringCounts: Map<String, Int> = emptyMap(),
    /**
     * The user paused indexing on this device and the pass **stopped early** (IN-D6).
     *
     * ⚠⚠ Reported rather than inferred from the counts, because "stopped because you asked" and
     * "finished, having found nothing to do" produce identical numbers — and only one of them
     * means the library is still part-indexed. A caller that guessed would tell the user the wrong
     * one.
     */
    val paused: Boolean = false,
    /**
     * How far into [media] the pass got — the item it stopped at when [paused], otherwise the whole
     * list.
     *
     * ⚠⚠ Also the only way "a pause **stops** the pass" is observable at all: stopping and skipping
     * write exactly the same rows, so without this the difference is a comment nothing can check.
     * ⭐ And it is what a UI needs to say "stopped at 300 of 9 000" rather than just "stopped".
     */
    val imagesVisited: Int = 0,
    /**
     * The device was not plugged in and unattended — before the pass, or partway through it — and the
     * pass stopped ([IndexingConditions]). ⚠ Reported for [paused]'s reason: "stopped because the
     * device got busy" and "finished" write the same rows, and only one leaves the library part-indexed.
     */
    val deferred: Boolean = false,
    /**
     * Photographs whose face templates the pass's reconcile dropped because the walk does not
     * offer them any more — deleted, trashed, or deleted on another device and synced here.
     */
    val staleFacesRemoved: Int = 0,
    /** The [EmbeddingStore] half of [staleFacesRemoved]: vectors and vectorless visits dropped. */
    val staleVectorsRemoved: Int = 0,
    /**
     * Whether the face half ran this pass, and if not, why — the pass report's `faces=` field.
     *
     * ⚠⚠ Its own field because the counts cannot say it. With search able to run and faces not, a
     * photograph whose vector is current is counted [alreadyCurrent], and [skippedNoBackend] /
     * [skippedNoConsent] count only photos for which NOTHING could run — so "the face model is not
     * ready" and "every face is done" printed the same line on a device pass.
     */
    val faceHalf: FaceHalf = FaceHalf.NOT_ASKED,
    /** Photographs whose faces were not looked at because of [faceHalf]'s reason — 0 when it is [FaceHalf.ON]. */
    val facesSkippedOff: Int = 0,
    /**
     * The pass deleted every face template — a withdrawal of face consent, or a model change
     * rebasing the store. ⚠ What People draws changed even when nothing was embedded.
     */
    val facesCleared: Boolean = false,
    /**
     * A new-arrivals batch (IN-D6a) stopped at its cap with photographs still to do. ⚠⚠ Not a
     * deferral and never a reason to run again: what is left waits for the scheduled pass or
     * *Index now*, and a batch that chained into the next would be the long unasked run IN-D6
     * forbids.
     */
    val batchCapReached: Boolean = false,
    /**
     * The pass walked the library — the scheduled pass and *Index now* — rather than a list of
     * paths. ⚠⚠ Only such a pass says anything about the whole library: a path batch of three
     * photographs recorded as library coverage reads "3 of 3" over "1,290 of 1,300 aren't here".
     * Set by `MediaIndexRunner`.
     */
    val libraryWalk: Boolean = false,
) {
    val skipped: Int get() = alreadyCurrent + notOnDevice + skippedNoConsent + skippedNoBackend

    /**
     * Whether this pass may have changed what People draws — the device-local face templates, which
     * no synced row records (`ios-face-grouping.md` §17.6). ⚠ Errs toward `true`: a pass that
     * changed nothing returns `false` only when it wrote no record (empty ones included), dropped
     * and cleared nothing. ⚠ The reader that matters is [MediaIndexer.faceStoreWrites], which also
     * sees a pass that ended without a report; this is the report's own view of the same thing.
     */
    val mayHaveChangedFaces: Boolean
        get() = faceRecordsWritten > 0 || facesEmbedded > 0 || staleFacesRemoved > 0 || facesCleared
}

/**
 * Whether one pass's face half ran ([IndexPassResult.faceHalf]).
 *
 * ⚠ One reason per pass: a missing store or backend holds for the whole pass, and consent — read per
 * photograph — is the reason only where both were there. A pass that met a withdrawal partway
 * reports [NO_CONSENT] with [IndexPassResult.facesSkippedOff] saying how many photographs it cost.
 */
enum class FaceHalf(val report: String) {
    /** Every photograph the pass got to was offered to the face half. */
    ON("on"),
    /** This graph binds no face template store — face grouping is not built into this app. */
    NO_STORE("off(noStore)"),
    /** No backend serves `FACE_CLUSTERING` — no face model, or its readiness does not serve yet. */
    NO_BACKEND("off(noBackend)"),
    /** The face switch is not on for this device, for at least one photograph the pass reached. */
    NO_CONSENT("off(noConsent)"),
    /** The pass ended before it resolved its backends — a deferral, a refusal, or no pass at all. */
    NOT_ASKED("notAsked"),
}

/**
 * **One pass over the user's media, producing labels** (IN-D6).
 *
 * ⭐ **One pass, not one per capability.** IN-D8's argument is that a single image–text embedding
 * model serves classification *and* search; the indexer is shaped to match, asking the backend once
 * per file rather than walking the library once per feature. A library walk is the expensive part —
 * far more so than the model call — and doing it twice is how "add OCR" becomes "re-read every
 * photo again".
 *
 * ⚠⚠ **Consent is re-read per file, not per pass.** A pass over a photo library runs for minutes to
 * hours; a user who revokes consent mid-pass expects it to stop, and a check hoisted to the top
 * would keep grinding through a library they just switched off.
 *
 * ⚠ Nothing here knows what a model is. It asks [InferenceBackendRegistry] for a backend and the
 * backend for tags — which is what lets the same indexer run against the fake today, ONNX Runtime,
 * and the hub (IN-D1).
 *
 * ⭐ **Labels and vectors, in one visit.** The vector goes to an
 * [EmbeddingStore] — device-local, never a row, because IN-D5 refuses to sync embeddings — and it
 * is written under `SEMANTIC_IMAGE_SEARCH`'s **own** consent, not tagging's.
 *
 * ⚠⚠ **Each half stands alone: its own backend, its own consent, its own freshness** (§32). Were
 * the two to share tagging's three, it would be invisible against the fake — it supports
 * everything — and fatal on a device, where the pinned ONNX backend supports search and not
 * tagging: every photo would be skipped for want of a tagging backend.
 */
class MediaIndexer(
    private val registry: InferenceBackendRegistry,
    /**
     * This device's id, read at each use. ⚠ A provider, not a value: "Forget this device" gives the
     * running process a new id (`SyncManager.forgetThisDevice`), and the consent / pause rows this
     * reads are keyed on it — a captured id would read the forgotten device's rows until a restart.
     */
    private val deviceId: () -> String,
    /**
     * IN-D9's vocabulary: what the model is asked about, **and the rule that decides what is
     * written** (§19.3).
     *
     * ⚠⚠ Was a bare `List<String>` and every returned tag became a row — "a backend gets labels; it
     * does not get *good* labels". Now the tiered rule runs: independent thresholds at the top, and
     * at the leaves an absolute floor **before** the margin test, degrading to the parent.
     *
     * ⚠ [LabelVocabulary.Empty] — the production value until one is calibrated — emits **nothing**.
     * That is deliberate: an uncalibrated label is not better than no label, and §19.3's trap is
     * exactly a plausible constant invented somewhere else.
     */
    private val vocabulary: LabelVocabulary = LabelVocabulary.Empty,
    /**
     * What the current model claims to be — the stamp written, and the one staleness is judged against.
     *
     * ⚠⚠ A [ModelBackedBackend] answers for itself, and that is what the face store's stamp needs:
     * the backend-id fallback does not move when the weights do, so a model swap would
     * append vectors from a new space onto a store stamped for the old one.
     */
    private val currentProvenance: (InferenceBackend) -> ModelProvenance =
        { (it as? ModelBackedBackend)?.provenance ?: ModelProvenance(it.id, 1) },
    /**
     * Where vectors land. `null` indexes labels only.
     *
     * ⚠⚠ **Must be the same instance the `photosLike` op reads.** Two stores give a search that
     * finds nothing while indexing reports success, with no error on either side.
     */
    private val embeddings: EmbeddingStore? = null,
    /**
     * IN-D6's *visible* half (1e-2). `null` keeps the pass silent, which is what a fixture wants.
     *
     * ⚠ Ticked per image **including skips**, because "how far through my library are you" is the
     * question — a bar that only moved on newly-indexed photographs would sit still for the whole
     * of an incremental pass and read as stuck.
     */
    /**
     * Where face templates land. `null` indexes labels and vectors only.
     *
     * ⚠⚠ A **third half**, with its own backend, its own consent and its own freshness — §32's rule,
     * and the reason it is a separate store rather than a column on [embeddings]: face templates are
     * a different space, N per photograph rather than one, and biometric where an image vector is
     * not (`photos-people-and-pets.md` §3.1).
     */
    private val faces: FaceTemplateStore? = null,
    private val progress: IndexProgressHolder? = null,
    /**
     * Plugged in, battery not low, not in use — asked before the pass and again per image.
     *
     * ⚠⚠ Defaults to [IndexingConditions.Never]: the scheduler does not enforce the pass's
     * constraints (see [IndexingConditions]), so a wiring that forgets this must index nothing
     * rather than everything, on battery.
     */
    private val conditions: IndexingConditions = IndexingConditions.Never,
) {

    /**
     * How many times this indexer has changed the face template store — a write of a photograph's
     * record (faces, or the empty record of a photograph with none), a clear, a reconcile that
     * dropped rows. Monotonic, never reset (`ios-face-grouping.md` §17.6).
     *
     * ⚠⚠ Counted AT the write, not from the pass's report: a pass that is stopped or throws returns
     * no report, but what it wrote before that is on disk and changes People all the same.
     * [MediaIndexRunner] compares it across each pass ([MediaIndexRunner.faceChanges]).
     * ⚠ An EMPTY record counts: a store holding only no-face photographs answers People differently
     * from an empty one (indexed, nobody found — not "index first").
     */
    val faceStoreWrites: StateFlow<Long> get() = faceStoreWritesNow.asStateFlow()
    private val faceStoreWritesNow = MutableStateFlow(0L)

    private fun faceStoreChanged() {
        faceStoreWritesNow.update { it + 1 }
    }

    /** @see MediaIndexer.index's `manual` — the two bars, chosen once and re-read per image. */
    fun allowed(manual: Boolean): Boolean = allowed(if (manual) PassBar.MANUAL else PassBar.SCHEDULED)

    /** The bar [bar] names, asked of the platform's [IndexingConditions] now. */
    fun allowed(bar: PassBar): Boolean = when (bar) {
        PassBar.SCHEDULED -> conditions.allowIndexing()
        PassBar.MANUAL -> conditions.allowManualIndexing()
        PassBar.ARRIVALS -> conditions.allowArrivalIndexing()
    }

    /**
     * Whether a pass could do anything at all: some half this device can run — its own backend, and
     * its store where it has one — with that half's OWN consent on, and indexing not paused.
     *
     * ⚠⚠ Per half, never two ORs (`StartMediaIndexPassImplementation`'s argument): a backend for X
     * and consent for Y is nothing this pass may do. Asked by a new-arrivals batch (IN-D6a) BEFORE
     * the library walk, so a device with no model or no consent costs a few reads per batch, not a
     * walk of the whole tree. ⚠ Every unknown is a no — absence of consent is NO (IN-D3).
     */
    /**
     * Whether the user has **explicitly** switched off every half this pass does on this device —
     * never "no consent found", which is absence (IN-D3). What lets a holder of arrived paths drop
     * them: the user asked for none of this.
     */
    suspend fun everyHalfWithdrawn(executionContext: ExecutionContext): Boolean =
        MEDIA_INDEX_CAPABILITIES.all { executionContext.inferenceConsentWithdrawn(it, deviceId()) }

    suspend fun anyHalfMayRun(executionContext: ExecutionContext): Boolean {
        if (executionContext.isIndexingPaused(deviceId())) return false
        return MEDIA_INDEX_CAPABILITIES.any { capability ->
            val stored = when (capability) {
                InferenceCapability.SEMANTIC_IMAGE_SEARCH -> embeddings != null
                InferenceCapability.FACE_CLUSTERING -> faces != null
                else -> true
            }
            stored && registry.backendFor(capability) != null &&
                executionContext.hasInferenceConsent(capability, deviceId())
        }
    }

    /**
     * ⚠⚠ A thin wrapper for ONE reason: the bar must be cleared **however the pass ends**. A throw
     * from anywhere in the walk — a full disk rethrown by `FileDatabase`, a model that dies — would
     * otherwise unwind straight past the `finish()` at the bottom, leaving a holder that says
     * "indexing" for the life of the process, with *Index now* disabled behind it because a pass
     * appears to be running. `MediaIndexRunner` repeats this in its own `finally`; both are cheap
     * and the non-runner path (a proxy that wires no runner) has only this one.
     */
    suspend fun index(
        executionContext: ExecutionContext,
        media: List<IndexableMedia>,
        capability: InferenceCapability = InferenceCapability.CONTENT_TAGGING,
        /**
         * The user asked for this pass (§34), so it is held to [IndexingConditions.allowManualIndexing]
         * — the same power question without the "not in use" half, which they disproved by pressing
         * the button. ⚠ The pause is NOT waived: it is the user's own "not right now", and the screen
         * offers Resume rather than a second start.
         */
        manual: Boolean = false,
        /**
         * Non-null says [media] is the **whole** library walk — every live, non-trashed image — and
         * carries the second proof a removal needs. What the stores remember that is neither in the
         * walk nor confirmed live is dropped before indexing starts ([reconcile]).
         *
         * ⚠⚠ **Opt-in, and absent by default, because a partial list would be a wipe.** A caller
         * indexing one uploaded file, or a slice, that claimed the whole library would delete every
         * other photograph's templates and vectors. Only a caller holding a walk that RETURNED and
         * that its source vouched for as complete has one to pass ([MediaWalk.wholeLibrary]) —
         * `MediaIndexRunner` and `MediaIndexPassImplementation`. A walk that throws never reaches
         * here, and a partial one arrives with `null`, so neither a read error nor an unreadable
         * subtree can be read as "the library does not have these any more".
         */
        wholeLibrary: WholeLibrary? = null,
        /**
         * The bar this pass is held to, before it starts and per image. Follows [manual] unless a
         * caller names another — a new-arrivals batch passes [PassBar.ARRIVALS] (IN-D6a).
         */
        bar: PassBar = if (manual) PassBar.MANUAL else PassBar.SCHEDULED,
        /**
         * At most this many photographs are **worked on** — their bytes read and handed to a model —
         * and then the pass stops with [IndexPassResult.batchCapReached]. ⚠⚠ Only a photograph whose
         * bytes were there counts: one already current costs a stamp read, and one with no bytes and
         * no preview (relay-only, on a metered network) costs nothing, or such photographs would spend
         * every batch's budget for ever. `null` is no cap. IN-D6a's small batch.
         */
        batchCap: Int? = null,
        /**
         * Told what became of each photograph of [media] this pass reached ([PathOutcome]); a
         * photograph never reported was not reached (the cap, a deferral, a pause). How a caller
         * holding a queue of paths knows which to keep — IN-D6a's arrivals queue.
         */
        onPath: ((String, PathOutcome) -> Unit)? = null,
    ): IndexPassResult = try {
        val reconciled = wholeLibrary?.let { reconcile(executionContext, media, it) } ?: Reconciled(0, 0)
        indexPass(executionContext, media, capability, bar, batchCap, onPath).copy(
            staleFacesRemoved = reconciled.faces,
            staleVectorsRemoved = reconciled.vectors,
        )
    } finally {
        progress?.finish()
    }

    private class Reconciled(val faces: Int, val vectors: Int)

    /**
     * Drops every stored template and vector whose path the library walk does not contain
     * (`media-processing-pipeline-sweep.md` F0).
     *
     * ⚠⚠ **Why here, and why every pass.** Nothing on the delete, trash or sync-apply path touches
     * the device-local stores, so before this a deleted photograph's biometric template lived until
     * consent was withdrawn, the model changed or the realm was wiped — and kept shaping People the
     * whole time. The walk already excludes trashed paths and never sees deleted ones, so "not in
     * the walk" covers a local delete, a trash, and a delete that arrived by sync, alike.
     *
     * ⚠⚠ **Two proofs, and a path is dropped only when both hold**: it is absent
     * from a walk its source vouches for as complete, **and** a point read confirms no live file is
     * at it ([WholeLibrary.stillLive]). Neither is enough alone:
     *  - **The walk is not a snapshot.** It reads the tree row by row with no lock held across it,
     *    and a `MoveFile` unlinks its source from the old parent before it saves the destination. A
     *    walk overlapping a move can therefore miss a photograph — or a moving folder's whole
     *    subtree — whose row still exists, with no error to show for it. The store offers nothing
     *    that would make the walk one: `Database.transaction` is the store-wide lock, which a
     *    whole-library walk must not hold, and a move is not one transaction anyway. A revision
     *    compared before and after would not do it either — a move that began before the walk and
     *    ends after it changes nothing in between. The point read is what catches it: the source
     *    row outlives the unlink, and a row that still exists is spared this pass.
     *  - **The point read is not a health check.** A store that is locked, mid-wipe or not yet
     *    hydrated could answer "no such row" for everything. It is asked only about paths a walk
     *    that just read the whole tree did not find.
     * So a transient condition on either side leaves the stores alone, and the next pass decides.
     *
     * ⚠⚠ **It forgets the paths it confirmed, not "everything else".** A row added to a store after
     * the walk was taken is not in the candidate set, so it cannot be dropped for being absent from
     * a list that predates it.
     *
     * ⚠ Before the backend and conditions checks on purpose: removing what the user deleted is not
     * work that should wait for a charger, and it costs a set difference, a point read per
     * candidate and two small writes per dropped record.
     * ⚠⚠ **But not before the runner's bar check** (`MediaIndexRunner`, before the walk, §14.19):
     * a pass whose bar flipped between its launch and its walk defers there, and so skips this
     * reconcile too. That is a race only — the scheduled pass was gated on the same bar just before
     * its launch, the manual one by the op — and the trade is deliberate: with no walk there is no
     * list to reconcile against, and a walk is the one read §14.19 must not make in the background.
     * The next pass reconciles. Do not move this ahead of that check to "restore" the ordering.
     *
     * ⚠ Paths only. A photograph **replaced at the same path** keeps its old template here, because
     * neither store records what content a row was derived from — that is the content-hash key
     * (D11), not this.
     *
     * ⚠ A store that fails is logged and skipped rather than failing the pass: what it could not
     * drop is still there for the next pass, and the read-side [LivePaths] filter keeps it out of
     * every answer meanwhile.
     */
    private suspend fun reconcile(
        executionContext: ExecutionContext,
        media: List<IndexableMedia>,
        wholeLibrary: WholeLibrary,
    ): Reconciled {
        val walked = media.mapTo(HashSet(media.size)) { it.filePath }
        val faceRows = faces?.let { store ->
            dropTheDead("faces", executionContext, walked, wholeLibrary, store::remembered, store::forget)
        } ?: 0
        if (faceRows > 0) faceStoreChanged()
        val vectorRows = embeddings?.let { store ->
            dropTheDead("embeddings", executionContext, walked, wholeLibrary, store::remembered, store::forget)
        } ?: 0
        return Reconciled(faceRows, vectorRows)
    }

    private suspend fun dropTheDead(
        store: String,
        executionContext: ExecutionContext,
        walked: Set<String>,
        wholeLibrary: WholeLibrary,
        remembered: suspend () -> Set<String>,
        forget: suspend (Collection<String>) -> Removal,
    ): Int = try {
        val absent = remembered().filter { it !in walked }
        val spared =
            if (absent.isEmpty()) emptySet() else wholeLibrary.stillLive.liveAmong(executionContext, absent)
        // ⚠ Called even with nothing to drop: it is also the store's retry for bytes an earlier pass
        // removed from every read but could not overwrite.
        val removal = forget(absent.filter { it !in spared })
        // ⚠⚠ Reported from the whole result: rows that were removed count even when others were
        // refused, and bytes still owed are named even when a refusal is what stopped the batch.
        if (!removal.complete) println("MediaIndexPass -> reconcile of $store: $removal")
        removal.removed
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        println("MediaIndexPass -> reconcile of $store failed: $failure")
        0
    }

    /**
     * The pass-start half of "withdrawal deletes the templates" ([indexPass] has the argument), on
     * its own so that a pass which is refused before it starts still does it.
     *
     * ⚠⚠ [MediaIndexRunner] calls this when its `beforePass` gate is closed. That gate decides
     * whether the stores may be read and extended; it must not decide whether a withdrawn user's
     * templates are deleted.
     */
    /** ⚠ Answers whether it deleted the templates — what People draws changes then ([IndexPassResult.facesCleared]). */
    suspend fun deleteTemplatesOnWithdrawal(executionContext: ExecutionContext): Boolean {
        if (faces != null &&
            executionContext.inferenceConsentWithdrawn(InferenceCapability.FACE_CLUSTERING, deviceId()) &&
            faces.provenance() != null
        ) {
            faces.clear()
            faceStoreChanged()
            return true
        }
        return false
    }

    private suspend fun indexPass(
        executionContext: ExecutionContext,
        media: List<IndexableMedia>,
        capability: InferenceCapability,
        bar: PassBar,
        batchCap: Int?,
        onPath: ((String, PathOutcome) -> Unit)?,
    ): IndexPassResult {
        // ⚠⚠ **Withdrawal deletes the templates** (`photos-people-and-pets.md` F4). A user who
        // switches face grouping off — on this device, or for the realm on another one — has asked
        // for the biometric data to go, not merely to stop growing. The proxy cannot observe a
        // preference flip, so it happens at the two places the store would be USED: here, and
        // `faceGroups`. ⚠ Before the backend check on purpose: a device whose model was deleted
        // still holds the templates it made. ⚠ Names are NOT touched — they are the user's own
        // words, synced `MediaLabel`s, and outlive the switch (`photos-people-and-pets.md` §8). ⚠⚠
        // Only an EXPLICIT withdrawal deletes — see `inferenceConsentWithdrawn`: a read that merely
        // finds nothing is not a switch-off.
        var facesCleared = deleteTemplatesOnWithdrawal(executionContext)
        // ⚠⚠ A backend PER HALF, never "the" backend. The pinned ONNX backend supports search and not
        // tagging, so a pass that asked only for a tagging backend found none and skipped every photo
        // on every real device — while the fake, which supports everything, kept every test green.
        val tagger = registry.backendFor(capability)
        val embedder = embeddings?.let { registry.backendFor(InferenceCapability.SEMANTIC_IMAGE_SEARCH) }
        // ⚠ A third half resolves its own backend, for §32.1's reason: the one that asked for "the"
        // backend skipped every photograph on every real device.
        val faceBackend = faces?.let { registry.backendFor(InferenceCapability.FACE_CLUSTERING) }
        // ⚠⚠ **A model change REBASES the template store**. The store refuses a
        // foreign stamp, and freshness below is `visited` alone — so after an app update moves the
        // pinned model, every photograph already seen would be skipped, every new one refused, and
        // People would keep serving clusters from the old space. Templates from another model are
        // worthless in this one; the cost is a re-index, never a name (labels are per-photograph).
        if (faces != null && faceBackend != null) {
            val held = faces.provenance()
            if (held != null && held != currentProvenance(faceBackend)) {
                faces.clear()
                faceStoreChanged()
                facesCleared = true
            }
        }
        // ⚠ Pass-level; consent, read per photograph below, can still turn it to NO_CONSENT.
        var faceHalf = when {
            faces == null -> FaceHalf.NO_STORE
            faceBackend == null -> FaceHalf.NO_BACKEND
            else -> FaceHalf.ON
        }
        if (tagger == null && embedder == null && faceBackend == null) {
            return IndexPassResult(
                skippedNoBackend = media.size,
                faceHalf = faceHalf,
                facesSkippedOff = media.size,
                facesCleared = facesCleared,
            )
        }
        // ⚠ Asked before the bar starts, so a pass the device is not ready for never shows as running.
        if (!allowed(bar)) return IndexPassResult(deferred = true, facesCleared = facesCleared)

        var labelled = 0
        var current = 0
        var notOnDevice = 0
        var fromPreview = 0
        var noConsent = 0
        var embedded = 0
        var rejected = 0
        var failed = 0
        var considered = 0
        var paused = false
        var deferred = false
        var workedOn = 0
        var capReached = false
        // The photograph whose bytes were read and whose work is under way, and whether any half of
        // that work failed. ⚠ Failure is UNAVAILABLE to a queue — tried again, a bounded number of times.
        var pendingPath: String? = null
        var pendingFailed = false
        fun settlePending() {
            val path = pendingPath ?: return
            onPath?.invoke(path, if (pendingFailed) PathOutcome.UNAVAILABLE else PathOutcome.DONE)
            pendingPath = null
        }
        val firing = mutableMapOf<String, Int>()

        // ⚠ Latched per pass, because a refusal is a property of the SINK, not of the photo: a store
        // stamped by another model, or holding another width, refuses every vector this backend
        // makes. Without the latch a pass re-embeds the whole library to be refused once per photo.
        var sinkRefuses = false
        // ⚠⚠ A `Failed` is not a latch by itself: it may be this photo's (a vector the output guard
        // refused), and latching on it let one photo end the vector half of every pass at itself.
        // The half stands down at [EMBED_FAILURE_STREAK_LIMIT] in a row. ⚠ In memory and per pass:
        // nothing about it is remembered, unlike a refusal.
        var embedFailureStreak = 0
        var notAttempted = 0
        // ⚠ The same latch for the detector: a model that will not open will not open for the next
        // photograph either, and retrying it per file is a session open per image.
        var detectorFailing = false
        // ⚠ Separate from [detectorFailing], exactly as `sinkRefuses` is separate from
        // `embedderFailing`: a store refusing a foreign stamp and a model that will not run are
        // different events, and the pass report is the only place that distinction survives.
        var faceSinkRefuses = false
        var facesFound = 0
        var facesEmbedded = 0
        var facesNotHuman = 0
        var photosSearchedForFaces = 0
        var facesSkippedOff = 0
        var facesFailed = 0
        var facesRejected = 0
        var faceRecordsWritten = 0

        val stamp = tagger?.let(currentProvenance)

        progress?.start(media.size)
        var visited = 0

        for (item in media) {
            settlePending()
            visited++
            progress?.advance(visited)

            // ⚠⚠ Per file, and it BREAKS rather than skipping (IN-D6). A pause is "stop now" — a
            // user whose phone is hot does not want the remaining nine thousand images each
            // considered and rejected. ⚠ The difference is invisible in what gets written, so
            // [IndexPassResult.imagesVisited] is what makes it a testable claim rather than a
            // comment: `break` stops at the current image, `continue` would walk the whole library.
            // Consent below skips instead, because a revocation still has to let the loop finish
            // counting what it refused.
            if (executionContext.isIndexingPaused(deviceId())) {
                paused = true
                break
            }
            // ⚠⚠ Per image, and it BREAKS, for the pause's reason: unplugging or picking the device up
            // mid-pass means "stop now", not "skip the remaining nine thousand". ⚠ A manual pass is
            // re-checked too — the battery can go low mid-library — against its own bar.
            if (!allowed(bar)) {
                deferred = true
                break
            }

            // ⚠⚠ Per file, and per capability. A revocation must stop the pass, not wait for the next
            // one — and each switch gates only its own half (IN-D3): a user who agreed to search and
            // not to labels gets an index and no labels, never nothing.
            val mayTag = tagger != null && executionContext.hasInferenceConsent(capability, deviceId())
            val mayEmbed = embedder != null &&
                executionContext.hasInferenceConsent(InferenceCapability.SEMANTIC_IMAGE_SEARCH, deviceId())
            // ⚠ Its own consent, re-read per file like the others: `FACE_CLUSTERING` is a separate
            // switch (IN-D3) and a user may have exactly one of the three on.
            val faceConsent = faces != null &&
                executionContext.hasInferenceConsent(InferenceCapability.FACE_CLUSTERING, deviceId())
            // ⚠⚠ **A withdrawal mid-pass deletes too**. The pass-start clear has
            // already run by now, so without this a switch-off during a long pass would stop new
            // templates and leave every one made so far on disk until the next pass or People read.
            // ⚠ `provenance() != null` makes it once: after the clear the store has no stamp.
            // ⚠⚠ Not `!faceConsent`: A per-file read that found nothing skips
            // this photo's faces and deletes NOTHING; only an explicit switch-off deletes — a single
            // empty read must never be able to destroy the whole face store.
            if (faces != null && !faceConsent && faces.provenance() != null &&
                executionContext.inferenceConsentWithdrawn(InferenceCapability.FACE_CLUSTERING, deviceId())
            ) {
                faces.clear()
                faceStoreChanged()
                facesCleared = true
            }
            val mayFaces = faceBackend != null && faceConsent
            // ⚠ Counted BEFORE the no-consent `continue` and the `current` bookkeeping below: a photo
            // whose vector is current is otherwise reported "current" with its faces never looked at.
            if (!mayFaces) {
                facesSkippedOff++
                if (faceHalf == FaceHalf.ON) faceHalf = FaceHalf.NO_CONSENT
            }
            if (!mayTag && !mayEmbed && !mayFaces) {
                noConsent++
                // ⚠ Done for a batch: nothing it may do here, and the switch's next pass covers it.
                onPath?.invoke(item.filePath, PathOutcome.DONE)
                continue
            }

            val needsTags = mayTag && stamp != null &&
                !executionContext.isCurrent(item.filePath, capability, stamp)
            // ⚠⚠ Judged by the device-local store, never by the marker below. The marker is a
            // `MediaLabel` row and SYNCS; a vector does not. Reading the marker here made a second
            // device — or this one after a reinstall, or a user who enabled search after labels —
            // skip every photo some other pass had tagged, and so never build an index at all.
            val vectorMissing = mayEmbed && embeddings?.visited(item.filePath) == false
            val embedderFailing = embedFailureStreak >= EMBED_FAILURE_STREAK_LIMIT
            val skippedUnread = vectorMissing && (sinkRefuses || embedderFailing)
            if (skippedUnread) {
                if (sinkRefuses) rejected++ else notAttempted++
            }
            val needsVector = vectorMissing && !skippedUnread
            // ⚠⚠ The face store's OWN answer, never the synced marker — §32.3's scar. And it answers
            // true for a photograph with no faces in it, which is most of them: the negative result
            // is recorded, or every pass re-reads the whole library for ever.
            val facesMissing = mayFaces && faces?.visited(item.filePath) == false
            // ⚠⚠ Mirrors `skippedUnread` above, and for the same reason: once a latch is set,
            // every remaining photograph takes this branch, and counting them as `current` would
            // report a library as indexed that was never looked at. A pass whose detector died on
            // the first file must say so for all of them, not claim the rest were already done.
            val skippedFaces = facesMissing && (faceSinkRefuses || detectorFailing)
            if (skippedFaces) {
                if (faceSinkRefuses) facesRejected++ else facesFailed++
            }
            val needsFaces = facesMissing && !skippedFaces
            if (!needsTags && !needsVector && !needsFaces) {
                if (!skippedUnread && !skippedFaces) {
                    current++
                    onPath?.invoke(item.filePath, PathOutcome.DONE)
                } else {
                    // ⚠ A latched half skipped it: not done, retried a bounded number of times.
                    onPath?.invoke(item.filePath, PathOutcome.UNAVAILABLE)
                }
                continue
            }
            // ⚠⚠ IN-D6a's cap, asked BEFORE the byte read and it BREAKS: the rest is the next batch's,
            // the scheduled pass's or *Index now*'s — never a chained run. Spent below, only once the
            // bytes turned out to be here.
            if (batchCap != null && workedOn >= batchCap) {
                capReached = true
                break
            }

            // ⚠ The original when it is here; otherwise a preview — a photograph stored
            // only on the relay is still worth indexing, and its preview is far cheaper than it.
            val original = item.bytes()
            val preview = if (original == null) item.preview?.invoke() else null
            val bytes = original ?: preview?.bytes
            // ⚠⚠ No explicit orientation for either: an original carries its own EXIF, and a preview
            // is upright (`PreviewBytes`). `null` tells the face backend to read the bytes' own tag.
            val orientation: Int? = null
            if (preview != null) fromPreview++
            if (bytes == null) {
                // ⚠ Counted, not silently dropped: "indexed 0 of 4 000" with no reason is the
                // report that gets filed as "the feature does nothing".
                notOnDevice++
                onPath?.invoke(item.filePath, PathOutcome.UNAVAILABLE)
                continue
            }
            workedOn++
            // ⚠⚠ Settled when the photograph's work is over — at the next photograph or after the
            // loop — never here, before it: a failure below keeps it queued. ⚠⚠ And a half already
            // latched off for this photograph (`skippedFaces`, `skippedUnread`) is a failure from the
            // start: the other half running on it does not make it done — dropped as DONE, its faces
            // (or vector) would wait for a library pass.
            pendingPath = item.filePath
            pendingFailed = skippedFaces || skippedUnread

            // ⚠⚠ The SAME visit, and this is IN-D8's whole argument made concrete: one image–text
            // model serves tagging and search, and the library walk — not the model call — is the
            // expensive part. Embedding in a second pass would re-read every photo to produce data
            // the first pass already had in hand.
            if (needsVector && embeddings != null && embedder != null) {
                when (val outcome = embedder.embedImageOutcome(bytes)) {
                    // ⚠⚠ The ONLY "no vector" remembered against the photo. A model that could not
                    // run — vanished mid-pass, would not open, threw — says nothing about the file,
                    // and remembering it would keep the photo out of the index for good.
                    // ⚠ Leaves the streak alone: a decoder refusal is no evidence about the model,
                    // for or against.
                    ImageEmbeddingOutcome.Unreadable -> embeddings.markVisited(item.filePath)
                    ImageEmbeddingOutcome.Failed -> {
                        failed++
                        pendingFailed = true
                        embedFailureStreak++
                        if (embedFailureStreak == EMBED_FAILURE_STREAK_LIMIT) {
                            println(
                                "MediaIndexPass -> vector half STOOD DOWN at ${item.filePath}: " +
                                    "$EMBED_FAILURE_STREAK_LIMIT embeds failed in a row"
                            )
                        }
                    }
                    is ImageEmbeddingOutcome.Embedded -> {
                        // ⚠ The model made a vector, so it works: whatever failed before this was
                        // those photos'. Reset before the sink is asked — a refusal is the sink's.
                        embedFailureStreak = 0
                        val embedding = outcome.embedding
                        if (embeddings.put(item.filePath, embedding.vector, embedding.producedBy)) {
                            embedded++
                        } else {
                            rejected++
                            sinkRefuses = true
                            pendingFailed = true
                        }
                    }
                }
            }

            // ⚠⚠ The same bytes again — IN-D8's argument extended to a third consumer. The library
            // walk is the expensive part; a separate face pass would re-read every photograph to get
            // what this one already has in hand.
            if (needsFaces && faces != null && faceBackend != null) {
                photosSearchedForFaces++
                // ⚠⚠ **ONE stamp for every record this pass writes** — a batch of faces, a
                // photograph with none, and an undecodable one alike. `DefaultFaceTemplateStore`
                // adopts a stamp on its FIRST write and refuses anything else for the rest of its
                // life, while an empty record carries no embedding to take a stamp from. Using
                // `ImageEmbedding.producedBy` for real batches and something else for empty ones
                // would make the store's identity depend on photo ORDER: a library whose first faces
                // appear after fifty landscapes would adopt the stamp from the landscapes and then
                // refuse every real vector for good.
                //
                // ⚠ So `currentProvenance` MUST return the face model's own provenance, or the
                // store's stamp is a label that does not move when the weights do. The face backend
                // is a [ModelBackedBackend] for exactly that reason.
                val faceStamp = currentProvenance(faceBackend)
                when (val detection = faceBackend.detectFaces(bytes, orientation)) {
                    // ⚠⚠ An empty list is a FACT and is recorded as one: this photograph has no faces,
                    // and no future pass needs to open it again.
                    is FaceDetectionOutcome.Detected -> {
                        facesFound += detection.faces.size
                        // ⚠⚠ **All of them, or none of them.** `embedFace` documents `null` as "the
                        // model could not run" — never "not a face" — because whether these bytes
                        // decode was already settled by `detectFaces`. So dropping the failures and
                        // storing the rest would mark the photograph DONE while one of its faces was
                        // silently lost, and `visited` would keep every later pass away from it. That
                        // is precisely the transient failure `Failed` below refuses to remember,
                        // arriving one call later.
                        val stored = ArrayList<StoredFace>(detection.faces.size)
                        var embedderCouldNotRun = false
                        for (face in detection.faces) {
                            val embedding = faceBackend.embedFace(bytes, face, orientation)
                            if (embedding == null) {
                                embedderCouldNotRun = true
                                break
                            }
                            // ⚠ Left out, and the photograph is still DONE: "not a face" is a fact
                            // about this face, unlike `null` above (§12.15).
                            if (embedding.notAFace) {
                                facesNotHuman++
                                continue
                            }
                            stored += StoredFace(face.box, embedding.vector)
                        }
                        if (embedderCouldNotRun) {
                            // ⚠ Nothing recorded, and latched: a model that will not run for this
                            // face will not run for the next photograph's either.
                            facesFailed++
                            pendingFailed = true
                            // ⚠ Diagnostic: the latch silences the face half for the rest of the
                            // pass, and without this line nothing says which photograph tripped it
                            // or why.
                            println("MediaIndexPass -> face half LATCHED at ${item.filePath}: embedFace returned null")
                            detectorFailing = true
                        } else if (faces.put(item.filePath, stored, faceStamp)) {
                            faceStoreChanged()
                            faceRecordsWritten++
                            facesEmbedded += stored.size
                        } else {
                            // ⚠ A refused write is the store's stamp or width disagreeing, which will
                            // disagree for every later photograph too — latch, do not re-ask per file.
                            facesRejected++
                            faceSinkRefuses = true
                            pendingFailed = true
                        }
                    }

                    // ⚠ Remembered against the file: these bytes are not an image this device decodes.
                    // ⚠⚠ The refusal is READ here too. A store already carrying a foreign stamp
                    // refuses this exactly as it refuses a real batch, and ignoring it meant a pass
                    // over undecodable files re-ran the detector for every one of them, reported no
                    // rejection, and never latched the incompatibility the `Detected` branch does.
                    FaceDetectionOutcome.Unreadable ->
                        if (faces.put(item.filePath, emptyList(), faceStamp)) {
                            faceStoreChanged()
                            faceRecordsWritten++
                        } else {
                            facesRejected++
                            faceSinkRefuses = true
                        }

                    // ⚠⚠ NOT remembered. A detector that could not run says nothing about the
                    // photograph, and recording it would keep the photo out of People for good.
                    FaceDetectionOutcome.Failed -> {
                        facesFailed++
                        pendingFailed = true
                        detectorFailing = true
                        println("MediaIndexPass -> face half LATCHED at ${item.filePath}: detectFaces Failed")
                    }
                }
            }

            if (!needsTags || tagger == null || stamp == null) continue

            // ⚠⚠ The marker is written even when the model found nothing, and that is the point:
            // otherwise a photo the model has no opinion about is re-read on every single pass,
            // forever. "It ran" and "it found something" are different facts.
            executionContext.markIndexed(item.filePath, capability, stamp)

            // ⚠ The model is asked about the vocabulary's terms; the vocabulary's RULE then decides
            // which of the answers is worth writing. Asking and selecting are separate steps because
            // a zero-shot score is free per label (IN-D9: one dot product) while a row is not.
            considered++
            selectLabels(tagger.tagImage(bytes, vocabulary.terms), vocabulary).forEach { tag ->
                // §7.7: counted per image, so the rate is "on how many photographs did this fire",
                // never "how many rows did it write".
                firing[tag.label] = (firing[tag.label] ?: 0) + 1
                executionContext.saveData(
                    Entity.create<MediaLabel>(
                        context = executionContext.context,
                        composite = MediaLabel.create(
                            context = executionContext.context,
                            filePath = item.filePath,
                            capability = capability,
                            label = tag.label,
                            confidencePerMille = (tag.confidence * 1_000).toInt().coerceIn(0, 1_000),
                            producedBy = stamp,
                        ),
                    ),
                )
                labelled++
            }
        }

        settlePending()

        // ⚠ Cleared by [index]'s `finally`, on every exit from the loop AND on a throw — see there.

        return IndexPassResult(
            labelled = labelled,
            alreadyCurrent = current,
            notOnDevice = notOnDevice,
            fromPreview = fromPreview,
            skippedNoConsent = noConsent,
            embedded = embedded,
            embeddingsRejected = rejected,
            embeddingsFailed = failed,
            embeddingsNotAttempted = notAttempted,
            embeddingStoodDown = embedFailureStreak >= EMBED_FAILURE_STREAK_LIMIT,
            consideredImages = considered,
            labelFiringCounts = firing,
            paused = paused,
            imagesVisited = visited,
            facesFound = facesFound,
            facesEmbedded = facesEmbedded,
            facesNotHuman = facesNotHuman,
            photosSearchedForFaces = photosSearchedForFaces,
            deferred = deferred,
            faceHalf = faceHalf,
            facesSkippedOff = facesSkippedOff,
            facesCleared = facesCleared,
            facesFailed = facesFailed,
            facesRejected = facesRejected,
            faceRecordsWritten = faceRecordsWritten,
            batchCapReached = capReached,
        )
    }
}

/** What became of one photograph a pass reached (`MediaIndexer.index`'s `onPath`). */
enum class PathOutcome {
    /** Indexed, already current, or nothing this device may do for it: no batch needs it again. */
    DONE,

    /**
     * Not indexed now, and worth asking again a few times: neither its bytes nor a preview are on
     * this device, a half failed on it, or — for a path batch — it is not (yet) a live image row.
     */
    UNAVAILABLE,
}

/**
 * Which bar a pass is held to (§32.9, §34.2, IN-D6a) — asked of [IndexingConditions] before the pass
 * and per image.
 */
enum class PassBar {
    /** The 12 h maintenance pass: charging, battery not low, not in use. */
    SCHEDULED,

    /** *Index now*: battery not low; unknown is allowed (the user asked). */
    MANUAL,

    /**
     * A new-arrivals batch (IN-D6a): battery not low, and **unknown is NO** — nobody asked. ⚠⚠ Its own
     * kind, so the batch's lighter bar never loosens [SCHEDULED]'s.
     */
    ARRIVALS,
}

/**
 * How many image embeds may fail **in a row** — no vector made in between — before a pass stops
 * asking for vectors.
 *
 * ⚠⚠ What it trades. A `Failed` is either the model's (will not open, throws) or this photo's (a
 * vector the output guard refused), and the indexer cannot tell which from one.
 *  - **Too low** and bad photos block the library: at 1, a single photo the model fails on every
 *    time ends the vector half of every pass at itself, and nothing after it in the walk is ever
 *    embedded.
 *  - **Too high** and a broken model is paid for that many times per pass: each wasted ask reads a
 *    photo's bytes and, on ONNX, is a ~100 MB session open that fails.
 * Three: the scheduled pass runs every 12 h and *Index now* on a press, so three failed opens per
 * pass is noise, while two bad photos do not look like a dead model.
 *
 * ⚠⚠ **Consecutive ASKS, not consecutive photos.** A photo that already has a vector is skipped
 * without being asked, so it does not reset the streak. Once a library is indexed, the only photos
 * asked are the ones that failed before and the new ones — so this many photos that fail every time,
 * with no un-embedded good photo walked between them, stand the half down and block what follows,
 * exactly as a broken model does. The two are indistinguishable without remembering
 * failures per photo, which is stored state this does not have (`inference-service.md` §32.3).
 */
internal const val EMBED_FAILURE_STREAK_LIMIT = 3

/** Records that [filePath] was visited by [stamp] — see the call site for why absence matters. */
internal fun ExecutionContext.markIndexed(
    filePath: String,
    capability: InferenceCapability,
    stamp: ModelProvenance,
) {
    saveData(
        Entity.create<MediaLabel>(
            context = context,
            composite = MediaLabel.create(
                context = context,
                filePath = filePath,
                capability = capability,
                label = INDEX_MARKER_LABEL,
                confidencePerMille = 0,
                producedBy = stamp,
            ),
        ),
    )
}

/**
 * Whether this file already carries a label from [stamp] or newer.
 *
 * ⚠⚠ This is the whole reason `producedBy` exists. Without it a second pass re-runs the model over
 * a library it has already read — hours of battery to produce rows that are byte-identical.
 *
 * ⚠ Judged on the pass's **marker** row, so a photo the model had no opinion about still counts as
 * indexed. Judging on "has any label" would re-read every unremarkable photo on every pass, forever.
 */
internal fun ExecutionContext.isCurrent(
    filePath: String,
    capability: InferenceCapability,
    stamp: ModelProvenance,
): Boolean {
    // ⚠ Answered by the pass's own marker row rather than by counting labels: a cheap count is
    // `core-realm-data.md`'s concern, and "how many labels does this file have" is the wrong
    // question anyway — a model that found nothing still ran.
    val probe = DataId.StringDataId.create(
        context = context,
        type = MediaLabel.ID,
        keyValue = MediaLabel.labelKey(filePath, capability, INDEX_MARKER_LABEL),
    )
    val stored = database.getData(probe) ?: return false
    val recorded = when (stored) {
        is Entity<*> -> runCatching { stored.getFieldValue<TString>(MediaLabel.Fields.PRODUCED_BY) }.getOrNull()
        is Composite -> runCatching { stored.getField<TString>(MediaLabel.Fields.PRODUCED_BY) }.getOrNull()
        else -> null
    }?.get()
    val parsed = ModelProvenance.parse(recorded) ?: return false
    return !parsed.isStaleAgainst(stamp)
}

