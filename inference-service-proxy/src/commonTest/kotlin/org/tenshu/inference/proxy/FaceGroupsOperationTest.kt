package org.tenshu.inference.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.Entity
import org.tenshu.core.get
import org.tenshu.inference.FaceGroupsUnavailable
import org.tenshu.inference.GroupedFace
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.InferenceService
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.client.inferenceServiceClientModule
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.inference.faceGroups
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `faceGroups` end-to-end over the op wire — RO → param map → proxy grouping → returned composites,
 * and every refusal path.
 *
 * ⭐⭐ **No model, no runtime, no hardware**, exactly as `PhotosLikeOperationTest` establishes for
 * its sibling. The grouping is real: real templates in a real store, the real `clusterFaces`, and
 * the real op surface. With a real checkpoint, only where the vectors come from changes.
 *
 * ⚠ The vectors here are hand-written orthogonal units rather than a backend's output, because the
 * question these tests ask is *"does the op group what the maths says is one person"* — a fixture
 * whose similarities were themselves produced by a model would be testing the model.
 */
class FaceGroupsOperationTest {

    private val thisDevice = "device-a"
    private val capability = InferenceCapability.FACE_CLUSTERING

    /** ⚠ Unit vectors: `clusterFaces` compares by cosine and assumes normalised input. */
    private val anna = floatArrayOf(1f, 0f, 0f, 0f)
    private val bob = floatArrayOf(0f, 1f, 0f, 0f)

    private val calibrated = FaceClustering.Calibrated(
        threshold = FaceSimilarityThreshold(0.9f),
        minFaces = 2,
    )

