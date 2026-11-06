package app.tenshu.control.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.file.FileService
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.initFileService
import org.tenshu.file.test.createFileTestRegistry
import org.tenshu.inference.GroupedFace
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.InferenceService
import org.tenshu.inference.SemanticSearchUnavailable
import org.tenshu.inference.SimilarPhoto
import org.tenshu.inference.client.inferenceServiceClientModule
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.inference.faceGroups
import org.tenshu.inference.photosLike
import org.tenshu.inference.proxy.DefaultEmbeddingStore
import org.tenshu.inference.proxy.DefaultFaceTemplateStore
import org.tenshu.inference.proxy.FaceClustering
import org.tenshu.inference.proxy.FaceGroupingComponents
import org.tenshu.inference.proxy.FaceSimilarityThreshold
import org.tenshu.inference.proxy.FakeInferenceBackend
import org.tenshu.inference.proxy.IndexingConditions
import org.tenshu.inference.proxy.InferenceBackendRegistry
import org.tenshu.inference.proxy.LivePaths
import org.tenshu.inference.proxy.MediaIndexRunner
import org.tenshu.inference.proxy.MediaIndexer
import org.tenshu.inference.proxy.MediaSource
import org.tenshu.inference.proxy.inferenceServiceProxyImplementationFactory
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **P0f-b PR-1 — the PRODUCTION seams, against a real file service** (`media-processing-pipeline-sweep.md`
 * §6.4.1). [fileLivePaths] and [controlMediaSource] are exactly what `ProxyModule` binds; nothing
 * here stands in for the realm. `DerivedDataOfDeletedPhotosTest` proves the inference half against a
 * stand-in; this proves the half that was inert in production until now.
 *
 * ⚠⚠ **Two sessions, on purpose** (§6.4.10's realm question). The pass runs in Control's session and
 * People / search are asked from Photos' — the way production splits them. If the two answered
 * against different realms, the filter would hide every live photograph; the first assertion of
 * each read test is that it does not.
 */
class MediaLibrarySeamsTest {

    private val thisDevice = "device-a"

    private val calibrated = FaceClustering.Calibrated(threshold = FaceSimilarityThreshold(0.9f), minFaces = 2)

    private val stores = InMemoryFileDatabase()
    private val faces = DefaultFaceTemplateStore(stores, "/inference/faces.log")
    private val embeddings = DefaultEmbeddingStore(stores, "/inference/embeddings.log")

    private inner class Device(scope: TestScope, livePaths: LivePaths = fileLivePaths) {
        val objectStore = InMemoryObjectStore()

        val proxy: TestProxy = TestProxy(
            schemaRegistry = createFileTestRegistry().apply {
                registerConfig(inferenceServiceCommonModule)
                registerConfig(inferenceServiceClientModule)
            },
            coroutineScope = scope,
            objectStore = objectStore,
        ) { id, proxy ->
            fileServiceProxyImplementationFactory(id = id, fileDatabase = proxy.fileDatabase)
                ?: inferenceServiceProxyImplementationFactory(
                    id = id,
                    registry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
                    embeddings = embeddings,
                    deviceId = thisDevice,
                    faceGrouping = FaceGroupingComponents(faces, calibrated, livePaths),
                )
        }.apply { executionContext.initFileService(fileDatabase = fileDatabase) }

        /** ⚠ The production source, over the proxy's own file bytes. */
        val source: MediaSource = controlMediaSource(proxy.fileDatabase, metered = null, livePaths = livePaths)

        val runner = MediaIndexRunner(
            indexer = MediaIndexer(
                registry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
                deviceId = thisDevice,
                embeddings = embeddings,
                faces = faces,
                conditions = IndexingConditions { true },
            ),
            source = source,
            // ⚠ Unconfined for `MediaIndexRunnerTest`'s reason: `backgroundScope` never runs the pass body.
            scope = CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler) + Job()),
        )

        /** Control's session — where the scheduled pass and *Index now* run. */
        val control: ExecutionContext = proxy.clientRegistry.getExecutionContext("app.tenshu.control")

        /** Photos' session — where People and search are asked. */
        private val photos: ExecutionContext = proxy.clientRegistry.getExecutionContext("app.tenshu.photos")

        val files: FileService = environment(proxy.executionContext).getService(FileService.ID)
        val inference: InferenceService = environment(photos).getService(InferenceService.ID)

        private fun environment(session: ExecutionContext): TenshuEnvironment {
            val executor = LocalTaskExecutor(session)
            return TenshuEnvironment(
                taskExecutor = executor,
                schemaRegistry = proxy.schemaRegistry,
                requestChainFactory = { context -> LocalRequestChain(executor, context) },
                tContext = proxy.tContext,
            )
        }

        init {
            consentTo(InferenceCapability.FACE_CLUSTERING)
            consentTo(InferenceCapability.SEMANTIC_IMAGE_SEARCH)
        }

        suspend fun seed(vararg photos: Pair<String, String>) {
            check(files.defaultFileSystem.createFolder("/photos") is TResult.Success)
            check(files.defaultFileSystem.createFolder("/Trash") is TResult.Success)
            photos.forEach { (name, shows) ->
                check(files.defaultFileSystem.uploadFile("/photos", name, shows.encodeToByteArray()) is TResult.Success) {
                    "seeding /photos/$name"
                }
            }
        }

        suspend fun delete(path: String) = check(files.defaultFileSystem.deleteFile(path) is TResult.Success)
        suspend fun move(from: String, to: String) = check(files.defaultFileSystem.moveFile(from, to) is TResult.Success)

        suspend fun pass() = runner.runToCompletion(control)

        suspend fun people(): Set<Set<String>> {
            val result = inference.faceGroups(10)
            assertTrue(result is TResult.Success, "faceGroups failed: $result")
            return result.value!!.toList().filterIsInstance<GroupedFace>()
                .groupBy({ it.groupId.get() }, { it.filePath.get() })
                .values.map { it.toSet() }.toSet()
        }

        private fun <T> write(preference: Preference<T>, value: T, deviceId: String? = null) {
            val tContext = proxy.tContext
            proxy.executionContext.saveData(
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

        private fun consentTo(capability: InferenceCapability) {
            write(capability.realmConsent, true)
            write(capability.deviceConsent, true, deviceId = thisDevice)
        }
    }

    private suspend fun TestScope.twoPeople(): Device = Device(this).apply {
        seed("a1.jpg" to "anna", "a2.jpg" to "anna", "b1.jpg" to "bob", "b2.jpg" to "bob")
        pass()
        assertEquals(
            setOf(setOf("/photos/a1.jpg", "/photos/a2.jpg"), setOf("/photos/b1.jpg", "/photos/b2.jpg")),
            people(),
            "precondition — and §6.4.10's realm check: a filter asking the wrong realm would hide everyone",
        )
    }

    // ---- the read filter, with no pass ---------------------------------------------------------

    @Test
    fun aDeletedPhotoStopsCountingInPeopleWithNoPass() = runTest {
        val device = twoPeople()

        device.delete("/photos/b2.jpg")

        // ⚠⚠ Bob had exactly `minFaces` photographs, so his absence is the proof the template no
        // longer COUNTS, not merely that a path was trimmed from the answer.
        assertEquals(setOf(setOf("/photos/a1.jpg", "/photos/a2.jpg")), device.people())
        assertTrue(faces.visited("/photos/b2.jpg"), "no pass ran: the store still holds it, the read dropped it")
    }

    @Test
    fun aTrashedPhotoStopsCountingAndCountsAgainAfterRestore() = runTest {
        val device = twoPeople()

        device.move("/photos/b2.jpg", "/Trash/b2.jpg")
        assertEquals(setOf(setOf("/photos/a1.jpg", "/photos/a2.jpg")), device.people(), "trashed")

        device.move("/Trash/b2.jpg", "/photos/b2.jpg")
        assertEquals(
            setOf(setOf("/photos/a1.jpg", "/photos/a2.jpg"), setOf("/photos/b1.jpg", "/photos/b2.jpg")),
            device.people(),
            "restored — the template was hidden, never removed, and the path is live again",
        )
    }

    @Test
    fun searchNeverRanksADeadOrTrashedPhoto() = runTest {
        val device = twoPeople()
        device.delete("/photos/a1.jpg")
        device.move("/photos/b1.jpg", "/Trash/b1.jpg")

        val anna = device.hits("anna")
        val bob = device.hits("bob")

        assertEquals("/photos/a2.jpg", anna.firstOrNull(), "the live anna photograph is still found: $anna")
        assertEquals("/photos/b2.jpg", bob.firstOrNull(), "the live bob photograph is still found: $bob")
        val all = anna + bob
        assertFalse("/photos/a1.jpg" in all, "a deleted photograph was ranked: $all")
        assertFalse(all.any { it.startsWith("/Trash/") || it == "/photos/b1.jpg" }, "a trashed photograph was ranked: $all")
    }

    @Test
    fun searchOverOnlyDeadVectorsSaysNotIndexedNotNothingMatched() = runTest {
        val device = Device(this)
        device.seed("a1.jpg" to "anna")
        device.pass()
        device.delete("/photos/a1.jpg")

        val result = device.inference.photosLike("anna", 10)

        val reason = ((result as? TResult.Failure<*>)?.error as? SemanticSearchUnavailable)?.reason?.get()
        assertEquals(SemanticSearchUnavailable.Reasons.NOT_INDEXED, reason, "got $result")
    }

    // ---- the failed-read rule ------------------------------------------------------------------

    /**
     * ⚠⚠ A liveness read that cannot answer — the store lost its file rows from under the file
     * service, the way a wipe in progress looks — must neither hide every face (an empty People success) nor
     * fall back to the unfiltered store (dead rows shown). The read fails, and Photos renders a
     * failure as "couldn't load".
     */
    @Test
    fun aLivenessReadThatCannotAnswerFailsTheReadInsteadOfHidingOrShowingEveryone() = runTest {
        val device = twoPeople()

        // Every `File` row goes from under the file service — the root included — while the consent
        // rows stay, so the read gets as far as the filter. Without the root check this answered
        // "every photograph is dead": an empty People success.
        listOf("/", "/photos", "/Trash", "/photos/a1.jpg", "/photos/a2.jpg", "/photos/b1.jpg", "/photos/b2.jpg")
            .forEach { path -> device.objectStore.removeObject(device.fileRow(path).getDataIdHash()) }

        val people = device.inference.faceGroups(10)
        assertTrue(people is TResult.Failure<*>, "People must fail, not answer: $people")
        val peopleError = (people as TResult.Failure<*>).error
        assertFalse(
            peopleError is org.tenshu.inference.FaceGroupsUnavailable,
            "not a named refusal either — NOT_INDEXED would tell the user to wait: $peopleError",
        )
        val search = device.inference.photosLike("anna", 10)
        assertTrue(search is TResult.Failure<*>, "search must fail, not answer: $search")
        val searchError = (search as TResult.Failure<*>).error
        assertFalse(searchError is SemanticSearchUnavailable, "not a named refusal either: $searchError")
    }

    // ---- B1 through the production source ------------------------------------------------------

    @Test
    fun theProductionSourceReportsACompleteWalkAndThePassErasesADeletedPhoto() = runTest {
        val device = twoPeople()
        val walk = device.source.walk(device.control)
        assertNotNull(walk.wholeLibrary, "a fully readable tree must be vouched for — else B1 never runs")
        assertEquals(4, walk.media.size)

        device.delete("/photos/b2.jpg")
        val result = device.pass()

        assertEquals(1, result.staleFacesRemoved, "the template was erased by the reconcile")
        assertEquals(1, result.staleVectorsRemoved, "the vector was erased by the reconcile")
        assertFalse(faces.visited("/photos/b2.jpg"))
        assertFalse(embeddings.visited("/photos/b2.jpg"))
        assertFalse(
            stores.readBytes("/inference/faces.log")!!.get().decodeToString(throwOnInvalidSequence = false)
                .contains("/photos/b2.jpg"),
            "gone from the disk, not masked",
        )
        assertTrue(faces.visited("/photos/a1.jpg") && embeddings.visited("/photos/a1.jpg"), "the rest stays")
    }

    @Test
    fun aWalkThatCouldNotReadTheWholeTreeIsPartialAndErasesNothing() = runTest {
        val device = twoPeople()
        device.delete("/photos/b2.jpg")

        // A folder whose list names a member whose row is not here — a device mid-sync. ⚠ Removed
        // from the raw store, under no observer, so the folder's list still names it.
        device.objectStore.removeObject(device.fileRow("/photos/a1.jpg").getDataIdHash())

        val walk = device.source.walk(device.control)
        assertEquals(null, walk.wholeLibrary, "an unreadable member makes the walk partial")

        val result = device.pass()
        assertEquals(0, result.staleFacesRemoved, "a partial walk reconciles nothing")
        assertTrue(faces.visited("/photos/b2.jpg"), "not erased — but still hidden by the read filter")
    }

    private fun Device.fileRow(path: String) = org.tenshu.core.entity.DataId.StringDataId.create(
        context = proxy.tContext,
        type = org.tenshu.core.file.File.ID,
        keyValue = path,
    )

    private suspend fun Device.hits(text: String): List<String> {
        val result = inference.photosLike(text, 10)
        assertTrue(result is TResult.Success, "photosLike failed: $result")
        return result.value!!.toList().filterIsInstance<SimilarPhoto>().map { it.filePath.get() }
    }
}
