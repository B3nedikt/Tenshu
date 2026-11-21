package org.tenshu.inference.proxy.coreml

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.MediaWorkBudget
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.AiModelFacts
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.FaceBox
import org.tenshu.inference.proxy.FaceTemplateCustody
import org.tenshu.inference.proxy.PET_PREFLIGHT_REVISION
import org.tenshu.inference.proxy.PetBackend
import org.tenshu.inference.proxy.PetOutcome
import org.tenshu.inference.proxy.PetPreflightDetector
import org.tenshu.inference.proxy.PetPreflightModels
import org.tenshu.inference.proxy.PetSelection
import org.tenshu.inference.proxy.PixelPetBackend
import org.tenshu.inference.proxy.PreflightKey
import org.tenshu.inference.proxy.PreflightTower
import org.tenshu.inference.proxy.PreparedModel
import org.tenshu.inference.proxy.ReadinessSlot
import org.tenshu.inference.proxy.RuntimeFingerprint
import org.tenshu.inference.proxy.StoredFace
import org.tenshu.inference.proxy.catalog.InstalledModelData
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.onnx.AnimalDetectorModel
import org.tenshu.inference.proxy.onnx.PetCropInput
import org.tenshu.inference.proxy.onnx.PetEmbedderModel
import org.tenshu.inference.proxy.petArchivePair
import org.tenshu.inference.proxy.vision.FaceImage
import org.tenshu.inference.proxy.vision.FaceImageDecoder
import kotlin.math.sqrt

/**
 * Pets on iOS (`photos-pets.md` PT-4b2): readiness, fingerprint, preflight models and the backend, over
 * Core ML RF-DETR + DINOv2 (§10.13 — not Vision). The face pair's file is the template: every path and
 * every claim comes from the pets [AiReadiness] ([ReadinessSlot.PETS]), never from an install record.
 */

/** One pet checkpoint's install record and its artifact for [role]. ⚠ By id: the other models' records are other models. */
private fun List<InstalledModelData>.petArtifact(modelId: String, role: String): Pair<InstalledModelData, String>? =
    firstNotNullOfOrNull { record ->
        if (record.modelId != modelId) return@firstNotNullOfOrNull null
        record.artifacts.firstOrNull { it.role == role }?.sha256?.let { record to it }
    }

private fun List<InstalledModelData>.embedderDigest(): String? =
    petArtifact(PetEmbedderModel.DinoV2SmallZeroShotCoreMl.modelId, ModelArtifactRole.PET_EMBEDDER)?.second

private fun List<InstalledModelData>.detectorDigest(): String? =
    petArtifact(AnimalDetectorModel.RfDetrNanoCoreMl.modelId, ModelArtifactRole.ANIMAL_DETECTOR)?.second

/**
 * What [store] and [cache] hold of the two pet models, **as the disk has it**: installed only when BOTH
 * are, prepared only when both are compiled for their installed digests — half a pipeline indexes nothing.
 */
fun coreMlPetModelFacts(store: ModelStore, cache: CoreMlModelCache): AiModelFacts {
    val installed: List<InstalledModelData> = store.installed()
    val embedder: String? = installed.embedderDigest()
    val detector: String? = installed.detectorDigest()
    if (embedder == null || detector == null) return AiModelFacts(installed = false, prepared = null)
    fun compiled(role: String, digest: String): String? = try {
        cache.compiledPath(role, digest)
    } catch (notADigest: IllegalArgumentException) {
        null
    }
    val embedderPath = compiled(ModelArtifactRole.PET_EMBEDDER, embedder)
    val detectorPath = compiled(ModelArtifactRole.ANIMAL_DETECTOR, detector)
    val prepared = if (embedderPath != null && detectorPath != null) {
        PreparedModel(mapOf(ModelArtifactRole.PET_EMBEDDER to embedderPath, ModelArtifactRole.ANIMAL_DETECTOR to detectorPath))
    } else {
        null
    }
    return AiModelFacts(installed = true, prepared = prepared)
}

/** Why readiness does not read the installed pair as prepared, by the very reading above; `null` when it does. */
fun coreMlPetsNotPrepared(store: ModelStore, cache: CoreMlModelCache): String? {
    val facts = coreMlPetModelFacts(store, cache)
    if (!facts.installed || facts.prepared != null) return null
    return "not compiled: [${ModelArtifactRole.ANIMAL_DETECTOR}, ${ModelArtifactRole.PET_EMBEDDER}]"
}

/**
 * What the pets pipeline would make a template with: BOTH archives (one identity, [petArchivePair]), the
 * compute units, the OS build, the face decode's revision (pets decode through it) and the pipeline.
 * ⚠ No text archive, and a non-null detector revision: the shape `FaceTemplateCustody` accepts.
 */
fun iosPetRuntimeFingerprint(embedderSha256: String?, detectorSha256: String?, osBuild: String): RuntimeFingerprint =
    RuntimeFingerprint(
        visionArchiveSha256 = petArchivePair(embedderSha256, detectorSha256),
        textArchiveSha256 = null,
        computeUnits = CoreMlComputeUnits.SHIPPED.name,
        osBuild = osBuild,
        preprocessingRevision = IOS_FACE_PREPROCESSING_REVISION,
        detectorRevision = PixelPetBackend.PET_PIPELINE_VERSION,
    )

