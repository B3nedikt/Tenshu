package org.tenshu.inference.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.ExecutionContext
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PR-6b, new arrivals while Photos is in front (`ios-on-device-inference.md` §17, `inference-service.md`
 * IN-D6a): the trigger on fakes, and over the real runner and indexer.
 */
class ForegroundArrivalsTest {

    private val thisDevice = "device-a"
    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH
    private val faces = InferenceCapability.FACE_CLUSTERING
    private val debounce = 3_000L

    private fun TestScope.queued(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())

    /** The trigger over a fake batch that counts and can be held. */
    private class Front {
        var inFront = true
        var vouches = true
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val standing = kotlinx.coroutines.flow.MutableStateFlow<PassStanding>(PassStanding.Idle(null))
        var batches = 0
        var looks = 0

        /** What each look finds: by default one new photograph per look. */
        var arrive: () -> List<String> = { listOf("/photos/new$looks.jpg") }
        var answer: () -> IndexPassResult? = { IndexPassResult() }
        var hold: CompletableDeferred<Unit>? = null
        var lookHold: CompletableDeferred<Unit>? = null
        var mayRun = true
        var windowsOpen = 0
        var windowsOpened = 0
        var notAsked = 0
    }

    private fun TestScope.arrivals(front: Front, scope: CoroutineScope = queued()): ForegroundArrivals =
        ForegroundArrivals(
            scope = scope,
            inForeground = { front.inFront },
            look = {
                front.looks++
                // ⚠ As the real look: a store read a cancellation does not interrupt.
                front.lookHold?.let { hold -> kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { hold.await() } }
                front.arrive().isNotEmpty()
            },
            runBatch = {
                front.batches++
                front.hold?.await()
                front.answer()?.let { PathsBatch(it, emptyMap()) }
            },
            runnerStanding = { front.standing },
            debounceMs = debounce,
            mayRun = { front.mayRun },
            notAsked = { front.notAsked++ },
            window = object : BackgroundWindow {
                override fun begin(name: String): Any {
                    front.windowsOpen++
                    front.windowsOpened++
                    return name
                }

                override fun end(handle: Any?) {
                    front.windowsOpen--
                }
            },
        ).also {
            it.start()
            it.follow(front.changes, vouches = { front.vouches })
        }

    // ─── The trigger ───────────────────────────────────────────────────────────────────────────

    /** ⚠⚠ A burst of arrivals is ONE batch, [debounce] after the last of them; a later arrival is one more. */
    @Test
    fun aBurstOfArrivalsIsOneBatch() = runTest {
        val front = Front()
        arrivals(front)
        runCurrent()
        repeat(10) {
            front.changes.emit(Unit)
            advanceTimeBy(500)
        }
        assertEquals(0, front.batches, "a batch started inside the burst")
        advanceTimeBy(debounce + 1)
        runCurrent()
        assertEquals(1, front.batches, "a burst was not one batch")
        advanceUntilIdle()
        assertEquals(1, front.batches)

        front.changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(2, front.batches, "an arrival after the batch started none")
    }

    /** ⚠⚠ No trigger behind Photos: an arrival in the background waits for the next foreground. */
    @Test
    fun noBatchStartsInTheBackground() = runTest {
        val front = Front().apply { inFront = false }
        val arrivals = arrivals(front)
        runCurrent()
        repeat(3) { front.changes.emit(Unit) }
        advanceUntilIdle()
        assertEquals(0, front.batches, "a batch started in the background")

        front.inFront = true
        arrivals.enteredForeground()
        advanceUntilIdle()
        assertEquals(1, front.batches, "what arrived behind Photos was not indexed in front")

        // A leave inside the debounce: the batch waiting to start never starts.
        front.changes.emit(Unit)
        advanceTimeBy(debounce / 2)
        front.inFront = false
        arrivals.leftForeground()
        advanceUntilIdle()
        assertEquals(1, front.batches, "a batch armed in front started after the leave")

        // ⚠ The scene already says "not in front" when the debounce fires, its leave not yet handled.
        front.inFront = true
        arrivals.enteredForeground()
        front.changes.emit(Unit)
        advanceTimeBy(debounce / 2)
        front.inFront = false
        advanceUntilIdle()
        assertEquals(1, front.batches, "a batch started with Photos no longer in front")
    }

