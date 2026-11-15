package org.tenshu.core.paging

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.changes
import org.tenshu.core.collection.list.drop
import org.tenshu.core.cancellableReads
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.core.TContext
import kotlin.math.max

/**
 * One-shot page reader: loads the requested `[position, position+loadSize)`
 * window and returns. It does **not** keep observing the list.
 *
 * Change-observation + invalidation live in [pagingSource] (factory level),
 * scoped to the collector. A `PagingSource` has no "consumer gone" hook, so a
 * self-observing source can't know when to stop and leaks one observation
 * coroutine per generation; driven repeatedly through `runComposeUiTest` those
 * accumulate into a GC-thrash hang. Keeping `load` one-shot removes that class
 * of leak entirely.
 *
 * The [projection] (e.g. Drive's `select { ... }` with per-row relation
 * resolution) is applied to the **windowed** chain — `drop(position)
 * .take(loadSize)` runs FIRST, then the projection sees only the ≤ loadSize
 * rows of this page. Order is load-bearing: `SelectImplementation` materialises
 * eagerly and resolves every projected relation per row (thumbnails via
 * `GetPreview`, sizes, timestamps), so a projection baked into the source chain
 * BEFORE the window ran over the entire folder on every page load — a
 * 2000-file folder cost 2000 thumbnail resolutions to render 20 rows, and the
 * full projected list crossed the IPC boundary (~800 KB Binder transactions)
 * until the client GC-thrashed into an ANR. Keep chains handed to this class
 * projection-free and pass the projection here instead.
 *
 * The [mapper] may be **partial** (`(T) -> R?`): a `null` drops the row from
 * the page. Key math stays correct because keys are offsets into the *source*
 * list and advance by the raw window size, not the filtered page size — see
 * `load`. This is what lets a forward-compat mapper (e.g. Control's
 * `mapToEventOrNull`, which drops unknown notification subtypes) ride the same
 * paging helper as a total 1:1 mapper
 * (`docs/proposals/notification-burst-aggregation.md` §2.4).
 *
 * The [ordering] is applied to the receiver **inside `load`, ahead of the window**
 * (`ordering(list).drop(pos).take(n)`), so a sorted listing pages through the same
 * source as an unsorted one: the sort never sits on the receiver, which is what the
 * factory's change observer watches, so a write does not re-sort the whole list on the
 * observer's side (`drive-sorted-folder-paging.md` §6 B5). With a stored-field, value-
 * property, type-test or `path.lowercase()` key the `sortBy(...).drop(pos).take(n)
 * .select` shape fuses to one bounded thin pass (`core-ordered-window-fusion.md` F1/§8).
 *
 * Lives in `commonMain` — identical on Android, desktop and iOS. androidx.paging
 * is multiplatform (paging-common) despite the "androidx" prefix, so there's no
 * need for per-target copies. The Compose collection path
 * (`collectAsLazyPagingItems()`) is Android/desktop-only; iOS drives the same
 * `Flow<PagingData<R>>` through [TenshuPagingPresenter] instead.
 */
