package app.tenshu.photos.ui.screens.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.data.ExifReadResult
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoDetails
import app.tenshu.photos.model.PhotoPlace
import app.tenshu.photos.model.nearestPlace
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the viewer knows that is not a picture (`photos-app.md` S3, `photos-viewer-about-screen.md`).
 *
 * ⚠⚠ **No bytes.** The viewer is a pager (`photos-app.md` §21) over the same list the grid showed,
 * and each page loads and holds its own hero — the `PhotoTile` rule, for the same reason: a view
 * model holding the bitmaps would keep every photograph the user has swiped past alive for as long
 * as the viewer is open, and a swipe through a day of photographs is hundreds of megabytes. The
 * pager composes a bounded window and drops the rest; a view model has no such window.
 *
 * [photo] is the one **currently on screen**, which after a swipe is not the one the user tapped.
 */
data class PhotoViewerUiState(
    val photo: Photo,
    /**
     * What the file says about itself. ⚠ `null` until the sheet's read lands, and it may stay
     * `null` — a row written before the metadata fields existed carries none, and the bytes the
     * values would be read from are not held by the proxy.
     */
    val details: PhotoDetails? = null,
    /**
     * How the photograph was taken, parsed on this device.
     *
     * ⚠ `null` means *not read yet*; [ExifReadResult.NotOnDevice] means read and answered. The
     * panel renders those differently — the first is a wait, the second is a fact — which is why
     * this is not simply a nullable [org.tenshu.core.file.MediaMetadata].
     */
    val exif: ExifReadResult? = null,
    /**
     * The albums this photograph is in. ⚠ `null` while unread, empty once read and it is in
     * none — the section is omitted for both, but only the second is a fact.
     */
    val albums: List<AlbumSummary>? = null,
    /**
     * Whether the About sheet is open.
     *
     * ⚠⚠ While it is, the pager is **locked**. A sheet describing one photograph above a pager
     * showing another is §21.4's stale-metadata bug wearing a bigger hat, and every field on this
     * state would be describing the wrong picture at once.
     */
    val infoOpen: Boolean = false,
    /**
     * Whether the photo on screen is favourited — `null` while the read is in flight.
     *
     * ⚠⚠ Paired with [canFavourite], never conflated with it: a file predating content addressing
     * has no `contentId`, the flag ops refuse it, and the star must be **disabled** rather than
     * drawn hollow and failing on tap. That is the rule the feed's long-press already follows.
     */
    val favourite: Boolean? = null,
    /** Whether the flag can be written at all — false for a photo with no `contentId`. */
    val canFavourite: Boolean = false,
    /**
     * Where this photograph was taken, when a **named** `Place` claims its coordinate (D2).
     *
     * ⚠ `null` for most photographs, and that is not a failure — see `nearestPlace`. The subtitle
     * omits the line rather than inventing one.
     */
    val place: PhotoPlace? = null,
    /**
     * Whether the chrome is showing (§3.1b).
     *
     * ⚠⚠ **One flag for our chrome *and* the OS bars.** Restoring one without the other makes
     * `WindowInsets.safeDrawing` describe bars that are not there, and the back arrow drifts into
     * the space they vacated (`photos-app.md` §21.6b).
     */
    val chromeVisible: Boolean = true,
    /**
     * A write the viewer started — the star, Add to Album, Trash — is still running.
     *
     * ⚠⚠ The shells disable the star and the menu on it, and the view model drops a second write
     * while it is `true`. Two writes racing each other end in whichever finishes LAST, which need not
     * be the user's latest choice: two quick star taps could leave the store `true` under a hollow
     * star. And a dropped write never calls back, so a choice made in that window
     * would vanish without a word if the chrome still offered it.
     *
     * ⚠ On the state rather than beside it, so both shells read it from the one place they already
     * observe.
     */
    val writing: Boolean = false,
) {
    val path: String get() = photo.path
}

/**
 * Follows the pager: which photograph is on screen, and everything about it that is not pixels.
 *
 * ⚠ One instance per viewer session, **not** one per photo. The hero bytes moved into the pages
 * (see [PhotoViewerUiState]), so what is left is a cursor and a few small reads — and a view model
 * per page would mean one retained in the store for every photograph swiped past.
 */
