@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.tenshu.sdk.ios

import app.tenshu.control.di.OwnedInferenceStores
import app.tenshu.control.di.PhotosOnDeviceAi
import app.tenshu.control.inference.CombinedModelSource
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.OnDeviceModelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.TResult
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.IosFileDatabase
import org.tenshu.core.common.util.Sha256Digest
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.DataId
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.MediaLabel
import org.tenshu.inference.client.inferenceService
import org.tenshu.inference.deleteModel
import org.tenshu.inference.faceGroups
import org.tenshu.inference.nameFaceGroup
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.AiReadinessState
import org.tenshu.inference.proxy.FaceBox
import org.tenshu.inference.proxy.FaceLandmarks
import org.tenshu.inference.proxy.FacePoint
import org.tenshu.inference.proxy.FacePreflightModels
import org.tenshu.inference.proxy.FacePreflightProbe
import org.tenshu.inference.proxy.FacePreflightProbeLookup
import org.tenshu.inference.proxy.FaceProbeDetection
import org.tenshu.inference.proxy.FaceProbeFace
import org.tenshu.inference.proxy.FaceProbeImage
import org.tenshu.inference.proxy.FaceProbeImages
import org.tenshu.inference.proxy.FaceTemplateStore
import org.tenshu.inference.proxy.ModelPreparer
import org.tenshu.inference.proxy.Preflight
import org.tenshu.inference.proxy.PreflightDevice
import org.tenshu.inference.proxy.PreflightOutcome
import org.tenshu.inference.proxy.PreflightTower
import org.tenshu.inference.proxy.StoredFace
import org.tenshu.inference.proxy.catalog.ModelArtifactData
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.coreml.CoreMlCompiler
import org.tenshu.inference.proxy.coreml.CoreMlTowerSpec
import org.tenshu.inference.proxy.coreml.VisionCoreMlFaceBackend
import org.tenshu.inference.proxy.coreml.iosPreflightKey
import org.tenshu.inference.proxy.onnx.FaceModel
import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import org.tenshu.inference.proxy.onnx.VisionModel
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.RealmSetting
import org.tenshu.sync.common.SyncManager
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `ios-face-grouping.md` F6 on Photos' own graph: the face model is **prepared** (compiled, first
 * opened, marked) by its own [ModelPreparer] and **probed** by its own [Preflight] over F4's
 * `FacePreflightProber`, both run by the check every launch and foreground runs, to the face slot's
 * `Ready`; the Models list shows it beside search's; its removal takes its archive and its compiled
 * tower and nothing of SigLIP's; and the switch turned off on the Models screen deletes this
 * phone's templates **now**, keeps the names, and an absent row deletes nothing.
 *
 * ⚠ Core ML and Vision are stand-ins (a compiler that compiles nothing, a detector and an embedder
 * that answer as a sound model would); the probe's protocol, its judge and every lock are real.
 */
@OptIn(ExperimentalEncodingApi::class)
class PhotosFaceModelTest {

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

    private val replica: String = temp("photos-face-model-replica")

    private fun photos(root: String, faces: Boolean = true): PhotosLocalRuntime.Assembly = PhotosLocalRuntime.assemble(
        replicaDirectory = replica,
        secureStorage = InMemorySecureStorageDelegate(),
        handedOffMaterial = material(),
        relayBaseUrl = "http://127.0.0.1:1",
        inferenceRoot = root,
        deviceRealmOwner = { "owner-a" },
        replicaEpoch = { 1L },
        onDeviceAi = PhotosLocalRuntime.OnDeviceAiInputs(buildFlag = true, osMajorVersion = 17, physicalMemoryBytes = 8L shl 30, facesBuildFlag = faces),
        // ⚠⚠ No catalog: a Models source here would otherwise ask the real CDN.
        onDeviceAiOverrides = noCatalogThen(stand_ins()),
    )

    @OptIn(ExperimentalStdlibApi::class)
    private fun sha256(bytes: ByteArray): String = Sha256Digest().apply { update(bytes, bytes.size) }.digest().toHexString()