class TenshuPagingSource<T : Data, P : Data, R : Any>(
    @Suppress("unused") private val context: TContext,
    private val tenshuList: TList<T>,
    private val projection: (TList<T>) -> TList<P>,
    /**
     * The order pages are cut in — a chain of `sortBy`/`thenBy` steps over the receiver, or the
     * identity for natural (store) order. Applied per [load], before the window. Declared ahead
     * of [mapper] so the trailing-lambda call form keeps binding to the mapper.
     */
    private val ordering: (TList<T>) -> TList<T> = { it },
    /**
     * The identity the consumer keys rows by (Drive: the path). When set, a row already served by
     * an earlier page of **this source instance** is dropped from a later one — see [servedKeys].
     * `null` (the default) serves every row the window returns, as before.
     */
    private val rowKey: ((R) -> Any)? = null,
    private val mapper: (P) -> R?,
) : PagingSource<Int, R>() {

    /**
     * Per-generation dedupe (`drive-sorted-folder-paging.md` §3 B4, §6).
     *
     * Keys are **offsets**, and an offset drifts: under a newest-first sort a backup inserts at
     * the *head*, so an append that lands between the insert and the reload re-reads the previous
     * page's last row at the next page's first offset. Drive keys its `LazyColumn` by `path`, and
     * a duplicate key there is Compose's "Key was already used" **crash**, not a cosmetic glitch.
     * So every row this instance serves is remembered by [rowKey] and never served twice.
     *
     * ⚠ It is one half of the answer. A row that *moved above* the loaded boundary (a head insert,
     * an overwrite under a date sort) is not duplicated but **missing**, and only a rebuild serves
     * it — which is the bounded reload in [pagingSource]. A source instance is one generation
     * (the factory rebuilds the `Pager` per settled change), so the set resets exactly when the
     * offsets are re-read from scratch.
     *
     * Guarded because Paging may run an append and a prepend concurrently.
     */
    private val servedKeys = mutableSetOf<Any>()
    private val servedLock = Mutex()

    override fun getRefreshKey(state: PagingState<Int, R>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            // Keys here are item OFFSETS (`nextKey = position + data.size`), not
            // page numbers. When the anchor page is the first page and it holds
            // fewer than `pageSize` items, `nextKey - pageSize` underflows to a
            // negative offset, which makes `load` read `drop(-n)` and fail — the
            // refresh then renders nothing and a freshly-created item never shows
            // until the flow is rebuilt. Clamp to a valid start; near the top,
            // reloading from 0 is exactly the intended window anyway.
            val key = anchorPage?.prevKey?.plus(state.config.pageSize)
                ?: anchorPage?.nextKey?.minus(state.config.pageSize)
                ?: 0
            key.coerceAtLeast(0)
        }
    }

    /**
     * ⚠⚠ **The page is read on [Dispatchers.Default], never on the caller's thread.**
     *
     * Paging calls `load` in the *collector's* context — for Compose that is
     * `collectAsLazyPagingItems`' `Dispatchers.Main`, and on Kotlin/Native `viewModelScope` is
     * `Main.immediate` (`core-paging-scroll-restoration.md` §3.1). Where the proxy is in-process
     * (iOS route-1, desktop) the work below is not an IPC wait but the real read: an ordered page
     * scans every thin row in the collection before the window selects its 20
     * (`core-ordered-window-fusion.md` — fusion bounds the *sort*, not the scan). Left on the
     * caller's thread that is a UI stall per page, growing with the folder.
     *
     * ⚠ Over Binder the call is a suspend IPC and the work happens in Control, so the stall is
     * invisible there — it appears only where the proxy is in-process.
     */
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, R> = withContext(Dispatchers.Default) {
        // Defence in depth: a refresh key should never be negative (see
        // getRefreshKey), but clamp here too so a stray negative key can never
        // turn into a `drop(-n)` that fails the whole reload.
        val position = (params.key ?: 0).coerceAtLeast(0)
        val loadSize = params.loadSize

        try {
            // One-shot read of the current window over the synchronous
            // call/response path (`getAll`, a TerminalOperation), NOT the stream
            // channel. `flow().first()` here used to open a stream and take its
            // *initial emission* as the page — but a stream's initial emission
            // rides Android's async proxy→client callback binding, which has no
            // ordering guarantee against the `call()` return and can be
            // queued/reordered/dropped when the reverse binding isn't ready. That
            // lost the first page over Binder (empty/stale folder listing) while
            // the parent's `Folder.FileCount` — resolved into the synchronous
            // `call()` response — stayed correct. Desktop's single ordered socket
            // never exhibited it, so the desktop paging tests couldn't catch it.
            // `getAll()` reads the window through the same synchronous path as
            // `count()`, removing the race from page data; `changes()` (in
            // [pagingSource]) still rides the stream, but only as an invalidation
            // *signal* whose loss merely delays a reload, never empties a page.
            // The window is cut FIRST so the projection only materialises this
            // page's rows; the ordering sits ahead of the window so the pair fuses.
            // ⚠ `cancellableReads`: a superseded generation's page stops in Control too, instead of
            // being walked to the end for nobody (`core-background-processing.md` §6.2.1).
            val result = cancellableReads {
                projection(ordering(tenshuList).drop(position).take(loadSize)).getAll()
            }
            when (result) {
                is TResult.Success<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val raw = (result.value as TList<P>).toList()
                    val data = dedupe(raw.mapNotNull(mapper))
                    LoadResult.Page<Int, R>(
                        data = data,
                        prevKey = if (position == 0) null else max(0, position - loadSize),
                        // Keys are offsets into the SOURCE list, so advance by
                        // the RAW window size, not `data.size` — a partial
                        // mapper would otherwise re-read its dropped rows as
                        // duplicates on the next page. End-of-list = an empty
                        // raw window; an all-filtered window still advances (an
                        // empty Page with a non-null nextKey is legal — Paging
                        // keeps walking).
                        nextKey = if (raw.isEmpty()) null else position + raw.size
                    )
                }

                is TResult.Failure<*> -> LoadResult.Error(Exception(result.error.toString()))
                else -> error("Unexpected result type: $result")
            }
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    /** Drops the rows of [page] this instance already served — see [servedKeys]. */
    private suspend fun dedupe(page: List<R>): List<R> {
        val key = rowKey ?: return page
        return servedLock.withLock { page.filter { row -> servedKeys.add(key(row)) } }
    }
}

