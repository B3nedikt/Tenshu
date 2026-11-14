package app.tenshu.photos.data

import androidx.paging.PagingData
import app.tenshu.photos.model.FaceGroup
import app.tenshu.photos.model.NamingOutcome
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoDetails
import app.tenshu.photos.model.PhotoLabel
import app.tenshu.photos.model.PhotoPlace
import app.tenshu.photos.model.SemanticSearchStatus
import app.tenshu.reachability.UnreachableReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * A [PhotosRepository] that does something around every call to another one — the one seam through
 * which "where a repository call runs" is decided for a whole shell (`photos-ios.md` §8.22.2).
 *
 * ⚠⚠ **Every member is written out, and there is deliberately no `by inner`.** Delegation forwards a
 * member this class does not name straight to [inner], in the caller's context — so a read added to
 * the interface later would compile, pass every test, and run on the iOS main thread. Written out, a
 * new abstract member is a compile error here, which is the moment to decide. ⚠ The interface's
 * DEFAULT members (`placesRead`, `allPhotosRead`, …) are forwarded to [inner]'s own override too,
 * never left to the default: the default would call this class's `places()` and lose the distinction
 * the `…Read` forms exist for.
 */
abstract class PhotosRepositoryDecorator(protected val inner: PhotosRepository) : PhotosRepository {

    /** Runs one READ — [name] is the member's, for a decorator that records or reports. */
    protected abstract suspend fun <T> call(name: String, block: suspend () -> T): T

    /** Runs one WRITE — anything that changes what the realm holds. The same as a read unless overridden. */
    protected open suspend fun <T> write(name: String, block: suspend () -> T): T = call(name, block)

    /**
     * A stream [inner] returned — a read made where it is COLLECTED. The reachability streams are
     * state, not reads, and do not pass here.
     *
     * ⚠ Abstract, like [call], and for the reason the class has no `by` delegation: with a default
     * that passed the stream through, a decorator that counted or moved every `call` would leave
     * streams out without a word.
     */
    protected abstract fun <T> stream(name: String, flow: Flow<T>): Flow<T>

    /** The context the feed's page reads are asked to run in — see [PhotosRepository.feedPaging]. */
    protected open fun pagingReadContext(asked: CoroutineContext): CoroutineContext = asked

    override fun controlReachable(): Flow<Boolean?> = inner.controlReachable()
    override fun controlUnreachableReason(): StateFlow<UnreachableReason?> = inner.controlUnreachableReason()
    override fun recheckControl() = inner.recheckControl()

    override suspend fun feedPage(beforeMs: Long, skip: Int, pageSize: Int): PhotoPageResult =
        call("feedPage") { inner.feedPage(beforeMs, skip, pageSize) }

    override fun feedPaging(pageSize: Int, readContext: CoroutineContext): Flow<PagingData<Photo>> =
        inner.feedPaging(pageSize, pagingReadContext(readContext))

    override suspend fun feedCaptureTimes(): List<Long>? = call("feedCaptureTimes") { inner.feedCaptureTimes() }

    /** State, like the reachability streams: forwarded as it is. */
    override fun libraryWatch(): LibraryWatch? = inner.libraryWatch()

    override suspend fun preview(path: String, width: Int, height: Int): PhotoPreview? =
        call("preview") { inner.preview(path, width, height) }

    override fun viewerPreview(path: String, requestPx: Int, mayMint: Flow<Unit>): Flow<PhotoPreview> =
        stream("viewerPreview", inner.viewerPreview(path, requestPx, mayMint))

    override suspend fun ensureViewerRung(path: String, userRequested: Boolean): ByteArray? =
        call("ensureViewerRung") { inner.ensureViewerRung(path, userRequested) }

    override suspend fun motionClip(path: String): ByteArray? = call("motionClip") { inner.motionClip(path) }

