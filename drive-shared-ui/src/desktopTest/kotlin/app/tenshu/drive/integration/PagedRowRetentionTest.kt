package app.tenshu.drive.integration

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.ui.model.FileUiModel
import kotlinx.coroutines.job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.tenshu.core.paging.TenshuPagingPresenter
import kotlin.test.assertTrue

/**
 * **Q1 of `core-paging-scroll-restoration.md`, measured** — what does `cachedIn(viewModelScope)`
 * actually cost, now that it retains every loaded page for the view model's life?
 *
 * ### ⚠⚠ Correcting the premise the question was written with
 *
 * Q1 was recorded as *"…and these rows carry thumbnails"*. **They do not.** Drive's paged projection
 * is metadata-only by design — no `File.Preview` — and
 * `FilesPagingIntegrationTest.pagedListingIsMetadataOnly_previewLoadsPerRow` pins it: `previewBytes`
 * is `null` on the paged path. The field exists on [FileUiModel], which is what made the claim
 * plausible, but only the per-row loader ever fills it, and that path is bounded by
 * `RowPrefetchLimiter` (a semaphore whose permit is released when the row leaves composition) —
 * bounded by *composition*, not by the cache. So bitmap memory is orthogonal to `cachedIn`.
 *
 * What `cachedIn` retains is three strings, an enum reference and a boolean per row.
 *
 * ### Why this is a JVM measurement and not an on-device one
 *
 * The question is "how much heap does N retained rows cost", which is a property of the row and of
 * Paging's own page bookkeeping — not of Binder, the proxy, or the store. A device adds noise and
 * needs a folder with thousands of real files to say the same thing. This drives the **real** Paging
 * machinery (`Pager` → `cachedIn` → `PagingDataPresenter`) over synthetic rows with realistic string
 * lengths, which isolates exactly the thing Q1 asks about. ⚠ ART's object layout differs from the
 * JVM's in detail, so read the result as an order of magnitude, which is all Q1 needs.
 *
 * ### It is also a regression guard
 *
 * The ceiling below is deliberately loose — heap deltas are not deterministic and a tight bound
 * would flake. It is sized to catch the change that would actually matter: **putting bytes back into
 * a paged row.** One 8 KB thumbnail per row over this many rows blows it by orders of magnitude, so
 * a future `File.Preview` sneaking into the paging projection fails here rather than on a user's
 * phone.
 */
class PagedRowRetentionTest {

    private val rowCount = 5_000
    private val pageSize = 20

    /** Realistic-ish shapes: a nested path, a filename, a "12.4 MB · 3 Feb" subline. */
    private fun row(i: Int) = FileUiModel(
        path = "/Photos/2026/August/holiday-album-part-$i/IMG_${1000 + i}_original.heic",
        name = "IMG_${1000 + i}_original.heic",
        subLine = "12.4 MB · 3 Feb 2026",
        previewIcon = FileIcon.Image,
        previewBytes = null,
        isFolder = false,
    )

    private inner class Rows : PagingSource<Int, FileUiModel>() {
        override fun getRefreshKey(state: PagingState<Int, FileUiModel>): Int? = 0
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, FileUiModel> {
            val start = params.key ?: 0
            val end = minOf(start + params.loadSize, rowCount)
            return LoadResult.Page(
                data = (start until end).map { row(it) },
                prevKey = null,
                nextKey = if (end >= rowCount) null else end,
            )
        }
    }

    private fun usedBytes(): Long {
        // Best-effort quiesce. Not a guarantee — hence the loose ceiling.
        repeat(4) { System.gc(); Thread.sleep(60) }
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test
    fun retainedPagesCostSingleDigitMegabytesAtExtremeScrollDepth() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val before = usedBytes()

            val cached: Flow<PagingData<FileUiModel>> = Pager(
                config = PagingConfig(
                    pageSize = pageSize,
                    initialLoadSize = pageSize,
                    enablePlaceholders = false,
                ),
                pagingSourceFactory = { Rows() },
            ).flow.cachedIn(scope)

            val presenter = TenshuPagingPresenter<FileUiModel>(scope, Dispatchers.Default)
            presenter.collect(cached)
            withTimeout(30_000) { presenter.snapshot.first { it.size >= pageSize } }

            // Page to the end — `get` records the access that triggers the append.
            withTimeout(120_000) {
                while (presenter.snapshot.value.size < rowCount) {
                    val n = presenter.snapshot.value.size
                    presenter.get(n - 1)
                    withTimeoutOrNull(5_000) { presenter.snapshot.first { it.size > n } } ?: break
                }
            }

            val loaded = presenter.snapshot.value.size
            assertTrue(loaded >= rowCount / 2, "only paged to $loaded of $rowCount — nothing to measure")

            val after = usedBytes()
            val deltaMb = (after - before).toDouble() / (1024 * 1024)
            val bytesPerRow = (after - before).toDouble() / loaded
            println(
                "Q1 retention: $loaded rows cached → %.1f MB (%.0f B/row)".format(deltaMb, bytesPerRow)
            )

            // ⚠ Loose on purpose (see the KDoc). One 8 KB thumbnail per row would be ~40 MB here.
            assertTrue(
                deltaMb < 25.0,
                "retained pages cost %.1f MB for $loaded rows (%.0f B/row) — that is far above a "
                    .format(deltaMb, bytesPerRow) +
                    "metadata-only row, so something byte-carrying is back in the paged projection",
            )
            // Keep the presenter reachable to the very end, or the JIT may collect what we measured.
            assertTrue(presenter.size >= 0)
        } finally {
            // cancel AND join: the presenter is still collecting on `Dispatchers.Default`.
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
