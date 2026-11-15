package app.tenshu.photos.ui.screens.albumdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.model.FaceCover
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import app.tenshu.photos.ui.screens.collections.SmartAlbum
import app.tenshu.photos.ui.screens.common.WriteOutcome
import app.tenshu.photos.ui.screens.common.boundedRead
import app.tenshu.photos.ui.screens.common.boundedWrite
import app.tenshu.photos.ui.screens.feed.FeedError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One album's contents (`photos-app.md` S5), or the Favourites smart album when [albumId] is
 * [FAVOURITES].
 *
 * ⚠ The two share a view model because they differ in exactly one way — where the list comes from
 * — and in one the user cannot change: Favourites has no membership to edit, so `remove` is absent
 * rather than disabled. [editable] is what the screen reads to know.
 */
data class AlbumDetailUiState(
    val title: String = "",
    val photos: List<Photo> = emptyList(),
    val loading: Boolean = true,
    val editable: Boolean = true,
    val error: FeedError? = null,
    /** A person's header — `null` for every collection that is not a person. */
    val person: PersonHeader? = null,
    /**
     * An edit — rename, delete, remove — is in flight. The shells disable the album's
     * menu and a photograph's Remove while it is: the edits are single-flight.
     */
    val writing: Boolean = false,
    /**
     * This album has been deleted — the shells leave the screen when it turns true. ⚠ STATE, not only a
     * callback: a delete whose answer came late (an unconfirmed edit, [WriteOutcome.Unconfirmed])
     * still has to close the screen.
     */
    val deleted: Boolean = false,
) {
    val isEmpty: Boolean get() = photos.isEmpty() && !loading && error == null

    /**
     * The sentence the screen shows in place of a grid — the error, else the empty copy — or `null`
     * while there is a grid (or a spinner) to show. ⚠ One copy for both shells. ⚠ `editable` false
     * is Favourites, the one non-editable screen with this copy.
     */
    val statusText: String?
        get() = when {
            loading -> null
            error != null -> when (error) {
                FeedError.Unreachable -> "Can't reach Tenshu Control.\nYour photos are safe — Control just isn't running."
                else -> "Something went wrong loading this album."
            }
            isEmpty -> if (editable) {
                "This album is empty.\nAdd photos to it from the feed."
            } else {
                "No favourites yet.\nTap the heart on a photo to keep it here."
            }
            else -> null
        }
}

/**
 * An edit to an album, and what both shells say when it is refused.
 *
 * ⚠⚠ Each sentence names what did NOT happen to the photographs, because that is the fear behind
 * every one of these: deleting an album or removing a photograph from one never touches the
 * photograph (`DeleteAlbum` / `RemoveFromAlbum` cannot reach `:file-service`), and the copy says so
 * rather than leaving the user to wonder what a refusal half-did.
 */
enum class AlbumEdit {
    Rename,
    Delete,
    Remove,
    ;

    /** The heading of the refusal. */
    val failureTitle: String
        get() = when (this) {
            Rename -> "Couldn't Rename the Album"
            Delete -> "Couldn't Delete the Album"
            Remove -> "Couldn't Remove the Photo"
        }

    /** The body of the refusal. */
    val failureMessage: String
        get() = when (this) {
            Rename -> "The album keeps its name. Make sure Tenshu Control is running, then try again."
            Delete -> "The album is still here. Make sure Tenshu Control is running, then try again."
            Remove -> "The photo is still in this album. Make sure Tenshu Control is running, then try again."
        }

    /**
     * What both shells say about [outcome] — `null` for [WriteOutcome.Done], which says nothing.
     *
     * ⚠⚠ An unconfirmed edit is NOT a refusal and is never worded as one: the write may still land,
     * and "the album keeps its name" followed by the name changing is the lie this outcome exists to
     * avoid.
     */
    fun report(outcome: WriteOutcome): AlbumEditReport? = when (outcome) {
        WriteOutcome.Done -> null
        WriteOutcome.Refused -> AlbumEditReport(failureTitle, failureMessage)
        WriteOutcome.Unconfirmed -> AlbumEditReport(
            "Still Waiting for Tenshu Control",
            // ⚠ No promise of an update: once the write is let go of, nothing watches it,
            // and a change that lands later is seen only when the album is read again.
            "Tenshu Control hasn't answered yet. The change may still be saved — open the album again later to check.",
        )
    }
}

