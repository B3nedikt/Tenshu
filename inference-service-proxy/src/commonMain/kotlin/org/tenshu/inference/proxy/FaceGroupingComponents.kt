package org.tenshu.inference.proxy

/**
 * What the proxy needs to answer `faceGroups` — the template store, and the numbers used to group
 * what is in it (IN-6, F3).
 *
 * ⚠⚠ **The two travel together on purpose, and that is the whole reason this type exists.** A
 * [clustering] parameter with a default would let a DI site wire the store, forget the numbers, and
 * ship a People screen that refuses every call — the kind of seam that is *technically* loud but
 * that nobody reads until a user reports an empty grid. Requiring both makes "which threshold is
 * this build using?" a question the wiring answers out loud.
 *
 * ⚠ [clustering] is expected to be [FaceClustering.Uncalibrated] until F2 records a measured
 * threshold and `minFaces`. That is a legitimate value, not a placeholder to be filled with a guess:
 * `photos-people-and-pets.md` §4 refuses to invent one, and the op turns it into a named refusal
 * rather than an empty result.
 */
data class FaceGroupingComponents(
    val store: FaceTemplateStore,
    val clustering: FaceClustering,
    /**
     * Which stored paths still name a live, untrashed file — `faceGroups` clusters only those
     * (P0f-a, `media-processing-pipeline-sweep.md` F0).
     *
     * ⚠⚠ **`null` is the gap, not a safe default.** Without it a deleted photograph's template keeps
     * shaping People until the next index pass reconciles the store, and a pass needs idle and
     * charging. It is nullable only because this module cannot build one (it depends on no file
     * service); Control supplies `livePathsAmong` from `:file-service-proxy` (`fileLivePaths`,
     * P0f-b PR-1). ⚠ The factory hands the same predicate to `photosLike`.
     */
    val livePaths: LivePaths? = null,
)