/** The pets preflight key: the fingerprint above, the app build and [PET_PREFLIGHT_REVISION]. */
fun iosPetPreflightKey(
    installed: List<InstalledModelData>,
    appBuild: String = currentAppBuild(),
    osBuild: String = currentOsBuild(),
): PreflightKey = PreflightKey(
    runtime = iosPetRuntimeFingerprint(installed.embedderDigest(), installed.detectorDigest(), osBuild),
    vocabularySha256 = null,
    appBuild = appBuild,
    preflightRevision = PET_PREFLIGHT_REVISION,
)

/** The key two catalog entries would have once installed — what a Models screen asks before the download. */
fun iosPetPreflightKeyOf(
    embedder: ModelEntryData,
    detector: ModelEntryData,
    appBuild: String = currentAppBuild(),
    osBuild: String = currentOsBuild(),
): PreflightKey = PreflightKey(
    runtime = iosPetRuntimeFingerprint(
        embedder.artifacts.firstOrNull { it.role == ModelArtifactRole.PET_EMBEDDER }?.sha256,
        detector.artifacts.firstOrNull { it.role == ModelArtifactRole.ANIMAL_DETECTOR }?.sha256,
        osBuild,
    ),
    vocabularySha256 = null,
    appBuild = appBuild,
    preflightRevision = PET_PREFLIGHT_REVISION,
)

/**
 * The pets [AiReadiness] as a graph binds it: [ReadinessSlot.PETS]' files, the facts and key above, and
 * **the pets custody** for its fingerprint.
 *
 * ⚠⚠ The custody is a [FaceTemplateCustody] over `pets.log` and [ReadinessSlot.PETS]' fingerprint path —
 * never faces' (it would clear the face templates) and never SigLIP's `InferenceStoreCustody` (it would
 * clear every search vector). It is taken as that type so a lambda of the wrong custody does not compile.
 */
fun coreMlPetReadiness(
    files: () -> FileDatabase,
    store: () -> ModelStore,
    cache: () -> CoreMlModelCache,
    custody: () -> FaceTemplateCustody,
    realmUsable: () -> Boolean,
    cancelPass: suspend () -> Unit,
    closeSessions: suspend () -> Unit,
): AiReadiness = AiReadiness(
    files = files,
    facts = { coreMlPetModelFacts(store(), cache()) },
    currentKey = { iosPetPreflightKey(store().installed()) },
    storesUsable = { realmUsable() && custody().usable() },
    applyFingerprint = { fingerprint ->
        val held = custody()
        require(held.fingerprintPath == ReadinessSlot.PETS.fingerprintPath) { "the pets readiness needs the pets custody, not ${held.fingerprintPath}" }
        held.applyFingerprint(fingerprint)
    },
    cancelPass = cancelPass,
    closeSessions = closeSessions,
    slot = ReadinessSlot.PETS,
)

/** The pets preflight's models, opened with the backend's own specs through [opener]. */
class CoreMlPetPreflightModels(
    private val opener: PetCoreMlOpener = SystemPetCoreMlOpener,
) : PetPreflightModels {

    override fun openEmbedder(compiledPath: String): PreflightTower {
        val session = opener.open(compiledPath, CoreMlTowerSpec.petEmbedder())
        return object : PreflightTower {
            override fun predict(floats: FloatArray): FloatArray = session.predict(floats)
            override fun predict(ids: IntArray): FloatArray = error("the pet embedder takes pixels")
            override fun close() = session.close()
        }
    }

    override fun openDetector(compiledPath: String): PetPreflightDetector {
        val session = opener.open(compiledPath, CoreMlTowerSpec.animalDetector())
        return object : PetPreflightDetector {
            override fun predictAll(floats: FloatArray): Map<String, FloatArray> = session.predictAll(floats)
            override fun close() = session.close()
        }
    }
}

/**
 * The iOS [PetBackend]: the face path's decode, Core ML RF-DETR, [PetSelection], Core ML DINOv2 — the
 * shared pipeline (`PixelPetBackend`'s steps, the same stamp) with iOS's rules around every model load:
 * - ⚠⚠ **served or nothing**: claims only while the pets readiness serves, and asks again after every
 *   blocking call, since a teardown or a realm reset can land under it;
 * - ⚠⚠ **every open is recorded** by readiness ([recordedOpen] → `AiReadiness.servingOpen`), after
 *   [beforeLoad] (the earlier graph's unload, and the probes), so a load Photos does not survive is counted;
 * - on [lane], one photograph at a time.
 */
