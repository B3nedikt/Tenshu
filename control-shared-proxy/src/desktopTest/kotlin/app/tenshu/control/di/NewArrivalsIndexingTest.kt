package app.tenshu.control.di

import app.tenshu.control.inference.ArrivalIndexScheduler
import app.tenshu.control.inference.InProcessArrivalScheduler
import app.tenshu.control.inference.NEW_ARRIVALS_DEBOUNCE_MS
import app.tenshu.control.inference.NEW_ARRIVALS_THROTTLE_MS
import app.tenshu.control.inference.NewArrivalsObserver
import app.tenshu.control.inference.ThrottledArrivalScheduler
import app.tenshu.control.inference.installNewArrivalsIndexing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.FileService
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.id
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.file.ContentChangedFileModification
import org.tenshu.core.file.FileModification
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.initFileService
import org.tenshu.file.test.createFileTestRegistry
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.inference.proxy.DefaultEmbeddingStore
import org.tenshu.inference.proxy.FakeInferenceBackend
import org.tenshu.inference.proxy.IndexingConditions
import org.tenshu.inference.proxy.InferenceBackendRegistry
import org.tenshu.inference.proxy.MediaIndexRunner
import org.tenshu.inference.proxy.MediaIndexer
import org.tenshu.inference.proxy.NEW_ARRIVALS_BATCH_CAP
import org.tenshu.inference.proxy.ArrivalQueue
import org.tenshu.inference.proxy.runArrivalsBatch
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import app.tenshu.control.inference.ArrivalJob
import app.tenshu.control.inference.NEW_ARRIVALS_MAX_LATENCY_MS
import kotlin.test.assertFalse
import app.tenshu.control.inference.LastIndexCoverage
import app.tenshu.control.inference.recordPass
import app.tenshu.control.inference.wipeDerivedInferenceState
import org.tenshu.inference.proxy.IndexPassResult
import org.tenshu.inference.proxy.DefaultFaceTemplateStore
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * IN-D6a on desktop and Android: an arrival is noticed from the file service's own modification rows
 * (never a walk), debounced into ONE batch, and the batch runs through the one runner.
 */
class NewArrivalsIndexingTest {

    private val thisDevice = "device-a"

    private class Counting : ArrivalIndexScheduler {
        val paths = mutableListOf<String>()
        val arrivals: Int get() = paths.size
        override fun arrived(filePath: String) {
            paths += filePath
        }
    }

    /** Counts every row read, so a batch's reads can be compared with the library's size. */
    private class CountingObjectStore : InMemoryObjectStore() {
        var reads = 0
        override fun getObject(dataIdHash: Long): ObjectStore.DbEntry? {
            reads++
            return super.getObject(dataIdHash)
        }
    }

