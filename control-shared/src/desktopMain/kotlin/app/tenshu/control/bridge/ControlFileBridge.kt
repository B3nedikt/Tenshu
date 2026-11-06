package app.tenshu.control.bridge

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
import org.tenshu.core.NotFoundError
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.core.ListBuilder
import org.tenshu.core.common.proxy.BridgeException
import org.tenshu.core.common.proxy.NativeBridgeServer
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.equalTo
import org.tenshu.core.file.Category
import org.tenshu.core.file.ContentHash
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.ContentModifiedAt
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.Folder
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.contentModifiedAtOrCreated
import org.tenshu.core.file.Size
import org.tenshu.core.file.Thumbnail
import org.tenshu.core.file.ThumbnailLarge
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.file.files
import org.tenshu.core.file.isPdf
import org.tenshu.core.file.path
import org.tenshu.core.file.system.AlreadyExistsError
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.file.system.replaceFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.fold
import org.tenshu.core.get
import org.tenshu.core.relation.eager
import org.tenshu.core.resolve
import org.tenshu.core.ifEmptyError
import org.tenshu.file.client.fileService
import org.tenshu.sync.client.syncService
import org.tenshu.sync.closeContentStream
import org.tenshu.sync.openContentStream
import org.tenshu.sync.readContentRange
import java.io.File as JavaFile
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds

/**
 * What the File Provider methods share across requests: the identifier map and the change
 * feed (docs/proposals/desktop-macos-integration.md §5a). One per daemon.
 *
 * [journal] persists the identifiers; `null` keeps them in memory (tests).
 */
class FileProviderBridgeState(journal: JavaFile? = null, stagingDir: JavaFile? = null) {
    internal val ids = FileProviderIds(journal)
    internal val feed = FileProviderChangeFeed()

    /** Where file contents are handed over by name instead of inside a reply (§5b). */
    internal val staging = FileProviderStaging(stagingDir)

    /** Caps concurrent `thumbnail` requests; see [THUMBNAIL_CONCURRENCY]. */
    internal val thumbnailGate = Semaphore(THUMBNAIL_CONCURRENCY)

    private val lock = ReentrantLock()

    /**
     * Runs [block] with no other identifier-touching request in flight.
     *
     * ⚠⚠ Bridge requests run concurrently, and a feed refresh is "list the realm, prune ids
     * whose path is gone, mint the rest". Interleaved with a rename, a refresh that listed the
     * realm *before* the rename prunes the id the rename just re-pointed (its new path is not
     * in the stale listing) and mints a fresh one for the old path — the rename then returns
     * an identifier that is already dead. The same holds for anything that mints (a listing, a
     * create) or drops (a delete) an id, so all of them share this one lock. `readContents`
     * only *resolves* an id and stays outside it, so a large download does not stall listings.
     */
    internal fun <T> exclusive(block: () -> T): T = lock.withLock(block)
}

/**
 * The File Provider methods' names on the wire: `fileProvider.v2.<name>`.
 *
 * ⚠⚠ The version is in the NAME because Control and Drive are separate installs. v1 addressed
 * items by path under these same bare names; had v2 kept them, a new extension against an old
 * daemon would have had `listFolder {id: "root"}` answered as a path listing and then failed
 * on the next call — half-working, with path-shaped ids leaking into Finder's database. With
 * distinct names a mismatched pair fails every call the same clean way (`unknown_method`,
 * which the extension treats as "daemon unavailable, retry"), in either direction. Bump the
 * number, do not reuse a name, when a parameter changes meaning.
 */
internal const val FILE_PROVIDER_METHOD_PREFIX = "fileProvider.v2."

/** How often a content read follows an identifier that moved under it before giving up. */
private const val READ_REMAP_RETRIES = 3

/**
 * How many `thumbnail` requests may be working at once. Finder asks for a whole folder's worth
 * in one burst, and each can fall through to a relay pull; uncapped, that burst is what filled
 * the bridge (see `NativeBridgeServer`'s *Threading* note).
 */
private const val THUMBNAIL_CONCURRENCY = 8

/**
 * ⚠⚠ The most a `thumbnail` request may take once its handler runs, queueing for
 * [THUMBNAIL_CONCURRENCY] included (not time queued for a bridge thread before that, and only as
 * far as the read is cancellable). Every handler holds a thread while it waits, so an unbounded
 * relay pull pins one for as long as the relay stays silent. Past this the answer is
 * `unavailable` — an error, which the system asks about again — never "no thumbnail", which it
 * would cache (see [storedThumbnail]). Owed work: `docs/tech-debt.md`, the desktop bridge entry.
 */
private val THUMBNAIL_TIMEOUT = 20.seconds

/** The largest upload the file-service can take: it is handed one `ByteArray`. */
private const val MAX_UPLOAD_BYTES = Int.MAX_VALUE - 16L