/** An outcome's alert, as both shells show it. */
data class AlbumEditReport(val title: String, val message: String)

/**
 * What confirming an album's delete says. ⚠ The photographs stay in the library — the one thing the
 * user must know before tapping Delete.
 */
const val ALBUM_DELETE_CONFIRMATION = "The photos in it stay in your library."

/**
 * What a person's page opens with: WHO defines the group, before any photograph.
 * Modelled on Google Photos' person page, where the face and the name head the grid.
 *
 * @param cover `null` falls back to the first photograph, whole
 */
data class PersonHeader(
    val cover: FaceCover?,
    /**
     * The other people this one can be narrowed WITH — see [companionsFor]. Empty hides
     * the "With…" chip, because a picker with nobody in it is a button that does nothing.
     */
    val companions: List<SmartAlbum> = emptyList(),
    /**
     * What the screen keys its "With…" selection on, so opening a different person never
     * inherits it. ⚠ The ROUTE's id, not the resolved person's: naming re-keys a person, and the
     * selection should survive the user naming the face they are looking at.
     */
    val key: String = "",
) {
    /**
     * The companion a "With…" selection of [withId] names, or `null`. ⚠ Through the aliases too:
     * naming an unnamed companion re-keys them from the cluster id to `person:name:…`, and a saved
     * selection must still find them afterwards. Resolved against the CURRENT companions, so a
     * refresh that drops them falls back to the whole grid rather than a stale intersection.
     */
    fun chosenCompanion(withId: String?): SmartAlbum? =
        withId?.let { id -> companions.firstOrNull { it.id == id || id in it.alternateIds } }
}

/**
 * The people a person's page offers in its "With…" picker: every other person, named or
 * not, in the order the People section lists them.
 *
 * ⚠ "Other" is decided by EVERY id [person] answers to, not just its primary: after a naming the
 * route can hold an alias, and offering someone themselves under their old cluster id would
 * intersect a person with itself and look like a filter that did nothing.
 */
fun companionsFor(person: SmartAlbum, people: List<SmartAlbum>): List<SmartAlbum> {
    val self = setOf(person.id) + person.alternateIds
    return people.filterNot { it.id in self || it.alternateIds.any { alias -> alias in self } }
}

/**
 * The photographs of [first] that also show [second] — Google Photos' "[face] +" chip.
 *
 * ⚠⚠⚠ **Intersection by PHOTO PATH, and nothing else.** Each person's list is already exactly what
 * `peopleFrom` gave them — for a named person, EXACTLY their labelled photographs, nothing inferred —
 * and this only asks which paths both lists hold. It says nothing about which FACE in a shared
 * photograph is whose; that needs the device-local face→person map of F6c
 * (`photos-people-and-pets.md` §6a). Do not "improve" this with face-level matching here: it is the
 * inference `peopleFrom` was rewritten six times to remove.
 *
 * Order follows [first], so the narrowed grid keeps the order of the page it was narrowed from.
 */
fun photosWithBoth(first: List<Photo>, second: List<Photo>): List<Photo> {
    val paths = second.mapTo(HashSet()) { it.path }
    return first.filter { it.path in paths }
}