/**
 * The longest a burst of change signals may defer a reload (`drive-sorted-folder-paging.md`
 * §6 B4). [INVALIDATION_COOLDOWN_MS] is a *trailing* debounce that restarts on every signal, so
 * a backup writing faster than every 400 ms would get **no** rebuild until the whole burst ended
 * — and under a newest-first sort every one of those writes lands at the head, ahead of every
 * loaded page. The drift window would be the burst, not 400 ms. So while signals keep arriving
 * the observer reloads at least this often; the per-generation dedupe in [TenshuPagingSource]
 * covers the rows an offset re-serves in between.
 *
 * ⚠ A reload mid-scroll resets the presenter under the reader. That was tolerable for a one-off
 * settled change; it is now a periodic event during a backup, and it is tested while a scroll is
 * in flight (`SortedFolderPagingIntegrationTest.continuousWritesDuringAScroll_rebuildWithinThe`
 * `MaxWait_andNeverDuplicateARow`), not only at rest.
 */
private const val INVALIDATION_MAX_WAIT_MS = 3_000L

/**
 * Quiet window the invalidation observer waits for before reloading. Change
 * signals are trailing-debounced (see [pagingSource]): a burst of writes (a
 * Synced-Folders backup or folder upload is one signal per file; several
 * folders created in quick succession is one per create) collapses into a
 * single window reload once signals stop for this long, instead of one reload
 * per signal. The trade-off is that an isolated single edit now shows after
 * this delay rather than instantly — correctness over ~a quarter-second of
 * latency, and the reason the pre-debounce throttle left folder listings stale
 * (`notification-burst-aggregation.md` §2.5;
 * `docs/proposals/drive-folder-listing-consistency.md`).
 */
private const val INVALIDATION_COOLDOWN_MS = 400L

/**
 * The longest a listing's first read waits for its change observer to be confirmed (see
 * [reloadSignals]). ⚠ A backstop for a lost confirmation, not a budget: with Control warm the
 * confirmation lands in ~0.3–2.7 s, and a refused subscribe answers at once.
 *
 * ⚠⚠ Sized for a **cold** Control, not a warm one (`photos-app.md` §38). There the confirmation came
 * 6.4 and 8.6 s after launch on the maintainer's phone; at 5 s this deadline fired first, the first
 * read went out anyway, and the confirmation then re-read — the very double read the wait exists to
 * remove, under the heaviest load it ever meets (first rows 13.8 / 16.5 s). A deadline that is
 * overtaken costs a duplicate whole read; one that is too long costs only the rare lost confirmation.
 */
internal const val FIRST_LOAD_OBSERVER_DEADLINE_MS = 10_000L

/** Shared default for [pagingSource]/[toPagingData] callers that don't tune paging. */
internal fun defaultPagingConfig() = PagingConfig(
    pageSize = 20,
    enablePlaceholders = false
)

fun <T : Data, R : Any> TList<T>.pagingSource(
    mapper: (T) -> R?,
    pagingConfig: PagingConfig = defaultPagingConfig(),
    ordering: (TList<T>) -> TList<T> = { it },
    invalidation: (TList<T>) -> TList<*> = { it },
    rowKey: ((R) -> Any)? = null,
): Flow<PagingData<R>> =
    pagingSource(mapper, pagingConfig, ordering, invalidation, rowKey, projection = { it })

