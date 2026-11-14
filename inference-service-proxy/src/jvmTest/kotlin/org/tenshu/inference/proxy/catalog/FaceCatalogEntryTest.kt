package org.tenshu.inference.proxy.catalog

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.onnx.FaceModel
import org.tenshu.inference.proxy.onnx.VisionModel
import org.tenshu.models.ModelCatalogBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tracked manifest's two face entries against the code that consumes them: Android's (IN-6 F2) and
 * iOS's (`ios-face-grouping.md` §7 F2, runtime `coreml+vision`).
 *
 * ⚠⚠ **The failure this exists for is silent at every layer.** If the manifest's id, roles or
 * runtime drift from [FaceModel], a device downloads both artifacts, the store records them under
 * that id, and `MediaPipeFaceBackend`'s locator — keyed by [FaceModel.PinnedAuraFace] — never finds
 * them. People then answers NO_BACKEND beside a model the Models screen shows as installed.
 */
class FaceCatalogEntryTest {

    private val manifestText = File(repoRoot(), "deploy/models/models.json").readText()
    private val catalog = parseModelCatalog(asSigned(manifestText)).models
    private val faces = catalog.filter { it.modelId == FaceModel.PinnedAuraFace.modelId }

    /** Android's entry: MediaPipe detects, ONNX Runtime embeds. */
    private val entry = faces.single { it.runtime == FaceModel.RUNTIME }

    /** iOS's entry (`ios-face-grouping.md` F2): Apple Vision detects, Core ML embeds. */
    private val ios = faces.single { it.runtime == IOS_FACE_RUNTIME }

    // ⚠ The signer's own strict parse: a misspelt key in the tracked manifest fails here.
    private val manifest = ModelCatalogBuilder.parseManifest(manifestText)
    private val iosManifest = manifest.models.single {
        it.id == FaceModel.PinnedAuraFace.modelId && it.runtime == IOS_FACE_RUNTIME
    }

    /**
     * ⚠ The manifest carries no digests — the signer computes them from the bytes (runbook §2) — and
     * the client drops an artifact without one. So a placeholder is added exactly where the signer
     * would put the real one; what is under test is the manifest's shape, not its hashes.
     */
    private fun asSigned(manifest: String): String {
        val doc = Json.parseToJsonElement(manifest).jsonObject
        val models = doc.getValue("models").jsonArray.map { model ->
            val artifacts = model.jsonObject.getValue("artifacts").jsonArray.map { artifact ->
                JsonObject(artifact.jsonObject + ("sha256" to JsonPrimitive("00")))
            }
            JsonObject(model.jsonObject + ("artifacts" to JsonArray(artifacts)))
        }
        return JsonObject(doc + ("models" to JsonArray(models))).toString()
    }

    @Test
    fun theEntryIsThePinnedModelAndClaimsFacesAlone() {
        assertEquals(FaceModel.PinnedAuraFace.modelVersion, entry.modelVersion)
        assertEquals(listOf(InferenceCapability.FACE_CLUSTERING), entry.capabilities)
        assertEquals(FaceModel.RUNTIME, entry.runtime)
        assertEquals("Apache-2.0", entry.licence)
    }

    @Test
    fun itCarriesExactlyTheTwoRolesTheBackendLocates() {
        assertEquals(
            setOf(ModelArtifactRole.FACE_DETECTOR, ModelArtifactRole.FACE_EMBEDDER),
            entry.artifacts.map { it.role }.toSet(),
        )
        // ⚠ The version is in the path: artifacts are served immutable, so a new version at an old
        // path would be ignored by every cache already holding the old bytes.
        entry.artifacts.forEach { assertTrue(it.path.startsWith("${entry.modelId}/${entry.modelVersion}/"), it.path) }
    }

    /**
     * ⚠⚠ IN-D13, both halves: offered where MediaPipe and ONNX both run (Android), and filtered out
     * on a build that only has ONNX (desktop) — never downloaded where it cannot execute.
     */
    @Test
    fun itIsUsableOnAndroidAndFilteredOnDesktop() {
        val android = SUPPORTED_RUNTIMES + FaceModel.RUNTIME
        assertNotNull(usableModels(listOf(entry), supportedRuntimes = android).singleOrNull())
        assertEquals(emptyList(), usableModels(listOf(entry), supportedRuntimes = SUPPORTED_RUNTIMES))
    }

    /**
     * ⚠⚠ The iOS backend (F4) finds its archive by [FaceModel.AuraFaceCoreMl]'s id and version, as
     * Android's locator does by [FaceModel.PinnedAuraFace]'s. A drift between the two is a device
     * that downloads the archive and never finds it.
     */
    @Test
    fun theIosEntryIsTheCoreMlFaceModel() {
        assertEquals(FaceModel.AuraFaceCoreMl.modelId, ios.modelId)
        assertEquals(FaceModel.AuraFaceCoreMl.modelVersion, ios.modelVersion)
    }

