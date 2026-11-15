package org.tenshu.inference.proxy

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.inference.AvailableModels
import org.tenshu.inference.ClassifyIntent
import org.tenshu.inference.DeleteModel
import org.tenshu.inference.DownloadModel
import org.tenshu.inference.DownloadProgress
import org.tenshu.inference.IndexProgress
import org.tenshu.inference.InstalledModels
import org.tenshu.inference.MediaIndexPass
import org.tenshu.inference.PhotosLike
import org.tenshu.inference.StartMediaIndexPass
import org.tenshu.inference.proxy.catalog.ModelDeliveryComponents

/**
 * The inference service's proxy-side operations (IN-4b) — the same shape as
 * `fileServiceProxyImplementationFactory`, one service later.
 *
 * ⚠⚠ [embeddings] is device-local state the op **reads** and `MediaIndexer` **writes**, so both must
 * be handed the *same instance*. Two stores would give a search that finds nothing while indexing
 * reports success — and no error anywhere, because each half works perfectly on its own.
 */
fun inferenceServiceProxyImplementationFactory(
    id: String,
    registry: InferenceBackendRegistry,
    embeddings: EmbeddingStore,
    deviceId: String,
    /**
     * IN-D6 (1e): the indexer and where it finds images.
     *
     * ⚠ Nullable together — a proxy that wires no media source has no index pass, rather than one
     * that runs and finds nothing. ⚠⚠ The [MediaIndexer] MUST be built with the same
     * [EmbeddingStore] instance passed above: two stores give a search that finds nothing while
     * indexing reports success, with no error on either side.
     */
    indexer: MediaIndexer? = null,
    mediaSource: MediaSource? = null,
    /**
     * §34: the one thing that may run a pass, so the scheduled and the manual start cannot overlap.
     *
     * ⚠ Nullable with the pair above: a proxy that wires no runner keeps the scheduled pass (which
     * then runs the indexer directly) and simply has no *Index now*.
     */
    runner: MediaIndexRunner? = null,
    /**
     * Where *Index now* runs (`control-background-work-off-fgs.md` §5.6.1). Null — desktop, iOS, tests —
     * starts the pass on [runner] in-process, as before. Android hands it to a WorkManager
     * long-running worker that shows its own progress notification, instead of Control's
     * `ProxyService` holding the `dataSync` service for it.
     */
    manualIndexLauncher: ManualIndexLauncher? = null,
    /**
     * IN-2c's store, catalog source and downloader (§23).
     *
     * ⚠ Nullable so a proxy that wires no model delivery — a test fixture, or a platform with no
     * runtime — simply has no model operations, rather than four that fail in a novel way.
     */
    delivery: ModelDeliveryComponents? = null,
    /**
     * IN-D6 (1e-3): where a running pass reports its position.
     *
     * ⚠⚠ Nullable with **no default instance**, deliberately. A default-constructed holder is one
     * nothing ever writes to, so the stream would answer *"not indexing"* forever — confidently,
     * on a device that is indexing. A proxy that wires no holder has no progress op at all, which
     * fails where it can be seen. ⚠ It must be the SAME instance handed to [indexer]'s
     * `MediaIndexer`, for the reason [embeddings] gives above.
     */
    indexProgress: IndexProgressHolder? = null,
    /**
     * IN-6 F3: the face template store and the numbers used to group it.
     *
     * ⚠ Nullable, so a proxy that wires no face support has **no** `faceGroups` op rather than one
     * that answers "nobody" — an absent operation fails where it can be seen, an empty person list
     * does not. ⚠⚠ Its store MUST be the same instance handed to [indexer]'s `MediaIndexer`, for
     * the reason [embeddings] gives above: two stores would give a People screen that stays empty
     * while indexing reports faces found, with no error on either side.
     */
    faceGrouping: FaceGroupingComponents? = null,
    /**
     * P0f-a: which stored paths still name a live, untrashed file, so `photosLike` never ranks a
     * deleted photograph's vector. ⚠ Defaults to the face wiring's predicate — one realm, one answer
     * — so the DI site supplies it once.
     */
    livePaths: LivePaths? = faceGrouping?.livePaths,
    /**
     * `photosLike`'s and `faceGroups`' check that the stores belong to the realm this device is on
     * now: every op that READS a store asks it first. ⚠ `null`
     * wherever the stores are cleared by the realm-wipe hook of the one process that holds them.
     */
    storeGate: (suspend () -> Boolean)? = null,
): OperationImplementation? {
    return when (id) {
        PhotosLike.ID -> PhotosLikeImplementation(registry, embeddings, deviceId, livePaths, storeGate)
        // IN-6 F3. ⚠ No `embeddings`: face templates live in their own store, and the two spaces
        // are unrelated — a cosine between an image embedding and a face template is computable
        // and meaningless.
        org.tenshu.inference.FaceGroups.ID -> faceGrouping?.let {
            FaceGroupsImplementation(registry, it.store, it.clustering, deviceId, it.livePaths, storeGate)
        }
        // IN-6 F3b. ⚠ NOT gated on `faceGrouping`, unlike the read above: naming needs no
        // templates and no model — it writes a string to a path. A device whose store was wiped,
        // or which never had a backend, must still be able to SHOW names its peers typed, and
        // refusing the write there would make a synced name un-correctable from that device.
        org.tenshu.inference.NameFaceGroup.ID -> NameFaceGroupImplementation(deviceId)
        // IN-6 F6c name suggestions — over the SAME template store, which holds the rejections so
        // they leave by every exit the templates do (`FaceRejections`). No grouping, no rejections.
        org.tenshu.inference.RejectFaceSuggestion.ID -> faceGrouping?.let {
            RejectFaceSuggestionImplementation(it.store, deviceId, storeGate)
        }
        org.tenshu.inference.FaceSuggestionRejections.ID -> faceGrouping?.let {
            FaceSuggestionRejectionsImplementation(it.store, deviceId, storeGate)
        }
        // MQ-5. ⚠ No `embeddings`: choosing which question was asked reads no vectors and no
        // library — the only user content it sees is the sentence itself.
        ClassifyIntent.ID -> ClassifyIntentImplementation(registry, deviceId)
        // IN-2c. ⚠⚠ The same `delivery` the DI site hands the backend locators, so the screen's
        // "installed" and the backend's "which weights do I load" are one store. Two would give a
        // screen that reports a model the backend cannot find.
        AvailableModels.ID -> delivery?.let { AvailableModelsImplementation(it.catalogSource) }
        InstalledModels.ID -> delivery?.let { InstalledModelsImplementation(it.store) }
        DownloadModel.ID -> delivery?.let { DownloadModelImplementation(it.downloader) }
        DeleteModel.ID -> delivery?.let { DeleteModelImplementation(it.store, it.storeChanges, it.downloader) }
        // 1c-3. ⚠ Needs BOTH the holder and the downloader: the holder carries the ticks, and
        // `isDownloading` is what tells "the entry has not been registered YET" from "it never
        // will" — the two look identical (an absent entry) and need opposite handling.
        // ⚠⚠ Through the runner when there is one, so a scheduled pass JOINS a manual one instead
        // of walking the library beside it (§34). Four passes in one minute were measured on a real
        // phone before the runner existed.
        MediaIndexPass.ID ->
            if (indexer != null && mediaSource != null) {
                MediaIndexPassImplementation(indexer, mediaSource, runner = runner)
            } else {
                null
            }
        // §34. ⚠ Needs the runner AND the indexer: the runner starts the work, the indexer answers
        // whether this device may do it at all, and the op turns that into a reason a screen shows.
        StartMediaIndexPass.ID ->
            if (runner != null && indexer != null) {
                StartMediaIndexPassImplementation(runner, indexer, registry, deviceId, manualIndexLauncher)
            } else {
                null
            }
        DownloadProgress.ID -> delivery?.let {
            ModelDownloadProgressImplementation(it.progress, it.downloader)
        }
        // 1e-3. ⚠ The holder ALONE, no `isDownloading` twin: a download registers its entry late,
        // so an absent one is ambiguous there; `MediaIndexer` calls `start()` first, so an absent
        // entry here unambiguously means no pass is running and is reported as such.
        IndexProgress.ID -> indexProgress?.let { MediaIndexProgressImplementation(it) }
        else -> null
    }
}