    private inner class Device(scope: TestScope) {
        val embeddings = DefaultEmbeddingStore(InMemoryFileDatabase(), "/inference/embeddings.log")
        val objectStore = CountingObjectStore()
        val queue = ArrivalQueue(InMemoryFileDatabase(), "/inference/arrivals.queue")

        val proxy: TestProxy = TestProxy(
            schemaRegistry = createFileTestRegistry().apply { registerConfig(inferenceServiceCommonModule) },
            coroutineScope = scope,
            objectStore = objectStore,
        ) { id, proxy -> fileServiceProxyImplementationFactory(
                id = id,
                fileDatabase = proxy.fileDatabase,
                // ⚠ As production passes it: without a device id the file ops write no modification
                // rows, and there would be nothing to notice an arrival by.
                deviceId = thisDevice,
            )
        }
            .apply { executionContext.initFileService(fileDatabase = fileDatabase) }

        /** Arrivals on battery, idle+charging never: the batch must run on its own bar. */
        private val bars = object : IndexingConditions {
            override fun allowIndexing() = false
            override fun allowManualIndexing() = true
            override fun allowArrivalIndexing() = true
        }

        val runner = MediaIndexRunner(
            indexer = MediaIndexer(
                registry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
                deviceId = { thisDevice },
                embeddings = embeddings,
                conditions = bars,
            ),
            source = controlMediaSource(proxy.fileDatabase, metered = null, livePaths = fileLivePaths),
            scope = CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler) + Job()),
        )

        val files: FileService = run {
            val executor = LocalTaskExecutor(proxy.executionContext)
            TenshuEnvironment(
                taskExecutor = executor,
                schemaRegistry = proxy.schemaRegistry,
                requestChainFactory = { context -> LocalRequestChain(executor, context) },
                tContext = proxy.tContext,
            ).getService(FileService.ID)
        }

        init {
            write(InferenceCapability.SEMANTIC_IMAGE_SEARCH.realmConsent, true)
            write(InferenceCapability.SEMANTIC_IMAGE_SEARCH.deviceConsent, true, deviceId = thisDevice)
        }

        suspend fun folders() {
            check(files.defaultFileSystem.createFolder("/photos") is TResult.Success)
            check(files.defaultFileSystem.createFolder("/Trash") is TResult.Success)
        }

        suspend fun upload(name: String) =
            check(files.defaultFileSystem.uploadFile("/photos", name, name.encodeToByteArray()) is TResult.Success)

        suspend fun move(from: String, to: String) = check(files.defaultFileSystem.moveFile(from, to) is TResult.Success)

        private fun <T> write(preference: Preference<T>, value: T, deviceId: String? = null) {
            val c = proxy.tContext
            proxy.executionContext.saveData(
                if (deviceId == null) {
                    Entity.create<RealmSetting>(
                        context = c,
                        composite = RealmSetting.create(
                            context = c,
                            preferenceId = preference.id,
                            value = preference.adapter.toData(value, c),
                        ),
                    )
                } else {
                    Entity.create<DeviceSetting>(
                        context = c,
                        composite = DeviceSetting.create(
                            context = c,
                            deviceId = deviceId,
                            preferenceId = preference.id,
                            value = preference.adapter.toData(value, c),
                        ),
                    )
                },
            )
        }
    }

    // ---- noticing -------------------------------------------------------------------------------

    @Test
    fun anUploadedPhotographIsAnArrival_aTextFileAndATrashingAreNot() = runTest {
        val device = Device(this).apply { folders() }
        val seen = Counting()
        device.proxy.executionContext.installNewArrivalsIndexing(seen)

        device.upload("a.jpg")
        assertTrue("/photos/a.jpg" in seen.paths, "an uploaded photograph is an arrival, by its path")

        val before = seen.arrivals
        NewArrivalsObserver(device.proxy.executionContext, seen).onDataSaved(
            ContentChangedFileModification.create(
                context = device.proxy.tContext,
                fileId = "/photos/a.jpg",
                timestamp = 1L,
                deviceId = thisDevice,
                manifestId = "/photos/a.jpg",
            ),
            WriteOrigin.SYNC_INGEST,
        )
        assertEquals(before, seen.arrivals, "new bytes at the same path are no arrival: the stores would not re-index it")
        device.upload("notes.txt")
        assertEquals(before, seen.arrivals, "a text file is no photograph")
        device.move("/photos/a.jpg", "/Trash/a.jpg")
        assertEquals(before, seen.arrivals, "a photograph moved to the trash has not arrived")
        device.move("/Trash/a.jpg", "/photos/a.jpg")
        assertTrue(seen.arrivals > before, "restored to a path no pass has seen: an arrival")
    }

    /** ⚠⚠ Synced in from another device is an arrival too: the observer does not look at the origin. */
    @Test
    fun aModificationRowIngestedFromAnotherDeviceIsAnArrival() = runTest {
        val device = Device(this).apply { folders() }
        val rows = mutableListOf<Data>()
        val context = device.proxy.executionContext
        context.addDataObserver(object : DataObserver {
            override fun onDataSaved(data: Data, origin: WriteOrigin) {
                val type = (data as? Entity<*>)?.type?.get() ?: data.id
                if ((context.schemaProvider.getSchema(type) as? CompositeSchema)?.isSubtypeOf(FileModification.ID) == true) rows += data
            }
            override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit
        })
        device.upload("b.jpg")
        assertTrue(rows.isNotEmpty(), "precondition: the upload saved a modification row")

        val seen = Counting()
        NewArrivalsObserver(context, seen).onDataSaved(rows.first(), WriteOrigin.SYNC_INGEST)
        assertEquals(1, seen.arrivals)
    }

    // ---- debouncing (desktop) ----------------------------------------------------------------------

    private fun TestScope.clock(): () -> Long = { testScheduler.currentTime }

    @Test
    fun aBurstOfArrivalsIsOneBatchAboutTwoMinutesAfterTheLast() = runTest {
        var batches = 0
        val scheduler = InProcessArrivalScheduler(backgroundScope, ArrivalQueue(null, "/q"), now = clock()) { batches++ }
        runCurrent()

        repeat(500) { scheduler.arrived("/photos/p$it.jpg") }
        advanceTimeBy(NEW_ARRIVALS_DEBOUNCE_MS / 2)
        scheduler.arrived("/photos/last.jpg")
        advanceTimeBy(NEW_ARRIVALS_DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals(0, batches, "the delay restarts from the last arrival")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, batches, "one batch for the whole burst")

        advanceTimeBy(NEW_ARRIVALS_DEBOUNCE_MS * 10)
        runCurrent()
        assertEquals(1, batches, "and nothing chains after it")
    }

    @Test
    fun onDesktopALongStreamStillRunsWithinTheMaxLatency() = runTest {
        val ranAt = mutableListOf<Long>()
        val scheduler = InProcessArrivalScheduler(backgroundScope, ArrivalQueue(null, "/q"), now = clock()) {
            ranAt += testScheduler.currentTime
        }
        runCurrent()
        val start = testScheduler.currentTime
        // An arrival every 30 s for an hour: the debounce alone would never fire.
        repeat(120) {
            scheduler.arrived("/photos/s$it.jpg")
            advanceTimeBy(30_000)
        }
        assertTrue(ranAt.isNotEmpty(), "a stream starved the batch")
        assertTrue(ranAt.first() - start <= NEW_ARRIVALS_MAX_LATENCY_MS, "the first batch ran at ${ranAt.first() - start} ms")
    }

    // ---- arming (Android) --------------------------------------------------------------------------

    /** WorkManager's unique job as the scheduler sees it: armed at [armedAt], due two minutes later. */
    private class FakeJob {
        var state = ArrivalJob.NONE
        val armedAt = mutableListOf<Long>()
    }

    private fun TestScope.android(job: FakeJob, queue: ArrivalQueue = ArrivalQueue(null, "/q")) =
        ThrottledArrivalScheduler(backgroundScope, queue, job = { job.state }, now = clock()) {
            job.armedAt += testScheduler.currentTime
            job.state = ArrivalJob.WAITING
        }

    @Test
    fun androidArmsTheJobAtOnceAndAtMostOncePerWindowDuringABurst() = runTest {
        val job = FakeJob()
        val scheduler = android(job)
        runCurrent()

        scheduler.arrived("/photos/a.jpg")
        runCurrent()
        assertEquals(1, job.armedAt.size, "the first arrival arms the job at once")
        repeat(1_000) { scheduler.arrived("/photos/b$it.jpg") }
        runCurrent()
        assertEquals(1, job.armedAt.size, "a burst inside the window is no second write")
        advanceTimeBy(NEW_ARRIVALS_THROTTLE_MS + 1)
        runCurrent()
        assertEquals(2, job.armedAt.size, "the burst pushes the waiting job back once, at the window's end")
        advanceTimeBy(NEW_ARRIVALS_THROTTLE_MS * 10)
        runCurrent()
        assertEquals(2, job.armedAt.size, "and nothing re-arms without an arrival")
    }

    @Test
    fun androidStopsPushingTheJobBackAtTheMaxLatency() = runTest {
        val job = FakeJob()
        val scheduler = android(job)
        runCurrent()
        val start = testScheduler.currentTime

        repeat(240) {
            scheduler.arrived("/photos/s$it.jpg")
            advanceTimeBy(15_000)
        }
        val due = job.armedAt.last() + NEW_ARRIVALS_DEBOUNCE_MS
        assertTrue(
            due - start <= NEW_ARRIVALS_MAX_LATENCY_MS,
            "an hour of arrivals pushed the job back to ${due - start} ms",
        )
    }

    @Test
    fun anArrivalNeverReplacesARunningBatch_andArmsTheNextOneAfterIt() = runTest {
        val job = FakeJob().apply { state = ArrivalJob.RUNNING }
        val queue = ArrivalQueue(null, "/q")
        val scheduler = android(job, queue)
        runCurrent()

        scheduler.arrived("/photos/during.jpg")
        advanceTimeBy(NEW_ARRIVALS_THROTTLE_MS * 4)
        runCurrent()
        assertEquals(0, job.armedAt.size, "REPLACE would have cancelled the running batch")
        assertEquals(listOf("/photos/during.jpg"), queue.paths(), "its path waits on the queue")

        job.state = ArrivalJob.NONE
        advanceTimeBy(NEW_ARRIVALS_THROTTLE_MS + 1)
        runCurrent()
        assertEquals(1, job.armedAt.size, "armed once the batch is done — a new arrival's batch, not a chain")
    }

    // ---- end to end, as desktop wires it --------------------------------------------------------

    @Test
    fun onDesktopAnUploadBehindABacklogIsIndexed_readingOnlyItsOwnRows() = runTest {
        val device = Device(this).apply { folders() }
        // The backlog: here before the watch, so never queued — the scheduled pass's.
        repeat(200) { device.upload("old$it.jpg") }
        var batches = 0
        val context = device.proxy.executionContext
        context.installNewArrivalsIndexing(
            InProcessArrivalScheduler(backgroundScope, device.queue, now = clock()) {
                batches++
                val before = device.objectStore.reads
                device.runner.runArrivalsBatch(context, device.queue, NEW_ARRIVALS_BATCH_CAP)
                batchReads += device.objectStore.reads - before
            },
        )
        runCurrent()

        device.upload("new.jpg")
        advanceTimeBy(NEW_ARRIVALS_DEBOUNCE_MS + 1)
        runCurrent()

        assertTrue(batches >= 1, "no batch ran")
        assertTrue(device.embeddings.visited("/photos/new.jpg"), "the photograph that arrived was indexed")
        assertFalse(device.embeddings.visited("/photos/old0.jpg"), "and not the backlog")
        assertTrue(batchReads < 50, "a batch over one path read $batchReads rows — a walk of 200 reads hundreds")
        assertTrue(
            device.runner.runToCompletion(context).deferred,
            "the 12 h pass still waits for idle+charging — the batch's bar did not loosen it",
        )
    }

    private var batchReads = 0

    // ---- what a path batch must not touch, and what a wipe must ------------------------------------

    /** ⚠⚠ A path batch of three must not overwrite the library's "N of M aren't on this device". */
    @Test
    fun aPathBatchLeavesTheLibrarysCoverageAlone() {
        val coverage = LastIndexCoverage()
        coverage.recordPass(IndexPassResult(imagesVisited = 1_300, notOnDevice = 1_290, libraryWalk = true))
        coverage.recordPass(IndexPassResult(imagesVisited = 3, notOnDevice = 0, libraryWalk = false))
        assertEquals(1_300, coverage.coverage.value?.total)
        assertEquals(1_290, coverage.coverage.value?.notOnDevice)
    }

    @Test
    fun aRealmWipeStopsThePassAndTakesTheArrivedPathsWithTheStores() = runTest {
        val files = InMemoryFileDatabase()
        val queue = ArrivalQueue(files, "/inference/arrivals.queue").apply { add("/photos/secret-name.jpg"); flush() }
        val embeddings = DefaultEmbeddingStore(files, "/inference/embeddings.log")
        val faces = DefaultFaceTemplateStore(files, "/inference/faces.log")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val runner = MediaIndexRunner(
            indexer = MediaIndexer(
                registry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
                deviceId = { thisDevice },
                embeddings = embeddings,
                conditions = IndexingConditions { true },
            ),
            source = org.tenshu.inference.proxy.MediaSource { _ ->
                listOf(org.tenshu.inference.proxy.IndexableMedia("/photos/a.jpg") { gate.await(); null })
            },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()),
        )
        assertTrue(embeddings.put("/photos/old.jpg", FloatArray(8) { 0.1f }, org.tenshu.inference.ModelProvenance.Fake))
        runner.start(Device(this).proxy.executionContext, manual = true)
        assertTrue(runner.isRunning())

        wipeDerivedInferenceState(runner, embeddings, faces, queue)

        assertFalse(runner.isRunning(), "the pass is stopped first")
        assertEquals(0, embeddings.size())
        assertEquals(0, queue.size, "the old realm's photo names are gone from memory")
        assertEquals(null, files.fileSize("/inference/arrivals.queue"), "and from disk")
    }

    // ---- wiring ----------------------------------------------------------------------------------

    @Test
    fun androidAndDesktopBothInstallTheWatchBesideTheSchedule() {
        val android = File("src/androidMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt").readText()
        val desktop = File("src/desktopMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt").readText()
        val common = File("src/commonMain/kotlin/app/tenshu/control/di/ProxyModule.kt").readText()

        assertTrue("installNewArrivalsIndexing(" in android && "ThrottledArrivalScheduler(" in android, "Android installs no arrival watch")
        assertTrue("NewArrivalsIndexWorker.enqueue(" in android, "Android's watch arms no WorkManager job")
        assertTrue("NewArrivalsIndexWorker.jobState(" in android, "Android arms without asking whether a batch is running")
        assertTrue("installNewArrivalsIndexing(" in desktop && "InProcessArrivalScheduler(" in desktop, "desktop installs no arrival watch")
        assertTrue(
            Regex("""MediaIndexRunner>\(\)\s*\.runArrivalsBatch\(""").containsMatchIn(desktop),
            "desktop's batch does not go through the one runner",
        )
        assertTrue(Regex("""single \{ org\.tenshu\.inference\.proxy\.ArrivalQueue\(""").containsMatchIn(common), "no single ArrivalQueue")
        listOf(android, desktop).forEach {
            assertTrue("registerMediaIndexSchedule()" in it, "the 12 h schedule must stay registered — it keeps the backlog")
        }
        val ios = File("src/iosMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt").readText()
        listOf("android" to android, "desktop" to desktop, "ios" to ios).forEach { (platform, text) ->
            assertTrue(
                Regex("""onLocalStoreWiped = \{\s*(//[^\n]*\n\s*)*app\.tenshu\.control\.inference\.wipeDerivedInferenceState\(""").containsMatchIn(text),
                "$platform: the realm wipe does not go through wipeDerivedInferenceState — the arrived paths survive it",
            )
        }
        val owned = File("src/iosMain/kotlin/app/tenshu/control/di/OwnedInferenceStores.kt").readText()
        assertTrue(Regex("""listOf\(INFERENCE_EMBEDDINGS_PATH, INFERENCE_FACES_PATH, INFERENCE_ARRIVALS_PATH\)""").containsMatchIn(owned), "iOS's eraseAtRest leaves the queue")
        assertTrue("arrivals = getOrNull()," in owned, "iOS's custody does not clear the queue")
    }
}