    private suspend fun eventually(what: String, condition: suspend () -> Boolean) {
        runCatching { withTimeout(30_000) { while (!condition()) delay(20) } }
        assertTrue(condition(), what)
    }

    // ─── the stand-ins ──────────────────────────────────────────────────────────────────────────

    /** Leaves a compiled directory and loads nothing, for whichever tower it is asked. */
    private class CompilesNothing(val compiled: MutableList<String>) : CoreMlCompiler {
        override fun compile(packagePath: String): String {
            val temporary = "${NSTemporaryDirectory()}compiled-${Random.nextLong().toString(16)}.mlmodelc"
            NSFileManager.defaultManager.createDirectoryAtPath(temporary, true, null, null)
            NSFileManager.defaultManager.createFileAtPath("$temporary/model.mil", null, null)
            return temporary
        }

        override fun firstOpen(compiledPath: String, spec: CoreMlTowerSpec) {
            compiled += spec.role
        }
    }

    private val compiled = mutableListOf<String>()
    private val width = FaceModel.AuraFaceCoreMl.dimensions

    private fun vector(length: Float, along: Float = 1f, axis: Int = 1): FloatArray = FloatArray(width).also {
        it[0] = length * along
        if (along != 1f) it[axis] = length * sqrt(1f - along * along)
    }

    private val profile = FaceModel.AuraFaceCoreMl.profile
    private val referenceTensor = ImagePreprocessing.preprocessPixels(FaceProbeImages.reference(), FaceProbeImages.SIDE, profile)
    private val brighterTensor = ImagePreprocessing.preprocessPixels(FaceProbeImages.brighter(), FaceProbeImages.SIDE, profile)
    private val shiftedTensor = ImagePreprocessing.preprocessPixels(FaceProbeImages.shifted(), FaceProbeImages.SIDE, profile)
    private val otherTensor = ImagePreprocessing.preprocessPixels(FaceProbeImages.other(), FaceProbeImages.SIDE, profile)
    private val photo = FaceProbeImage(64, 64, IntArray(64 * 64) { (0xFF shl 24) or (it * 977 and 0xFFFFFF) })

    /** Which embedders the probe opened, and how many are open now. */
    private val probedEmbedders = mutableListOf<String>()
    private var openEmbedders = 0

    /** A detector and an embedder that answer the probe as a sound pair would. */
    private val models = object : FacePreflightModels {
        override fun faceProbe(): FaceProbeImage = photo

        override fun openEmbedder(compiledPath: String): PreflightTower {
            probedEmbedders += compiledPath
            openEmbedders++
            return object : PreflightTower {
                override fun predict(floats: FloatArray): FloatArray = when {
                    floats.contentEquals(referenceTensor) -> vector(20f)
                    floats.contentEquals(brighterTensor) -> vector(20f, along = 0.95f, axis = 1)
                    floats.contentEquals(shiftedTensor) -> vector(20f, along = 0.9f, axis = 2)
                    floats.contentEquals(otherTensor) -> vector(20f, along = 0.2f, axis = 3)
                    else -> vector(20f, along = 0f, axis = 5)
                }

                override fun predict(ids: IntArray): FloatArray = error("pixels only")
                override fun close() {
                    openEmbedders--
                }
            }
        }

        override fun detect(argb: IntArray, width: Int, height: Int): FaceProbeDetection = FaceProbeDetection(
            FaceModel.VISION_LANDMARKS_REVISION,
            listOf(
                FaceProbeFace(
                    FaceBox(0.2f, 0.2f, 0.8f, 0.8f),
                    FaceLandmarks(FacePoint(0.35f, 0.4f), FacePoint(0.65f, 0.4f), FacePoint(0.5f, 0.55f), FacePoint(0.38f, 0.7f), FacePoint(0.62f, 0.7f)),
                ),
            ),
        )
    }

    private val faceArchive: ByteArray = towerArchive("auraface_fp16")

