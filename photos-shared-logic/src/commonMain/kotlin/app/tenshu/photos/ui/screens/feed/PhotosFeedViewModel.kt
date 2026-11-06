package app.tenshu.photos.ui.screens.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.data.TenshuPhotosRepository
import app.tenshu.photos.model.FeedItem
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.collections.photoCount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map

/**
 * Why a read has nothing to show. Distinguished because the answers differ, and shared by every
 * Photos screen that reads over IPC — the albums list, an album's detail, the duplicate scan.
 *
 * ⚠⚠ Not the feed's: the feed carries its failure through Paging's `LoadState.Error` (as a
 * `FeedReadException`), because Paging has no third state; the other screens hold a `UiState` and
 * need this. Left in this package because that is where
 * they import it from, and moving it would be a rename dressed as a refactor.
 */
sealed interface FeedError {
    /** The Control proxy could not be reached — the app is fine, the proxy is not running. */
    data object Unreachable : FeedError

    /** The read itself failed. [detail] is for the log, not the screen. */
    data class Failed(val detail: String) : FeedError
}

/**
 * What the Home feed shows (`photos-app.md` S1), as a `PagingData` stream.
 *
 * ⚠⚠ **Not a hand-written pager.** The feed pages through `:core-paging`'s keyset `PagingSource`
 * (`core-keyset-paging.md`); a pager kept here — a cursor, a tie skip counted off the accumulated
 * list, a head page merged into the held tail — is a defect at every one of those sites, and a tie
 * skip can fail to terminate (§6.3). What Paging buys:
 *
 * - **`cachedIn`**, so the feed survives a navigation round-trip with its loaded pages and its
 *   scroll position intact (`core-paging-scroll-restoration.md`) — without it Photos is the mirror
 *   image of Drive's bug, keeping the data and losing the place.
 * - **Invalidation instead of a merge.** The chain's change signal rebuilds the pager, so a
 *   photograph taken while the app was backgrounded appears without a restart, and the reload
 *   re-anchors on the cursor rather than jumping to the top.
 * - **One page in flight** for free. A grid fires its "near the end" callback on every scroll
 *   frame; Paging's own load state absorbs it.
 */