/**
 * As [pagingSource], with a per-window [projection] (e.g. an entity-aware
 * `select { ... }`, hence the separate projected element type [P]) applied
 * AFTER the page's `drop().take()` window — see [TenshuPagingSource].
 *
 * The three hooks all default to the unsorted, unprojected, undeduped behaviour; their contract is
 * specified in `drive-sorted-folder-paging.md` §6.
 *
 * @param ordering the sort each page is cut under, applied ahead of the window inside `load` (see
 *   [TenshuPagingSource]). The receiver itself stays unsorted.
 * @param invalidation the chain whose changes rebuild the `Pager` — [reloadSignals] observes
 *   **this**, not the receiver. ⚠⚠ For a listing whose rows are composites the receiver is the
 *   wrong thing to observe: a bare `Folder.files` diff compares `path` alone, so an in-place
 *   overwrite (same identity, new size and date) produces no signal — the row keeps its stale
 *   place, and one that moved above the loaded boundary is never served at all. Pass a
 *   **projected** chain, whose rows diff as entities. ⚠⚠ Never pass the *sorted* chain (a sorted
 *   observer re-sorts the whole list on every write), and never one projecting an on-demand
 *   property such as `File.Preview` (re-resolved for every row on every change).
 * @param rowKey enables the per-generation dedupe ([TenshuPagingSource.servedKeys]).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
fun <T : Data, P : Data, R : Any> TList<T>.pagingSource(
    mapper: (P) -> R?,
    pagingConfig: PagingConfig = defaultPagingConfig(),
    ordering: (TList<T>) -> TList<T> = { it },
    invalidation: (TList<T>) -> TList<*> = { it },
    rowKey: ((R) -> Any)? = null,
    projection: (TList<T>) -> TList<P>,
): Flow<PagingData<R>> {
    val context = this as? TContext ?: error("TList must implement TContext to use pagingSource")
    val list = this

    // Reactive reload strategy: rebuild the whole `Pager` per settled change via
    // `flatMapLatest`, rather than reusing one `InvalidatingPagingSourceFactory`
    // and calling `invalidate()`. This is the Android folder-listing-staleness
    // bug (`docs/proposals/drive-folder-listing-consistency.md`). On-device
    // instrumentation proved the data is never wrong — the proxy-computed window
    // and `Folder.FileCount` stay correct throughout; it's purely client-side
    // Paging reactivity that drops rows.
    //
    // ⚠⚠ This comment used to forbid `cachedIn` downstream, on the grounds that it DROPS the
    // refresh `load()` on every new `PagingData` generation after the first — so with `cachedIn`
    // every create after the initial load failed to appear until re-entry. That was measured
    // on-device on 2026-07-10 and was real. It has since **stopped being true**: SR-1 of
    // `core-paging-scroll-restoration.md` (2026-08-12) put it under test on paging 3.5.0 and could
    // not reproduce it, either in an isolated fixture (`CachedInRefreshContractTest`) or against
    // this very pipeline through the real differ (`FilesPagingReactivityUiTest`). Drive's view
    // models now DO wrap this in `cachedIn`, so a listing survives a navigation round-trip with its
    // loaded pages intact.
    //
    // ⚠ The rule decayed unnoticed because its only evidence was a device session and a doc
    // paragraph — the desktop twin was `@Ignore`d and hanging when it was written. Both directions
    // are permanent tests now. If `FilesPagingReactivityUiTest` ever goes red, the ban is back and
    // `core-paging-scroll-restoration.md` §7 holds the contingency design.
    //
    // Trailing `debounce` collapses the burst of change signals a single logical
    // change emits (the entity save, the parent-relation maintainer's array
    // update, and the store's write-commit echo — empirically two waves ~280 ms
    // apart) into ONE reload; [INVALIDATION_COOLDOWN_MS] is sized to span that
    // echo so one create is one reload, not two. `flatMapLatest` also cancels a
    // superseded in-flight reload cleanly. Trade-off: a reactive reload restarts
    // from the refresh key, so a create while deep-scrolled re-anchors near the
    // top — unnoticeable for the usual create-from-top flow.
    //
    @Suppress("UNCHECKED_CAST")
    val observed = invalidation(list) as TList<Data>
    return observed.reloadSignals(firstLoadWaitsForObserver = false).flatMapLatest { reload ->
        Pager(config = pagingConfig) {
            TenshuPagingSource(context, list, projection, ordering, rowKey, mapper)
        }.flow.labelled(reload)
    }
}

/**
 * When to rebuild the `Pager` — one emission to start, then one per settled change to the list.
 *
 * Shared by [pagingSource] and [keysetPagingSource]; the rebuild strategy above explains *why* it
 * is a rebuild rather than an `invalidate()`, and this is the signal that drives it.
 *
 * Signal-only observation (`changes()`, not `flow().drop(1)`): the observer only needs a change
 * *signal*; `flow()` would ship the whole raw list proxy→client on subscribe just to discard it.
 * `changes()` carries the intent to the proxy (`Stream` op's `signalOnly`), skipping the initial
 * emission. ⚠⚠ Deliberately called on the UNPROJECTED receiver — observing a projected chain would
 * re-run the projection (relation resolution incl. thumbnails) over the WHOLE list on every change,
 * the feedback amplification that melted the 2k-file folder (see [TenshuPagingSource]).
 *
 * ## ⚠⚠ The first load waits for the observer (`photos-app.md` §38)
 *
 * The first emission is the observer's **subscribe confirmation** (`emitOnSubscribe`), not an
 * immediate tick. Registering the observer is a round trip to the proxy, and a write landing before
 * it is registered is signalled to nobody — so a page read before the confirmation can be stale with
 * nothing coming to correct it. This used to be handled by reading at once AND re-reading on the
 * confirmation; `flatMapLatest` cancelled the first read on the client, but **the proxy finished it
 * anyway** (the cancellation does not cross Binder), so the listing's first page — and every
 * rebuild of the stream, such as a Photos zoom change — cost two whole reads. On the maintainer's
 * phone the confirmation came 0.4–2.7 s after the first read began and the abandoned read ran on for
 * 1.9–8.2 s beside the one that counted (§37.1). Reading once, after the confirmation, closes the
 * same gap by ordering instead.
 *
 * Two exits keep the wait from becoming a hang:
 * - **A failed subscribe ticks at once** (`emitOnFailedSubscribe`). A proxy that is not there
 *   refuses the subscribe, and the page read it was holding back is what fails and tells the user
 *   so — it must not sit on a spinner instead. The retry's own tick re-reads once a subscribe lands.
 * - **[FIRST_LOAD_OBSERVER_DEADLINE_MS]** — a confirmation that never arrives (a lost callback)
 *   delays the first page by that much; it never withholds it. If the confirmation turns up after
 *   the deadline it still reloads, which is the old read-then-re-read and exactly as correct.
 *
 * ## ⚠ Scoped to the keyset source ([firstLoadWaitsForObserver])
 *
 * Only [keysetPagingSource] — the Photos feed, the listing whose page is a whole-realm walk —
 * waits. [pagingSource] (Drive's folders, Control's events, Fit's history) keeps reading at once and
 * re-reading on the confirmation: its pages are bounded and cheap, so the duplicate costs little,
 * while waiting would put one subscribe round trip (~0.5–0.9 s on a warm phone, §38) in front of
 * every folder's first rows — a trade nobody has measured for those listings. Widen it when someone
 * does.
 */