    private fun proxy(
        store: FaceTemplateStore,
        clustering: FaceClustering = calibrated,
        registry: InferenceBackendRegistry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
    ): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
            registerConfig(inferenceServiceClientModule)
        },
        implementationFactoryOverride = { id, _ ->
            inferenceServiceProxyImplementationFactory(
                id = id,
                registry = registry,
                embeddings = InMemoryEmbeddingStore(),
                deviceId = thisDevice,
                faceGrouping = FaceGroupingComponents(store, clustering),
                storeGate = storeGate,
            )
        },
    )

    /** The store gate; `null` is every graph but the one that stamps its stores. */
    private var storeGate: (suspend () -> Boolean)? = null

    /** Counts every call that reads a template or the store's stamp. */
    private class ReadCounting(private val inner: FaceTemplateStore) : FaceTemplateStore by inner {
        var reads = 0
        override suspend fun rows(): List<FaceRow> = inner.rows().also { reads++ }
        override suspend fun facesOf(filePath: String): List<StoredFace> = inner.facesOf(filePath).also { reads++ }
        override suspend fun provenance(): ModelProvenance? = inner.provenance().also { reads++ }
        override suspend fun size(): Int = inner.size().also { reads++ }
        override suspend fun remembered(): Set<String> = inner.remembered().also { reads++ }
        override suspend fun visited(filePath: String): Boolean = inner.visited(filePath).also { reads++ }
    }

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

    private fun TestProxy.consent() {
        write(capability.realmConsent, true)
        write(capability.deviceConsent, true, deviceId = thisDevice)
    }

    /**
     * Three photographs of one person and two of another.
     *
     * ⚠ The sizes are deliberately UNEQUAL (3 vs 2) so "largest group first" and the group-wise cap
     * are both observable; with two groups of two, an implementation that ignored size would pass.
     */
    private suspend fun twoPeople(): FaceTemplateStore = InMemoryFaceTemplateStore().apply {
        listOf("a1", "a2", "a3").forEach { put("/photos/$it.jpg", listOf(face(anna)), ModelProvenance.Fake) }
        listOf("b1", "b2").forEach { put("/photos/$it.jpg", listOf(face(bob)), ModelProvenance.Fake) }
    }

    private fun face(vector: FloatArray) = StoredFace(
        box = FaceBox(left = 0.1f, top = 0.1f, right = 0.4f, bottom = 0.4f),
        vector = vector,
    )

    private suspend fun InferenceService.groups(limit: Int = 10) = faceGroups(limit)

    private fun TResult<TList<GroupedFace>>.rows(): List<GroupedFace> =
        (this as TResult.Success<TList<GroupedFace>>).value!!.toList().filterIsInstance<GroupedFace>()

    private fun TResult<*>.errorReason(): String {
        val failure = this as? TResult.Failure ?: error("expected a failure, got $this")
        val error = failure.error as? FaceGroupsUnavailable
            ?: error("expected FaceGroupsUnavailable, got ${failure.error}")
        return error.reason.get()
    }

    @Test
    fun facesTheMathsCallsOnePersonComeBackUnderOneGroupId() = runTest {
        val service = service(proxy(twoPeople()).apply { consent() })

        val byGroup = service.groups().rows().groupBy({ it.groupId.get() }, { it.filePath.get() })

        assertEquals(2, byGroup.size)
        // ⚠ Sets, not lists: the claim is membership. Asserting order here would pin
        // `clusterFaces`'s internal sort, which no caller depends on.
        assertEquals(
            setOf(
                setOf("/photos/a1.jpg", "/photos/a2.jpg", "/photos/a3.jpg"),
                setOf("/photos/b1.jpg", "/photos/b2.jpg"),
            ),
            byGroup.values.map { it.toSet() }.toSet(),
        )
    }

    @Test
    fun everyMemberOfAGroupCarriesTheSameId() = runTest {
        val service = service(proxy(twoPeople()).apply { consent() })

        val rows = service.groups().rows()

        // Two people, five photographs, so exactly two distinct ids across five rows. An
        // implementation that minted an id per ROW would return five and still look grouped.
        assertEquals(5, rows.size)
        assertEquals(2, rows.map { it.groupId.get() }.distinct().size)
    }

    @Test
    fun theCapDropsWholeGroupsNeverPartOfOne() = runTest {
        val service = service(proxy(twoPeople()).apply { consent() })

        val rows = service.groups(limit = 1).rows()

        // ⚠⚠ THREE rows for a limit of one, and that is the assertion. `take(limit)` applied to
        // rows instead of groups returns a single face — a "person" with one photograph — which is
        // both wrong and plausible-looking.
        assertEquals(3, rows.size)
        assertEquals(1, rows.map { it.groupId.get() }.distinct().size)
        assertTrue(rows.all { it.filePath.get().contains("/a") })
    }

    @Test
    fun withoutCalibrationItSaysSoRatherThanReportingAnEmptyLibrary() = runTest {
        val service = service(
            proxy(twoPeople(), clustering = FaceClustering.Uncalibrated).apply { consent() },
        )

        // ⚠⚠ The reason this branch exists. `clusterFaces(rows, Uncalibrated)` returns an empty
        // list, so without the guard this call succeeds with zero groups over a store holding five
        // faces — "you are in none of your own photographs", stated confidently.
        assertEquals(FaceGroupsUnavailable.Reasons.UNCALIBRATED, service.groups().errorReason())
    }

    @Test
    fun withoutConsentItFailsRatherThanReturningNothing() = runTest {
        val service = service(proxy(twoPeople())) // ⚠ no consent written

        assertEquals(FaceGroupsUnavailable.Reasons.NO_CONSENT, service.groups().errorReason())
    }

    @Test
    fun consentWithdrawnAfterIndexingStillRefusesTheTemplatesOnDisk() = runTest {
        val store = twoPeople()
        val proxy = proxy(store).apply {
            consent()
            write(capability.deviceConsent, false, deviceId = thisDevice)
        }

        // ⚠⚠ The store is FULL here. Consent is checked before it is read, so withdrawing the
        // switch stops serving templates that a previous pass already wrote — a test that seeded an
        // empty store would pass on `NOT_INDEXED` and prove nothing about withdrawal.
        assertEquals(FaceGroupsUnavailable.Reasons.NO_CONSENT, service(proxy).groups().errorReason())
    }

    /**
     * ⚠⚠ Withdrawal DELETES — refusing to serve is not enough, because the templates are biometric
     * data the user asked to be rid of. Opening People after switching off is one of the two
     * places that happens (`MediaIndexer`'s pass is the other).
     */
    @Test
    fun consentWithdrawnDeletesTheTemplatesOnDisk() = runTest {
        val store = twoPeople()
        val proxy = proxy(store).apply {
            consent()
            write(capability.deviceConsent, false, deviceId = thisDevice)
        }

        service(proxy).groups()

        assertEquals(0, store.size())
        assertEquals(null, store.provenance())
    }

    /**
     * ⚠⚠ With the model gone the op refuses NO_BACKEND first — and the deletion must still happen,
     * or a user who deleted the model and then switched faces off keeps the templates the Settings
     * copy promised to remove.
     */
    @Test
    fun consentWithdrawnDeletesTheTemplatesEvenWithNoBackend() = runTest {
        val store = twoPeople()
        val proxy = proxy(
            store,
            registry = InferenceBackendRegistry(listOf(FakeInferenceBackend(capabilities = emptySet()))),
        ).apply {
            // ⚠ An EXPLICIT switch-off: only that deletes.
            write(capability.realmConsent, false)
        }

        assertEquals(FaceGroupsUnavailable.Reasons.NO_BACKEND, service(proxy).groups().errorReason())
        assertEquals(0, store.size())
    }

    /**
     * ⚠⚠ A read that finds NO consent row is not a switch-off. It refuses — absence is NO
     * for serving — but it must not destroy the templates.
     */
    @Test
    fun aMissingConsentRowRefusesButDeletesNothing() = runTest {
        val store = twoPeople()
        val proxy = proxy(store) // ⚠ no consent rows at all

        assertEquals(FaceGroupsUnavailable.Reasons.NO_CONSENT, service(proxy).groups().errorReason())
        assertEquals(5, store.size(), "absence is not withdrawal")
    }

    /** A fake that answers for its model, as `MediaPipeFaceBackend` does. */
    private class StampedBackend(
        inner: InferenceBackend,
        override val provenance: ModelProvenance,
    ) : InferenceBackend by inner, ModelBackedBackend

    /**
     * ⚠⚠ After an app update moves the pinned model, the old space's templates must not be
     * clustered under the new model — People says NOT_INDEXED until a pass rebuilds.
     */
    @Test
    fun anotherModelsTemplatesAreNeverServed() = runTest {
        val store = twoPeople() // stamped ModelProvenance.Fake
        val proxy = proxy(
            store,
            registry = InferenceBackendRegistry(
                listOf(StampedBackend(FakeInferenceBackend(), ModelProvenance("auraface-v1-int8", 2))),
            ),
        ).apply { consent() }

        assertEquals(FaceGroupsUnavailable.Reasons.NOT_INDEXED, service(proxy).groups().errorReason())
        assertEquals(0, store.size())
    }

    /** ⚠ The guard's other side: the same model's templates are served as before. */
    @Test
    fun theSameModelsTemplatesAreServed() = runTest {
        val store = twoPeople()
        val proxy = proxy(
            store,
            registry = InferenceBackendRegistry(listOf(StampedBackend(FakeInferenceBackend(), ModelProvenance.Fake))),
        ).apply { consent() }

        service(proxy).groups()

        assertEquals(5, store.size())
    }

    /**
     * ⚠⚠ The **ordering**, which the two tests above do not pin: both of them die to any mutation
     * that removes the consent check at all, so neither says *when* it runs. The implementation's
     * claim is stronger than "it refuses" — it is that no template is read before consent is
     * established, which for biometric data is the difference between a refusal and a disclosure
     * that happened to be discarded.
     */
    @Test
    fun withConsentOffNoTemplateIsEverRead() = runTest {
        val spy = RecordingFaceTemplateStore(twoPeople())
        val service = service(proxy(spy)) // ⚠ no consent written

        assertEquals(FaceGroupsUnavailable.Reasons.NO_CONSENT, service.groups().errorReason())
        assertEquals(0, spy.rowsReads, "templates were read despite consent being absent")
    }

    /** Counts reads of the templates themselves — not of the size, which discloses nothing. */
    private class RecordingFaceTemplateStore(
        private val delegate: FaceTemplateStore,
    ) : FaceTemplateStore by delegate {
        var rowsReads = 0
            private set

        override suspend fun rows(): List<FaceRow> {
            rowsReads++
            return delegate.rows()
        }
    }

    @Test
    fun withNothingIndexedItSaysSoRatherThanFindingNobody() = runTest {
        val service = service(proxy(InMemoryFaceTemplateStore()).apply { consent() })

        assertEquals(FaceGroupsUnavailable.Reasons.NOT_INDEXED, service.groups().errorReason())
    }

    @Test
    fun aLibraryThatWasIndexedAndHasNobodyInItIsAnEmptySuccess() = runTest {
        val store = InMemoryFaceTemplateStore().apply {
            // ⚠ What an index pass writes for a photograph it examined and found no face in —
            // `MediaIndexer` records that deliberately, so the file is not re-read every pass.
            put("/photos/landscape.jpg", emptyList(), ModelProvenance.Fake)
            put("/photos/receipt.jpg", emptyList(), ModelProvenance.Fake)
        }
        val service = service(proxy(store).apply { consent() })

        // ⚠⚠ The distinction the old check could not make. `size()` sums FACES, so a fully indexed
        // library of landscapes sums to zero and reported `NOT_INDEXED` — telling the user to wait
        // for a pass that had already run, forever. Nobody in your photographs is a legitimate
        // answer, and it is a SUCCESS with no groups.
        assertEquals(emptyList(), service.groups().rows())
    }

    @Test
    fun withNoBackendItSaysSoRatherThanFindingNobody() = runTest {
        val service = service(
            proxy(
                twoPeople(),
                registry = InferenceBackendRegistry(
                    listOf(FakeInferenceBackend(capabilities = emptySet())),
                ),
            ).apply { consent() },
        )

        assertEquals(FaceGroupsUnavailable.Reasons.NO_BACKEND, service.groups().errorReason())
    }

    @Test
    fun aNonPositiveCapReturnsNothingWithoutClaimingTheFeatureIsUnavailable() = runTest {
        val service = service(proxy(twoPeople()).apply { consent() })

        // ⚠ A caller mistake is not a broken device: this succeeds, empty. Reporting it as
        // `UNCALIBRATED` or `NOT_INDEXED` would send someone to debug a build that is fine.
        assertEquals(emptyList(), service.groups(limit = 0).rows())
    }

    @Test
    fun theFaceIndexCrossesTheWireSoTwoPeopleInOneFrameStayDistinct() = runTest {
        val store = InMemoryFaceTemplateStore().apply {
            // One photograph, two different faces — the ordinary couple-in-a-frame case.
            put("/photos/both.jpg", listOf(face(anna), face(bob)), ModelProvenance.Fake)
            put("/photos/a1.jpg", listOf(face(anna)), ModelProvenance.Fake)
            put("/photos/b1.jpg", listOf(face(bob)), ModelProvenance.Fake)
        }
        val service = service(proxy(store).apply { consent() })

        val rows = service.groups().rows()

        // ⚠⚠ `/photos/both.jpg` appears in BOTH groups, under different face indexes. A row shape
        // keyed on path alone could not express that, and one of the two people would silently
        // lose the photograph.
        val both = rows.filter { it.filePath.get() == "/photos/both.jpg" }
        assertEquals(2, both.size)
        assertEquals(2, both.map { it.groupId.get() }.distinct().size)
        assertEquals(setOf(0L, 1L), both.map { it.faceIndex.get() }.toSet())
    }

    /** The face's box crosses the wire, in thousandths of the photograph. */
    @Test
    fun theFaceBoxCrossesTheWireInThousandths() = runTest {
        val service = service(proxy(twoPeople()).apply { consent() })

        val row = service.groups().rows().first()

        // `face()` puts every fixture face at (0.1, 0.1) – (0.4, 0.4).
        assertEquals(100L, row.boxLeftPerMille.get())
        assertEquals(100L, row.boxTopPerMille.get())
        assertEquals(400L, row.boxRightPerMille.get())
        assertEquals(400L, row.boxBottomPerMille.get())
    }

    /**
     * ⚠⚠ "Only them": `facesInPhoto` counts EVERY face in the photograph, including faces whose
     * group this call dropped. A client counting the returned rows would see Anna alone in the
     * shared photograph once Bob's group is cut by the limit, and file a photo of two people under
     * "Only them".
     */
    @Test
    fun facesInPhotoCountsFacesWhoseGroupWasDropped() = runTest {
        val store = InMemoryFaceTemplateStore().apply {
            put("/photos/both.jpg", listOf(face(anna), face(bob)), ModelProvenance.Fake)
            listOf("a1", "a2").forEach { put("/photos/$it.jpg", listOf(face(anna)), ModelProvenance.Fake) }
            put("/photos/b1.jpg", listOf(face(bob)), ModelProvenance.Fake)
        }
        val service = service(proxy(store).apply { consent() })

        // ⚠ Limit 1: only the larger group (Anna, 3 photographs) is returned; Bob's is cut.
        val rows = service.groups(limit = 1).rows()

        assertEquals(1, rows.map { it.groupId.get() }.distinct().size, "Bob's group was dropped")
        val shared = rows.single { it.filePath.get() == "/photos/both.jpg" }
        assertEquals(2L, shared.facesInPhoto.get(), "Bob is still in that photograph")
        assertEquals(1L, rows.first { it.filePath.get() == "/photos/a1.jpg" }.facesInPhoto.get())
    }

    // ─── the store gate (`ios-on-device-inference.md` §13) ───────────────────────────────────────

    @Test
    fun aClosedStoreGateRefusesAndReadsNoTemplate() = runTest {
        val store = ReadCounting(twoPeople())
        storeGate = { false }
        val service = service(proxy(store).apply { consent() })

        assertEquals(FaceGroupsUnavailable.Reasons.NO_BACKEND, service.groups().errorReason())
        assertEquals(0, store.reads, "the store was read behind a closed gate")
    }

    @Test
    fun aClosedGateWithNoWithdrawalDeletesNothingAndDoesNotTouchTheStore() = runTest {
        // ⚠ No consent row at all: absence refuses and deletes nothing, and with the gate closed
        // the store is not even asked for its stamp.
        val inner = twoPeople()
        val store = ReadCounting(inner)
        storeGate = { false }

        assertEquals(FaceGroupsUnavailable.Reasons.NO_BACKEND, service(proxy(store)).groups().errorReason())
        assertEquals(0, store.reads)
        assertEquals(5, inner.size())
    }

    @Test
    fun aClosedGateWithConsentStillGivenDeletesNothingAndServesNothing() = runTest {
        val inner = twoPeople()
        storeGate = { false }
        val answer = service(proxy(inner).apply { consent() }).groups()

        assertEquals(FaceGroupsUnavailable.Reasons.NO_BACKEND, answer.errorReason())
        assertEquals(5, inner.size(), "a closed gate is not a withdrawal")
    }

    /**
     * ⚠⚠ The gate decides what is SERVED, never what is deleted. The gate here is a real custody
     * closed for a reason that has nothing to do with the templates: its vector clear failed. The
     * user then switches face grouping off.
     */
    @Test
    fun aWithdrawalDeletesTheTemplatesThroughAGateAFingerprintFailureClosed() = runTest {
        val store = twoPeople()
        val vectors = object : EmbeddingStore by InMemoryEmbeddingStore() {
            override suspend fun clear(): Unit = error("the disk refused")
        }
        val custody = InferenceStoreCustody(
            files = org.tenshu.core.common.proxy.InMemoryFileDatabase(),
            embeddings = vectors,
            faces = InMemoryFaceTemplateStore(),
            cancelPass = {},
            currentRealm = { InferenceRealm("owner-a") },
            // ⚠ The realm half passes over empty stores; the fingerprint half then cannot clear.
            currentFingerprint = { RuntimeFingerprint("v1", "t1", "CPU_AND_NEURAL_ENGINE", "24A434", 1) },
        )
        storeGate = { custody.open(fromPass = false) }
        val proxy = proxy(store).apply { write(capability.realmConsent, false) }

        val answer = service(proxy).groups()

        assertFalse(custody.usable(), "the fixture: the gate is closed")
        assertEquals(FaceGroupsUnavailable.Reasons.NO_BACKEND, answer.errorReason())
        assertEquals(0, store.size(), "a closed gate kept a withdrawn user's templates on disk")
    }

    /**
     * The gate as the iOS owner binds it: a custody, reached by a People request before any launch
     * check has run, after the device moved to another realm. ⚠ The paths are the same in both
     * realms, which is why a live-path filter could not have caught it.
     */
    @Test
    fun aRequestThatReachesTheGateFirstAfterARealmResetSeesTheTemplatesCleared() = runTest {
        val store = twoPeople()
        val files = org.tenshu.core.common.proxy.InMemoryFileDatabase()
        var owner = "owner-a"
        fun custody() = InferenceStoreCustody(
            files = files,
            embeddings = InMemoryEmbeddingStore(),
            faces = store,
            cancelPass = {},
            currentRealm = { InferenceRealm(owner) },
        )
        // The previous process stamped owner-a; then the templates were written.
        val rows = store.rows()
        assertTrue(custody().ensureCurrentRealm())
        rows.forEach { store.put(it.filePath, it.faces, it.producedBy) }
        assertEquals(5, store.size())

        owner = "owner-b"
        storeGate = custody().let { next -> { next.open(fromPass = false) } }
        val service = service(proxy(store).apply { consent() })

        assertEquals(FaceGroupsUnavailable.Reasons.NOT_INDEXED, service.groups().errorReason())
        assertEquals(0, store.size(), "the previous realm's templates outlived the request")
    }
}