class PhotosFeedViewModel(
    private val repository: PhotosRepository,
    /**
     * The start of the section a capture time belongs to, at a given density, in the viewer's zone
     * — what the headers group on.
     *
     * ⚠ Injected rather than imported, so tests need no calendar. Every shell passes
     * `PhotoDates::sectionStart` — Compose and SwiftUI alike (`photos-ios.md` §1.2).
     * ⚠ It is also why grouping happens here rather than in the screen: a separator pass belongs
     * upstream of [cachedIn], so it runs once per page rather than once per recomposition.
     * ⚠⚠ It takes the [FeedDensity] because the density decides what a section IS — see that
     * enum's KDoc for why this is not the layout-only knob S1 describes.
     */
    private val sectionStartOf: (FeedDensity, Long) -> Long,
    /**
     * The local calendar year of a capture time — what the scrubber's pills are grouped by.
     *
     * ⚠ Injected for the reason [dayStartOf] is, and defaulted for the reason
     * `CollectionsViewModel` defaults its own: tests need no calendar, and a
     * crude fallback keeps a test or a preview from having to supply a calendar. ⚠⚠ The shells pass
     * the real one; a UTC-ish approximation puts a pill on the wrong side of New Year for a
     * photograph taken in the last hours of December.
     */
    private val yearOf: (Long) -> Int = { epochMs -> (epochMs / YEAR_MS + 1970).toInt() },
    pageSize: Int = TenshuPhotosRepository.DEFAULT_PAGE_SIZE,
    /**
     * Marked when the feed's first photograph arrives; [yearMarks] waits for it, and so does
     * whatever else the shell hands the same instance to (`photos-app.md` §38). ⚠ The Compose shells
     * share ONE with `CollectionsViewModel` through DI; the default is this view model's own.
     */
    private val firstPage: FeedFirstPage = FeedFirstPage(),
    /**
     * Where [yearMarks] is computed — `null` keeps it in the collector's context, as the Compose
     * shells have it. ⚠⚠ The iOS shell passes a background dispatcher (`photos-ios.md` §8.22.2): the
     * index is a pass over every capture time in the library, resolving a calendar year for each,
     * and `viewModelScope` there is the main thread. The read itself is moved by the repository the
     * shell installs (`OffCallerPhotosRepository`); this is the arithmetic after it.
     */
    private val workDispatcher: CoroutineDispatcher? = null,
) : ViewModel() {

    private val _density = MutableStateFlow(FeedDensity.Mosaic)

    /**
     * How coarsely the feed is grouped right now.
     *
     * ⚠ `Mosaic` on open, every time (§32). Persisting it is a `PreferenceService` question and a
     * synced one — a zoom level is arguably per-device — and guessing either way here would be a
     * settings decision smuggled into a layout change.
     */
    val density: StateFlow<FeedDensity> = _density.asStateFlow()

    /** ⚠⚠ Rebuilds the paged stream: the separator rule is part of it. See [items]. */
    fun setDensity(value: FeedDensity) {
        _density.value = value
    }

    /**
     * The feed's rows — photographs with a header before each new section.
     *
     * ⚠⚠ `insertSeparators`, not a grouping pass over an accumulated list. The old screen ran
     * `photos.groupIntoDays(...)` over every row it held on every change, which is what a
     * `List<Photo>` forces; over a paged stream the same rule is a *local* one — a header goes
     * between two rows whose days differ — and Paging applies it per page as pages arrive.
     *
     * ⚠ A day that straddles a page boundary still gets ONE header, which was the property the
     * whole-list pass existed to guarantee: `before` is the last row of the previous page, so the
     * comparison spans the seam rather than restarting at it.
     *
     * ⚠ Upstream of [cachedIn] deliberately — the separators are a pure function of the rows, so
     * caching them is free, and putting the transform downstream would recompute every header for
     * every collector.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val items: Flow<PagingData<FeedItem>> = _density
        // ⚠⚠ `flatMapLatest`, so a density change rebuilds the stream. The separators are computed
        // here, upstream of [cachedIn], so a coarser grouping cannot be applied to a cached list —
        // the pages have to come back through the transform. ⚠ It costs the scroll position, which
        // is what a zoom does everywhere: the rows the user was looking at are a different size and
        // a different number now, so "the same place" no longer names anything.
        .flatMapLatest { density ->
            repository.feedPaging(pageSize).map { page ->
                page.map<Photo, FeedItem> {
                    // ⚠ Here, upstream of [cachedIn], a page's rows pass exactly once — the first
                    // one through is the first page landing. Idempotent after that.
                    firstPage.markLanded()
                    FeedItem.Tile(it, density)
                }
                    .insertSeparators { before, after ->
                        when {
                            // End of the list — a trailing header would label nothing.
                            after !is FeedItem.Tile -> null
                            before !is FeedItem.Tile ->
                                FeedItem.DayHeader(sectionStartOf(density, after.photo.capturedAtMs))
                            sectionStartOf(density, before.photo.capturedAtMs) !=
                                sectionStartOf(density, after.photo.capturedAtMs) ->
                                FeedItem.DayHeader(sectionStartOf(density, after.photo.capturedAtMs))

                            else -> null
                        }
                    }
            }
        }
        .cachedIn(viewModelScope)

    /**
     * The scrubber's year pills — one mark per year, spaced by how many photographs that
     * year holds.
     *
     * ⚠⚠ **One read for the whole library, not one per page.** It needs no shared content index
     * (cold cost ~55 s, `photos-feed-scrubber.md` §5): the feed's own window answers it in a single
     * `getAll` projecting nothing but the capture time. The map is a property of the library, so it
     * is read once and held.
     *
     * ⚠ `WhileSubscribed`, so a feed the user never scrubs never pays for it — and `replay = 1`
     * inside `stateIn`, so re-entering the screen does not re-read. ⚠⚠ It deliberately does NOT
     * refresh when the feed changes: a photograph arriving moves every pill below it by one row out
     * of hundreds, which no eye can see, and re-reading the library on every sync tick would cost
     * far more than the staleness. It is re-read when the view model is.
     *
     * ⚠ Empty on failure as well as on an empty library, and that is deliberate here even though
     * the repository distinguishes them: the only thing the track can do with either answer is draw
     * no pills. The distinction matters to a caller that could retry, and this one cannot.
     *
     * ⚠⚠ **Read after the feed's first rows, never beside them** (`photos-app.md` §38, fix 2). It is a
     * whole-library read in Control, and issued at launch it shared Control with the first page —
     * which is what a user is waiting for. The marks are useless until there are rows to scrub, so
     * waiting costs nothing; an empty or failed feed never reads them at all.
     */
    val yearMarks: StateFlow<List<FeedYearMark>> = flow {
        firstPage.await()
        // ⚠ `withContext` around the arithmetic, not `flowOn` on the flow: `stateIn` fuses with a
        // `flowOn` and would run its whole sharing coroutine — the state's publication with it — on
        // the worker. Only the index is built there; the value is published from `viewModelScope`.
        val times = repository.feedCaptureTimes()
        emit(
            when {
                times == null -> emptyList()
                workDispatcher == null -> feedYearMarks(times, yearOf)
                else -> withContext(workDispatcher) { feedYearMarks(times, yearOf) }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── the rotating player (photos-video-support.md M5) ─────────────────────────────────────

    /**
     * The feed's one video player, rotating over the visible video tiles (§5 — D1, D7).
     *
     * ⚠ Owned here rather than by the screen for §5.2's reason: a tile cannot know what the next
     * tile is, and the single fetch slot has to outlive any one tile's composition. The screen
     * feeds it the visible video paths and turn completions; the shell feeds it the Settings
     * boolean and the lifecycle; it hands back the one [app.tenshu.photos.model.MotionTurn].
     */
    val motion: MotionRotation = MotionRotation(viewModelScope, fetch = repository::motionClip)

    // ── multi-select (S1) ────────────────────────────────────────────────────────────────────

    private val _selected = MutableStateFlow<Map<String, Photo>>(emptyMap())

    /**
     * The photographs the user has picked, keyed by path and in the order they picked them.
     *
     * ⚠⚠ **The `Photo`, not just the path.** Drive's `SelectionViewModel` keeps paths because its
     * operations take paths; Photos' take a `Photo` — `trash` needs the content id and `addToAlbum`
     * needs both. Keeping only paths would mean resolving them back through the paged list, and a
     * selected photograph can be scrolled far outside the loaded window by the time the user acts.
     *
     * ⚠ A `Map` rather than a `Set<Photo>` so the grid can ask "is this path selected?" without
     * holding a second copy of the truth — the tile has the path, not the row's identity.
     */
    val selected: StateFlow<Map<String, Photo>> = _selected.asStateFlow()

    /** ⚠ Toggle, not add: the same long-press that begins a selection ends it on the last item. */
    fun toggleSelection(photo: Photo) {
        touchedWhileActing?.add(photo.path)
        _selected.update { current ->
            if (current.containsKey(photo.path)) current - photo.path
            else current + (photo.path to photo)
        }
    }

    fun clearSelection() {
        _selected.value = emptyMap()
    }

    /**
     * Move every selected photograph to the trash.
     *
     * ⚠⚠ **The failures stay selected.** `TrashViewModel` already learned that a destructive action
     * which quietly does nothing is worse than one that fails loudly — that is the whole reason
     * `repository.trash` returns a `Boolean` and its caller is expected to read it. In bulk the
     * same rule needs one more thing: clearing the whole selection after a partial failure throws
     * away the user's record of *which* photographs still need dealing with. So the successes leave
     * the selection and the failures remain in it, ready to retry.
     *
     * ⚠ [onDone] carries both numbers because the screen has to be able to say "7 moved, 3 could
     * not" — a bulk action that reports only its successes is the quiet lie one layer up.
     */
    fun trashSelected(onDone: (BulkOutcome) -> Unit = {}) {
        act(BulkAction.Trash, onDone) { repository.trash(it) }
    }

    /** Add every selected photograph to [albumId], with the same partial-failure contract. */
    fun addSelectedToAlbum(albumId: String, onDone: (BulkOutcome) -> Unit = {}) {
        act(BulkAction.AddToAlbum, onDone) { repository.addToAlbum(albumId, it) }
    }

    private val _acting = MutableStateFlow(false)

    /**
     * A bulk action is running. ⚠ The shells disable their bulk controls on it — but the guard is
     * [act]'s own, not theirs: a second tap, or a keyboard shortcut, that arrives mid-flight is
     * ignored here whatever the chrome shows.
     */
    val acting: StateFlow<Boolean> = _acting.asStateFlow()

    /**
     * The paths the user toggled while a bulk action ran, or `null` when none runs. ⚠ Their latest
     * choice wins over the action's result: a photograph deselected and picked again mid-flight is
     * the user's NEW selection, and removing it because the write for the old one succeeded would
     * silently drop it.
     */
    private var touchedWhileActing: MutableSet<String>? = null

    /**
     * ⚠ Sequential, not `async`-fanned. Every one of these is a write through the same proxy, and
     * the content plane's own concurrency work (`content-plane-transfer-concurrency.md`) is about
     * overlapping *transfer* latency — firing fifty writes at once would queue more work than the
     * proxy can answer, the shape the read path is built to avoid.
     *
     * ⚠⚠ **Single-flight.** A second action while one runs is dropped: it would snapshot the same
     * selection and race the same writes — two moves from one source path, one of them failing and
     * re-selecting a photograph the other moved.
     *
     * ⚠⚠ **Reconciled, not assigned.** The user may Cancel or toggle while the writes run, so the
     * result REMOVES the successes from the selection as it stands at the end rather than replacing
     * it with the failures: an assignment would put back what Cancel cleared and drop a tile
     * selected mid-flight. A failure the user did not touch is still selected, which is the
     * retry contract above. ⚠ And a path the user toggled mid-flight is theirs ([touchedWhileActing]):
     * a snapshot member deselected and picked again is a NEW selection the subtraction must not undo.
     */
    private fun act(action: BulkAction, onDone: (BulkOutcome) -> Unit, one: suspend (Photo) -> Boolean) {
        if (_acting.value) return
        _acting.value = true
        val touched = mutableSetOf<String>()
        touchedWhileActing = touched
        viewModelScope.launch {
            try {
                val picked = _selected.value
                val failed = mutableSetOf<String>()
                picked.values.forEach { photo ->
                    if (!one(photo)) failed += photo.path
                }
                val succeeded = picked.keys - failed
                val after = _selected.updateAndGet { current -> current - (succeeded - touched) }
                onDone(
                    BulkOutcome(
                        action,
                        succeeded = succeeded.size,
                        failed = failed.size,
                        failedStillSelected = failed.count { it in after },
                    ),
                )
            } finally {
                touchedWhileActing = null
                _acting.value = false
            }
        }
    }

    private companion object {
        /** ⚠ A crude fallback only — the shells pass a real calendar-aware `yearOf`. */
        const val YEAR_MS = 31_556_952_000L
    }
}

/** Which bulk action an outcome reports — the report's wording depends on it. */
enum class BulkAction {
    Trash,
    AddToAlbum,
}

/**
 * What a bulk action actually did.
 *
 * ⚠⚠ Two numbers, never one. "It worked" and "it worked for seven of ten" are different sentences,
 * and a screen that can only say the first will say it when the second is true.
 */
data class BulkOutcome(
    val action: BulkAction,
    val succeeded: Int,
    val failed: Int,
    /**
     * How many of the failures are still selected once the action ends. ⚠ Not always [failed]: the
     * user may Cancel or deselect while the writes run, and a report promising "still selected so
     * you can try again" over an empty selection points at a retry that is not there.
     */
    val failedStillSelected: Int = failed,
) {
    val total: Int get() = succeeded + failed
    val partial: Boolean get() = failed > 0 && succeeded > 0

    /**
     * The report's heading, as both shells say it.
     *
     * ⚠ Here rather than in either shell so Android and iOS say the same sentence about the same
     * outcome — the copy is where "7 of 10" either survives or quietly becomes "done".
     */
    val title: String get() = when {
        failed == 0 -> "Done"
        // ⚠ Per action: adding to an album moves nothing, and "could not be moved" would say a
        // failed add had changed where the photographs live.
        else -> "Some photos could not be $verb"
    }

    /** What happened to each photograph that succeeded. */
    private val verb: String get() = when (action) {
        BulkAction.Trash -> "moved to the trash"
        BulkAction.AddToAlbum -> "added to the album"
    }

    /**
     * The report's sentence. ⚠⚠ The partial case is the one this exists for, and it names what is
     * left SELECTED, because that selection is the affordance for retrying.
     */
    val message: String get() = when {
        failed == 0 -> "${photoCount(succeeded)} $verb."
        failed == 1 && succeeded == 0 -> "The photo could not be $verb."
        succeeded == 0 -> "None of the $total photos could be $verb."
        // ⚠ Offers the retry only when the failures are all still there to retry.
        failedStillSelected < failed -> "$succeeded $verb. $failed could not be."
        // ⚠ Singular-aware: the iOS pass read "1 could not be, and are still selected".
        else -> "$succeeded $verb. $failed could not be, and ${if (failed == 1) "is" else "are"} " +
            "still selected so you can try again."
    }
}
