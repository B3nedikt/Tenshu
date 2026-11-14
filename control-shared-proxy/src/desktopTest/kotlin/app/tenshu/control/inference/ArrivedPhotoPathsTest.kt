package app.tenshu.control.inference

import app.tenshu.control.di.controlMediaSource
import app.tenshu.control.di.fileLivePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.FileService
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.file.system.replaceFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.initFileService
import org.tenshu.file.test.createFileTestRegistry
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.inference.proxy.FakeInferenceBackend
import org.tenshu.inference.proxy.IndexingConditions
import org.tenshu.inference.proxy.InMemoryEmbeddingStore
import org.tenshu.inference.proxy.InferenceBackendRegistry
import org.tenshu.inference.proxy.MediaIndexRunner
import org.tenshu.inference.proxy.MediaIndexer
import org.tenshu.inference.proxy.MediaSource
import org.tenshu.inference.proxy.NEW_ARRIVALS_BATCH_CAP
import org.tenshu.inference.proxy.runArrivalsBatch
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PR-6b (`ios-on-device-inference.md` §17): which photographs arrived, read from the real file
 * service's modification log, and the batch over them through the PRODUCTION media source — no walk.
 */
class ArrivedPhotoPathsTest {

    private val thisDevice = "device-a"
    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH

    private inner class Realm(scope: TestScope) {
        val proxy: TestProxy = TestProxy(
            schemaRegistry = createFileTestRegistry().apply { registerConfig(inferenceServiceCommonModule) },
            coroutineScope = scope,
        ) { id, proxy -> fileServiceProxyImplementationFactory(id = id, fileDatabase = proxy.fileDatabase, deviceId = thisDevice) }
            .apply { executionContext.initFileService(fileDatabase = fileDatabase) }

        val context: ExecutionContext = proxy.executionContext
        val files: FileService = run {
            val executor = LocalTaskExecutor(context)
            TenshuEnvironment(
                taskExecutor = executor,
                schemaRegistry = proxy.schemaRegistry,
                requestChainFactory = { c -> LocalRequestChain(executor, c) },
                tContext = proxy.tContext,
            ).getService(FileService.ID)
        }

        /** Counts every whole-library walk the production source is asked for. */
        var walks = 0
        private val production: MediaSource = controlMediaSource(proxy.fileDatabase, metered = null, livePaths = fileLivePaths)
        val source: MediaSource = object : MediaSource {
            override suspend fun media(executionContext: ExecutionContext) = walk(executionContext).media
            override suspend fun walk(executionContext: ExecutionContext) = production.walk(executionContext).also { walks++ }
            override suspend fun mediaAt(executionContext: ExecutionContext, paths: Collection<String>) = production.mediaAt(executionContext, paths)
        }
        val embeddings = InMemoryEmbeddingStore()
        val runner = MediaIndexRunner(
            MediaIndexer(
                registry = InferenceBackendRegistry(listOf(FakeInferenceBackend(capabilities = setOf(search)))),
                deviceId = { thisDevice },
                embeddings = embeddings,
                conditions = object : IndexingConditions {
                    override fun allowIndexing() = false
                    override fun allowArrivalIndexing() = true
                },
            ),
            source,
            CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler) + Job()),
        )

        init {
            write(search.realmConsent, true)
            write(search.deviceConsent, true, deviceId = thisDevice)
        }

        suspend fun upload(folder: String, name: String) =
            check(files.defaultFileSystem.uploadFile(folder, name, name.encodeToByteArray()) is TResult.Success) { "upload $folder/$name" }

        private fun <T> write(preference: Preference<T>, value: T, deviceId: String? = null) {
            val tContext = proxy.tContext
            context.saveData(
                if (deviceId == null) {
                    Entity.create<RealmSetting>(context = tContext, composite = RealmSetting.create(context = tContext, preferenceId = preference.id, value = preference.adapter.toData(value, tContext)))
                } else {
                    Entity.create<DeviceSetting>(context = tContext, composite = DeviceSetting.create(context = tContext, deviceId = deviceId, preferenceId = preference.id, value = preference.adapter.toData(value, tContext)))
                },
            )
        }
    }

    /** ⚠⚠ Image arrivals only, each once: uploads, a move to a new path; never a folder, a non-image, a delete, an overwrite. */
    @Test
    fun theLogSaysWhichPhotographsArrivedAndEachOnce() = runTest {
        val realm = Realm(this)
        check(realm.files.defaultFileSystem.createFolder("/photos") is TResult.Success)
        val arrivals = ArrivedPhotoPaths(ArrivalsLogCursor.InMemory())
        arrivals.read(realm.context)
        realm.upload("/photos", "a.jpg")
        realm.upload("/photos", "notes.txt")
        assertEquals(listOf("/photos/a.jpg"), arrivals.read(realm.context).paths)
        assertEquals(ArrivedPhotoPaths.Look(emptyList(), missed = false), arrivals.read(realm.context), "an arrival was offered twice")

        realm.upload("/photos", "b.jpg")
        assertEquals(listOf("/photos/b.jpg"), arrivals.read(realm.context).paths)

        check(realm.files.defaultFileSystem.moveFile("/photos/a.jpg", "/photos/c.jpg") is TResult.Success)
        assertEquals(listOf("/photos/c.jpg"), arrivals.read(realm.context).paths, "a photograph at a new path is one no pass has seen")

        // ⚠⚠ A move of the very LAST row read: its history is re-keyed (the old row reaped), and
        // reading resumes past the newest remembered row still there — the move is not lost.
        realm.upload("/photos", "e.jpg")
        assertEquals(listOf("/photos/e.jpg"), arrivals.read(realm.context).paths)
        check(realm.files.defaultFileSystem.moveFile("/photos/e.jpg", "/photos/f.jpg") is TResult.Success)
        assertEquals(ArrivedPhotoPaths.Look(listOf("/photos/f.jpg"), missed = false), arrivals.read(realm.context), "a move of the last row read was lost")

        check(realm.files.defaultFileSystem.deleteFile("/photos/b.jpg") is TResult.Success)
        realm.upload("/photos", "d.txt")
        // ⚠ An overwrite in place is a content change at a path the stores already know: not an arrival.
        check(realm.files.defaultFileSystem.replaceFile("/photos", "c.jpg", byteArrayOf(9)) is TResult.Success)
        assertEquals(emptyList(), arrivals.read(realm.context).paths)
    }

    /**
     * ⚠⚠ A cold start reads past the cursor the last process left, never the log's history: 1,000
     * photographs no pass has seen, a cursor persisted after them, one upload — the fresh process
     * queues the upload alone, and the first batch indexes it. With no cursor at all (the first look
     * ever) nothing is queued and the look says arrivals may have been missed (the wake's).
     */
    @Test
    fun aColdStartQueuesOnlyWhatArrivedPastThePersistedCursor() = runTest {
        val realm = Realm(this)
        check(realm.files.defaultFileSystem.createFolder("/photos") is TResult.Success)
        repeat(1_000) { realm.upload("/photos", "old$it.jpg") }
        val disk = InMemoryFileDatabase()

        val firstEver = ArrivedPhotoPaths(ArrivalsLogCursor.InFile(disk)).read(realm.context)
        assertEquals(ArrivedPhotoPaths.Look(emptyList(), missed = true), firstEver, "a first look queued the log's history")

        realm.upload("/photos", "upload.jpg")
        // A fresh process: a new reader over the cursor on disk.
        val look = ArrivedPhotoPaths(ArrivalsLogCursor.InFile(disk)).read(realm.context)
        assertEquals(ArrivedPhotoPaths.Look(listOf("/photos/upload.jpg"), missed = false), look, "a cold start re-offered the log")

        val queue = org.tenshu.inference.proxy.ArrivalQueue(files = null, path = "/inference/arrivals.queue")
        look.paths.forEach(queue::add)
        val batch = assertNotNull(realm.runner.runArrivalsBatch(realm.context, queue, NEW_ARRIVALS_BATCH_CAP))
        assertTrue(realm.embeddings.visited("/photos/upload.jpg"), "the upload was not in the first batch: ${batch.result}")
        assertEquals(0, realm.walks)

        // ⚠ The end marked before consent (hashes only): the first look after it offers what came since.
        val marked = ArrivedPhotoPaths(ArrivalsLogCursor.InFile(InMemoryFileDatabase()))
        marked.markEndIfUnmarked(realm.context)
        realm.upload("/photos", "after.jpg")
        assertEquals(ArrivedPhotoPaths.Look(listOf("/photos/after.jpg"), missed = false), marked.read(realm.context))

        // ⚠ An EMPTY log marked (a fresh install): its first upload is offered, not seeded past.
        val empty = Realm(this)
        val fromStart = ArrivedPhotoPaths(ArrivalsLogCursor.InFile(InMemoryFileDatabase()))
        fromStart.markEndIfUnmarked(empty.context)
        check(empty.files.defaultFileSystem.createFolder("/photos") is TResult.Success)
        empty.upload("/photos", "first.jpg")
        assertEquals(ArrivedPhotoPaths.Look(listOf("/photos/first.jpg"), missed = false), fromStart.read(empty.context), "an empty log's first upload was seeded past")
    }

    /** ⚠ More new rows than the window: the newest are read, and the look says some were missed. A cursor that moved: nothing queued, missed. */
    @Test
    fun aBurstPastTheWindowOrAMovedCursorIsTheWakes() = runTest {
        val realm = Realm(this)
        check(realm.files.defaultFileSystem.createFolder("/photos") is TResult.Success)
        val cursor = ArrivalsLogCursor.InMemory()
        val arrivals = ArrivedPhotoPaths(cursor, window = 5)
        arrivals.read(realm.context)
        repeat(8) { realm.upload("/photos", "b$it.jpg") }
        val burst = arrivals.read(realm.context)
        assertTrue(burst.missed, "a burst past the window was not reported")
        assertEquals((3 until 8).map { "/photos/b$it.jpg" }, burst.paths, "the newest of a burst were not read")

        // Rows merged in ahead of the cursor (its row further down than it was): the new rows are still read, and missed.
        realm.upload("/photos", "late.jpg")
        val at = assertNotNull(cursor.read())
        cursor.write(at.copy(index = at.index - 1))
        assertEquals(ArrivedPhotoPaths.Look(listOf("/photos/late.jpg"), missed = true), arrivals.read(realm.context), "rows merged ahead of the cursor went unreported")

        // None of the cursor's rows left (another realm's log): nothing guessed at.
        realm.upload("/photos", "other.jpg")
        cursor.write(ArrivalsLogCursor.Position(tail = listOf(1L, 2L), index = 1))
        assertEquals(ArrivedPhotoPaths.Look(emptyList(), missed = true), arrivals.read(realm.context), "a lost cursor guessed")
    }

    /**
     * ⚠⚠ The production chain end to end: 60 photographs no pass has seen, one upload; the batch over
     * what the log says arrived indexes the upload alone, and the library is never walked.
     */
    @Test
    fun aBatchOverTheArrivalsIndexesTheUploadAloneWithNoWalk() = runTest {
        val realm = Realm(this)
        check(realm.files.defaultFileSystem.createFolder("/photos") is TResult.Success)
        repeat(60) { realm.upload("/photos", "old$it.jpg") }
        val arrivals = ArrivedPhotoPaths(ArrivalsLogCursor.InMemory())
        arrivals.read(realm.context)
        realm.upload("/photos", "upload.jpg")

        val arrived = arrivals.read(realm.context).paths
        assertEquals(listOf("/photos/upload.jpg"), arrived)
        val batch = realm.runner.runPathsBatch(realm.context, arrived + "/photos/gone.jpg", NEW_ARRIVALS_BATCH_CAP)
        val result = assertNotNull(batch).result
        assertEquals(1, result.embedded, "the upload was not indexed: $result")
        assertEquals(1, realm.embeddings.size())
        assertTrue(realm.embeddings.visited("/photos/upload.jpg"))
        assertEquals(0, realm.walks, "the batch walked the library")
        assertEquals(1, result.imagesVisited, "the batch looked at a path that is not a live image")
    }
}
