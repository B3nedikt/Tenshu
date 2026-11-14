package app.tenshu.control.di

import org.tenshu.core.TByteArray
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.file.proxy.MeteredNetworkSignal
import org.tenshu.file.proxy.livePathsAmong
import org.tenshu.file.proxy.previewForIndex
import org.tenshu.file.proxy.walkImageLibrary
import org.tenshu.inference.proxy.IndexableMedia
import org.tenshu.inference.proxy.LivePaths
import org.tenshu.inference.proxy.MediaSource
import org.tenshu.inference.proxy.MediaWalk
import org.tenshu.inference.proxy.PreviewBytes

/**
 * The two places `:inference-service-proxy` meets the file tree, as named functions rather than
 * lambdas inside `ProxyModule` — so a test builds **the production seams**, not a copy of them
 * (P0f-b PR-1, `media-processing-pipeline-sweep.md` §6.4.1).
 *
 * ⚠⚠ Composition across services is CONTROL's job, not a service's: `:inference-service-proxy`
 * depends on no other service, the file-tree knowledge lives in `:file-service-proxy`, and the two
 * meet here.
 */

/**
 * Which stored paths still name a live, untrashed `File` — the read filter `faceGroups` and
 * `photosLike` apply, and the reconcile's second proof (P0f-a's mechanism, wired by P0f-b PR-1).
 *
 * ⚠⚠ **One predicate, one realm.** It answers against the op's own `ExecutionContext`, and every
 * session a proxy mints shares that proxy's one `Database` and base context (`ClientRegistry`), so
 * the People read from Photos, a Mia search and the scheduled pass's walk all see the same personal
 * `File` rows. A shared realm's files are `SharedFile` rows under `"$realmId::"` keys, never
 * `File` rows at a path, and the walk never reaches them either — so the stores hold only personal
 * paths, and this asks only about personal paths. `MediaLibrarySeamsTest` pins it across sessions.
 *
 * ⚠⚠ **A store that cannot answer throws, never "all dead"** ([org.tenshu.file.proxy.LivenessUnanswered]):
 * the reads then fail visibly and the reconcile drops nothing.
 */
val fileLivePaths: LivePaths = LivePaths { executionContext, paths -> executionContext.livePathsAmong(paths) }

/**
 * Control's library source for the index pass (IN-D6), and **the only list a pass may reconcile
 * against** (P0f-a's B1, wired by P0f-b PR-1).
 *
 * ⚠⚠ [MediaSource.walk] answers [MediaWalk.complete] only when [walkImageLibrary] says it read the
 * whole tree, and then always with [livePaths] as the second proof per absent path — a walk is not
 * a snapshot (a `MoveFile` unlinks before it saves). Anything less is [MediaWalk.partial], which
 * reconciles nothing. Before PR-1 this source overrode only `media()`, so `walk` took its partial
 * default and no pass in production ever erased a deleted photograph's template.
 *
 * ⚠ The byte read stays LAZY: `MediaIndexer` checks the staleness stamp before calling it, so a
 * re-run over an already-indexed library costs a stamp read per photo rather than a file read.
 *
 * @param metered contributed per platform; `null` means "cannot tell", which allows the pull — the
 *   documented default for this seam (desktop is unmetered by construction).
 */
fun controlMediaSource(
    files: FileDatabase,
    metered: MeteredNetworkSignal?,
    livePaths: LivePaths,
): MediaSource = object : MediaSource {

    override suspend fun media(executionContext: ExecutionContext): List<IndexableMedia> =
        walk(executionContext).media

    override suspend fun walk(executionContext: ExecutionContext): MediaWalk {
        val library = executionContext.walkImageLibrary()
        val media = library.paths.map { path -> executionContext.indexable(files, metered, path) }
        return if (library.complete) MediaWalk.complete(media, livePaths) else MediaWalk.partial(media)
    }

    /**
     * ⚠⚠ Point reads only (IN-D6a's batch): one row lookup per path through [livePaths], never the
     * tree. A path that is no image, trashed or not a live `File` row any more is not returned.
     */
    override suspend fun mediaAt(executionContext: ExecutionContext, paths: Collection<String>): List<IndexableMedia> {
        val images = paths.filter { fileCategoryForName(it.substringAfterLast('/')) == FileCategory.Image }
        val live = livePaths.live(executionContext, images)
        return images.filter { it in live }.map { path -> executionContext.indexable(files, metered, path) }
    }
}

private fun ExecutionContext.indexable(
    files: FileDatabase,
    metered: MeteredNetworkSignal?,
    path: String,
): IndexableMedia = IndexableMedia(
    filePath = path,
    // ⚠ Stat first: `readBytes` on a missing file logs a warning on Android, and a photo stored
    // only on the relay is the COMMON case on a device that does not hold full copies — 1,290
    // warnings per pass on the maintainer's phone.
    // ⚠ Typed local: a bare `?.get()` here resolves against an unrelated operator.
    bytes = {
        if (files.fileSize(path) == null) null else (files.readBytes(path) as TByteArray?)?.get()
    },
    // 2026-09-27: a photo whose original is not here is indexed from its preview.
    // ⚠ A pull is a network fetch, so never on a metered connection.
    preview = {
        previewForIndex(
            fileDatabase = files,
            filePath = path,
            mayPull = metered?.isMetered() != true,
        )?.let { PreviewBytes(it.bytes) }
    },
)
