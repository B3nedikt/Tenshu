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
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.get
import org.tenshu.inference.FaceGroupsUnavailable
import org.tenshu.inference.GroupedFace
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.InferenceService
import org.tenshu.inference.MediaLabel
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.RejectedFace
import org.tenshu.inference.client.inferenceServiceClientModule
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.inference.namePetGroup
import org.tenshu.inference.petGroups
import org.tenshu.inference.petSuggestionRejections
import org.tenshu.inference.rejectPetSuggestion
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The four pet ops over the wire (`photos-pets.md` PT-3b): the face contracts with `PET_RECOGNITION`,
 * `pets.log` and the pet backend — and ⚠⚠ never one of the face half's stores, switches or labels.
 */
class PetOperationsTest {

    private val thisDevice = "device-a"
    private val pet = InferenceCapability.PET_RECOGNITION
    private val stamp = ModelProvenance("dinov2-small-zeroshot", 1, 1001)

    /** Two near-identical cats (cosine ≈ 0.99) and a third far from both (cosine 0.6). */
    private val mochi = floatArrayOf(1f, 0f, 0f)
    private val mochiAgain = floatArrayOf(0.99f, 0.141f, 0f)
    private val tabby = floatArrayOf(0.6f, 0.8f, 0f)

    private class FakePets(override val provenance: ModelProvenance, var present: Boolean = true) : PetBackend {
        override fun available() = present
        override suspend fun findPets(bytes: ByteArray, orientation: Int?): PetOutcome = PetOutcome.Failed
    }

