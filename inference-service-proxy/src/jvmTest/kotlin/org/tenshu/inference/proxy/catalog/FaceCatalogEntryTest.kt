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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tracked manifest's face entry against the code that consumes it (IN-6 F2).
 *
 * ⚠⚠ **The failure this exists for is silent at every layer.** If the manifest's id, roles or
 * runtime drift from [FaceModel], a device downloads both artifacts, the store records them under
 * that id, and `MediaPipeFaceBackend`'s locator — keyed by [FaceModel.PinnedAuraFace] — never finds
 * them. People then answers NO_BACKEND beside a model the Models screen shows as installed.
 */
class FaceCatalogEntryTest {

    private val entry = parseModelCatalog(asSigned(File(repoRoot(), "deploy/models/models.json").readText()))
        .models.single { it.modelId == FaceModel.PinnedAuraFace.modelId }

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

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "no settings.gradle.kts above ${System.getProperty("user.dir")}" }
    }
}
