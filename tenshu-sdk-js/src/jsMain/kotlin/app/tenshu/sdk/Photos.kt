@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.promise
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.equalTo
import org.tenshu.core.file.CameraMake
import org.tenshu.core.file.CameraModel
import org.tenshu.core.file.CaptureLatE7
import org.tenshu.core.file.CaptureLngE7
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.DurationMs
import org.tenshu.core.file.Category
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.Orientation
import org.tenshu.core.file.PixelHeight
import org.tenshu.core.file.PixelWidth
import org.tenshu.core.file.Size
import org.tenshu.core.file.TRASH_ROOT
import org.tenshu.core.file.ThumbnailMotion
import org.tenshu.core.file.contentId
import org.tenshu.core.file.createdAt
import org.tenshu.core.file.files
import org.tenshu.core.file.isMedia
import org.tenshu.core.file.notTrashed
import org.tenshu.core.file.path
import org.tenshu.core.file.system.AlreadyExistsError
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.fold
import org.tenshu.core.getOrNull
import org.tenshu.core.inRange
import org.tenshu.core.oneOf
import org.tenshu.core.resolve
import org.tenshu.file.client.fileService
import org.tenshu.photo.Album
import org.tenshu.photo.AlbumEntry
import org.tenshu.photo.PhotoFlags
import org.tenshu.photo.PhotoService
import org.tenshu.photo.TrashedPhoto
import org.tenshu.photo.addToAlbum
import org.tenshu.photo.albumId
import org.tenshu.photo.clearTrashedPhoto
import org.tenshu.photo.client.photoService
import org.tenshu.photo.coverContentId
import org.tenshu.photo.deleteAlbum
import org.tenshu.photo.favourite
import org.tenshu.photo.filePath
import org.tenshu.photo.hidden
import org.tenshu.photo.name
import org.tenshu.photo.originalPath
import org.tenshu.photo.position
import org.tenshu.photo.recordTrashedPhoto
import org.tenshu.photo.removeFromAlbum
import org.tenshu.photo.saveAlbum
import org.tenshu.photo.setPhotoFlags
import org.tenshu.photo.trashPath
import org.tenshu.photo.trashedAt
import kotlin.js.Date
import kotlin.js.Promise
import org.tenshu.photo.contentId as photoContentId
import org.tenshu.photo.createdAt as albumCreatedAt

/**
 * The Photos web surface (`web-app-suite.md` Wave 2). It mirrors the parts of the
 * native `PhotosRepository` / `TenshuPhotosRepository` that `photos-app.md` §42 leaves undisputed:
 * the capture-date feed, the viewer's stored details, albums, favourites, the trash and `MOTION`
 * clip bytes. Faces, labels, search, places, duplicates, upload and mint-on-view are §42's open
 * questions and are deliberately absent.
 *
 * ⚠⚠ **Re-implemented, not compiled.** `*-shared-logic` must not compile to JS, so this is a second
 * reader of the relations `TenshuPhotosRepository` reads. The rules it copies are listed once at
 * the top of `PhotosLibrary.kt` and pinned by `PhotosLibraryRulesTest` + `PhotosLibraryContractTest`.
 *
 * ⚠⚠ **Bytes are not here**, with one exception. Stills come through Drive's surface:
 * `FileSystem.getPreview` for tiles, `FileSystem.getLargePreview` for the viewer (with its
 * `coveringRung` tri-state), and `ContentStream` for a video's original. Nothing here projects
 * `File.Preview` or `File.ViewerPreview` (`drive-web-parity.md` §1.2i). The exception is
 * [motionClip], because `MOTION` never joins `GetPreview`'s ladder.
 *
 * Every call resolves `{ ok: true, … }` or `{ ok: false, error: { kind, message } }`, with `kind` a
 * catalogue key (`error_…`). ⚠ **A failed read is a failure, never an empty list**: an empty feed
 * and a feed that could not be read must not look the same.
 *
 * **Not-found** has two shapes:
 * - A read of ONE thing by path ([photo], [details], [motionClip]) answers `ok: true` with `null`.
 *   That is an answer.
 * - An op that needs its target to exist fails with a typed key: `error_photos_unknown_album`,
 *   `error_photos_not_found`, `error_photos_not_in_trash`. A write that touched nothing must not
 *   read as done.
 *
 * **Reactivity:**
 * - albums, favourites and the trash have push forms ([observeAlbums], [observeFavourites],
 *   [observeTrash]) over the photo-service relations' Flows;
 * - the feed is snapshot + refetch, because its only Flow is a whole-realm `traverse()` stream,
 *   which is the whole library per change;
 * - ⚠ the push forms re-emit when a *photo-service* row changes. A file moved or deleted in Drive
 *   changes no such row, so the caller refetches after its own file writes.
 */