class PhotoViewerViewModel(
    private val repository: PhotosRepository,
    /** The photo the user tapped. A seed only — the pager reports the real one on its first frame. */
    photo: Photo,
) : ViewModel() {

    private val _state = MutableStateFlow(PhotoViewerUiState(photo = photo))
    val state: StateFlow<PhotoViewerUiState> = _state.asStateFlow()

    /**
     * Every named place in the realm, read **once per viewer session**.
     *
     * ⚠⚠ Once, not per page. `groupPhotosByPlace` makes the same argument for the Places section
     * and it holds harder here: a read per swipe would put an IPC round trip on the app's most
     * repeated gesture to answer something that is pure arithmetic once the list is in hand.
     */
    private var places: List<PhotoPlace> = emptyList()

    /**
     * The in-flight per-photo read.
     *
     * ⚠⚠ Cancelled on the next swipe. During a fast flick a dozen reads start and eleven of their
     * answers are about photographs no longer on screen — one landing late would light the star for
     * the wrong picture, which is §21.4's stale-metadata bug in a second field.
     */
    private var perPhotoJob: Job? = null

    init {
        loadPerPhoto(photo)
        viewModelScope.launch {
            places = repository.places()
            // ⚠ The pager may have moved while this was in flight — resolve against what is current,
            // never against the seed.
            _state.value = _state.value.copy(place = placeOf(_state.value.photo))
        }
    }

    /**
     * The pager moved to another photograph.
     *
     * ⚠⚠ Clears [PhotoViewerUiState.details] and [PhotoViewerUiState.favourite] in the same
     * emission as it moves the photo. The sheet takes the name from one field and the camera from
     * another, so a value that outlives its photograph by even one frame prints one picture's
     * dimensions under another's name — an assertion neither row makes.
     */
    fun onPhotoShown(photo: Photo) {
        val current = _state.value
        if (current.photo.path == photo.path) return
        _state.value = current.copy(
            photo = photo,
            details = null,
            // ⚠⚠ Cleared with the same emission, for the same reason as `details`. This one is
            // worse if it lingers: the previous photograph's aperture under this one's name is a
            // claim about a picture that was never taken that way.
            exif = null,
            albums = null,
            favourite = null,
            // ⚠ Arithmetic, not a read — [places] is already in hand, so this one field is allowed
            // to be correct immediately rather than arriving a frame later.
            place = placeOf(photo),
        )
        loadPerPhoto(photo)
        // ⚠ Re-read only for a sheet that is open. Swiping with it closed must stay as cheap as
        // swiping was before the sheet existed — and the EXIF read is the most expensive of all of
        // them, since it reads the file's bytes.
        if (current.infoOpen) {
            loadDetails(photo.path)
            loadExif(photo.path)
            loadAlbums(photo)
        }
    }

    /**
     * Show or hide the info sheet, reading the file's metadata the first time it is opened.
     *
     * ⚠ Lazy on purpose. The sheet is opened on a minority of the photos anyone looks at, and the
     * read is a search of the realm for one row — paying it on every viewer open would make the
     * most-used screen in the app slower for a panel most people never see.
     */
    fun toggleInfo() = setInfoOpen(!_state.value.infoOpen)

    /**
     * Set the panel's state directly.
     *
     * ⚠⚠ **Idempotent on purpose, and that is why `toggleInfo` is not the only way in.** The
     * panel is a scroll position on the page rather than an overlay (§11), so the *user* can
     * open it by dragging, and the screen has to report that back. A toggle cannot express "it is
     * open now" — reporting an already-open panel would close it — so the two-way binding needs a
     * setter, and re-reporting the state it is already in has to do nothing at all.
     */
    fun setInfoOpen(open: Boolean) {
        if (_state.value.infoOpen == open) return
        val opening = open
        _state.value = _state.value.copy(infoOpen = opening)
        // ⚠ An open sheet forces the chrome back (§3.1b): a sheet dragged up over hidden chrome and
        // then dismissed would leave the user on a bare photograph with no way out.
        if (opening) _state.value = _state.value.copy(chromeVisible = true)
        if (!opening) return
        // ⚠ Each guarded on its own value, not on one shared "already loaded" flag: they are two
        // reads of very different cost and either can land, or fail, without the other.
        if (_state.value.details == null) loadDetails(_state.value.path)
        if (_state.value.exif == null) loadExif(_state.value.path)
        if (_state.value.albums == null) loadAlbums(_state.value.photo)
    }

    /** A tap on the photograph. ⚠ Toggles our chrome and the OS bars together — never one alone. */
    fun toggleChrome() {
        _state.value = _state.value.copy(chromeVisible = !_state.value.chromeVisible)
    }

    /**
     * Favourite or un-favourite the photograph on screen.
     *
     * ⚠⚠ Optimistic, and it **reverts on refusal**. The op returns `false` for a row it will not
     * write, and a star that stayed filled over a flag that was never stored is the worst of the
     * three possible outcomes — the user believes a thing that is not true, and the Favourites album
     * will disagree with the viewer next time they look.
     */
    fun toggleFavourite() {
        val current = _state.value
        // ⚠⚠ Not while the flag is UNKNOWN (`null`, the read in flight): treating it as "not
        // favourited" would write `true` over a photograph that already is one — a tap meant to
        // remove it would do nothing — and the late read would then overwrite the optimistic value.
        // The shells disable the star until [PhotoViewerUiState.favourite] is known.
        // ⚠ Nor while another write runs ([PhotoViewerUiState.writing]): two star writes in flight end
        // in whichever finishes last, not in the latest tap.
        if (!current.canFavourite || current.favourite == null || current.writing) return
        val target = !(current.favourite ?: false)
        val photo = current.photo
        pendingFavourite = photo.path
        _state.value = current.copy(favourite = target, writing = true)
        viewModelScope.launch {
            val ok = try {
                repository.setFavourite(photo.contentId, target)
            } finally {
                pendingFavourite = null
                _state.value = _state.value.copy(writing = false)
            }
            val now = _state.value
            // ⚠ Only if the pager is on that photograph: a user who swiped on has moved, and writing
            // a star they can no longer see would corrupt the one they can.
            // ⚠⚠ On success TOO, not only on refusal: a swipe away and back while the
            // write runs clears the optimistic star and reads the flag again — a read that could
            // return the OLD value — so the write's own answer is the one that stands.
            if (now.photo.path == photo.path) {
                _state.value = now.copy(favourite = if (ok) target else !target)
            }
        }
    }


    /**
     * Move the photograph on screen to the trash. [onDone] reports whether it HAPPENED.
     *
     * ⚠⚠ The caller closes the viewer only on `true`. A viewer that closes on a failed trash tells
     * the user the photograph has gone (`TrashViewModel.trash`). Staying put is the honest answer
     * to a refusal.
     *
     * ⚠ The photograph is the one on screen WHEN ASKED — never the seed, never a later swipe's.
     * ⚠ Single-flight, the bulk actions' rule: a second tap while the first runs would
     * move the same file twice and report the second, failed move over the first one's success.
     */
    fun trash(onDone: (Boolean) -> Unit) {
        write(onDone) { repository.trash(it) }
    }

    /**
     * Add [photo] to [albumId]; [onDone] reports whether it happened.
     *
     * ⚠⚠ The photograph is PASSED, not read off the state, unlike [trash]: the album is picked in a
     * sheet, and the photograph on screen can change while it is open — a live refresh deleting it
     * retargets the viewer to a neighbour — so the caller captures the one whose menu opened the
     * sheet. Reading the state at pick time would silently file the neighbour.
     */
    fun addToAlbum(albumId: String, photo: Photo, onDone: (Boolean) -> Unit) {
        write(onDone, photo) { repository.addToAlbum(albumId, it) }
    }

    private fun write(onDone: (Boolean) -> Unit, target: Photo? = null, one: suspend (Photo) -> Boolean) {
        if (_state.value.writing) return
        _state.value = _state.value.copy(writing = true)
        val photo = target ?: _state.value.photo
        viewModelScope.launch {
            val ok = try {
                one(photo)
            } finally {
                _state.value = _state.value.copy(writing = false)
            }
            onDone(ok)
        }
    }

    private fun placeOf(photo: Photo): PhotoPlace? =
        nearestPlace(photo.latE7, photo.lngE7, places)

    /**
     * The path whose favourite write is in flight, or `null`. ⚠ A read of that photograph's flag that
     * lands meanwhile is ignored — it may predate the write — and the write's answer is applied
     * instead ([toggleFavourite]).
     */
    private var pendingFavourite: String? = null

    private fun loadPerPhoto(photo: Photo) {
        perPhotoJob?.cancel()
        val canFavourite = photo.contentId.isNotEmpty()
        _state.value = _state.value.copy(canFavourite = canFavourite)
        if (!canFavourite) {
            // ⚠ Not "not favourited" — unfavouritable. The star renders disabled off [canFavourite],
            // and leaving this null keeps the two statements apart.
            return
        }
        perPhotoJob = viewModelScope.launch {
            val favourite = repository.isFavourite(photo.contentId)
            val now = _state.value
            if (now.photo.path == photo.path && pendingFavourite != photo.path) {
                _state.value = now.copy(favourite = favourite)
            }
        }
    }

    /**
     * ⚠⚠ The expensive one. It reads the file's own bytes, so it is fired **only** for an open
     * sheet — never on a viewer open, and never on a swipe past a closed panel. The repository
     * refuses before spending anything when the device does not hold the original.
     */
    private fun loadExif(path: String) {
        viewModelScope.launch {
            val result = repository.photographicSettings(path)
            val now = _state.value
            // ⚠ Only while the sheet is still open AND still on this photo — the same two guards
            // `loadDetails` uses, and for the same reason.
            if (now.infoOpen && now.path == path) _state.value = now.copy(exif = result)
        }
    }

    private fun loadAlbums(photo: Photo) {
        viewModelScope.launch {
            val albums = repository.albumsFor(photo.contentId)
            val now = _state.value
            if (now.infoOpen && now.path == photo.path) _state.value = now.copy(albums = albums)
        }
    }

    private fun loadDetails(path: String) {
        viewModelScope.launch {
            val details = repository.details(path)
            val now = _state.value
            // ⚠ Only while the sheet is still open AND still on this photo: a user who closed it,
            // or swiped past, has moved on, and re-rendering behind them is worse than dropping
            // the answer.
            if (now.infoOpen && now.path == path) _state.value = now.copy(details = details)
        }
    }
}