    /** The probe this build would carry for [faceArchive]: measured on it, and on nothing else. */
    private val probe = FacePreflightProbe(
        id = "test",
        embedderArchiveSha256 = sha256(faceArchive),
        imagesSha256 = FaceProbeImages.SHA256,
        hostMargin = 0.7,
        minMargin = 0.35,
        hostNorm = 20.0,
        normBand = 2.0,
        faceHostMargin = 0.6,
        faceMinMargin = 0.3,
        hostReference = FloatArray(FaceModel.AuraFaceCoreMl.dimensions).also { it[0] = 20f },
        minHostCosine = 0.99,
    )

    private fun stand_ins(): Module = module {
        single<CoreMlCompiler> { CompilesNothing(compiled) }
        single<FacePreflightModels> { models }
        single<FacePreflightProbeLookup> { FacePreflightProbeLookup { key -> probe.takeIf { it.isFor(key) } } }
        single<PreflightDevice> {
            object : PreflightDevice {
                override fun thermalState(): Int = 0
                override fun memoryWarnings(): Long = 0
                override fun footprintBytes(): Long? = null
                override fun availableBytes(): Long? = null
                override fun nowEpochMs(): Long = 1_000_000
            }
        }
    }

    /** A ustar archive holding one `<stem>.mlpackage` directory, as the export script writes a tower. */
    private fun towerArchive(stem: String): ByteArray {
        fun entry(name: String, content: ByteArray, type: Char): ByteArray {
            val header = ByteArray(512)
            fun put(offset: Int, text: String) = text.encodeToByteArray().copyInto(header, offset)
            put(0, name)
            put(100, "0000644")
            put(108, "0000000")
            put(116, "0000000")
            put(124, content.size.toString(8).padStart(11, '0'))
            put(136, "00000000000")
            header[156] = type.code.toByte()
            put(257, "ustar")
            put(263, "00")
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            put(148, header.sumOf { it.toInt() and 0xFF }.toString(8).padStart(6, '0'))
            header[154] = 0
            return header + content.copyOf((content.size + 511) / 512 * 512)
        }
        return entry("$stem.mlpackage", ByteArray(0), '5') +
            entry("$stem.mlpackage/Manifest.json", "{}".encodeToByteArray(), '0') +
            entry("$stem.mlpackage/Data/weights/weight.bin", stem.encodeToByteArray(), '0') +
            ByteArray(1024)
    }

    // ─── fixtures on the graph ──────────────────────────────────────────────────────────────────

    private val PhotosLocalRuntime.Assembly.faceReadiness: AiReadiness get() = koin.get(named(PhotosOnDeviceAi.FACE_READINESS))
    private val PhotosLocalRuntime.Assembly.facePreflight: Preflight get() = koin.get(named(PhotosOnDeviceAi.FACE_PREFLIGHT))
    private val PhotosLocalRuntime.Assembly.facePreparer: ModelPreparer get() = koin.get(named(PhotosOnDeviceAi.FACE_PREPARER))

    /** The face entry as the store holds it after a verified download. */
    private fun PhotosLocalRuntime.Assembly.installFaceModel() {
        val entry = ModelEntryData(
            modelId = FaceModel.AuraFaceCoreMl.modelId,
            name = "People (AuraFace + Apple Vision)",
            modelVersion = 1,
            capabilities = listOf(InferenceCapability.FACE_CLUSTERING),
            labelSetVersion = 1,
            runtime = FaceModel.COREML_VISION_RUNTIME,
            artifactVersion = 1,
            artifacts = listOf(ModelArtifactData(ModelArtifactRole.FACE_EMBEDDER, "models/faceEmbedder", sha256(faceArchive), faceArchive.size.toLong())),
            licence = "Apache-2.0",
        )
        assertNotNull(koin.get<ModelStore>().add(entry, mapOf(ModelArtifactRole.FACE_EMBEDDER to faceArchive)))
    }

    private val siglipBytes: Map<String, ByteArray> = listOf(ModelArtifactRole.VISION, ModelArtifactRole.TEXT, ModelArtifactRole.VOCABULARY)
        .associateWith { role -> "siglip-$role".encodeToByteArray() }