    private fun proxy(pets: FaceTemplateStore, faces: FaceTemplateStore, backend: PetBackend?): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
            registerConfig(inferenceServiceClientModule)
        },
        implementationFactoryOverride = { id, _ ->
            inferenceServiceProxyImplementationFactory(
                id = id,
                registry = InferenceBackendRegistry(listOf(FakeInferenceBackend())),
                embeddings = InMemoryEmbeddingStore(),
                deviceId = thisDevice,
                faceGrouping = FaceGroupingComponents(faces, FaceClustering.Measured),
                petGrouping = FaceGroupingComponents(pets, PetClustering.BestGuess),
                petBackend = backend,
            )
        },
    )

    private fun service(proxy: TestProxy): InferenceService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        return TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        ).getService(InferenceService.ID)
    }

    private fun <T> TestProxy.write(preference: Preference<T>, value: T, deviceId: String? = null) {
        executionContext.saveData(
            if (deviceId == null) {
                Entity.create<RealmSetting>(context = tContext, composite = RealmSetting.create(context = tContext, preferenceId = preference.id, value = preference.adapter.toData(value, tContext)))
            } else {
                Entity.create<DeviceSetting>(context = tContext, composite = DeviceSetting.create(context = tContext, deviceId = deviceId, preferenceId = preference.id, value = preference.adapter.toData(value, tContext)))
            },
        )
    }

    private fun TestProxy.consentTo(capability: InferenceCapability) {
        write(capability.realmConsent, true)
        write(capability.deviceConsent, true, deviceId = thisDevice)
    }

    private fun face(vector: FloatArray) = StoredFace(FaceBox(0.1f, 0.1f, 0.4f, 0.4f, 0.9f), vector)

    private suspend fun threeCats(): FaceTemplateStore = InMemoryFaceTemplateStore().apply {
        put("/a.jpg", listOf(face(mochi)), stamp)
        put("/b.jpg", listOf(face(mochiAgain)), stamp)
        put("/c.jpg", listOf(face(tabby)), stamp)
    }

    private fun TResult<TList<GroupedFace>>.rows(): List<GroupedFace> =
        (this as TResult.Success<TList<GroupedFace>>).value!!.toList().filterIsInstance<GroupedFace>()

    private fun TResult<*>.reason(): String =
        ((this as TResult.Failure).error as FaceGroupsUnavailable).reason.get()

    @Test
    fun petGroupsGroupsPetsAtTheBestGuessCut() = runTest {
        val proxy = proxy(threeCats(), InMemoryFaceTemplateStore(), FakePets(stamp)).apply { consentTo(pet) }

        val groups = service(proxy).petGroups(10).rows().groupBy({ it.groupId.get() }, { it.filePath.get() })

        // ⚠ The tabby at cosine 0.6 is its own one-photo group, dropped by minFaces = 2 — never merged.
        assertEquals(listOf(setOf("/a.jpg", "/b.jpg")), groups.values.map { it.toSet() })
    }

    @Test
    fun faceConsentIsNotPetConsent() = runTest {
        val proxy = proxy(threeCats(), InMemoryFaceTemplateStore(), FakePets(stamp)).apply { consentTo(InferenceCapability.FACE_CLUSTERING) }
        assertEquals(FaceGroupsUnavailable.Reasons.NO_CONSENT, service(proxy).petGroups(10).reason())
        assertEquals(FaceGroupsUnavailable.Reasons.NO_CONSENT, service(proxy).namePetGroup(listOf("/a.jpg"), "Mochi").reason())
    }

    @Test
    fun withoutThePetModelsThereIsNoBackend() = runTest {
        val absent = proxy(threeCats(), InMemoryFaceTemplateStore(), FakePets(stamp, present = false)).apply { consentTo(pet) }
        assertEquals(FaceGroupsUnavailable.Reasons.NO_BACKEND, service(absent).petGroups(10).reason())
        val none = proxy(threeCats(), InMemoryFaceTemplateStore(), null).apply { consentTo(pet) }
        assertEquals(FaceGroupsUnavailable.Reasons.NO_BACKEND, service(none).petGroups(10).reason())
    }

    @Test
    fun aWithdrawalClearsThePetsStore_neverTheFaces() = runTest {
        val pets = threeCats()
        val faces = InMemoryFaceTemplateStore().apply { put("/a.jpg", listOf(face(floatArrayOf(0f, 0f, 1f))), ModelProvenance.Fake) }
        val proxy = proxy(pets, faces, FakePets(stamp)).apply {
            consentTo(pet)
            write(pet.deviceConsent, false, deviceId = thisDevice)
        }

        assertEquals(FaceGroupsUnavailable.Reasons.NO_CONSENT, service(proxy).petGroups(10).reason())
        assertNull(pets.provenance(), "pets.log cleared")
        assertEquals(1, faces.size(), "⚠⚠ faces.log untouched")
    }

    @Test
    fun aPipelineChangeClearsThePetsStoreBeforeReading() = runTest {
        val pets = threeCats()
        val proxy = proxy(pets, InMemoryFaceTemplateStore(), FakePets(ModelProvenance("dinov2-small-zeroshot", 1, 2001))).apply { consentTo(pet) }

        assertEquals(FaceGroupsUnavailable.Reasons.NOT_INDEXED, service(proxy).petGroups(10).reason())
        assertEquals(0, pets.size())
    }

    @Test
    fun namingWritesAPetLabel_byTheUser_neverAFaceLabel() = runTest {
        val proxy = proxy(threeCats(), InMemoryFaceTemplateStore(), FakePets(stamp)).apply { consentTo(pet) }

        val written = service(proxy).namePetGroup(listOf("/a.jpg", "/b.jpg"), "Mochi")
        assertEquals(2L, (written as TResult.Success).value!!.get())

        fun stored(capability: InferenceCapability) = proxy.executionContext.database.getData(
            DataId.StringDataId.create(context = proxy.tContext, type = MediaLabel.ID, keyValue = MediaLabel.labelKey("/a.jpg", capability, "Mochi")),
        )
        assertNotNull(stored(pet), "a petRecognition label")
        assertNull(stored(InferenceCapability.FACE_CLUSTERING), "⚠⚠ never a person's")
        val provenance = when (val row = stored(pet)) {
            is Entity<*> -> row.getFieldValue<org.tenshu.core.TString>(MediaLabel.Fields.PRODUCED_BY).get()
            is org.tenshu.core.Composite -> row.getField<org.tenshu.core.TString>(MediaLabel.Fields.PRODUCED_BY).get()
            else -> null
        }
        assertEquals(ModelProvenance.User.asString(), provenance)
    }

    @Test
    fun aRejectionIsHeldBesideThePetTemplates() = runTest {
        val pets = threeCats()
        val faces = InMemoryFaceTemplateStore().apply { put("/a.jpg", listOf(face(floatArrayOf(0f, 0f, 1f))), ModelProvenance.Fake) }
        val proxy = proxy(pets, faces, FakePets(stamp)).apply { consentTo(pet) }
        val service = service(proxy)

        val added = service.rejectPetSuggestion("Mochi", listOf("/c.jpg"), listOf(100, 100, 400, 400))
        assertEquals(1L, (added as TResult.Success).value!!.get())

        assertEquals(1, pets.rejections().size)
        assertTrue(faces.rejections().isEmpty(), "⚠⚠ not in faces.log's rejections")
        val held = (service.petSuggestionRejections() as TResult.Success<TList<RejectedFace>>).value!!.toList().filterIsInstance<RejectedFace>()
        assertEquals(listOf("/c.jpg"), held.map { it.filePath.get() })
    }
}