class AlbumDetailViewModel(
    private val repository: PhotosRepository,
    private val albumId: String,
    title: String,
    /** How long a read may take before the refresh gives up on it — see [refresh]. */
    private val refreshTimeoutMs: Long = CollectionsViewModel.REFRESH_TIMEOUT_MS,
    /** How long an edit may take before it is given up on — see [edit]. */
    private val editTimeoutMs: Long = CollectionsViewModel.REFRESH_TIMEOUT_MS,
    /**
     * An edit is known to have landed — the shell refreshes where this album is SHOWN (the Collections
     * tab). ⚠⚠ A hook on the view model, not state the screen observes: the answer
     * can arrive after the user has left — Back stays enabled while an edit runs, and an unconfirmed
     * edit may land minutes later — and a departed screen observes nothing. The view model outlives it.
     */
    private val onEditLanded: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(
        AlbumDetailUiState(title = title, editable = albumId != FAVOURITES)
    )
    val state: StateFlow<AlbumDetailUiState> = _state.asStateFlow()

    private var inFlight = false
    private var rerunRequested = false

    /** Counts the removals that HAPPENED; a read notes the count it started at. */
    private var removalCount = 0

    /**
     * Each removed photograph's `contentId`, with the [removalCount] its removal made — kept until a
     * read that STARTED after it lands. See [remove].
     */
    private val pendingRemovals = mutableMapOf<String, Int>()

    // ⚠⚠ AFTER every field above (the iOS crash, §8.16): on K/N `viewModelScope` is `Main.immediate`,
    // so this first read runs INSIDE the constructor, and a field declared below it is still null.
    // A JVM test's `StandardTestDispatcher` defers the read and never sees it.
    init {
        refresh()
    }


    /**
     * Re-read the album.
     *
     * ⚠⚠ Single-flight, with ONE queued rerun — `CollectionsViewModel.refresh`'s rule.
     * Two overlapping reads race and the one that finishes LAST wins, so a read started before a
     * removal or a trash could put the old photographs back. A request made while one runs is not
     * dropped either: it runs once more after, so a change that landed mid-read is still seen.
     *
     * ⚠⚠ **Bounded, and the latch always released** — `CollectionsViewModel.refresh`'s rule. A lost
     * Android reply never returns; without a bound the latch would stay set and every later refresh
     * only queue behind it, leaving the album on its spinner until the screen was rebuilt. The read
     * runs as its own job and only the WAIT is timed: a hung read is abandoned, not waited for, and
     * returns state rather than writing it, so a late answer changes nothing.
     */
    fun refresh() {
        if (inFlight) {
            rerunRequested = true
            return
        }
        inFlight = true
        viewModelScope.launch {
            val read = async { read() }
            try {
                val found = withTimeoutOrNull(refreshTimeoutMs) { read.await() }
                    ?: run {
                        read.cancel()
                        println("AlbumDetailViewModel -> refresh timed out after ${refreshTimeoutMs}ms")
                        Found.Error(FeedError.Failed("timed out"))
                    }
                publish(found)
            } finally {
                inFlight = false
                if (rerunRequested) {
                    rerunRequested = false
                    refresh()
                }
            }
        }
    }

    /** What one read found — only the fields a read OWNS. See [publish]. */
    private sealed interface Found {
        data class Photos(val photos: List<Photo>) : Found
        data class Error(val error: FeedError) : Found
    }

    /** The album as one read finds it. ⚠ Returns what it found rather than writing it — see [refresh]. */
    private suspend fun read(): Found {
        val startedAt = removalCount
        val result = if (albumId == FAVOURITES) {
            repository.favourites()
        } else {
            repository.albumPhotos(albumId)
        }
        return when (result) {
            is PhotoPageResult.Page -> {
                // A read that started after a removal already reflects it; one that started before
                // may not, and must not put the photograph back — [publish] filters what remains.
                pendingRemovals.entries.removeAll { (_, madeAt) -> madeAt <= startedAt }
                Found.Photos(result.photos)
            }

            PhotoPageResult.Unreachable -> Found.Error(FeedError.Unreachable)

            is PhotoPageResult.Failed -> Found.Error(FeedError.Failed(result.message))
        }
    }

    /**
     * Writes what a read found into the LATEST state.
     *
     * ⚠⚠ Only the read's own fields, never a copy of the whole state taken when the read began
     * — a rename answered meanwhile would have its title put back, and an edit started meanwhile
     * would have `writing` cleared under it, its menus re-enabled and a second edit let past the
     * single-flight guard.
     */
    private fun publish(found: Found) {
        _state.update { latest ->
            when (found) {
                is Found.Photos -> latest.copy(
                    photos = found.photos.filterNot { it.contentId in pendingRemovals },
                    loading = false,
                    error = null,
                )
                is Found.Error -> latest.copy(loading = false, error = found.error)
            }
        }
    }

    /**
     * Removes a photo from this album; [onDone] reports how it ended.
     *
     * ⚠⚠ From the *album*, never from the realm — the separation `RemoveFromAlbum` enforces by
     * being unable to reach `:file-service` at all. A no-op on Favourites, which has no membership.
     */
    fun remove(photo: Photo, onDone: (WriteOutcome) -> Unit = {}) {
        // ⚠ Locals, never `this`, in every closure handed to `boundedWrite` — see [edit].
        val repo = repository
        val id = albumId
        val contentId = photo.contentId
        edit(
            onDone,
            write = { repo.removeFromAlbum(id, contentId) },
            // ⚠⚠ Never skipped: `albumPhotos` drops an entry whose FILE cannot be resolved, so "not
            // on the page" is no proof the membership is gone — a skipped removal lets the
            // photograph come back once its file resolved again. The removal is idempotent, so it
            // is simply sent. (Rename and delete keep their checks: those read the album ROWS.)
            alreadyDone = { false },
            observe = { },
            publish = { refresh() },
            applied = {
                // ⚠⚠ Off the grid AT ONCE, not when the re-read lands: the edit
                // unlocks as soon as this returns, and a tile still showing could be removed again —
                // a second write that finds nothing to remove and reports a refusal of a removal that
                // worked. ⚠ And kept off until a read STARTED after this lands: one already in flight
                // snapshotted the album with the photograph still in it.
                pendingRemovals[photo.contentId] = ++removalCount
                _state.update { latest -> latest.copy(photos = latest.photos.filterNot { it.contentId == photo.contentId }) }
                refresh()
            },
        )
    }

    /**
     * Renames this album to [name], trimmed; [onDone] reports how it ended.
     *
     * ⚠ A blank name is not a rename — refused here, not only by the shells' disabled Save. ⚠ The
     * title changes only once the rename is known to have happened: an optimistic title left standing
     * after a refusal is a name the album does not have.
     */
    fun rename(name: String, onDone: (WriteOutcome) -> Unit = {}) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            if (_state.value.editable && !_state.value.writing) onDone(WriteOutcome.Refused)
            return
        }
        val repo = repository
        val id = albumId
        edit(
            onDone,
            write = { repo.renameAlbum(id, trimmed) },
            alreadyDone = { albumIn(repo, id)?.name == trimmed },
            applied = { _state.update { it.copy(title = trimmed) } },
            // ⚠ The store's name — not ours: another rename may have landed in between.
            observe = { albumIn(repo, id)?.name },
            publish = { name -> _state.update { it.copy(title = name) } },
        )
    }

    /**
     * Deletes this album; [onDone] reports how it ended, and [AlbumDetailUiState.deleted] closes the
     * screen once it has happened.
     *
     * ⚠⚠ The album, never its photographs — `DeleteAlbum` cannot reach them. ⚠ Leaving on a refusal
     * would show the Collections tab still holding the album with nothing said; staying is the
     * honest answer (the viewer's trash rule).
     */
    fun delete(onDone: (WriteOutcome) -> Unit = {}) {
        val repo = repository
        val id = albumId
        val gone: suspend () -> Boolean = {
            // ⚠ Gone only on a list that was READ: an unreachable proxy proves nothing.
            val albums = repo.albums()
            albums is AlbumsResult.Albums && albums.albums.none { it.albumId == id }
        }
        edit(
            onDone,
            write = { repo.deleteAlbum(id) },
            alreadyDone = gone,
            applied = { _state.update { it.copy(deleted = true) } },
            observe = { if (gone()) true else null },
            publish = { _state.update { it.copy(deleted = true) } },
        )
    }

    /**
     * Runs one edit: [write], then — once it is known to have happened — [applied].
     *
     * ⚠⚠ Single-flight — the viewer's rule: a rename and a delete in flight together, or the
     * same removal twice, end in whichever answer lands last. A request made while one runs is
     * ignored and [onDone] is not called; the shells disable the actions meanwhile.
     *
     * ⚠⚠ Bounded by [boundedWrite], which holds the lock until the write settles or is abandoned —
     * see it for why each half of that matters. [onDone] is called ONCE, with the first
     * thing known: Done, Refused, or Unconfirmed (the shells say "still waiting", the menus stay
     * disabled). After an Unconfirmed: a late YES applies [applied] — the answer is proof — and then
     * [observe]s the store; no answer only [observe]s, claiming nothing.
     *
     * ⚠⚠ [write], [alreadyDone] and [observe] must close over LOCALS only, never `this`:
     * `boundedWrite` runs them detached, and a Binder call that never returns keeps its closure
     * alive for good — one that touched a field would keep the whole view model with it, once per
     * retry. [observe] reads; [publish] — on this view model — writes what it read.
     */
    private fun <T : Any> edit(
        onDone: (WriteOutcome) -> Unit,
        write: suspend () -> Boolean,
        alreadyDone: suspend () -> Boolean,
        applied: () -> Unit,
        observe: suspend () -> T?,
        publish: (T) -> Unit,
    ) {
        val current = _state.value
        if (!current.editable || current.writing) return
        _state.value = current.copy(writing = true)
        viewModelScope.launch {
            var unconfirmed = false
            val outcome = try {
                boundedWrite(
                    timeoutMs = editTimeoutMs,
                    settleMs = editTimeoutMs,
                    write = write,
                    alreadyDone = alreadyDone,
                    onUnconfirmed = {
                        unconfirmed = true
                        onDone(WriteOutcome.Unconfirmed)
                    },
                )
            } finally {
                // ⚠ Released as soon as the WRITE has settled — before any reconciliation read, which
                // is a Binder call too and may never answer.
                _state.update { it.copy(writing = false) }
            }
            when {
                !unconfirmed -> {
                    if (outcome == WriteOutcome.Done) landedOne(applied)
                    // ⚠ A NO re-reads too: Android's long-press removal passes no
                    // `onDone`, so the read is all that can say Control is unreachable — and it is
                    // what shows whether the refusal left the album as it was.
                    if (outcome == WriteOutcome.Refused) refresh()
                    onDone(outcome)
                }
                outcome == WriteOutcome.Refused -> refresh()
                else -> {
                    // ⚠ A late YES is proof: applied even if the store cannot be read now.
                    if (outcome == WriteOutcome.Done) applied()
                    // Then the store's truth, bounded, in its own job; never this edit's claim.
                    var seen: T? = null
                    boundedRead(editTimeoutMs) { seen = observe(); true }
                    seen?.let(publish)
                    onEditLanded()
                }
            }
        }
    }

    /** Applies a landed edit and tells the shell, which refreshes where this album is shown. */
    private fun landedOne(applied: () -> Unit) {
        applied()
        onEditLanded()
    }

    companion object {
        /** The pseudo-album id the Favourites row opens. Not a stored [org.tenshu.photo.Album]. */
        const val FAVOURITES = "__favourites__"
    }
}

/** [albumId] as [repository]'s album list reads it now — `null` when absent or the list cannot be read. */
private suspend fun albumIn(repository: PhotosRepository, albumId: String): AlbumSummary? =
    (repository.albums() as? AlbumsResult.Albums)?.albums?.firstOrNull { it.albumId == albumId }