    /** SigLIP installed, its towers marked and its verdict passed: search `Ready` through its own writers. */
    private suspend fun PhotosLocalRuntime.Assembly.searchReady(root: String) {
        val entry = ModelEntryData(
            modelId = VisionModel.PinnedSigLip.modelId, name = "SigLIP", modelVersion = 1,
            capabilities = listOf(InferenceCapability.SEMANTIC_IMAGE_SEARCH), labelSetVersion = 1,
            runtime = "coreml", artifactVersion = 1,
            artifacts = siglipBytes.map { (role, content) -> ModelArtifactData(role, "models/$role", sha256(content), content.size.toLong()) },
            licence = "Apache-2.0",
        )
        assertNotNull(koin.get<ModelStore>().add(entry, siglipBytes))
        listOf(ModelArtifactRole.VISION, ModelArtifactRole.TEXT).forEach { role -> markTower(root, role, siglipBytes.getValue(role)) }
        val readiness: AiReadiness = koin.get()
        readiness.fingerprintChecked()
        assertEquals(AiReadinessState.Ready, readiness.recordVerdict(true, iosPreflightKey(koin.get<ModelStore>().installed())))
    }

    private fun markTower(root: String, role: String, bytes: ByteArray) {
        val digest: String = sha256(bytes)
        files.createDirectoryAtPath("$root/inference/coreml/${digest.take(16)}/$role.mlmodelc", true, null, null)
        IosFileDatabase(root).writeRawByteArray("inference/coreml/${digest.take(16)}/ready", "digest=$digest\nosBuild=os\n".encodeToByteArray())
    }

    private fun faceTower(root: String): String = "$root/inference/coreml/${sha256(faceArchive).take(16)}"

    /** Installed, then the check every launch runs: the face model's prepare and probe, to `Ready`. */
    private suspend fun faceModelThroughTheCheck(root: String): PhotosLocalRuntime.Assembly {
        val photos = photos(root)
        assertTrue(PhotosLocalRuntime.checkInferenceStores(photos))
        photos.installFaceModel()
        assertEquals(AiReadinessState.Preparing, photos.faceReadiness.fingerprintChecked(), "the fixture: installed, not compiled")
        PhotosOnDeviceAi.recheck(photos.koin, launch = true)
        return photos
    }

    // ─── prepare and probe ──────────────────────────────────────────────────────────────────────

    /** ⭐ F6's row: installed → compiled and first opened → probed → `Ready`, by the one check. */
    @Test
    fun theFaceModelIsPreparedAndProbedToReadyByTheCheckEveryLaunchRuns() = runBlocking {
        val root = temp("photos-face-model")
        val photos = faceModelThroughTheCheck(root)

        assertEquals(AiReadinessState.Ready, photos.faceReadiness.current())
        assertEquals(listOf(ModelArtifactRole.FACE_EMBEDDER), compiled, "the face tower was not compiled and first opened, or SigLIP's was too")
        assertTrue(files.fileExistsAtPath("${faceTower(root)}/${ModelArtifactRole.FACE_EMBEDDER}.mlmodelc/model.mil"), "no compiled face embedder")
        assertTrue(files.fileExistsAtPath("${faceTower(root)}/ready"), "no ready marker")
        assertEquals(listOf("${faceTower(root)}/${ModelArtifactRole.FACE_EMBEDDER}.mlmodelc"), probedEmbedders, "the probe did not open the compiled embedder")
        assertEquals(0, openEmbedders, "the probe left the embedder open")
        assertIs<PreflightOutcome.Passed>(photos.facePreflight.lastReport.value?.outcome)
        assertTrue(photos.koin.get<VisionCoreMlFaceBackend>().supports(InferenceCapability.FACE_CLUSTERING))
        // ⚠⚠ Search's half saw none of it.
        assertEquals(AiReadinessState.NoModel, photos.koin.get<AiReadiness>().current())
        assertFalse(files.fileExistsAtPath("$root/${AiReadiness.VERDICT_PATH}"), "the face verdict was written in search's file")
    }

