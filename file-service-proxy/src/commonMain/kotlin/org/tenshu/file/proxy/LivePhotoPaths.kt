package org.tenshu.file.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.isTrashedPath

/**
 * The subset of [paths] that still name a file the user has: a `File` row exists at that path **and**
 * it is not in the trash (`media-processing-pipeline-sweep.md` F0, P0f-a).
 *
 * ⚠⚠ **What derived data must be filtered by before it is used.** A face template or an image vector
 * is keyed by path and lives in a device-local store that nothing on the delete, trash or sync-apply
 * path touches, so a store row outlives its photograph until the next index pass reconciles it. A
 * read that clusters or ranks those rows asks this first, which is what makes a delete hold on the
 * very next read rather than at the next idle-and-charging pass.
 *
 * ⚠ The trash rule is [collectImagePaths]'s rule, deliberately: a trashed photo is not indexed, so it
 * must not be served from what an earlier pass indexed either.
 *
 * ⚠ Lives here for the reason [collectImagePaths] does — "does this file exist" is a file-service
 * question, and `:inference-service-proxy` depends on no file service. Control composes the two (the
 * `LivePaths` seam). ⚠ In-process point lookups, one per path: no reply here grows with the realm,
 * and the caller hands paths over in bounded chunks.
 *
 * ⚠⚠ **A store that cannot answer must not answer "dead"** (P0f-b PR-1). A read filter that took
 * "no row" from a store that is mid-wipe or unreadable would hide every face in People; a reconcile
 * would erase them. So the answer is vouched for by the realm root, which `initFileService` creates
 * and nothing deletes: if `/` does not read, this throws [LivenessUnanswered] instead of returning
 * an empty set. The callers then do the safe thing for each: `faceGroups` and `photosLike` fail
 * visibly (never an empty success, never an unfiltered answer), and the index pass's reconcile
 * drops nothing that pass. ⚠ Asked once per call, after the per-path reads, so a store that goes
 * away mid-chunk is caught too.
 */
fun ExecutionContext.livePathsAmong(paths: Collection<String>): Set<String> {
    if (paths.isEmpty()) return emptySet()
    val live = paths.filterTo(LinkedHashSet()) { path ->
        !isTrashedPath(path) &&
            database.contains(DataId.StringDataId.create(context = context, type = File.ID, keyValue = path))
    }
    if (!database.contains(DataId.StringDataId.create(context = context, type = File.ID, keyValue = "/"))) {
        throw LivenessUnanswered("the realm root does not read, so no path's absence can be trusted")
    }
    return live
}

/**
 * [livePathsAmong] could not tell live from dead — the realm's own root does not read.
 *
 * ⚠ Deliberately untyped (no schema error, so no wire or baseline change): it is an unexpected
 * store state, not a refusal a screen names. It reaches a client as an ordinary failed operation,
 * which Photos renders as "couldn't load", never as "nobody here" or "nothing matched".
 */
class LivenessUnanswered(message: String) : IllegalStateException(message)
