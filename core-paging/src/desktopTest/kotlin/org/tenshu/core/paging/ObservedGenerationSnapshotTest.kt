package org.tenshu.core.paging

import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.Interactor
import org.tenshu.core.InteractorProvider
import org.tenshu.core.common.PureOperationRunnerImplementation
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.TStringDto
import org.tenshu.core.common.proxy.TraitResolverImpl
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A live listing driven by paging-testing's `asSnapshot`, with the observer's confirmation landing
 * **inside** the scroll — the shape that failed `FilesListingEfficiencyTest` on public Linux CI with
 * `IndexOutOfBoundsException: Index: 20, Size: 20` out of `SnapshotLoader.awaitLoad`.
 *
 * A [pagingSource] listing reads at once and again when its observer is confirmed, one proxy round
 * trip later. On a fast machine that second generation lands before the scroll starts; on a slow one
 * it lands mid-scroll and resets the presenter under an index `SnapshotLoader` is holding. Here the
 * confirmation is delivered from inside the first append's read, so the late arrival is forced
 * rather than waited for, and both arms run on virtual time.
 */
class ObservedGenerationSnapshotTest {

    private lateinit var tContext: TContext

    @BeforeTest
    fun setUp() {
        val registry = createCoreTestRegistry()
        val resolverContext = createTestContext(schemaRegistry = registry)
        val resolver = TraitResolverImpl { resolverContext }
        val pureExecutor = PureOperationRunnerImplementation({ resolverContext }) {
            OperationImplementationFactory { _ -> null }
        }

        @Suppress("UNCHECKED_CAST")
        val interactorProvider = object : InteractorProvider {
            override suspend fun <T : Data?> getInteractor(ro: RequestObject): Interactor<T> =
                ro as Interactor<T>
        }

        tContext = createTestContext(
            schemaRegistry = registry,
            traitResolver = resolver,
            pureOperationRunner = pureExecutor,
            additionalComponents = mapOf(InteractorProvider.COMPONENT_ID to interactorProvider),
        )
    }

    /** The race itself, so the fix below is known to be removing something real. */
    @Test
    fun asSnapshotAlone_throwsWhenTheConfirmationLandsMidScroll() = runTest {
        val feed = confirmedMidScroll()

        val thrown = assertFailsWith<IndexOutOfBoundsException> {
            feed.listing().asSnapshot { scrollTo(SCROLL_TO) }
        }
        // The shape public Linux CI saw (`Index: 20, Size: 20` there): an index computed on the first
        // generation, peeked on the second, which holds one initial page.
        assertEquals("Index: ${2 * PAGE - 1}, Size: $PAGE", thrown.message)
    }

    @Test
    fun fromTheObservedGeneration_scrollsOneGeneration_whenTheConfirmationIsLate() = runTest {
        val feed = confirmedMidScroll()
        // Nothing reads the provisional generation now, so the mid-scroll hook never fires: the
        // confirmation comes the way a proxy sends it, on its own and late.
        backgroundScope.launchConfirmation(feed)

        val snapshot = feed.listing().fromTheObservedGeneration().asSnapshot { scrollTo(SCROLL_TO) }

        assertEquals(feed.rows.take(snapshot.size), snapshot, "one generation, in order, from the top")
        assertEquals(true, snapshot.size > SCROLL_TO, "the scroll reached index $SCROLL_TO")
        assertEquals(
            0, feed.pagesReadBeforeConfirmation,
            "the provisional generation is dropped before anything collects it, so it reads nothing",
        )
    }

    /** A flow nothing labels — any non-[pagingSource] listing — passes through untouched. */
    @Test
    fun fromTheObservedGeneration_isTransparentToAnUnlabelledFlow() = runTest {
        val rows = (1..30).map { "r$it" }
        val unlabelled: Flow<PagingData<String>> = flowOf(PagingData.from(rows))

        assertEquals(rows, unlabelled.fromTheObservedGeneration().asSnapshot())
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private class Feed(val list: ObservedList) {
        val rows: List<String> get() = list.rows
        var pagesReadBeforeConfirmation: Int? = null

        fun confirm() {
            if (pagesReadBeforeConfirmation != null) return
            pagesReadBeforeConfirmation = list.reads.pages
            list.confirmSubscribe()
        }
    }

    /**
     * A feed whose observer is confirmed by the read of the third window — mid-scroll, while
     * `SnapshotLoader` holds index 20 of a 20-row presenter.
     */
    private fun confirmedMidScroll(): Feed {
        val feed = Feed(ObservedList(tContext, (0 until 60).map { "r%02d".format(it) }.toMutableList()))
        feed.list.reads.onDrop = { offset -> if (offset >= 2 * PAGE) feed.confirm() }
        return feed
    }

    private fun CoroutineScope.launchConfirmation(feed: Feed) = launch {
        delay(1_500) // the round trip
        feed.confirm()
    }

    private fun Feed.listing(): Flow<PagingData<String>> = list.pagingSource(
        mapper = { (it as TStringDto).get() },
        pagingConfig = PagingConfig(
            pageSize = PAGE,
            initialLoadSize = PAGE,
            prefetchDistance = 1,
            enablePlaceholders = false,
        ),
    )

    private companion object {
        const val PAGE = 10
        const val SCROLL_TO = 25
    }
}
