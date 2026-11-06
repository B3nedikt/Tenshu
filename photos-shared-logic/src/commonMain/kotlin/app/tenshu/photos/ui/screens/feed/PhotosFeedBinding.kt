package app.tenshu.photos.ui.screens.feed

import androidx.paging.LoadState
import app.tenshu.photos.data.FeedReadException
import app.tenshu.photos.model.FeedItem
import app.tenshu.photos.model.Photo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import app.tenshu.photos.time.PhotoDates
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import org.tenshu.core.paging.TenshuPagingPresenter
import kotlin.coroutines.CoroutineContext

/**
 * The feed as a **non-Compose** shell draws it: the view model, a presenter collecting its paged
 * rows, and the [FeedLayout] those rows make at the current density and width (`photos-ios.md`).
 *
 * Compose gets all of this from `collectAsLazyPagingItems()` plus a `remember { FeedLayout.of(…) }`
 * in `PhotosFeedScreen`. SwiftUI gets neither, and the rule this app's iOS slice
 * runs on (`photos-ios.md` PIO-D2) is that the arithmetic is decided here, where a JVM test can see
 * it, and Swift only draws [layout]: `0 ..< layout.lineCount`, each line a header or a row of
 * tiles, placeholders included.
 *
 * ⚠⚠ Built on [TenshuPagingPresenter.window], never `snapshot` — the feed pages with placeholders
 * on (the scrubber spans the whole library), and `snapshot` loses where the loaded window
 * sits (§1.3).
 *
 * ⚠⚠ **[touch] is not optional.** Paging appends only when an access is recorded; a grid that
 * draws [layout] without calling [touch] from each visible line renders the first page and never
 * grows (`TenshuPagingPresenter`'s KDoc).
 */
