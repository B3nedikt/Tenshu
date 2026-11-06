package app.tenshu.photos.data

import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import app.tenshu.photos.model.FaceGroup
import app.tenshu.photos.model.NamingOutcome
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoDetails
import app.tenshu.photos.model.PhotoLabel
import app.tenshu.photos.model.PhotoPlace
import app.tenshu.photos.model.SemanticSearchStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The repository an **unprovisioned** Photos shell binds: no Control has handed this app its key
 * material, so there is no library to read (`photos-ios.md`; parity doc D4).
 *
 * ⚠⚠ **Never a mock behind the shipping gate.** A photo app that renders somebody else's
 * photographs — or none, as though the library were empty — has told the user something false about
 * the one thing it exists to keep.
 *
 * ⚠⚠ **Unreachable, never empty** (`photos-app.md` §10.3). Every read that has a typed failure
 * returns its `Unreachable` arm, and the feed fails with [FeedReadException] `unreachable = true`,
 * so each screen takes the same path it takes when Control stops answering on Android. The plain
 * list-returning reads (search, places, labels) answer empty, exactly as the real repository does
 * on a dead channel — which is safe only because [controlReachable] is `false`, and that is what
 * puts the shell's wall in front of all of them.
 *
 * Every write is refused (`false` / `null`), so no screen can report a change that went nowhere.
 *
 * ⚠ [controlUnreachableReason] stays `null` — *unknown*: whether Control is missing or merely not
 * set up is the shell's question (`ConnectToControlView` asks the OS), and a guess here would be
 * `UnreachableReason.NotInstalled`'s own warning — absence inferred from silence.
 */
class NotConnectedPhotosRepository : PhotosRepository {

    override fun controlReachable(): Flow<Boolean?> = flowOf(false)

    override suspend fun feedPage(beforeMs: Long, skip: Int, pageSize: Int): PhotoPageResult =
        PhotoPageResult.Unreachable

    override fun feedPaging(pageSize: Int, readContext: kotlin.coroutines.CoroutineContext): Flow<PagingData<Photo>> = flowOf(
        PagingData.empty(
            sourceLoadStates = LoadStates(
                refresh = LoadState.Error(FeedReadException(unreachable = true, detail = NOT_CONNECTED)),
                prepend = LoadState.NotLoading(endOfPaginationReached = true),
                append = LoadState.NotLoading(endOfPaginationReached = true),
            ),
        ),
    )

    /** ⚠ `null`, the "could not read" answer — an empty list would draw a scrubber over nothing. */
    override suspend fun feedCaptureTimes(): List<Long>? = null

    override suspend fun preview(path: String, width: Int, height: Int): PhotoPreview? = null

    override fun viewerPreview(path: String, requestPx: Int, mayMint: Flow<Unit>): Flow<PhotoPreview> =
        emptyFlow()

    override suspend fun ensureViewerRung(path: String, userRequested: Boolean): ByteArray? = null

    override suspend fun motionClip(path: String): ByteArray? = null

    override suspend fun albums(): AlbumsResult = AlbumsResult.Unreachable

    override suspend fun albumPhotos(albumId: String): PhotoPageResult = PhotoPageResult.Unreachable

    override suspend fun createAlbum(name: String): String? = null

    override suspend fun renameAlbum(albumId: String, name: String): Boolean = false

    override suspend fun deleteAlbum(albumId: String): Boolean = false

    override suspend fun addToAlbum(albumId: String, photo: Photo): Boolean = false

    override suspend fun removeFromAlbum(albumId: String, contentId: String): Boolean = false

    override suspend fun albumsFor(contentId: String): List<AlbumSummary> = emptyList()

    override suspend fun favourites(): PhotoPageResult = PhotoPageResult.Unreachable

    override suspend fun isFavourite(contentId: String): Boolean = false

    override suspend fun setFavourite(contentId: String, favourite: Boolean): Boolean = false

    override suspend fun places(): List<PhotoPlace> = emptyList()

    override suspend fun trash(photo: Photo): Boolean = false

    override suspend fun restore(contentId: String): Boolean = false

    override suspend fun deleteForever(contentId: String): Boolean = false

    override suspend fun trashed(): TrashResult = TrashResult.Unreachable

    override suspend fun duplicates(): DuplicatesResult = DuplicatesResult.Unreachable

    override suspend fun deletePermanently(photo: Photo): Boolean = false

    override suspend fun holdsContent(path: String, contentHash: String): Boolean = false

    override suspend fun details(path: String): PhotoDetails? = null

    override suspend fun photographicSettings(path: String): ExifReadResult = ExifReadResult.Unreachable

    override suspend fun searchByName(query: String): List<Photo> = emptyList()

    override suspend fun searchByLabel(query: String): List<Pair<Photo, String>> = emptyList()

    /** ⚠ `null`, not empty: nothing was searched (`photos-ios.md` §8.19) — "No photos match" would be a claim. */
    override suspend fun searchByNameRead(query: String): List<Photo>? = null

    override suspend fun searchByLabelRead(query: String): List<Pair<Photo, String>>? = null

    /** ⚠ `Failed`, not `Unavailable`: that arm names a *model* cause, and there is no model question here. */
    override suspend fun searchSemantic(query: String, limit: Int): Pair<List<Photo>, SemanticSearchStatus> =
        emptyList<Photo>() to SemanticSearchStatus.Failed

    /** ⚠ `Failed`, not `Unavailable`, for the same reason as [searchSemantic]: its reasons are all model or consent causes. */
    override suspend fun faceGroups(limit: Int): Pair<List<FaceGroup>, PeopleStatus> =
        emptyList<FaceGroup>() to PeopleStatus.Failed

    /** `Failed` is the arm whose documented cases include "Control not running" — the "try again" answer, never `NoConsent`. */
    override suspend fun nameFaceGroup(filePaths: List<String>, name: String): NamingOutcome =
        NamingOutcome.Failed

    override suspend fun allPhotos(): List<Photo> = emptyList()

    override suspend fun allLabels(): List<PhotoLabel> = emptyList()

    private companion object {
        const val NOT_CONNECTED = "Tenshu Control has not connected this app"
    }
}