    /** A fresh process owes one batch to its first foreground; a vouching watch with no change owes none after. */
    @Test
    fun aFreshProcessOwesOneBatchAndAQuietVouchingWatchNoneAfter() = runTest {
        val front = Front()
        val arrivals = arrivals(front)
        advanceUntilIdle()
        assertEquals(1, front.batches, "a fresh process did not look for what arrived while it was not running")

        arrivals.leftForeground()
        arrivals.enteredForeground()
        advanceUntilIdle()
        assertEquals(1, front.batches, "a foreground with no change walked the library")

        // ⚠ A watch that does not vouch saw nothing: anything may have arrived.
        front.vouches = false
        arrivals.leftForeground()
        arrivals.enteredForeground()
        advanceUntilIdle()
        assertEquals(2, front.batches, "a foreground the watch could not vouch for owed nothing")
    }

    /** ⚠⚠ A capped batch ends batching for this foreground — arrivals after it start none — and the next foreground may take one more. */
    @Test
    fun aCappedBatchIsNeverChained() = runTest {
        val front = Front().apply { answer = { IndexPassResult(batchCapReached = true) } }
        val arrivals = arrivals(front)
        advanceUntilIdle()
        assertEquals(1, front.batches)
        repeat(3) {
            front.changes.emit(Unit)
            advanceUntilIdle()
        }
        assertEquals(1, front.batches, "a batch after a capped one, in the same foreground")

        arrivals.leftForeground()
        arrivals.enteredForeground()
        advanceUntilIdle()
        assertEquals(2, front.batches, "the next foreground took no batch for what arrived")
    }

    /** ⚠ A busy runner (a press, a wake) is neither joined nor replaced: the batch is owed, and tried once that pass ends. */
    @Test
    fun aBusyRunnerIsWaitedOutAndTheBatchTriedOnce() = runTest {
        val front = Front()
        front.standing.value = PassStanding.Running(1, manual = true)
        var busy = true
        front.answer = { if (busy) null else IndexPassResult() }
        arrivals(front)
        advanceUntilIdle()
        assertEquals(1, front.batches)
        advanceTimeBy(60_000)
        assertEquals(1, front.batches, "a busy runner was polled")

        busy = false
        front.standing.value = PassStanding.Idle(PassEnding.Finished(1, IndexPassResult()))
        advanceUntilIdle()
        assertEquals(2, front.batches, "the owed batch was not tried once the pass ended")
        advanceTimeBy(60_000)
        assertEquals(2, front.batches)
    }

    /**
     * ⚠⚠ The busy pass ended before the busy answer was handled (the runner already reads Idle): the
     * owed batch is tried once more — nothing else would say Idle again. ⚠ Once: a retired runner,
     * busy from Idle for ever, is not retried in a loop.
     */
    @Test
    fun aBusyAnswerFromAnIdleRunnerIsRetriedOnceAndNeverLooped() = runTest {
        val raced = Front()
        var busy = true
        raced.answer = { if (busy) null.also { busy = false } else IndexPassResult() }
        arrivals(raced)
        advanceUntilIdle()
        assertEquals(2, raced.batches, "the owed batch waited for another change after its pass had ended")

        // ⚠ Bounded time, never `advanceUntilIdle`: a loop must read as a count, not a hang.
        val retired = Front().apply { answer = { null } }
        val scope = queued()
        try {
            arrivals(retired, scope)
            runCurrent()
            advanceTimeBy(60_000)
            assertEquals(2, retired.batches, "a retired runner was retried in a loop")
        } finally {
            // ⚠ A loop left running would keep `runTest` draining it for ever.
            scope.coroutineContext[Job]!!.cancel()
        }
    }

    /**
     * ⚠⚠ No half may run (no consent, no model): the log is not read and nothing is queued — the
     * batch is still asked, so its refusal deletes what a withdrawal owes.
     */
    @Test
    fun noHalfThatMayRunMeansTheLogIsNeverRead() = runTest {
        val front = Front().apply { mayRun = false }
        arrivals(front)
        advanceUntilIdle()
        front.changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(0, front.looks, "the log was read with no half that may run")
        assertEquals(2, front.batches, "the refusal (and what a withdrawal owes) was skipped")
        assertEquals(2, front.notAsked, "the log's end was not marked for when consent comes")

        val library = Library(names(3))
        val unconsented = Graph(library, queued(), context = proxy(emptyList()).executionContext)
        unconsented.follow()
        advanceUntilIdle()
        assertEquals(0, unconsented.looks, "a graph with no consent read the log")
        assertEquals(emptyList(), unconsented.queue.paths(), "a graph with no consent queued paths")
    }