    /**
     * ⚠⚠ FD4: one checkpoint, one id, across runtimes. The iOS entry is the same `(id, version)` as
     * Android's, so a name typed on one platform lands on the same model stamp on the other. The
     * `-int8` in the id is wrong for an fp16 artifact and is kept on purpose: renaming Android's id
     * would re-index every Android library.
     */
    @Test
    fun theIosEntryIsTheSameCheckpointUnderItsOwnRuntime() {
        assertEquals(entry.modelId, ios.modelId)
        assertEquals(entry.modelVersion, ios.modelVersion)
        assertEquals(entry.labelSetVersion, ios.labelSetVersion)
        assertEquals(entry.artifactVersion, ios.artifactVersion)
        assertEquals(entry.licence, ios.licence)
        assertEquals(listOf(InferenceCapability.FACE_CLUSTERING), ios.capabilities)
        // ⚠ The embedder alone: Vision is a system framework, so there is no detector to download.
        val embedder = ios.artifacts.single()
        assertEquals(ModelArtifactRole.FACE_EMBEDDER, embedder.role)
        assertTrue(embedder.path.startsWith("${ios.modelId}/${ios.modelVersion}/coreml/"), embedder.path)
        assertTrue(embedder.path.endsWith(".mlpackage.tar"), embedder.path)
        // ⚠ Artifacts are served immutable: the archive must not share a path with Android's files.
        assertTrue(embedder.path !in entry.artifacts.map { it.path }.toSet(), embedder.path)
    }

    /**
     * ⚠⚠ The archive is pinned to the reproducible export, and the entry names the snapshot it was
     * converted from. Without the pin, any other export staged under the same name would sign.
     */
    @Test
    fun theIosArchiveIsPinnedAndNamesItsSource() {
        val artifact = iosManifest.artifacts.single()
        assertTrue(Regex("[0-9a-f]{64}").matches(artifact.sha256), "${artifact.path} has no sha256 pin")
        assertTrue((artifact.byteSize ?: 0) > 0, "${artifact.path} has no byteSize pin")
        val source = assertNotNull(iosManifest.source)
        assertEquals("fal/AuraFace-v1", source.repo)
        // The fp32 checkpoint, never Android's int8 quantisation of it.
        assertEquals(AURAFACE_FP32_SHA256, source.files["glintr100.onnx"])
        assertNotNull(source.toolchain)
        assertNull(
            manifest.models.single { it.id == FaceModel.PinnedAuraFace.modelId && it.runtime == FaceModel.RUNTIME }.source,
            "nothing records which snapshot Android's published files came from",
        )
    }

    /**
     * ⚠⚠ **Safe to publish before F5**: no shipped build contributes `coreml+vision`, so every one of
     * them drops the iOS entry and sees what it sees today. That includes Photos on iOS with AI-L
     * on, which contributes bare `coreml`; a bare-`coreml` face entry would have offered it a
     * ~130 MB download nothing on it can run (FD4).
     */
    @Test
    fun shippedBuildsFilterTheIosEntryOutAndAFaceBuildKeepsExactlyOne() {
        fun facesSeenBy(runtimes: Set<String>) =
            usableModels(catalog, supportedRuntimes = runtimes).filter { it.modelId == FaceModel.PinnedAuraFace.modelId }

        assertEquals(listOf(FaceModel.RUNTIME), facesSeenBy(SUPPORTED_RUNTIMES + FaceModel.RUNTIME).map { it.runtime })
        assertEquals(emptyList(), facesSeenBy(SUPPORTED_RUNTIMES))
        assertEquals(emptyList(), facesSeenBy(IOS_AI_L_RUNTIMES))

        val faceBuild = IOS_AI_L_RUNTIMES + IOS_FACE_RUNTIME
        assertEquals(listOf(IOS_FACE_RUNTIME), facesSeenBy(faceBuild).map { it.runtime })
        // ⚠ And that build still sees one SigLIP entry, the Core ML one: one runtime per model id.
        assertEquals(
            listOf("coreml"),
            usableModels(catalog, supportedRuntimes = faceBuild)
                .filter { it.modelId == VisionModel.PinnedSigLip.modelId }.map { it.runtime },
        )
        // ⚠ The build claim itself: if a shipped set gains the runtime, the cases above say so first.
        assertTrue(IOS_FACE_RUNTIME !in SUPPORTED_RUNTIMES + FaceModel.RUNTIME + IOS_AI_L_RUNTIMES)
    }

    /**
     * ⚠⚠ The signer refuses a second iOS face entry, and one under another version. Run on the
     * tracked manifest, so the refusal is pinned for this entry and not only for a fixture.
     */
    @Test
    fun theSignerAcceptsTheManifestAndRefusesASecondIosFaceEntry() {
        ModelCatalogBuilder.checkManifest(manifest)
        assertFailsWith<IllegalArgumentException> {
            ModelCatalogBuilder.checkManifest(manifest.copy(models = manifest.models + iosManifest))
        }
        val bumped = iosManifest.copy(version = iosManifest.version + 1)
        assertFailsWith<IllegalArgumentException> {
            ModelCatalogBuilder.checkManifest(manifest.copy(models = manifest.models - iosManifest + bumped))
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "no settings.gradle.kts above ${System.getProperty("user.dir")}" }
    }

    private companion object {
        /** ⚠ The face runtime iOS's backend will contribute (FD4); F5 contributes it. */
        const val IOS_FACE_RUNTIME = FaceModel.COREML_VISION_RUNTIME

        /** What Photos on iOS contributes with AI-L on (`PhotosOnDeviceAi.kt`, `PhotosModelDownloadTest`). */
        val IOS_AI_L_RUNTIMES = setOf("coreml")

        /** `glintr100.onnx` at `fal/AuraFace-v1@af6d057c` (`ios-face-grouping.md` §10.2). */
        const val AURAFACE_FP32_SHA256 = "a7933ea5330113b01c9b60351d8f4c33003f145d8470ac5f0e52ee2effe25c60"
    }
}