@JsExport
object Photos {

    // ── the feed ──────────────────────────────────────────────────────────────────────────────

    /**
     * One keyset page of the feed, newest first: `{ ok: true, photos, nextCursor }`.
     *
     * [cursor] is `null` (or `""`) for the first page, else the `nextCursor` a previous page
     * returned. `nextCursor` is `null` after the last page. The token is opaque: do not parse it.
     * [pageSize] must be `1..500`, so a page can never be the whole library in one postMessage.
     *
     * ⚠ Keyset over a TOTAL order, `(capture time desc, path asc)`, and the cursor names the last
     * row served (`PhotosLibrary.kt` rule 3). What that guarantees, against rows arriving or being
     * trashed between two calls:
     * - a row served once is never served again, and a row still present after the cursor is
     *   never skipped — including at the boundary millisecond, ties and all;
     * - a row that arrives BEFORE the cursor (newer, or tied and earlier by path) is not in later
     *   pages: refetch the head to show it. One that arrives after the cursor lands in its page.
     * ⚠ Not a snapshot: a page reflects the realm when it was read, so the pages together are not
     * one instant's feed.
     */
    fun feedPage(cursor: String?, pageSize: Int): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val at = FeedCursor.decode(cursor)
            ?: return@promise photosFailure(PhotosWebErrorKeys.BAD_CURSOR, "cursor '$cursor'")
        if (pageSize !in 1..MAX_FEED_PAGE_SIZE) {
            return@promise photosFailure(PhotosWebErrorKeys.BAD_PAGE_SIZE, "pageSize $pageSize")
        }
        // ⚠ The chain has no string `>` predicate, so the rest of the boundary millisecond is read
        // whole (one millisecond's rows: a burst, not the library) and cut by path here. Everything
        // older is one bounded top-N walk below it.
        val rows = mutableListOf<Entity<File>>()
        if (at.afterPath != null) {
            rows += feedRows { feedWindow(at.beforeMs, fromMs = at.beforeMs) }
                .valueOr { return@promise photosFailure(it) }
                .filter { it.feedKey().afterCursor(at) }
                .take(pageSize)
        }
        if (rows.size < pageSize) {
            val olderThan = if (at.afterPath != null) at.beforeMs - 1 else at.beforeMs
            rows += feedRows { feedWindow(olderThan).take(pageSize - rows.size) }
                .valueOr { return@promise photosFailure(it) }
        }
        val next = nextFeedCursor(rows.map { it.feedKey() }, pageSize)
        okResult {
            it.photos = rows.mapNotNull { row -> row.photoOf() }.toTypedArray()
            it.nextCursor = next?.encode()
        }
    }

    /**
     * Every capture time in the feed, newest first, as epoch ms: `{ ok: true, times }`. This is the
     * scrubber's density map and the feed's length (`times.length`).
     *
     * ⚠ It covers the whole feed, not the loaded pages: the year marks must say where 2021 is *in
     * the library*. It is one number per photograph, read over the same window the pages use, so a
     * mark cannot point where the feed has no photographs.
     */
    fun captureTimes(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        client().fileService.defaultFileSystem.root
            .files
            .traverse()
            .feedWindow(Long.MAX_VALUE)
            .select { relations(File.CreatedAt) }
            .getAll()
            .fold(
                onSuccess = { rows ->
                    val times = rows.toList().mapNotNull { row -> row.get(File.CreatedAt)?.get()?.toDouble() }
                    okResult { it.times = times.toTypedArray() }
                },
                onFailure = { photosFailure(it) },
            )
    }

    // ── one photograph ────────────────────────────────────────────────────────────────────────

    /**
     * The photograph at [path]: `{ ok: true, photo }`, with `photo: null` when nothing is there — or
     * when what is there is not a photograph or video (a `.txt` is no photo).
     */
    fun photo(path: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        when (val found = photoAt(path)) {
            is Lookup.Found -> okResult { it.photo = found.value }
            Lookup.Missing, Lookup.NotMedia -> okResult { it.photo = null }
            is Lookup.Failed -> photosFailure(found.error)
        }
    }

    /**
     * The viewer's info panel from the file's **stored** fields: `{ ok: true, details }`, with
     * `details: null` when nothing is at [path].
     *
     * ⚠ Only what the row carries. The full EXIF parse of the original's bytes is §42.5's open
     * question, and on web it would mean fetching a 6–18 MB original to fill a panel.
     */
    fun details(path: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        client().fileService.defaultFileSystem.root
            .files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            .select {
                fields(File.Field.PATH)
                relations(
                    File.CreatedAt, File.Size, File.PixelWidth, File.PixelHeight, File.Orientation,
                    File.CameraMake, File.CameraModel, File.DurationMs, File.CaptureLatE7, File.CaptureLngE7,
                )
            }
            .resolve()
            .fold(
                onSuccess = { entity ->
                    okResult {
                        it.details = entity?.let { e ->
                            PhotoDetailsJs(
                                path = path,
                                capturedAtMs = e.get(File.CreatedAt)?.get()?.toDouble(),
                                sizeBytes = e.get(File.Size)?.get()?.toDouble(),
                                pixelWidth = e.get(File.PixelWidth)?.get()?.toInt(),
                                pixelHeight = e.get(File.PixelHeight)?.get()?.toInt(),
                                orientation = e.get(File.Orientation)?.get()?.toInt(),
                                cameraMake = e.get(File.CameraMake)?.get(),
                                cameraModel = e.get(File.CameraModel)?.get(),
                                durationMs = e.get(File.DurationMs)?.get()?.toDouble(),
                                latE7 = e.get(File.CaptureLatE7)?.get()?.toDouble(),
                                lngE7 = e.get(File.CaptureLngE7)?.get()?.toDouble(),
                            )
                        }
                    }
                },
                onFailure = { error -> if (error.isNotFound()) okResult { it.details = null } else photosFailure(error) },
            )
    }

    /**
     * The video's `MOTION` clip, a ~3 s, 256 px, silent MP4 (`photos-video-support.md` §4):
     * `{ ok: true, bytes }`, with `bytes: null` when no clip exists here yet.
     *
     * ⚠⚠ `null` is a wait, not an answer. The class is OnDemand, so this read is what pulls it, and
     * a video over the cap, or minted before the class existed, never has one. Never remember a `null`.
     * ⚠⚠ Its own read, outside `GetPreview`'s ladder: a clip is not a bitmap. Ask for the one clip
     * in flight, never for every video scrolled past (§5.3).
     */
    fun motionClip(path: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        client().fileService.defaultFileSystem.root
            .files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            .select { relations(File.ThumbnailMotion) }
            .resolve()
            .fold(
                onSuccess = { entity ->
                    val bytes = entity?.get(File.ThumbnailMotion)?.getOrNull()?.takeIf { b -> b.isNotEmpty() }
                    okResult { it.bytes = bytes }
                },
                onFailure = { error -> if (error.isNotFound()) okResult { it.bytes = null } else photosFailure(error) },
            )
    }

    // ── albums ────────────────────────────────────────────────────────────────────────────────

    /** Every album, newest first, each with its count and cover path: `{ ok: true, albums }`. */
    fun albums(): Promise<dynamic> = GlobalScope.promise { JsRuntime.awaitReady(); albumsResult() }

    /** Pushes the [albums] envelope on every album or membership change. `onError` is terminal. */
    fun observeAlbums(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        Contacts.observeJob(onError) {
            merge(albumRowsFlow(), entryRowsFlow()).collect { callback(albumsResult()) }
        }

    /**
     * One album's photographs in the user's order: `{ ok: true, photos }`, or
     * `error_photos_unknown_album`.
     *
     * ⚠ An entry whose file cannot be found is omitted, not rendered as a gap. An entry whose path
     * moved is found again by its content id and its path hint is rewritten, best effort, exactly as
     * native heals it (`TenshuPhotosRepository.photoFor`).
     */
    fun albumPhotos(albumId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val albums = albumRows().valueOr { return@promise photosFailure(it) }
        if (albums.none { it.albumId.get() == albumId }) return@promise unknownAlbum(albumId)
        val entries = entryRows().valueOr { return@promise photosFailure(it) }
        val photos = mutableListOf<PhotoJs>()
        for (entry in entries.filter { it.albumId == albumId }.sortedBy { it.position }) {
            when (val found = photoAt(entry.filePath)) {
                is Lookup.Found -> photos += found.value
                is Lookup.Failed -> return@promise photosFailure(found.error)
                // ⚠ Only a non-media file can sit at a path an entry names (the guard keeps them out).
                Lookup.NotMedia -> continue
                Lookup.Missing -> {
                    val moved = libraryByContentIds(listOf(entry.contentId))
                        .valueOr { return@promise photosFailure(it) }
                    val photo = moved.firstOrNull() ?: continue
                    photos += photo
                    healHint(entry.albumId, entry.contentId, photo.path)
                }
            }
        }
        okResult { it.photos = photos.toTypedArray() }
    }

    /** The albums one photograph is in (the viewer's info panel): `{ ok: true, albums }`. */
    fun albumsFor(contentId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        // ⚠ A file with no content id is in no album by construction; nothing to read.
        if (contentId.isEmpty()) return@promise okResult { it.albums = emptyArray<PhotoAlbumJs>() }
        val albums = albumRows().valueOr { return@promise photosFailure(it) }
        val entries = entryRows().valueOr { return@promise photosFailure(it) }
        val ids = entries.filter { it.contentId == contentId }.map { it.albumId }.toSet()
        okResult { it.albums = summariesOf(albums, entries).filter { a -> a.albumId in ids }.toTypedArray() }
    }

    /** `{ ok: true, albumId }`. */
    fun createAlbum(name: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val albumId = mintAlbumId(Date.now().toLong(), name)
        service().saveAlbum(albumId, name).fold(
            onSuccess = { okResult { it.albumId = albumId } },
            onFailure = { photosFailure(it) },
        )
    }

    /**
     * ⚠⚠ `SaveAlbum` REPLACES the row, so the rename carries the album's chosen cover. Without it
     * every rename resets the cover. An album that cannot be read is a failure,
     * never a rename with the cover guessed.
     */
    fun renameAlbum(albumId: String, name: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val row = (albumRows().valueOr { return@promise photosFailure(it) }).firstOrNull { it.albumId.get() == albumId }
            ?: return@promise unknownAlbum(albumId)
        write { saveAlbum(albumId, name, row.coverContentId.get()) }
    }

    /** Deletes the album and its entries. No photograph is touched. */
    fun deleteAlbum(albumId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val missing = albumCheck(albumId)
        if (missing != null) return@promise missing
        write { deleteAlbum(albumId) }
    }

    /**
     * Files the photograph at [path] into the album. Fails with `error_photos_not_found` when no
     * file is there, `error_photos_not_a_photo` for a non-media file or one in the trash, and
     * `error_photos_no_content_id` for a file predating content addressing.
     * ⚠ Re-adding keeps its position; it is not a reorder.
     */
    fun addToAlbum(albumId: String, path: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val missing = albumCheck(albumId)
        if (missing != null) return@promise missing
        val photo = when (val target = photoToWrite(path)) {
            is Target.Ok -> target.photo
            is Target.Refused -> return@promise target.failure
        }
        write { addToAlbum(albumId, photo.contentId, photo.path) }
    }

    /** `{ ok: true, changed }`: `changed: false` when the photograph was not in the album. */
    fun removeFromAlbum(albumId: String, contentId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val missing = albumCheck(albumId)
        if (missing != null) return@promise missing
        val entries = entryRows().valueOr { return@promise photosFailure(it) }
        val present = entries.any { it.albumId == albumId && it.contentId == contentId }
        service().removeFromAlbum(albumId, contentId).fold(
            onSuccess = { okResult { it.changed = present } },
            onFailure = { photosFailure(it) },
        )
    }

    // ── favourites ────────────────────────────────────────────────────────────────────────────

    /**
     * Every favourited photograph or video, newest first: `{ ok: true, photos }`.
     *
     * ⚠ DIVERGES from native on purpose: `TenshuPhotosRepository.favourites` reads `imagesOnly`, so a
     * favourited video is missing there. Here it appears, because the user favourited it
     * (`photos-app.md` §42.6).
     */
    fun favourites(): Promise<dynamic> = GlobalScope.promise { JsRuntime.awaitReady(); favouritesResult() }

    /** Pushes the [favourites] envelope on every flag change. `onError` is terminal. */
    fun observeFavourites(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        Contacts.observeJob(onError) {
            flagRowsFlow().collect { callback(favouritesResult()) }
        }

    /** `{ ok: true, favourite }`. */
    fun isFavourite(contentId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val flags = flagRows().valueOr { return@promise photosFailure(it) }
        okResult { it.favourite = flags.any { f -> f.photoContentId.get() == contentId && f.favourite.get() } }
    }

    /**
     * ⚠⚠ The flags row holds `favourite` AND `hidden`, so `hidden` is read back and passed through.
     * Defaulting it would silently un-hide a photograph on every favourite.
     * ⚠ Unlike native, a flags read that fails is a failure here, not `hidden = false`: defaulting
     * on a failed read is the same un-hide, only rarer.
     */
    fun setFavourite(contentId: String, favourite: Boolean): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        if (contentId.isEmpty()) return@promise photosFailure(PhotosWebErrorKeys.NO_CONTENT_ID, null)
        val flags = flagRows().valueOr { return@promise photosFailure(it) }
        val hidden = flags.firstOrNull { it.photoContentId.get() == contentId }?.hidden?.get() ?: false
        write { setPhotoFlags(contentId, favourite, hidden) }
    }

    // ── trash ─────────────────────────────────────────────────────────────────────────────────

    /** What is in the trash, most recently trashed first: `{ ok: true, items }`. */
    fun trashed(): Promise<dynamic> = GlobalScope.promise { JsRuntime.awaitReady(); trashResult() }

    /** Pushes the [trashed] envelope on every trash-record change. `onError` is terminal. */
    fun observeTrash(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        Contacts.observeJob(onError) {
            trashRowsFlow().collect { callback(trashResult()) }
        }

    /**
     * Moves the photograph at [path] to `/Trash` and records where it came from.
     *
     * ⚠ A move, not a delete: the bytes and the content id stay put, so restoring is another move.
     * ⚠⚠ `/Trash` is created first, because a move into a missing folder is `notFound`. "Already
     * exists" is success, matched on the error's TYPE. ⚠ Recorded AFTER the move: a record with no
     * file is harmless, and a trashed file with no record is still recoverable from Drive.
     * ⚠⚠ A path already under `/Trash` is refused (`error_photos_not_a_photo`): re-trashing it would
     * overwrite its record's `originalPath` with the trash path, and restore would lose the way home.
     */
    fun trash(path: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val photo = when (val target = photoToWrite(path)) {
            is Target.Ok -> target.photo
            is Target.Refused -> return@promise target.failure
        }
        val fs = client().fileService.defaultFileSystem
        val folder = fs.createFolder(TRASH_ROOT)
        if (folder is TResult.Failure<*> && folder.error !is AlreadyExistsError) {
            return@promise photosFailure(folder.error)
        }
        val trashPath = trashPathFor(photo.contentId, photo.name)
        val moved = fs.moveFile(photo.path, trashPath)
        if (moved is TResult.Failure<*>) return@promise photosFailure(moved.error)
        write { recordTrashedPhoto(photo.contentId, photo.path, trashPath) }
    }

    /** Puts a trashed photograph back where it was. `error_photos_not_in_trash` for an unknown id. */
    fun restore(contentId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val record = (trashRows().valueOr { return@promise photosFailure(it) }).firstOrNull { it.contentId == contentId }
            ?: return@promise photosFailure(PhotosWebErrorKeys.NOT_IN_TRASH, contentId)
        val moved = client().fileService.defaultFileSystem.moveFile(record.trashPath, record.originalPath)
        if (moved is TResult.Failure<*>) return@promise photosFailure(moved.error)
        write { clearTrashedPhoto(contentId) }
    }

    /**
     * ⚠⚠ Irreversible. Deletes a trashed photograph for good and clears its record.
     *
     * A file that is already gone is success, and its record is cleared so the trash never shows a
     * row that cannot be acted on. ⚠⚠ Any other delete failure leaves the record ALONE: the file is
     * still at `/Trash/<cid>-<name>`, and the record is the only thing that lists it in [trashed]
     * and lets a retry find it. Clearing it first would strand the file, out of the feed and the
     * trash both.
     */
    fun deleteForever(contentId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val record = (trashRows().valueOr { return@promise photosFailure(it) }).firstOrNull { it.contentId == contentId }
            ?: return@promise photosFailure(PhotosWebErrorKeys.NOT_IN_TRASH, contentId)
        val deleted = deleteFile(record.trashPath)
        if (deleted is TResult.Failure<*> && !deleted.error.isNotFound()) return@promise photosFailure(deleted.error)
        write { clearTrashedPhoto(contentId) }
    }

    /** [deleteForever]'s file delete — a seam, so a test can make it fail the way a real store can. */
    internal var deleteFile: suspend (path: String) -> TResult<*> =
        { path -> client().fileService.defaultFileSystem.deleteFile(path) }

    // ── internals ─────────────────────────────────────────────────────────────────────────────

    private fun client(): TenshuClient = JsRuntime.tenshuClient

    private fun service(): PhotoService = client().photoService

    /** `Read`'s value, or [onFailed] with the typed error — inline, so it may `return@promise`. */
    private inline fun <T> Read<T>.valueOr(onFailed: (TError) -> T): T = when (this) {
        is Read.Ok -> value
        is Read.Failed -> onFailed(error)
    }

    private fun unknownAlbum(albumId: String): dynamic = photosFailure(PhotosWebErrorKeys.UNKNOWN_ALBUM, albumId)

    /**
     * `null` when [albumId] names an album, else the failure to answer with. ⚠ A failed read stays a
     * failed read, never "unknown album".
     */
    private suspend fun albumCheck(albumId: String): dynamic {
        val albums = albumRows().valueOr { return photosFailure(it) }
        return if (albums.any { it.albumId.get() == albumId }) null else unknownAlbum(albumId)
    }

    private suspend fun write(op: suspend PhotoService.() -> TResult<*>): dynamic =
        service().op().fold(onSuccess = { okResult() }, onFailure = { photosFailure(it) })

    private suspend fun healHint(albumId: String, contentId: String, path: String) {
        // ⚠ The one deliberate swallow, as natively: a failed optimisation must not fail the read.
        try {
            service().addToAlbum(albumId, contentId, path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("Photos -> healing an album membership hint failed: ${e.message}")
        }
    }

    /**
     * One file by path. A missing folder or file is [Lookup.Missing]; a row without a capture time
     * too. A file that is not an image or video is [Lookup.NotMedia], never a photograph.
     */
    private suspend fun photoAt(path: String): Lookup =
        client().fileService.defaultFileSystem.root
            .files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            .select { photoRowProjection() }
            .resolve()
            .fold(
                onSuccess = { entity ->
                    when {
                        entity == null -> Lookup.Missing
                        entity.get(File.Category) !in MEDIA_CATEGORIES -> Lookup.NotMedia
                        else -> entity.photoOf()?.let { Lookup.Found(it) } ?: Lookup.Missing
                    }
                },
                onFailure = { error -> if (error.isNotFound()) Lookup.Missing else Lookup.Failed(error) },
            )

    /**
     * The photograph a write ([trash], [addToAlbum]) may act on, or the typed refusal: not found, not
     * a photograph (non-media, or already under `/Trash`), or no content id.
     */
    private suspend fun photoToWrite(path: String): Target {
        if (path == TRASH_ROOT || path.startsWith("$TRASH_ROOT/")) {
            return Target.Refused(photosFailure(PhotosWebErrorKeys.NOT_A_PHOTO, "in the trash: $path"))
        }
        val photo = when (val found = photoAt(path)) {
            is Lookup.Found -> found.value
            Lookup.Missing -> return Target.Refused(photosFailure(PhotosWebErrorKeys.NOT_FOUND, path))
            Lookup.NotMedia -> return Target.Refused(photosFailure(PhotosWebErrorKeys.NOT_A_PHOTO, path))
            is Lookup.Failed -> return Target.Refused(photosFailure(found.error))
        }
        if (photo.contentId.isEmpty()) return Target.Refused(photosFailure(PhotosWebErrorKeys.NO_CONTENT_ID, path))
        return Target.Ok(photo)
    }

    /** One feed walk, projected for photo rows — [feedPage]'s two halves. */
    private suspend fun feedRows(window: TList<File>.() -> TList<File>): Read<List<Entity<File>>> =
        client().fileService.defaultFileSystem.root
            .files
            .traverse()
            .window()
            .select { photoRowProjection() }
            .getAll()
            .fold(onSuccess = { Read.Ok(it.toList()) }, onFailure = { Read.Failed(it) })

    private fun Entity<File>.feedKey() = FeedKey(get(File.CreatedAt)?.get() ?: 0L, path.get())

    /**
     * Feed members whose content id is in [ids], newest first: one fused walk per
     * [CONTENT_ID_CHUNK] ids, never the shared index (`photos-app.md` §39). `null` if a walk failed.
     */
    private suspend fun libraryByContentIds(ids: List<String>): Read<List<PhotoJs>> {
        val wanted = ids.filter { it.isNotEmpty() }.distinct()
        val all = mutableListOf<PhotoJs>()
        for (chunk in wanted.chunked(CONTENT_ID_CHUNK)) {
            val rows = client().fileService.defaultFileSystem.root
                .files
                .traverse()
                .filter<File> { isMedia() }
                .filter<File> { createdAt.inRange(0L, Long.MAX_VALUE) }
                .filter<File> { notTrashed() }
                .filter<File> { contentId oneOf chunk }
                .sortByDescending(File.CreatedAt)
                .select { photoRowProjection() }
                .getAll()
                .fold(onSuccess = { Read.Ok(it.toList()) }, onFailure = { Read.Failed(it) })
                .valueOr { return Read.Failed(it) }
            all += rows.mapNotNull { it.photoOf() }
        }
        return Read.Ok(
            all.distinctBy { it.path }
                .sortedWith(compareByDescending<PhotoJs> { it.capturedAtMs }.thenBy { it.path }),
        )
    }

    // ⚠⚠ Each push form's projection must carry EVERY field its snapshot reads: `StreamRegistry`
    // compares the projected rows, so a change to an unprojected field (a rename, a healed path
    // hint, a new cover) emits nothing and the subscriber keeps the stale value.

    private fun albumRowsFlow(): Flow<*> =
        service().albums
            .select {
                fields(Album.Field.ALBUM_ID, Album.Field.NAME, Album.Field.CREATED_AT, Album.Field.COVER_CONTENT_ID)
            }
            .flow()

    private fun entryRowsFlow(): Flow<*> =
        service().albumEntries
            .select {
                fields(
                    AlbumEntry.Field.ALBUM_ID,
                    AlbumEntry.Field.CONTENT_ID,
                    AlbumEntry.Field.FILE_PATH,
                    AlbumEntry.Field.POSITION,
                )
            }
            .flow()

    private fun flagRowsFlow(): Flow<*> =
        service().photoFlags.select { fields(PhotoFlags.Field.CONTENT_ID, PhotoFlags.Field.FAVOURITE) }.flow()

    private fun trashRowsFlow(): Flow<*> =
        service().trashedPhotos
            .select {
                fields(
                    TrashedPhoto.Field.CONTENT_ID,
                    TrashedPhoto.Field.ORIGINAL_PATH,
                    TrashedPhoto.Field.TRASH_PATH,
                    TrashedPhoto.Field.TRASHED_AT,
                )
            }
            .flow()

    private suspend fun albumRows(): Read<List<Entity<Album>>> =
        service().albums
            .select {
                fields(Album.Field.ALBUM_ID, Album.Field.NAME, Album.Field.CREATED_AT, Album.Field.COVER_CONTENT_ID)
            }
            .getAll()
            .fold(onSuccess = { Read.Ok(it.toList()) }, onFailure = { Read.Failed(it) })

    private suspend fun entryRows(): Read<List<EntryRow>> =
        service().albumEntries
            .select {
                fields(
                    AlbumEntry.Field.ALBUM_ID,
                    AlbumEntry.Field.CONTENT_ID,
                    AlbumEntry.Field.FILE_PATH,
                    AlbumEntry.Field.POSITION,
                )
            }
            .getAll()
            .fold(
                onSuccess = { rows ->
                    Read.Ok(rows.toList().map {
                        EntryRow(it.albumId.get(), it.photoContentId.get(), it.filePath.get(), it.position.get())
                    })
                },
                onFailure = { Read.Failed(it) },
            )

    private suspend fun flagRows(): Read<List<Entity<PhotoFlags>>> =
        service().photoFlags
            .select { fields(PhotoFlags.Field.CONTENT_ID, PhotoFlags.Field.FAVOURITE, PhotoFlags.Field.HIDDEN) }
            .getAll()
            .fold(onSuccess = { Read.Ok(it.toList()) }, onFailure = { Read.Failed(it) })

    private suspend fun trashRows(): Read<List<TrashRow>> =
        service().trashedPhotos
            .select {
                fields(
                    TrashedPhoto.Field.CONTENT_ID,
                    TrashedPhoto.Field.ORIGINAL_PATH,
                    TrashedPhoto.Field.TRASH_PATH,
                    TrashedPhoto.Field.TRASHED_AT,
                )
            }
            .getAll()
            .fold(
                onSuccess = { rows ->
                    Read.Ok(rows.toList().map {
                        TrashRow(it.photoContentId.get(), it.originalPath.get(), it.trashPath.get(), it.trashedAt.get())
                    })
                },
                onFailure = { Read.Failed(it) },
            )

    /** The chosen cover, else the first entry by position — [Album.coverContentId]'s fallback. */
    private fun summariesOf(albums: List<Entity<Album>>, entries: List<EntryRow>): List<PhotoAlbumJs> =
        albums.map { album ->
            val id = album.albumId.get()
            val mine = entries.filter { it.albumId == id }.sortedBy { it.position }
            val coverId = album.coverContentId.get().takeIf { it.isNotEmpty() }
            val cover = mine.firstOrNull { it.contentId == coverId } ?: mine.firstOrNull()
            PhotoAlbumJs(
                albumId = id,
                name = album.name.get(),
                createdAtMs = album.albumCreatedAt.get().toDouble(),
                photoCount = mine.size,
                coverPath = cover?.filePath,
            )
        }.sortedByDescending { it.createdAtMs }

    private suspend fun albumsResult(): dynamic {
        val albums = albumRows().valueOr { return photosFailure(it) }
        val entries = entryRows().valueOr { return photosFailure(it) }
        return okResult { it.albums = summariesOf(albums, entries).toTypedArray() }
    }

    private suspend fun favouritesResult(): dynamic {
        val flags = flagRows().valueOr { return photosFailure(it) }
        val ids = flags.filter { it.favourite.get() }.map { it.photoContentId.get() }
        if (ids.isEmpty()) return okResult { it.photos = emptyArray<PhotoJs>() }
        val photos = libraryByContentIds(ids).valueOr { return photosFailure(it) }
        return okResult { it.photos = photos.toTypedArray() }
    }

    private suspend fun trashResult(): dynamic {
        val records = trashRows().valueOr { return photosFailure(it) }
        val items = mutableListOf<TrashedPhotoJs>()
        for (record in records.sortedByDescending { it.trashedAtMs }) {
            when (val found = photoAt(record.trashPath)) {
                is Lookup.Found -> items += TrashedPhotoJs(
                    contentId = record.contentId,
                    photo = found.value,
                    originalPath = record.originalPath,
                    trashedAtMs = record.trashedAtMs.toDouble(),
                )
                // ⚠ A record whose file is gone is dropped: its restore cannot work.
                Lookup.Missing, Lookup.NotMedia -> Unit
                is Lookup.Failed -> return photosFailure(found.error)
            }
        }
        return okResult { it.items = items.toTypedArray() }
    }
}

private sealed interface Lookup {
    class Found(val value: PhotoJs) : Lookup
    data object Missing : Lookup
    data object NotMedia : Lookup
    class Failed(val error: TError) : Lookup
}

private sealed interface Target {
    class Ok(val photo: PhotoJs) : Target
    class Refused(val failure: dynamic) : Target
}

private val MEDIA_CATEGORIES = setOf(FileCategory.Image, FileCategory.Video)

private class EntryRow(val albumId: String, val contentId: String, val filePath: String, val position: Long)

private class TrashRow(val contentId: String, val originalPath: String, val trashPath: String, val trashedAtMs: Long)