    /**
     * ⚠⚠ The look is a store read a cancellation cannot interrupt: a leave mid-look leaves the batch's
     * background window open until the look has returned (iOS: no `0xdead10cc`), and ends it then.
     */
    @Test
    fun aLeaveMidLookHoldsTheWindowUntilTheLookEnds() = runTest {
        val front = Front().apply { lookHold = CompletableDeferred() }
        val arrivals = arrivals(front)
        advanceUntilIdle()
        assertEquals(1, front.looks)
        assertEquals(1, front.windowsOpen, "a batch ran with no background window")

        front.inFront = false
        arrivals.leftForeground()
        advanceUntilIdle()
        assertEquals(1, front.windowsOpen, "the window ended while the look was still reading")

        front.lookHold!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, front.windowsOpen, "the window outlived the batch")
        assertEquals(0, front.batches, "the batch ran its pass after the leave")
        assertEquals(1, front.windowsOpened)
    }

    /**
     * ⚠⚠ A batch that THREW is retried at the next foreground — never at once, never in a loop — even
     * with a vouching watch and no further change (`owed` had been taken at the fire).
     */
    @Test
    fun aThrowingBatchIsRetriedAtTheNextForegroundAndNotAtOnce() = runTest {
        val front = Front()
        var throwing = true
        front.answer = { if (throwing) error("the store is gone") else IndexPassResult() }
        val arrivals = arrivals(front)
        advanceUntilIdle()
        advanceTimeBy(60_000)
        assertEquals(1, front.batches, "a throwing batch was retried at once")

        throwing = false
        front.inFront = false
        arrivals.leftForeground()
        front.inFront = true
        arrivals.enteredForeground()
        advanceUntilIdle()
        assertEquals(2, front.batches, "the failed work was not retried at the next foreground")
        arrivals.leftForeground()
        arrivals.enteredForeground()
        advanceUntilIdle()
        assertEquals(2, front.batches, "a quiet vouching watch retried work that had succeeded")
    }

    /** ⚠ A look that finds nothing arrived runs no pass at all. */
    @Test
    fun aLookThatFindsNoArrivalRunsNoPass() = runTest {
        val front = Front().apply { arrive = { emptyList() } }
        arrivals(front)
        advanceUntilIdle()
        front.changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(2, front.looks)
        assertEquals(0, front.batches, "a pass ran with nothing arrived")
    }

    /** A batch the bar refused is owed to the next change, never retried in a loop. */
    @Test
    fun aDeferredBatchWaitsForTheNextChange() = runTest {
        val front = Front().apply { answer = { IndexPassResult(deferred = true) } }
        arrivals(front)
        advanceUntilIdle()
        advanceTimeBy(60_000)
        assertEquals(1, front.batches, "a refused batch was retried by itself")
        front.changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(2, front.batches)
    }

    // ─── Over the real runner and indexer ──────────────────────────────────────────────────────

    private fun proxy(consented: List<InferenceCapability> = listOf(search)): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
        },
    ).apply {
        consented.forEach { capability ->
            write(capability.realmConsent, true)
            write(capability.deviceConsent, true, deviceId = thisDevice)
        }
    }

    private fun <T> TestProxy.write(preference: Preference<T>, value: T, deviceId: String? = null) {
        executionContext.saveData(
            if (deviceId == null) {
                Entity.create<RealmSetting>(
                    context = tContext,
                    composite = RealmSetting.create(context = tContext, preferenceId = preference.id, value = preference.adapter.toData(value, tContext)),
                )
            } else {
                Entity.create<DeviceSetting>(
                    context = tContext,
                    composite = DeviceSetting.create(context = tContext, deviceId = deviceId, preferenceId = preference.id, value = preference.adapter.toData(value, tContext)),
                )
            },
        )
    }

    /** A library whose photos may each wait for a gate, counting its walks and its reads. */
    private class Library(val names: List<String>, gated: Boolean = false) : MediaSource {
        val gates: List<CompletableDeferred<Unit>> = names.map { CompletableDeferred<Unit>().also { gate -> if (!gated) gate.complete(Unit) } }
        val read = mutableListOf<String>()
        var walks = 0

        /** Every path a batch asked about by path. */
        val askedAt = mutableListOf<String>()

        /** Names whose bytes (and preview) are not on this device. */
        val missing = mutableSetOf<String>()

        private fun item(i: Int, name: String) = IndexableMedia("/photos/$name") {
            read += name
            gates[i].await()
            if (name in missing) null else name.encodeToByteArray()
        }

        override suspend fun media(executionContext: ExecutionContext): List<IndexableMedia> {
            walks++
            return names.mapIndexed(::item)
        }

        override suspend fun mediaAt(executionContext: ExecutionContext, paths: Collection<String>): List<IndexableMedia> {
            askedAt += paths
            val index = names.withIndex().associate { (i, name) -> "/photos/$name" to i }
            return paths.mapNotNull { path -> index[path]?.let { item(it, names[it]) } }
        }
    }

    /** Photos' wiring over the real runner: the arrivals bar, the batch, the trigger. */
    private inner class Graph(
        val library: Library,
        val scope: CoroutineScope,
        val context: ExecutionContext = proxy().executionContext,
        backends: List<InferenceBackend> = listOf(FakeInferenceBackend(capabilities = setOf(search))),
        withFaces: Boolean = false,
    ) {
        var phone = DeviceIndexingState(foreground = true, thermal = ThermalState.NOMINAL, lowPowerMode = false, batteryLevel = 0.8f, charging = false)
        var inWake = false
        val holder = IndexProgressHolder()
        val embeddings = InMemoryEmbeddingStore()
        val faceStore: InMemoryFaceTemplateStore? = if (withFaces) InMemoryFaceTemplateStore() else null
        val conditions = ForegroundIndexingConditions(read = { phone }, inWake = { inWake })
        val indexer = MediaIndexer(
            registry = InferenceBackendRegistry(backends),
            deviceId = { thisDevice },
            embeddings = embeddings,
            faces = faceStore,
            conditions = conditions,
            progress = holder,
        )
        val runner = MediaIndexRunner(
            indexer,
            library,
            scope,
            progress = holder,
        )
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

        /** What the next look finds arrived; taken by it into [queue]. */
        val arrived = mutableListOf<String>()
        val queue = ArrivalQueue(files = null, path = "/inference/arrivals.queue")
        var looks = 0
        val arrivals = ForegroundArrivals(
            scope = scope,
            inForeground = { phone.foreground },
            look = {
                looks++
                arrived.forEach(queue::add)
                arrived.clear()
                queue.size > 0
            },
            runBatch = { runner.runArrivalsBatch(context, queue, NEW_ARRIVALS_BATCH_CAP) },
            runnerStanding = { runner.standing },
            debounceMs = debounce,
            mayRun = { indexer.anyHalfMayRun(context) },
        ).also { it.start() }

        /** The watch followed, with every photograph of [library] arrived (what a fresh library is). */
        fun follow(arriving: List<String> = library.names) {
            arrive(*arriving.toTypedArray())
            arrivals.follow(changes, vouches = { true })
        }

        fun arrive(vararg names: String) {
            arrived += names.map { "/photos/$it" }
        }

        fun leave() {
            phone = phone.copy(foreground = false)
            arrivals.leftForeground()
        }

        fun enter() {
            phone = phone.copy(foreground = true)
            arrivals.enteredForeground()
        }
    }

    private fun names(count: Int, from: Int = 0): List<String> = (from until from + count).map { "p$it.jpg" }

    /**
     * ⚠⚠ The cap: a batch over 120 arrived photos indexes exactly [NEW_ARRIVALS_BATCH_CAP], says it
     * stopped there, keeps the other 70 queued, and is not chained — an arrival in the same
     * foreground starts nothing; the next foreground takes one more batch of the cap, and no more.
     */
    @Test
    fun aBatchIndexesTheCapAndIsNeverChained() = runTest {
        val library = Library(names(120))
        val graph = Graph(library, queued())
        graph.follow()
        advanceUntilIdle()
        assertEquals(NEW_ARRIVALS_BATCH_CAP, graph.embeddings.size(), "the batch did not stop at the cap")
        val ended = assertIs<PassEnding.Finished>(assertIs<PassStanding.Idle>(graph.runner.standing.value).last)
        assertTrue(ended.result.batchCapReached, "the capped batch did not say so")
        assertEquals(1, graph.arrivals.batchesStarted)
        assertEquals(120 - NEW_ARRIVALS_BATCH_CAP, graph.queue.paths().size, "what the cap left was not kept")
        assertEquals(0, library.walks, "a batch walked the library")

        graph.changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(1, graph.arrivals.batchesStarted, "a second batch was chained in the same foreground")
        assertEquals(NEW_ARRIVALS_BATCH_CAP, graph.embeddings.size())

        graph.leave()
        graph.enter()
        advanceUntilIdle()
        assertEquals(2, graph.arrivals.batchesStarted)
        assertEquals(2 * NEW_ARRIVALS_BATCH_CAP, graph.embeddings.size(), "the next foreground's batch was not one cap")
    }

    /** A batch that finds no more than the cap indexes them all, and is a complete pass. */
    @Test
    fun aSmallBatchIndexesEveryNewPhoto() = runTest {
        val library = Library(names(3))
        val graph = Graph(library, queued())
        graph.follow()
        advanceUntilIdle()
        assertEquals(3, graph.embeddings.size())
        val ended = assertIs<PassEnding.Finished>(assertIs<PassStanding.Idle>(graph.runner.standing.value).last)
        assertFalse(ended.result.batchCapReached)
        assertEquals(PassOutcome.COMPLETED, passOutcomeOf(ended))
    }

    /** ⚠⚠ Absence is NO: with no consent, and with no model, no pass runs and the library is not even walked. */
    @Test
    fun noConsentOrNoModelMeansNoPassAndNoWalk() = runTest {
        val noConsent = Library(names(3))
        val unconsented = Graph(noConsent, queued(), context = proxy(emptyList()).executionContext)
        unconsented.follow()
        advanceUntilIdle()
        assertEquals(1, unconsented.arrivals.batchesStarted)
        assertEquals(0, noConsent.walks, "a batch with no consent walked the library")
        assertEquals(emptyList(), noConsent.askedAt, "a batch with no consent read a photograph")
        assertEquals(PassStanding.Idle(null), unconsented.runner.standing.value, "a batch with no consent ran a pass")

        val noModel = Library(names(3))
        val unserved = Graph(noModel, queued(), backends = emptyList())
        unserved.follow()
        advanceUntilIdle()
        assertEquals(0, noModel.walks, "a batch with no model walked the library")
        assertEquals(PassStanding.Idle(null), unserved.runner.standing.value, "a batch with no model ran a pass")
    }

    /** ⚠⚠ The bar: behind Photos, too warm, Low Power Mode, a low or an UNKNOWN battery — no walk. */
    @Test
    fun theArrivalsBarIsIndexNowsWithAnUnknownBatteryANo() = runTest {
        val front = DeviceIndexingState(foreground = true, thermal = ThermalState.NOMINAL, lowPowerMode = false, batteryLevel = 0.8f, charging = false)
        assertTrue(arrivalIndexingAllowed(front))
        assertTrue(arrivalIndexingAllowed(front.copy(batteryLevel = null, charging = true)), "charging, level unknown")
        assertFalse(arrivalIndexingAllowed(front.copy(foreground = false)), "behind Photos")
        assertFalse(arrivalIndexingAllowed(front.copy(thermal = ThermalState.SERIOUS)), "too warm")
        assertFalse(arrivalIndexingAllowed(front.copy(lowPowerMode = true)), "Low Power Mode")
        assertFalse(arrivalIndexingAllowed(front.copy(batteryLevel = 0.1f)), "battery low")
        assertFalse(arrivalIndexingAllowed(front.copy(batteryLevel = null, charging = null)), "nobody asked: unknown is no")
        assertTrue(ForegroundIndexingConditions(read = { front.copy(batteryLevel = null, charging = null) }).allowManualIndexing(), "a press is still allowed on an unknown battery")

        val library = Library(names(3))
        val graph = Graph(library, queued())
        graph.phone = graph.phone.copy(thermal = ThermalState.SERIOUS)
        graph.follow()
        advanceUntilIdle()
        assertEquals(0, library.walks, "a warm phone walked the library for a batch")
    }

    /**
     * ⚠⚠ Leaving the foreground stops the running batch at its next photo — the one in flight
     * abandoned, none after it read — and the batch is owed to the next foreground, which resumes
     * from the store. While it runs, *Index now*'s bar shows it.
     */
    @Test
    fun leavingTheForegroundStopsTheBatchAtItsNextPhoto() = runTest {
        val library = Library(names(3), gated = true)
        val graph = Graph(library, queued())
        graph.follow()
        advanceTimeBy(debounce + 1)
        runCurrent()
        assertEquals(listOf("p0.jpg"), library.read)
        assertIs<PassStanding.Running>(graph.runner.standing.value)
        assertNotNull(graph.holder.progress.value, "a batch ran with nothing on Index now's surface")

        graph.arrivals.leftForeground()
        advanceUntilIdle()
        library.gates.forEach { it.complete(Unit) }
        advanceUntilIdle()
        assertEquals(listOf("p0.jpg"), library.read, "a photo was read after the leave")
        assertEquals(PassStanding.Idle(PassEnding.Stopped(1)), graph.runner.standing.value, "the leave did not stop the batch")
        assertNull(graph.holder.progress.value)
        assertEquals(0, graph.embeddings.size())

        graph.enter()
        advanceUntilIdle()
        assertEquals(3, graph.embeddings.size(), "the next foreground did not take what the leave stopped")
    }

    /**
     * ⚠⚠ Single flight with *Index now*: a batch never joins nor replaces a press's pass (it is owed
     * and runs once that pass ends), and a press during a batch replaces the batch.
     */
    @Test
    fun aBatchAndIndexNowAreOnePassAtATime() = runTest {
        val library = Library(names(2), gated = true)
        val graph = Graph(library, queued())
        assertTrue(graph.runner.start(graph.context, manual = true))
        runCurrent()
        assertEquals(PassStanding.Running(1, manual = true), graph.runner.standing.value)

        graph.follow()
        advanceTimeBy(debounce + 1)
        runCurrent()
        assertEquals(1, graph.arrivals.batchesStarted)
        assertEquals(PassStanding.Running(1, manual = true), graph.runner.standing.value, "the batch replaced the press's pass")
        assertEquals(1, library.walks, "the batch walked beside the press's pass")

        library.gates.forEach { it.complete(Unit) }
        advanceUntilIdle()
        assertEquals(2, graph.arrivals.batchesStarted, "the owed batch was not tried once the press's pass ended")
        assertEquals(2, graph.embeddings.size())

        // A press during a batch: the batch is cancelled, the press's pass runs.
        val more = Library(names(2, from = 10), gated = true)
        val second = Graph(more, queued())
        second.follow()
        advanceTimeBy(debounce + 1)
        runCurrent()
        val batch = assertIs<PassStanding.Running>(second.runner.standing.value)
        assertFalse(batch.manual)
        assertTrue(second.runner.start(second.context, manual = true), "a press joined the batch")
        runCurrent()
        assertEquals(PassStanding.Running(batch.pass + 1, manual = true), second.runner.standing.value)
        more.gates.forEach { it.complete(Unit) }
        advanceUntilIdle()
        assertEquals(2, second.embeddings.size())
    }

    /**
     * ⚠⚠ Single flight with the processing wake (§16.3 decision 6, as amended in §17): a batch that
     * meets a wake's pass yields to it, and a wake that meets a batch replaces it.
     */
    @Test
    fun aBatchYieldsToAWakeAndAWakeReplacesABatch() = runTest {
        val library = Library(names(2), gated = true)
        val graph = Graph(library, queued())
        graph.inWake = true
        graph.phone = graph.phone.copy(charging = true)
        val wake = backgroundScope.launch { graph.runner.runToCompletion(graph.context, manual = false) }
        runCurrent()
        assertEquals(PassStanding.Running(1, manual = false), graph.runner.standing.value)
        graph.follow()
        advanceTimeBy(debounce + 1)
        runCurrent()
        assertEquals(PassStanding.Running(1, manual = false), graph.runner.standing.value, "the batch replaced the wake's pass")
        library.gates.forEach { it.complete(Unit) }
        advanceUntilIdle()
        assertTrue(wake.isCompleted)
        assertEquals(2, graph.arrivals.batchesStarted, "the owed batch was not tried once the wake's pass ended")

        val more = Library(names(2, from = 10), gated = true)
        val second = Graph(more, queued())
        second.follow()
        advanceTimeBy(debounce + 1)
        runCurrent()
        val batch = assertIs<PassStanding.Running>(second.runner.standing.value)
        second.inWake = true
        second.phone = second.phone.copy(charging = true)
        backgroundScope.launch { second.runner.runToCompletion(second.context, manual = false) }
        runCurrent()
        assertEquals(PassStanding.Running(batch.pass + 1, manual = false), second.runner.standing.value, "a wake joined the capped batch")
    }

    /**
     * ⚠⚠ The arrival is the one indexed, whatever is behind it: a library of 1,000 photographs no pass
     * has seen, and one upload. The batch reads that one photograph — no walk, no other path asked,
     * no other bytes read — and the backlog stays the wake's.
     */
    @Test
    fun anArrivalBehindABacklogIsIndexedAndNothingElseIsRead() = runTest {
        val library = Library(names(1_000) + "upload.jpg")
        val graph = Graph(library, queued())
        graph.follow(arriving = listOf("upload.jpg"))
        advanceUntilIdle()
        assertEquals(1, graph.arrivals.batchesStarted)
        assertTrue(graph.embeddings.visited("/photos/upload.jpg"), "the arrival was not indexed")
        assertEquals(1, graph.embeddings.size(), "the batch indexed the backlog")
        assertEquals(listOf("upload.jpg"), library.read, "the batch read another photograph")
        assertEquals(listOf("/photos/upload.jpg"), library.askedAt, "the batch asked about another path")
        assertEquals(0, library.walks, "the batch walked the library")
        assertEquals(emptyList(), graph.queue.paths())
    }

    /**
     * ⚠⚠ A photograph whose bytes are not here does not spend the cap (Codex): 50 such arrivals and one
     * that is here — the one is indexed. The 50 stay queued, are tried again by later batches, and are
     * left to the wake after [ARRIVAL_MAX_UNAVAILABLE].
     */
    @Test
    fun unavailablePhotosDoNotSpendTheCapAndAreTriedABoundedNumberOfTimes() = runTest {
        val away = names(NEW_ARRIVALS_BATCH_CAP, from = 100)
        val library = Library(away + "here.jpg").apply { missing += away }
        val graph = Graph(library, queued())
        graph.follow()
        advanceUntilIdle()
        assertTrue(graph.embeddings.visited("/photos/here.jpg"), "photographs not on this device spent the cap")
        val ended = assertIs<PassEnding.Finished>(assertIs<PassStanding.Idle>(graph.runner.standing.value).last)
        assertFalse(ended.result.batchCapReached)
        assertEquals(away.map { "/photos/$it" }.toSet(), graph.queue.paths().toSet(), "the unavailable were not kept")

        repeat(ARRIVAL_MAX_UNAVAILABLE - 1) {
            graph.leave()
            graph.enter()
            graph.changes.emit(Unit)
            advanceUntilIdle()
        }
        assertEquals(emptyList(), graph.queue.paths(), "an unavailable photograph was kept for ever")
        assertEquals(ARRIVAL_MAX_UNAVAILABLE, graph.arrivals.batchesStarted)
    }

    /** ⚠⚠ A refused batch still deletes the templates a withdrawal owes, as a pass's refusals do. */
    @Test
    fun aRefusedBatchStillDeletesWhatAWithdrawalOwes() = runTest {
        val proxy = proxy(listOf(faces))
        val graph = Graph(
            Library(listOf("anna.jpg")),
            queued(),
            context = proxy.executionContext,
            backends = listOf(FakeInferenceBackend(capabilities = setOf(faces))),
            withFaces = true,
        )
        graph.follow()
        advanceUntilIdle()
        val store = assertNotNull(graph.faceStore)
        assertNotNull(store.provenance(), "the first batch wrote no face record")

        proxy.write(faces.deviceConsent, false, deviceId = thisDevice)
        graph.arrive("anna.jpg")
        graph.changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(2, graph.arrivals.batchesStarted)
        assertNull(store.provenance(), "a refused batch left the withdrawn templates")
    }

    /** ⚠⚠ People refreshes after a batch: the runner's face-change counter moves, and its reader emits once. */
    @Test
    fun peopleIsToldToReReadAfterABatch() = runTest {
        val library = Library(listOf("anna.jpg"))
        val graph = Graph(
            library,
            queued(),
            context = proxy(listOf(faces)).executionContext,
            backends = listOf(FakeInferenceBackend(capabilities = setOf(faces))),
            withFaces = true,
        )
        var told = 0
        backgroundScope.launch { faceChangingPasses(graph.runner.faceChanges).collect { told++ } }
        runCurrent()
        graph.follow()
        advanceUntilIdle()
        assertEquals(1, graph.arrivals.batchesStarted)
        assertEquals(1L, graph.runner.faceChanges.value, "a batch that wrote face records did not move the counter")
        assertEquals(1, told, "People was not told to re-read after the batch")
    }
}
