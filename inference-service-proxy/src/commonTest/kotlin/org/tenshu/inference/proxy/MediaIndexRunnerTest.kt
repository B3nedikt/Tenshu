package org.tenshu.inference.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.BackgroundLiveness
import org.tenshu.core.entity.Entity
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §34's single-flight.
 *
 * ⚠⚠ Two passes over one library is reachable by construction, not by bad luck: the schedule is
 * dispatched by **two** drivers (`MediaIndexSchedule` — the in-process `ScheduleDrainLoop`, and on
 * Android WorkManager waking a closed Control), and *Index now* adds a third entry point that a user
 * can press while either is working. Nothing deduped them before this class.
 */
class MediaIndexRunnerTest {

    private val thisDevice = "device-a"
    private val capability = InferenceCapability.SEMANTIC_IMAGE_SEARCH

    private fun proxy(): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
        },
    ).apply {
        write(capability.realmConsent, true)
        write(capability.deviceConsent, true, deviceId = thisDevice)
    }

    private fun <T> TestProxy.write(preference: Preference<T>, value: T, deviceId: String? = null) {
        executionContext.saveData(
            if (deviceId == null) {
                Entity.create<RealmSetting>(
                    context = tContext,
                    composite = RealmSetting.create(
                        context = tContext,
                        preferenceId = preference.id,
                        value = preference.adapter.toData(value, tContext),
                    ),
                )
            } else {
                Entity.create<DeviceSetting>(
                    context = tContext,
                    composite = DeviceSetting.create(
                        context = tContext,
                        deviceId = deviceId,
                        preferenceId = preference.id,
                        value = preference.adapter.toData(value, tContext),
                    ),
                )
            },
        )
    }

    /**
     * The runner's process-lifetime scope, as a test one.
     *
     * ⚠ NOT `backgroundScope`: its coroutines are not dispatched by `advanceUntilIdle`, so the pass
     * body never ran and every assertion here read as "nothing happened" — which is also what a real
     * defect would look like. Unconfined starts the pass on the spot, which is what these tests are
     * about; the gate is what holds it in flight.
     */
    private fun kotlinx.coroutines.test.TestScope.passScope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())