internal fun fileProviderMethod(name: String): String = FILE_PROVIDER_METHOD_PREFIX + name

/**
 * Registers the native-bridge **file-read** methods that back the macOS Drive
 * File Provider (docs/proposals/desktop-macos-integration.md §5 / §5a).
 *
 * Every item is addressed by an **identifier**, never a path — see [FileProviderIds] for why
 * and for what the identifier does and does not survive. `"root"` is the realm root.
 *
 *  - `listFolder(id)` — children of that folder, each shaped as a [BridgeFileItem].
 *  - `itemMetadata(id)` — one item's metadata.
 *  - `readContents(id)` — the file's raw bytes, base64-encoded for the JSON envelope.
 *  - `thumbnail(id, width, height)` — a stored thumbnail class for a file whose bytes need not
 *    be here; `{ available: false }` when the realm has none. See [storedThumbnail].
 *  - `listAll()` — every item in the realm plus the current anchor; the working set.
 *  - `currentAnchor()` — the change feed's anchor for the realm as it is now.
 *  - `changesSince(anchor)` — `{ anchor, expired, updated: [item], deleted: [id] }`. An anchor
 *    the feed cannot answer comes back `expired: true` and the caller must re-list.
 *
 * These run **in the Control daemon**, reading through the in-process [TenshuClient]
 * file-service. Deps are resolved lazily per request from [koin]; suspend reads run under
 * `runBlocking` on the bridge's own handler dispatcher — ⚠⚠ never on `Dispatchers.IO`, which the
 * reads themselves need (`NativeBridgeServer`'s *Threading* note).
 *
 * ⚠ `listAll`, `currentAnchor` and `changesSince` each **walk the whole realm**. There is no
 * files-scoped change signal to ask instead, so the cost of noticing a change is a full
 * listing; the caller polls, and must not poll fast.
 *
 * ⚠ `readContents` base64-encodes the whole file into the JSON reply; large-file streaming is
 * still owed (proposal §6a). Errors surface as [BridgeException] codes (`not_found` /
 * `unavailable` / `invalid_request`) the extension maps to `NSFileProviderError`.
 */