    override suspend fun albums(): AlbumsResult = call("albums") { inner.albums() }
    override suspend fun albumPhotos(albumId: String): PhotoPageResult = call("albumPhotos") { inner.albumPhotos(albumId) }
    override suspend fun createAlbum(name: String): String? = write("createAlbum") { inner.createAlbum(name) }
    override suspend fun renameAlbum(albumId: String, name: String): Boolean =
        write("renameAlbum") { inner.renameAlbum(albumId, name) }
    override suspend fun deleteAlbum(albumId: String): Boolean = write("deleteAlbum") { inner.deleteAlbum(albumId) }
    override suspend fun addToAlbum(albumId: String, photo: Photo): Boolean =
        write("addToAlbum") { inner.addToAlbum(albumId, photo) }
    override suspend fun removeFromAlbum(albumId: String, contentId: String): Boolean =
        write("removeFromAlbum") { inner.removeFromAlbum(albumId, contentId) }
    override suspend fun albumsFor(contentId: String): List<AlbumSummary> = call("albumsFor") { inner.albumsFor(contentId) }

    override suspend fun favourites(): PhotoPageResult = call("favourites") { inner.favourites() }
    override suspend fun isFavourite(contentId: String): Boolean = call("isFavourite") { inner.isFavourite(contentId) }
    override suspend fun setFavourite(contentId: String, favourite: Boolean): Boolean =
        write("setFavourite") { inner.setFavourite(contentId, favourite) }

    override suspend fun places(): List<PhotoPlace> = call("places") { inner.places() }
    override suspend fun placesRead(): List<PhotoPlace>? = call("placesRead") { inner.placesRead() }

    override suspend fun trash(photo: Photo): Boolean = write("trash") { inner.trash(photo) }
    override suspend fun restore(contentId: String): Boolean = write("restore") { inner.restore(contentId) }
    override suspend fun deleteForever(contentId: String): Boolean = write("deleteForever") { inner.deleteForever(contentId) }
    override suspend fun trashed(): TrashResult = call("trashed") { inner.trashed() }

    override suspend fun duplicates(): DuplicatesResult = call("duplicates") { inner.duplicates() }
    override suspend fun deletePermanently(photo: Photo): Boolean = write("deletePermanently") { inner.deletePermanently(photo) }
    override suspend fun holdsContent(path: String, contentHash: String): Boolean =
        call("holdsContent") { inner.holdsContent(path, contentHash) }

    override suspend fun details(path: String): PhotoDetails? = call("details") { inner.details(path) }
    override suspend fun photographicSettings(path: String): ExifReadResult =
        call("photographicSettings") { inner.photographicSettings(path) }
    override suspend fun originalBytes(path: String): ByteArray? = call("originalBytes") { inner.originalBytes(path) }
    override suspend fun saveEditedCopy(original: Photo, rendered: ByteArray): Photo? =
        write("saveEditedCopy") { inner.saveEditedCopy(original, rendered) }
    override suspend fun photoEdit(contentId: String): StoredEdit? = call("photoEdit") { inner.photoEdit(contentId) }
    override suspend fun saveEdit(original: Photo, recipe: app.tenshu.photos.edit.EditRecipe, rendered: ByteArray): Photo? =
        write("saveEdit") { inner.saveEdit(original, recipe, rendered) }
    override suspend fun revertEdit(photo: Photo): Photo? = write("revertEdit") { inner.revertEdit(photo) }

    override suspend fun searchByName(query: String): List<Photo> = call("searchByName") { inner.searchByName(query) }
    override suspend fun searchByLabel(query: String): List<Pair<Photo, String>> =
        call("searchByLabel") { inner.searchByLabel(query) }
    override suspend fun searchByNameRead(query: String): List<Photo>? = call("searchByNameRead") { inner.searchByNameRead(query) }
    override suspend fun searchByLabelRead(query: String): List<Pair<Photo, String>>? =
        call("searchByLabelRead") { inner.searchByLabelRead(query) }
    override suspend fun searchSemantic(query: String, limit: Int): Pair<List<Photo>, SemanticSearchStatus> =
        call("searchSemantic") { inner.searchSemantic(query, limit) }