    /** A second check of a `Ready` face model compiles nothing and probes nothing again. */
    @Test
    fun aReadyFaceModelIsLeftAsItIsByTheNextCheck() = runBlocking {
        val root = temp("photos-face-model")
        val photos = faceModelThroughTheCheck(root)
        PhotosOnDeviceAi.recheck(photos.koin)
        assertEquals(1, compiled.size)
        assertEquals(1, probedEmbedders.size)
        assertEquals(AiReadinessState.Ready, photos.faceReadiness.current())
    }

    /** The face check is the face flag's: without it no face preparer or probe exists, and search's check is as it was. */
    @Test
    fun withoutTheFaceFlagThereIsNoFacePrepareOrProbe() = runBlocking {
        val photos = photos(temp("photos-face-model"), faces = false)
        assertTrue(photos.koin.getOrNull<ModelPreparer>(named(PhotosOnDeviceAi.FACE_PREPARER)) == null)
        assertTrue(photos.koin.getOrNull<Preflight>(named(PhotosOnDeviceAi.FACE_PREFLIGHT)) == null)
        assertFalse(PhotosOnDeviceAi.modelSource(photos.koin) is CombinedModelSource)
    }

    /** ⚠ A teardown of a graph that never probed a face constructs no face probe (nor its readiness). */
    @Test
    fun aTeardownBuildsNoFaceProbe() = runBlocking {
        val photos = photos(temp("photos-face-model"))
        assertEquals(null, PhotosOnDeviceAi.builtFacePreflight(photos.koin), "the fixture: no face probe yet")
        PhotosOnDeviceAi.retire(photos.koin)?.join()
        assertEquals(null, PhotosOnDeviceAi.builtFacePreflight(photos.koin), "the teardown built the face probe")
    }

    /** Before any catalog (a cold, offline launch) the screen knows which model is face grouping's. */
    @Test
    fun theModelsScreenKnowsWhichModelIsWhoseWithNoCatalog() = runBlocking {
        val photos = photos(temp("photos-face-model"))
        assertEquals(
            mapOf(VisionModel.PinnedSigLip.modelId to InferenceCapability.SEMANTIC_IMAGE_SEARCH, FaceModel.AuraFaceCoreMl.modelId to InferenceCapability.FACE_CLUSTERING),
            photos.modelsRepository().capabilities.modelFeatures,
        )
        val without = photos(temp("photos-face-model"), faces = false)
        assertEquals(emptyMap(), without.modelsRepository().capabilities.modelFeatures, "faces off changed search's capabilities")
        // ⚠ The fixture's ids are the graph's.
        assertEquals(FaceModel.AuraFaceCoreMl.modelId, PhotosModelsFixture.FACE_MODEL_ID)
        assertEquals(VisionModel.PinnedSigLip.modelId, PhotosModelsFixture.MODEL_ID)
        PhotosOnDeviceAi.retire(photos.koin)?.join()
        PhotosOnDeviceAi.retire(without.koin)?.join()
        Unit
    }

    // ─── the Models list ────────────────────────────────────────────────────────────────────────

    @Test
    fun theModelsListShowsTheFaceModelFromItsOwnReadiness() = runBlocking {
        val root = temp("photos-face-model")
        val photos = faceModelThroughTheCheck(root)
        val source: OnDeviceModelSource = assertNotNull(PhotosOnDeviceAi.modelSource(photos.koin))
        assertIs<CombinedModelSource>(source)

        val listed: ModelsAvailability.Listed = assertIs(source.models.first { it is ModelsAvailability.Listed && it.models.isNotEmpty() })
        val row = listed.models.single()
        assertEquals(FaceModel.AuraFaceCoreMl.modelId, row.modelId)
        val held = assertIs<ModelSetup.Held>(row.setup)
        assertEquals(ModelSetup.Standing.Ready, held.standing)
        assertEquals(faceArchive.size.toLong() + org.tenshu.inference.proxy.catalog.CoreMlDiskBudget.prepareBytes(faceArchive.size.toLong()), held.model.onDeviceBytes)
        PhotosOnDeviceAi.retire(photos.koin)?.join()
        Unit
    }

