package app.tenshu.photos.data

import androidx.paging.PagingData
import app.tenshu.ingest.IndexedContent
import app.tenshu.ingest.RealmContentReads
import app.tenshu.photos.data.TenshuPhotosRepository.Companion.DEFAULT_PAGE_SIZE
import app.tenshu.photos.model.FaceGroup
import app.tenshu.photos.model.FaceRegion
import app.tenshu.photos.model.GroupFace
import app.tenshu.photos.model.NamingOutcome
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoDetails
import app.tenshu.photos.model.PhotoLabel
import app.tenshu.photos.model.PhotoPlace
import app.tenshu.photos.model.SemanticSearchStatus
import app.tenshu.photos.model.photoPlaceOrNull
import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.UnreachableReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.collection.list.changes
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.count
import org.tenshu.core.collection.list.drop
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.util.bestEffort
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.contains
import org.tenshu.core.core.ListBuilder
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.equalTo
import org.tenshu.core.file.CameraMake
import org.tenshu.core.file.CameraModel
import org.tenshu.core.file.CaptureLatE7
import org.tenshu.core.file.CaptureLngE7
import org.tenshu.core.file.Category
import org.tenshu.core.file.ContentHash
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.Data
import org.tenshu.core.file.DurationMs
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.Folder
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.MediaMetadata
import org.tenshu.core.file.Orientation
import org.tenshu.core.file.PixelHeight
import org.tenshu.core.file.PixelWidth
import org.tenshu.core.TByteArray
import org.tenshu.core.relation.Property
import org.tenshu.core.file.Preview
import org.tenshu.core.file.Size
import org.tenshu.core.file.TRASH_ROOT
import org.tenshu.core.file.Thumbnail
import org.tenshu.core.file.ThumbnailLarge
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.core.file.ThumbnailXXLarge
import org.tenshu.core.file.ViewerPreview
import org.tenshu.core.file.ThumbnailMotion
import org.tenshu.core.file.isMedia
import org.tenshu.core.file.isTrashedPath
import org.tenshu.core.file.createdAt
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.file.files
import org.tenshu.core.file.category
import org.tenshu.core.file.contentId
import org.tenshu.core.oneOf
import org.tenshu.core.file.name
import org.tenshu.core.file.notTrashed
import org.tenshu.core.file.path
import org.tenshu.core.file.readMediaMetadata
import org.tenshu.core.file.system.AlreadyExistsError
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.ensureThumbnailClass
import org.tenshu.core.file.system.hasLocalData
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.fold
import org.tenshu.core.getOrNull
import org.tenshu.core.ifEmptyError
import org.tenshu.core.inRange
import org.tenshu.core.paging.keysetPagingSource
import org.tenshu.core.paging.seekablePagingConfig
import org.tenshu.core.place.Place
import org.tenshu.core.place.city
import org.tenshu.core.place.label
import org.tenshu.core.place.latE7
import org.tenshu.core.place.lngE7
import org.tenshu.core.place.placeId
import org.tenshu.core.relation.eager
import org.tenshu.core.resolve
import org.tenshu.file.client.asksForHeroByName
import org.tenshu.file.client.fileService
import org.tenshu.inference.FaceGroupsUnavailable
import org.tenshu.inference.INDEX_MARKER_LABEL
import org.tenshu.inference.MediaLabel
import org.tenshu.inference.SemanticSearchUnavailable
import org.tenshu.inference.client.inferenceService
import org.tenshu.inference.capability
import org.tenshu.inference.confidencePerMille
import org.tenshu.inference.filePath
import org.tenshu.inference.label
import org.tenshu.inference.faceGroups
import org.tenshu.inference.nameFaceGroup
import org.tenshu.inference.photosLike
import org.tenshu.photo.Album
import org.tenshu.photo.AlbumEntry
import org.tenshu.photo.PhotoFlags
import org.tenshu.photo.PhotoService
import org.tenshu.photo.TrashedPhoto
import org.tenshu.photo.addToAlbum
import org.tenshu.photo.albumId
import org.tenshu.photo.clearTrashedPhoto
import org.tenshu.photo.client.photoService
import org.tenshu.photo.contentId
import org.tenshu.photo.coverContentId
import org.tenshu.photo.createdAt
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
import org.tenshu.place.client.placeService

/**
 * The Photos feed, read from the **Control** proxy's `:file-service` over IPC
 * (`photos-app.md` §9.1 — the shape `PhotosFeedChainTest` pins).
 *
 * ⚠⚠ **Not `photosCapturedBetween`.** That op and its sibling `photosNear` exist for Timeline and
 * Mia, and their own KDocs are explicit about the trade they made: an O(files-in-realm) scan
 * returning three scalars, with no paging and no index. A feed pages continuously, so it needs the
 * chain — which can express the window, the ordering and the bound, and which streams.
 *
 * ⚠ The page is still a full-tree walk (`traverse()`), and what one costs at 20 000 photos is an
 * open device measurement, not something a unit test can answer. Until it is run,
 * [DEFAULT_PAGE_SIZE] is a guess made on the safe side.
 */
