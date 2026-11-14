package org.tenshu.inference.proxy.coreml

import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.inference.proxy.AiModelFacts
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.FaceTemplateCustody
import org.tenshu.inference.proxy.ReadinessSlot
import org.tenshu.inference.proxy.FACE_PREFLIGHT_REVISION
import org.tenshu.inference.proxy.PreflightKey
import org.tenshu.inference.proxy.PreparedModel
import org.tenshu.inference.proxy.RuntimeFingerprint
import org.tenshu.inference.proxy.catalog.InstalledModelData
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.catalog.ModelStore
import org.tenshu.inference.proxy.onnx.FaceModel

/**
 * The face pair's half of readiness (`ios-face-grouping.md` §6.2, FD7): what the store and the
 * compiled cache hold of it, and the key a face verdict is bound to. The SigLIP twins are
 * [coreMlModelFacts] and [iosPreflightKey]; the two models never read each other's record.
 */

/** The face checkpoint's install record, if any. ⚠ By id: SigLIP's record is another model. */
private fun List<InstalledModelData>.faceCheckpoint(): InstalledModelData? =
    firstOrNull { it.modelId == FaceModel.AuraFaceCoreMl.modelId && it.artifacts.any { artifact -> artifact.role == ModelArtifactRole.FACE_EMBEDDER } }

/**
 * What [store] and [cache] hold of the face embedder, **as the disk has it**: prepared only with
 * the ready marker for the installed digest and the compiled directory there, as SigLIP's towers.
 */
fun coreMlFaceModelFacts(store: ModelStore, cache: CoreMlModelCache): AiModelFacts {
    val checkpoint: InstalledModelData = store.installed().faceCheckpoint() ?: return AiModelFacts(installed = false, prepared = null)
    val digest: String? = checkpoint.artifacts.firstOrNull { it.role == ModelArtifactRole.FACE_EMBEDDER }?.sha256
    val compiled: String? = digest?.let {
        // ⚠ A digest that is not a sha256 names no directory; anything else propagates as unreadable.
        try {
            cache.compiledPath(ModelArtifactRole.FACE_EMBEDDER, it)
        } catch (notADigest: IllegalArgumentException) {
            null
        }
    }
    return AiModelFacts(installed = true, prepared = compiled?.let { PreparedModel.of(ModelArtifactRole.FACE_EMBEDDER to it) })
}

/**
 * Why readiness does not read the installed face checkpoint as prepared, by **the very reading**
 * [coreMlFaceModelFacts] makes; `null` when it does, or when nothing is installed. What the face
 * prepare step says of an install it cannot finish (`ios-face-grouping.md` F6), as
 * `coreMlNotPrepared` is SigLIP's. ⚠ The face model has no vocabulary, so the one reason is a
 * tower that is not compiled, or a record with no embedder.
 */
fun coreMlFaceNotPrepared(store: ModelStore, cache: CoreMlModelCache): String? {
    val facts: AiModelFacts = coreMlFaceModelFacts(store, cache)
    if (!facts.installed || facts.prepared != null) return null
    return "not compiled: [${ModelArtifactRole.FACE_EMBEDDER}]"
}

/**
 * What the face pair would make a template with: the embedder's digest, the compute units, the OS
 * build (FD9: Vision's points differ by platform, so an OS update is a new key), the face decode's
 * revision and Vision's pinned revision.
 *
 * ⚠ The embedder's digest rides in `visionArchiveSha256` (it is the model's image tower), and
 * there is no text archive: a face fingerprint can never equal a SigLIP one.
 */
fun iosFaceRuntimeFingerprint(embedderSha256: String?, osBuild: String): RuntimeFingerprint = RuntimeFingerprint(
    visionArchiveSha256 = embedderSha256,
    textArchiveSha256 = null,
    computeUnits = CoreMlComputeUnits.SHIPPED.name,
    osBuild = osBuild,
    preprocessingRevision = IOS_FACE_PREPROCESSING_REVISION,
    detectorRevision = FaceModel.VISION_LANDMARKS_REVISION,
)

/** The face preflight key (§6.2): the fingerprint above, the app build and [FACE_PREFLIGHT_REVISION]. */
fun iosFacePreflightKey(
    installed: List<InstalledModelData>,
    appBuild: String = currentAppBuild(),
    osBuild: String = currentOsBuild(),
): PreflightKey = PreflightKey(
    runtime = iosFaceRuntimeFingerprint(installed.faceCheckpoint()?.artifacts?.firstOrNull { it.role == ModelArtifactRole.FACE_EMBEDDER }?.sha256, osBuild),
    vocabularySha256 = null,
    appBuild = appBuild,
    preflightRevision = FACE_PREFLIGHT_REVISION,
)

/** The face key [entry] would have once installed: what a Models screen asks before a 130 MB download. */
fun iosFacePreflightKeyOf(
    entry: ModelEntryData,
    appBuild: String = currentAppBuild(),
    osBuild: String = currentOsBuild(),
): PreflightKey = PreflightKey(
    runtime = iosFaceRuntimeFingerprint(entry.artifacts.firstOrNull { it.role == ModelArtifactRole.FACE_EMBEDDER }?.sha256, osBuild),
    vocabularySha256 = null,
    appBuild = appBuild,
    preflightRevision = FACE_PREFLIGHT_REVISION,
)

/**
 * The face pair's [AiReadiness] as a graph binds it (F5): [ReadinessSlot.FACES]' files, the face
 * facts and key above, and **the face custody** for its fingerprint.
 *
 * ⚠⚠ The custody is taken as a [FaceTemplateCustody], never as a lambda: `applyFingerprint` is a
 * function, and SigLIP's `InferenceStoreCustody::applyFingerprint` has the same shape. Handed that,
 * the face key's fingerprint would be written over `/inference/runtime.fingerprint` and every search
 * vector cleared (`ios-face-grouping.md` §14.4a). Here the wrong custody does not compile.
 *
 * @param realmUsable the realm custody's answer (`InferenceStoreCustody.usable`): which realm the
 *   templates belong to is that custody's, and a face model is served only over usable stores.
 */
fun coreMlFaceReadiness(
    files: () -> FileDatabase,
    store: () -> ModelStore,
    cache: () -> CoreMlModelCache,
    custody: () -> FaceTemplateCustody,
    realmUsable: () -> Boolean,
    cancelPass: suspend () -> Unit,
    closeSessions: suspend () -> Unit,
): AiReadiness = AiReadiness(
    files = files,
    facts = { coreMlFaceModelFacts(store(), cache()) },
    currentKey = { iosFacePreflightKey(store().installed()) },
    storesUsable = { realmUsable() && custody().usable() },
    applyFingerprint = { fingerprint -> custody().applyFingerprint(fingerprint) },
    cancelPass = cancelPass,
    closeSessions = closeSessions,
    slot = ReadinessSlot.FACES,
)