@OptIn(FlowPreview::class)
internal fun <T : Data> TList<T>.reloadSignals(firstLoadWaitsForObserver: Boolean): Flow<Reload> =
    if (firstLoadWaitsForObserver) firstLoadAfterTheObserver() else merge(
        flowOf(Reload.PROVISIONAL), // initial load — at once, and again on the subscribe tick below
        changes(emitOnSubscribe = true)
            .debounceExceptFirst(INVALIDATION_COOLDOWN_MS, INVALIDATION_MAX_WAIT_MS)
            .map { Reload.OBSERVED },
    )

@OptIn(FlowPreview::class)
private fun <T : Data> TList<T>.firstLoadAfterTheObserver(): Flow<Reload> = channelFlow {
    val deadline = launch {
        delay(FIRST_LOAD_OBSERVER_DEADLINE_MS)
        send(Reload.PROVISIONAL)
    }
    changes(emitOnSubscribe = true, emitOnFailedSubscribe = true)
        .debounceExceptFirst(INVALIDATION_COOLDOWN_MS, INVALIDATION_MAX_WAIT_MS)
        .collect {
            deadline.cancel()
            send(Reload.OBSERVED)
        }
}

/**
 * Reactive windowed paging over a chain-built [TList] — the repository-facing
 * name for [pagingSource]. One helper for every paged listing (Drive Files,
 * Control events after `notification-burst-aggregation.md` Slice 3): build the
 * chain (`navigateTo`/`sortByDescending`/…), then `.toPagingData(mapper)`.
 *
 * A per-row `select { ... }` must NOT be baked into the receiver chain — pass
 * it as [projection] instead, so it runs on each page's `drop().take()` window
 * rather than the whole list (see [TenshuPagingSource] for why this is
 * load-bearing). [pagingConfig] tunes page size / prefetch per screen; the
 * default is 20 rows, no placeholders. The [mapper] may be partial
 * (`(T) -> R?`) — see [TenshuPagingSource].
 *
 * Formerly lived in `:drive-shared-logic` as a commonMain bridge from the days
 * `:core-paging` was JVM-only; multiplatform `paging-common` made the bridge
 * redundant, so it moved here where both apps reach it.
 */