class TenshuPhotosRepository(
    private val client: TenshuClient,
    /**
     * The app's reachability engine, **read from and never written to**. Failures are classified
     * from `proxyConnected` by [pageReadFailure] and its siblings; successes say nothing, because
     * the executor classifies the round trip and `answered` wakes the probe.
     * `ControlReachability.markReachable`/`markUnreachable` carry why neither is a repository's
     * call to make.
     */
    // ⚠ Required, never defaulted (ADR 0025 §8.6): `null` only where no engine exists (tests), stated there.
    private val reachability: ControlReachability?,
    /**
     * Where a new album's id gets its uniqueness. ⚠ A parameter because this module has no clock
     * dependency of its own and a test must be able to make an id predictable.
     */
    private val clockMs: () -> Long = { 0L },
    /**
     * Where the library watch keeps its observers ([libraryWatch]) — `null`, the default, and there is
     * no watch: Collections re-reads on every return, as the Compose shells have it today.
     */
    private val watchScope: CoroutineScope? = null,
) : PhotosRepository {

    // ── what Collections reads, as chains (`photos-ios.md` §8.22.3) ─────────────────────────────────
    //
    // ⚠⚠ ONE definition each, used by the read AND by the watch on it ([libraryWatch]): the watch
    // observes exactly what the read reads, so a field the read selects cannot change unobserved.
    // ⚠ Why not the bare lists: a `File` observed bare diffs by its path alone, so a change in
    // place signals nothing (`drive-sorted-folder-paging.md` §3 B5). Whether these five lists share
    // that is NOT established — `RealRepositoryReadTest` passes with albums and flags observed bare
    // — and observing the read's own projection is what makes the answer not matter.

    private fun albumRows() = client.photoService.albums
        .select {
            fields(
                Album.Field.ALBUM_ID,
                Album.Field.NAME,
                Album.Field.CREATED_AT,
                Album.Field.COVER_CONTENT_ID,
            )
        }

    private fun placeRows() = client.placeService.places
        .select {
            fields(
                Place.Fields.PLACE_ID,
                Place.Fields.LABEL,
                Place.Fields.CITY,
                Place.Fields.LAT_E7,
                Place.Fields.LNG_E7,
            )
        }

    private fun entryRows() = client.photoService.albumEntries
        .select {
            fields(
                AlbumEntry.Field.ALBUM_ID,
                AlbumEntry.Field.CONTENT_ID,
                AlbumEntry.Field.FILE_PATH,
                AlbumEntry.Field.POSITION,
            )
        }

    private fun flagRows() = client.photoService.photoFlags
        .select {
            fields(
                PhotoFlags.Field.CONTENT_ID,
                PhotoFlags.Field.FAVOURITE,
                PhotoFlags.Field.HIDDEN,
            )
        }

    private fun labelRows() = client.inferenceService.labels
        .select {
            fields(
                MediaLabel.Fields.FILE_PATH,
                MediaLabel.Fields.LABEL,
                MediaLabel.Fields.CONFIDENCE_PER_MILLE,
                // ⚠⚠ Needed to tell a person's NAME from a model's TAG.
                // Unselected, every name typed into People also becomes a Things card.
                MediaLabel.Fields.CAPABILITY,
            )
        }

    private val watch = MutableLibraryWatch()

    /** How many of [watchedLists] have a live observer, and how many collected feeds do. */
    private val listsObserved = MutableStateFlow(0)
    private val feedsObserved = MutableStateFlow(0)
    private val watchStarted = MutableStateFlow(false)

    /**
     * Whether what the Collections tab reads has changed (`photos-ios.md` §8.22.3).
     *
     * Two kinds of source, because they cost differently:
     * - **The albums, their entries, the flags, the places, the labels** — small lists, each observed
     *   with a signal-only stream of its own, started on the first call and kept for the process.
     * - **The files** — NOT observed again. The feed already observes the whole tree, a second
     *   signal-only stream would re-evaluate it per change (`tech-debt.md`), and two observers of one
     *   chain share a stream id. The feed's own reload signal is taken instead ([feedPaging]).
     *
     * ⚠⚠ So [LibraryWatch.watching] needs a COLLECTED feed. With none — Collections opened before the
     * Library was ever built — it is false, and the tab re-reads on every return as it always did.
     * The promise is therefore exact: **Collections is never staler than the Library's own grid.**
     * ⚠ A file changed in place (same path) signals nothing to either; nothing Collections draws
     * from a file changes in place today.
     * ⚠ `faceGroups` has no list to observe — the clusters are this device's. What moves them here is
     * the indexer, which writes a marker label per file it indexes, and labels are observed.
     *
     * `null` without a [watchScope]: nowhere to keep the observers, so nothing is promised.
     */
    override fun libraryWatch(): LibraryWatch? {
        val scope = watchScope ?: return null
        if (watchStarted.compareAndSet(expect = false, update = true)) {
            val lists = listOf(albumRows(), entryRows(), flagRows(), placeRows(), labelRows())
            lists.forEach { list -> scope.launch { observe(list) } }
            scope.launch {
                try {
                    combine(listsObserved, feedsObserved) { observed, feeds -> observed == lists.size && feeds > 0 }
                        .collect { watch.setWatching(it) }
                } finally {
                    // ⚠⚠ The scope is gone (a teardown) and the observers with it: a view model still
                    // holding this repository must not go on believing a watch nobody keeps.
                    watch.setWatching(false)
                }
            }
        }
        return watch
    }

    /**
     * One list's changes into the watch. ⚠ The first tick is the subscribe confirmation — from then
     * on a change cannot be missed — and every later one is a change, a re-subscribe after a failure
     * included (`changes()` ticks then, because arbitrary changes were missed while it was down).
     *
     * ⚠⚠ A throw is caught HERE: [watchScope] has no handler, and an uncaught
     * exception on Kotlin/Native ends the process. The list is then simply unobserved — `watching`
     * goes false and stays false, the safe direction: every return walks.
     */
    private suspend fun <T : Data> observe(list: TList<T>) {
        var live = false
        try {
            list.changes(emitOnSubscribe = true).collect {
                if (live) {
                    watch.changed()
                } else {
                    live = true
                    listsObserved.update { it + 1 }
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("TenshuPhotosRepository -> a library watch observer ended, the watch stops vouching: $e")
        } finally {
            if (live) listsObserved.update { it - 1 }
        }
    }

    override fun controlReachable(): Flow<Boolean?> = reachability?.state ?: flowOf(true)

    override fun controlUnreachableReason(): kotlinx.coroutines.flow.StateFlow<UnreachableReason?> =
        reachability?.reason ?: app.tenshu.reachability.NO_UNREACHABLE_REASON

    override fun recheckControl() {
        reachability?.recheck()
    }

    /**
     * The feed as a `PagingData` stream (`core-keyset-paging.md` K-4).
     *
     * ⚠⚠ The cursor arithmetic belongs to `:core-paging`, not to this app — [feedWindow] states
     * the window and nothing here counts ties, guards an in-flight load or merges on resume. Every
     * one of those was a defect while Photos owned it (`core-keyset-paging.md` §6.3).
     *
     * ⚠⚠ **Placeholders and jumping are ON**. The scrubber's track is the whole library
     * rather than the loaded window, and a long drag re-anchors in one read instead of walking
     * every page down to it. Both come from the same triple — `seekablePagingConfig()` plus a
     * [feedTotal] — which `:core-paging` refuses to accept half of.
     */
    override fun feedPaging(pageSize: Int, readContext: kotlin.coroutines.CoroutineContext): Flow<PagingData<Photo>> {
        // ⚠ The feed's change observer doubles as the library watch's file signal ([libraryWatch]):
        // its first reload is "observing from here", every later one is "the files changed".
        var observing = false
        val onReload: (() -> Unit)? = if (watchScope == null) null else {
            {
                if (observing) {
                    watch.changed()
                } else {
                    observing = true
                    feedsObserved.update { it + 1 }
                }
            }
        }
        return client.fileService.defaultFileSystem.root
            .files
            .traverse()
            .keysetPagingSource(
                mapper = { row -> row.toPhotoOrNull() },
                // ⚠ Read off the PROJECTED row, not off the mapped `Photo`: `toPhotoOrNull` is
                // partial (a row with no capture time is dropped), and a page it empties still has
                // to advance the cursor or the pager stalls on one window forever.
                cursorOf = { row -> row.get(File.CreatedAt)?.get() ?: 0L },
                pagingConfig = seekablePagingConfig(pageSize = pageSize),
                totalCount = { feedTotal() },
                window = { list, before -> list.feedWindow(before) },
                projection = { window -> window.select { feedRowProjection() } },
                readContext = readContext,
                onReload = onReload,
            )
            .onCompletion {
                if (observing) {
                    observing = false
                    feedsObserved.update { it - 1 }
                }
            }
    }

    /**
     * How many photographs the feed holds — the length of the scrubber's track.
     *
     * ⚠⚠ Counted over [feedMembers] — the *pages'* own predicate — not over the content index and
     * not over a looser filter. A total that disagrees with the rows renders, under placeholders,
     * as tiles that never fill.
     *
     * ⚠ "Has a capture time" is part of that predicate rather than a client-side afterthought:
     * `inRangeLogic` treats an absent value as no match. That is what lets this be a count instead
     * of a count-then-filter.
     *
     * ⚠ Ends in `count().resolve()` so **one integer** crosses the wire (`core/Resolve.kt`). Reading
     * the rows to count them costs a path per photograph over a 1 MB Binder transaction, which is
     * a ceiling as well as a cost.
     *
     * ⚠⚠ **`null`, never 0.** `:core-paging` caches the first non-null answer as the list's length,
     * so a 0 says *the library is empty* and collapses the scrubber's track, where `null` says
     * *no placeholders this generation* — the honest answer to a count that did not come back.
     *
     * ⚠ A Control that lacks `resolve` answers `null` here, and the announce does **not** fence it:
     * adding an operation bumps no schema version, so the session is admitted and the call fails at
     * *dispatch* instead. A device reading of `null` means rebuild the PROXY, not this app.
     */
    internal suspend fun feedTotal(): Int? {
        return client.fileService.defaultFileSystem.root
            .files
            .traverse()
            .feedMembers()
            .count()
            .resolve()
            .getOrNull()
            ?.get()
            ?.toInt()
    }

    /**
     * Every capture time in the feed, newest first.
     *
     * ⚠⚠ Read over [feedWindow] for the reason [feedTotal] is counted over [feedMembers]: pills
     * built from a looser filter land where the feed has no photographs.
     *
     * ⚠⚠ **Ordered by the chain, not here.** `feedYearMarks` deliberately does not sort, so an
     * unordered list produces visibly wrong marks rather than silently wrong ones — which makes
     * [feedWindow]'s `sortByDescending` load-bearing.
     *
     * ⚠ `null`, never empty — both draw no pills, and only the caller can tell "no photographs"
     * from "the read did not come back". Same op-skew caveat as [feedTotal].
     */
    override suspend fun feedCaptureTimes(): List<Long>? {
        return client.fileService.defaultFileSystem.root
            .files
            .traverse()
            .feedWindow(null)
            .select { relations(File.CreatedAt) }
            .getAll()
            .getOrNull()
            // ⚠ `toList()` first: the terminal answers a `TList`, not a Kotlin collection.
            ?.toList()
            ?.mapNotNull { row -> row.get(File.CreatedAt)?.get() }
    }

    /**
     * The feed's window: every image in the realm captured at or before [beforeMs], newest first.
     *
     * ⚠⚠ **Inclusive of [beforeMs]**, which is what `:core-paging`'s keyset cursor requires — an
     * exclusive bound silently loses every photograph sharing the boundary millisecond, so a burst
     * or a bulk import simply vanishes from the feed. The rows the previous page already served at
     * that millisecond come back at the head of this window and the pager's skip drops them.
     *
     * ⚠⚠ `inRange` is half-open (`start <= v < end`), so the inclusive bound is [exclusiveEndFor]'s
     * `+ 1` and not the parameter as given. Pinned by
     * `theFeedWindowIncludesItsCursor_soTiedPhotographsSurviveAPageBoundary`.
     *
     * ⚠ Shared by [feedPaging] and [feedPage] rather than written twice, and `MockPhotosRepository`
     * has to agree with it — the two diverging by one `<` has cost photographs before.
     */
    private fun TList<File>.feedWindow(beforeMs: Long?): TList<File> = this
        // ⚠ Separate filter steps because separate steps are an **and**; the one place this feed
        // needs an *or* — image or video — is inside `isMedia()`, over the `oneOf` predicate.
        .filter<File> { isMedia() }
        .filter<File> { createdAt.inRange(0L, exclusiveEndFor(beforeMs)) }
        // ⚠⚠ The trash is a realm folder, so `traverse()` walks it like any other. Excluded
        // IN the chain, because filtering client-side after the page would hand the pager a short
        // page and end the feed.
        .filter<File> { notTrashed() }
        .sortByDescending(File.CreatedAt)

    /**
     * The feed's membership test on its own — every predicate, no ordering.
     *
     * ⚠ Split out of [feedWindow] so [feedTotal] can count without sorting — the ordering is what
     * a *page* needs, and it is a third of the count's cost. ⚠⚠ One function rather than two copies
     * of the predicate: a count that filtered differently from the rows renders, under
     * placeholders, as tiles that never fill.
     */
    private fun TList<File>.feedMembers(beforeMs: Long? = null): TList<File> = this
        .filter<File> { isMedia() }
        .filter<File> { createdAt.inRange(0L, exclusiveEndFor(beforeMs)) }
        .filter<File> { notTrashed() }

    /**
     * The half-open end that makes [feedWindow] closed at [beforeMs].
     *
     * ⚠ `inRange` is `start <= v < end`, so asking for "captured at or before the cursor" means
     * passing `cursor + 1`. Doing the arithmetic here rather than at the call site keeps the one
     * place that has to know about the half-open bound next to the reason it exists.
     *
     * ⚠⚠ `Long.MAX_VALUE` is the "from the head" sentinel and must NOT be widened — `+ 1` overflows
     * to `Long.MIN_VALUE` and the window would match nothing at all, turning the first page of the
     * feed empty. No real capture time is `Long.MAX_VALUE`, so leaving it exclusive loses nothing.
     */
    private fun exclusiveEndFor(beforeMs: Long?): Long {
        val cursor = beforeMs ?: Long.MAX_VALUE
        return if (cursor == Long.MAX_VALUE) cursor else cursor + 1
    }

    /**
     * What a feed row needs, as one projection — shared so a row cannot mean two things.
     *
     * ⚠ `fields(PATH)` is not optional: the streaming terminal derives each row's DataId from the
     * path, so omitting it fails with "Field tenshu/file/path not found in caller".
     */
    private fun ListBuilder<Selector<File>>.feedRowProjection() {
        fields(File.Field.PATH)
        // ⚠ `ContentId` is not decoration: it is the key every album write uses, and a feed row
        // without it can be shown but not filed.
        relations(
            File.CreatedAt,
            File.Category,
            File.ContentId,
            // M1: the tile's duration badge. Cheap — a scalar already on the row, and the read is
            // the same one the feed was making.
            File.DurationMs,
            // The Places section groups on these, in one pass over the feed rather than one
            // `photosNear` scan per place.
            File.CaptureLatE7,
            File.CaptureLngE7,
            // §16: the tile's crop decides which thumbnail class fills it, and that is the photo's
            // aspect ratio. Two scalars, the same weight as the coordinates above.
            File.PixelWidth,
            File.PixelHeight,
        )
    }

    /**
     * [feedRowProjection] less what an images-only read already knows: no `Category` (every row is
     * an image) and no `DurationMs` (a still has none). ⚠ Fewer properties is fewer bytes per row,
     * and bytes are what size a library page (§39) — `toPhotoOrNull` reads the two as absent, which
     * is exactly what they are on an image.
     */
    private fun ListBuilder<Selector<File>>.libraryRowProjection() {
        fields(File.Field.PATH)
        relations(
            File.CreatedAt,
            File.ContentId,
            File.CaptureLatE7,
            File.CaptureLngE7,
            File.PixelWidth,
            File.PixelHeight,
        )
    }

    override suspend fun feedPage(beforeMs: Long, skip: Int, pageSize: Int): PhotoPageResult {
        if (pageSize <= 0) return PhotoPageResult.Page(emptyList(), hasMore = false)

        return client.fileService.defaultFileSystem.root
            .files
            .traverse()                                              // every file in the realm
            .feedWindow(beforeMs)                                    // the keyset window
            // ⚠⚠ The tie cursor. `inRange` above is INCLUSIVE of `beforeMs` — deliberately, so
            // photographs sharing the boundary millisecond are not lost — which means the rows
            // the caller already holds at that millisecond come back at the head of this page.
            // Dropping exactly those is what lets the cursor move.
            //
            // ⚠ Relies on the sort being STABLE, so the same tied rows come back in the same
            // order — which `sortByDescending` guarantees on both of its paths.
            // ⚠ This page is a fused thin walk only while [feedWindow]'s predicates stay fusable —
            // `filter`'s KDoc says what that means.
            .drop(skip)
            .take(pageSize)
            .select { feedRowProjection() }
            .getAll()
            .fold(
                onSuccess = { rows ->
                    val photos = rows.toList().mapNotNull { it.toPhotoOrNull() }
                    PhotoPageResult.Page(photos, hasMore = photos.size >= pageSize)
                },
                onFailure = { error ->
                    // ⚠⚠ **This arm is the dead-transport path** — the executor folds an
                    // unreachable proxy into a typed `TResult.Failure` rather than throwing,
                    // so there is nothing left for a `catch` to do. It classifies rather than
                    // reporting an outage; see [pageReadFailure].
                    pageReadFailure(
                        proxyConnected = client.proxyConnected.value,
                        detail = error.toString(),
                    )
                },
            )
    }

    /**
     * ⚠ Two reads, not one projection of both, and the order matters — the recipe
     * `TenshuFilesRepository.getPreview` established for `SyncStrategy.OnDemand`: read
     * `eager(File.Preview(w, h))`, and on a null *pull* the content-plane thumbnail into the local
     * cache and ask again. `File.Preview` will not fetch on its own.
     *
     * ⚠⚠ **Except above `LARGE`, where a device without the original asks for the hero rung by name
     * first.** Pulling only on a null read left it unreachable: the grid has already pulled `LARGE`
     * for the same photograph, so `GetPreview` answers a 1024 px request with that 256 px class —
     * its best *local* answer, never null — and nothing ever asked for `XLARGE`. Every viewer page
     * was an upscale while the hero rung sat on the content plane (`drive-synced-thumbnails.md` §16).
     *
     * ⚠ Where the rule lives, and its two cases, are [asksForHeroByName].
     *
     * ⚠ When that ask comes back empty the answer is `File.Preview`'s best local class, marked
     * [PhotoPreview.standIn] so nothing caches it as the answer to a hero-sized request.
     */
    override suspend fun preview(path: String, width: Int, height: Int): PhotoPreview? {
        val wantsHero = asksForHeroByName(maxOf(width, height), path) { holdsOriginal(it) }
        // ⚠ Empty bytes are no answer (`photos-app.md` §36) — they fall through to the next read,
        // exactly as `null` does, instead of becoming a blank image.
        if (wantsHero) {
            heroThumbnail(path)?.takeIf { it.isNotEmpty() }?.let { return PhotoPreview(it) }
        }
        val bytes = readPreview(path, width, height)?.takeIf { it.isNotEmpty() } ?: run {
            cacheThumbnails(path)
            readPreview(path, width, height)?.takeIf { it.isNotEmpty() }
        } ?: return null
        return PhotoPreview(bytes, standIn = wantsHero)
    }

    /**
     * §4.5 of `drive-xxlarge-thumbnail-rung.md` — **the best picture of this photograph, as soon as
     * possible**, as a stream rather than one answer:
     *
     * 1. the best **local** answer at once, marked [PhotoPreview.standIn] when a larger rung is
     *    still wanted, so nothing remembers it as the answer to a hero-sized request;
     * 2. the named rung — `XXLARGE`, falling back to `XLARGE` — pulled and emitted after it lands.
     *
     * ⚠⚠ **An upgrade identical to what is already on screen is not emitted.** Step 1 can itself be
     * served the local `XXLARGE`, and it returns bare bytes, so the flow cannot tell which class
     * answered; step 2's local-first select would then hand back the very same blob and the UI would
     * decode 8–16 MiB twice for one picture. The bytes are compared before emitting.
     *
     * ⚠⚠ **Step 1 is [File.ViewerPreview], not `File.Preview`** — D-17. On the device holding the
     * original that is the difference between its own photograph re-rendered at the size asked for
     * and the 2048 px rung stretched up to it; on every other device the two are identical, which is
     * why there is no `HasLocalData` probe here (`File.ViewerPreview`'s KDoc).
     *
     * ⚠ Mint-on-view — §4.5's step 3, for a photograph no device ever minted a rung for — runs
     * last, once [mayMint] licenses it (step 3 in the body).
     */
    override fun viewerPreview(
        path: String,
        requestPx: Int,
        mayMint: Flow<Unit>,
    ): Flow<PhotoPreview> = flow {
        val wantsRung = asksForHeroByName(requestPx, path) { holdsOriginal(it) }

        // ⚠⚠ **At or below the hero rung there is nothing to be progressive about**, and being
        // progressive there is actively wrong: the class that answers a 1024 px request *is* the
        // class this would pull, and `covering` serves the **smallest** class that covers a
        // request. So a device holding `XXLARGE` but not `XLARGE` would draw its 2048 px copy, pull
        // `XLARGE`, and then correctly prefer the 1024 px class that had just arrived — a visible
        // redraw to a softer picture, plus a relay fetch, to end up where one read would have. Pull
        // first and answer once, as the one-shot `preview()` does.
        if (wantsRung && requestPx <= GetStoredThumbnail.XLARGE_PX) {
            pullHeroRung(path, requestPx)
            val answer = readViewerPreview(path, requestPx)
                ?: run {
                    cacheThumbnails(path)
                    readViewerPreview(path, requestPx)
                }
            // ⚠ Not a stand-in: the rung it was waiting for has already been asked for.
            answer?.let { emit(PhotoPreview(it)) }
            return@flow
        }

        val local = readViewerPreview(path, requestPx)
            ?: run {
                cacheThumbnails(path)
                readViewerPreview(path, requestPx)
            }
        local?.let { emit(PhotoPreview(it, standIn = wantsRung)) }

        if (!wantsRung) return@flow
        pullHeroRung(path, requestPx)

        // ⚠⚠ **Ask the ladder again; never emit the pulled blob directly.** The pull makes a class
        // *reachable*; which class should be drawn is `StoredThumbnailLadder`'s decision, and it is
        // the only thing that can answer it — this layer gets bare bytes and cannot tell a 1024 px
        // class from a 2048 px one. ⚠ Above the hero rung the two agree: the class pulled here is
        // the largest that exists, so the re-read can only equal it or better it.
        val pulled = readViewerPreview(path, requestPx)
        var shown = local
        if (pulled != null && (shown == null || !shown.contentEquals(pulled))) {
            shown = pulled
            emit(PhotoPreview(pulled))
        }

        // ⚠⚠ **Step 3 — mint on view, and it belongs *in this flow*.** A parallel path writing into
        // its own UI state breaks three ways at once: on a device holding the original it replaces
        // a render at the requested size with the smaller stored rung; it re-decodes bytes this
        // flow has already drawn, because the comparison below never sees them; and when steps 1–2
        // produced nothing — mint-on-view's *own* headline case — its result is unreachable and the
        // page keeps saying the photograph is not downloaded. One emission channel, one comparison,
        // one ordering.
        //
        // ⚠ The wait is the caller's: `mayMint` emits when the page has been settled long enough to
        // count as looked at (D-18). Nothing is spent before it does, and a collector that never
        // emits — a neighbour composed ahead, the default — mints nothing.
        //
        // ⚠⚠ **A licence that completes without emitting means "never", and it is the only way this
        // flow reaches its `Absent` verdict.** The default `emptyFlow()` completes at once; the
        // viewer's completes after its single emission. A licence that stayed open after emitting
        // would suspend this collector for ever and the page would spin instead of resolving —
        // which is why the viewer's ends in `take(1)` rather than relying on `firstOrNull` alone.
        // ⚠ A page that never settles never resolves here, deliberately: it is off-screen, and the
        // pager settles every page the user actually lands on.
        mayMint.firstOrNull() ?: return@flow
        ensureViewerRung(path)

        // ⚠⚠ Re-read rather than draw what the mint returned, for the reason step 2 does — and here
        // it matters more: this device now **holds the original**, so the ladder may render at the
        // full requested size, which beats the 2048 px rung the mint just wrote.
        val afterMint = readViewerPreview(path, requestPx) ?: return@flow
        if (shown != null && shown.contentEquals(afterMint)) return@flow
        emit(PhotoPreview(afterMint))
    }

    override suspend fun ensureViewerRung(path: String, userRequested: Boolean): ByteArray? =
        client.fileService.defaultFileSystem
            .ensureThumbnailClass(
                path = path,
                thumbnailClass = GetStoredThumbnail.CLASS_XXLARGE,
                userRequested = userRequested,
            )
            .getOrNull()
            ?.get()

    /** O(1), no payload read. ⚠ A failed probe reads as *not held*, which only costs a pull. */
    private suspend fun holdsOriginal(path: String): Boolean =
        client.fileService.defaultFileSystem.hasLocalData(path).getOrNull()?.get() == true

    /**
     * The hero class itself: selecting the relation answers from the local copy when there is one
     * and pulls it otherwise, as [motionClip] does for its class. `null` when no device ever minted
     * one (an upload the backfill has not reached) or the fetch failed — the caller
     * then falls back to the best class `File.Preview` has.
     */
    private suspend fun heroThumbnail(path: String): ByteArray? = namedRung(path, File.ThumbnailXLarge)

    /**
     * Names the rung the **viewer** wants — `XXLARGE` first, `XLARGE` behind it (§4.4) — for its
     * side effect only: the class lands locally and the ladder picks it up on the next read.
     *
     * ⚠⚠ `XXLARGE` only above [GetStoredThumbnail.XLARGE_PX]. It is ~0.5 MB on the wire, so naming it
     * for anything tile-sized is §14.2's trap at three times the cost — "never a grid tile, never a
     * feed row". The viewer's own floor is the hero rung ([heroPreviewRequestPx]), so in practice the
     * fallback arm is for a request that landed exactly on it.
     *
     * ⚠ The fallback is not a failure path: an older photograph, on a realm no device has
     * backfilled, has an `XLARGE` and no `XXLARGE`. ⚠⚠ But it is also not a *preference* — what
     * gets drawn afterwards is whatever the ladder then considers best, which is why this returns
     * nothing at all.
     */
    private suspend fun pullHeroRung(path: String, requestPx: Int) {
        if (requestPx > GetStoredThumbnail.XLARGE_PX) {
            namedRung(path, File.ThumbnailXXLarge)?.let { return }
        }
        namedRung(path, File.ThumbnailXLarge)
    }

    /**
     * Selecting the relation answers from the local copy when there is one and pulls it otherwise,
     * as [motionClip] does for its class. `null` when no device ever minted one (an upload the
     * backfill has not reached) **or** the fetch failed — the caller keeps whatever it already had.
     *
     * ⚠⚠ Those two cases are deliberately not distinguished here, and mint-on-view is where that is
     * not good enough: minting a rung locally on the strength of a *failed read* would turn a flaky
     * relay into a 6–18 MB original download and a duplicate mint (§4.5). That needs the typed
     * channel, not `getOrNull()`.
     */
    private suspend fun namedRung(path: String, property: Property.Dynamic<TByteArray>): ByteArray? =
        client.fileService.defaultFileSystem.root
            .files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            .select { relations(property) }
            .resolve()
            .getOrNull()
            ?.get(property)
            ?.getOrNull()
            // ⚠ Empty bytes are no answer (`photos-app.md` §36) — every read path, the viewer's included.
            ?.takeIf { it.isNotEmpty() }

    /** [readPreview]'s twin on D-17's property — the viewer's read (`File.ViewerPreview`). */
    private suspend fun readViewerPreview(path: String, requestPx: Int): ByteArray? =
        client.fileService.defaultFileSystem.root
            .files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            .select { relations(eager(File.ViewerPreview(width = requestPx, height = requestPx))) }
            .resolve()
            .getOrNull()
            ?.get(File.ViewerPreview)
            ?.getOrNull()
            // ⚠ Empty bytes are no answer (`photos-app.md` §36) — every read path, the viewer's included.
            ?.takeIf { it.isNotEmpty() }

    /**
     * ⚠ One read, not the pull-then-read pair [preview] needs: `File.ThumbnailMotion` is the blob
     * itself, not a resolver over classes, so selecting the relation both pulls the OnDemand blob
     * into the local cache and answers with it — the same select `cacheThumbnails` issues for the
     * stills, whose values it discards.
     */
    override suspend fun motionClip(path: String): ByteArray? =
        client.fileService.defaultFileSystem.root
            .files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            .select { relations(File.ThumbnailMotion) }
            .resolve()
            .getOrNull()
            ?.get(File.ThumbnailMotion)
            ?.getOrNull()

    private suspend fun readPreview(path: String, width: Int, height: Int): ByteArray? =
        client.fileService.defaultFileSystem.root
            .files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            // `eager(...)` is load-bearing: `File.Preview` is deferred by default, and without the
            // override every tile in the grid resolves to null.
            .select { relations(eager(File.Preview(width = width, height = height))) }
            .resolve()
            .getOrNull()
            ?.get(File.Preview)
            ?.getOrNull()
            // ⚠ Empty bytes are no answer (`photos-app.md` §36) — every read path, the viewer's included.
            ?.takeIf { it.isNotEmpty() }

    /**
     * Fetch the thumbnail blobs into the local cache. The values are discarded: the point is the
     * side effect (`retention-quota-model.md` tier 3 — losing them is a re-download, not data loss).
     *
     * ⚠⚠ **This and [heroThumbnail] are the only things that ask for a rung.** Every class is
     * `Syncable(OnDemand)` and `GetPreview` resolves them *locally* without fetching, so a rung no
     * caller names never reaches a device that did not upload the photo
     * (`drive-synced-thumbnails.md` §14). The hero rung is not pulled here: [preview] asks for it
     * first whenever the request could use it.
     */
    private suspend fun cacheThumbnails(path: String) {
        client.fileService.defaultFileSystem.root
            .files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            .select { relations(File.Thumbnail, File.ThumbnailLarge) }
            .resolve()
    }


    // ── albums ───────────────────────────────────────────────────────────────────────────────

    override suspend fun albums(): AlbumsResult = albumSummaries(withCoverShapes = true)

    /**
     * ⚠ [withCoverShapes] reads each cover's pixel size (§16) — one fused walk over the covers'
     * content ids (§39). The viewer's About panel lists albums by name and must not pay for it.
     */
    private suspend fun albumSummaries(withCoverShapes: Boolean): AlbumsResult {
        val albums = albumRows()
            .getAll()
            .fold(onSuccess = { it.toList() }, onFailure = { null })
            ?: return albumsReadFailure(client.proxyConnected.value, "could not read albums")

        val entries = allEntries()
            ?: return albumsReadFailure(client.proxyConnected.value, "could not read album entries")
        // ⚠ The cover's shape, for its card's thumbnail class (§16) — ONE fused walk over the covers'
        // content ids, never a read per album and never the shared index (§39: the index is the
        // whole realm in one transaction). A failed walk only costs the cards their shape: they
        // guess 4:3.
        val shapes = if (withCoverShapes) coverShapes(albums, entries) else null

        return AlbumsResult.Albums(
            albums.map { album ->
                val id = album.albumId.get()
                val mine = entries.filter { it.albumId == id }.sortedBy { it.position }
                val coverId = album.coverContentId.get().takeIf { it.isNotEmpty() }
                // The chosen cover, else the first photo — the fallback [Album.coverContentId]
                // documents, resolved here rather than stored.
                val cover = mine.firstOrNull { it.contentId == coverId } ?: mine.firstOrNull()
                val coverShape = cover?.let { shapes?.get(it.contentId) }
                AlbumSummary(
                    albumId = id,
                    name = album.name.get(),
                    createdAtMs = album.createdAt.get(),
                    photoCount = mine.size,
                    coverPath = cover?.filePath,
                    coverPixelWidth = coverShape?.pixelWidth,
                    coverPixelHeight = coverShape?.pixelHeight,
                )
            }.sortedByDescending { it.createdAtMs }
        )
    }

    /**
     * The covers' photographs by content id — the cover each card will draw (the chosen one, else
     * the album's first entry), read [CONTENT_ID_CHUNK] content ids per walk. `null` when a walk
     * failed.
     */
    private suspend fun coverShapes(albums: List<Entity<Album>>, entries: List<Entry>): Map<String, Photo>? {
        val coverIds = albums.mapNotNull { album ->
            val id = album.albumId.get()
            val mine = entries.filter { it.albumId == id }
            val chosen = album.coverContentId.get().takeIf { it.isNotEmpty() }
            (mine.firstOrNull { it.contentId == chosen } ?: mine.minByOrNull { it.position })?.contentId
        }.filter { it.isNotEmpty() }.distinct()
        if (coverIds.isEmpty()) return emptyMap()
        return coverIds.chunked(CONTENT_ID_CHUNK)
            .flatMap { ids -> readLibrary(imagesOnly = false) { filter<File> { contentId oneOf ids } } ?: return null }
            .associateBy { it.contentId }
    }

    override suspend fun albumPhotos(albumId: String): PhotoPageResult {
        val entries = (allEntries()
            ?: return pageReadFailure(client.proxyConnected.value, "could not read album entries"))
            .filter { it.albumId == albumId }
            .sortedBy { it.position }

        // ⚠ An entry whose file cannot be resolved is dropped, not rendered as a gap: a photo
        // deleted in Drive leaves its entry behind, and an album shows what exists.
        return PhotoPageResult.Page(entries.mapNotNull { photoFor(it) }, hasMore = false)
    }

    /**
     * ⚠ Two reads that already exist, filtered — no new op. The entries give the album ids this
     * content belongs to; [albums] turns them into rows with a name, a count and a cover, which is
     * exactly what the About sheet renders and what the Albums tab already renders elsewhere.
     *
     * ⚠⚠ Short-circuits on an empty [contentId] rather than reading anything. Membership is keyed
     * on that id, so a file predating content addressing is in no album by construction — and
     * spending two realm reads to be told so on every sheet open would be paying for a certainty.
     */
    override suspend fun albumsFor(contentId: String): List<AlbumSummary> {
        if (contentId.isEmpty()) return emptyList()
        val ids = (allEntries() ?: return emptyList())
            .filter { it.contentId == contentId }
            .map { it.albumId }
            .toSet()
        if (ids.isEmpty()) return emptyList()
        return when (val all = albumSummaries(withCoverShapes = false)) {
            is AlbumsResult.Albums -> all.albums.filter { it.albumId in ids }
            // ⚠ The sheet's album row is decoration on a panel about one photograph; a failed
            // read here omits the section rather than turning the whole panel into an error.
            else -> emptyList()
        }
    }

    override suspend fun createAlbum(name: String): String? {
        val albumId = mintAlbumId(clockMs(), name)
        return if (write { it.saveAlbum(albumId, name) }) albumId else null
    }

    /**
     * ⚠⚠ `SaveAlbum` REPLACES the row, so the rename must carry the album's chosen cover: without
     * it the default `""` is written and every rename resets the cover to the first entry. ⚠ A row
     * that cannot be read is a refusal, never a rename with the cover guessed.
     */
    override suspend fun renameAlbum(albumId: String, name: String): Boolean {
        val cover = client.photoService.albums
            .select { fields(Album.Field.ALBUM_ID, Album.Field.COVER_CONTENT_ID) }
            .getAll()
            .fold(onSuccess = { it.toList() }, onFailure = { null })
            ?.firstOrNull { it.albumId.get() == albumId }
            ?.coverContentId?.get()
            ?: return false
        return write { it.saveAlbum(albumId, name, cover) }
    }

    override suspend fun deleteAlbum(albumId: String): Boolean =
        write { it.deleteAlbum(albumId) }

    override suspend fun addToAlbum(albumId: String, photo: Photo): Boolean {
        // ⚠ Refused here as well as in the op: a UI that offered the action and then failed would
        // be worse than one that never offered it, and the caller needs the answer before the tap.
        if (photo.contentId.isEmpty()) return false
        return write { it.addToAlbum(albumId, photo.contentId, photo.path) }
    }

    override suspend fun removeFromAlbum(albumId: String, contentId: String): Boolean =
        write { it.removeFromAlbum(albumId, contentId) }

    override suspend fun favourites(): PhotoPageResult {
        val flagged = allFlags()
            ?: return pageReadFailure(client.proxyConnected.value, "could not read flags")
        val favouriteIds = flagged.filter { it.second }.map { it.first }.toSet()
        if (favouriteIds.isEmpty()) return PhotoPageResult.Page(emptyList(), hasMore = false)

        // ⚠⚠ §39: one fused walk over the favourites' content ids, not the shared index. The index
        // is the whole realm in one transaction (seconds, and no answer at all past ~1 MB); this
        // reads only the rows it returns.
        val matches = favouriteIds.toList()
            .chunked(CONTENT_ID_CHUNK)
            .flatMap { ids ->
                readLibrary(imagesOnly = true) { filter<File> { contentId oneOf ids } }
                    // ⚠ A failed walk is a failed read, not "no favourites".
                    ?: return PhotoPageResult.Failed("the library could not be read")
            }
            .distinctBy { it.path }
            .sortedByDescending { it.capturedAtMs }
        return PhotoPageResult.Page(matches, hasMore = false)
    }

    override suspend fun isFavourite(contentId: String): Boolean =
        allFlags()?.any { it.first == contentId && it.second } == true

    override suspend fun setFavourite(contentId: String, favourite: Boolean): Boolean {
        if (contentId.isEmpty()) return false
        // ⚠ Both flags are written together (one row), so `hidden` is read back and passed through
        // rather than defaulted — defaulting it would silently un-hide a photo on every favourite.
        val hidden = allFlagRows()?.firstOrNull { it.contentId.get() == contentId }?.hidden?.get()
            ?: false
        return write { it.setPhotoFlags(contentId, favourite, hidden) }
    }


    override suspend fun places(): List<PhotoPlace> = placesRead().orEmpty()

    override suspend fun placesRead(): List<PhotoPlace>? {
        return placeRows()
            .getAll()
            .fold(
                onSuccess = { rows ->
                    rows.toList().mapNotNull { place ->
                        // ⚠ The accessors are nullable *by declaration*, so absence arrives
                        // as a null rather than as a `SIGSEGV` no `catch` could intercept — see
                        // the accessor block in `Place.kt` for why this type in particular.
                        // ⚠ Which absences are fatal stays a per-field judgement, in
                        // `photoPlaceOrNull`, where it is testable without a store.
                        photoPlaceOrNull(
                            placeId = place.placeId?.get(),
                            label = place.label?.get(),
                            city = place.city?.get(),
                            latE7 = place.latE7?.get(),
                            lngE7 = place.lngE7?.get(),
                        )
                    }
                },
                // ⚠ `null`, not empty: a failed read is not a library with no places (§36).
                onFailure = { null },
            )
    }


    // ── trash ────────────────────────────────────────────────────────────────────────────────

    override suspend fun trash(photo: Photo): Boolean {
        if (photo.contentId.isEmpty()) return false
        // ⚠⚠ **`/Trash` has to exist before anything can be moved into it** — `moveFile` answers
        // `notFound` without a folder to hang the destination on, and nothing else in the codebase
        // creates this one.
        if (!ensureTrashFolder()) return false
        val trashPath = trashPathFor(photo.contentId, photo.name)
        val moved = client.fileService.defaultFileSystem
            .moveFile(photo.path, trashPath)
            .let { it is TResult.Success }
        if (!moved) return false

        // ⚠ Recorded AFTER the move: a record with no file is harmless (the restore finds nothing
        // and clears it), while a file in the trash with no record is still recoverable by hand
        // from Drive. The reverse order gets both wrong.
        return write { it.recordTrashedPhoto(photo.contentId, photo.path, trashPath) }
    }

    override suspend fun restore(contentId: String): Boolean {
        val record = trashRecords()?.firstOrNull { it.contentId == contentId } ?: return false
        val restored = client.fileService.defaultFileSystem
            .moveFile(record.trashPath, record.originalPath)
            .let { it is TResult.Success }
        if (!restored) return false
        return write { it.clearTrashedPhoto(contentId) }
    }

    override suspend fun deleteForever(contentId: String): Boolean {
        val record = trashRecords()?.firstOrNull { it.contentId == contentId } ?: return false
        val deleted = client.fileService.defaultFileSystem
            .deleteFile(record.trashPath)
            .let { it is TResult.Success }
        // ⚠ Clear the record even when the delete failed to find the file: a record pointing at
        // nothing is what makes the Trash screen show a row that cannot be acted on.
        write { it.clearTrashedPhoto(contentId) }
        return deleted
    }

    /**
     * Make sure `/Trash` exists, creating it if it does not.
     *
     * ⚠⚠ **An "already exists" answer is success, not failure**, and it is matched on the error's
     * TYPE rather than its message — a diagnostic string is not a contract, and pinning behaviour
     * to one is how a rename becomes a production-only regression. It also has to be handled rather
     * than avoided with a look-first check: two photographs trashed at once would both look, both
     * create, and one would lose.
     *
     * ⚠ Called on every trash rather than once at startup. It is one cheap op against a folder that
     * exists after the first time, and a "did we create it yet" flag would be wrong the moment a
     * user deleted the folder from Drive — which they can, because it is an ordinary realm folder.
     */
    private suspend fun ensureTrashFolder(): Boolean {
        return when (val result = client.fileService.defaultFileSystem.createFolder(TRASH_ROOT)) {
            is TResult.Success<*> -> true
            is TResult.Failure<*> -> result.error is AlreadyExistsError
            else -> false
        }
    }

    // ── duplicates ───────────────────────────────────────────────────────────────────────────

    /**
     * ⚠ The realm's *files*, not the feed, and the media filter is applied to the groups: reading
     * the feed instead would miss a photo that is a duplicate of a file Drive uploaded.
     *
     * ⚠⚠ In byte-bounded replies ([RealmContentReads.duplicateScan], §40) — never the whole realm in
     * one. That read (the shared content index, §20) was ~640 bytes a file in ONE Binder
     * transaction, so a realm past ~1,600 files got no answer and this screen said *could not look*.
     */
    override suspend fun duplicates(): DuplicatesResult {
        val scan = RealmContentReads.duplicateScan(client)
            // ⚠ A failed read is "could not look", never "no duplicates".
            ?: return duplicatesReadFailure(
                proxyConnected = client.proxyConnected.value,
                detail = "the library's content could not be read",
            )
        val groups = scan.groups.map { group ->
            DuplicateGroup(
                contentHash = group.first().contentHash,
                photos = group.map { it.toPhoto() },
                sizeBytes = group.first().sizeBytes,
            )
        }
        return DuplicatesResult.Groups(groups, filesScanned = scan.filesScanned)
    }

    override suspend fun deletePermanently(photo: Photo): Boolean =
        client.fileService.defaultFileSystem
            .deleteFile(photo.path)
            .let { it is TResult.Success }

    /**
     * ⚠ A search by path, like [details] — one row. A trashed copy's path is `/Trash/…`, so it is not
     * found at the path it had. Absent hash, absent row and a failed read are all `false`.
     *
     * ⚠⚠ A read that THROWS is `false` as well — unlike the reads around it, which let
     * a throw through as the programming error it usually is. This answer gates a permanent delete,
     * and its contract is "`true` only on positive evidence": a typed error from a dropped connection
     * is "could not look", never something to carry past the caller's lock.
     * ⚠ `runCatchingCancellable`, never `runCatching` — a cancelled caller stops here, it is not told NO.
     */
    override suspend fun holdsContent(path: String, contentHash: String): Boolean {
        if (contentHash.isEmpty()) return false
        val held = runCatchingCancellable {
            client.fileService.defaultFileSystem.root
                .files
                .navigateTo(path.substringBeforeLast('/'))
                .search<File> { this.path equalTo path }
                .select {
                    fields(File.Field.PATH)
                    relations(File.ContentHash)
                }
                .resolve()
                .getOrNull()
                ?.get(File.ContentHash)
                ?.get()
        }.getOrElse {
            println("TenshuPhotosRepository -> holdsContent could not look: $it")
            null
        }
        return held == contentHash
    }

    private fun IndexedContent.toPhoto(): Photo = Photo(
        path = path,
        capturedAtMs = createdAtMs,
        isVideo = isVideo,
        contentId = contentId,
        // ⚠ Same reason as the coordinates below: the index answers "same bytes?" and carries no
        // duration, so a duplicate group's video tile shows the bare glyph until the viewer re-reads
        // it through the feed's projection.
        durationMs = null,
        // ⚠ The index does not project capture coordinates: it exists to answer "same bytes?", and
        // a duplicate group's tiles never show a place chip. A viewer opened from here re-reads the
        // photo through the feed's projection like any other.
        latE7 = null,
        lngE7 = null,
        contentHash = contentHash,
        // §16: the tile's thumbnail class follows the photo's shape, and the index carries it.
        pixelWidth = pixelWidth,
        pixelHeight = pixelHeight,
    )

    override suspend fun trashed(): TrashResult {
        val records = trashRecords()
            ?: return trashReadFailure(client.proxyConnected.value, "could not read the trash")
        return TrashResult.Items(
            records
                .sortedByDescending { it.trashedAtMs }
                .mapNotNull { record ->
                    // ⚠ A record whose file is gone is dropped rather than shown: the row would
                    // offer a restore that cannot work.
                    byPath(record.trashPath)?.let { photo ->
                        TrashedItem(
                            contentId = record.contentId,
                            photo = photo,
                            originalPath = record.originalPath,
                            trashedAtMs = record.trashedAtMs,
                        )
                    }
                }
        )
    }

    private data class TrashRecord(
        val contentId: String,
        val originalPath: String,
        val trashPath: String,
        val trashedAtMs: Long,
    )

    private suspend fun trashRecords(): List<TrashRecord>? =
        client.photoService.trashedPhotos
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
                    rows.toList().map { record ->
                        TrashRecord(
                            contentId = record.contentId.get(),
                            originalPath = record.originalPath.get(),
                            trashPath = record.trashPath.get(),
                            trashedAtMs = record.trashedAt.get(),
                        )
                    }
                },
                onFailure = { null },
            )

    // ── shared helpers ───────────────────────────────────────────────────────────────────────

    /** One album entry, flattened — the fields the app needs without carrying an `Entity` around. */
    private data class Entry(
        val albumId: String,
        val contentId: String,
        val filePath: String,
        val position: Long,
    )

    private suspend fun allEntries(): List<Entry>? =
        entryRows()
            .getAll()
            .fold(
                onSuccess = { rows ->
                    rows.toList().map { row ->
                        Entry(
                            albumId = row.albumId.get(),
                            contentId = row.contentId.get(),
                            filePath = row.filePath.get(),
                            position = row.position.get(),
                        )
                    }
                },
                onFailure = { null },
            )

    private suspend fun allFlagRows(): List<Entity<PhotoFlags>>? =
        flagRows()
            .getAll()
            .fold(
                onSuccess = { rows -> rows.toList() },
                onFailure = { null },
            )

    private suspend fun allFlags(): List<Pair<String, Boolean>>? =
        allFlagRows()?.map { it.contentId.get() to it.favourite.get() }

    /**
     * Resolve one entry to a [Photo] — **path first, content id as the fallback**.
     *
     * ⚠⚠ This is the self-healing half of `AlbumEntry`'s hint. The path resolves through the same
     * cheap `navigateTo` + `search` every other read uses; when it misses (the user moved the
     * photo in Drive) the content id still identifies the file, so the album finds it with one
     * realm walk and re-adds it — which rewrites the hint, so the walk happens once per move
     * rather than once per read.
     */
    private suspend fun photoFor(entry: Entry): Photo? {
        byPath(entry.filePath)?.let { return it }

        val moved = findByContentId(entry.contentId) ?: return null
        // Heal the hint. A failure here is not fatal: the album still shows the photo, and the
        // next read pays for the walk again.
        //
        // ⚠ **The one deliberate swallow in this file**: [write] can throw, and a failed
        // *optimisation* must not take the read that triggered it with it. It absorbs a genuinely
        // optional write, never a programming error dressed as a read failure.
        bestEffort("Photos -> healing an album membership hint") {
            write { it.addToAlbum(entry.albumId, entry.contentId, moved.path) }
        }
        return moved
    }

    override suspend fun allPhotos(): List<Photo> = allPhotosRead().orEmpty()

    /**
     * Every photograph in the library, newest first — what Collections' years, places, things and
     * people are built from (`photos-app.md` §39).
     *
     * ⚠⚠ **Its own read, not the shared content index.** The index is a whole-realm *stream*: one
     * emission carrying every file in the realm, all nine of its properties, in one Binder
     * transaction. Measured on a phone with 1,401 photographs it took ~6 s and was **995 KB** — a
     * whisker under Binder's ~1 MB ceiling, which every library a little larger goes over, and then
     * the read does not come back at all. This read asks only for images, only for the fields a
     * card needs, and in pages of [LIBRARY_PAGE_SIZE] — each page one fused thin walk in Control
     * (`core-ordered-window-fusion.md` §9), each reply a bounded transaction.
     *
     * ⚠ What it answers, against the index read it replaced: the same photographs, newest first, but
     * **with** the ones that have no content hash (files uploaded before the hash was stamped — the
     * index cannot key them, the feed shows them) and **without** the ones that have no capture
     * time (the feed drops them too; the index filed them under 1970).
     *
     * `null` when any page failed — a partial library is not the library (§36).
     */
    override suspend fun allPhotosRead(): List<Photo>? = readLibrary(imagesOnly = true)

    /**
     * Every photograph the feed's window holds that [narrow] keeps, newest first, read in keyset
     * pages of [pageSize] — the feed's own cursor, so ties at a page boundary are neither lost nor
     * repeated.
     *
     * ⚠⚠ [narrow] must stay a fusable predicate (`Filter`'s KDoc): it runs ahead of the sort, and a
     * predicate the fusion cannot read turns every page back into a whole-realm materialisation.
     *
     * `null` when any page failed part way.
     */
    private suspend fun readLibrary(
        imagesOnly: Boolean,
        pageSize: Int = LIBRARY_PAGE_SIZE,
        narrow: TList<File>.() -> TList<File> = { this },
    ): List<Photo>? {
        val all = mutableListOf<Photo>()
        var cursor = Long.MAX_VALUE
        var skip = 0
        while (true) {
            val rows = client.fileService.defaultFileSystem.root
                .files
                .traverse()
                // ⚠ [feedMembers]' predicates, with the media test narrowed to images when that is
                // the question — ONE category predicate, not `isMedia()` and then `Image` again: a
                // dynamic predicate is the dearest thing a fused walk evaluates per row (§39).
                .filter<File> { if (imagesOnly) category equalTo FileCategory.Image else isMedia() }
                .filter<File> { createdAt.inRange(0L, exclusiveEndFor(cursor)) }
                .filter<File> { notTrashed() }
                .narrow()
                .sortByDescending(File.CreatedAt)
                .drop(skip)
                .take(pageSize)
                .select { if (imagesOnly) libraryRowProjection() else feedRowProjection() }
                .getAll()
                .getOrNull()
                ?.toList()
                ?: return null
            // ⚠ Paged on the ROWS, not on the mapped photographs: `toPhotoOrNull` is partial, and a
            // short mapped page must not end the read while rows remain (the window already requires
            // a capture time, so today the two counts agree).
            val photos = rows.mapNotNull { it.toPhotoOrNull() }
            all += photos
            if (rows.size < pageSize) break
            val last = photos.lastOrNull()?.capturedAtMs ?: break
            // ⚠⚠ The tie skip is the difference between terminating and not. The window is
            // inclusive of `cursor`, so a page whose rows ALL share one capture time leaves the
            // cursor where it was — without the skip this would append the same rows for ever. It
            // counts every row already held at the boundary, across pages.
            skip = all.count { it.capturedAtMs == last }
            cursor = last
        }
        return all
    }

    /**
     * Every `MediaLabel` row, unfiltered but for the sentinel (`inference-service.md` §12).
     *
     * ⚠⚠ **One read, not one per label.** The label vocabulary is open-ended, so a read per label
     * would be an unbounded number of full-realm walks per Collections refresh — the exact cost
     * §20 removed. The join back to a [Photo] happens against the caller's own shared-index read.
     *
     * ⚠⚠ The [INDEX_MARKER_LABEL] sentinel is dropped **here**, at the only place that reads every
     * label: it is written for every indexed file whether or not the model had an opinion, so a
     * caller that forgot would get one collection containing the user's entire library.
     *
     * ⚠ Empty on failure, like every other read in this class that cannot tell the caller anything
     * useful about *why*. Nothing on this path asks a model a question, so an empty answer is
     * "nothing has been labelled" and never a refusal — the distinction `searchSemantic` carries a
     * status for.
     */
    override suspend fun allLabels(): List<PhotoLabel> = allLabelsRead().orEmpty()

    // ⚠ `null` when the read failed — not "nothing has been labelled" (§36).
    override suspend fun allLabelsRead(): List<PhotoLabel>? {
        return labelRows()
            .getAll()
            .getOrNull()
            ?.toList()
            ?.mapNotNull { entity ->
                val label = entity.label.get()
                if (label == INDEX_MARKER_LABEL) return@mapNotNull null
                PhotoLabel(
                    path = entity.filePath.get(),
                    label = label,
                    // ⚠ Stored as a Long — the DSL's `int` field is 64-bit on the wire.
                    confidencePerMille = entity.confidencePerMille.get().toInt(),
                    capability = entity.capability.get(),
                )
            }
    }

    /**
     * `faceGroups` over the wire, grouped by the id the proxy minted.
     *
     * ⚠⚠ The same `when`/`else` shape as [searchSemantic] and for the same reasons: `TResult` is
     * not a closed hierarchy, and a `FaceGroupsUnavailable` must reach the screen as a *reason*
     * rather than as an empty list. `UNCALIBRATED` in particular would otherwise render as a
     * library containing nobody.
     *
     * ⚠ Rows arrive one per **face**, so two faces of different people in one photograph produce
     * two rows with the same path under different ids. Grouping by id — never by path — is what
     * keeps both people's copy of that photograph.
     */
    override suspend fun faceGroups(limit: Int): Pair<List<FaceGroup>, PeopleStatus> {
        return when (val result = client.inferenceService.faceGroups(limit)) {
            is TResult.Success -> {
                val groups = result.value?.toList()
                    ?.groupBy { it.groupId.get() }
                    ?.map { (groupId, rows) ->
                        // ⚠ `distinct()`: one person photographed twice in one frame is one
                        // photograph to a grid, and a duplicate path would render a duplicate tile.
                        FaceGroup(
                            groupId = groupId,
                            filePaths = rows.map { it.filePath.get() }.distinct(),
                            // The box and the photo's face count, per face.
                            faces = rows.map { row ->
                                GroupFace(
                                    path = row.filePath.get(),
                                    faceIndex = row.faceIndex.get().toInt(),
                                    region = FaceRegion(
                                        left = row.boxLeftPerMille.get() / 1000f,
                                        top = row.boxTopPerMille.get() / 1000f,
                                        right = row.boxRightPerMille.get() / 1000f,
                                        bottom = row.boxBottomPerMille.get() / 1000f,
                                    ),
                                    facesInPhoto = row.facesInPhoto.get().toInt(),
                                )
                            },
                        )
                    }
                    .orEmpty()
                groups to PeopleStatus.Available
            }

            else -> {
                val error = (result as? TResult.Failure<*>)?.error
                val status = (error as? FaceGroupsUnavailable)
                    ?.let { PeopleStatus.Unavailable(it.reason.get()) }
                    ?: PeopleStatus.Failed
                emptyList<FaceGroup>() to status
            }
        }
    }

    /**
     * The one write in this feature, and the only part of it that syncs (A40).
     *
     * ⚠⚠ Reports refusal rather than swallowing it. A name the user typed that silently did not
     * save is worse than an error: they will believe the person is named, see the name vanish on
     * the next refresh, and have nothing to report. The proxy refuses only for want of consent
     * today, which is a window a stale screen can genuinely be in.
     */
    override suspend fun nameFaceGroup(filePaths: List<String>, name: String): NamingOutcome {
        // ⚠⚠ **Chunked, because one Binder transaction is ~1 MB** (the ceiling this class
        // documents for listings). A heavily photographed person can hold tens of thousands of
        // paths, and an unbounded list would make exactly the people most worth naming the ones
        // that CANNOT be named — failing at the transaction layer, where the message explains
        // nothing.
        // ⚠⚠ Refused here rather than sent and failed at the transport. The name is repeated
        // in EVERY call, so one sufficiently large pasted name makes every chunk over-budget and
        // every call fail below the typed-error layer, where nothing can explain it. The dialog
        // caps the field too; this is the backstop for a caller that is not the dialog.
        if (name.length * 4 + WIRE_ENTRY_OVERHEAD * 2 > NAME_CALL_BYTE_BUDGET) {
            return NamingOutcome.Failed
        }

        var savedAny = false
        chunkWithinTransaction(filePaths, name).forEach { chunk ->
            when (val result = client.inferenceService.nameFaceGroup(chunk, name)) {
                is TResult.Success -> {
                    // ⚠⚠ Compared to the CHUNK, not to zero. The proxy deliberately returns a
                    // short count when its own write tore, so "it returned a positive number" would
                    // call 1-of-100 a success — clearing the retry and telling the user it is done
                    // while 99 photographs stay unnamed.
                    val wrote = result.value?.get() ?: 0L
                    if (wrote < chunk.size) {
                        return if (savedAny || wrote > 0L) {
                            NamingOutcome.PartiallySaved
                        } else {
                            NamingOutcome.Failed
                        }
                    }
                    savedAny = true
                }
                else -> {
                    // ⚠⚠ A partial save is reported AS a partial save. Stopping here is
                    // right — pressing on past a refusal writes more of what was just refused —
                    // but the rows already written are durable and synced, so calling this a plain
                    // failure would send the user to retype a name that is half applied.
                    if (savedAny) return NamingOutcome.PartiallySaved
                    val error = (result as? TResult.Failure<*>)?.error
                    return if (
                        error is FaceGroupsUnavailable &&
                        error.reason.get() == FaceGroupsUnavailable.Reasons.NO_CONSENT
                    ) {
                        NamingOutcome.NoConsent
                    } else {
                        NamingOutcome.Failed
                    }
                }
            }
        }
        return NamingOutcome.Saved
    }

    /**
     * Split [filePaths] into calls that each fit inside one Binder transaction.
     *
     * ⚠⚠ **By BYTES, not by count**, and a fixed count was the first attempt at this: paths
     * have no length bound and neither does a pasted [name], so 500 deeply nested paths — or one
     * enormous name repeated in every call — still serialises past the ~1 MB ceiling. The request
     * is valid and fails before it reaches the proxy, where no typed error can explain it.
     *
     * ⚠ A deliberately crude upper bound on encoded size: 4 bytes per char is UTF-8's maximum,
     * plus per-entry framing. Over-estimating costs an extra round trip on a once-per-person act;
     * under-estimating costs a person who cannot be named at all.
     */
    private fun chunkWithinTransaction(filePaths: List<String>, name: String): List<List<String>> {
        val fixed = name.length * 4 + WIRE_ENTRY_OVERHEAD
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var used = fixed
        filePaths.forEach { path ->
            val cost = path.length * 4 + WIRE_ENTRY_OVERHEAD
            // ⚠ `current.isNotEmpty()`: a single path bigger than the whole budget still goes
            // out on its own rather than becoming an empty chunk that silently drops it. That one
            // call may fail at the transport — a path that long is pathological — but dropping
            // the photograph without a word is worse, and the NAME, which is the part a user can
            // actually make enormous, is bounded before we get here.
            if (current.isNotEmpty() && used + cost > NAME_CALL_BYTE_BUDGET) {
                chunks += current
                current = mutableListOf()
                used = fixed
            }
            current += path
            used += cost
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    // ── search (S2) ──────────────────────────────────────────────────────────────────────────

    /**
     * ⚠ Drive's `searchFiles` chain, narrowed to photographs: the same
     * `name.contains(ignoreCase = true)` predicate, plus the category and trash filters every read
     * path in this app carries. `ignoreCase` is an explicit opt-in — `contains` defaults to
     * case-sensitive like Kotlin's.
     */
    override suspend fun searchByName(query: String): List<Photo> = searchByNameRead(query).orEmpty()

    // ⚠ `null` when the walk failed — not "nothing matched" (`photos-ios.md` §8.19).
    override suspend fun searchByNameRead(query: String): List<Photo>? {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return client.fileService.defaultFileSystem.root
            .files
            .traverse()
            .filter<File> { name.contains(needle, ignoreCase = true) }
            .filter<File> { isMedia() }
            .filter<File> { notTrashed() }
            .take(SEARCH_LIMIT)
            .select {
                fields(File.Field.PATH)
                relations(
                    File.CreatedAt, File.Category, File.ContentId, File.DurationMs,
                    File.PixelWidth, File.PixelHeight,
                )
            }
            .getAll()
            .getOrNull()
            ?.toList()
            ?.mapNotNull { it.toPhotoOrNull() }
    }

    /**
     * ⚠⚠ Two reads, and it cannot be one. `MediaLabel` rows live in `:inference-service` and carry
     * a *path*, not a reference — deliberately, so `:file-service` never learns what a model is
     * (the `PhotosNear` posture). So the labels are read first and their paths resolved second,
     * which is a join this app performs, exactly like the Places section's `Place` join.
     *
     * ⚠ Still empty on almost every device, though `MediaIndexer` writes a `MediaLabel` per tag on
     * its scheduled idle+charging pass. What keeps the table empty is the model: no catalog key ⇒
     * `NO_CATALOG` ⇒ no model ⇒ every pass is a no-op. The return stays an empty list rather than a
     * status all the same — an absent *index* is a real "nothing matched", unlike an absent
     * *model*, which `searchSemantic` reports as a refusal.
     */
    override suspend fun searchByLabel(query: String): List<Pair<Photo, String>> =
        searchByLabelRead(query).orEmpty()

    // ⚠ `null` when the label rows could not be read — not "nothing is tagged" (`photos-ios.md` §8.19).
    override suspend fun searchByLabelRead(query: String): List<Pair<Photo, String>>? {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val rows = client.inferenceService.labels
            .select {
                fields(MediaLabel.Fields.FILE_PATH, MediaLabel.Fields.LABEL)
            }
            .getAll()
            .getOrNull()
            ?.toList()
            ?.map { entity -> entity.filePath.get() to entity.label.get() }
            ?: return null

        return citableLabelMatches(rows, needle, SEARCH_LIMIT)
            .mapNotNull { (path, label) -> citablePhotoAt(path)?.let { it to label } }
    }

    /**
     * ⚠⚠ The refusal is the point. `photosLike` throws `SemanticSearchUnavailable` rather than
     * answering empty, and this keeps the two apart all the way to the screen: a `Failure` carrying
     * that error becomes [SemanticSearchStatus.Unavailable] with its raw reason, and anything else
     * becomes [SemanticSearchStatus.Failed]. Collapsing either into an empty list would render
     * "no model on this device" as "nothing matched".
     */
    override suspend fun searchSemantic(
        query: String,
        limit: Int,
    ): Pair<List<Photo>, SemanticSearchStatus> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList<Photo>() to SemanticSearchStatus.Available
        // ⚠ A `when`, not `fold`: resolving each hit's path is a suspending read and `fold`'s
        // lambdas are not suspend. The ranked paths come out of the result first, then get
        // resolved — which also keeps the op's ordering, which `mapNotNull` preserves.
        return when (val result = client.inferenceService.photosLike(text, limit)) {
            is TResult.Success -> {
                val paths = result.value?.toList()
                    ?.map { it.filePath.get() }
                    .orEmpty()
                // ⚠ A path whose file has since moved or been trashed is dropped rather than
                // shown as a row that opens nothing.
                paths.mapNotNull { citablePhotoAt(it) } to SemanticSearchStatus.Available
            }

            // ⚠ `else`, not `is TResult.Failure`: `TResult` is an interface rather than a
            // sealed hierarchy the compiler can close, and anything that is not a success is
            // a failure to a caller.
            else -> {
                val error = (result as? TResult.Failure<*>)?.error
                val status = (error as? SemanticSearchUnavailable)
                    ?.let { SemanticSearchStatus.Unavailable(it.reason.get()) }
                    ?: SemanticSearchStatus.Failed
                emptyList<Photo>() to status
            }
        }
    }

    // ── metadata ─────────────────────────────────────────────────────────────────────────────

    /**
     * ⚠⚠ **Two reads, and their order is the whole design.** `hasLocalData` is O(1) and reads no
     * payload (its own KDoc says so); `File.Data` on a file this device does not hold triggers a
     * content-plane fetch of the **entire original**, because there is no ranged read — megabytes
     * pulled to reach a couple of hundred bytes of EXIF. Asking the free question first is what
     * makes this panel affordable; swapping these two lines makes it spend a user's mobile data
     * without asking (`photos-viewer-about-screen.md` §7.3).
     */
    override suspend fun photographicSettings(path: String): ExifReadResult {
        val local = client.fileService.defaultFileSystem
            .hasLocalData(path)
            .getOrNull()
            ?.get()
        return when (local) {
            // ⚠ `null` is "the probe failed", which is a different statement from "the bytes are
            // elsewhere" — and only one of the two is something the user can act on.
            null -> exifReadFailure(
                proxyConnected = client.proxyConnected.value,
                detail = "presence probe returned nothing for $path",
            )
            false -> ExifReadResult.NotOnDevice
            true -> {
                val bytes = client.fileService.defaultFileSystem.root
                    .files
                    .navigateTo(path.substringBeforeLast('/'))
                    .search<File> { this.path equalTo path }
                    .select { relations(File.Data) }
                    .resolve()
                    .getOrNull()
                    // ⚠ `getOrNull`, not `get`: graceful on a SharedMemory cache miss — the recipe
                    // `TenshuFilesRepository.getRemoteFile` established.
                    ?.get(File.Data)
                    ?.getOrNull()
                // ⚠ The probe said yes and the read still came back empty: a race with an evict, or
                // a cache miss. "Not here" is the truthful answer, not a failure.
                if (bytes == null) ExifReadResult.NotOnDevice
                else ExifReadResult.Read(readMediaMetadata(bytes))
            }
        }
    }

    /**
     * ⚠ A **search**, not a traverse: the info sheet is opened on one known path, and walking the
     * realm to answer a question about a single row is the cost this app has spent twice already
     * (§12.4, §13.5). `navigateTo` descends folder by folder so the search matches inside the
     * right one.
     */
    override suspend fun details(path: String): PhotoDetails? {
        return client.fileService.defaultFileSystem.root
            .files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            .select {
                fields(File.Field.PATH)
                relations(
                    File.CreatedAt,
                    File.Size,
                    File.PixelWidth,
                    File.PixelHeight,
                    File.Orientation,
                    File.CameraMake,
                    File.CameraModel,
                    File.DurationMs,
                )
            }
            .resolve()
            .getOrNull()
            ?.let { entity ->
                PhotoDetails(
                    path = path,
                    capturedAtMs = entity.get(File.CreatedAt)?.get(),
                    sizeBytes = entity.get(File.Size)?.get(),
                    media = MediaMetadata(
                        // ⚠ The coordinate is deliberately not read here. The sheet shows a place
                        // *name*, which is a separate join and not a number the row carries.
                        pixelWidth = entity.get(File.PixelWidth)?.get()?.toInt(),
                        pixelHeight = entity.get(File.PixelHeight)?.get()?.toInt(),
                        orientation = entity.get(File.Orientation)?.get()?.toInt(),
                        cameraMake = entity.get(File.CameraMake)?.get(),
                        cameraModel = entity.get(File.CameraModel)?.get(),
                        durationMs = entity.get(File.DurationMs)?.get(),
                    ),
                )
            }
    }

    /**
     * The photograph a search hit may CITE at [path], or `null` — the join every model-backed source
     * goes through (`media-processing-pipeline-sweep.md` F12, `photos-ios.md` §8.19).
     *
     * ⚠⚠ Two ways a stored path is not a photograph to show, and both are `null` here:
     * - **no live `File` row** — `MediaLabel` rows and vectors outlive their file (nothing deletes
     *   them), so a deleted or moved photograph's row is still read;
     * - **a trash path** — a trashed photograph HAS a live row, at `/Trash/…`. Nothing keys a label
     *   there today; D14's move hook would, for typed names, and this is the filter that must
     *   already be in place when it does.
     *
     * ⚠ What this cannot see: a path whose file was REPLACED, and a row a read failed on — which
     * is dropped like a dead one, the safe direction for a citation.
     */
    internal suspend fun citablePhotoAt(path: String): Photo? =
        if (isTrashedPath(path)) null else byPath(path)

    private suspend fun byPath(path: String): Photo? =
        client.fileService.defaultFileSystem.root
            .files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            .select {
                fields(File.Field.PATH)
                relations(
                    File.CreatedAt, File.Category, File.ContentId, File.DurationMs,
                    File.PixelWidth, File.PixelHeight,
                )
            }
            .resolve()
            .getOrNull()
            ?.toPhotoOrNull()

    private suspend fun findByContentId(contentId: String): Photo? {
        if (contentId.isEmpty()) return null
        var cursor = Long.MAX_VALUE
        var seenAtCursor = 0
        while (true) {
            val page = feedPage(cursor, seenAtCursor, DEFAULT_PAGE_SIZE)
            val photos = (page as? PhotoPageResult.Page)?.photos ?: return null
            photos.firstOrNull { it.contentId == contentId }?.let { return it }
            if (photos.size < DEFAULT_PAGE_SIZE) return null
            val next = photos.last().capturedAtMs
            // ⚠⚠ Same termination hazard as `collectFeedThePreIndexWay`, and worse to debug: a
            // page entirely within one millisecond would spin here forever without ever finding
            // the photograph. ⚠ Accumulated across pages, because consecutive pages can both end
            // inside the same tie run.
            seenAtCursor = if (next == cursor) seenAtCursor + photos.size else
                photos.count { it.capturedAtMs == next }
            cursor = next
        }
    }

    /** Runs a write op and folds its result to a plain boolean the UI can act on. */
    private suspend fun write(block: suspend (PhotoService) -> TResult<Data?>): Boolean {
        return block(client.photoService).fold(onSuccess = { true }, onFailure = { false })
    }

    /**
     * ⚠ A row with no `File.CreatedAt` is **dropped**, not read as epoch zero — the call
     * `PhotosCapturedBetween` makes, for the same reason: absent is not 1970, and treating it as
     * such files every timestamp-less image at the very bottom of the feed forever.
     */
    private fun Entity<File>.toPhotoOrNull(): Photo? {
        val capturedAt = get(File.CreatedAt)?.get() ?: return null
        return Photo(
            path = this.path.get(),
            capturedAtMs = capturedAt,
            // The category predicate already kept videos out of this read; the flag is here for
            // the day the feed asks for both (S1 shows video too) and costs nothing now.
            isVideo = get(File.Category) == FileCategory.Video,
            durationMs = get(File.DurationMs)?.get(),
            contentId = get(File.ContentId)?.get().orEmpty(),
            latE7 = get(File.CaptureLatE7)?.get(),
            lngE7 = get(File.CaptureLngE7)?.get(),
            pixelWidth = get(File.PixelWidth)?.get()?.toInt(),
            pixelHeight = get(File.PixelHeight)?.get()?.toInt(),
        )
    }

    companion object {
        /**
         * ⚠ A guess, and knowingly so — see the class KDoc. Small enough that a cold page on a
         * large realm cannot block a paint for long, large enough to fill more than one screen of
         * a phone grid so the user rarely waits mid-scroll.
         */
        const val DEFAULT_PAGE_SIZE = 60

        /**
         * ⚠ A cap, not a page. Search has no keyset cursor — `contains` gives no ordering to page
         * on — so the honest shape is a bounded first look rather than a pretence of completeness.
         * The screen says when it is showing a capped list.
         */
        const val SEARCH_LIMIT = 60

        /**
         * Rows per page of a whole-library read ([allPhotosRead], §39).
         *
         * ⚠⚠ Sized by BYTES, not by speed. A library row is ~430 bytes on the wire (measured on the
         * emulator: 216 KB per 500-row page; `WholeLibraryReadBenchmarkTest` on the JVM), and a page
         * is one Binder transaction with a ~1 MB ceiling shared with whatever else is in flight — so
         * 500 rows sits near the quarter-of-Binder budget [NAME_CALL_BYTE_BUDGET] keeps for the same
         * reason. Bigger pages mean fewer walks (each page is one O(realm) thin walk in Control);
         * revisit only with a byte measurement in hand.
         */
        const val LIBRARY_PAGE_SIZE = 500

        /**
         * Content ids per `oneOf` predicate. ⚠ `oneOf` scans its candidates linearly per row, so a
         * walk costs rows × ids; chunking keeps a favourites list of thousands from becoming one
         * walk of millions of comparisons.
         */
        const val CONTENT_ID_CHUNK = 200

        /**
         * The encoded-size budget for one `nameFaceGroup` call.
         *
         * ⚠ A quarter of Binder's ~1 MB, because the ceiling is per TRANSACTION and shared with
         * whatever else is in flight on that thread — a budget that only just fits in an empty
         * transaction is one that fails under load, which is the worst time to find out.
         */
        const val NAME_CALL_BYTE_BUDGET = 256 * 1024

        /** Rough per-entry wire framing, added to every path and to the name. */
        const val WIRE_ENTRY_OVERHEAD = 64

        /**
         * Paths per `nameFaceGroup` call.
         *
         * ⚠⚠ **A Binder transaction is ~1 MB for the whole call**, shared with every other
         * argument, and a path can be long. Unbounded, the people most worth naming — the ones
         * with thousands of photographs — would be exactly the ones that *cannot* be named,
         * failing at the transaction layer where the message explains nothing.
         *
         * ⚠ 500 keeps a chunk far under the ceiling with room that does not need re-deriving when
         * paths get longer. Naming is a once-per-person act, so extra round trips cost nothing a
         * user can perceive.
         */
        const val NAME_PATHS_PER_CALL = 500
    }
}

/**
 * Walk down to [parentPath] one folder at a time, so a `search` for the file itself matches inside
 * the right folder rather than against the root array.
 *
 * ⚠ The **third** copy — `TenshuFilesRepository` and `TimelineProjectionRepository` each carry a
 * private one. Lifting it into `:file-service-client` beside the chain accessors is the follow-up.
 */
private fun TList<File>.navigateTo(parentPath: String): TList<File> {
    val parent = parentPath.ifEmpty { "/" }
    if (parent == "/") return this
    var list = this
    var prefix = ""
    for (segment in parent.trimStart('/').split('/')) {
        prefix = "$prefix/$segment"
        val level = prefix
        list = list.search<Folder> { path equalTo level }.ifEmptyError().files
    }
    return list
}

/**
 * How a **folded** read failure becomes a result — one per result family (`ADR 0025`), split from
 * the reads so the decision is testable without a live `TenshuClient`
 * (`ReadFailureClassificationTest`).
 *
 * ⚠⚠ [proxyConnected] `false` is authoritative: no call crosses a dead channel, so a read that
 * failed while it was down failed *because* of that. `true` proves nothing — a connected proxy
 * still refuses every read on a schema mismatch — so it stays a genuine failure and the shell's
 * reason supplies the words.
 *
 * ⚠ [detail] is diagnostic and never reaches the screen. Classifying on its **text** is what this
 * exists to avoid.
 */
internal fun pageReadFailure(proxyConnected: Boolean, detail: String): PhotoPageResult =
    if (proxyConnected) PhotoPageResult.Failed(detail) else PhotoPageResult.Unreachable

/** @see pageReadFailure */
internal fun albumsReadFailure(proxyConnected: Boolean, detail: String): AlbumsResult =
    if (proxyConnected) AlbumsResult.Failed(detail) else AlbumsResult.Unreachable

/** @see pageReadFailure */
internal fun trashReadFailure(proxyConnected: Boolean, detail: String): TrashResult =
    if (proxyConnected) TrashResult.Failed(detail) else TrashResult.Unreachable

/** @see pageReadFailure */
internal fun duplicatesReadFailure(proxyConnected: Boolean, detail: String): DuplicatesResult =
    if (proxyConnected) DuplicatesResult.Failed(detail) else DuplicatesResult.Unreachable

/** @see pageReadFailure */
internal fun exifReadFailure(proxyConnected: Boolean, detail: String): ExifReadResult =
    if (proxyConnected) ExifReadResult.Failed(detail) else ExifReadResult.Unreachable

/**
 * The id a new album gets — the clock plus the name's hash, not a UUID.
 *
 * ⚠ This module has no random source and no clock dependency of its own, and an album id only has
 * to be unique among one user's albums. A collision would merge two albums the user made in the
 * same millisecond with the same name, which is the same album.
 *
 * ⚠⚠ Pure so it can be tested without a running Control — that test is what catches a defect
 * giving *every* album one byte-identical id. Assert that two ids differ.
 */
internal fun mintAlbumId(clockMs: Long, name: String): String =
    "album-$clockMs-${name.hashCode().toUInt().toString(16)}"

/**
 * Which `MediaLabel` rows — `(filePath, label)` — answer a label search for [needle], one per file, at
 * most [limit] (`photos-app.md` S2 source 2).
 *
 * ⚠⚠ The index-pass sentinel is on EVERY indexed file, and this is a `contains`: without the
 * exclusion a search for "ind" returns the user's whole library captioned `__indexed`.
 * ⚠⚠ A row at a trash path is dropped BEFORE the cap: counted first, sixty trashed rows would
 * fill the cap and hide every live match behind them.
 * ⚠ One row per file: a photo tagged both "dog" and "dogs" is still one photograph, and the first wins.
 */
internal fun citableLabelMatches(
    rows: List<Pair<String, String>>,
    needle: String,
    limit: Int,
): List<Pair<String, String>> = rows
    .filter { (path, label) ->
        label != INDEX_MARKER_LABEL && label.contains(needle, ignoreCase = true) && !isTrashedPath(path)
    }
    .distinctBy { it.first }
    .take(limit)

/**
 * Where a trashed photo moves to.
 *
 * ⚠ Prefixed with the content id, so two photos called `IMG_0001.jpg` trashed from different
 * folders cannot collide — and the prefix is the same id the record is keyed on, so the two halves
 * stay legible to a human reading `/Trash` in Drive.
 *
 * ⚠⚠ Pure for the same reason as [mintAlbumId], which has the same failure mode: every trashed
 * photo targeting one identical path is a collision on the *second* trash.
 */
internal fun trashPathFor(contentId: String, name: String): String = "$TRASH_ROOT/$contentId-$name"