    // ─── the removal ────────────────────────────────────────────────────────────────────────────

    /** ⚠⚠ Its archive and its compiled tower go; SigLIP's record, archives, towers and `Ready` stay. */
    @Test
    fun removingTheFaceModelTakesItsArchiveAndTowerAndNothingOfSearchs() = runBlocking {
        val root = temp("photos-face-model")
        val photos = faceModelThroughTheCheck(root)
        photos.searchReady(root)
        val faceArchivePath: String = photos.koin.get<ModelStore>().installed().single { it.modelId == FaceModel.AuraFaceCoreMl.modelId }.artifacts.single().localPath
        val siglipPaths: List<String> = photos.koin.get<ModelStore>().installed().single { it.modelId == VisionModel.PinnedSigLip.modelId }.artifacts.map { it.localPath }
        assertNotNull(IosFileDatabase(root).readBytes(faceArchivePath)?.get(), "the fixture: the face archive is held")

        val answer = photos.tenshuClient.inferenceService.deleteModel(FaceModel.AuraFaceCoreMl.modelId)

        assertIs<TResult.Success<*>>(answer, "deleteModel answered: $answer")
        assertEquals(listOf(VisionModel.PinnedSigLip.modelId), photos.koin.get<ModelStore>().installed().map { it.modelId })
        // ⚠ The compiled tower goes WITH the removal (`ModelPreparer.evicting`), before the record.
        assertFalse(files.fileExistsAtPath(faceTower(root)), "the face model's compiled tower outlived its removal")
        assertEquals(AiReadinessState.NoModel, photos.faceReadiness.current())
        // SigLIP: every archive, both towers and its Ready.
        siglipPaths.forEach { path -> assertNotNull(IosFileDatabase(root).readBytes(path)?.get(), "SigLIP's $path went with the face model") }
        listOf(ModelArtifactRole.VISION, ModelArtifactRole.TEXT).forEach { role ->
            val directory = "$root/inference/coreml/${sha256(siglipBytes.getValue(role)).take(16)}"
            assertTrue(files.fileExistsAtPath("$directory/$role.mlmodelc"), "SigLIP's $role tower went with the face model")
            assertTrue(files.fileExistsAtPath("$directory/ready"), "SigLIP's $role marker went with the face model")
        }
        assertEquals(AiReadinessState.Ready, photos.koin.get<AiReadiness>().current(), "the face model's removal stood search down")

        // ⚠ The archive is the store's declared garbage, released when Photos next starts — as
        // SigLIP's is (`ModelsMessage.RemovedSpacePending`): the next process's store sweeps it.
        PhotosOnDeviceAi.retire(photos.koin)?.join()
        val next = photos(root)
        assertTrue(PhotosLocalRuntime.checkInferenceStores(next))
        assertEquals(listOf(VisionModel.PinnedSigLip.modelId), next.koin.get<ModelStore>().installed().map { it.modelId })
        assertEquals(null, IosFileDatabase(root).readBytes(faceArchivePath)?.get(), "the face archive outlived the next start")
        siglipPaths.forEach { path -> assertNotNull(IosFileDatabase(root).readBytes(path)?.get(), "the next start swept SigLIP's $path") }
        PhotosOnDeviceAi.retire(next.koin)?.join()
        Unit
    }

    // ─── the switch, through the Models screen's own repository ─────────────────────────────────