class CoreMlPetBackend(
    private val ready: () -> Boolean,
    locateDetector: () -> String?,
    locateEmbedder: () -> String?,
    private val decoder: FaceImageDecoder = IosFaceImageDecoder,
    private val opener: PetCoreMlOpener = SystemPetCoreMlOpener,
    private val beforeLoad: suspend () -> Unit = {},
    private val recordedOpen: suspend (role: String, open: () -> CoreMlSessionPort) -> CoreMlSessionPort = { _, opening -> opening() },
    private val lane: CoroutineDispatcher = MediaWorkBudget.lane,
    private val selection: PetSelection = PetSelection.BestGuess,
) : PetBackend {

    private val detector = CoreMlAnimalDetector(locateDetector, opener = opener)
    private val embedder = CoreMlPetEmbedder(locateEmbedder, opener = opener)
    private val lock = Mutex()

    /** The shared pipeline's stamp: the same checkpoints, the same crop and selection as Android. */
    override val provenance: ModelProvenance = ModelProvenance(
        PetEmbedderModel.DinoV2SmallZeroShotCoreMl.modelId,
        PetEmbedderModel.DinoV2SmallZeroShotCoreMl.modelVersion,
        labelSetVersion = PixelPetBackend.PET_PIPELINE_VERSION * 1000 + AnimalDetectorModel.RfDetrNanoCoreMl.modelVersion,
    )

    override fun available(): Boolean = ready() && detector.available() && embedder.available()

    override suspend fun findPets(bytes: ByteArray, orientation: Int?): PetOutcome = withContext(lane) {
        if (!available()) return@withContext PetOutcome.Failed
        // ⚠ Before the lock: the wait can be long, and it is not this backend's.
        beforeLoad()
        lock.withLock {
            if (!openRecorded(detector.held) || !openRecorded(embedder.held)) return@withLock PetOutcome.Failed
            val image: FaceImage = runCatching { decoder.decodeUpright(bytes, orientation) }.getOrNull()
                ?: return@withLock PetOutcome.Unreadable
            val animals = detector.detect(image.argb, image.width, image.height) ?: return@withLock PetOutcome.Failed
            val selected = selection.select(animals, image.width, image.height)
            val pets = ArrayList<StoredFace>(selected.animals.size)
            for (animal in selected.animals) {
                val output = embedder.embed(PetCropInput.tensor(image, animal)) ?: return@withLock PetOutcome.Failed
                val template = template(output) ?: return@withLock PetOutcome.Failed
                pets += StoredFace(FaceBox(animal.left, animal.top, animal.right, animal.bottom, animal.confidence), template)
            }
            // ⚠⚠ Asked again after the blocking calls: a teardown under them means nothing here is recorded.
            if (!ready()) return@withLock PetOutcome.Failed
            PetOutcome.Found(pets, selected.overlapping)
        }
    }

    /** Lets both models go — a withdrawal, a teardown, a probe about to load. */
    suspend fun closeSessions() = lock.withLock {
        detector.close()
        embedder.close()
    }

    /** Opens [held]'s model through readiness's record when it is not held; `false` when it cannot be. */
    private suspend fun openRecorded(held: PetCoreMlModel): Boolean {
        val path: String = held.pathToOpen() ?: return held.available()
        val opened: CoreMlSessionPort = try {
            recordedOpen(held.modelSpec.role) { opener.open(path, held.modelSpec) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (rejected: CoreMlRejectedModelException) {
            println("CoreMlPetBackend -> $path refused: ${rejected.message}")
            held.reject(path)
            return false
        } catch (failure: Throwable) {
            println("CoreMlPetBackend -> ${held.modelSpec.role} could not open: $failure")
            return false
        }
        held.adopt(opened)
        return true
    }

    /** The CLS half, unit length; `null` for a wrong width or no direction — `PixelPetBackend`'s rule. */
    private fun template(output: FloatArray): FloatArray? {
        if (output.size != PetEmbedderModel.WIDTH) return null
        val cls = output.copyOf(PetEmbedderModel.TEMPLATE)
        val norm = sqrt(cls.sumOf { it.toDouble() * it })
        if (!norm.isFinite() || norm == 0.0) return null
        return FloatArray(cls.size) { (cls[it] / norm).toFloat() }
    }
}

/**
 * The backend as Photos binds it: every path and claim from the pets [readiness]. ⚠⚠ [readiness] must be
 * a [ReadinessSlot.PETS] readiness, and is refused otherwise.
 */
fun petBackendOver(
    readiness: AiReadiness,
    decoder: FaceImageDecoder = IosFaceImageDecoder,
    opener: PetCoreMlOpener = SystemPetCoreMlOpener,
    beforeLoad: suspend () -> Unit = {},
    foregroundNow: () -> Long? = { null },
    nowEpochMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
): CoreMlPetBackend {
    require(readiness.slot == ReadinessSlot.PETS) { "the pet backend needs the pets readiness, not ${readiness.slot}" }
    return CoreMlPetBackend(
        ready = readiness::serves,
        locateDetector = { readiness.serving()?.paths?.get(ModelArtifactRole.ANIMAL_DETECTOR) },
        locateEmbedder = { readiness.serving()?.paths?.get(ModelArtifactRole.PET_EMBEDDER) },
        decoder = decoder,
        opener = opener,
        beforeLoad = beforeLoad,
        recordedOpen = { role, opening -> readiness.servingOpen(role, foregroundNow(), nowEpochMs(), opening) },
    )
}