fun NativeBridgeServer.registerFileMethods(koin: Koin, state: FileProviderBridgeState) {
    register(fileProviderMethod("listFolder")) { params ->
        state.exclusive {
            val path = state.pathOf(params.require("id"))
            val items = runBlocking {
                koin.fileSystem().root.files
                    .childrenOf(path)
                    .select { itemProjection() }
                    .flow()
                    .first()
                    .fold(
                        onSuccess = { list -> list.toList().map { it.toBridgeItem(state.ids) } },
                        onFailure = { throw BridgeException("not_found", "listFolder failed for '$path': $it") },
                    )
            }
            folderResult(items)
        }
    }

    register(fileProviderMethod("itemMetadata")) { params ->
        state.exclusive {
            val path = state.pathOf(params.require("id"))
            metadataResult(runBlocking { koin.fileSystem().readMetadata(path, state.ids) })
        }
    }

    register(fileProviderMethod("readContents")) { params ->
        val id = params.require("id")
        // Outside the lock, so a download does not stall listings — which means a rename can
        // land between resolving the id and reading the row, in either of two ways:
        //  - the old path is now empty, and the read fails (as `not_found`, or as `internal`
        //    when it was the PARENT that moved and the chain broke a level up);
        //  - the old path already holds a DIFFERENT file, and the read succeeds with bytes
        //    that are not this item's — which Finder would cache under this identifier.
        // So the mapping is checked again after every attempt, success or failure, and the
        // result stands only if the id still named the path that was read.
        val bytes = runBlocking { state.readFollowing(id) { koin.fileSystem().readBytes(it) } }
        contentsResult(bytes)
    }

    register(fileProviderMethod("thumbnail")) { params ->
        val id = params.require("id")
        val maxEdge = maxOf(params.requireEdge("width"), params.requireEdge("height"))
        // Resolves only, like `readContents`, and for the same reason outside the lock: the
        // cold path is a content-plane fetch. The same re-check keeps a thumbnail of whatever
        // now sits at the old path from being cached under this identifier.
        val bytes = runBlocking {
            try {
                withTimeout(THUMBNAIL_TIMEOUT) {
                    state.thumbnailGate.withPermit {
                        state.readFollowing(id) { koin.fileSystem().storedThumbnail(it, maxEdge) }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw BridgeException("unavailable", "thumbnail for '$id' timed out")
            }
        }
        thumbnailResult(bytes)
    }

    // Contents by hand-off (§5b): the daemon writes the file into the staging directory under
    // the name the caller chose and answers with its size; no byte crosses the bridge. Like
    // `readContents` it only resolves the id, runs outside the lock, and stands only if the id
    // still named the path that was read.
    register(fileProviderMethod("fetchContents")) { params ->
        val id = params.require("id")
        val staged = params.require("staged")
        state.staging.validate(staged)
        try {
            val size = runBlocking {
                state.readFollowing(id) { path ->
                    koin.get<TenshuClient>().stageContents(path, state.staging, staged)
                }
            }
            state.staging.settled(staged)
            mapOf("staged" to staged, "size" to size)
        } catch (e: Throwable) {
            // A half-written plaintext file must not outlive the request that failed.
            state.staging.discard(staged)
            throw e
        }
    }

    // Stops the `fetchContents` writing to `staged` at its next chunk. A separate request on a
    // separate connection, because the one being cancelled is blocked in its handler.
    register(fileProviderMethod("cancelTransfer")) { params ->
        state.staging.cancel(params.require("staged"))
        mapOf("ok" to true)
    }

    register(fileProviderMethod("listAll")) { _ ->
        state.exclusive {
            val (items, anchor) = runBlocking { koin.fileSystem().refreshFeed(state) }
            folderResult(items) + ("anchor" to anchor)
        }
    }

    register(fileProviderMethod("currentAnchor")) { _ ->
        state.exclusive {
            mapOf("anchor" to runBlocking { koin.fileSystem().refreshFeed(state) }.second)
        }
    }

    register(fileProviderMethod("changesSince")) { params ->
        state.exclusive {
            val since = params.require("anchor")
            val (items, _) = runBlocking { koin.fileSystem().refreshFeed(state) }
            changesResult(state.feed.changesSince(since), items)
        }
    }
}

/**
 * Registers the native-bridge **file-write** methods that back the macOS Drive
 * File Provider's write path (docs/proposals/desktop-macos-integration.md §6):
 *
 *  - `createFolder(parentId, name)` / `createFile(parentId, name, data, modified?)` — `createItem`.
 *    `modified` is the new file's own modification time in epoch millis, when Finder's template
 *    carried one: a file dragged in keeps its date instead of taking the moment of the copy.
 *  - `modifyContents(id, data)` — overwrite an existing file's bytes.
 *  - `moveItem(id, parentId, name)` — rename and/or move; the item **keeps its identifier**.
 *  - `deleteItem(id)`.
 *
 * Create / modify / move return the resulting `{ item }` so the extension gets the post-write
 * `NSFileProviderItem` in one round-trip; `deleteItem` returns `{ ok: true }`. A name collision
 * surfaces as the `already_exists` code so the extension can map it to a File Provider conflict.
 *
 * ⚠ Writes go **straight to the file-service** — proxy-side permission enforcement
 * (`tenshu-rei.md` §2.2) is deferred, like the reads. `createFile` / `modifyContents` carry the
 * whole payload as base64.
 */
fun NativeBridgeServer.registerFileWriteMethods(koin: Koin, state: FileProviderBridgeState) {
    register(fileProviderMethod("createFolder")) { params ->
        state.exclusive {
            val path = childPath(state.pathOf(params.require("parentId")), params.requireName())
            runBlocking {
                val fileSystem = koin.fileSystem()
                fileSystem.createFolder(path).orThrowWrite("createFolder", path)
                metadataResult(fileSystem.readMetadata(path, state.ids))
            }
        }
    }

    register(fileProviderMethod("createFile")) { params ->
        state.createFile(
            koin, params.require("parentId"), params.requireName(), params.requireData(), params.modifiedAt(),
        )
    }

    register(fileProviderMethod("modifyContents")) { params ->
        state.modifyContents(koin, params.require("id"), params.requireData())
    }

    // The by-hand-off twins (§5b): the extension has put the new contents in the staging
    // directory and names the file. It is consumed here — removed whether the write succeeds
    // or not, since a retry stages a fresh copy.
    register(fileProviderMethod("createFileFrom")) { params ->
        val parentId = params.require("parentId")
        val name = params.requireName()
        val staged = params.require("staged")
        val modifiedAt = params.modifiedAt()
        try {
            state.createFile(koin, parentId, name, state.staging.readStaged(staged), modifiedAt)
        } finally {
            state.staging.discard(staged)
        }
    }

    register(fileProviderMethod("modifyContentsFrom")) { params ->
        val id = params.require("id")
        val staged = params.require("staged")
        try {
            state.modifyContents(koin, id, state.staging.readStaged(staged))
        } finally {
            state.staging.discard(staged)
        }
    }

    register(fileProviderMethod("moveItem")) { params ->
        state.exclusive {
            val id = params.require("id")
            val name = params.requireName()
            runBlocking {
                val fileSystem = koin.fileSystem()
                // A move this bridge deferred earlier may have been replayed since; the retry
                // of that same request must find the identifier already at its destination.
                state.ids.settleDeferredMoves(fileSystem.presentAmong(state.ids.deferredMovePaths()))
                val from = state.pathOf(id)
                val to = childPath(state.pathOf(params.require("parentId")), name)
                if (from == to) return@runBlocking metadataResult(fileSystem.readMetadata(from, state.ids))
                // The intent is journalled BEFORE the move: if Control dies between the move
                // committing and the re-point below, the next start still knows this id was
                // on its way to `to`. Files only — a folder has nothing to recognise it by.
                val fingerprint = fileSystem.presentAmong(setOf(from))[from]
                if (fingerprint != null) state.ids.deferMove(from, to, fingerprint)
                try {
                    fileSystem.moveFile(from, to).orThrowWrite("moveItem", "$from -> $to")
                } catch (e: BridgeException) {
                    state.ids.cancelMove(from)
                    throw e
                }
                // ⚠ `moveFile` can report success for a move it only *deferred*: nothing is at `to`
                // yet and the item is still at `from`. So "the destination cannot be read" has
                // two meanings, and the SOURCE tells them apart:
                //  - source still there → deferred. The id stays put and the deferral is
                //    remembered, so the replay re-points it instead of retiring it.
                //  - source gone → the move was applied and only the read-back failed. The id
                //    follows the item now; the retry then finds it already at `to`.
                val moved = try {
                    fileSystem.readEntityItem(to)
                } catch (e: BridgeException) {
                    // A read that FAILED says nothing either way; the journalled intent settles
                    // it on a later refresh, whichever happened.
                    if (e.code != "not_found") throw e
                    if (from in fileSystem.presentAmong(setOf(from))) {
                        // The intent recorded above stays open and is settled by the replay.
                        throw BridgeException("unavailable", "moveItem '$from' -> '$to' has not been applied yet")
                    }
                    state.ids.moved(from, to)
                    throw BridgeException("unavailable", "moveItem '$from' -> '$to' applied; '$to' is not readable yet")
                }
                state.ids.moved(from, to)
                metadataResult(moved.toBridgeItem(state.ids))
            }
        }
    }

    register(fileProviderMethod("deleteItem")) { params ->
        state.exclusive {
            val path = state.pathOf(params.require("id"))
            if (path == FileProviderIds.ROOT_PATH) throw BridgeException("invalid_request", "the root cannot be deleted")
            runBlocking { koin.fileSystem().deleteFile(path).orThrowWrite("deleteItem", path) }
            state.ids.removed(path)
            mapOf("ok" to true)
        }
    }
}

private fun FileProviderBridgeState.createFile(
    koin: Koin,
    parentId: String,
    name: String,
    data: ByteArray,
    modifiedAtMillis: Long?,
): Map<String, Any?> = exclusive {
    val parent = pathOf(parentId)
    val path = childPath(parent, name)
    runBlocking {
        val fileSystem = koin.fileSystem()
        fileSystem.uploadFile(parent, name, data, sourceModifiedAt = modifiedAtMillis)
            .orThrowWrite("createFile", path)
        metadataResult(fileSystem.readMetadata(path, ids))
    }
}

private fun FileProviderBridgeState.modifyContents(koin: Koin, id: String, data: ByteArray): Map<String, Any?> =
    exclusive {
        val path = pathOf(id)
        runBlocking {
            val fileSystem = koin.fileSystem()
            fileSystem.replaceFile(parentOf(path), nameOf(path), data).orThrowWrite("modifyContents", path)
            metadataResult(fileSystem.readMetadata(path, ids))
        }
    }

/**
 * The bytes of a staged upload.
 *
 * ⚠ Still the whole file in memory, once: the file-service's upload takes a `ByteArray` on
 * every platform, so the hand-off removes the base64 copy, the JSON copy and the frame limit —
 * not the engine's own. A file a `ByteArray` cannot hold is refused by name rather than left
 * to fail as an allocation.
 */
private fun FileProviderStaging.readStaged(name: String): ByteArray {
    validate(name)
    val size = uploadSize(name)
    if (size > MAX_UPLOAD_BYTES) {
        throw BridgeException("too_large", "'$name' is $size bytes; uploads are limited to $MAX_UPLOAD_BYTES")
    }
    return openUpload(name).use { it.readBytes() }
}

/**
 * Writes the contents of the file at [path] into the staged download [name], returning the
 * byte count.
 *
 * Through the sync service's content stream when the file has one — a seekable, chunk-at-a-time
 * read (`drive-streaming-preview.md` §3.5), so a large video never exists whole in this process.
 * A file with no stream yet (not synced; its bytes are only local) is read the old way: that is
 * the one case where the bytes are already here, so there is nothing to fetch in pieces.
 */
private suspend fun TenshuClient.stageContents(path: String, staging: FileProviderStaging, name: String): Long {
    fun checkCancelled() {
        if (staging.isCancelled(name)) throw BridgeException("cancelled", "the transfer was cancelled")
    }
    val opening = syncService.openContentStream(File.ID, path, File.Data.id)
        .fold(onSuccess = { it }, onFailure = { null })
    if (opening == null) {
        // One read, so cancel can only be honoured around it — but it must be, or a cancelled
        // fetch would be reported to Finder as a completed one.
        checkCancelled()
        val bytes = fileService.defaultFileSystem.readBytes(path)
        checkCancelled()
        staging.createDownload(name).use { it.write(bytes) }
        checkCancelled()
        return bytes.size.toLong()
    }
    val handle = opening.handle.get()
    try {
        return staging.createDownload(name).use { sink ->
            copyRanges(opening.totalSize.get(), sink, isCancelled = { staging.isCancelled(name) }) { offset, length ->
                syncService.readContentRange(handle, offset, length)
                    .fold(onSuccess = { it.getOrNull() }, onFailure = { null })
            }
        }
    } finally {
        syncService.closeContentStream(handle)
    }
}

/** The realm path an identifier names; an unknown identifier is `not_found`. */
private fun FileProviderBridgeState.pathOf(id: String): String =
    ids.pathFor(id) ?: throw BridgeException("not_found", "no item with id '$id'")

/**
 * Walks the realm, folds what it found into the change feed and prunes identifiers whose
 * path is gone. Returns every item and the feed's anchor for exactly that state.
 */
private suspend fun FileSystem.refreshFeed(state: FileProviderBridgeState): Pair<List<BridgeFileItem>, String> {
    val rows = root.files.traverse()
        .select { itemProjection() }
        .flow()
        .first()
        .fold(
            onSuccess = { list -> list.toList().map { ProjectedFile(it) } },
            onFailure = { throw BridgeException("unavailable", "could not list the realm: $it") },
        )
    // Deferred moves that have been replayed are re-pointed first, or the prune below would
    // retire their identifiers and the mint would hand the destinations new ones.
    val fingerprints = rows.associate { FileProviderIds.normalise(it.path) to it.moveFingerprint() }
    val livePaths = fingerprints.keys
    state.ids.settleDeferredMoves(fingerprints)
    // Prune BEFORE minting: an identifier whose path vanished is reported as deleted by the
    // feed (it drops out of the snapshot), and must not be handed to a new file at that path.
    state.ids.retainOnly(livePaths)
    val items = rows.map { it.toBridgeItem(state.ids) }
    val anchor = state.feed.refresh(items.associate { it.id to it.version() })
    return items to anchor
}

private fun Koin.fileSystem(): FileSystem =
    get<TenshuClient>().fileService.defaultFileSystem

private fun Map<String, String>.require(key: String): String =
    this[key] ?: throw BridgeException("invalid_request", "missing '$key'")

/** A single path component: no separators, and not one of the directory aliases. */
private fun Map<String, String>.requireName(): String =
    require("name").also {
        if (it.isEmpty() || '/' in it || it == "." || it == "..") {
            throw BridgeException("invalid_request", "'$it' is not a valid file name")
        }
    }

/**
 * The optional `modified` of a create, in epoch millis. Absent and unreadable are the same
 * answer — the upload then stamps the moment of the write — because a date is never worth
 * refusing a file over.
 */
private fun Map<String, String>.modifiedAt(): Long? = this["modified"]?.toLongOrNull()?.takeIf { it > 0 }

private fun Map<String, String>.requireData(): ByteArray =
    try {
        Base64.getDecoder().decode(require("data"))
    } catch (e: IllegalArgumentException) {
        throw BridgeException("invalid_request", "malformed base64 'data'")
    }

/** Parent folder of an absolute path; the realm root maps to `"/"` for the ops. */
private fun parentOf(path: String): String = path.substringBeforeLast('/').ifEmpty { "/" }

/** Final path component (the file/folder name). */
private fun nameOf(path: String): String = path.substringAfterLast('/')

/** Path of [name] directly under the folder at [parent] (no double slash at the root). */
private fun childPath(parent: String, name: String): String =
    if (parent == FileProviderIds.ROOT_PATH) "/$name" else "$parent/$name"

/** What every item-shaped read projects; shared so listings and metadata cannot drift. */
private fun <T : org.tenshu.core.Data> ListBuilder<Selector<T>>.itemProjection() {
    fields(File.Field.PATH)
    relations(
        File.Category, File.Size, File.CreatedAt, File.LastModifiedAt, File.ContentModifiedAt,
        File.ContentHash, File.ContentId,
    )
}

/**
 * Unwraps a write op's [TResult], translating a failure to a [BridgeException]:
 * a name collision → `already_exists` (the extension maps it to a conflict),
 * everything else → `internal`.
 */
private fun TResult<org.tenshu.core.Data?>.orThrowWrite(op: String, path: String) {
    fold(
        onSuccess = {},
        onFailure = { error ->
            val code = if (error is AlreadyExistsError) "already_exists" else "internal"
            throw BridgeException(code, "$op failed for '$path': $error")
        },
    )
}

/**
 * Metadata for the single file/folder at [path] as a [BridgeFileItem] (throws `not_found` if
 * absent). Shared by `itemMetadata` and the write methods' post-write re-read.
 */
private suspend fun FileSystem.readMetadata(path: String, ids: FileProviderIds): BridgeFileItem =
    readEntityItem(path).toBridgeItem(ids)

/**
 * A failed read as a bridge error, keeping "it is not there" apart from "the read failed".
 *
 * ⚠⚠ The difference decides things. `not_found` is acted on — Finder drops the item, a move is
 * taken as applied because its source is gone — so it must mean absence and nothing else. A
 * missing row *or a missing folder above it* arrives as the engine's typed [NotFoundError];
 * anything else (a store hiccup, a dead transport) is `unavailable`, which is only retried.
 */
private fun TError.asBridgeException(op: String, path: String): BridgeException =
    if (this is NotFoundError) {
        BridgeException("not_found", "no file at '$path'")
    } else {
        BridgeException("unavailable", "$op failed for '$path': $this")
    }

/**
 * Those of [paths] that exist, each mapped to its move-fingerprint (`null` when it has none).
 * A path is left out only when it is verifiably absent; a read that merely failed propagates.
 */
private suspend fun FileSystem.presentAmong(paths: Set<String>): Map<String, String?> {
    val present = HashMap<String, String?>()
    for (path in paths) {
        if (path == FileProviderIds.ROOT_PATH) continue
        try {
            present[path] = readEntityItem(path).moveFingerprint()
        } catch (e: BridgeException) {
            if (e.code != "not_found") throw e
        }
    }
    return present
}

/** A positive pixel edge; the bridge's params are strings whatever the caller sent. */
private fun Map<String, String>.requireEdge(key: String): Int =
    require(key).toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
        ?: throw BridgeException("invalid_request", "'$key' must be a positive number")

/** What [read] finds at the path [id] names, re-read if the identifier moved while reading. */
private suspend fun <T> FileProviderBridgeState.readFollowing(id: String, read: suspend (path: String) -> T): T {
    var path = pathOf(id)
    repeat(READ_REMAP_RETRIES) {
        val outcome = runCatching { read(path) }
        val current = pathOf(id)
        if (current == path) return outcome.getOrThrow()
        path = current
    }
    throw BridgeException("unavailable", "'$id' kept moving while it was being read")
}

/**
 * The stored thumbnail class that best answers a [maxEdge]-pixel request for the file at
 * [path], or `null` when the realm has none (`drive-synced-thumbnails.md`).
 *
 * ⚠⚠ Reads the stored CLASSES, never `File.Preview`: on a device that holds the bytes
 * `GetPreview` renders from the original, and a Finder thumbnail must not read — let alone
 * fetch — a file's content. The classes are separate small blobs on the content plane.
 *
 * ⚠ Only `LIST` (100 px) and `LARGE` (256 px), the two every upload mints. The hero classes
 * are sharper but naming one *declares* it wanted for this device, which starts a realm-wide
 * backfill (`ThumbnailPreferences.WantedClasses`) — not a side effect for a Finder icon.
 *
 * A class that is not on this device yet is pulled once and re-read, as
 * `TenshuFilesRepository.getPreview` does: the classes are `Syncable(OnDemand)`, so projecting
 * them through a materialising terminal is what fetches them, and the first (`eager`) read is
 * what keeps a thumbnail that is already local from waiting on that.
 */
private suspend fun FileSystem.storedThumbnail(path: String, maxEdge: Int): ByteArray? {
    if (path == FileProviderIds.ROOT_PATH) return null
    val file = root.files.fileAt(path)
    suspend fun read(): ByteArray? = file
        .select { relations(eager(File.Thumbnail), eager(File.ThumbnailLarge)) }
        .resolve()
        .fold(
            onSuccess = { entity ->
                if (entity == null) throw BridgeException("not_found", "no file at '$path'")
                val list = entity.get(File.Thumbnail)?.getOrNull()
                val large = entity.get(File.ThumbnailLarge)?.getOrNull()
                bestStoredClass(maxEdge, list, large)
            },
            onFailure = { throw it.asBridgeException("thumbnail", path) },
        )
    val local = read()
    // Nothing mints a still for any other kind of file, so there is nothing to ask the relay for.
    if (local != null || !carriesStills(path)) return local
    // ⚠⚠ A pull that FAILED must not come back as "no thumbnail". The system caches that
    // answer until the item's content version moves, so a relay that was briefly unreachable
    // would leave the generic icon on the file for good. It is an error instead, which the
    // system asks about again.
    file.select { relations(File.Thumbnail, File.ThumbnailLarge) }.resolve().fold(
        onSuccess = {},
        onFailure = { throw it.asBridgeException("thumbnail pull", path) },
    )
    return read()
}

/**
 * The smallest class that covers a [maxEdge]-pixel request, else whichever exists — the rule
 * the web's stored-class read applies (`readPreviewJs`).
 */
internal fun bestStoredClass(maxEdge: Int, list: ByteArray?, large: ByteArray?): ByteArray? =
    if (maxEdge > GetStoredThumbnail.LIST_PX) large ?: list else list ?: large

/**
 * What a still class is minted for: an image, a video's poster frame, a PDF's first page —
 * by name, as every platform's `GetPreview` decides it (`ThumbnailClass.canCarry` is the
 * proxy-internal statement of the same rule).
 */
private fun carriesStills(path: String): Boolean = nameOf(path).let { name ->
    isPdf(name) || fileCategoryForName(name).let { it == FileCategory.Image || it == FileCategory.Video }
}

private suspend fun FileSystem.readBytes(path: String): ByteArray =
    root.files
        .fileAt(path)
        .select { relations(File.Data) }
        .resolve()
        .fold(
            onSuccess = { entity ->
                // `TByteArray.getOrNull()` is graceful on a cache miss (see
                // TenshuFilesRepository.getRemoteFile for why not `get()`).
                entity?.get(File.Data)?.getOrNull()
                    ?: throw BridgeException(
                        if (entity == null) "not_found" else "unavailable",
                        if (entity == null) "no file at '$path'" else "no local bytes for '$path'",
                    )
            },
            onFailure = { throw it.asBridgeException("readContents", path) },
        )

private suspend fun FileSystem.readEntityItem(path: String): ProjectedFile {
    if (path == FileProviderIds.ROOT_PATH) throw BridgeException("invalid_request", "the root has no metadata row")
    return root.files
        .fileAt(path)
        .select { itemProjection() }
        .resolve()
        .fold(
            onSuccess = { it?.let(::ProjectedFile) ?: throw BridgeException("not_found", "no file at '$path'") },
            onFailure = { throw it.asBridgeException("metadata", path) },
        )
}

/**
 * Children of the folder at [folderPath] (`""`/`"/"` = the realm root). Mirrors
 * `TenshuFilesRepository.navigateTo` but rooted *at* the target folder rather
 * than its parent: a missing folder fails fast via [ifEmptyError] (→ `not_found`),
 * while a genuinely empty folder yields an empty child list.
 *
 * Segment-wise like `navigateTo`: each hop is a direct-child key lookup so the
 * proxy's by-key search fast path point-reads every level (a single deep
 * search always misses the top-level membership probe and falls back to the
 * recursive scan).
 */
private fun TList<File>.childrenOf(folderPath: String): TList<File> {
    val folder = folderPath.ifEmpty { "/" }
    if (folder == "/") return this
    var list = this
    var prefix = ""
    for (segment in folder.trimStart('/').split('/')) {
        prefix = "$prefix/$segment"
        val level = prefix
        list = list.search<Folder> { path equalTo level }.ifEmptyError().files
    }
    return list
}

/** The single file/folder at the absolute [fullPath], searched under its parent. */
private fun TList<File>.fileAt(fullPath: String) =
    childrenOf(fullPath.substringBeforeLast('/'))
        .search<File> { path equalTo fullPath }

/**
 * An `Entity<File>` read out into plain values, before any identifier is involved. Split from
 * [BridgeFileItem] because minting an identifier is a write to the map: a caller that may
 * still discard the row (a deferred move) must be able to look first.
 */
private class ProjectedFile(entity: Entity<File>) {
    val path: String = entity.path.get()
    val isFolder: Boolean = entity.type.get() == Folder.ID
    val size: Long = entity.get(File.Size)?.get() ?: 0L
    val createdAtMillis: Long? = entity.get(File.CreatedAt)?.get()
    private val touchedAtMillis: Long? = entity.get(File.LastModifiedAt)?.get()

    /**
     * The date Finder shows as "Date Modified": the file's own, never the moment it was uploaded
     * or moved — a library backed up yesterday otherwise reads as modified yesterday.
     * ⚠ A folder has no content date; it keeps the realm's "last touched".
     */
    val modifiedAtMillis: Long? =
        if (isFolder) touchedAtMillis
        else contentModifiedAtOrCreated(entity.get(File.ContentModifiedAt)?.get(), createdAtMillis)
    val category: String? = categoryCode(entity.get(File.Category))
    val contentHash: String? = entity.get(File.ContentHash)?.get()
    val contentId: String? = entity.get(File.ContentId)?.get()

    /**
     * What identifies this item across a move, so "the item now at the destination is the one
     * that left the source" can be told from "something else is" — or `null` when nothing does.
     *
     * Only a file's non-blank content id qualifies (rows from before content addressing carry
     * an empty one, and "" would match every other such row): it is random, minted at upload and carried by a
     * move. Two rows share one only when one is a copy of the other, and adopting a copy
     * hands Finder the same bytes. ⚠ A **folder** has no such field — its creation time is a
     * millisecond, which two folders can share — so a folder's deferred move is not tracked
     * at all and degrades to delete + create. Vague would be worse than absent here: a wrong
     * match gives one tree's identifier, and everything Finder cached under it, to another.
     */
    fun moveFingerprint(): String? = if (isFolder) null else contentId?.takeIf { it.isNotBlank() }

    fun toBridgeItem(ids: FileProviderIds) = BridgeFileItem(
        id = ids.idFor(path),
        parentId = ids.idFor(path.substringBeforeLast('/').ifEmpty { FileProviderIds.ROOT_PATH }),
        name = path.substringAfterLast('/'),
        isFolder = isFolder,
        size = if (isFolder) 0L else size,
        createdAtMillis = createdAtMillis,
        modifiedAtMillis = modifiedAtMillis,
        category = category,
        // The content hash says "these bytes"; the content id is re-minted on every overwrite
        // that changes them. Size + the realm's touch time is the fallback for rows older than
        // both — ⚠ not `modifiedAtMillis`, which an overwrite from an older source moves backwards
        // or not at all.
        contentVersion = if (isFolder) "" else contentHash ?: contentId ?: "$size:${touchedAtMillis ?: 0}",
        path = path,
    )
}

private fun Entity<File>.toBridgeItem(ids: FileProviderIds): BridgeFileItem = ProjectedFile(this).toBridgeItem(ids)

/**
 * Stable category code for the wire (ADR 0016: surfaces ship codes, the
 * extension owns the localised display string). `null` when uncategorised /
 * a folder.
 */
private fun categoryCode(category: FileCategory?): String? = when (category) {
    FileCategory.Image -> "image"
    FileCategory.Audio -> "audio"
    FileCategory.Video -> "video"
    FileCategory.Document -> "document"
    FileCategory.Archive -> "archive"
    FileCategory.Other -> "other"
    null -> null
}

/**
 * The bridge projection of a file/folder for the File Provider. Plain Kotlin so
 * the JSON-shaping helpers below are unit-testable without an `Entity` or a proxy.
 *
 * [path] never crosses the bridge — the extension addresses everything by [id].
 */
internal data class BridgeFileItem(
    val id: String,
    val parentId: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long,
    val createdAtMillis: Long?,
    val modifiedAtMillis: Long?,
    val category: String?,
    val contentVersion: String,
    val path: String,
) {
    /** Everything Finder can observe about the item; a change in any of it is an update. */
    fun version(): String = "$parentId|$name|$isFolder|$contentVersion|$size|$createdAtMillis|$modifiedAtMillis"
}

/** `{ id, parentId, name, isFolder, size, created, modified, category, contentVersion }`. */
internal fun fileItemMap(item: BridgeFileItem): Map<String, Any?> = mapOf(
    "id" to item.id,
    "parentId" to item.parentId,
    "name" to item.name,
    "isFolder" to item.isFolder,
    "size" to item.size,
    "created" to item.createdAtMillis,
    "modified" to item.modifiedAtMillis,
    "category" to item.category,
    "contentVersion" to item.contentVersion,
)

/**
 * `{ anchor, expired, updated: [item], deleted: [id] }`. [changes] is `null` when the feed
 * could not answer the caller's anchor — the reply then says only `expired: true`.
 */
internal fun changesResult(
    changes: FileProviderChangeFeed.Changes?,
    items: List<BridgeFileItem>,
): Map<String, Any?> =
    if (changes == null) {
        mapOf("expired" to true)
    } else {
        mapOf(
            "expired" to false,
            "anchor" to changes.anchor,
            "updated" to items.filter { it.id in changes.updated }.map { fileItemMap(it) },
            "deleted" to changes.deleted.toList(),
        )
    }

/** `{ items: [{ … }] }` — a folder's children for the enumerator. */
internal fun folderResult(items: List<BridgeFileItem>): Map<String, Any?> =
    mapOf("items" to items.map { fileItemMap(it) })

/** `{ item: { … } }` — a single item's metadata. */
internal fun metadataResult(item: BridgeFileItem): Map<String, Any?> =
    mapOf("item" to fileItemMap(item))

/**
 * `{ data: <base64>, encoding: "base64", size: <bytes> }`. The bridge envelope
 * is JSON, so bytes ride as base64; the extension decodes before writing the
 * materialised file.
 */
internal fun contentsResult(bytes: ByteArray): Map<String, Any?> = mapOf(
    "data" to Base64.getEncoder().encodeToString(bytes),
    "encoding" to "base64",
    "size" to bytes.size,
)

/**
 * `{ available: true, data, encoding, size }`, or `{ available: false }` when the realm stores
 * no thumbnail for the item — an answer, not an error: the extension reports "no thumbnail"
 * and Finder draws the type's icon.
 */
internal fun thumbnailResult(bytes: ByteArray?): Map<String, Any?> =
    if (bytes == null) mapOf("available" to false) else contentsResult(bytes) + ("available" to true)
