@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.tenshu.sdk.ios

import app.tenshu.control.di.OwnedInferenceStores
import app.tenshu.control.di.PhotosOnDeviceAi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.tenshu.core.TResult
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.IosFileDatabase
import org.tenshu.core.common.util.Sha256Digest
import org.tenshu.core.entity.Entity
import org.tenshu.inference.FaceGroupsUnavailable
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.client.inferenceService
import org.tenshu.inference.faceGroups
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.AiReadinessState
import org.tenshu.inference.proxy.AiStaticGates
import org.tenshu.inference.proxy.EmbeddingStore
import org.tenshu.inference.proxy.FaceBox
import org.tenshu.inference.proxy.FaceClustering
import org.tenshu.inference.proxy.FaceGroupingComponents
import org.tenshu.inference.proxy.FaceTemplateCustody
import org.tenshu.inference.proxy.FaceTemplateStore
import org.tenshu.inference.proxy.FingerprintOutcome
import org.tenshu.inference.proxy.InferenceBackend
import org.tenshu.inference.proxy.InferenceBackendRegistry
import org.tenshu.inference.proxy.InferenceStoreCustody
import org.tenshu.inference.proxy.ReadinessSlot
import org.tenshu.inference.proxy.StoredFace
import org.tenshu.inference.proxy.catalog.ModelArtifactData
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.catalog.ModelStoreChanges
import org.tenshu.inference.proxy.coreml.CoreMlInferenceBackend
import org.tenshu.inference.proxy.coreml.VisionCoreMlFaceBackend
import org.tenshu.inference.proxy.coreml.iosFacePreflightKey
import org.tenshu.inference.proxy.coreml.iosRuntimeFingerprint
import org.tenshu.inference.proxy.onnx.FaceModel
import org.tenshu.inference.proxy.onnx.VisionModel
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.RealmSetting
import org.tenshu.sync.common.SyncManager
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `ios-face-grouping.md` F5: Photos binds the face backend behind **its own** build flag (FD10),
 * which needs the on-device AI flag and both static gates too; the face pair's readiness keeps its
 * fingerprint in its own custody, never SigLIP's (§14.4a); and the realm stamp, consent and
 * withdrawal hold on the graph that has a face backend.
 *
 * ⭐ Every graph is `PhotosLocalRuntime.assemble`'s own, as in [PhotosOnDeviceAiTest].
 * ⚠ Nothing here runs Vision or Core ML: the face model is `Ready` through a recorded verdict, the
 * one writer F5 does not bind (the face prepare and probe are F6's).
 */
@OptIn(ExperimentalEncodingApi::class)
class PhotosFaceBindingTest {

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
    }

    private val files = NSFileManager.defaultManager
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

    private fun inputs(ai: Boolean = true, faces: Boolean = true, os: Int = 17, memory: Long = eightGigabytes) =
        PhotosLocalRuntime.OnDeviceAiInputs(buildFlag = ai, osMajorVersion = os, physicalMemoryBytes = memory, facesBuildFlag = faces)

    /** The realm the device is on; a test moves it. */
    private var deviceOwner: String? = "owner-a"

    /** The shared store's epoch, the stamp's second half; a test moves it. */
    private var epoch: Long? = 1L

    private val replica: String = temp("photos-faces-replica")

    private fun photos(root: String = temp("photos-faces"), onDeviceAi: PhotosLocalRuntime.OnDeviceAiInputs? = inputs()): PhotosLocalRuntime.Assembly =
        PhotosLocalRuntime.assemble(
            replicaDirectory = replica,
            secureStorage = InMemorySecureStorageDelegate(),
            handedOffMaterial = material(),
            relayBaseUrl = "http://127.0.0.1:1",
            inferenceRoot = root,
            deviceRealmOwner = { deviceOwner },
            replicaEpoch = { epoch },
            onDeviceAi = onDeviceAi,
            // ⚠⚠ No catalog: a Models repository here would otherwise ask the real CDN.
            onDeviceAiOverrides = noCatalogThen(null),
        )

    private val PhotosLocalRuntime.Assembly.faceReadiness: AiReadiness
        get() = koin.get(named(PhotosOnDeviceAi.FACE_READINESS))

    // ─── the flag: FD10 ─────────────────────────────────────────────────────────────────────────

    @Test
    fun onlyTheExactYesSwitchesTheFaceFlagOn() {
        listOf<Any?>(null, "", "NO", "no", "yes", "true", "1", "\$(TENSHU_PHOTOS_FACES)", 1, true).forEach { raw ->
            assertFalse(PhotosLocalRuntime.facesBuildFlag(raw), "'$raw' switched faces on")
        }
        assertTrue(PhotosLocalRuntime.facesBuildFlag("YES"))
        assertEquals("TenshuPhotosFaces", PhotosLocalRuntime.FACES_INFO_KEY)
        assertNotEquals(PhotosLocalRuntime.ON_DEVICE_AI_INFO_KEY, PhotosLocalRuntime.FACES_INFO_KEY)
    }

    /** What a graph with no face backend is: nothing of the face pair, and no face switch. */
    private fun assertNoFaceBackend(photos: PhotosLocalRuntime.Assembly, why: String) {
        val koin: Koin = photos.koin
        assertFalse(photos.faces, why)
        assertNull(koin.getOrNull<VisionCoreMlFaceBackend>(), "$why: a face backend is bound")
        assertNull(koin.getOrNull<AiReadiness>(named(PhotosOnDeviceAi.FACE_READINESS)), "$why: a face readiness is bound")
        assertNull(koin.getOrNull<FaceTemplateCustody>(), "$why: a face custody is bound")
        val backends: List<InferenceBackend> = koin.getOrNull(named("inferenceBackends")) ?: emptyList()
        assertTrue(backends.none { it is VisionCoreMlFaceBackend }, "$why: $backends")
        assertFalse(InferenceCapability.FACE_CLUSTERING in PhotosOnDeviceAi.features(backends), "$why: a face switch is offered")
        val runtimes: Set<String> = koin.getOrNull(named("supportedModelRuntimes")) ?: emptySet()
        assertFalse(FaceModel.COREML_VISION_RUNTIME in runtimes, "$why: the face download is offered")
    }

    @Test
    fun withBothFlagsOnAnAdmittedPhoneTheFaceBackendIsBoundAndClaimsNothingWithNoModel() = runBlocking {
        val photos = photos()
        val koin: Koin = photos.koin

        assertTrue(photos.onDeviceAi)
        assertTrue(photos.faces)
        val backends: List<InferenceBackend> = koin.get(named("inferenceBackends"))
        assertEquals(listOf<Any>(koin.get<CoreMlInferenceBackend>(), koin.get<VisionCoreMlFaceBackend>()), backends)
        assertEquals(setOf("coreml", FaceModel.COREML_VISION_RUNTIME), koin.get<Set<String>>(named("supportedModelRuntimes")))
        assertEquals(listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH, InferenceCapability.FACE_CLUSTERING), PhotosOnDeviceAi.features(backends))
        assertSame(ReadinessSlot.FACES, photos.faceReadiness.slot)
        assertNotSame(koin.get<AiReadiness>(), photos.faceReadiness)

        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        PhotosOnDeviceAi.recheck(koin)
        assertEquals(AiReadinessState.NoModel, photos.faceReadiness.current())
        assertTrue(koin.get<InferenceBackendRegistry>().available.isEmpty(), "a backend with no model claimed a capability")
    }

    @Test
    fun theFaceFlagAloneBindsNothingAtAll() = runBlocking {
        val photos = photos(onDeviceAi = inputs(ai = false, faces = true))
        assertFalse(photos.onDeviceAi)
        assertNull(photos.koin.getOrNull<List<InferenceBackend>>(named("inferenceBackends")))
        assertNoFaceBackend(photos, "the AI flag is off")
    }

    @Test
    fun withTheFaceFlagOffSearchIsBoundAndFacesAreNot() = runBlocking {
        val photos = photos(onDeviceAi = inputs(ai = true, faces = false))
        assertTrue(photos.onDeviceAi)
        assertSame<Any>(photos.koin.get<CoreMlInferenceBackend>(), photos.koin.get<List<InferenceBackend>>(named("inferenceBackends")).single())
        assertEquals(setOf("coreml"), photos.koin.get<Set<String>>(named("supportedModelRuntimes")))
        assertNoFaceBackend(photos, "the face flag is off")
    }

    @Test
    fun eachStaticGateKeepsTheFaceBackendOutWithBothFlagsOn() = runBlocking {
        assertNoFaceBackend(photos(onDeviceAi = inputs(os = 16)), "iOS 16")
        val below: Long = AiStaticGates.PLACEHOLDER_MIN_PHYSICAL_MEMORY_BYTES - 1
        assertNoFaceBackend(photos(onDeviceAi = inputs(memory = below)), "too little memory")
        assertFalse(PhotosOnDeviceAi.facesAdmitted(aiBuildFlag = true, facesBuildFlag = true, osMajorVersion = 26, physicalMemoryBytes = below))
        assertTrue(PhotosOnDeviceAi.facesAdmitted(aiBuildFlag = true, facesBuildFlag = true, osMajorVersion = 26, physicalMemoryBytes = below + 1))
        assertFalse(PhotosOnDeviceAi.facesAdmitted(aiBuildFlag = false, facesBuildFlag = true, osMajorVersion = 26, physicalMemoryBytes = eightGigabytes))
        assertFalse(PhotosOnDeviceAi.facesAdmitted(aiBuildFlag = true, facesBuildFlag = false, osMajorVersion = 26, physicalMemoryBytes = eightGigabytes))
    }

    /** No inputs: what a build reads when its Info.plist does not say `YES`. This host's does not. */
    @Test
    fun aBuildWhoseInfoPlistDoesNotSayYesBindsNoFaceBackend() = runBlocking {
        assertFalse(PhotosLocalRuntime.facesBuildFlag(), "the test host's bundle switches faces on")
        assertNoFaceBackend(photos(onDeviceAi = null), "production, with no flag")
    }

    // ─── capabilities from the graph ────────────────────────────────────────────────────────────

    @Test
    fun theModelsScreenOffersTheFaceSwitchOnlyBesideAFaceBackend() = runBlocking {
        val with = photos()
        assertEquals(
            listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH, InferenceCapability.FACE_CLUSTERING),
            with.modelsRepository().capabilities.features,
        )
        val without = photos(onDeviceAi = inputs(faces = false))
        assertEquals(listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH), without.modelsRepository().capabilities.features)
        PhotosOnDeviceAi.retire(with.koin)?.join()
        PhotosOnDeviceAi.retire(without.koin)?.join()
        Unit
    }

    /** FD3: F1a/F1b kept 0.44, so iOS has no override: People clusters at `FaceClustering.Measured`. */
    @Test
    fun theFaceGraphClustersAtTheMeasuredOperatingPoint() {
        assertSame(FaceClustering.Measured, photos().koin.get<FaceGroupingComponents>().clustering)
    }

    // ─── custody: never SigLIP's ────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalStdlibApi::class)
    private fun sha256(bytes: ByteArray): String = Sha256Digest().apply { update(bytes, bytes.size) }.digest().toHexString()

    private val faceWeights: ByteArray = "AURAFACE-COREML".encodeToByteArray()

    /** The face entry as the store would hold it after a verified download. */
    private fun PhotosLocalRuntime.Assembly.installFaceModel(weights: ByteArray = faceWeights) {
        val entry = ModelEntryData(
            modelId = FaceModel.AuraFaceCoreMl.modelId,
            name = "AuraFace",
            modelVersion = 1,
            capabilities = listOf(InferenceCapability.FACE_CLUSTERING),
            labelSetVersion = 1,
            runtime = FaceModel.COREML_VISION_RUNTIME,
            artifactVersion = 1,
            artifacts = listOf(ModelArtifactData(ModelArtifactRole.FACE_EMBEDDER, "models/faceEmbedder", sha256(weights), weights.size.toLong())),
            licence = "Apache-2.0",
        )
        assertNotNull(koin.get<ModelStore>().add(entry, mapOf(ModelArtifactRole.FACE_EMBEDDER to weights)))
    }

    /** What the prepare step would leave for the embedder: its compiled directory, and the marker last. */
    private fun markFacePrepared(root: String, weights: ByteArray = faceWeights) {
        val digest: String = sha256(weights)
        files.createDirectoryAtPath("$root/inference/coreml/${digest.take(16)}/${ModelArtifactRole.FACE_EMBEDDER}.mlmodelc", true, null, null)
        IosFileDatabase(root).writeRawByteArray("inference/coreml/${digest.take(16)}/ready", "digest=$digest\nosBuild=os\n".encodeToByteArray())
    }

    /** SigLIP's three artifacts, installed, prepared and passed: search `Ready` through its own verdict. */
    private suspend fun PhotosLocalRuntime.Assembly.searchReady(root: String) {
        val bytes: Map<String, ByteArray> = listOf(ModelArtifactRole.VISION, ModelArtifactRole.TEXT, ModelArtifactRole.VOCABULARY)
            .associateWith { role -> "siglip-$role".encodeToByteArray() }
        val entry = ModelEntryData(
            modelId = VisionModel.PinnedSigLip.modelId,
            name = "SigLIP",
            modelVersion = 1,
            capabilities = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH),
            labelSetVersion = 1,
            runtime = "coreml",
            artifactVersion = 1,
            artifacts = bytes.map { (role, content) -> ModelArtifactData(role, "models/$role", sha256(content), content.size.toLong()) },
            licence = "Apache-2.0",
        )
        assertNotNull(koin.get<ModelStore>().add(entry, bytes))
        listOf(ModelArtifactRole.VISION, ModelArtifactRole.TEXT).forEach { role ->
            val digest: String = sha256(bytes.getValue(role))
            files.createDirectoryAtPath("$root/inference/coreml/${digest.take(16)}/$role.mlmodelc", true, null, null)
            IosFileDatabase(root).writeRawByteArray("inference/coreml/${digest.take(16)}/ready", "digest=$digest\nosBuild=os\n".encodeToByteArray())
        }
        val readiness: AiReadiness = koin.get()
        readiness.fingerprintChecked()
        assertEquals(AiReadinessState.Ready, readiness.recordVerdict(true, org.tenshu.inference.proxy.coreml.iosPreflightKey(koin.get<ModelStore>().installed())))
        assertTrue(PhotosLocalRuntime.checkInferenceStores(this))
    }

    /** The face model, installed, prepared and passed: `Ready` through writer 2's verdict. */
    private suspend fun PhotosLocalRuntime.Assembly.faceReady(root: String) {
        installFaceModel()
        markFacePrepared(root)
        PhotosOnDeviceAi.recheck(koin)
        assertEquals(AiReadinessState.AwaitingVerdict, faceReadiness.current(), "the fixture: prepared, no verdict")
        assertEquals(AiReadinessState.Ready, faceReadiness.recordVerdict(true, iosFacePreflightKey(koin.get<ModelStore>().installed())))
    }

    private fun bytes(root: String, path: String): ByteArray? = IosFileDatabase(root).readBytes(path)?.get()

    private fun face() = StoredFace(FaceBox(0f, 0f, 0.1f, 0.1f), FloatArray(FaceModel.AuraFaceCoreMl.dimensions) { if (it == 0) 1f else 0f })

    private suspend fun PhotosLocalRuntime.Assembly.putTemplate(path: String = "/a.jpg") {
        // ⚠ The face backend's own stamp: a template of another model would be cleared as one.
        assertTrue(koin.get<FaceTemplateStore>().put(path, listOf(face()), koin.get<VisionCoreMlFaceBackend>().provenance))
    }

    private suspend fun PhotosLocalRuntime.Assembly.putVector(path: String = "/a.jpg") {
        assertTrue(koin.get<EmbeddingStore>().put(path, floatArrayOf(1f, 0f), org.tenshu.inference.ModelProvenance("siglip", 1)))
    }

    private suspend fun PhotosLocalRuntime.Assembly.templates(): Int = koin.get<FaceTemplateStore>().size()
    private suspend fun PhotosLocalRuntime.Assembly.vectors(): Int = koin.get<EmbeddingStore>().size()

    @Test
    fun theFacePairsCustodyKeepsItsOwnFileAndIsNotSearchs() {
        val custody: FaceTemplateCustody = photos().koin.get()
        assertEquals(ReadinessSlot.FACES.fingerprintPath, custody.fingerprintPath)
        assertNotEquals(InferenceStoreCustody.FINGERPRINT_PATH, custody.fingerprintPath)
        assertNotEquals(ReadinessSlot.SIGLIP.fingerprintPath, ReadinessSlot.FACES.fingerprintPath)
    }

    /**
     * ⚠⚠ §14.4a's trap, on the real graph: the face model entering `Ready` applies the FACE key to
     * the FACE custody. The templates it could not have made are cleared; the search vectors, and
     * SigLIP's fingerprint file, are not touched.
     */
    @Test
    fun theFaceModelBecomingReadyClearsOnlyTemplatesAndNeverTouchesSearch() = runBlocking {
        val root = temp("photos-faces")
        val photos = photos(root)
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.putVector()
        photos.putTemplate()
        val siglipFingerprint: ByteArray = assertNotNull(bytes(root, InferenceStoreCustody.FINGERPRINT_PATH), "the fixture: SigLIP's fingerprint is held")

        photos.faceReady(root)

        assertTrue(photos.koin.get<VisionCoreMlFaceBackend>().supports(InferenceCapability.FACE_CLUSTERING))
        assertEquals(setOf(InferenceCapability.FACE_CLUSTERING), photos.koin.get<InferenceBackendRegistry>().available)
        assertEquals(0, photos.templates(), "templates of an unknown making outlived the face model's first Ready")
        assertEquals(1, photos.vectors(), "the face model's Ready cleared the search vectors")
        assertContentEquals(siglipFingerprint, bytes(root, InferenceStoreCustody.FINGERPRINT_PATH), "the face key was written over SigLIP's fingerprint")
        val faceFingerprint: String = assertNotNull(bytes(root, ReadinessSlot.FACES.fingerprintPath)).decodeToString()
        assertTrue("\"detectorRevision\":${FaceModel.VISION_LANDMARKS_REVISION}" in faceFingerprint, faceFingerprint)
        // ⚠ And the search custody still reads its own fingerprint as current: nothing to clear.
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        assertEquals(1, photos.vectors())

        // A template made now stays across a second check of both.
        photos.putTemplate()
        PhotosOnDeviceAi.recheck(photos.koin)
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        assertEquals(1, photos.templates())
        assertEquals(AiReadinessState.Ready, photos.faceReadiness.current())
    }

    /** The face custody handed search's key refuses it, and the face backend then claims nothing. */
    @Test
    fun aSearchKeyHandedToTheFaceCustodyIsRefusedAndNothingIsServed() = runBlocking {
        val root = temp("photos-faces")
        val photos = photos(root)
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.faceReady(root)
        val held: ByteArray = assertNotNull(bytes(root, ReadinessSlot.FACES.fingerprintPath))

        val outcome = photos.koin.get<FaceTemplateCustody>().applyFingerprint(iosRuntimeFingerprint(photos.koin.get<ModelStore>().installed()))

        assertIs<FingerprintOutcome.Failed>(outcome)
        assertFalse(photos.koin.get<VisionCoreMlFaceBackend>().supports(InferenceCapability.FACE_CLUSTERING))
        assertContentEquals(held, bytes(root, ReadinessSlot.FACES.fingerprintPath))
    }

    // ─── model changes go to the readiness they are about ───────────────────────────────────────

    @Test
    fun aChangeToEachModelStandsOnlyThatModelDown() = runBlocking {
        val root = temp("photos-faces")
        val photos = photos(root)
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.searchReady(root)
        photos.faceReady(root)
        val changes: ModelStoreChanges = photos.koin.get()
        val search: AiReadiness = photos.koin.get()
        val face: VisionCoreMlFaceBackend = photos.koin.get()
        fun serving(): Pair<Boolean, Boolean> = (search.serving() != null) to face.supports(InferenceCapability.FACE_CLUSTERING)
        assertEquals(true to true, serving(), "the fixture: both models serve")

        val duringFaces: Pair<Boolean, Boolean> = changes.changing(FaceModel.AuraFaceCoreMl.modelId) { serving() }
        val duringSearch: Pair<Boolean, Boolean> = changes.changing(VisionModel.PinnedSigLip.modelId) { serving() }

        assertEquals(true to false, duringFaces, "a change to the face model: (search serving, faces serving)")
        assertEquals(false to true, duringSearch, "a change to search's model: (search serving, faces serving)")
        assertEquals(true to true, serving(), "a model did not come back once its change ended")
    }

    // ─── the realm stamp and withdrawal, on the graph with a face backend ───────────────────────

    private fun PhotosLocalRuntime.Assembly.context() =
        koin.get<ClientRegistry>().getExecutionContext(PhotosLocalRuntime.PHOTOS_CLIENT_ID)

    /** Face grouping's two switches as Settings writes them; `null` leaves a row absent. */
    private fun PhotosLocalRuntime.Assembly.consentToFaces(realm: Boolean?, device: Boolean?) {
        val context = context()
        val capability = InferenceCapability.FACE_CLUSTERING
        if (realm != null) {
            context.saveData(
                Entity.create<RealmSetting>(
                    context = context.context,
                    composite = RealmSetting.create(context = context.context, preferenceId = capability.realmConsent.id, value = capability.realmConsent.adapter.toData(realm, context.context)),
                ),
            )
        }
        if (device != null) {
            context.saveData(
                Entity.create<DeviceSetting>(
                    context = context.context,
                    composite = DeviceSetting.create(
                        context = context.context,
                        deviceId = koin.get<SyncManager>().currentDeviceId,
                        preferenceId = capability.deviceConsent.id,
                        value = capability.deviceConsent.adapter.toData(device, context.context),
                    ),
                ),
            )
        }
    }

    private suspend fun PhotosLocalRuntime.Assembly.faceGroupsRefusal(): String {
        val answer = tenshuClient.inferenceService.faceGroups(10)
        val refusal = assertIs<TResult.Failure<*>>(answer, "faceGroups answered: $answer").error
        return assertIs<FaceGroupsUnavailable>(refusal).reason.get()
    }

    /** The fixture every case below starts from: faces `Ready`, consent given, one template held, served. */
    private suspend fun servingPeople(root: String): PhotosLocalRuntime.Assembly {
        val photos = photos(root)
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.faceReady(root)
        photos.consentToFaces(realm = true, device = true)
        photos.putTemplate("/a.jpg")
        photos.putTemplate("/b.jpg")
        assertIs<TResult.Success<*>>(photos.tenshuClient.inferenceService.faceGroups(10), "the fixture: People is served")
        return photos
    }

    @Test
    fun anOwnerChangeClearsTheTemplatesBeforeFaceGroupsServes() = runBlocking {
        val photos = servingPeople(temp("photos-faces"))

        deviceOwner = "owner-b"
        photos.faceGroupsRefusal()

        assertEquals(0, photos.templates(), "the old realm's templates outlived the People request")
    }

    /** ⚠⚠ The epoch half (§2.2 item 8): a reset that keeps the owner, which a path-keyed store cannot see. */
    @Test
    fun anEpochChangeClearsTheTemplatesBeforeFaceGroupsServes() = runBlocking {
        val photos = servingPeople(temp("photos-faces"))

        epoch = 2L
        val answer = photos.tenshuClient.inferenceService.faceGroups(10)

        assertEquals(0, photos.templates(), "the previous store generation's templates outlived the People request: $answer")
    }

    /** ⚠⚠ The gate decides what is served, never what is deleted: no realm at all, and the withdrawal still deletes. */
    @Test
    fun aWithdrawalDeletesTheTemplatesThroughAGateWithNoRealm() = runBlocking {
        val photos = servingPeople(temp("photos-faces"))
        photos.consentToFaces(realm = false, device = null)
        deviceOwner = null

        photos.faceGroupsRefusal()

        assertEquals(0, photos.templates(), "a closed gate kept a withdrawn user's templates on disk")
    }

    /**
     * The iOS graph's twin of `aWithdrawalDeletesTheTemplatesThroughAGateAFingerprintFailureClosed`:
     * the FACE custody has failed (it was handed search's key), so nothing is served; the user then
     * switches face grouping off on this device.
     */
    @Test
    fun aWithdrawalDeletesTheTemplatesWhileTheFaceCustodyHasFailed() = runBlocking {
        val photos = servingPeople(temp("photos-faces"))
        assertIs<FingerprintOutcome.Failed>(photos.koin.get<FaceTemplateCustody>().applyFingerprint(iosRuntimeFingerprint(emptyList())))
        assertFalse(photos.koin.get<VisionCoreMlFaceBackend>().supports(InferenceCapability.FACE_CLUSTERING), "the fixture: nothing is served")
        photos.consentToFaces(realm = null, device = false)

        photos.faceGroupsRefusal()

        assertEquals(0, photos.templates(), "a failed custody kept a withdrawn user's templates on disk")
    }

    @Test
    fun anAbsentConsentRowRefusesAndDeletesNothing() = runBlocking {
        val root = temp("photos-faces")
        val photos = photos(root)
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.faceReady(root)
        photos.putTemplate()

        photos.faceGroupsRefusal()

        assertEquals(1, photos.templates(), "absence is NO, not a withdrawal")
    }

    // ─── teardown ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun aTornDownGraphClaimsNoFaceAndItsFaceReadinessIsRetired() = runBlocking {
        val root = temp("photos-faces")
        val photos = photos(root)
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.faceReady(root)

        val unloading = assertNotNull(PhotosOnDeviceAi.retire(photos.koin))

        assertTrue(photos.faceReadiness.isRetired(), "the face readiness outlived its graph")
        assertFalse(photos.koin.get<VisionCoreMlFaceBackend>().supports(InferenceCapability.FACE_CLUSTERING))
        withTimeout(30_000) { unloading.join() }
        assertEquals(AiReadinessState.NoModel, photos.faceReadiness.current())
    }
}
