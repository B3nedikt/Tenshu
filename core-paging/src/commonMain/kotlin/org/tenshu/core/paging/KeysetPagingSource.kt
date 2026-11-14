package org.tenshu.core.paging

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.drop
import org.tenshu.core.cancellableReads
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.core.TContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.max

/**
 * Where a page starts. Two shapes in one type, because Paging allows exactly one key type:
 *
 * - **Cursor-anchored** ([cursor] non-null): continue below [cursor], dropping [skip] rows tied at
 *   it. This is the normal forward read, and it is the one that does not drift when rows are
 *   inserted ahead of it.
 * - **Positional** ([cursor] `null`): open the window at its head and drop [position] rows. Used
 *   for the two operations that *seek* rather than continue — a scrubber jump, and a prepend.
 *
 * ⚠⚠ [skip] is not an optimisation, it is what makes a cursor able to move at all. The window a
 * caller supplies is **inclusive** of the cursor (see [keysetPagingSource]) — deliberately, because
 * an exclusive one silently loses every row tied at a page boundary. Inclusive means the rows the
 * previous page already served at that boundary value come back at the head of the next window, and
 * [skip] is how many of them to drop. A page whose rows *all* share one cursor value leaves the
 * cursor exactly where it was, so without [skip] the pager re-serves the same rows forever.
 *
 * That is not hypothetical: it is `core-keyset-paging.md` §6.3, where the same defect had three
 * sites in Photos and two of them did not merely duplicate rows but **failed to terminate**.
 *
 * [position] is the index of the first row the page serves, in the whole ordered list. It is what
 * `LoadResult.Page.itemsBefore` needs, and carrying it in the key is what makes a jump free: the
 * refresh key after a seek is a position, and the page it produces knows where it sits without
 * counting anything.
 */
data class KeysetKey<K : Any>(
    val cursor: K?,
    val skip: Int = 0,
    val position: Int = 0,
)

/**
 * Paging over a chain-built [TList] keyed by a **cursor** rather than an offset.
 *
 * [TenshuPagingSource] is `PagingSource<Int, R>` and its key is a position, which is correct only
 * while the source list is stable between page reads. When rows are inserted *ahead* of the cursor,
 * page *n+1* re-serves a row from page *n* or skips one, because the offset that addressed row 40
 * now addresses row 39. ⚠⚠ For a whole-realm feed with a backup drain running that is the normal
 * case, not an edge case — `core-keyset-paging.md` §2. A cursor on the sort key cannot drift that
 * way: it names a *row*, and an insert elsewhere does not move it.
 *
 * The caller supplies the two halves the chain already understands:
 *
 * - [window] — the chain that opens the page's window at a cursor, ending in the same `sortBy` the
 *   listing is ordered by. **Must be inclusive of the cursor** (`inRange(0, before)`, not `<`); see
 *   [KeysetKey].
 * - [cursorOf] — the sort key of a row, read off the **projected source row** [P] rather than off
 *   the mapped [R]. ⚠ That is deliberate: [mapper] may be partial, and a page whose rows are *all*
 *   dropped by it still has to advance the cursor or the pager stalls on one window forever.
 *
 * ⚠⚠ **Only the forward read is keyset. Seeks are positional** — a scrubber jump and a prepend both
 * open the window at its head and drop to where they need to be. That is not a shortcut, it is the
 * only thing a cursor cannot express: "the row 12,000 rows down" is a rank, and ranks are what
 * offsets are for. The trade is stated rather than hidden — a seek can drift by however many rows
 * were inserted since the page it seeks against was read, which resolves on the next invalidation
 * (and an insert *causes* an invalidation). The forward read, which is where the user spends
 * essentially all of their time, never drifts.
 *
 * ⚠ A seek costs one `O(position)` read: `sort.drop(p).take(n)` fuses to a bounded top-(p + n)
 * selection of thin rows (`core-ordered-window-fusion.md` F2). Continuing from there is
 * `O(pageSize)` per page again.
 *
 * **Placeholders and jumping.** Supply [totalCount] and the source reports `itemsBefore`/
 * `itemsAfter` and declares [jumpingSupported], which is what lets a fast-scroll thumb address the
 * whole library rather than the loaded window. Without it the counts stay `COUNT_UNDEFINED` and
 * jumping is off — Paging then walks every intervening page to reach a far scroll target.
 *
 * As with [TenshuPagingSource]: [load] is one-shot (change observation lives in the factory, so a
 * source cannot leak an observation coroutine per generation), the [projection] runs on the
 * **windowed** chain rather than the whole list, and the page is read through the synchronous
 * `getAll` path rather than a stream's initial emission.
 */