class PhotosFeedBinding(
    val viewModel: PhotosFeedViewModel,
    scope: CoroutineScope,
    presenterContext: CoroutineContext = Dispatchers.Main,
) {
    val presenter: TenshuPagingPresenter<FeedItem> = TenshuPagingPresenter(scope, presenterContext)

    /** The grid's width in dp (points on iOS), or `null` before the shell has measured it. */
    private val gridWidthDp = MutableStateFlow<Float?>(null)

    /**
     * A layout, the density it was cut at, and the density of the GENERATION presented — read off
     * the window's own tiles ([FeedItem.Tile.density]), `null` while nothing is loaded.
     */
    private class Shaped(val layout: FeedLayout, val density: FeedDensity, val presented: FeedDensity?)

    private val shaped: StateFlow<Shaped> =
        combine(presenter.window, viewModel.density, gridWidthDp) { window, density, width ->
            Shaped(
                FeedLayout.of(
                    placeholdersBefore = window.placeholdersBefore,
                    items = window.items,
                    placeholdersAfter = window.placeholdersAfter,
                    density = density,
                    // ⚠ The same rule Compose's feed applies (`columnsAt`), decided HERE so a density
                    // change re-derives it — a column count handed in by the shell would go stale.
                    columns = width?.let { density.columnsAt(it) } ?: density.columns,
                ),
                density,
                window.items.firstNotNullOfOrNull { (it as? FeedItem.Tile)?.density },
            )
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            Shaped(FeedLayout.of(0, emptyList(), 0, viewModel.density.value), viewModel.density.value, null),
        )

    /**
     * The feed's lines at the current density — what the shell renders. Rebuilt whenever a page
     * lands, the density changes, or the width does.
     */
    val layout: StateFlow<FeedLayout> =
        shaped.map { it.layout }.stateIn(scope, SharingStarted.Eagerly, shaped.value.layout)

    /**
     * What the feed is showing — the one decision the shell switches on to pick between the grid,
     * a spinner, the empty state and an error.
     *
     * ⚠⚠ Four outcomes, never collapsed (`photos-app.md` §10.3): *loading* ≠ *empty* ≠
     * *unreachable* ≠ *failed*. "No photos yet" for a library the app cannot see tells the user
     * their photographs are gone. Decided here, not in Swift, because Paging's load states are
     * not on the umbrella's surface and the rule is the one `PhotosFeedScreen` already applies.
     */
    val status: StateFlow<FeedStatus> =
        combine(presenter.loadState, presenter.refreshSettled, presenter.window) { states, settled, window ->
            val refresh = states?.refresh
            when {
                refresh is LoadState.Error -> {
                    val cause = refresh.error
                    if (cause is FeedReadException && cause.unreachable) FeedStatus.Unreachable else FeedStatus.Failed
                }
                window.size > 0 -> FeedStatus.Loaded
                settled && refresh !is LoadState.Loading -> FeedStatus.Empty
                else -> FeedStatus.Loading
            }
        }.stateIn(scope, SharingStarted.Eagerly, FeedStatus.Loading)

    /**
     * Whether ANY paging load is in error — refresh, append or prepend. ⚠ [status] reports only the
     * refresh (it decides what the whole screen shows); a caller paging toward an edge for something
     * specific — the viewer looking for a tapped photograph — must also see an edge that failed, or
     * it waits for a page that is never coming.
     */
    val pagingFailed: StateFlow<Boolean> = presenter.loadState
        .map { states ->
            states != null && (states.refresh is LoadState.Error || states.append is LoadState.Error ||
                states.prepend is LoadState.Error)
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch {
            var previous: Shaped? = null
            shaped.collect { current ->
                onShaped(previous, current)
                previous = current
            }
        }
        presenter.collect(viewModel.items)
    }

    /** Retry after [FeedStatus.Failed] / [FeedStatus.Unreachable]: re-collect the feed. */
    fun retry() {
        presenter.collect(viewModel.items)
    }

    /**
     * Retry the failed page loads of the CURRENT generation — an append or prepend that failed while
     * paging toward an edge. ⚠ [retry] re-collects, which behind `cachedIn` replays the cached
     * generation and restarts nothing; this asks Paging itself.
     */
    fun retryPaging() {
        presenter.retry()
    }

    /**
     * Reload the feed from its source — after a write the user is watching, like the viewer's trash:
     * the file moved to `/Trash`, which the feed's window excludes, so its row must go before the
     * grid is seen again. The chain's own change signal would do this too, after its debounce
     * (Android's explicit `refresh()` in the same place, for the same reason).
     */
    fun refresh() {
        presenter.refresh()
    }

    /**
     * The width the grid is drawn in, in dp — points on iOS — **after** the shell's own horizontal
     * padding; `null` (or ≤ 0) restores the density's phone count. The column count is derived here by
     * `columnsAt` (`GridWidthScale`), the rule Compose's feed uses, so the two shells cannot disagree.
     *
     * ⚠⚠ A shell that never reports a width draws the phone count at any size — on a 1032 pt iPad
     * window three 340 pt columns, every tile an upscale of a rung a third its size
     * (`photos-ios.md` §8.5).
     */
    fun setGridWidth(widthDp: Float?) {
        gridWidthDp.value = widthDp?.takeIf { it > 0f }
    }

    /**
     * Record that list position [index] is on screen — Paging's load trigger. Call it for the first
     * index of every line as the line appears.
     */
    fun touch(index: Int) {
        presenter.get(index)
    }

    /** The photograph at list position [index], or `null` for a header or an unloaded slot. */
    fun photoAt(index: Int): Photo? = (layout.value.itemAt(index) as? FeedItem.Tile)?.photo

    /**
     * The list positions of every **loaded photograph**, in feed order — the viewer's pages (the
     * viewer is a pager over the list the grid showed). Headers and placeholders are not
     * pages. ⚠ Grows as the feed pages; the viewer calls [touch] on its last page to ask for more.
     */
    fun loadedPhotoPositions(): List<Int> = layout.value.loadedPhotoPositions()

    /** The header's section start at list position [index], or `null` when it is not a header. */
    fun sectionStartAt(index: Int): Long? = (layout.value.itemAt(index) as? FeedItem.DayHeader)?.dayStartMs

    /**
     * Where the photograph at [path] sits in the CURRENT list, or `null` if it is not loaded there.
     * ⚠⚠ A tap must resolve the photograph, not the index it was drawn at: a tile
     * drawn from an older layout carries an index that names another photograph — or a header — in
     * the newest one. The path is the identity ([FeedLayout.photoOrdinalOf]); positions and
     * photographs are read from ONE snapshot, since the window updates before the layout derived
     * from it.
     */
    fun positionOf(path: String): Int? = layout.value.positionOf(path)

    /**
     * The sticky header's text for [line] — its section's label at the current density, or `null`
     * for a line nothing is known about (placeholders). The same function Compose's overlay and
     * scrubber bubble read ([FeedLayout.labelAt]).
     */
    fun labelAt(line: Int): String? {
        val current = shaped.value
        return current.layout.labelAt(line, current.density, PhotoDates::sectionLabel)
    }

    /**
     * The label of [line] in [layout] — the layout the shell DREW, which can be behind the binding's
     * newest. ⚠⚠ The sticky header must use this one: looked up in a newer layout, the same line
     * names another section (a "2 January" header over a 31 December row), and
     * the label is formatted for the density [layout] was cut at, not the current one.
     */
    fun labelAt(line: Int, layout: FeedLayout): String? =
        layout.labelAt(line, layout.density, PhotoDates::sectionLabel)

    // ── re-anchoring ───────────────────────────────────────────────────────────────────────────

    /** The top of the viewport as the shell last reported it: a line, how far into it (0–1), and
     *  the layout that line indexes — the one the shell DREW. */
    private var viewportLine = 0
    private var viewportScrolledPast = 0f
    private var viewportLayout: FeedLayout? = null

    /**
     * Report the line at the top of the viewport, how much of it has scrolled past (0 to 1), and the
     * [layout] that line indexes — the one on screen. ⚠ Call it as the top line changes AND whenever
     * a new layout is drawn; a density or width change re-anchors on the photograph it names.
     * Reporting nothing re-anchors on the first line, which is right for a feed at the top.
     *
     * ⚠⚠ [layout] is not optional: a newer layout can reach the binding before the
     * shell draws it — a page, or a second density change — and the same line number names another
     * photograph there. The line is resolved against the layout it was read off, never a newer one.
     */
    fun setViewport(topLine: Int, scrolledPast: Float, layout: FeedLayout) {
        viewportLine = topLine
        viewportScrolledPast = scrolledPast.coerceIn(0f, 1f)
        viewportLayout = layout
    }

    /**
     * A line the shell must scroll to, top-aligned. [serial] makes a repeat of the same line fire.
     *
     * ⚠⚠ [line] is an index into [layout], and only into it. A shell that scrolls before it draws
     * [layout] resolves the index against the OLD lines, and with index ids shared across layouts the
     * offset it lands on is kept when the new lines replace them, so a Day → Month change puts the
     * wrong photograph on top. Scroll once [layout] is the one drawn.
     */
    class ScrollRequest(
        val line: Int,
        val serial: Int,
        val layout: FeedLayout,
        /** The photograph's library position, for a shell whose drawn layout has moved on since. */
        val photo: Int,
        /** Whether the anchor was the section header above [photo] — see [FeedLayout.lineOfPhoto]. */
        val onHeader: Boolean,
        /** The photograph's identity — its path — when it was loaded; resolved before [photo]. */
        val path: String? = null,
    ) {
        /**
         * The line to scroll to in [drawn] — [line] when [drawn] is [layout], else the photograph
         * re-resolved there (a page replacing the layout between "ready" and the scroll running
         * makes [line] name another photograph; dropping the scroll would lose the place).
         */
        fun lineIn(drawn: FeedLayout): Int? {
            if (drawn === layout) return line
            // ⚠⚠ An identified photograph that [drawn] does not hold has NO line there: its old
            // ordinal names whatever took its place. Only an anchor without an
            // identity (a placeholder) falls back to the ordinal.
            val ordinal = if (path != null) drawn.photoOrdinalOf(path) ?: return null else photo
            return drawn.lineOfPhoto(ordinal, onHeader)
        }
    }

    private val _scrollRequests = MutableStateFlow<ScrollRequest?>(null)

    /**
     * Where a density or width change needs the grid to be (`photos-app.md` §32–§33.3, the Compose
     * `DensityAnchor`). ⚠⚠ The grid's position is a LINE index, and a line is three, five or seven
     * photographs, so left alone line 100 is photograph ~300 in the day view and ~500 in the month
     * view. The only coordinate the densities share is a photograph's library position, so that is
     * what is carried across.
     */
    val scrollRequests: StateFlow<ScrollRequest?> = _scrollRequests

    /** What the shell scrolls to: a line in the layout it has drawn, and the request it answers. */
    class ScrollTarget(val line: Int, val serial: Int)

    /**
     * The line the grid drawn from [drawn] should scroll to NOW, or `null` — decided here, read
     * synchronously, so the shell never acts on a mirror of it.
     *
     * - `null` once [cancelReanchor] has run — at once, not when an async observer next resumes.
     * - `null` for a layout OLDER than the request's: a line resolved there would be kept as a
     *   pixel offset when the newer lines replace it.
     * - Otherwise the photograph's line in [drawn] — the request's own layout, or the binding's
     *   newest one (the shell has caught up). ⚠ Not "only the request's own": two flows the shell
     *   observes separately can conflate that exact layout away, and a settled anchor publishes no
     *   replacement, so waiting for it stranded the scroll.
     *
     * Call it when a layout is drawn or a request arrives, and AGAIN at the last moment before the
     * scroll is written.
     */
    fun scrollTarget(drawn: FeedLayout): ScrollTarget? {
        val request = _scrollRequests.value ?: return null
        if (drawn !== request.layout && drawn !== layout.value) return null
        val line = request.lineIn(drawn) ?: return null
        return ScrollTarget(line, request.serial)
    }

    /**
     * The shell has performed request [serial]: it is consumed, and [scrollTarget] offers it no more.
     * ⚠ The binding outlives the grid: remembering completion only in the grid would let a
     * recreated grid — after an error and a retry — see the old request and jump back. A later
     * pass publishes a NEW serial, which this does not touch.
     */
    fun acknowledgeScroll(serial: Int) {
        if (_scrollRequests.value?.serial == serial) _scrollRequests.value = null
    }

    /**
     * The user took the list back — a drag, a tap on a tile. A re-anchor still waiting for pages
     * must not yank a scroll the user has already started.
     */
    fun cancelReanchor() {
        pending = null
        // ⚠⚠ And withdraw what was already published: a request the shell has not
        // performed yet — waiting for its layout, or queued for the next run-loop turn — would
        // otherwise still yank a list the user has taken back. The shell re-checks before scrolling.
        _scrollRequests.value = null
    }

    /**
     * A re-anchor in flight. [awaitGeneration] is set for a density change: the paged stream is
     * rebuilt, and only a window whose tiles were cut for that density is the rebuilt generation.
     */
    private class Pending(
        val photo: Int,
        val onHeader: Boolean,
        val awaitGeneration: FeedDensity?,
        /** The photograph itself when it was loaded: photographs arriving or leaving
         *  ahead between generations shift [photo], so each layout resolves THIS first. */
        val identity: Photo?,
    ) {
        var passes = 0
    }

    private var pending: Pending? = null

    /** Whether a re-anchor is still live — what a later page would act on. Tests only. */
    internal val isReanchoring: Boolean get() = pending != null

    /**
     * Whether the binding still means to move the grid — a re-anchor waiting for its pages, or a
     * request published and not yet performed. ⚠ A shell that follows the top of the feed as
     * photographs arrive must stand aside while this is true: the position is the binding's.
     */
    val reanchorPending: Boolean get() = pending != null || _scrollRequests.value != null
    private var serial = 0

    private fun onShaped(previous: Shaped?, current: Shaped) {
        if (previous != null &&
            (previous.density != current.density || previous.layout.columns != current.layout.columns)
        ) {
            // ⚠ The layout the viewport was READ OFF, not the last one the binding saw — see
            // [setViewport]. Before the shell reports anything, the previous layout is what it drew.
            val shown = viewportLayout ?: previous.layout
            val top = viewportLine
            pending = shown.photoAt(top, viewportScrolledPast)?.let { photo ->
                Pending(
                    photo = photo,
                    identity = shown.photoOfOrdinal(photo),
                    // ⚠ A header and the row under it share one position; remember which was on top.
                    onHeader = top < shown.lineCount && shown[top] is FeedLine.Header,
                    // Only a density change rebuilds the paged stream; a width change re-cuts lines.
                    // ⚠ A width change DURING a density re-anchor still waits for that generation —
                    // otherwise it would settle on the stale one the density change is replacing.
                    awaitGeneration = if (previous.density != current.density) current.density else pending?.awaitGeneration,
                )
            }
        }
        val anchor = pending ?: return
        val layout = current.layout
        if (layout.lineCount == 0) return
        val awaited = anchor.awaitGeneration?.let { current.presented == it } ?: true
        // ⚠⚠ Resolved by identity in THIS layout; the ordinal only for an anchor that never had one.
        val found = anchor.identity?.let { layout.photoOrdinalOf(it.path) }
        val ordinal = found ?: anchor.photo
        val unresolvedIdentity = anchor.identity != null && found == null
        // ⚠⚠ Only the awaited generation's passes count toward the limit: a slow regrouping whose
        // OLD generation keeps presenting (appends, drops) would spend every pass on stale events
        // and give up before its own generation arrived. Stale passes still scroll — they keep the
        // photograph in view meanwhile — but they are not attempts. ⚠ Nor does a pass that has not
        // found the photograph's identity: a backup burst can push it pages further down, and
        // expiring before that page lands would leave the grid on the stale ordinal. An anchor with
        // an identity lives until found, gone (absent from a COMPLETE layout), cancelled, or
        // replaced by a newer change.
        val gone = unresolvedIdentity && layout.isComplete
        if (awaited && !unresolvedIdentity) anchor.passes++
        // ⚠ Nothing is published for an identity this layout does not hold — a stale-ordinal scroll
        // would move the grid to another photograph. The anchor waits (or ends, if gone).
        if (unresolvedIdentity) {
            // ⚠ And WITHDRAW the one already published: it was cut for a layout that
            // still held the photograph, and a grid still drawing that layout would pass `scrollTarget`
            // and scroll to the gone photograph's old place.
            _scrollRequests.value = null
        } else {
            _scrollRequests.value = ScrollRequest(
                layout.lineOfPhoto(ordinal, anchor.onHeader), ++serial, layout, ordinal, anchor.onHeader,
                path = anchor.identity?.path,
            )
        }
        // ⚠ After a density change the first layout can be the OLD window re-cut; the rebuilt
        // stream's first page (the top of the library) lands after it, so "loaded" alone does not
        // settle it. ⚠⚠ Nor does "a second layout", nor "a newer presentation": an equal rebuilt
        // window is swallowed by the window StateFlow, and a replayed or late OLD generation is a
        // presentation too. Settled = the window on screen was cut for the new density — it says so
        // in its own tiles — and the photograph is loaded in it. An anchor WITH an identity settles
        // only when that identity is found — never on its stale ordinal.
        val loaded = if (anchor.identity != null) found != null else layout.isLoaded(anchor.photo)
        val settled = loaded && awaited
        if (settled || gone || anchor.passes >= MAX_REANCHOR_PASSES) pending = null
    }

    private companion object {
        /** The re-cut, the rebuilt stream's first page, the jump it causes, and one spare. */
        const val MAX_REANCHOR_PASSES = 4
    }
}

/** What [PhotosFeedBinding.status] reports. See its KDoc for why there are four and not two. */
enum class FeedStatus { Loading, Loaded, Empty, Unreachable, Failed }
