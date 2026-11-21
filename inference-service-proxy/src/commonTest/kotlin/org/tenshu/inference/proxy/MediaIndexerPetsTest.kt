package org.tenshu.inference.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pets as the **fourth half** of the index pass (`photos-pets.md` PT-3): the face half's rules — its
 * own store, backend, consent and freshness — and ⚠⚠ withdrawal deleting **only its own** templates.
 */
class MediaIndexerPetsTest {

    private val thisDevice = "device-a"
    private val pet = InferenceCapability.PET_RECOGNITION

    private fun proxy(): TestProxy = TestProxy(
        schemaRegistry = createCoreTestRegistry().apply {
            registerConfig(preferenceServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
        },
    )

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

    private fun TestProxy.consentTo(capability: InferenceCapability) {
        write(capability.realmConsent, true)
        write(capability.deviceConsent, true, deviceId = thisDevice)
    }

    /** Reads its answer out of the bytes: `"cat,cat"` is two pets, `""` none, `"pdf"` unreadable, `"boom"` a failure. */
    private class FakePets(override var provenance: ModelProvenance = ModelProvenance("dinov2-small-zeroshot", 1, 1001)) : PetBackend {
        var present = true
        var asked = 0
        override fun available() = present
        override suspend fun findPets(bytes: ByteArray, orientation: Int?): PetOutcome {
            asked++
            return when (val text = bytes.decodeToString()) {
                "pdf" -> PetOutcome.Unreadable
                "boom" -> PetOutcome.Failed
                "" -> PetOutcome.Found(emptyList())
                else -> PetOutcome.Found(text.split(",").map { StoredFace(FaceBox(0.1f, 0.1f, 0.3f, 0.3f, 0.9f), FloatArray(384) { 1f }) })
            }
        }
    }

    private fun indexer(pets: FaceTemplateStore?, backend: PetBackend?, faces: FaceTemplateStore? = null) = MediaIndexer(
        // ⚠ No face backend: these tests watch faces.log only as something a pets switch-off must not touch.
        registry = InferenceBackendRegistry(listOf(FakeInferenceBackend(capabilities = emptySet()))),
        deviceId = { thisDevice },
        faces = faces,
        conditions = IndexingConditions { true },
        pets = pets,
        petBackend = backend,
    )

    private fun media(vararg photos: Pair<String, String>) =
        photos.map { (path, content) -> IndexableMedia(path) { content.encodeToByteArray() } }

    @Test
    fun petsAreIndexedUnderTheirOwnSwitch_andFaceConsentIsNotIt() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(InferenceCapability.FACE_CLUSTERING) }

        val withFacesOnly = indexer(store, FakePets()).index(proxy.executionContext, media("/a.jpg" to "cat"))
        assertEquals(0, withFacesOnly.photosSearchedForPets, "face consent must not index pets")
        assertEquals(0, store.size())

