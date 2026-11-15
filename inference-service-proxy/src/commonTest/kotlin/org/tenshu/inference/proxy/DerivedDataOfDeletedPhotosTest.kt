package org.tenshu.inference.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.get
import org.tenshu.inference.GroupedFace
import org.tenshu.inference.INDEX_MARKER_LABEL
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.InferenceService
import org.tenshu.inference.MediaLabel
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.SemanticSearchUnavailable
import org.tenshu.inference.SimilarPhoto
import org.tenshu.inference.client.inferenceServiceClientModule
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.inference.faceGroups
import org.tenshu.inference.nameFaceGroup
import org.tenshu.inference.photosLike
import org.tenshu.inference.proxy.vocabulary.LabelTier
import org.tenshu.inference.proxy.vocabulary.VocabularyLabel
import org.tenshu.inference.proxy.vocabulary.vocabularyOf
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **A deleted, trashed or sync-deleted photograph stops shaping People, and its derived data
 * goes** (`media-processing-pipeline-sweep.md` F0).
 *
 * ⚠⚠ No delete path touches the device-local stores, and `faceGroups` clusters every row they
 * hold. Without the liveness filter and the reconcile, a deleted photograph's biometric template
 * keeps pulling its person's centroid, counting toward `minFaces` and standing as a cover until
 * consent is withdrawn, the model changes or the realm is wiped.
 *
 * ⭐ The stores are the **durable** ones over a file database, because the claim is about the disk:
 * a tombstone that leaves the 512 floats in the log is not a deletion. The realm half is a stand-in
 * ([Realm]) applying `livePathsAmong`'s rule — exists, and not under `/Trash/` — whose real
 * implementation is pinned against a real file service in `:file-service-proxy`'s `LivePhotoPathsTest`;
 * this module depends on no file service, by design.
 */
class DerivedDataOfDeletedPhotosTest {

    private val thisDevice = "device-a"

    private val calibrated = FaceClustering.Calibrated(
        threshold = FaceSimilarityThreshold(0.9f),
        minFaces = 2,
    )

    /** What the realm holds, as far as liveness can tell: a path, and what the photograph shows. */
    private class Realm(vararg photos: Pair<String, String>) {
        val content = linkedMapOf(*photos)

        fun delete(path: String) {
            content.remove(path)
        }

        fun trash(path: String) {
            val shows = content.remove(path) ?: error("no $path")
            content["/Trash$path"] = shows
        }

        /** `livePathsAmong`'s rule. ⚠ Records every chunk it is asked, so boundedness is assertable. */
        val asked = mutableListOf<Int>()
        val livePaths = LivePaths { _, paths ->
            asked += paths.size
            paths.filterTo(HashSet()) { it in content && !it.startsWith("/Trash/") }
        }

        /** Set when the walk could not read part of the tree — `walkImageLibrary`'s `complete`. */
        var walkComplete = true

        /**
         * Photographs whose row exists but which the walk does not reach — what a `MoveFile` in
         * flight looks like: unlinked from the old parent, destination not yet saved.
         */
        val unlinked = mutableSetOf<String>()

        fun restore(path: String) {
            content[path] = content.remove("/Trash$path") ?: error("$path is not in the trash")
        }

        /** `walkImageLibrary`'s rule: every image, trashed ones skipped, and whether it saw it all. */
        val source = object : MediaSource {
            override suspend fun media(executionContext: org.tenshu.core.common.proxy.ExecutionContext) =
                content.filterKeys { !it.startsWith("/Trash/") && it !in unlinked }.map { (path, shows) ->
                    IndexableMedia(path) { shows.encodeToByteArray() }
                }

            override suspend fun walk(executionContext: org.tenshu.core.common.proxy.ExecutionContext) =
                if (walkComplete) {
                    MediaWalk.complete(media(executionContext), livePaths)
                } else {
                    MediaWalk.partial(media(executionContext))
                }
        }
    }

    /** [source]'s list, vouched for as complete, with [stillLive] as the reconcile's second proof. */
    private fun vouching(source: MediaSource, stillLive: LivePaths) = object : MediaSource {
        override suspend fun media(executionContext: org.tenshu.core.common.proxy.ExecutionContext) =
            source.media(executionContext)

        override suspend fun walk(executionContext: org.tenshu.core.common.proxy.ExecutionContext) =
            MediaWalk.complete(media(executionContext), stillLive)
    }

