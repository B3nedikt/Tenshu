package org.tenshu.inference.proxy

import org.tenshu.core.common.proxy.ExecutionContext

/**
 * Which stored paths still name a photograph the user has — the read-side half of removing the
 * rows of deleted photographs (`media-processing-pipeline-sweep.md` F0).
 *
 * ⚠⚠ **Why a read must ask.** [FaceTemplateStore] and [EmbeddingStore] are keyed by path and device-
 * local, and nothing on the delete, trash or sync-apply path touches them. [MediaIndexer] reconciles
 * them at the start of every pass, but a pass needs idle and charging, so between a delete and the
 * next pass a deleted photograph's biometric template would keep shaping People. A read that
 * clusters or ranks stored rows therefore drops every path this does not return.
 *
 * ⚠⚠ **A seam, because this module cannot answer it.** "Does this file exist, and is it in the
 * trash" is a file-service question, and `:inference-service-proxy` depends on no file service
 * (`inference-service.md` §26.4). Control composes the two, exactly as it does for [MediaSource]; the
 * file-service half is `org.tenshu.file.proxy.livePathsAmong`.
 *
 * ⚠ Called with at most [LIVE_PATHS_CHUNK] paths at a time ([liveAmong]), so an implementation that
 * crosses a process boundary never builds a reply that grows with the library, as one
 * transaction over all of it would.
 */
fun interface LivePaths {

    /** The subset of [paths] whose file exists in the realm and is not trashed. */
    suspend fun live(executionContext: ExecutionContext, paths: List<String>): Set<String>
}

/** The `path oneOf` chunk size `photos-app.md` §39 settled on for realm reads. */
internal const val LIVE_PATHS_CHUNK = 200

/** [LivePaths.live] over any number of [paths], [LIVE_PATHS_CHUNK] at a time. */
internal suspend fun LivePaths.liveAmong(
    executionContext: ExecutionContext,
    paths: Collection<String>,
): Set<String> {
    val out = HashSet<String>()
    paths.distinct().chunked(LIVE_PATHS_CHUNK).forEach { chunk -> out += live(executionContext, chunk) }
    return out
}