class TenshuKeysetPagingSource<T : Data, P : Data, R : Any, K : Any>(
    @Suppress("unused") private val context: TContext,
    private val tenshuList: TList<T>,
    private val window: (TList<T>, K?) -> TList<T>,
    private val projection: (TList<T>) -> TList<P>,
    private val cursorOf: (P) -> K,
    private val mapper: (P) -> R?,
    /**
     * The length of the whole ordered list. `null` ⇒ no placeholders, no jumping.
     *
     * ⚠⚠ Called **once per source instance**, not once per page — see [total]. For the caller this
     * exists for, it is a `count()` over a filtered whole-realm traversal, and a filtered traversal
     * has no O(1) member-hash count to fall back on: it walks the tree. Per page that would double
     * every page's cost to keep a placeholder region exact to the row.
     */
    private val totalCount: (suspend () -> Int?)? = null,
    /**
     * Where a page's read, the count beside it, and the mapping of its rows run. Empty keeps them
     * where Paging calls [load] — the collector's scope.
     *
     * ⚠⚠ That is right when the read is IPC, which suspends, and wrong when the proxy runs in the
     * caller's own process (iOS route 1): there a read is a store query on the calling thread, and
     * Paging calls [load] from the scope the list is cached in — the main thread. ⚠ Only the read
     * moves: the key arithmetic and Paging's own state stay where they were, so the order pages are
     * asked for and placed in is unchanged.
     */
    private val readContext: CoroutineContext = EmptyCoroutineContext,
) : PagingSource<KeysetKey<K>, R>() {

    /**
     * ⚠ Jumping is meaningless without placeholders: a jump is a scroll *through* placeholders past
     * `PagingConfig.jumpThreshold`, so with none there is nothing to scroll through. Tying it to
     * [totalCount] keeps the two from being configured apart — and `PageFetcherSnapshot` requires
     * this to be `true` whenever a config sets `jumpThreshold`, so a caller cannot half-enable it.
     */
    override val jumpingSupported: Boolean get() = totalCount != null

    /**
     * The list's length, counted once and then remembered for this source's lifetime.
     *
     * ⚠ A source *is* one generation: the factory rebuilds the `Pager` per settled change, so a row
     * arriving means a new source and a fresh count. "Once per generation" is therefore not a
     * staleness compromise but the same unit the rest of the pipeline already works in — the window
     * a count could be wrong in is the invalidation debounce, and being one row out for that long
     * sizes a placeholder region imperceptibly differently.
     *
     * ⚠ Deliberately unsynchronised. Paging can have an append and a prepend in flight together, so
     * two loads may both find it unset and both count; they get the same answer and one wins. A
     * mutex here would serialise page loads against each other to prevent a duplicate read that
     * costs less than the mutex. ⚠ `@Volatile` because with a [readContext] those two loads can be on
     * two threads: it makes the answer one of them wrote visible to the other, nothing more.
     */
    @kotlin.concurrent.Volatile
    private var countedTotal: Int? = null

    private suspend fun total(): Int? {
        val counter = totalCount ?: return null
        // A count is a read: a superseded generation's must stop in Control too (see [load]).
        return countedTotal ?: cancellableReads { counter() }?.also { countedTotal = it }
    }

    /**
     * Where a reload should re-open the list.
     *
     * With placeholders on, `anchorPosition` is an index into the **whole** list, so it is directly
     * a seek — which is exactly what a jump needs, and the reason a scrub lands where it was
     * dragged instead of back where it started.
     *
     * ⚠ Without placeholders `anchorPosition` counts loaded rows only, so it is not a rank and
     * cannot be used as one. There the key is *the previous page's* `nextKey` — read that way, not
     * as the anchor page's `prevKey`, because `prevKey` is a positional seek and re-serving the
     * anchor page through it would give up the cursor for no reason.
     *
     * ⚠ With `PagingConfig.maxSize` set, Paging drops leading pages, so index 0 stops meaning "the
     * head of the list" and a deep anchor would re-anchor at the top of the *loaded* window
     * instead. Nothing sets `maxSize` today.
     */
    override fun getRefreshKey(state: PagingState<KeysetKey<K>, R>): KeysetKey<K>? {
        val anchor = state.anchorPosition ?: return null

        if (jumpingSupported) {
            return if (anchor <= 0) null else KeysetKey(cursor = null, position = anchor)
        }

        val page = state.closestPageToPosition(anchor) ?: return null
        // Referential, not `indexOf`: LoadResult.Page is a data class, so two structurally equal
        // pages would resolve to the same index.
        val index = state.pages.indexOfFirst { it === page }
        if (index <= 0) return null
        return state.pages[index - 1].nextKey
    }

    override suspend fun load(params: LoadParams<KeysetKey<K>>): LoadResult<KeysetKey<K>, R> {
        val key = params.key
        val slice = sliceFor(params, key)
            ?: return LoadResult.Page(
                data = emptyList(),
                prevKey = null,
                nextKey = key,
                itemsBefore = if (totalCount != null) 0 else LoadResult.Page.COUNT_UNDEFINED,
                itemsAfter = LoadResult.Page.COUNT_UNDEFINED,
            )

        return try {
            inReadContext {
                // ⚠⚠ Started BESIDE the page, not after it (`photos-app.md` §38, fix 3). For the Photos
                // feed both are whole-realm walks in Control, and in series the count held every first
                // page back by another 1.0–1.8 s on a phone (§37.1). Memoised, so on every load after the
                // generation's first this answers at once.
                val counting = if (totalCount != null) async { total() } else null

                val windowed = window(tenshuList, slice.cursor)
                    .drop(slice.offset)
                    .take(slice.size)

                // ⚠ `cancellableReads`: Paging cancels a load whenever a generation is superseded (a
                // new `PagingData`, the subscribe tick, a density change), and without it Control
                // walked every abandoned page to the end beside the one that counts — 1.9–8.2 s on a
                // device (`photos-app.md` §37, `core-background-processing.md` §6.2.1). A page is a
                // pure read, so stopping it part-way loses nothing. ⚠ The count beside it is one too
                // ([total]), so cancelling [counting] on a failed page stops it in Control as well.

                when (val result = cancellableReads { projection(windowed).getAll() }) {
                    is TResult.Success<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val raw = (result.value as TList<P>).toList()
                        val total = counting?.await()

                        LoadResult.Page(
                            data = raw.mapNotNull(mapper),
                            // Always positional: "the page ending at where this one starts". A cursor
                            // cannot express it — see the class KDoc on seeks.
                            prevKey = if (slice.position == 0) null
                            else KeysetKey(cursor = null, position = slice.position),
                            nextKey = nextKeyAfter(raw, slice, key),
                            itemsBefore = if (total != null) slice.position else LoadResult.Page.COUNT_UNDEFINED,
                            itemsAfter = if (total != null) {
                                max(0, total - slice.position - raw.size)
                            } else LoadResult.Page.COUNT_UNDEFINED,
                        )
                    }

                    is TResult.Failure<*> -> {
                        // ⚠ A failed page is an error now — it does not wait for a count nobody will use.
                        counting?.cancel()
                        LoadResult.Error(Exception(result.error.toString()))
                    }
                    else -> error("Unexpected result type: $result")
                }
            }
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    /** ⚠ `coroutineScope` when nothing was asked for: the caller's context exactly, as it always was. */
    private suspend fun <T> inReadContext(block: suspend CoroutineScope.() -> T): T =
        if (readContext === EmptyCoroutineContext) coroutineScope(block) else withContext(readContext, block)

    /** The window this load reads, or `null` when there is provably nothing to read. */
    private fun sliceFor(params: LoadParams<KeysetKey<K>>, key: KeysetKey<K>?): Slice<K>? = when {
        params is LoadParams.Prepend -> {
            // The rows immediately above the anchor page: `[end - loadSize, end)`.
            val end = key?.position ?: 0
            if (end <= 0) null
            else {
                val start = max(0, end - params.loadSize)
                Slice(cursor = null, offset = start, size = end - start, position = start)
            }
        }
        // A seek (or the very first load): open at the window's head and drop into place.
        key?.cursor == null -> Slice(
            cursor = null,
            offset = key?.position ?: 0,
            size = params.loadSize,
            position = key?.position ?: 0,
        )
        // The forward read: continue below the cursor, past the rows already served at its value.
        else -> Slice(
            cursor = key.cursor,
            offset = key.skip,
            size = params.loadSize,
            position = key.position,
        )
    }

    /**
     * The key the *next* page starts from: the last row's key, plus how many rows now share it.
     *
     * ⚠ The tie count carries across pages. Rows at that value served by *earlier* pages count too,
     * which is the case of one tie run spanning a page boundary; counting this page alone would
     * re-serve the earlier page's share of the run.
     *
     * ⚠⚠ A **positional** page cannot always hand back a cursor. Its window opened at the head and
     * dropped, so rows tied with the page's last row may sit *above* where it started — invisible
     * from here, and a cursor whose skip missed them would re-serve them on the next page. The tell
     * is cheap: if the page's first row's key differs from its last, the boundary's tie run began
     * inside the page and the count is exact. If the whole page is one tie run it may not be, and
     * the next page stays positional rather than guessing. ⚠ That case is also the one where a
     * cursor buys nothing — you cannot step through a tie run with a value that never changes.
     *
     * ⚠ Computed off the unmapped window, so a page that [mapper] empties still advances. An empty
     * raw window is the end of the list, and only that.
     */
    private fun nextKeyAfter(
        raw: List<P>,
        slice: Slice<K>,
        from: KeysetKey<K>?,
    ): KeysetKey<K>? {
        if (raw.isEmpty()) return null
        val keys = raw.map(cursorOf)
        val boundary = keys.last()
        val position = slice.position + raw.size

        if (slice.cursor == null && keys.first() == boundary) {
            return KeysetKey(cursor = null, position = position)
        }

        val carried = if (from?.cursor == boundary) from.skip else 0
        return KeysetKey(boundary, keys.count { it == boundary } + carried, position)
    }

    private class Slice<K : Any>(
        /** `null` ⇒ read positionally from the window's head. */
        val cursor: K?,
        /** How far into the window the page starts: a tie skip, or an absolute rank. */
        val offset: Int,
        val size: Int,
        /** Index of the first row served, in the whole ordered list. */
        val position: Int,
    )
}

/**
 * A [PagingConfig] for a listing whose scroll bar addresses the **whole** list, not the loaded
 * window — placeholders on, jumping armed.
 *
 * ⚠⚠ The three settings are one decision, which is why they are one function: placeholders give the
 * track its full length, `jumpThreshold` is what turns a long drag into a re-anchor instead of a
 * walk through every intervening page, and `PageFetcherSnapshot` throws if a config sets
 * `jumpThreshold` against a source that does not declare `jumpingSupported` — which
 * [TenshuKeysetPagingSource] does exactly when it was given a `totalCount`. Configure them apart
 * and the failure is either a crash or a silently sequential seek.
 */
fun seekablePagingConfig(pageSize: Int = 20, jumpThreshold: Int = pageSize * 3) = PagingConfig(
    pageSize = pageSize,
    // ⚠ One page first, not Paging's default three (`photos-app.md` §38, fix 4) — Drive and Fit made
    // the same call for the same reason. A first page of 60 already fills a phone screen several
    // times, and the next one is prefetched as soon as the first is drawn, so a bigger first read
    // only delays the first row.
    initialLoadSize = pageSize,
    enablePlaceholders = true,
    jumpThreshold = jumpThreshold,
)

/**
 * Reactive keyset paging over a chain-built [TList] — the keyset twin of [toPagingData], for a
 * listing whose source gains rows while the user scrolls.
 *
 * ```
 * root.files.traverse()
 *     .filter<File> { category equalTo FileCategory.Image }
 *     .toKeysetPagingData(
 *         mapper = { it.toPhotoOrNull() },
 *         cursorOf = { it.createdAtMs },
 *         pagingConfig = seekablePagingConfig(),
 *         totalCount = { contentIndex.photoCount() },
 *     ) { list, before ->
 *         list.filter<File> { createdAt.inRange(0L, before ?: Long.MAX_VALUE) }
 *             .sortByDescending(File.CreatedAt)
 *     }
 * ```
 *
 * ⚠⚠ [window] owns the *ordering* as well as the bound, because the two cannot be separated: the
 * cursor is a position **in the sort**, so a window that filters without sorting — or sorts by
 * something other than [cursorOf]'s key — pages incoherently rather than failing.
 *
 * ⚠ The per-settled-change `Pager` rebuild is kept, and `core-keyset-paging.md` §6.1 asked whether
 * a keyset source still needs it. It does: insert-tolerance *while paging* is a different thing
 * from noticing an insert — the already-loaded pages still do not contain the new row, and
 * invalidation is the only thing that puts it there. What changes is what the rebuild costs the
 * reader, because the reload re-anchors (see [TenshuKeysetPagingSource.getRefreshKey]) rather than
 * restarting at the head.
 */
fun <T : Data, R : Any, K : Any> TList<T>.toKeysetPagingData(
    mapper: (T) -> R?,
    cursorOf: (T) -> K,
    pagingConfig: PagingConfig = defaultPagingConfig(),
    totalCount: (suspend () -> Int?)? = null,
    window: (TList<T>, K?) -> TList<T>,
): Flow<PagingData<R>> =
    keysetPagingSource(mapper, cursorOf, pagingConfig, totalCount, window, projection = { it })

/**
 * As [toKeysetPagingData], with a per-window [projection] (an entity-aware `select { ... }`)
 * applied AFTER the page's window — the shape a real listing uses. See [TenshuPagingSource] for why
 * a projection baked into the receiver chain instead is the 2k-row ANR.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T : Data, P : Data, R : Any, K : Any> TList<T>.keysetPagingSource(
    mapper: (P) -> R?,
    cursorOf: (P) -> K,
    pagingConfig: PagingConfig = defaultPagingConfig(),
    totalCount: (suspend () -> Int?)? = null,
    window: (TList<T>, K?) -> TList<T>,
    projection: (TList<T>) -> TList<P>,
    readContext: CoroutineContext = EmptyCoroutineContext,
    /**
     * Called once per reload signal, before that reload's `Pager` is built: first when the change
     * observer is live (or was given up on — see [reloadSignals]), then once per settled change to
     * the list. ⚠ Not per page, and not per jump: a jump rebuilds the source, not the `Pager`.
     *
     * For a caller that keeps something ELSE current off the same list and must not pay for a second
     * observer of it — a second signal-only stream re-evaluates the whole list per change
     * (`tech-debt.md`), and two observers of one chain share a stream id.
     */
    onReload: (() -> Unit)? = null,
): Flow<PagingData<R>> {
    val context = this as? TContext
        ?: error("TList must implement TContext to use keysetPagingSource")
    val list = this

    // ⚠ Loud rather than inert. Paging ignores itemsBefore/itemsAfter when placeholders are off, so
    // a caller who supplies a totalCount against the default config would get a full-length track
    // that silently is not one — the class of failure that gets a capability written down as
    // shipped without existing. `seekablePagingConfig()` is the pairing.
    require(totalCount == null || pagingConfig.enablePlaceholders) {
        "totalCount was supplied but PagingConfig.enablePlaceholders is false, so the counts " +
            "would be ignored and the listing would still be as long as its loaded window. " +
            "Use seekablePagingConfig()."
    }

    // ⚠⚠ The first page waits for the change observer (`photos-app.md` §38) — see [reloadSignals].
    return list.reloadSignals(firstLoadWaitsForObserver = true).flatMapLatest { reload ->
        // ⚠⚠ **The count is memoised per GENERATION, not per source, and the difference is a
        // whole read on every scrub.** `TenshuKeysetPagingSource` memoises the total on itself, so
        // one source counts once however many pages it serves — but Paging builds a *new* source
        // every time it invalidates, and a **jump invalidates**. A seek down a long track
        // therefore paid a refresh plus a fresh full count before any row appeared (~700 ms on a
        // 922-photograph library), which is most of the grey that follows a fast scrub.
        //
        // A jump does not change the data; it changes where the reader is looking. The count is a
        // property of the list, so the memo belongs to the thing that tracks the list changing —
        // `reloadSignals()` — and not to the thing that tracks the reader moving.
        val perGeneration = totalCount?.memoisedForOneGeneration()
        onReload?.invoke()
        Pager(config = pagingConfig) {
            TenshuKeysetPagingSource(
                context, list, window, projection, cursorOf, mapper, perGeneration, readContext,
            )
        }.flow.labelled(reload)
    }
}

/**
 * Count once, and keep the answer until the list itself changes.
 *
 * ⚠ A `null` is **not** memoised. `null` means the count failed — a dead proxy, a version-skewed
 * one — and remembering it would disable placeholders for the lifetime of the generation on the
 * strength of one bad read. A value is remembered; a failure is retried.
 *
 * ⚠ The lock is not decoration. Paging can create the prepend and append sources of one generation
 * close enough together that both find the memo empty, and two concurrent full-tree counts is the
 * cost this exists to avoid — twice over.
 */
internal fun (suspend () -> Int?).memoisedForOneGeneration(): suspend () -> Int? {
    val counter = this
    val lock = Mutex()
    var counted: Int? = null
    return {
        lock.withLock { counted ?: counter()?.also { counted = it } }
    }
}