    private val files = InMemoryFileDatabase()
    private val faces = DefaultFaceTemplateStore(files, FACES)
    private val embeddings = DefaultEmbeddingStore(files, VECTORS)

    private fun twoPeople() = Realm(
        "/photos/a1.jpg" to "anna",
        "/photos/a2.jpg" to "anna",
        "/photos/b1.jpg" to "bob",
        "/photos/b2.jpg" to "bob",
    )

    private fun proxy(realm: Realm): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
            registerConfig(inferenceServiceClientModule)
        },
        implementationFactoryOverride = { id, _ ->
            inferenceServiceProxyImplementationFactory(
                id = id,
                registry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
                embeddings = embeddings,
                deviceId = thisDevice,
                faceGrouping = FaceGroupingComponents(faces, calibrated, realm.livePaths),
            )
        },
    ).apply {
        consentTo(InferenceCapability.FACE_CLUSTERING)
        consentTo(InferenceCapability.SEMANTIC_IMAGE_SEARCH)
    }

    private fun indexer(backend: InferenceBackend = FakeInferenceBackend()) = MediaIndexer(
        registry = InferenceBackendRegistry(listOf(backend)),
        deviceId = { thisDevice },
        embeddings = embeddings,
        faces = faces,
        conditions = IndexingConditions { true },
    )

    /** ⚠ Unconfined for `MediaIndexRunnerTest`'s reason: `backgroundScope` never runs the pass body. */
    private fun TestScope.runner(
        realm: Realm,
        source: MediaSource = realm.source,
        indexer: MediaIndexer = indexer(),
    ) = MediaIndexRunner(
        indexer = indexer,
        source = source,
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()),
    )

    private fun service(proxy: TestProxy): InferenceService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        )
        return env.getService(InferenceService.ID)
    }

    private suspend fun InferenceService.people(): Map<String, Set<String>> =
        ((faceGroups(10) as TResult.Success<TList<GroupedFace>>).value!!.toList())
            .filterIsInstance<GroupedFace>()
            .groupBy({ it.groupId.get() }, { it.filePath.get() })
            .mapValues { it.value.toSet() }

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

    private fun TestProxy.consentTo(capability: InferenceCapability) {
        write(capability.realmConsent, true)
        write(capability.deviceConsent, true, deviceId = thisDevice)
    }

    private fun TestProxy.storedName(filePath: String, name: String): Any? =
        executionContext.database.getData(
            DataId.StringDataId.create(
                context = tContext,
                type = MediaLabel.ID,
                keyValue = MediaLabel.labelKey(filePath, InferenceCapability.FACE_CLUSTERING, name),
            ),
        )

    private fun ByteArray.contains(text: String): Boolean =
        decodeToString(throwOnInvalidSequence = false).contains(text)

    private fun log(path: String): ByteArray = files.readBytes(path)!!.get()

    // ---- the read side: People stops counting a gone photograph on the very next read ----------

    @Test
    fun aDeletedPhotoStopsShapingPeopleWithNoNewPass() = runTest {
        val realm = twoPeople()
        val proxy = proxy(realm)
        runner(realm).runToCompletion(proxy.executionContext)
        val service = service(proxy)
        assertEquals(
            setOf(setOf("/photos/a1.jpg", "/photos/a2.jpg"), setOf("/photos/b1.jpg", "/photos/b2.jpg")),
            service.people().values.toSet(),
            "precondition: two people of two photographs each",
        )

        realm.delete("/photos/b2.jpg")

        // ⚠⚠ Bob had exactly `minFaces` photographs. Counting the deleted one keeps him a person;
        // not counting it leaves one face, below the bar — so his group's absence IS the proof the
        // template no longer counts, not merely that a path was filtered from the answer.
        assertEquals(setOf(setOf("/photos/a1.jpg", "/photos/a2.jpg")), service.people().values.toSet())
        assertTrue(faces.visited("/photos/b2.jpg"), "no pass ran: the store still holds it, the read dropped it")
    }

    @Test
    fun aTrashedPhotoStopsShapingPeopleWithNoNewPass() = runTest {
        val realm = twoPeople()
        val proxy = proxy(realm)
        runner(realm).runToCompletion(proxy.executionContext)
        val service = service(proxy)

        realm.trash("/photos/b2.jpg")

        assertEquals(setOf(setOf("/photos/a1.jpg", "/photos/a2.jpg")), service.people().values.toSet())
        assertTrue(faces.visited("/photos/b2.jpg"), "no pass ran")
    }

    @Test
    fun theLivenessLookupIsAskedInBoundedChunks() = runTest {
        val realm = Realm()
        repeat(450) { index ->
            realm.content["/photos/p$index.jpg"] = "anna"
            faces.put("/photos/p$index.jpg", listOf(face()), ModelProvenance.Fake)
        }
        val service = service(proxy(realm))

        assertEquals(450, service.people().values.single().size)
        // ⚠⚠ Never one question the size of the library: the liveness read is batched.
        assertEquals(listOf(200, 200, 50), realm.asked)
    }

    @Test
    fun aDeletedPhotoIsNotRankedBySearch() = runTest {
        val realm = twoPeople()
        val proxy = proxy(realm)
        runner(realm).runToCompletion(proxy.executionContext)
        val service = service(proxy)

        realm.delete("/photos/a1.jpg")

        // The fake embeds text and image bytes in one space, so "anna" matches both anna photographs
        // exactly; only the live one may come back.
        val hits = (service.photosLike("anna", limit = 10) as TResult.Success).value!!.toList()
            .filterIsInstance<SimilarPhoto>()
            .map { it.filePath.get() }
        assertEquals("/photos/a2.jpg", hits.first())
        assertFalse("/photos/a1.jpg" in hits, "a deleted photograph's vector must not be ranked: $hits")
    }

    // ---- the reconcile: the next pass removes what the walk no longer offers --------------------

    @Test
    fun aDeleteThatArrivedBySyncIsRemovedFromBothStoresAtTheNextPass() = runTest {
        val realm = twoPeople()
        val proxy = proxy(realm)
        val runner = runner(realm)
        runner.runToCompletion(proxy.executionContext)
        assertTrue(log(FACES).contains("/photos/b2.jpg"), "precondition")

        // A peer deleted it: the row simply vanished from this realm. Nothing local called anything.
        realm.delete("/photos/b2.jpg")
        val result = runner.runToCompletion(proxy.executionContext)

        assertEquals(1, result.staleFacesRemoved)
        assertEquals(1, result.staleVectorsRemoved)
        assertFalse(faces.visited("/photos/b2.jpg"))
        assertFalse(embeddings.visited("/photos/b2.jpg"))
        assertTrue(faces.visited("/photos/a1.jpg") && embeddings.visited("/photos/a1.jpg"), "the rest stays")
        // ⚠⚠ Gone from the DISK, not hidden behind a tombstone: the bytes no longer name it.
        assertFalse(log(FACES).contains("/photos/b2.jpg"), "template bytes left behind")
        assertFalse(log(VECTORS).contains("/photos/b2.jpg"), "vector bytes left behind")
        // …and a reopen agrees: a struck record is stepped over, and the rows after it still parse.
        val reopened = DefaultFaceTemplateStore(files, FACES)
        assertEquals(
            setOf("/photos/a1.jpg", "/photos/a2.jpg", "/photos/b1.jpg"),
            reopened.rows().map { it.filePath }.toSet(),
        )
        assertEquals(3, DefaultEmbeddingStore(files, VECTORS).size())
    }

    @Test
    fun aTrashedPhotoIsRemovedFromBothStoresAtTheNextPass() = runTest {
        val realm = twoPeople()
        val proxy = proxy(realm)
        val runner = runner(realm)
        runner.runToCompletion(proxy.executionContext)

        realm.trash("/photos/b2.jpg")
        runner.runToCompletion(proxy.executionContext)

        assertFalse(faces.visited("/photos/b2.jpg"))
        assertFalse(embeddings.visited("/photos/b2.jpg"))
    }

    @Test
    fun theStoreKeepsWorkingAfterAReconcile() = runTest {
        val realm = twoPeople()
        val proxy = proxy(realm)
        val runner = runner(realm)
        runner.runToCompletion(proxy.executionContext)
        realm.delete("/photos/b2.jpg")
        runner.runToCompletion(proxy.executionContext)

        // ⚠ A removal changes no length, so the append offset still names the end of the log — an
        // append landing anywhere else would be lost, or would overwrite a live row, on the next start.
        realm.content["/photos/c1.jpg"] = "bob"
        runner.runToCompletion(proxy.executionContext)

        assertTrue(DefaultFaceTemplateStore(files, FACES).visited("/photos/c1.jpg"))
        assertTrue(DefaultEmbeddingStore(files, VECTORS).visited("/photos/c1.jpg"))
    }

    @Test
    fun aWalkThatThrowsRemovesNothing() = runTest {
        val realm = twoPeople()
        val proxy = proxy(realm)
        runner(realm).runToCompletion(proxy.executionContext)

        val broken = MediaSource { _ -> throw IllegalStateException("store unreadable") }
        assertFailsWith<IllegalStateException> { runner(realm, broken).runToCompletion(proxy.executionContext) }

        // ⚠⚠ A failed read is not "the library is empty".
        assertEquals(4, faces.rows().size)
        assertEquals(4, embeddings.size())
    }

    /**
     * ⚠⚠ A walk that could not read part of the tree — a device mid-sync — lists fewer photographs
     * than the user has. Reconciling against it would erase derived data of photographs that still
     * exist, so it removes nothing; the next complete walk does the work.
     */
    @Test
    fun aPartialWalkRemovesNothing() = runTest {
        val realm = twoPeople()
        val proxy = proxy(realm)
        val runner = runner(realm)
        runner.runToCompletion(proxy.executionContext)

        realm.delete("/photos/b2.jpg")
        realm.walkComplete = false
        val result = runner.runToCompletion(proxy.executionContext)

        assertEquals(0, result.staleFacesRemoved)
        assertTrue(faces.visited("/photos/b2.jpg") && embeddings.visited("/photos/b2.jpg"))

        realm.walkComplete = true
        runner.runToCompletion(proxy.executionContext)
        assertFalse(faces.visited("/photos/b2.jpg"), "the next complete walk removes it")
    }

    /** A source that cannot vouch for its list — a bare lambda — never deletes by absence. */
    @Test
    fun aSourceThatCannotVouchForCompletenessRemovesNothing() = runTest {
        val realm = twoPeople()
        val proxy = proxy(realm)
        runner(realm).runToCompletion(proxy.executionContext)
        realm.delete("/photos/b2.jpg")

        val unvouched = MediaSource { context -> realm.source.media(context) }
        runner(realm, unvouched).runToCompletion(proxy.executionContext)

        assertTrue(faces.visited("/photos/b2.jpg"))
    }

    @Test
    fun aPartialListNeverReconciles() = runTest {
        val realm = twoPeople()
        val proxy = proxy(realm)
        runner(realm).runToCompletion(proxy.executionContext)

        // ⚠⚠ A caller indexing one file must not be read as "the library is now one file".
        indexer().index(proxy.executionContext, listOf(IndexableMedia("/photos/a1.jpg") { "anna".encodeToByteArray() }))

        assertEquals(4, faces.rows().size)
        assertEquals(4, embeddings.size())
    }

    // ---- D13: what a person typed is not derived data -------------------------------------------

    @Test
    fun aTypedNameSurvivesTheReadFilterAndTheReconcile() = runTest {
        val realm = twoPeople()
        val proxy = proxy(realm)
        val runner = runner(realm)
        runner.runToCompletion(proxy.executionContext)
        val service = service(proxy)
        service.nameFaceGroup(listOf("/photos/a1.jpg", "/photos/a2.jpg"), "Anna")
        service.nameFaceGroup(listOf("/photos/b1.jpg", "/photos/b2.jpg"), "Bob")

        realm.delete("/photos/b2.jpg")
        service.people()
        runner.runToCompletion(proxy.executionContext)

        assertNotNull(proxy.storedName("/photos/a1.jpg", "Anna"), "a remaining person's name")
        assertNotNull(proxy.storedName("/photos/a2.jpg", "Anna"))
        // ⚠ D13 = keep: P0f-a removes templates and vectors only. Even the name typed on the photo
        // that went stays — deleting something a person typed is the irreversible direction.
        assertNotNull(proxy.storedName("/photos/b1.jpg", "Bob"))
        assertNotNull(proxy.storedName("/photos/b2.jpg", "Bob"))
        assertEquals(setOf(setOf("/photos/a1.jpg", "/photos/a2.jpg")), service.people().values.toSet())
    }

    // ---- constraint 0: a visit that derived nothing is remembered, so it is reconciled too --------

    /** A backend that cannot decode [UNREADABLE], and records every image it was asked to embed. */
    private class Picky(private val inner: InferenceBackend = FakeInferenceBackend()) : InferenceBackend by inner {
        val asked = mutableListOf<String>()

        override suspend fun embedImageOutcome(bytes: ByteArray): ImageEmbeddingOutcome {
            val shows = bytes.decodeToString()
            asked += shows
            return if (shows == UNREADABLE) ImageEmbeddingOutcome.Unreadable else inner.embedImageOutcome(bytes)
        }
    }

    /**
     * ⚠⚠ A photograph this device could not decode leaves no vector and no face — only the record
     * that it was looked at. A reconcile that enumerated vectors could never see that record, so it
     * would outlive its file (hard constraint 0).
     */
    @Test
    fun aDeletedPathHoldingOnlyAVisitIsGone() = runTest {
        val realm = Realm("/photos/a1.jpg" to "anna", BROKEN to UNREADABLE)
        val proxy = proxy(realm)
        val runner = runner(realm, indexer = indexer(Picky()))
        runner.runToCompletion(proxy.executionContext)
        assertTrue(embeddings.visited(BROKEN), "precondition: remembered")
        assertFalse(embeddings.entries().any { it.first == BROKEN }, "precondition: as a visit, with no vector")
        assertTrue(faces.visited(BROKEN) && faces.facesOf(BROKEN).isEmpty(), "precondition: an empty face row")

        realm.delete(BROKEN)
        val result = runner.runToCompletion(proxy.executionContext)

        assertFalse(embeddings.visited(BROKEN), "the vectorless visit outlived its file")
        assertFalse(faces.visited(BROKEN), "the empty face row outlived its file")
        assertEquals(1, result.staleVectorsRemoved)
        assertEquals(1, result.staleFacesRemoved)
        assertFalse(log(VECTORS).contains(BROKEN), "the visit's path is still in the vector log")
        assertFalse(log(FACES).contains(BROKEN), "the empty row's path is still in the face log")
        assertFalse(DefaultEmbeddingStore(files, VECTORS).visited(BROKEN), "and a reopen agrees")
        assertFalse(DefaultFaceTemplateStore(files, FACES).visited(BROKEN))
    }

    /**
     * ⚠⚠ The cost of a visit that outlives its file: whatever sits at that path later is skipped for
     * as long as the model stays. Trashing drops the visit, so the restored photograph is looked at
     * again — it may be readable now, or be a different file.
     */
    @Test
    fun anUnreadablePhotoTrashedAndRestoredIsVisitedAgain() = runTest {
        val realm = Realm("/photos/a1.jpg" to "anna", BROKEN to UNREADABLE)
        val proxy = proxy(realm)
        val picky = Picky()
        val runner = runner(realm, indexer = indexer(picky))
        runner.runToCompletion(proxy.executionContext)
        runner.runToCompletion(proxy.executionContext)
        assertEquals(1, picky.asked.count { it == UNREADABLE }, "precondition: a remembered visit is not re-read")

        realm.trash(BROKEN)
        runner.runToCompletion(proxy.executionContext)
        assertFalse(embeddings.visited(BROKEN) || faces.visited(BROKEN), "trashed: the visit goes")

        realm.restore(BROKEN)
        runner.runToCompletion(proxy.executionContext)

        assertEquals(2, picky.asked.count { it == UNREADABLE }, "the restored photograph was never looked at again")
        assertTrue(embeddings.visited(BROKEN) && faces.visited(BROKEN))
    }

    // ---- the second proof: absent from the walk is not yet dead --------------------------------

    /**
     * ⚠⚠ A walk is not a snapshot. `MoveFile` unlinks its source from the old parent before it
     * saves the destination, so a walk overlapping a move misses a photograph whose row still
     * exists — with nothing unreadable to show for it. The point read is what spares it.
     */
    @Test
    fun aPhotographAMoveUnlinkedFromTheTreeIsSparedBecauseItsRowStillExists() = runTest {
        val realm = twoPeople()
        val proxy = proxy(realm)
        val runner = runner(realm)
        runner.runToCompletion(proxy.executionContext)

        realm.unlinked += "/photos/b2.jpg"
        val during = runner.runToCompletion(proxy.executionContext)

        assertEquals(0, during.staleFacesRemoved)
        assertEquals(0, during.staleVectorsRemoved)
        assertTrue(faces.visited("/photos/b2.jpg") && embeddings.visited("/photos/b2.jpg"), "a live photograph's data was erased")

        // The move lands: the old path's row is retired, and the photograph is at its new one.
        realm.unlinked -= "/photos/b2.jpg"
        realm.delete("/photos/b2.jpg")
        realm.content["/album/b2.jpg"] = "bob"
        val after = runner.runToCompletion(proxy.executionContext)

        assertEquals(1, after.staleFacesRemoved)
        assertFalse(faces.visited("/photos/b2.jpg"))
        assertTrue(faces.visited("/album/b2.jpg") && embeddings.visited("/album/b2.jpg"))
    }

    /** ⚠⚠ A reconcile that cannot get its second proof does nothing that pass. */
    @Test
    fun aLivenessReadThatFailsRemovesNothing() = runTest {
        val realm = twoPeople()
        val proxy = proxy(realm)
        runner(realm).runToCompletion(proxy.executionContext)
        realm.delete("/photos/b2.jpg")

        val cannotRead = vouching(realm.source) { _, _ -> error("store locked") }
        val result = runner(realm, cannotRead).runToCompletion(proxy.executionContext)

        assertEquals(0, result.staleFacesRemoved)
        assertEquals(0, result.staleVectorsRemoved)
        assertTrue(faces.visited("/photos/b2.jpg") && embeddings.visited("/photos/b2.jpg"))
    }

    /**
     * ⚠⚠ The point read is not a health check: a store that is locked or mid-wipe can answer "no
     * such row" for everything. So it is asked only about what a complete walk did not return — and
     * here, where the walk returned everything, it is not asked at all.
     */
    @Test
    fun aLivenessReadThatFindsNothingCannotTouchWhatTheWalkReturned() = runTest {
        val realm = twoPeople()
        val proxy = proxy(realm)
        runner(realm).runToCompletion(proxy.executionContext)

        var asked = 0
        val blind = vouching(realm.source) { _, _ ->
            asked++
            emptySet()
        }
        val result = runner(realm, blind).runToCompletion(proxy.executionContext)

        assertEquals(0, result.staleFacesRemoved)
        assertEquals(0, asked, "nothing was absent, so there was nothing to ask about")
        assertEquals(4, faces.rows().size)
        assertEquals(4, embeddings.size())
    }

    /**
     * ⚠⚠ One reconcile: a photograph is removed, the next one's strike is refused, and the bytes of
     * the first cannot be erased either. The pass must still count the row that went — reporting 0
     * over a removed row whose template is on disk is the report A40 forbids.
     */
    @Test
    fun aPassCountsTheRowsItRemovedEvenWhenAnotherIsRefusedAndTheErasureFails() = runTest {
        val disk = CrashingFiles()
        val flakyFaces = DefaultFaceTemplateStore(disk, FACES)
        val realm = twoPeople()
        val proxy = proxy(realm)
        val indexer = MediaIndexer(
            registry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
            deviceId = { thisDevice },
            embeddings = embeddings,
            faces = flakyFaces,
            conditions = IndexingConditions { true },
        )
        val runner = runner(realm, indexer = indexer)
        runner.runToCompletion(proxy.executionContext)
        realm.delete("/photos/b1.jpg")
        realm.delete("/photos/b2.jpg")

        // The first dead photograph is struck; the second strike fails, and so does every write after.
        disk.arm(call = 1, CrashingFiles.Kill.FAILS, thenWritesFail = true)
        val result = runner.runToCompletion(proxy.executionContext)

        assertEquals(1, result.staleFacesRemoved, "a removed row was reported as not removed")
        assertEquals(1, flakyFaces.recordsNotErased(), "its bytes are still owed, and still queued")
        assertEquals(1, listOf("/photos/b1.jpg", "/photos/b2.jpg").count { flakyFaces.visited(it) }, "the refused one stays")

        disk.watch()
        val next = runner.runToCompletion(proxy.executionContext)
        assertEquals(1, next.staleFacesRemoved)
        assertEquals(0, flakyFaces.recordsNotErased())
    }

    // ---- P0f-a deletes no `MediaLabel` row -------------------------------------------------------

    /**
     * ⚠⚠ The reconcile touches the two device-local stores and nothing in the realm. Label deletion
     * on a permanent delete is P0f-b's hook (D13): a row deleted here would not even reach a peer,
     * and a typed name must never be deleted on the strength of "absent from my walk".
     */
    @Test
    fun theReconcileDeletesNoRealmRowNotEvenAModelProducedLabel() = runTest {
        val realm = twoPeople()
        val proxy = proxy(realm).apply { consentTo(InferenceCapability.CONTENT_TAGGING) }
        val tagging = MediaIndexer(
            registry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
            deviceId = { thisDevice },
            vocabulary = vocabularyOf(version = 1, labels = listOf(VocabularyLabel("cat", LabelTier.TOP, floor = 0.5f))),
            embeddings = embeddings,
            faces = faces,
            conditions = IndexingConditions { true },
        )
        suspend fun pass(): IndexPassResult {
            val walk = realm.source.walk(proxy.executionContext)
            return tagging.index(
                proxy.executionContext,
                walk.media,
                InferenceCapability.CONTENT_TAGGING,
                wholeLibrary = walk.wholeLibrary,
            )
        }
        val tagged = pass()
        assertTrue(faces.visited("/photos/b2.jpg") && embeddings.visited("/photos/b2.jpg"), "precondition: $tagged")
        fun label(name: String) = proxy.executionContext.database.getData(
            DataId.StringDataId.create(
                context = proxy.tContext,
                type = MediaLabel.ID,
                keyValue = MediaLabel.labelKey("/photos/b2.jpg", InferenceCapability.CONTENT_TAGGING, name),
            ),
        )
        assertNotNull(label("cat"), "precondition: a model-produced label — $tagged")
        assertNotNull(label(INDEX_MARKER_LABEL), "precondition: its marker")

        val deleted = mutableListOf<DataId>()
        proxy.executionContext.addDataObserver(object : DataObserver {
            override fun onDataSaved(data: Data, origin: WriteOrigin) = Unit
            override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
                deleted += dataId
            }
        })
        realm.delete("/photos/b2.jpg")
        val result = pass()

        assertEquals(1, result.staleFacesRemoved, "precondition: the reconcile ran")
        assertEquals(1, result.staleVectorsRemoved)
        assertEquals(emptyList(), deleted, "the reconcile deleted a realm row")
        assertNotNull(label("cat"))
        assertNotNull(label(INDEX_MARKER_LABEL))
    }

    // ---- the read filter and an empty answer ------------------------------------------------------


    @Test
    fun searchOverOnlyDeletedPhotosIsNotIndexedRatherThanNothingMatched() = runTest {
        val realm = Realm("/photos/a1.jpg" to "anna")
        val proxy = proxy(realm)
        runner(realm).runToCompletion(proxy.executionContext)
        realm.delete("/photos/a1.jpg")

        val result = service(proxy).photosLike("anna", limit = 10)

        // ⚠ The store is not empty, but nothing in it is searchable: an empty success here would
        // tell the user "no photo of that" when the truth is "nothing live has been indexed yet".
        val error = (result as? TResult.Failure)?.error as? SemanticSearchUnavailable
        assertEquals(SemanticSearchUnavailable.Reasons.NOT_INDEXED, error?.reason?.get(), "got $result")
    }

    private fun face() = StoredFace(
        box = FaceBox(left = 0.1f, top = 0.1f, right = 0.4f, bottom = 0.4f),
        vector = floatArrayOf(1f, 0f, 0f, 0f),
    )

    private companion object {
        const val FACES = "inference/faces.log"
        const val VECTORS = "inference/vectors.log"
        const val BROKEN = "/photos/broken.jpg"

        /** Bytes `FakeInferenceBackend` cannot decode as a face photograph, nor [Picky] as an image. */
        const val UNREADABLE = "not-an-image"
    }
}