fun <T : Data, R : Any> TList<T>.toPagingData(
    mapper: (T) -> R?,
    pagingConfig: PagingConfig = defaultPagingConfig(),
    ordering: (TList<T>) -> TList<T> = { it },
    invalidation: (TList<T>) -> TList<*> = { it },
    rowKey: ((R) -> Any)? = null,
): Flow<PagingData<R>> =
    pagingSource(mapper, pagingConfig, ordering, invalidation, rowKey)

/**
 * As [toPagingData], with a per-window [projection] — the shape Drive's Files
 * listing uses (`.navigateTo(folder).toPagingData(mapper) { it.select {…} }`).
 * [ordering], [invalidation] and [rowKey] are the sorted listing's three hooks — see
 * [pagingSource].
 */
fun <T : Data, P : Data, R : Any> TList<T>.toPagingData(
    mapper: (P) -> R?,
    pagingConfig: PagingConfig = defaultPagingConfig(),
    ordering: (TList<T>) -> TList<T> = { it },
    invalidation: (TList<T>) -> TList<*> = { it },
    rowKey: ((R) -> Any)? = null,
    projection: (TList<T>) -> TList<P>,
): Flow<PagingData<R>> =
    pagingSource(mapper, pagingConfig, ordering, invalidation, rowKey, projection)

/**
 * Trailing-debounce every emission **except the first**, which passes straight through — and
 * never defer an emission longer than [maxWaitMs] while signals keep arriving.
 *
 * ⚠ The first emission is the `emitOnSubscribe` readiness tick, and debouncing it caused a real
 * regression: it landed ~[INVALIDATION_COOLDOWN_MS] *after* the listing was already on screen and
 * being scrolled, and a new `PagingData` generation mid-scroll resets the presenter under the
 * reader (`IndexOutOfBoundsException: Index: 44, Size: 40` out of `SnapshotLoader.awaitLoad` —
 * `FilesListingEfficiencyTest`, 3 of 3 full gates, invisible in isolation because the scroll
 * finished first). Passing it through immediately makes the catch-up coincide with the initial
 * load, which `flatMapLatest` then supersedes cleanly, instead of interrupting a scroll. ⚠ For a
 * keyset listing that tick IS the initial load since `photos-app.md` §38 ([reloadSignals]), so
 * debouncing it would also add the cooldown to its first page.
 *
 * The distinction is principled rather than a workaround: the cooldown exists to collapse a *burst*
 * of change signals into one reload, and a one-off readiness tick has no burst to collapse.
 *
 * **The ceiling** ([INVALIDATION_MAX_WAIT_MS]): a pure trailing debounce restarts its timer on
 * every signal, so a burst that never pauses for [timeoutMs] never emits. The first signal after
 * an emission arms a second timer that fires regardless of what follows; whichever of the two
 * fires first emits, and the other finds the epoch moved on and does nothing. Two timers rather
 * than a clock, so virtual time in tests drives it exactly like wall time.
 *
 * Structurally: the trailing timer is a coroutine cancelled and relaunched per signal (the
 * `collectLatest` + `delay` idiom, made explicit); the ceiling is a coroutine launched once per
 * burst. Emission is serialised through [Mutex] and an epoch counter, because Paging's rebuild
 * must never be triggered twice for one burst.
 */
private fun Flow<Unit>.debounceExceptFirst(timeoutMs: Long, maxWaitMs: Long): Flow<Unit> =
    channelFlow {
        var passedFirst = false
        val lock = Mutex()
        var epoch = 0                // guarded by lock: bumped on every emission
        var ceilingArmed = false     // guarded by lock: a ceiling timer is pending for this epoch
        var trailing: Job? = null    // collector-only

        suspend fun fire(armedEpoch: Int) {
            val emit = lock.withLock {
                if (epoch != armedEpoch) return@withLock false
                epoch++
                ceilingArmed = false
                true
            }
            if (emit) send(Unit)
        }

        collect {
            if (!passedFirst) {
                passedFirst = true
                send(Unit)
                return@collect
            }
            val (armedEpoch, armCeiling) = lock.withLock {
                val arm = !ceilingArmed
                ceilingArmed = true
                epoch to arm
            }
            trailing?.cancel()
            trailing = launch {
                delay(timeoutMs)
                fire(armedEpoch)
            }
            if (armCeiling) {
                launch {
                    delay(maxWaitMs)
                    fire(armedEpoch)
                }
            }
        }
    }