        proxy.consentTo(pet)
        val result = indexer(store, FakePets()).index(proxy.executionContext, media("/a.jpg" to "cat,dog", "/b.jpg" to ""))
        assertEquals(2, result.photosSearchedForPets)
        assertEquals(2, result.petsEmbedded)
        assertEquals(2, result.petRecordsWritten, "the photograph with no animal is a record too")
        assertTrue(store.visited("/b.jpg"))
    }

    @Test
    fun aVisitedPhotographIsNotOpenedAgain() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(pet) }
        val backend = FakePets()
        indexer(store, backend).index(proxy.executionContext, media("/a.jpg" to "", "/b.jpg" to "pdf"))
        assertEquals(2, backend.asked)

        val again = indexer(store, backend).index(proxy.executionContext, media("/a.jpg" to "", "/b.jpg" to "pdf"))
        assertEquals(2, backend.asked, "no animal and unreadable are both remembered")
        assertEquals(2, again.alreadyCurrent)
    }

    @Test
    fun aFailureIsNeverRecorded_andLatchesTheHalfForThePass() = runTest {
        val store = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(pet) }
        val backend = FakePets()

        val result = indexer(store, backend).index(proxy.executionContext, media("/a.jpg" to "boom", "/b.jpg" to "cat"))
        assertEquals(1, backend.asked, "latched after the first failure")
        assertEquals(2, result.petsFailed)
        assertFalse(store.visited("/a.jpg"))
        assertFalse(store.visited("/b.jpg"))
    }

    @Test
    fun withdrawingPetsDeletesThePetTemplates_andNotTheFaces() = runTest {
        val pets = InMemoryFaceTemplateStore()
        val faces = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(pet) }
        indexer(pets, FakePets()).index(proxy.executionContext, media("/a.jpg" to "cat"))
        faces.put("/a.jpg", listOf(StoredFace(FaceBox(0f, 0f, 1f, 1f), FloatArray(512) { 1f })), ModelProvenance("auraface", 1))

        proxy.write(pet.deviceConsent, false, deviceId = thisDevice)
        val result = indexer(pets, FakePets(), faces = faces).index(proxy.executionContext, media("/a.jpg" to "cat"))

        assertTrue(result.petsCleared)
        assertNull(pets.provenance(), "pets.log is gone")
        assertEquals(1, faces.size(), "⚠⚠ a pets switch-off never reaches faces.log")
    }

    @Test
    fun withdrawingFacesNeverTakesThePets() = runTest {
        val pets = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(pet) }
        indexer(pets, FakePets()).index(proxy.executionContext, media("/a.jpg" to "cat"))

        proxy.write(InferenceCapability.FACE_CLUSTERING.realmConsent, false)
        indexer(pets, FakePets(), faces = InMemoryFaceTemplateStore()).index(proxy.executionContext, media("/b.jpg" to "dog"))

        assertEquals(2, pets.size())
    }

    /**
     * ⚠⚠ A `petGroups` read that sees the switch-off WHILE `findPets` runs clears pets.log; the
     * write that follows must not recreate it. The last photograph on purpose: no later file's
     * check is left to catch it.
     */
    @Test
    fun aWriteThatLandsAfterAConcurrentWithdrawalCleanupDoesNotRecreateTheStore() = runTest {
        val pets = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(pet) }
        val withdrawnWhileFinding = object : PetBackend by FakePets() {
            override suspend fun findPets(bytes: ByteArray, orientation: Int?): PetOutcome {
                proxy.write(pet.deviceConsent, false, deviceId = thisDevice)
                pets.clear() // the concurrent read's withdrawal cleanup
                return PetOutcome.Found(listOf(StoredFace(FaceBox(0.1f, 0.1f, 0.3f, 0.3f, 0.9f), FloatArray(384) { 1f })))
            }
        }

        val result = indexer(pets, withdrawnWhileFinding).index(proxy.executionContext, media("/a.jpg" to "cat"))

        assertEquals(1, result.petRecordsWritten, "the write itself did land")
        assertNull(pets.provenance(), "and the re-check after it deleted it again")
        assertEquals(0, pets.size())
        assertTrue(result.petsCleared)
    }

    @Test
    fun aMissingConsentRowIsNotAWithdrawal() = runTest {
        // ⚠⚠ Absence is "not asked", and must never be able to destroy the store.
        val pets = InMemoryFaceTemplateStore()
        pets.put("/a.jpg", emptyList(), ModelProvenance("dinov2-small-zeroshot", 1, 1001))
        val result = indexer(pets, FakePets()).index(proxy().executionContext, media("/a.jpg" to "cat"))
        assertFalse(result.petsCleared)
        assertTrue(pets.visited("/a.jpg"))
    }

    @Test
    fun aPipelineChangeStartsTheStoreOver() = runTest {
        val pets = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(pet) }
        indexer(pets, FakePets()).index(proxy.executionContext, media("/a.jpg" to "cat"))

        val moved = FakePets(ModelProvenance("dinov2-small-zeroshot", 1, 2001))
        val result = indexer(pets, moved).index(proxy.executionContext, media("/a.jpg" to "cat"))

        assertTrue(result.petsCleared)
        assertEquals(1, moved.asked, "re-indexed under the new pipeline")
        assertEquals(ModelProvenance("dinov2-small-zeroshot", 1, 2001), pets.provenance())
    }

    @Test
    fun aDeletedPhotographsPetsLeaveWithIt() = runTest {
        val pets = InMemoryFaceTemplateStore()
        val proxy = proxy().apply { consentTo(pet) }
        indexer(pets, FakePets()).index(proxy.executionContext, media("/a.jpg" to "cat", "/gone.jpg" to "dog"))

        val result = indexer(pets, FakePets()).index(
            proxy.executionContext,
            media("/a.jpg" to "cat"),
            wholeLibrary = WholeLibrary(LivePaths { _, _ -> emptySet() }),
        )
        assertEquals(1, result.stalePetsRemoved)
        assertFalse(pets.visited("/gone.jpg"))
    }

    @Test
    fun petsAloneAreEnoughForAPassToRun_andOnlyWhenTheModelsAreThere() = runTest {
        val proxy = proxy().apply { consentTo(pet) }
        assertTrue(indexer(InMemoryFaceTemplateStore(), FakePets()).anyHalfMayRun(proxy.executionContext))
        val absent = FakePets().apply { present = false }
        assertFalse(indexer(InMemoryFaceTemplateStore(), absent).anyHalfMayRun(proxy.executionContext))
        assertFalse(indexer(null, FakePets()).anyHalfMayRun(proxy.executionContext), "no store, no pets")
    }
    // ── People & pets is told when only pets changed (`photos-pets.md` §10.18) ──────────────────────

    private fun TestScope.passScope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())

    /**
     * ⚠⚠ Measured on an iPhone: a pass that found 28 animals and no faces left Collections on its
     * empty answer until Photos was relaunched — the runner only counted face writes.
     */
    @Test
    fun aPassThatOnlyFoundPetsTellsPeople() = runTest {
        val proxy = proxy().apply { consentTo(pet) }
        val runner = MediaIndexRunner(
            indexer(InMemoryFaceTemplateStore(), FakePets()),
            MediaSource { media("/a.jpg" to "cat") },
            passScope(),
        )

        runner.runToCompletion(proxy.executionContext)

        assertEquals(1L, runner.faceChanges.value, "the pets store changed, so People & pets did")
    }

    @Test
    fun aPassRefusedAfterPetsWereSwitchedOffTellsPeople() = runTest {
        val proxy = proxy().apply { consentTo(pet) }
        val pets = InMemoryFaceTemplateStore()
        val runner = MediaIndexRunner(indexer(pets, FakePets()), MediaSource { media("/a.jpg" to "cat") }, passScope())
        runner.runToCompletion(proxy.executionContext)
        proxy.write(pet.deviceConsent, false, deviceId = thisDevice)

        runner.runToCompletion(proxy.executionContext)

        assertEquals(0, pets.size(), "precondition: the withdrawal cleared pets.log")
        assertEquals(2L, runner.faceChanges.value, "and the pets that vanished are a change too")
    }
}
