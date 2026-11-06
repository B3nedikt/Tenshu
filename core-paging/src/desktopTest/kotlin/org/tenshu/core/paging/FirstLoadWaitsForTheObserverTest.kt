package org.tenshu.core.paging

import androidx.paging.LoadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.Interactor
import org.tenshu.core.InteractorProvider
import org.tenshu.core.Operation
import org.tenshu.core.StreamCallback
import org.tenshu.core.StreamInteractor
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TaskInteractor
import org.tenshu.core.collection.list.Drop
import org.tenshu.core.collection.list.GetAll
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Take
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.PureOperationRunnerImplementation
import org.tenshu.core.common.assertNotReached
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.TStringDto
import org.tenshu.core.common.proxy.TraitResolverImpl
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.util.toTString
import org.tenshu.core.core.OperationResolver
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * When a paged listing reads its first page (`photos-app.md` §38, fix 1).
 *
 * ⚠⚠ **The confirmation arrives LATE here, on purpose.** Production registers the change observer
 * with a round trip to Control, and the `start` callback that confirms it comes back on its own,
 * after `registerCallback` has returned — 0.4–2.7 s later on the maintainer's phone (§37). A fixture
 * that confirms inside `registerCallback` (`TenshuPagingSourceTest.MockTList` does) collapses the two
 * reads into one before either is issued, so the defect this file pins is invisible there: that is
 * the convenient fake, and it is why these tests confirm by hand.
 *
 * What it pinned, before: the first page was read at once AND again when the confirmation landed —
 * two whole reads of the same window, the first cancelled on the client and finished by Control
 * anyway (1.9–8.2 s of its time, §37.1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstLoadWaitsForTheObserverTest {

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

    @Test
    fun theFirstPageIsReadOnce_whenTheObserverIsConfirmedAfterTheListingStarted() = runTest {
        val feed = ObservedList(tContext, rows(25))
        present(feed)

        runCurrent()
        assertEquals(
            0, feed.reads.pages,
            "no page is read before Control confirms the observer: a write in that gap would be " +
                "signalled to nobody, so a read made then is a read that has to be made again",
        )

        advanceTimeBy(1_500) // the round trip
        feed.confirmSubscribe()
        advanceTimeBy(2_000)

        assertEquals(1, feed.reads.pages, "one read of the first page, not one plus a re-read")
    }

    /** The guarantee the immediate read + re-read existed for, kept by ordering instead. */
    @Test
    fun aWriteBeforeTheObserverIsConfirmed_isInTheFirstPage() = runTest {
        val feed = ObservedList(tContext, rows(25))
        val presenter = present(feed)
        runCurrent()

        feed.rows.add(0, "9999:new") // lands while the registration is in flight
        feed.confirmSubscribe()
        advanceTimeBy(2_000)

        assertEquals("9999:new", presenter.window.value.items.firstOrNull())
    }

    /**
     * ⚠⚠ A subscribe that FAILS must not hold the listing on a spinner: the read it was holding back
     * is the one that fails and tells the user Control is not there (`changes`' `emitOnFailedSubscribe`).
     */
    @Test
    fun aFailedSubscribe_stillReadsTheFirstPage_atOnce() = runTest {
        val feed = ObservedList(tContext, rows(25))
        present(feed)
        runCurrent()

        feed.confirmSubscribe(GenericError.create(tContext, "Control is not there"))
        advanceTimeBy(200)

        assertEquals(1, feed.reads.pages, "the failure is answered by a read, not by waiting")
    }

    /**
     * ⚠⚠ Control unreachable or schema-skewed at launch: the subscribe is REFUSED, `changes()` goes
     * on retrying and emits nothing — and the feed must still end in its error state ("Can't reach
     * Tenshu Control"), not on a spinner. That state is produced by a page read failing, so the
     * refusal has to let one through.
     */
    @Test
    fun aRefusedSubscribe_endsInTheFeedsErrorState_notAnEndlessSpinner() = runTest {
        val feed = ObservedList(tContext, rows(25))
        feed.reads.fail = true
        val presenter = present(feed)
        runCurrent()

        feed.confirmSubscribe(GenericError.create(tContext, "session refused: schema skew"))
        advanceTimeBy(500)
        assertIs<LoadState.Error>(presenter.loadState.value?.refresh, "at once — not after the deadline")

        advanceTimeBy(30_000) // `changes()` retries meanwhile, each attempt left unanswered
        assertIs<LoadState.Error>(presenter.loadState.value?.refresh, "and it stays an error, never a spinner")
    }

    /** The same for a subscribe that is never answered at all: the deadline lets the failing read through. */
    @Test
    fun aSubscribeNeverAnswered_endsInTheFeedsErrorStateAfterTheDeadline() = runTest {
        val feed = ObservedList(tContext, rows(25))
        feed.reads.fail = true
        val presenter = present(feed)

        advanceTimeBy(FIRST_LOAD_OBSERVER_DEADLINE_MS + 100)

        assertIs<LoadState.Error>(presenter.loadState.value?.refresh)
    }

    /** ⚠ A confirmation that never comes (a lost callback) delays the first page; it never loses it. */
    @Test
    fun anObserverThatIsNeverConfirmed_stillReadsTheFirstPageAfterTheDeadline() = runTest {
        val feed = ObservedList(tContext, rows(25))
        present(feed)

        advanceTimeBy(FIRST_LOAD_OBSERVER_DEADLINE_MS - 100)
        assertEquals(0, feed.reads.pages)

        advanceTimeBy(200)
        assertEquals(1, feed.reads.pages)
    }

    /** Fix 4: the first load is one page, not Paging's default three. */
    @Test
    fun theFirstLoadReadsOnePage() = runTest {
        val feed = ObservedList(tContext, rows(25))
        present(feed)
        runCurrent()
        feed.confirmSubscribe()
        advanceTimeBy(2_000)

        assertEquals(listOf(PAGE), feed.reads.takes, "the first read asks for one page")
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private fun TestScope.present(feed: ObservedList): TenshuPagingPresenter<String> {
        val presenter = TenshuPagingPresenter<String>(
            backgroundScope,
            mainContext = StandardTestDispatcher(testScheduler),
        )
        presenter.collect(
            feed.keysetPagingSource(
                mapper = { (it as TStringDto).get() },
                cursorOf = { (it as TStringDto).get().substringBefore(':').toLong() },
                pagingConfig = seekablePagingConfig(pageSize = PAGE),
                totalCount = { feed.rows.size },
                window = { root, cursor ->
                    (root as ObservedList).where { key -> cursor == null || key <= cursor }
                },
                projection = { it },
            ),
        )
        return presenter
    }

    private fun rows(count: Int): MutableList<String> =
        (1..count).map { "${1000L - it}:r$it" }.toMutableList()

    private companion object {
        const val PAGE = 10
    }
}

/** What the listing asked of the "proxy". */
private class Reads {
    var pages = 0
    val takes = mutableListOf<Int>()

    /** Every page read answers a failure — Control is not there. */
    var fail = false
}

/**
 * A slicing [TList] whose root also serves `stream/start` — with the confirmation held until the
 * test delivers it ([confirmSubscribe]), which is how a proxy across a process boundary answers.
 */
private class ObservedList(
    override val context: TContext,
    val rows: MutableList<String>,
    private val provider: () -> List<String> = { rows },
    val reads: Reads = Reads(),
    private val callbacks: MutableList<StreamCallback<TString>> = mutableListOf(),
) : TList<TString>, RequestObject {

    fun where(predicate: (Long) -> Boolean): ObservedList = derived {
        provider().filter { predicate(it.substringBefore(':').toLong()) }
    }

    private fun derived(next: () -> List<String>) = ObservedList(context, rows, next, reads, callbacks)

    /** Delivers the observer's `start` — `null` = registered, an error = refused. */
    fun confirmSubscribe(error: TError? = null) {
        callbacks.toList().forEach { it.start(error) }
    }

    override val requestChain: RequestChain = object : RequestChain {
        override fun addReference(id: String): RequestChain = this
        override fun addArray(array: org.tenshu.core.TArray): RequestChain = this
        override fun addNestedArray(block: (RequestChain) -> RequestChain): RequestChain = this
        override fun getCallstack(): List<Data> = emptyList()
        override suspend fun getResult(): TResult<Data> = error("not used")
    }

    private val operations: Map<String, Operation> by lazy {
        mapOf(
            Drop.ID to object : Drop<TString> {
                override val schema: OperationSchema get() = Drop.Schema
                override fun <V : Data> getField(reference: String): V = assertNotReached()
                override fun <V : Data> getOptionalField(reference: String): V? = null
                override fun drop(n: Int): TList<TString> = derived { provider().drop(n) }
            },
            Take.ID to object : Take<TString> {
                override val schema: OperationSchema get() = Take.Schema
                override fun <V : Data> getField(reference: String): V = assertNotReached()
                override fun <V : Data> getOptionalField(reference: String): V? = null
                override fun take(n: Int): TList<TString> {
                    reads.takes += n
                    return derived { provider().take(n) }
                }
            },
            GetAll.ID to object : GetAll<TString> {
                override val schema: OperationSchema get() = GetAll.Schema
                override fun <V : Data> getField(reference: String): V = assertNotReached()
                override fun <V : Data> getOptionalField(reference: String): V? = null
                override fun getAll(): RequestObject = object : TaskInteractor<TList<TString>>, RequestObject {
                    @Suppress("UNCHECKED_CAST")
                    override suspend fun execute(): TResult<TList<TString>> {
                        reads.pages++
                        if (reads.fail) {
                            return TResult.Failure.create(context, GenericError.create(context, "Control is not there"))
                        }
                        return TResult.Success.create(
                            context,
                            provider().map { it.toTString(context) }.toTList(context) as TList<TString>,
                        )
                    }

                    override val context: TContext get() = this@ObservedList.context
                    override val requestChain: RequestChain get() = this@ObservedList.requestChain
                }
            },
            Stream.ID to object : Stream<TString>, StreamInteractor<TString>, RequestObject {
                override val initialResult: TResult<Data?>? = null
                override val schema: OperationSchema get() = Stream.Schema
                override fun <V : Data> getField(reference: String): V = assertNotReached()
                override fun <V : Data> getOptionalField(reference: String): V? = null
                override fun stream(signalOnly: Boolean): RequestObject = this

                // ⚠ Registers and RETURNS — the confirmation is [confirmSubscribe]'s, later.
                override suspend fun registerCallback(callback: StreamCallback<TString>) {
                    callbacks.add(callback)
                }

                override suspend fun endStream(callback: StreamCallback<TString>) {
                    callbacks.remove(callback)
                }

                override val context: TContext get() = this@ObservedList.context
                override val requestChain: RequestChain get() = this@ObservedList.requestChain
            },
        )
    }

    private val resolver = object : OperationResolver {
        @Suppress("UNCHECKED_CAST")
        override fun <O : Operation> findOperation(
            operationId: String,
            requestChain: RequestChain,
            context: TContext,
        ): O? = operations[operationId] as O?
    }

    override val components: Map<String, TComponent>
        get() = context.components + mapOf(OperationResolver.COMPONENT_ID to resolver)

    override fun getElement(index: Int): TString = provider()[index].toTString(context)
    override fun <V : Data> getField(reference: String): V = assertNotReached()
    override fun <V : Data> getOptionalField(reference: String): V? = null
}