    private fun PhotosLocalRuntime.Assembly.consentToFaces(realm: Boolean?, device: Boolean?) {
        val context = koin.get<ClientRegistry>().getExecutionContext(PhotosLocalRuntime.PHOTOS_CLIENT_ID)
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

    private suspend fun PhotosLocalRuntime.Assembly.putTemplate(path: String) {
        val face = StoredFace(FaceBox(0f, 0f, 0.1f, 0.1f), FloatArray(width) { if (it == 0) 1f else 0f })
        assertTrue(koin.get<FaceTemplateStore>().put(path, listOf(face), koin.get<VisionCoreMlFaceBackend>().provenance))
    }

    private suspend fun PhotosLocalRuntime.Assembly.templates(): Int = koin.get<FaceTemplateStore>().size()

    /** Whether the name typed for [path] is still stored (the synced `MediaLabel` row). */
    private fun PhotosLocalRuntime.Assembly.named(path: String, name: String): Boolean {
        val context = koin.get<ClientRegistry>().getExecutionContext(PhotosLocalRuntime.PHOTOS_CLIENT_ID)
        val id = DataId.StringDataId.create(context = context.context, type = MediaLabel.ID, keyValue = MediaLabel.labelKey(path, InferenceCapability.FACE_CLUSTERING, name))
        return context.database.getData(id) != null
    }

    /** Faces `Ready` through its own prepare and probe, the switch on, two templates and a name. */
    private suspend fun peopleWithAName(root: String): PhotosLocalRuntime.Assembly {
        val photos = faceModelThroughTheCheck(root)
        photos.consentToFaces(realm = true, device = true)
        photos.putTemplate("/a.jpg")
        photos.putTemplate("/b.jpg")
        assertIs<TResult.Success<*>>(photos.tenshuClient.inferenceService.faceGroups(10), "the fixture: People is served")
        assertIs<TResult.Success<*>>(photos.tenshuClient.inferenceService.nameFaceGroup(listOf("/a.jpg"), "Ada"), "the fixture: a name is typed")
        assertTrue(photos.named("/a.jpg", "Ada"))
        return photos
    }

    /**
     * ⚠⚠ The Models screen's *Turn off* (this device's switch) deletes this phone's templates
     * **now**, through the operation's withdrawal-first deletion, and the typed name stays.
     */
    @Test
    fun turningFacesOffOnTheModelsScreenDeletesTheTemplatesNowAndKeepsTheNames() = runBlocking {
        val photos = peopleWithAName(temp("photos-face-model"))
        assertEquals(2, photos.templates())

        assertTrue(photos.modelsRepository().setDeviceConsent(InferenceCapability.FACE_CLUSTERING, false), "the switch was not written")

        eventually("the withdrawn user's templates are still on disk: ${photos.templates()}") { photos.templates() == 0 }
        assertTrue(photos.named("/a.jpg", "Ada"), "the name went with the face data")
        PhotosOnDeviceAi.retire(photos.koin)?.join()
        Unit
    }

    /** The same for *Turn off everywhere*'s last write, the realm's no. */
    @Test
    fun theRealmsNoDeletesTheTemplatesNowAndKeepsTheNames() = runBlocking {
        val photos = peopleWithAName(temp("photos-face-model"))

        assertTrue(photos.modelsRepository().setRealmConsent(InferenceCapability.FACE_CLUSTERING, false))

        eventually("the withdrawn user's templates are still on disk: ${photos.templates()}") { photos.templates() == 0 }
        assertTrue(photos.named("/a.jpg", "Ada"))
        PhotosOnDeviceAi.retire(photos.koin)?.join()
        Unit
    }

    /**
     * ⚠⚠ Absence is NO and is not a withdrawal: with no row written, the screen's withdrawal hook
     * running anyway (search's switch turned off, which tells every source) deletes nothing.
     */
    @Test
    fun anAbsentFaceRowDeletesNothingWhenTheScreenSaysAWithdrawal() = runBlocking {
        val photos = faceModelThroughTheCheck(temp("photos-face-model"))
        photos.putTemplate("/a.jpg")
        val source: OnDeviceModelSource = assertNotNull(PhotosOnDeviceAi.modelSource(photos.koin))

        source.consentWithdrawn(InferenceCapability.FACE_CLUSTERING)
        assertTrue(photos.modelsRepository().setDeviceConsent(InferenceCapability.SEMANTIC_IMAGE_SEARCH, false))
        delay(500)

        assertEquals(1, photos.templates(), "absence deleted a template")
        PhotosOnDeviceAi.retire(photos.koin)?.join()
        Unit
    }
}