    override suspend fun allPhotos(): List<Photo> = call("allPhotos") { inner.allPhotos() }
    override suspend fun allPhotosRead(): List<Photo>? = call("allPhotosRead") { inner.allPhotosRead() }
    override suspend fun allLabels(): List<PhotoLabel> = call("allLabels") { inner.allLabels() }
    override suspend fun allLabelsRead(): List<PhotoLabel>? = call("allLabelsRead") { inner.allLabelsRead() }
    override suspend fun faceGroups(limit: Int): Pair<List<FaceGroup>, PeopleStatus> =
        call("faceGroups") { inner.faceGroups(limit) }
    override suspend fun editedContentIds(): Set<String>? =
        call("editedContentIds") { inner.editedContentIds() }
    override suspend fun nameFaceGroup(filePaths: List<String>, name: String): NamingOutcome =
        write("nameFaceGroup") { inner.nameFaceGroup(filePaths, name) }
    override suspend fun faceSuggestionRejections(): List<app.tenshu.photos.model.FaceRejection>? =
        call("faceSuggestionRejections") { inner.faceSuggestionRejections() }
    override suspend fun rejectNameSuggestion(name: String, faces: List<app.tenshu.photos.model.GroupFace>): Boolean =
        write("rejectNameSuggestion") { inner.rejectNameSuggestion(name, faces) }
}

/**
 * [inner], with every read and write run on [dispatcher] instead of in the caller
 * (`photos-ios.md` §8.22.2).
 *
 * ⚠⚠ **This is the iOS shell's, and only its.** There the repository is the in-process route-1
 * runtime: a call is a store query on the thread that makes it, and every view model's scope is
 * `Main.immediate` — so a call made from a view model, or from a Swift `Task` on the main actor,
 * holds the main thread for as long as the query runs. A walk of the library is ~2 s on a phone.
 * The Compose shells do not wrap: their calls are IPC and already suspend.
 *
 * What it changes for a caller is only WHERE the call runs. The caller's coroutine stays in its own
 * context — a view model still reads and publishes its state from `viewModelScope` and nothing else
 * touches it — and it suspends where on iOS it would otherwise block, which is what every one of
 * these view models already does on Android.
 *
 * ⚠⚠ **Reads run side by side; writes run one at a time, on a lane of their own.** A write here is
 * several store operations — `trash` makes the folder, moves the file, then records the move;
 * `addToAlbum` reads the last position, then saves the next — and the store's lock covers each
 * operation, not the sequence. On the main thread a sequence that does not suspend ran start to
 * finish with nothing between its steps; on a pool two of them interleave, and two adds to one album
 * take the same position. The lane gives back exactly what the main thread gave: one write's steps
 * are not interleaved with another's unless it suspends.
 *
 * ⚠ The feed's pages are not calls on this interface — Paging makes them, inside the `PagingSource` —
 * so they are moved by asking for it: [pagingReadContext].
 * ⚠ A view model's own work over what a read returned (grouping a library, a year index) is NOT moved
 * by this; where that is more than a pass over a page it takes the same dispatcher itself
 * (`CollectionsViewModel.workDispatcher`, `PhotosFeedViewModel.workDispatcher`).
 */
class OffCallerPhotosRepository(
    inner: PhotosRepository,
    private val dispatcher: CoroutineDispatcher,
) : PhotosRepositoryDecorator(inner) {

    private val writes: CoroutineDispatcher = dispatcher.limitedParallelism(1)

    override suspend fun <T> call(name: String, block: suspend () -> T): T = withContext(dispatcher) { block() }

    override suspend fun <T> write(name: String, block: suspend () -> T): T = withContext(writes) { block() }

    override fun <T> stream(name: String, flow: Flow<T>): Flow<T> = flow.flowOn(dispatcher)

    override fun pagingReadContext(asked: CoroutineContext): CoroutineContext = asked + dispatcher
}
