package org.tenshu.inference.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.entity.Entity
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.ModelProvenance
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
 * IN-D6a's batch: the ARRIVED paths through the one runner — never a walk, capped by photographs
 * actually read, never chained, held to its own bar, single-flight with *Index now* and the 12 h pass.
 */
class NewArrivalsBatchTest {

    private val thisDevice = "device-a"
    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH

    private fun proxy(consent: Boolean = true): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
        },
    ).apply {
        if (consent) {
            write(search.realmConsent, true)
            write(search.deviceConsent, true, deviceId = thisDevice)
        }
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

    private fun TestScope.passScope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())

    /** Each bar answered on its own, so a test can tell which one a pass asked. */
    private class Bars(
        var scheduled: Boolean = false,
        var manual: Boolean = true,
        var arrivals: Boolean = true,
    ) : IndexingConditions {
        override fun allowIndexing() = scheduled
        override fun allowManualIndexing() = manual
        override fun allowArrivalIndexing() = arrivals
    }

    private fun indexer(
        store: EmbeddingStore,
        conditions: IndexingConditions,
        backends: List<InferenceBackend> = listOf(FakeInferenceBackend(capabilities = setOf(search))),
        faces: FaceTemplateStore? = null,
    ) = MediaIndexer(
        registry = InferenceBackendRegistry(backends),
        deviceId = { thisDevice },
        embeddings = store,
        faces = faces,
        conditions = conditions,
    )

    /**
     * A library of [names]; the ones in [unavailable] have no bytes and no preview. ⚠ [walks] counts
     * every enumeration of the library and [pointReads] every path a batch asked for by name.
     */
    private class Library(
        private val names: List<String>,
        private val unavailable: Set<String> = emptySet(),
        private val gate: CompletableDeferred<Unit> = CompletableDeferred(Unit),
    ) : MediaSource {
        var walks = 0
        var pointReads = 0
        var byteReads = 0

        private fun item(name: String) = IndexableMedia("/photos/$name") {
            byteReads++
            gate.await()
            if (name in unavailable) null else "bytes of $name".encodeToByteArray()
        }

        override suspend fun media(executionContext: ExecutionContext) = names.also { walks++ }.map(::item)

        override suspend fun mediaAt(executionContext: ExecutionContext, paths: Collection<String>): List<IndexableMedia> {
            pointReads += paths.size
            val known = names.toSet()
            return paths.map { it.removePrefix("/photos/") }.filter { it in known }.map(::item)
        }
    }

    private fun photos(n: Int, prefix: String = "p") = (1..n).map { "$prefix$it.jpg" }

    private fun queueOf(vararg names: String) = ArrivalQueue(InMemoryFileDatabase(), "/q").apply { names.forEach { add("/photos/$it") } }

    // ---- the arrived photograph, and only it ------------------------------------------------------

    @Test
    fun anUploadBehindAThousandPhotoBacklogIsIndexedInTheNextBatch() = runTest {
        val store = InMemoryEmbeddingStore()
        val library = Library(photos(1_000, "old") + "new.jpg")
        val runner = MediaIndexRunner(indexer(store, Bars()), library, passScope())
        val queue = queueOf("new.jpg")

        val batch = runner.runArrivalsBatch(proxy().executionContext, queue, cap = 50)

        assertNotNull(batch)
        assertTrue(store.visited("/photos/new.jpg"), "the photograph that arrived is the one indexed")
        assertEquals(1, batch.result.embedded, "and none of the backlog: that is the scheduled pass's")
        assertEquals(0, queue.size)
    }

    @Test
    fun aBatchReadsOnlyItsPaths_noWalkNoReconcile() = runTest {
        val store = InMemoryEmbeddingStore()
        val library = Library(photos(1_000))
        val runner = MediaIndexRunner(indexer(store, Bars()), library, passScope())

        runner.runArrivalsBatch(proxy().executionContext, queueOf("p7.jpg", "p8.jpg"), cap = 50)

        assertEquals(0, library.walks, "no walk of the library — and so nothing to reconcile against")
        assertEquals(2, library.pointReads, "one point read per queued path")
        assertEquals(2, library.byteReads)
    }

    @Test
    fun aBatchStopsAtItsCap_keepsTheRestQueued_andStartsNoSecond() = runTest {
        val store = InMemoryEmbeddingStore()
        val library = Library(photos(5))
        val runner = MediaIndexRunner(indexer(store, Bars()), library, passScope())
        val queue = queueOf(*photos(5).toTypedArray())

        val batch = runner.runArrivalsBatch(proxy().executionContext, queue, cap = 2)
        advanceUntilIdle()

        assertNotNull(batch)
        assertEquals(2, batch.result.embedded)
        assertTrue(batch.result.batchCapReached)
        assertFalse(batch.result.deferred, "a capped batch is no deferral — nothing retries it")
        assertEquals(listOf("/photos/p3.jpg", "/photos/p4.jpg", "/photos/p5.jpg"), queue.paths(), "the rest wait")
        assertEquals(5, library.pointReads, "one batch, and nothing ran after it")
        assertFalse(runner.isRunning())
    }

    @Test
    fun unavailablePhotographsDoNotSpendTheCap_andLeaveAfterTheirAttempts() = runTest {
        val store = InMemoryEmbeddingStore()
        val gone = setOf("u1.jpg", "u2.jpg", "u3.jpg")
        val library = Library(gone.toList() + listOf("a.jpg", "b.jpg"), unavailable = gone)
        val runner = MediaIndexRunner(indexer(store, Bars()), library, passScope())
        val queue = queueOf("u1.jpg", "u2.jpg", "u3.jpg", "a.jpg", "b.jpg")

        val batch = runner.runArrivalsBatch(proxy().executionContext, queue, cap = 2)

        assertEquals(2, batch?.result?.embedded, "the two that were there, past three that were not")
        assertFalse(batch!!.result.batchCapReached)
        assertEquals(gone.map { "/photos/$it" }, queue.paths(), "kept, for a few more batches")

        repeat(ARRIVAL_MAX_UNAVAILABLE - 1) { runner.runArrivalsBatch(proxy().executionContext, queue, cap = 2) }
        assertEquals(0, queue.size, "then left to the scheduled pass")
    }

    /**
     * ⚠⚠ "No row" is retried, not dropped: a peer's modification row may be applied before its File
     * row. A path that never gets one leaves after the retry limit.
     */
    @Test
    fun aPathWithNoRowIsRetriedBeforeItLeaves() = runTest {
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore(), Bars()), Library(photos(1)), passScope())
        val queue = queueOf("p1.jpg", "not-yet.jpg")

        runner.runArrivalsBatch(proxy().executionContext, queue, cap = 50)
        assertEquals(listOf("/photos/not-yet.jpg"), queue.paths(), "the indexed one left; the rowless one waits")

        repeat(ARRIVAL_MAX_UNAVAILABLE - 1) { runner.runArrivalsBatch(proxy().executionContext, queue, cap = 50) }
        assertEquals(0, queue.size)
    }

    /** A backend whose image embed always fails — a model that will not run. */
    private class FailingEmbeds(private val inner: InferenceBackend = FakeInferenceBackend(capabilities = setOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH))) :
        InferenceBackend by inner {
        override suspend fun embedImageOutcome(bytes: ByteArray): ImageEmbeddingOutcome = ImageEmbeddingOutcome.Failed
    }

    /** ⚠⚠ DONE is reported after the work: a photograph whose embed failed stays queued. */
    @Test
    fun aPhotographWhoseWorkFailedStaysQueued() = runTest {
        val runner = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore(), Bars(), backends = listOf(FailingEmbeds())),
            Library(photos(1)),
            passScope(),
        )
        val queue = queueOf("p1.jpg")

        runner.runArrivalsBatch(proxy().executionContext, queue, cap = 50)

        assertEquals(listOf("/photos/p1.jpg"), queue.paths(), "a failed embed is not done")
    }

    /** Search works; the face detector fails every time — so the face half latches on photo 1. */
    private class FailingDetector(private val inner: InferenceBackend = FakeInferenceBackend()) : InferenceBackend by inner {
        override suspend fun detectFaces(bytes: ByteArray, orientation: Int?): FaceDetectionOutcome = FaceDetectionOutcome.Failed
        override suspend fun detectFaces(bytes: ByteArray): FaceDetectionOutcome = FaceDetectionOutcome.Failed
    }

    /**
     * ⚠⚠ A half latched off after photo 1 leaves photos 2..n half-done: search ran, faces never did.
     * They keep their bounded retries rather than being dropped as done.
     */
    @Test
    fun photographsWhoseFaceHalfWasLatchedOffStayQueuedWithTheirRetries() = runTest {
        val proxy = proxy().apply {
            write(InferenceCapability.FACE_CLUSTERING.realmConsent, true)
            write(InferenceCapability.FACE_CLUSTERING.deviceConsent, true, deviceId = thisDevice)
        }
        val store = InMemoryEmbeddingStore()
        val runner = MediaIndexRunner(
            indexer(store, Bars(), backends = listOf(FailingDetector()), faces = InMemoryFaceTemplateStore()),
            Library(photos(3)),
            passScope(),
        )
        val queue = queueOf(*photos(3).toTypedArray())

        runner.runArrivalsBatch(proxy.executionContext, queue, cap = 50)

        assertEquals(3, store.size(), "precondition: the search half ran on all three")
        assertEquals(photos(3).map { "/photos/$it" }, queue.paths(), "none of them is done: their faces never ran")
        repeat(ARRIVAL_MAX_UNAVAILABLE - 1) { runner.runArrivalsBatch(proxy.executionContext, queue, cap = 50) }
        assertEquals(0, queue.size, "and the retries are bounded")
    }

    /** ⚠⚠ Only a library walk speaks for the library (the Models screen's coverage). */
    @Test
    fun aPathBatchSaysItIsNoLibraryWalk_andTheScheduledPassSaysItIs() = runTest {
        val finished = mutableListOf<IndexPassResult>()
        val runner = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore(), Bars(scheduled = true)),
            Library(photos(3)),
            passScope(),
            onPassFinished = { finished += it },
        )
        runner.runArrivalsBatch(proxy().executionContext, queueOf("p1.jpg"), cap = 50)
        runner.runToCompletion(proxy().executionContext)

        assertEquals(listOf(false, true), finished.map { it.libraryWalk })
    }

    /** ⚠⚠ Every half explicitly switched off: the arrived paths go too. Absence keeps them (above). */
    @Test
    fun aWithdrawalOfEveryHalfEmptiesTheQueue() = runTest {
        val files = InMemoryFileDatabase()
        val queue = ArrivalQueue(files, "/q").apply { add("/photos/p1.jpg"); flush() }
        val proxy = proxy().apply {
            MEDIA_INDEX_CAPABILITIES.forEach { write(it.deviceConsent, false, deviceId = thisDevice) }
        }
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore(), Bars()), Library(photos(1)), passScope())

        runner.runArrivalsBatch(proxy.executionContext, queue, cap = 50)

        assertEquals(0, queue.size)
        assertNull(files.fileSize("/q"), "and its file")
    }

    @Test
    fun aWakeExpiryDoesNotStopAnArrivalsBatch() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore(), Bars()), Library(photos(1), gate = gate), passScope())
        val queue = queueOf("p1.jpg")
        val batch = async(start = CoroutineStart.UNDISPATCHED) { runner.runArrivalsBatch(proxy().executionContext, queue, cap = 50) }

        runner.cancelRunningScheduledPass()
        assertTrue(runner.isRunning(), "the wake's expiry is not the batch's end")
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, batch.await()?.result?.embedded)
    }

    @Test
    fun aRealmChangeAndARelinquishClearTheQueue() = runTest {
        val files = InMemoryFileDatabase()
        var realm: InferenceRealm? = InferenceRealm("owner-a")
        val queue = ArrivalQueue(files, "/q")
        val custody = InferenceStoreCustody(
            files = files,
            embeddings = InMemoryEmbeddingStore(),
            faces = InMemoryFaceTemplateStore(),
            cancelPass = {},
            currentRealm = { realm },
            arrivals = queue,
        )
        assertTrue(custody.ensureCurrentRealm())
        queue.add("/photos/a.jpg")
        queue.flush()

        realm = InferenceRealm("owner-b")
        custody.ensureCurrentRealm()
        assertEquals(0, queue.size, "a realm change")
        assertNull(files.fileSize("/q"))

        queue.add("/photos/b.jpg")
        custody.relinquish()
        assertEquals(0, queue.size, "a relinquish")
    }

    /** The other realm-change branch: the store's epoch unreadable, the owner another one. */
    @Test
    fun anOwnerChangeSeenWithoutTheEpochClearsTheQueueToo() = runTest {
        val files = InMemoryFileDatabase()
        var reading: RealmReading = RealmReading.Of(InferenceRealm("owner-a", storeEpoch = "1"))
        val queue = ArrivalQueue(files, "/q")
        val custody = InferenceStoreCustody(
            files = files,
            embeddings = InMemoryEmbeddingStore(),
            faces = InMemoryFaceTemplateStore(),
            cancelPass = {},
            readRealm = { reading },
            arrivals = queue,
        )
        assertTrue(custody.ensureCurrentRealm())
        queue.add("/photos/a.jpg")

        reading = RealmReading.CannotTell(ownerId = "owner-b", why = "epoch unreadable")
        custody.ensureCurrentRealm()

        assertEquals(0, queue.size)
    }

    // ---- refusals ----------------------------------------------------------------------------------

    @Test
    fun withoutConsentNothingIsReadAndTheQueueIsKept() = runTest {
        val library = Library(photos(3))
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore(), Bars()), library, passScope())
        val queue = queueOf("p1.jpg")

        val batch = runner.runArrivalsBatch(proxy(consent = false).executionContext, queue, cap = 50)

        assertNotNull(batch)
        assertFalse(batch.result.deferred, "absence of consent is not a state that passes — no retry")
        assertEquals(0, library.pointReads, "refused before any read")
        assertEquals(1, queue.size)
    }

    @Test
    fun withoutAModelNothingIsRead() = runTest {
        val library = Library(photos(3))
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore(), Bars(), backends = emptyList()), library, passScope())

        val batch = runner.runArrivalsBatch(proxy().executionContext, queueOf("p1.jpg"), cap = 50)

        assertEquals(0, batch?.result?.embedded)
        assertEquals(0, library.pointReads)
    }

    @Test
    fun aLowBatteryDefersTheBatchBeforeTheSlotIsTaken() = runTest {
        val library = Library(photos(3))
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore(), Bars(arrivals = false)), library, passScope())

        val batch = runner.runArrivalsBatch(proxy().executionContext, queueOf("p1.jpg"), cap = 50)

        assertTrue(batch!!.result.deferred)
        assertEquals(0, library.pointReads)
        assertEquals(PassStanding.Idle(null), runner.standing.value, "no pass ever started")
    }

    /** ⚠⚠ A refused batch still deletes what a consent withdrawal owes, as the pass's refusals do. */
    @Test
    fun aRefusedBatchStillDeletesTemplatesAfterAWithdrawal() = runTest {
        suspend fun withTemplates() = InMemoryFaceTemplateStore().apply {
            put("/photos/a.jpg", listOf(StoredFace(FaceBox(0.1f, 0.1f, 0.4f, 0.4f), floatArrayOf(1f, 0f))), ModelProvenance.Fake)
        }
        val proxy = proxy().apply { write(InferenceCapability.FACE_CLUSTERING.deviceConsent, false, deviceId = thisDevice) }

        val battery = withTemplates()
        MediaIndexRunner(indexer(InMemoryEmbeddingStore(), Bars(arrivals = false), faces = battery), Library(photos(1)), passScope())
            .runArrivalsBatch(proxy.executionContext, queueOf("p1.jpg"), cap = 50)
        assertEquals(0, battery.size(), "the bar's refusal")

        val noModel = withTemplates()
        val runner = MediaIndexRunner(
            indexer(InMemoryEmbeddingStore(), Bars(), backends = emptyList(), faces = noModel),
            Library(photos(1)),
            passScope(),
        )
        val faceChangesBefore = runner.faceChanges.value
        runner.runArrivalsBatch(proxy.executionContext, queueOf("p1.jpg"), cap = 50)
        assertEquals(0, noModel.size(), "the nothing-may-run refusal")
        assertTrue(runner.faceChanges.value > faceChangesBefore, "and People is told")

        val scheduled = withTemplates()
        MediaIndexRunner(indexer(InMemoryEmbeddingStore(), Bars(scheduled = false), faces = scheduled), Library(photos(1)), passScope())
            .runToCompletion(proxy.executionContext)
        assertEquals(0, scheduled.size(), "the scheduled pass's bar refusal too")
    }

    /** ⚠⚠ Its own bar: the batch does not need the 12 h bar, and the 12 h pass does not get the batch's. */
    @Test
    fun theBatchIsHeldToItsOwnBarAndTheScheduledPassKeepsIdleAndCharging() = runTest {
        val bars = Bars(scheduled = false, manual = true, arrivals = true)
        val store = InMemoryEmbeddingStore()
        val library = Library(photos(2))
        val runner = MediaIndexRunner(indexer(store, bars), library, passScope())
        val proxy = proxy()

        assertTrue(runner.runToCompletion(proxy.executionContext).deferred, "the 12 h pass: not idle+charging, so no")
        assertEquals(0, library.walks)

        assertEquals(2, runner.runArrivalsBatch(proxy.executionContext, queueOf("p1.jpg", "p2.jpg"), cap = 50)?.result?.embedded)

        bars.arrivals = false
        assertTrue(runner.runArrivalsBatch(proxy.executionContext, queueOf("p1.jpg"), cap = 50)!!.result.deferred, "nor the press's")
    }

    // ---- single flight -----------------------------------------------------------------------------

    @Test
    fun aBatchDoesNotStartBesideARunningIndexNow_andKeepsItsQueue() = runTest {
        val gate = CompletableDeferred<Unit>()
        val library = Library(photos(1), gate = gate)
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore(), Bars()), library, passScope())
        val proxy = proxy()
        val queue = queueOf("p1.jpg")

        assertTrue(runner.start(proxy.executionContext, manual = true))
        assertNull(runner.runArrivalsBatch(proxy.executionContext, queue, cap = 50), "single-flight: the press is indexing")
        assertEquals(1, queue.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, library.walks, "one pass — the press's")
    }

    @Test
    fun aPressReplacesARunningBatch_whichSettlesNothing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val library = Library(photos(1), gate = gate)
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore(), Bars()), library, passScope())
        val proxy = proxy()
        val queue = queueOf("p1.jpg")

        val batch = async(start = CoroutineStart.UNDISPATCHED) { runner.runArrivalsBatch(proxy.executionContext, queue, cap = 50) }
        assertTrue(runner.isRunning())
        assertTrue(runner.start(proxy.executionContext, manual = true), "a press supersedes an automatic batch")
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(batch.await(), "told another pass is indexing, not that it failed")
        assertEquals(1, queue.size, "a batch that did not finish settles nothing")
    }

    @Test
    fun theScheduledPassReplacesARunningBatchRatherThanJoiningIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val library = Library(photos(3), gate = gate)
        val runner = MediaIndexRunner(indexer(InMemoryEmbeddingStore(), Bars(scheduled = true)), library, passScope())
        val proxy = proxy()

        val batch = async(start = CoroutineStart.UNDISPATCHED) { runner.runArrivalsBatch(proxy.executionContext, queueOf("p1.jpg"), cap = 1) }
        val scheduled = async(start = CoroutineStart.UNDISPATCHED) { runner.runToCompletion(proxy.executionContext) }
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(batch.await())
        assertEquals(3, scheduled.await().embedded, "its own whole-library report, not the batch's")
    }

    @Test
    fun absentConditionsNeverIndexArrivals() {
        assertFalse(IndexingConditions.Never.allowArrivalIndexing())
        assertFalse(IndexingConditions { true }.allowArrivalIndexing(), "a platform that does not say so indexes no arrivals")
    }

    // ---- the queue ---------------------------------------------------------------------------------

    @Test
    fun clearEmptiesTheQueueInMemoryAndOnDisk() {
        val files = InMemoryFileDatabase()
        val queue = ArrivalQueue(files, "/q").apply { add("/photos/a.jpg"); flush() }
        assertTrue(files.fileSize("/q") != null)
        queue.clear()
        assertEquals(0, queue.size)
        assertNull(files.fileSize("/q"))
        assertEquals(0, ArrivalQueue(files, "/q").size, "nothing for the next process either")
    }

    @Test
    fun theQueueDeduplicatesDropsTheOldestPastCapacityAndSurvivesTheProcess() {
        val files = InMemoryFileDatabase()
        val queue = ArrivalQueue(files, "/q", capacity = 3)
        listOf("a", "b", "a", "c", "d").forEach { queue.add("/photos/$it.jpg") }
        assertEquals(listOf("/photos/b.jpg", "/photos/c.jpg", "/photos/d.jpg"), queue.paths())

        queue.settle(mapOf("/photos/c.jpg" to PathOutcome.UNAVAILABLE))
        queue.flush()
        val reopened = ArrivalQueue(files, "/q", capacity = 3)
        assertEquals(queue.paths(), reopened.paths(), "a cold-woken job finds the paths on disk")
        reopened.settle(mapOf("/photos/c.jpg" to PathOutcome.UNAVAILABLE))
        reopened.settle(mapOf("/photos/c.jpg" to PathOutcome.UNAVAILABLE))
        assertEquals(listOf("/photos/b.jpg", "/photos/d.jpg"), reopened.paths(), "its attempts were persisted with it")
    }
}
