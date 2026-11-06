package app.tenshu.photos.data

import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import app.tenshu.photos.model.FaceGroup
import app.tenshu.photos.model.NamingOutcome
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoLabel
import app.tenshu.photos.model.PhotoPlace
import app.tenshu.photos.model.SemanticSearchStatus
import org.tenshu.inference.INDEX_MARKER_LABEL
import org.tenshu.inference.InferenceCapability

/**
 * An in-memory feed for previews, desktop render tests and the view-model tests — none of which
 * may need a running Control (the `MockContactsRepository` precedent).
 *
 * It pages exactly as the real one does, keyset and all, because paging is the part of the feed
 * most likely to be wrong and a mock that ignores the cursor would hide it.
 */
class MockPhotosRepository(
    photos: List<Photo> = samplePhotos(),
    /** The places `:place-service` would return. Empty by default — most users have none. */
    private val places: List<PhotoPlace> = emptyList(),
    /** What every read returns instead of a page — for exercising the shell's two failure states. */
    private val outcome: PhotoPageResult? = null,
    /**
     * What [photographicSettings] answers.
     *
     * ⚠ Defaults to [ExifReadResult.NotOnDevice], not to a parsed blob. That is the honest default
     * for a preview or a render test — no bytes are anywhere near them — and it means a screen that
     * only works when the EXIF happens to be present fails loudly in tests rather than quietly on a
     * phone.
     */
    private val exif: ExifReadResult = ExifReadResult.NotOnDevice,
    /** Which albums each `contentId` belongs to, for the About sheet's row. */
    private val albumsByContentId: Map<String, List<AlbumSummary>> = emptyMap(),
    /**
     * The `MOTION` clips this device "holds", by path (`photos-video-support.md` M5). Empty by
     * default — which is the honest state of every video uploaded before M4, and what makes a
     * rotation test have to say which tiles are eligible rather than assume all are.
     */
    private val motionClips: Map<String, ByteArray> = emptyMap(),
) : PhotosRepository {

    /** Every [motionClip] read, in order — the rotation's one-in-flight rule is a count on this. */
    val motionClipReads = mutableListOf<String>()

    override suspend fun motionClip(path: String): ByteArray? {
        motionClipReads += path
        return motionClips[path]
    }

    override suspend fun photographicSettings(path: String): ExifReadResult = exif

    override suspend fun albumsFor(contentId: String): List<AlbumSummary> =
        if (contentId.isEmpty()) emptyList()
        else albumsByContentId[contentId] ?: emptyList()


    /** Newest first, like the realm read; a caller may hand them in any order. */
    private var all = photos.sortedByDescending { it.capturedAtMs }

    /**
     * Replace the library mid-test — the realm gaining a photograph while a screen is open.
     *
     * ⚠ `var`, and the only mutable thing here besides [pagesServed]. Without it there is no way to
     * exercise a read that happens twice with different data, which is exactly what the feed's
     * resume refresh is for.
     */
    fun setPhotos(photos: List<Photo>) {
        all = photos.sortedByDescending { it.capturedAtMs }
    }

    var pagesServed: Int = 0
        private set

    override suspend fun feedPage(beforeMs: Long, skip: Int, pageSize: Int): PhotoPageResult {
        outcome?.let { return it }
        pagesServed++
        // ⚠⚠ `<=` and a `drop`, MIRRORING the real repository — and this line used to be `<`.
        // That single character was why no mock-backed test could see the tie defect: the real
        // reader asks for `inRange(0, before)`, which is inclusive, so it repeated the boundary
        // row, while this one skipped it. Both wrong, in opposite directions, and a mock that
        // disagrees with production at a boundary hides exactly the bugs that live there.
        val window = all.filter { it.capturedAtMs <= beforeMs }.drop(skip).take(pageSize)
        return PhotoPageResult.Page(window, hasMore = window.size >= pageSize)
    }

    /** The watch this mock answers with — `null`, the default, is "cannot tell". Set by a test or a fixture. */
    var watch: LibraryWatch? = null

    override fun libraryWatch(): LibraryWatch? = watch

    /**
     * The feed as `PagingData`, served whole.
     *
     * ⚠⚠ **Deliberately not a fake pager.** The mock used to reimplement the window (`<=`, `drop`,
     * `take`) and the single character it got wrong — `<` where production used `inRange` — was the
     * reason no mock-backed test could see the tie defect, while the guard written to catch that
     * very divergence asserted the mock's side of it. The paging arithmetic now lives in
     * `:core-paging` and is tested there against a fixture that really slices, so the honest thing
     * for a mock to do is not to have a second copy of it. A test that needs the *arithmetic* wants
     * `KeysetPagingSourceTest`; a test that needs a screen with photographs in it wants this.
     *
     * ⚠ [outcome] still rides through, as a `LoadState.Error` carrying [FeedReadException] — the
     * screen's "can't reach Control" path has to be reachable from a mock or it is untested.
     */
    override fun feedPaging(pageSize: Int, readContext: kotlin.coroutines.CoroutineContext): Flow<PagingData<Photo>> {
        pagesServed++
        return when (val failure = outcome) {
            null -> flowOf(PagingData.from(all, sourceLoadStates = settled()))
            is PhotoPageResult.Unreachable -> flowOf(
                PagingData.empty(
                    sourceLoadStates = errorStates(FeedReadException(unreachable = true, detail = "unreachable")),
                )
            )
            is PhotoPageResult.Failed -> flowOf(
                PagingData.empty(
                    sourceLoadStates = errorStates(FeedReadException(unreachable = false, detail = failure.message)),
                )
            )
            is PhotoPageResult.Page ->
                flowOf(PagingData.from(failure.photos, sourceLoadStates = settled()))
        }
    }

    /**
     * ⚠ Derived from the same list [feedPaging] serves, so the mock cannot disagree with itself
     * about which photographs the feed holds — the fidelity guard `feedWindow` learned the hard way
     * (a mock using `<` where the chain used `<=` hid a real page-boundary loss).
     *
     * ⚠ Honours [outcome]: a repository that cannot serve the feed cannot serve its density map
     * either, and returning a map anyway would draw pills over an error screen.
     */
    override suspend fun feedCaptureTimes(): List<Long>? = when (val failure = outcome) {
        null -> all.map { it.capturedAtMs }
        is PhotoPageResult.Page -> failure.photos.map { it.capturedAtMs }
        else -> null
    }

    /**
     * A finished load: nothing in flight, nothing more to fetch.
     *
     * ⚠⚠ Not the default, and the difference is a whole screen. `PagingData.from(data)` passes
     * `sourceLoadStates = null`, which leaves the presenter on its INITIAL states — `refresh` stays
     * `Loading` forever. A screen that reads "empty **and** refresh finished" to decide it may say
     * "No photos yet" then never says it, and against a mock library with rows in it nothing looks
     * wrong, because rows render whatever the load state claims. The empty state would have been
     * the one thing this mock could not exercise.
     */
    private fun settled() = LoadStates(
        refresh = LoadState.NotLoading(endOfPaginationReached = false),
        prepend = LoadState.NotLoading(endOfPaginationReached = true),
        append = LoadState.NotLoading(endOfPaginationReached = true),
    )

    private fun errorStates(cause: Throwable) = LoadStates(
        refresh = LoadState.Error(cause),
        prepend = LoadState.NotLoading(endOfPaginationReached = true),
        append = LoadState.NotLoading(endOfPaginationReached = true),
    )

    override suspend fun preview(path: String, width: Int, height: Int): PhotoPreview? =
        if (all.any { it.path == path }) PhotoPreview(ByteArray(0)) else null

    override fun viewerPreview(path: String, requestPx: Int, mayMint: Flow<Unit>): Flow<PhotoPreview> = flow {
        preview(path, requestPx, requestPx)?.let { emit(it) }
    }

    override suspend fun ensureViewerRung(path: String, userRequested: Boolean): ByteArray? = null

    // ── albums (PH-2) ────────────────────────────────────────────────────────────────────────
    //
    // ⚠ In-memory, but with the real semantics the screens depend on: album order is newest
    // first, entry order is insertion order, and a photo with no content id cannot be filed. A
    // mock that ignored the last one would let a screen ship an action that always fails on a
    // device.

    private val albums = mutableMapOf<String, AlbumSummary>()
    private val entries = mutableMapOf<String, MutableList<Photo>>()
    private val favourites = mutableSetOf<String>()

    override suspend fun albums(): AlbumsResult {
        outcome?.let { return AlbumsResult.Unreachable }
        return AlbumsResult.Albums(
            albums.values.map { summary ->
                val mine = entries[summary.albumId].orEmpty()
                summary.copy(photoCount = mine.size, coverPath = mine.firstOrNull()?.path)
            }.sortedByDescending { it.createdAtMs }
        )
    }

    override suspend fun albumPhotos(albumId: String): PhotoPageResult {
        outcome?.let { return it }
        return PhotoPageResult.Page(entries[albumId].orEmpty().toList(), hasMore = false)
    }

    override suspend fun createAlbum(name: String): String? {
        val id = "album-${albums.size + 1}"
        albums[id] = AlbumSummary(id, name, createdAtMs = albums.size.toLong(), photoCount = 0, coverPath = null)
        return id
    }

    override suspend fun renameAlbum(albumId: String, name: String): Boolean {
        val existing = albums[albumId] ?: return false
        albums[albumId] = existing.copy(name = name)
        return true
    }

    override suspend fun deleteAlbum(albumId: String): Boolean {
        entries.remove(albumId)
        return albums.remove(albumId) != null
    }

    override suspend fun addToAlbum(albumId: String, photo: Photo): Boolean {
        if (photo.contentId.isEmpty()) return false
        val mine = entries.getOrPut(albumId) { mutableListOf() }
        if (mine.none { it.contentId == photo.contentId }) mine += photo
        return true
    }

    override suspend fun removeFromAlbum(albumId: String, contentId: String): Boolean =
        entries[albumId]?.removeAll { it.contentId == contentId } == true

    override suspend fun favourites(): PhotoPageResult {
        outcome?.let { return it }
        return PhotoPageResult.Page(all.filter { it.contentId in favourites }, hasMore = false)
    }

    override suspend fun places(): List<PhotoPlace> = places

    // ── trash (PH-5) ─────────────────────────────────────────────────────────────────────────

    private val trash = mutableMapOf<String, TrashedItem>()

    override suspend fun trash(photo: Photo): Boolean {
        // ⚠ An [outcome] makes writes fail too, not just reads. A mock whose trash always succeeds
        // cannot express "the move did not happen", which is the state the `/Trash` incident lived
        // in — and a caller that has to handle it deserves a way to test that it does.
        if (outcome != null) return false
        if (photo.contentId.isEmpty()) return false
        trash[photo.contentId] = TrashedItem(
            contentId = photo.contentId,
            photo = photo.copy(path = "/Trash/${photo.contentId}-${photo.name}"),
            originalPath = photo.path,
            trashedAtMs = trash.size.toLong(),
        )
        return true
    }

    override suspend fun restore(contentId: String): Boolean = trash.remove(contentId) != null

    override suspend fun deleteForever(contentId: String): Boolean {
        // ⚠ Mirrors the real one: the photograph goes, the record goes with it.
        val removed = trash.remove(contentId) ?: return false
        purged += removed.contentId
        return true
    }

    override suspend fun trashed(): TrashResult {
        outcome?.let { return TrashResult.Unreachable }
        return TrashResult.Items(trash.values.sortedByDescending { it.trashedAtMs })
    }

    /** What `deleteForever` removed — so a test can assert a photograph really went. */
    val purged = mutableSetOf<String>()

    // ── duplicates (PH-6) ────────────────────────────────────────────────────────────────────

    /**
     * Duplicate groups by content hash, over whatever [Photo.contentHash] the fixture carries.
     *
     * ⚠ Trashed photos are excluded, like the real index: a screen that lists a copy the user has
     * already deleted, and offers to delete it again, is the bug this mirrors rather than hides.
     */
    override suspend fun duplicates(): DuplicatesResult {
        outcome?.let { return DuplicatesResult.Unreachable }
        val live = all.filter { it.contentId !in trash.keys && it.contentId !in purged }
        val groups = live
            .filter { it.contentHash.isNotEmpty() }
            .groupBy { it.contentHash }
            .values
            .filter { it.size > 1 }
            .map { group ->
                val ordered = group.sortedBy { it.capturedAtMs }
                DuplicateGroup(
                    contentHash = ordered.first().contentHash,
                    photos = ordered,
                    sizeBytes = 1_000L,
                )
            }
            .sortedByDescending { it.photos.size }
        return DuplicatesResult.Groups(groups, filesScanned = live.size)
    }

    // ── search (S2) ──────────────────────────────────────────────────────────────────────────

    /**
     * Labels a test can plant. Empty by default — like every real device today, where the backend
     * registry is `Empty` and no pass has ever written a row (`inference-service.md` §17.4).
     *
     * ⚠ Plant one with [label]; the sentinel is plantable too, on purpose, because the thing worth
     * testing is that readers drop it.
     */
    val labels = mutableListOf<PhotoLabel>()

    /**
     * Plants a label. Confident by default, so a test opts *in* to the weak-label case.
     *
     * ⚠ [capability] defaults to tagging because that is what a *model* produces. A test that
     * wants a person's name plants `FACE_CLUSTERING` explicitly — and the Things section must
     * then not show it (IN-6 F3b).
     */
    fun label(
        path: String,
        label: String,
        confidencePerMille: Int = 1000,
        capability: String = InferenceCapability.CONTENT_TAGGING.id,
    ) {
        labels += PhotoLabel(path, label, confidencePerMille, capability)
    }

    /**
     * What [searchSemantic] answers. ⚠ Defaults to **`NO_BACKEND`**, because that is what an actual
     * device answers: the production backend registry is empty (`inference-service.md` §17), so a
     * test that assumed hits would be testing a configuration nobody has.
     */
    var semanticOutcome: Pair<List<Photo>, SemanticSearchStatus> =
        emptyList<Photo>() to SemanticSearchStatus.Unavailable("noBackend")

    /** ⚠ Trashed photos excluded, like the real index — this is what the Collections tab groups over. */
    /** How many times the shared index was read — what a re-read is observable by. */
    var allPhotosReads: Int = 0
        private set

    override suspend fun allPhotos(): List<Photo> {
        allPhotosReads++
        return all.filter { it.contentId !in trash.keys && it.contentId !in purged }
    }

    override suspend fun searchByName(query: String): List<Photo> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return all.filter { it.name.contains(needle, ignoreCase = true) && citable(it) }
    }

    /**
     * ⚠⚠ What the real repository's `citablePhotoAt` answers (`photos-ios.md` §8.19): a search hit may
     * cite only a photograph still in the library — not one in the trash, not one deleted. This mock's
     * label and "looks like" joins used to return both, so a screen tested on it could ship citing
     * photographs that are gone.
     */
    private fun citable(photo: Photo): Boolean = photo.contentId !in trash.keys && photo.contentId !in purged

    /**
     * ⚠⚠ Drops the sentinel exactly where the real read does. A fake that answered on `__indexed`
     * would let a screen ship a search that returns the whole library — the failure mode the real
     * one carried until the constant moved out of the proxy module.
     */
    override suspend fun searchByLabel(query: String): List<Pair<Photo, String>> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return labels
            .filter { it.label != INDEX_MARKER_LABEL && it.label.contains(needle, ignoreCase = true) }
            .distinctBy { it.path }
            .mapNotNull { row -> all.firstOrNull { it.path == row.path && citable(it) }?.let { it to row.label } }
    }

    /** ⚠ Sentinel-free, like the real read — see [searchByLabel]. */
    override suspend fun allLabels(): List<PhotoLabel> =
        labels.filter { it.label != INDEX_MARKER_LABEL }

    /**
     * ⚠ Defaults to a REFUSAL, not to an empty success. A mock that answered
     * `Available to emptyList()` would let a screen ship having only ever been seen in the state
     * People is never actually in on a device without a model.
     */
    var peopleOutcome: Pair<List<FaceGroup>, PeopleStatus> =
        emptyList<FaceGroup>() to PeopleStatus.Unavailable("noBackend")

    override suspend fun faceGroups(limit: Int): Pair<List<FaceGroup>, PeopleStatus> =
        peopleOutcome.first.take(limit) to peopleOutcome.second

    /** Every (paths, name) the caller asked to write, in order. */
    val namedGroups = mutableListOf<Pair<List<String>, String>>()

    /**
     * ⚠ Settable, because a caller that treats naming as infallible is the defect worth
     * catching: consent can be withdrawn while a naming sheet is open, and Control can go away.
     */
    var namingOutcome: NamingOutcome = NamingOutcome.Saved

    /**
     * Photographs to accept before [namingOutcome] takes over — `null` accepts all of them.
     *
     * ⚠⚠ Without this the PARTIAL case cannot be reached from a test at all: the real
     * repository splits a large person across several calls, and the defect worth catching is the
     * one where an early call persists rows and a later one is refused.
     */
    var namingAcceptsFirst: Int? = null

    override suspend fun nameFaceGroup(filePaths: List<String>, name: String): NamingOutcome {
        val accept = namingAcceptsFirst
        if (accept != null && accept < filePaths.size) {
            if (accept > 0) namedGroups += filePaths.take(accept) to name
            // ⚠ The real repository reaches this by comparing the proxy's returned COUNT against
            // the chunk it sent — a short count is a torn write, not a success.
            return if (accept > 0) NamingOutcome.PartiallySaved else namingOutcome
        }
        if (namingOutcome != NamingOutcome.Saved) return namingOutcome
        namedGroups += filePaths to name
        return NamingOutcome.Saved
    }

    override suspend fun searchSemantic(
        query: String,
        limit: Int,
    ): Pair<List<Photo>, SemanticSearchStatus> {
        if (query.isBlank()) return emptyList<Photo>() to SemanticSearchStatus.Available
        return semanticOutcome.first.filter { citable(it) }.take(limit) to semanticOutcome.second
    }

    /** Details a test can plant per path; absent paths answer `null`, like the real read. */
    val detailsByPath = mutableMapOf<String, app.tenshu.photos.model.PhotoDetails>()

    override suspend fun details(path: String): app.tenshu.photos.model.PhotoDetails? =
        detailsByPath[path] ?: all.firstOrNull { it.path == path }?.let {
            app.tenshu.photos.model.PhotoDetails(
                path = it.path,
                capturedAtMs = it.capturedAtMs,
                sizeBytes = null,
            )
        }

    override suspend fun deletePermanently(photo: Photo): Boolean {
        if (photo.contentId.isEmpty()) return false
        purged += photo.contentId
        return true
    }

    /** ⚠ Like the real read: a trashed or deleted copy is not at its path any more. */
    override suspend fun holdsContent(path: String, contentHash: String): Boolean =
        outcome == null && contentHash.isNotEmpty() && all.any {
            it.path == path && it.contentHash == contentHash && it.contentId !in trash.keys && it.contentId !in purged
        }

    override suspend fun isFavourite(contentId: String): Boolean = contentId in favourites

    override suspend fun setFavourite(contentId: String, favourite: Boolean): Boolean {
        if (contentId.isEmpty()) return false
        if (favourite) favourites += contentId else favourites -= contentId
        return true
    }

    companion object {
        /**
         * One photo a minute, walking backwards from a fixed instant — enough to page over.
         *
         * ⚠ Every fourth row is a **video**, and one of those carries no duration
         * (`photos-video-support.md` M1). A stills-only fixture would make the tile's video
         * decoration unreachable from the mock, so the desktop app and every preview would render
         * a branch nobody had ever looked at — and the unknown-duration case, which is the one
         * that must draw *no* badge rather than `0:00`, would exist only in a unit test.
         */
        fun samplePhotos(count: Int = 12, startMs: Long = 1_700_000_000_000L): List<Photo> =
            (0 until count).map { i ->
                val isVideo = i % 4 == 3
                Photo(
                    path = "/Backup/Phone/Camera/" +
                        if (isVideo) "VID_${1000 + i}.mp4" else "IMG_${1000 + i}.jpg",
                    capturedAtMs = startMs - i * 60_000L,
                    isVideo = isVideo,
                    // ⚠ The i == 3 video is deliberately duration-less — see the KDoc. The others
                    // walk a short clip, past a minute, and past an hour, so the formatter's two
                    // shapes are both visible in the running app.
                    durationMs = when {
                        !isVideo -> null
                        i == 3 -> null
                        else -> 6_000L + i * 41_000L
                    },
                    // ⚠ Every sample photo carries one: a fixture whose photos could not be filed
                    // would make the album tests pass for the wrong reason.
                    contentId = "c$i",
                )
            }
    }
}