/**
     * A **dispatched** scope, for the claims about what is true *before* a call returns.
     *
     * ⚠⚠ [passScope] cannot see them: Unconfined runs the pass body on the spot, so the indexer's own
     * `progress.start(total)` lands before `start()` returns and the assertion holds whether or not
     * the runner published anything. Standard queues the body instead, which is what a real
     * dispatcher does — and what leaves the holder empty for the whole library walk.
     */
    private fun kotlinx.coroutines.test.TestScope.queuedPassScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + Job())

    /** The holder every runner here publishes into, so the bar's lifecycle is assertable. */
    private val progressHolder = IndexProgressHolder()

    private fun indexer(
        embeddings: EmbeddingStore,
        conditions: IndexingConditions = IndexingConditions { true },
    ) = MediaIndexer(
        registry = InferenceBackendRegistry(
            listOf(FakeInferenceBackend(capabilities = setOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH))),
        ),
        deviceId = { thisDevice },
        embeddings = embeddings,
        conditions = conditions,
        progress = progressHolder,
    )

    /** A source whose bytes block until released — without it no two passes can be in flight at once. */
    private class GatedSource(private val gate: CompletableDeferred<Unit>, private val names: List<String>) : MediaSource {
        var reads = 0

        /**
         * How many times the LIBRARY was enumerated.
         *
         * ⚠⚠ Counted apart from [reads], and the difference is the whole assertion in
         * `aScheduledPassTheDeviceRefusesTakesNoSlotAndWalksNothing`: a refused pass never reads a
         * byte either way — it gives up before the loop — so only the walk distinguishes a gate that
         * runs before the launch from one that runs inside it. In production this is
         * `collectImagePaths`, a recursive read of the whole realm tree.
         */
        var walks = 0

        override suspend fun media(executionContext: org.tenshu.core.common.proxy.ExecutionContext) =
            names.also { walks++ }.map { name ->
                IndexableMedia("/photos/$name") {
                    reads++
                    gate.await()
                    "bytes of $name".encodeToByteArray()
                }
            }
    }

    @Test
    fun aSecondStartJoinsTheRunningPassRatherThanWalkingTheLibraryTwice() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = GatedSource(gate, listOf("a.jpg"))
        val store = InMemoryEmbeddingStore()
        val runner = MediaIndexRunner(indexer(store), source, passScope())
        val proxy = proxy()

        assertTrue(runner.start(proxy.executionContext), "the first press starts a pass")
        advanceUntilIdle()
        assertTrue(runner.isRunning())

        assertFalse(runner.start(proxy.executionContext), "a second press must not start a second pass")
        advanceUntilIdle()

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, source.reads, "one walk, not two")
    }

    /** ⚠ The scheduled path joins a manual pass — it does not start a second one beside it. */
    @Test
    fun theScheduledPassJoinsAManualOneAndReportsItsResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = GatedSource(gate, listOf("a.jpg"))
        val store = InMemoryEmbeddingStore()
        val runner = MediaIndexRunner(indexer(store), source, passScope())
        val proxy = proxy()

        runner.start(proxy.executionContext, manual = true)
        advanceUntilIdle()
        assertTrue(runner.isRunning(), "precondition: the manual pass is still in flight")

        // ⚠ Asked for WHILE the manual pass is running — the whole point. Releasing the gate first
        // would let it finish, and the scheduled call would then start a second pass, which passes
        // the count assertions below for the wrong reason.
        // ⚠⚠ UNDISPATCHED: it must reach `runToCompletion` — and so join — BEFORE the gate opens.
        // Dispatched, it ran after the manual pass had already finished and started a second one,
        // which is the very thing this test exists to catch.
        val scheduled = async(start = CoroutineStart.UNDISPATCHED) {
            runner.runToCompletion(proxy.executionContext)
        }
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, scheduled.await().embedded, "the scheduled caller gets the joined pass's own report")
        assertEquals(1, source.reads, "and the library was walked once")
    }

    /**
     * ⚠⚠ Liveness is what keeps Control alive for a pass nobody is bound to, and it must come back
     * down: a stuck source is a foreground notification that never goes away.
     */
    @Test
    fun aPassHoldsTheProcessAliveWhileItRunsAndReleasesItAfter() = runTest {
        val gate = CompletableDeferred<Unit>()
        val liveness = BackgroundLiveness()
        val runner = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore()),
            GatedSource(gate, listOf("a.jpg")),
            passScope(),
            liveness = liveness,
        )

        runner.start(proxy().executionContext)
        advanceUntilIdle()
        assertEquals(1, liveness.active.value, "a running pass must hold the process up")

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, liveness.active.value, "and let it go when the pass ends")
    }

    /**
     * ⚠⚠ control-background-work-off-fgs.md §5.2: only a MANUAL pass may hold the user-started key
     * Android's foreground service counts. A scheduled pass — which the in-process drain loop runs with
     * a client open — is automatic, and promoting the dataSync service for its hours is what Play rejects.
     */
    /**
     * Neither pass promotes Control's foreground service: Android's *Index now* runs under its own
     * long-running worker (`control-background-work-off-fgs.md` §5.6.1). The manual pass still
     * holds its own key, apart from the scheduled one.
     */
    @Test
    fun neitherPassCountsAsUserStarted_andEachHoldsItsOwnKey() = runTest {
        val liveness = BackgroundLiveness()
        val gate = CompletableDeferred<Unit>()
        val runner = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore()),
            GatedSource(gate, listOf("a.jpg")),
            passScope(),
            liveness = liveness,
        )

        val scheduled = async { runner.runToCompletion(proxy().executionContext) }
        advanceUntilIdle()
        assertEquals(1, liveness.active.value, "precondition: the scheduled pass is running")
        assertEquals(0, liveness.userStartedActive.value, "a scheduled pass must not count as user-started")
        gate.complete(Unit)
        scheduled.await()
        advanceUntilIdle()
        assertEquals(0, liveness.active.value)

        val gate2 = CompletableDeferred<Unit>()
        val manualRunner = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore()),
            GatedSource(gate2, listOf("a.jpg")),
            passScope(),
            liveness = liveness,
        )
        manualRunner.start(proxy().executionContext, manual = true)
        advanceUntilIdle()
        assertEquals(0, liveness.userStartedActive.value, "Index now runs under its own worker, not the FGS")
        assertTrue(BackgroundLiveness.MEDIA_INDEX_PASS in liveness.activeSources.value, "and holds its own key")
        gate2.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, liveness.userStartedActive.value)
    }

    @Test
    fun aPassThatThrowsStillReleasesTheProcess() = runTest {
        val liveness = BackgroundLiveness()
        val exploding = object : MediaSource {
            override suspend fun media(executionContext: org.tenshu.core.common.proxy.ExecutionContext):
                List<IndexableMedia> = error("the library walk failed")
        }
        val runner = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore()),
            exploding,
            passScope(),
            liveness = liveness,
        )

        runner.start(proxy().executionContext)
        advanceUntilIdle()

        assertEquals(0, liveness.active.value, "a thrown pass must not pin the process awake for ever")
        assertFalse(runner.isRunning())
    }

    // ---- §34.5: the pass is observable before the op returns --------------------------------

    /**
     * ⚠⚠ **Without this, the whole visible half of *Index now* is a no-op.** `IndexProgress` is a
     * BOUNDED stream — an empty holder means "no pass is running", so it sends one idle tick and
     * ends. The screen subscribes the instant the op returns, which is while the library walk is
     * still going, so a holder published by the indexer (after the walk) is published too late:
     * every subscriber is told nothing is happening, for the hours the pass then takes.
     */
    @Test
    fun aStartedPassIsPublishedBeforeStartReturns() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runner = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore()),
            GatedSource(gate, listOf("a.jpg")),
            queuedPassScope(),
            progress = progressHolder,
        )

        runner.start(proxy().executionContext)

        // ⚠ NO `advanceUntilIdle` — that is the point. The claim is about what a subscriber sees the
        // moment the call returns, not about what becomes true eventually.
        assertNotNull(
            progressHolder.progress.value,
            "a subscriber arriving now would be told no pass is running, and its stream would END",
        )

        gate.complete(Unit)
        advanceUntilIdle()
    }

    /** ⚠ And the real total replaces the placeholder once the walk is done. */
    @Test
    fun theRealTotalReplacesTheStartingPlaceholder() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runner = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore()),
            GatedSource(gate, listOf("a.jpg", "b.jpg", "c.jpg")),
            queuedPassScope(),
            progress = progressHolder,
        )

        runner.start(proxy().executionContext)
        advanceUntilIdle()

        assertEquals(3, progressHolder.progress.value?.total)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    // ---- §34.5: the bar is cleared however the pass ends -----------------------------------

    /**
     * ⚠⚠ A holder left non-null is a bar that says "indexing" for the life of the process — with
     * *Index now* disabled behind it, because a pass appears to be running. A throw unwinds past
     * `MediaIndexer`'s ordinary `finish()`, and the `async` that carries it is never awaited on the
     * manual path, so nothing anywhere reports the failure either.
     */
    @Test
    fun aPassThatThrowsClearsTheBar() = runTest {
        val exploding = object : MediaSource {
            override suspend fun media(executionContext: org.tenshu.core.common.proxy.ExecutionContext):
                List<IndexableMedia> = error("the library walk failed")
        }
        val runner = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore()),
            exploding,
            passScope(),
            progress = progressHolder,
        )

        runner.start(proxy().executionContext)
        advanceUntilIdle()

        assertNull(progressHolder.progress.value, "a failed pass must not leave a bar running forever")
    }

    /** ⚠ The same, for a throw from INSIDE the walk — past the point the indexer published the total. */
    @Test
    fun aPassThatThrowsMidLibraryClearsTheBar() = runTest {
        val failingBytes = MediaSource {
            listOf(IndexableMedia("/photos/a.jpg") { throw IllegalStateException("disk full") })
        }
        val runner = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore()),
            failingBytes,
            passScope(),
            progress = progressHolder,
        )

        runner.start(proxy().executionContext)
        advanceUntilIdle()

        assertNull(progressHolder.progress.value)
    }

    // ---- §14.19: the standing, the process's slot, the bar before the walk ---------------------

    /** ⚠ One value with the pass it is about: running, then how it ended, numbered per runner. */
    @Test
    fun theStandingSaysWhichPassRunsAndHowEachEnded() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore()), GatedSource(gate, listOf("a.jpg")), passScope(), progress = progressHolder)
        assertEquals(PassStanding.Idle(null), runner.standing.value)

        runner.start(proxy().executionContext)
        assertEquals(PassStanding.Running(1, manual = true), runner.standing.value)
        runner.cancelRunningPass()
        assertEquals(PassStanding.Idle(PassEnding.Stopped(1)), runner.standing.value)

        runner.start(proxy().executionContext)
        assertEquals(PassStanding.Running(2, manual = true), runner.standing.value)
        gate.complete(Unit)
        advanceUntilIdle()
        val ended = runner.standing.value as PassStanding.Idle
        assertEquals(2L, (ended.last as PassEnding.Finished).pass)
        assertNull(progressHolder.progress.value, "the standing ended before the bar was cleared")
    }

    @Test
    fun aPassThatThrowsEndsFailedWithItsNumber() = runTest {
        val exploding = object : MediaSource {
            override suspend fun media(executionContext: org.tenshu.core.common.proxy.ExecutionContext): List<IndexableMedia> = error("walk failed")
        }
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore()), exploding, passScope())
        runner.start(proxy().executionContext)
        advanceUntilIdle()

        val last = (runner.standing.value as PassStanding.Idle).last
        assertTrue(last is PassEnding.Failed && last.pass == 1L, "$last")
    }

    /**
     * ⚠⚠ Two runners sharing one exclusion run one pass at a time (a graph that replaces another
     * in one process brings its own runner): the second waits until the first has let go.
     */
    @Test
    fun twoRunnersSharingTheProcessesSlotNeverRunTwoPasses() = runTest {
        val slot = PassExclusion.shared()
        val firstGate = CompletableDeferred<Unit>()
        val first = GatedSource(firstGate, listOf("a.jpg"))
        val second = GatedSource(CompletableDeferred<Unit>().also { it.complete(Unit) }, listOf("b.jpg"))
        val old = MediaIndexRunner(indexer(InMemoryEmbeddingStore()), first, passScope(), exclusion = slot)
        val successor = MediaIndexRunner(indexer(InMemoryEmbeddingStore()), second, passScope(), exclusion = slot)

        old.start(proxy().executionContext)
        advanceUntilIdle()
        successor.start(proxy().executionContext)
        advanceUntilIdle()
        assertEquals(0, second.walks, "the successor's pass walked the library beside the old one")

        old.cancelRunningPass()
        advanceUntilIdle()
        assertEquals(1, second.walks, "the successor's pass never started once the slot was free")
    }

    /**
     * A source whose FIRST pass blocks inside its first photo's read where no cancel reaches it (a
     * store read, §14.19.9) until [released], and whose later passes block in their walk.
     */
    private class UnwindingSource(private val released: CompletableDeferred<Unit>) : MediaSource {
        var walks = 0

        override suspend fun media(executionContext: org.tenshu.core.common.proxy.ExecutionContext): List<IndexableMedia> {
            walks++
            if (walks > 1) CompletableDeferred<Unit>().await()
            return listOf(
                IndexableMedia("/photos/a.jpg") {
                    withContext(NonCancellable) { released.await() }
                    "bytes of a.jpg".encodeToByteArray()
                },
            )
        }
    }

    /**
     * ⚠⚠ `ios-shared-store-sqlite.md` B.16 (249): on iOS a graph's unload
     * (`AiReadiness.stopServing`) cancels the pass first and claims it, so the teardown's own stop
     * finds nothing to claim. The barrier must still wait until that pass has ended.
     */
    @Test
    fun aStopThatLosesTheRaceToAnotherCancellerStillWaitsForThePassToEnd() = runTest {
        val released = CompletableDeferred<Unit>()
        val source = UnwindingSource(released)
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore()), source, queuedPassScope(), progress = progressHolder)
        runner.start(proxy().executionContext)
        advanceUntilIdle()
        assertEquals(IndexProgressSnapshot(done = 1, total = 1), progressHolder.progress.value, "precondition: the pass is inside its first photo")

        // The unload's cancel wins: it claims the pass and waits for it.
        val unload = queuedPassScope().launch { runner.cancelRunningPass() }
        advanceUntilIdle()
        // The teardown's stop comes second, and finds nothing to claim.
        val barrier = queuedPassScope().launch { runner.stopAndAwaitEnded() }
        advanceUntilIdle()
        assertFalse(barrier.isCompleted, "the barrier ended while the pass was still writing")
        assertFalse(unload.isCompleted)

        released.complete(Unit)
        advanceUntilIdle()
        assertTrue(barrier.isCompleted, "the barrier ends with the pass")
        assertTrue(unload.isCompleted)
    }

    /**
     * `ios-shared-store-sqlite.md` B.16 (252): once a teardown's stop has begun, no pass starts, so
     * the stop never chases one; a `start` between its cancel and its snapshot is refused, as is
     * every later one, and the stop still waits for the pass it found.
     */
    @Test
    fun aStartOnceTheStopHasBegunIsRefused() = runTest {
        val released = CompletableDeferred<Unit>()
        val source = UnwindingSource(released)
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore()), source, queuedPassScope(), progress = progressHolder)
        val context = proxy().executionContext
        runner.start(context)
        advanceUntilIdle()
        var between: Boolean? = null
        runner.afterCancelForTests = { between = runner.start(context) }
        val barrier = queuedPassScope().launch { runner.stopAndAwaitEnded() }
        advanceUntilIdle()
        assertFalse(barrier.isCompleted, "the stop waits for the pass it found")
        assertFalse(runner.start(context), "a start once the stop has begun is refused")
        released.complete(Unit)
        advanceUntilIdle()
        assertTrue(barrier.isCompleted)
        assertEquals(false, between, "a start between the cancel and the snapshot is refused")
        assertFalse(runner.start(context), "and every later start is refused")
        assertEquals(1, source.walks, "no second pass walked the library")
        assertEquals(IndexPassResult(deferred = true), runner.runToCompletion(context, manual = true))
    }

    /**
     * ⚠⚠ iOS: a pass that replaced one still unwinding waits in the slot while the old one's
     * indexer clears the bar in its `finally`. Once the slot is its own, the bar is its again.
     */
    @Test
    fun aPassWaitingInTheSlotForOneStillUnwindingKeepsItsBar() = runTest {
        val released = CompletableDeferred<Unit>()
        val source = UnwindingSource(released)
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore()), source, queuedPassScope(), progress = progressHolder, exclusion = PassExclusion.shared())
        val context = proxy().executionContext
        runner.start(context)
        advanceUntilIdle()
        assertEquals(IndexProgressSnapshot(done = 1, total = 1), progressHolder.progress.value, "precondition: pass 1 is inside its first photo")

        // Leaving Photos: the stop lets go of the runner's lock before it joins, and a press lands.
        val stop = queuedPassScope().launch { runner.cancelRunningPass() }
        advanceUntilIdle()
        assertTrue(runner.start(context))
        advanceUntilIdle()
        assertEquals(PassStanding.Running(2, manual = true), runner.standing.value)

        released.complete(Unit)
        advanceUntilIdle()
        assertTrue(stop.isCompleted, "precondition: pass 1 has unwound")
        assertEquals(2, source.walks, "precondition: pass 2 has the slot and is walking")
        assertEquals(PassStanding.Running(2, manual = true), runner.standing.value)
        assertNotNull(progressHolder.progress.value, "the outgoing pass's unwinding cleared the bar of the pass that replaced it")
    }

    /**
     * ⚠⚠ Every platform: [MediaIndexRunner.cancelRunningPass] lets go of the lock before its join,
     * so a `start` in between runs the next pass beside the one unwinding, and the outgoing pass's
     * `finally` must not clear the new one's bar.
     */
    @Test
    fun anOutgoingPassesFinallyDoesNotClearTheBarOfThePassThatReplacedIt() = runTest {
        val released = CompletableDeferred<Unit>()
        var first = true
        val source = object : MediaSource {
            override suspend fun media(executionContext: org.tenshu.core.common.proxy.ExecutionContext): List<IndexableMedia> {
                // ⚠ A cancellation point: the cancelled pass stops here, before its indexer.
                yield()
                return listOf(IndexableMedia("/photos/a.jpg") { CompletableDeferred<Unit>().await(); ByteArray(0) })
            }
        }
        val runner = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore()),
            source,
            queuedPassScope(),
            progress = progressHolder,
            beforePass = {
                if (first) {
                    first = false
                    withContext(NonCancellable) { released.await() }
                }
                true
            },
        )
        val context = proxy().executionContext
        runner.start(context)
        advanceUntilIdle()

        val stop = queuedPassScope().launch { runner.cancelRunningPass() }
        advanceUntilIdle()
        assertTrue(runner.start(context))
        advanceUntilIdle()
        assertEquals(IndexProgressSnapshot(done = 1, total = 1), progressHolder.progress.value, "precondition: pass 2 is inside its first photo")

        released.complete(Unit)
        advanceUntilIdle()
        assertTrue(stop.isCompleted, "precondition: pass 1 has unwound")
        assertEquals(PassStanding.Running(2, manual = true), runner.standing.value)
        assertEquals(IndexProgressSnapshot(done = 1, total = 1), progressHolder.progress.value, "pass 1's finally cleared pass 2's bar")
    }

    /**
     * ⚠⚠ A pass cancelled before its coroutine was dispatched still ends, with its number, and
     * clears the bar: with the default start its body and `finally` never run.
     */
    @Test
    fun aPassCancelledBeforeItWasDispatchedStillEndsAndClearsTheBar() = runTest {
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore()), GatedSource(CompletableDeferred(), listOf("a.jpg")), queuedPassScope(), progress = progressHolder)
        runner.start(proxy().executionContext)
        // ⚠ Nothing dispatched yet: the pass body has not run.
        runner.cancelRunningPass()
        advanceUntilIdle()

        assertEquals(PassStanding.Idle(PassEnding.Stopped(1)), runner.standing.value)
        assertNull(progressHolder.progress.value, "a pass cancelled before it ran left the bar standing")
    }

    /**
     * A pass cancelled before it was dispatched raises nothing. Raised before `ensureActive`, the
     * liveness would go 0 → 1 → 0 for a pass that never ran: on Android a foreground-service start
     * for nothing.
     */
    @Test
    fun aPassCancelledBeforeItWasDispatchedNeverRaisesTheLiveness() = runTest {
        val liveness = BackgroundLiveness()
        val seen = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { liveness.active.collect { seen += it } }
        val runner = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore()),
            GatedSource(CompletableDeferred(), listOf("a.jpg")),
            queuedPassScope(),
            liveness = liveness,
            progress = progressHolder,
        )
        runner.start(proxy().executionContext)
        runner.cancelRunningPass()
        advanceUntilIdle()

        assertEquals(PassStanding.Idle(PassEnding.Stopped(1)), runner.standing.value)
        assertEquals(listOf(0), seen, "a pass that never ran raised the liveness")
    }

    /**
     * The driver over the **real** runner's endings, not a fake's: a pass that ends, and a pass
     * stopped by leaving the foreground before it was dispatched.
     */
    @Test
    fun theForegroundDriverReadsTheRealRunnersEndings() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore()), GatedSource(gate, listOf("a.jpg")), queuedPassScope(), progress = progressHolder)
        val context = proxy().executionContext
        val driver = ForegroundIndexing(
            // ⚠ Unconfined: the stop on leaving runs at once, before the queued pass is dispatched.
            scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()),
            conditions = ForegroundIndexingConditions { DeviceIndexingState(true, ThermalState.NOMINAL, false, null, null) },
            changes = kotlinx.coroutines.flow.emptyFlow(),
            memoryWarnings = kotlinx.coroutines.flow.emptyFlow(),
            runnerStanding = { runner.standing },
            startPass = {
                runner.start(context)
                StartAnswer.Started
            },
            cancelPass = { runner.cancelRunningPass() },
            closeSessions = {},
            clock = { 0L },
        ).also { it.start() }

        assertEquals(IndexNowAnswer.Started, driver.indexNow())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ForegroundIndexingState.NotAsked, driver.standing.value.state, "the real ending did not end the ask")

        assertEquals(IndexNowAnswer.Started, driver.indexNow())
        driver.leftForeground()
        advanceUntilIdle()
        assertEquals(ForegroundIndexingState.Stopped(ForegroundIndexingState.Stopped.Why.LEFT_PHOTOS), driver.standing.value.state)
        assertEquals(PassStanding.Idle(PassEnding.Stopped(2)), runner.standing.value)
        assertNull(progressHolder.progress.value)
    }

    /** ⚠⚠ A pass that begins after the device stopped allowing it reads nothing of the library. */
    @Test
    fun aPassTheBarRefusesByTheTimeItBeginsWalksNothing() = runTest {
        var allowed = true
        val source = GatedSource(CompletableDeferred(), listOf("a.jpg"))
        val runner = MediaIndexRunner(
            indexer(
                InMemoryEmbeddingStore(),
                conditions = object : IndexingConditions {
                    override fun allowIndexing(): Boolean = false
                    override fun allowManualIndexing(): Boolean = allowed
                },
            ),
            source,
            queuedPassScope(),
        )
        runner.start(proxy().executionContext)
        allowed = false
        advanceUntilIdle()

        assertEquals(0, source.walks, "the library was walked by a pass the device no longer allowed")
        val last = (runner.standing.value as PassStanding.Idle).last as PassEnding.Finished
        assertTrue(last.result.deferred)
    }

    // ---- §34.3: which pass owns the slot ------------------------------------------------------

    /**
     * ⚠⚠ A scheduled pass the device refuses must not take the single-flight slot. `ScheduleDrainLoop`
     * dispatches without enforcing the charging/idle constraint, so one is dispatched at every
     * startup; if it occupied the slot, a press landing in that window would be told "started" over a
     * pass about to stand down — and it would have walked the whole realm tree first, for nothing.
     */
    @Test
    fun aScheduledPassTheDeviceRefusesTakesNoSlotAndWalksNothing() = runTest {
        val source = GatedSource(CompletableDeferred(), listOf("a.jpg"))
        val runner = MediaIndexRunner(
            indexer(
                InMemoryEmbeddingStore(),
                conditions = object : IndexingConditions {
                    override fun allowIndexing(): Boolean = false
                    override fun allowManualIndexing(): Boolean = true
                },
            ),
            source,
            passScope(),
            progress = progressHolder,
        )

        val result = runner.runToCompletion(proxy().executionContext)
        advanceUntilIdle()

        assertTrue(result.deferred, "a device that is in use defers the scheduled pass")
        assertEquals(0, source.walks, "and does not walk the realm tree to find that out")
        assertFalse(runner.isRunning())
        assertNull(progressHolder.progress.value, "nothing is running, so nothing is published")
        assertTrue(
            runner.start(proxy().executionContext),
            "and a press right afterwards starts a real pass rather than joining a ghost",
        )
    }

    /**
     * ⚠⚠ A press REPLACES a scheduled pass. The two are held to different bars: the scheduled one
     * stands down the moment the device stops being idle — which is the moment someone picks it up to
     * press this — so joining it would report "started" over work about to stop.
     */
    @Test
    fun aPressSupersedesARunningScheduledPass() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = GatedSource(gate, listOf("a.jpg"))
        val runner = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore()),
            source,
            passScope(),
            progress = progressHolder,
        )
        val proxy = proxy()

        val scheduled = async(start = CoroutineStart.UNDISPATCHED) {
            runner.runToCompletion(proxy.executionContext)
        }
        advanceUntilIdle()
        assertTrue(runner.isRunning(), "precondition: a scheduled pass holds the slot")

        assertTrue(
            runner.start(proxy.executionContext, manual = true),
            "a press must start its own pass rather than joining a scheduled one",
        )
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, source.reads, "the scheduled walk and the pressed one")
        // ⚠ The superseded caller is not handed a failure: the work IS happening, under the press.
        assertTrue(scheduled.await().deferred)
    }

    /** ⚠ But a scheduled pass still JOINS a manual one — superseding runs one way only. */
    @Test
    fun aScheduledPassStillJoinsARunningManualOne() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = GatedSource(gate, listOf("a.jpg"))
        val runner = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore()),
            source,
            passScope(),
            progress = progressHolder,
        )
        val proxy = proxy()

        runner.start(proxy.executionContext, manual = true)
        advanceUntilIdle()
        val scheduled = async(start = CoroutineStart.UNDISPATCHED) {
            runner.runToCompletion(proxy.executionContext)
        }
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, scheduled.await().embedded, "the joined pass's own report")
        assertEquals(1, source.reads, "one walk")
    }

    // ---- §34.6: a wipe stops the pass before it clears ---------------------------------------

    /**
     * ⚠⚠ **The half of the realm-reset fix that `clear()` alone cannot do.** A pass suspended in the
     * model holds a list of paths from the realm being wiped. Left running it resumes after the
     * clear, finds an unstamped store, writes a fresh header and repopulates it with the OLD realm's
     * vectors — a search in the new realm answering with photographs from the realm the user left.
     */
    @Test
    fun aWipedStoreIsNotRepopulatedByThePassThatWasRunning() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = InMemoryEmbeddingStore()
        val runner = MediaIndexRunner(
            indexer(store),
            GatedSource(gate, listOf("a.jpg")),
            passScope(),
            progress = progressHolder,
        )

        runner.start(proxy().executionContext)
        advanceUntilIdle()

        // What the wipe hook does, in order.
        runner.cancelRunningPass()
        store.clear()

        // The old pass's bytes arrive after the wipe — the window the cancellation closes.
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, store.size(), "the old realm's vectors must not come back")
        assertNull(store.provenance(), "nor a stamp that says the store belongs to a model")
        assertFalse(runner.isRunning())
        assertNull(progressHolder.progress.value)
    }

    /**
     * The runner reports every finished pass — manual *Index now* included, which is the pass a
     * user actually watches — so a screen can say "indexed N of M" after the progress stream has
     * gone quiet.
     */
    @Test
    fun aManualPassReportsWhatItDidToItsListener() = runTest {
        val source = object : MediaSource {
            override suspend fun media(executionContext: org.tenshu.core.common.proxy.ExecutionContext) = listOf(
                IndexableMedia("/photos/here.jpg") { "bytes".encodeToByteArray() },
                // ⚠ A photograph whose bytes are not on this device.
                IndexableMedia("/photos/remote.jpg") { null },
            )
        }
        val reported = mutableListOf<IndexPassResult>()
        val runner = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore()), source, passScope(), onPassFinished = { reported += it },
        )
        val proxy = proxy()

        runner.start(proxy.executionContext, manual = true)
        advanceUntilIdle()

        val result = reported.single()
        assertEquals(2, result.imagesVisited)
        assertEquals(1, result.notOnDevice)
    }

    // ─── the pass-start gate (`ios-on-device-inference.md` §3.1) ────────────────────────────────

    @Test
    fun aPassTheGateRefusesWalksNothingAndWritesNothing() = runTest {
        val source = GatedSource(CompletableDeferred(Unit), listOf("a.jpg"))
        val store = InMemoryEmbeddingStore()
        var asked = 0
        val runner = MediaIndexRunner(indexer(store), source, passScope(), beforePass = { asked++; false })

        val result = runner.runToCompletion(proxy().executionContext, manual = true)

        assertTrue(result.deferred)
        assertEquals(1, asked)
        assertEquals(0, source.walks, "the library is not even enumerated")
        assertEquals(0, store.size())
    }

    @Test
    fun theGateIsAskedAtTheStartOfEveryPass() = runTest {
        val source = GatedSource(CompletableDeferred(Unit), listOf("a.jpg"))
        var asked = 0
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore()), source, passScope(), beforePass = { asked++; true })
        val proxy = proxy()

        runner.runToCompletion(proxy.executionContext, manual = true)
        runner.runToCompletion(proxy.executionContext, manual = true)

        assertEquals(2, asked)
        assertEquals(2, source.walks)
    }

    /** The gate as the iOS owner binds it, reached by a pass before any launch check has run. */
    @Test
    fun aPassNeverStartsOverVectorsAnotherArchiveMade() = runTest {
        val store = InMemoryEmbeddingStore()
        val files = org.tenshu.core.common.proxy.InMemoryFileDatabase()
        fun fingerprint(vision: String) = RuntimeFingerprint(vision, "t1", "CPU_AND_NEURAL_ENGINE", "24A434", 1)
        fun custody(vision: String) = InferenceStoreCustody(
            files = files,
            embeddings = store,
            faces = InMemoryFaceTemplateStore(),
            cancelPass = { error("a pass must not cancel itself") },
            currentRealm = { InferenceRealm("owner-a") },
            currentFingerprint = { fingerprint(vision) },
        )
        assertTrue(custody("v1").ensureCurrentRealm(fromPass = true))
        assertTrue(store.put("/photos/old.jpg", floatArrayOf(1f, 0f), org.tenshu.inference.ModelProvenance("other", 1)))

        var heldAtTheWalk = -1
        val source = object : MediaSource {
            override suspend fun media(executionContext: org.tenshu.core.common.proxy.ExecutionContext): List<IndexableMedia> {
                heldAtTheWalk = store.size()
                return emptyList()
            }
        }
        val next = custody("v2")
        val runner = MediaIndexRunner(indexer(store), source, passScope(), beforePass = { next.open(fromPass = true) })

        runner.runToCompletion(proxy().executionContext, manual = true)

        assertEquals(0, heldAtTheWalk, "the pass walked the library over vectors another archive made")
    }

    // ─── a refused pass still honours a withdrawal ──────────────────────────────────────────────

    private suspend fun templates(): FaceTemplateStore = InMemoryFaceTemplateStore().apply {
        put("/photos/a.jpg", listOf(StoredFace(FaceBox(0f, 0f, 0.1f, 0.1f), floatArrayOf(1f, 0f))), org.tenshu.inference.ModelProvenance.Fake)
    }

    private fun refusedRunner(faces: FaceTemplateStore, source: MediaSource) = MediaIndexRunner(
        MediaIndexer(
            registry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
            deviceId = { thisDevice },
            embeddings = InMemoryEmbeddingStore(),
            faces = faces,
            conditions = IndexingConditions { true },
        ),
        source,
        kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher() + Job()),
        beforePass = { false },
    )

    /**
     * ⚠⚠ The gate decides whether the stores may be read and extended, never whether a withdrawn
     * user's templates are deleted. The pass is the other place that deletion runs, and it stands
     * behind the same gate as `faceGroups`.
     */
    @Test
    fun aPassTheGateRefusesStillDeletesTheTemplatesOfAWithdrawnUser() = runTest {
        val faces = templates()
        val source = GatedSource(CompletableDeferred(Unit), listOf("a.jpg"))
        val proxy = proxy().apply { write(InferenceCapability.FACE_CLUSTERING.realmConsent, false) }

        val result = refusedRunner(faces, source).runToCompletion(proxy.executionContext, manual = true)

        assertTrue(result.deferred)
        assertEquals(0, source.walks, "a refused pass indexed")
        assertEquals(0, faces.size(), "a closed gate kept a withdrawn user's templates")
    }

    /** ⚠ The bar's refusal before the walk deletes what a withdrawal owes, as the gate's does. */
    @Test
    fun aPassTheBarRefusesBeforeTheWalkStillDeletesTheTemplatesOfAWithdrawnUser() = runTest {
        val faces = templates()
        val source = GatedSource(CompletableDeferred(Unit), listOf("a.jpg"))
        val proxy = proxy().apply { write(InferenceCapability.FACE_CLUSTERING.realmConsent, false) }
        val runner = MediaIndexRunner(
            MediaIndexer(
                registry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
                deviceId = { thisDevice },
                embeddings = InMemoryEmbeddingStore(),
                faces = faces,
                conditions = object : IndexingConditions {
                    override fun allowIndexing(): Boolean = false
                    override fun allowManualIndexing(): Boolean = false
                },
            ),
            source,
            passScope(),
        )

        runner.start(proxy.executionContext, manual = true)
        advanceUntilIdle()

        assertEquals(0, source.walks)
        assertEquals(0, faces.size(), "a pass the bar refused kept a withdrawn user's templates")
    }

    @Test
    fun aPassTheGateRefusesDeletesNothingWithoutAWithdrawal() = runTest {
        val faces = templates()
        val source = GatedSource(CompletableDeferred(Unit), listOf("a.jpg"))
        val proxy = proxy().apply {
            write(InferenceCapability.FACE_CLUSTERING.realmConsent, true)
            write(InferenceCapability.FACE_CLUSTERING.deviceConsent, true, deviceId = thisDevice)
        }

        refusedRunner(faces, source).runToCompletion(proxy.executionContext, manual = true)

        assertEquals(1, faces.size(), "a closed gate is not a withdrawal")
    }

    /**
     * `control-background-work-off-fgs.md` §5.6.1: *Index now* runs under Android's long-running
     * worker, which awaits the pass. Stopping the worker — the notification's Stop, the OS, the
     * 6-hour type limit — must end the pass, or it runs on with no notification and no job.
     */
    @Test
    fun aManualPassEndsWhenItsWorkerIsStopped() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore()), GatedSource(gate, listOf("a.jpg")), passScope())
        val worker = launch { runner.runManualToCompletion(proxy().executionContext) }
        advanceUntilIdle()
        assertTrue(runner.isRunning(), "precondition: the manual pass is running")

        worker.cancel()          // Stop
        worker.join()
        advanceUntilIdle()

        assertFalse(runner.isRunning(), "Stop ends the pass")
    }

    /** And it REPLACES a scheduled pass, as a press does: the scheduled bar would stop it any moment. */
    @Test
    fun aManualPassReplacesARunningScheduledOne() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = GatedSource(gate, listOf("a.jpg"))
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore()), source, passScope())
        val proxy = proxy()
        val scheduled = async(start = CoroutineStart.UNDISPATCHED) { runner.runToCompletion(proxy.executionContext) }
        advanceUntilIdle()

        val manual = async { runner.runManualToCompletion(proxy.executionContext) }
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(scheduled.await().deferred, "the scheduled pass stood down")
        assertFalse(manual.await().deferred, "the manual pass ran")
        assertEquals(2, source.walks, "two walks: the replaced pass and the manual one")
    }

    // ---- the terminal outcome, and the continued pass over the real runner (§15) -------------

    /**
     * ⚠⚠ Every path ends in its own outcome, a throw included: the bar's `null` is the same for
     * all of them, and `onPassFinished` is not reached from a throw.
     */
    @Test
    fun everyPathEndsInItsTerminalOutcome() = runTest {
        val context = proxy().executionContext
        suspend fun MediaIndexRunner.ofItsFirstPass(): PassOutcome = assertNotNull(outcomeOf(1), "no outcome for the pass it started").await()

        val done = MediaIndexRunner(indexer(InMemoryEmbeddingStore()), GatedSource(CompletableDeferred(Unit), listOf("a.jpg")), passScope(), progress = progressHolder)
        done.start(context)
        assertEquals(PassOutcome.COMPLETED, done.ofItsFirstPass())

        val gated = MediaIndexRunner(indexer(InMemoryEmbeddingStore()), GatedSource(CompletableDeferred(Unit), listOf("a.jpg")), passScope(), beforePass = { false })
        gated.start(context)
        assertEquals(PassOutcome.REFUSED, gated.ofItsFirstPass(), "the stores' gate's refusal")

        var allowed = true
        val barred = MediaIndexRunner(
            indexer(
                InMemoryEmbeddingStore(),
                conditions = object : IndexingConditions {
                    override fun allowIndexing(): Boolean = false
                    override fun allowManualIndexing(): Boolean = allowed
                },
            ),
            GatedSource(CompletableDeferred(Unit), listOf("a.jpg")),
            queuedPassScope(),
        )
        barred.start(context)
        allowed = false
        advanceUntilIdle()
        assertEquals(PassOutcome.REFUSED, barred.ofItsFirstPass(), "the bar's refusal")

        val stopped = MediaIndexRunner(indexer(InMemoryEmbeddingStore()), GatedSource(CompletableDeferred(), listOf("a.jpg")), passScope())
        stopped.start(context)
        stopped.cancelRunningPass()
        assertEquals(PassOutcome.CANCELLED, stopped.ofItsFirstPass())

        val neverDispatched = MediaIndexRunner(indexer(InMemoryEmbeddingStore()), GatedSource(CompletableDeferred(Unit), listOf("a.jpg")), queuedPassScope())
        neverDispatched.start(context)
        neverDispatched.cancelRunningPass()
        assertEquals(PassOutcome.CANCELLED, neverDispatched.ofItsFirstPass(), "a pass cancelled before dispatch")

        val walkThrows = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore()),
            object : MediaSource {
                override suspend fun media(executionContext: org.tenshu.core.common.proxy.ExecutionContext): List<IndexableMedia> = error("walk failed")
            },
            passScope(),
        )
        walkThrows.start(context)
        assertEquals(PassOutcome.FAILED, walkThrows.ofItsFirstPass())

        val bytesThrow = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore()),
            MediaSource { listOf(IndexableMedia("/photos/a.jpg") { throw IllegalStateException("disk full") }) },
            passScope(),
        )
        bytesThrow.start(context)
        assertEquals(PassOutcome.FAILED, bytesThrow.ofItsFirstPass(), "a throw mid-library")

        assertNull(done.outcomeOf(2), "an outcome for a pass never started")
    }

    /** A library whose photos each wait for their own gate: what is in flight at an expiry. */
    private class PerPhotoSource(names: List<String>) : MediaSource {
        val gates: List<CompletableDeferred<Unit>> = names.map { CompletableDeferred() }
        val read = mutableListOf<String>()
        private val names = names

        override suspend fun media(executionContext: org.tenshu.core.common.proxy.ExecutionContext) =
            names.mapIndexed { i, name ->
                IndexableMedia("/photos/$name") {
                    read += name
                    gates[i].await()
                    "bytes of $name".encodeToByteArray()
                }
            }
    }

    private class RecordingTask : ContinuedTask {
        val progress = mutableListOf<Pair<Long, Long>>()
        val completions = mutableListOf<Boolean>()
        override fun progress(done: Long, total: Long) {
            progress += done to total
        }

        override fun complete(success: Boolean) {
            completions += success
        }
    }

    /** Photos' wiring, over the real runner and indexer: the bar counts a live task as the foreground. */
    private inner class Continued(val source: MediaSource, scope: CoroutineScope) {
        var scene = true
        val identifiers = mutableListOf<String>()
        val context = proxy().executionContext
        lateinit var bridge: ContinuedIndexing
        lateinit var driver: ForegroundIndexing
        val conditions = ForegroundIndexingConditions {
            DeviceIndexingState(scene || bridge.holdsPass(), ThermalState.NOMINAL, false, null, null)
        }
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore(), conditions), source, scope, progress = progressHolder)

        init {
            bridge = ContinuedIndexing(
                scope = scope,
                scheduler = { ContinuedTaskScheduler { identifiers += it } },
                outcomeOf = runner::outcomeOf,
                progress = { progressHolder.progress },
                settled = { driver.continuationSettled() },
                released = { driver.continuationReleased() },
                identifierPrefix = "app.test.indexnow.",
                newSuffix = { "${identifiers.size}" },
            )
            driver = ForegroundIndexing(
                scope = scope,
                conditions = conditions,
                changes = kotlinx.coroutines.flow.emptyFlow(),
                memoryWarnings = kotlinx.coroutines.flow.emptyFlow(),
                runnerStanding = { runner.standing },
                startPass = {
                    runner.start(context)
                    StartAnswer.Started
                },
                cancelPass = { runner.cancelRunningPass() },
                closeSessions = {},
                clock = { 0L },
                continuation = bridge,
            ).also { it.start() }
        }

        /** *Index now*, its task launched, and Photos left. */
        suspend fun pressedLaunchedAndLeft(task: ContinuedTask) {
            assertEquals(IndexNowAnswer.Started, driver.indexNow())
            bridge.launched(identifiers.single(), task)
            scene = false
            driver.leftForeground()
        }
    }

    /**
     * ⚠⚠ The pass goes on in the background under its task, and an expiry stops it **at the next
     * photo**: the one in flight is abandoned and none after it is read. The task completes with
     * failure once the pass has ended.
     */
    @Test
    fun anExpiredContinuedTaskStopsThePassAtTheNextPhoto() = runTest {
        val source = PerPhotoSource(listOf("a.jpg", "b.jpg", "c.jpg"))
        val continued = Continued(source, passScope())
        val task = RecordingTask()
        continued.pressedLaunchedAndLeft(task)

        source.gates[0].complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a.jpg", "b.jpg"), source.read, "the pass did not go on in the background under its task")
        assertEquals(PassStanding.Running(1, manual = true), continued.runner.standing.value)
        assertEquals(3L, task.progress.last().second, "the task's progress did not follow the pass: ${task.progress}")
        assertTrue(task.progress.last().first >= 1L, "the task's progress did not advance: ${task.progress}")

        continued.bridge.expired(continued.identifiers.single())
        advanceUntilIdle()
        source.gates.forEach { it.complete(Unit) }
        advanceUntilIdle()
        assertEquals(listOf("a.jpg", "b.jpg"), source.read, "a photo was read after the expiry")
        assertEquals(PassStanding.Idle(PassEnding.Stopped(1)), continued.runner.standing.value)
        assertEquals(listOf(false), task.completions)
        assertNull(progressHolder.progress.value)
    }

    @Test
    fun aContinuedPassThatRunsToTheEndCompletesItsTaskWithSuccess() = runTest {
        val source = PerPhotoSource(listOf("a.jpg", "b.jpg"))
        val continued = Continued(source, passScope())
        val task = RecordingTask()
        continued.pressedLaunchedAndLeft(task)
        source.gates.forEach { it.complete(Unit) }
        advanceUntilIdle()
        assertEquals(listOf("a.jpg", "b.jpg"), source.read)
        assertEquals(listOf(true), task.completions)
        assertFalse(continued.bridge.holdsPass())
    }

    /** ⚠⚠ A throwing pass completes its task with failure: the holder's `null` alone could not say so. */
    @Test
    fun aContinuedPassThatThrowsCompletesItsTaskWithFailure() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = MediaSource {
            listOf(
                IndexableMedia("/photos/a.jpg") {
                    gate.await()
                    throw IllegalStateException("disk full")
                },
            )
        }
        val continued = Continued(source, passScope())
        val task = RecordingTask()
        continued.pressedLaunchedAndLeft(task)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue((continued.runner.standing.value as PassStanding.Idle).last is PassEnding.Failed)
        assertEquals(listOf(false), task.completions)
    }
}
