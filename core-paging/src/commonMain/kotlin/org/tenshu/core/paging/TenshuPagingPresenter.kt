package org.tenshu.core.paging

import androidx.paging.CombinedLoadStates
import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Non-Compose driver for a `Flow<PagingData<T>>`, for platforms (iOS) that can't
 * use `collectAsLazyPagingItems()` — that helper is Compose-only. Wraps the same
 * [PagingDataPresenter] that `LazyPagingItems` is built on, so load states,
 * placeholders, retry and the load-next trigger survive — unlike a hand-rolled
 * `AsyncStream`, which would drop all of it (proposal
 * `ios-proxy-architecture.md` §9.5).
 *
 * Usage from SwiftUI (via the SKIE bridge): observe [snapshot] for the visible
 * items + [loadState] for spinners/errors, and call [get] from each cell's
 * `onAppear` to page forward. Drive's lists use `enablePlaceholders = false`,
 * so [snapshot] is the dense list of loaded items and its indices line up with
 * [get].
 *
 * ⚠⚠ **The [get] call is not optional.** [snapshot] is a passive mirror; Paging
 * appends only when an access is *recorded* through [get]. A `ForEach(snapshot)`
 * with no `onAppear` renders the first window and never grows — and it does so
 * silently, looking like a sync or thumbnail failure rather than a paging one
 * (a large folder renders 20 rows while its own row count, which comes from
 * elsewhere, stays correct). Compose gets this for free from
 * `collectAsLazyPagingItems()`; a hand-driven presenter does not.
 *
 * Lifecycle: [collect] launches collection in the [scope] passed at
 * construction; cancel that scope (e.g. the owning VM's `viewModelScope`) to
 * stop. Re-calling [collect] replaces the previous source.
 */
class TenshuPagingPresenter<T : Any>(
    private val scope: CoroutineScope,
    // The dispatcher PagingDataPresenter diffs/notifies on. Defaults to the main
    // dispatcher (real on iOS via the main run loop); tests inject a test
    // dispatcher so there is no Dispatchers.Main requirement off-device.
    mainContext: CoroutineContext = Dispatchers.Main,
) {
    private val _snapshot = MutableStateFlow<List<T>>(emptyList())
    private val _window = MutableStateFlow(PagedWindow<T>(0, emptyList(), 0))
    private val _refreshSettled = MutableStateFlow(false)

    private val presenter = object : PagingDataPresenter<T>(mainContext) {
        override suspend fun presentPagingDataEvent(event: PagingDataEvent<T>) {
            // `items` excludes placeholders; with placeholders off it is the
            // full presented window. Re-read after every event so the snapshot
            // tracks inserts/removes/refreshes.
            val presented = snapshot()
            _snapshot.value = presented.items
            _window.value = PagedWindow(presented.placeholdersBefore, presented.items, presented.placeholdersAfter)
            // Presenting anything means the generation's initial load has landed — this is
            // the ONLY settle signal a static source ever gives (see [refreshSettled]). A
            // real Pager reaches here after its refresh completes too (load-state updates
            // travel a separate channel and never reach this callback), so it is not an
            // early flip for the Pager case either.
            _refreshSettled.value = true
        }
    }

    /** The currently presented items (no placeholders), updated on every event. */
    val snapshot: StateFlow<List<T>> = _snapshot.asStateFlow()

    /**
     * The presented items **with their position in the whole list** — for a source that pages with
     * placeholders on (`enablePlaceholders = true`, e.g. [seekablePagingConfig]).
     *
     * ⚠⚠ Use this, not [snapshot], whenever placeholders are on. [snapshot] drops them, so after a
     * jump (a scrubber drag lands mid-library and the loaded window has placeholders on *both*
     * sides) its index 0 is not the list's index 0, and a shell that feeds [snapshot]'s indices to
     * [get] records accesses at the wrong positions — the list renders, scrolls and pages, and the
     * scrubber spans only what has loaded. No crash; a wrong control (`photos-ios.md` §1.3). With
     * placeholders off, [PagedWindow.placeholdersBefore] and
     * [PagedWindow.placeholdersAfter] are both 0 and this is [snapshot] with a size.
     *
     * Swift renders `0 ..< window.size`, draws [PagedWindow.itemAt] (nil = a placeholder cell) and
     * still calls [get] from every cell's `onAppear` — the load trigger is the same either way.
     */
    val window: StateFlow<PagedWindow<T>> = _window.asStateFlow()

    /**
     * True once the current source's initial refresh has landed — even with zero rows. An
     * empty [snapshot] is ambiguous ("still loading" vs "genuinely empty"); a screen that
     * renders a spinner on `snapshot.isEmpty()` alone shows it forever on an empty folder
     * (invisible over canned fakes, immediate over a real Pager).
     *
     * Settles on EITHER a completed refresh load state OR the first presentation event.
     * ⚠ Both arms are load-bearing: a static `PagingData.from(list)` source — what Drive's
     * *sorted* listing returns — drives no Pager and so emits no load states at all, so the
     * load-state arm alone would leave this permanently `false` and spin an empty sorted
     * folder forever.
     */
    val refreshSettled: StateFlow<Boolean> = _refreshSettled.asStateFlow()

    /**
     * Combined refresh/append/prepend load states, or `null` before the first
     * load lands. Drives spinners, the end-of-list footer and error/retry UI.
     */
    val loadState: StateFlow<CombinedLoadStates?> get() = presenter.loadStateFlow

    /**
     * Retry the current generation's FAILED loads — refresh, append or prepend — through Paging's
     * own retry. ⚠ Not [collect] again: behind `cachedIn` a re-collection only replays the cached
     * `PagingData` and never restarts a failed page.
     */
    fun retry() {
        presenter.retry()
    }

    /**
     * Reload the list from its source — Paging's own refresh, which invalidates the current
     * `PagingSource`. Mirrors `LazyPagingItems.refresh`: for a caller that just changed what the
     * source returns and is watching (Photos' viewer after a trash). ⚠ Not [collect] again, for
     * [retry]'s reason: behind `cachedIn` a re-collection replays the old generation.
     */
    fun refresh() {
        presenter.refresh()
    }

    /** Number of items in the current snapshot. */
    val size: Int get() = presenter.size

    /**
     * Returns the item at [index] and records the access with Paging, which is
     * what triggers loading the next page as the user nears the end. Mirrors
     * `LazyPagingItems.get`. Returns `null` for an out-of-range index (or a
     * not-yet-loaded placeholder slot).
     */
    fun get(index: Int): T? =
        if (index in 0 until presenter.size) presenter[index] else null

    /**
     * Starts collecting [flow], replacing any previous collection. Each new
     * `PagingData` generation is fed to the presenter; `collectLatest` cancels
     * the prior generation's in-flight processing on a new emission.
     */
    fun collect(flow: Flow<PagingData<T>>): Job {
        _refreshSettled.value = false
        return scope.launch {
            launch {
                presenter.loadStateFlow.collect { states ->
                    // ⚠⚠ LATCH — set `true`, NEVER write `false`. `loadStateFlow` is a
                    // StateFlow seeded with `null`, so this arm's FIRST emission is always
                    // `null`, and an unconditional assignment would write `false` there. That
                    // write races `presentPagingDataEvent`'s `true` on a separate coroutine:
                    // lose the race and the settle is clobbered, and a static
                    // `PagingData.from(…)` source emits no further event or load state to set it
                    // again — so the flag stays `false` forever and the spinner never resolves.
                    // Only `collect` resets, which is the one place a reset is correct (a
                    // genuinely new generation).
                    if (states != null && states.refresh !is androidx.paging.LoadState.Loading) {
                        _refreshSettled.value = true
                    }
                }
            }
            flow.collectLatest { presenter.collectFrom(it) }
        }
    }
}

/**
 * One presentation of a paged list, placeholders included: [placeholdersBefore] unloaded slots,
 * then [items], then [placeholdersAfter] unloaded slots. See [TenshuPagingPresenter.window].
 */
data class PagedWindow<T : Any>(
    val placeholdersBefore: Int,
    val items: List<T>,
    val placeholdersAfter: Int,
) {
    /** The full list length, placeholders included — what a scrubber spans. */
    val size: Int get() = placeholdersBefore + items.size + placeholdersAfter

    /** The loaded item at list position [index], or `null` for a placeholder or an out-of-range index. */
    fun itemAt(index: Int): T? = items.getOrNull(index - placeholdersBefore)
}
