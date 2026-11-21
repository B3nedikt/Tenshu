@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.tenshu.sdk.ios

import app.tenshu.control.di.OwnedInferenceStores
import app.tenshu.control.di.PET_TEMPLATE_STORE
import app.tenshu.control.di.PhotosOnDeviceAi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.tenshu.core.TResult
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.inference.FaceGroupsUnavailable
import org.tenshu.inference.client.inferenceService
import org.tenshu.inference.petGroups
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.AiReadinessState
import org.tenshu.inference.proxy.FaceTemplateCustody
import org.tenshu.inference.proxy.FaceTemplateStore
import org.tenshu.inference.proxy.MediaIndexer
import org.tenshu.inference.proxy.PetBackend
import org.tenshu.inference.proxy.ReadinessSlot
import org.tenshu.inference.proxy.coreml.CoreMlPetBackend
import platform.Foundation.NSTemporaryDirectory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pets in Photos' graph (`photos-pets.md` PT-4b2b): bound behind **their own** build flag, which needs the
 * on-device AI flag too; their own custody, readiness and probe; and inert — nothing claimed, the pet ops
 * answering `NO_BACKEND` — until the models are installed and probed.
 *
 * ⭐ Every graph is `PhotosLocalRuntime.assemble`'s own, as in [PhotosFaceBindingTest].
 */
@OptIn(ExperimentalEncodingApi::class)
class PhotosPetBindingTest {

    @BeforeTest
    fun redirectMain() {
        Dispatchers.setMain(Dispatchers.Default)
        OwnedInferenceStores.clearPriorWritersForTests()
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = true)
    }

    @AfterTest
    fun restore() {
        PhotosLocalRuntime.modelTransfersSettleNow(inForeground = false)
        OwnedInferenceStores.clearPriorWritersForTests()
        Dispatchers.resetMain()
        TestGraphs.stopAll()
    }

    private fun temp(name: String): String = NSTemporaryDirectory() + "$name-" + Random.nextLong().toString(16)

    private fun material() = RealmKeyMaterial(
        sharedIdentityKey = Random.nextBytes(32),
        sharedVolumeKey = Random.nextBytes(32),
        deviceSigningBlob = (Base64.encode(Random.nextBytes(32)) + "|" + Base64.encode(Random.nextBytes(32))).encodeToByteArray(),
        ownerId = "owner-a",
        deviceId = "device-1",
        realmEncryptionPublicKey = Random.nextBytes(32),
        realmEncryptionPrivateKey = Random.nextBytes(32),
    )

    private val eightGigabytes = 8L * 1024 * 1024 * 1024
    private val replica: String = temp("photos-pets-replica")

    private fun inputs(ai: Boolean = true, pets: Boolean = true) = PhotosLocalRuntime.OnDeviceAiInputs(
        buildFlag = ai, osMajorVersion = 17, physicalMemoryBytes = eightGigabytes, petsBuildFlag = pets,
    )

    private fun photos(onDeviceAi: PhotosLocalRuntime.OnDeviceAiInputs = inputs()): PhotosLocalRuntime.Assembly =
        PhotosLocalRuntime.assemble(
            replicaDirectory = replica,
            secureStorage = InMemorySecureStorageDelegate(),
            handedOffMaterial = material(),
            relayBaseUrl = "http://127.0.0.1:1",
            inferenceRoot = temp("photos-pets"),
            deviceRealmOwner = { "owner-a" },
            replicaEpoch = { 1L },
            onDeviceAi = onDeviceAi,
            onDeviceAiOverrides = noCatalogThen(null),
        )

    @Test
    fun onlyTheExactYesSwitchesThePetsFlagOn() {
        listOf<Any?>(null, "", "NO", "yes", "true", "1", "\$(TENSHU_PHOTOS_PETS)", true).forEach { raw ->
            assertFalse(PhotosLocalRuntime.petsBuildFlag(raw), "'$raw' switched pets on")
        }
        assertTrue(PhotosLocalRuntime.petsBuildFlag("YES"))
        assertEquals("TenshuPhotosPets", PhotosLocalRuntime.PETS_INFO_KEY)
    }

    @Test
    fun withBothFlagsThePetsMachineryIsBound_andClaimsNothingWithNoModel() = runBlocking {
        val photos = photos()
        val koin: Koin = photos.koin
        val readiness: AiReadiness = koin.get(named(PhotosOnDeviceAi.PETS_READINESS))
        assertSame(ReadinessSlot.PETS, readiness.slot)
        assertNotSame(koin.get<AiReadiness>(), readiness)
        assertIs<CoreMlPetBackend>(koin.get<PetBackend>())
        val custody: FaceTemplateCustody = koin.get(named(PhotosOnDeviceAi.PETS_CUSTODY))
        assertEquals(ReadinessSlot.PETS.fingerprintPath, custody.fingerprintPath)
        assertEquals(2, PhotosOnDeviceAi.petPreparers(koin).size, "one preparer per pet model")
        // PT-4c: the switch is offered beside a bound pet backend, and both pet models read as its rows.
        val backends: List<org.tenshu.inference.proxy.InferenceBackend> = koin.get(named("inferenceBackends"))
        assertTrue(org.tenshu.inference.InferenceCapability.PET_RECOGNITION in PhotosOnDeviceAi.features(backends, pets = true))
        val features = PhotosOnDeviceAi.modelFeatures(faces = false, pets = true)
        assertEquals(org.tenshu.inference.InferenceCapability.PET_RECOGNITION, features["dinov2-small-zeroshot"])
        assertEquals(org.tenshu.inference.InferenceCapability.PET_RECOGNITION, features["rfdetr-nano-coco-int8"])

        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        PhotosOnDeviceAi.recheck(koin)
        assertEquals(AiReadinessState.NoModel, readiness.current())
        assertFalse(koin.get<MediaIndexer>().petsRunHere(), "no model, no pet half")

        // The pet ops exist here, and say why there is nothing: no backend, not "no pets".
        val answer = photos.tenshuClient.inferenceService.petGroups(10)
        val error = (answer as TResult.Failure).error
        assertEquals(FaceGroupsUnavailable.Reasons.NO_BACKEND, (error as FaceGroupsUnavailable).reason.get())
    }

    @Test
    fun withThePetsFlagOffNothingOfPetsIsBound() = runBlocking {
        val photos = photos(onDeviceAi = inputs(pets = false))
        val koin: Koin = photos.koin
        assertNull(koin.getOrNull<PetBackend>())
        assertNull(koin.getOrNull<AiReadiness>(named(PhotosOnDeviceAi.PETS_READINESS)))
        assertTrue(PhotosOnDeviceAi.petPreparers(koin).isEmpty())
    }

    @Test
    fun thePetsFlagAloneBindsNothing() = runBlocking {
        val photos = photos(onDeviceAi = inputs(ai = false, pets = true))
        assertNull(photos.koin.getOrNull<PetBackend>())
    }

    @Test
    fun theOwnedPetsStoreIsNotTheFaceStore() = runBlocking {
        val koin: Koin = photos().koin
        val pets: FaceTemplateStore = koin.get(named(PET_TEMPLATE_STORE))
        assertNotSame(koin.get<FaceTemplateStore>(), pets)
    }
}
