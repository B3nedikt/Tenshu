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
import org.tenshu.inference.proxy.onnx.OnnxModelFiles
import org.tenshu.inference.proxy.onnx.PetEmbedderModel
import org.tenshu.models.ModelCatalogBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pets PT-3c (`photos-pets.md` §10.11): the manifest's pet embedder IS [PetEmbedderModel.DinoV2SmallZeroShot],
 * and only Android and macOS — the two places a `PixelPetBackend` runs — can see it.
 *
 * ⚠⚠ [AnimalDetectorCatalogEntryTest]'s failure, the same silent way: an id, version, role or digest
 * that drifts from the descriptor downloads fine, and `petEmbedderStoreLocator` — keyed by the
 * descriptor — never finds it, so Pets never runs beside a model the Models screen shows as installed.
 */
class PetEmbedderCatalogEntryTest {

    private val model = PetEmbedderModel.DinoV2SmallZeroShot
    private val manifestText = File(repoRoot(), "deploy/models/models.json").readText()
    private val manifest = ModelCatalogBuilder.parseManifest(manifestText)
    private val entries = manifest.models.filter { it.id == model.modelId }

    @Test
    fun everyEntryIsTheDescriptorsEmbedder() {
        assertEquals(setOf(FaceModel.RUNTIME, FaceModel.ONNX_VISION_RUNTIME, "coreml"), entries.map { it.runtime }.toSet())
        for (entry in entries) {
            // ⚠ iOS's entry is the Core ML archive of the same checkpoint (PT-4c): its own digest, the same id and version.
            val described = if (entry.runtime == "coreml") PetEmbedderModel.DinoV2SmallZeroShotCoreMl else model
            assertEquals(described.modelVersion, entry.version)
            assertEquals(listOf(InferenceCapability.PET_RECOGNITION.id), entry.capabilities)
            assertEquals("Apache-2.0", entry.licence)
            val artifact = entry.artifacts.single()
            assertEquals(ModelArtifactRole.PET_EMBEDDER, artifact.role)
            assertEquals(described.sha256, artifact.sha256)
            assertEquals(described.byteSize, artifact.byteSize)
            assertTrue(artifact.path.startsWith("${model.modelId}/${model.modelVersion}/"), artifact.path)
            // ⚠ The side-load name and the store's file are the same model: one name for both.
            assertEquals(OnnxModelFiles.petEmbedderFileName(model), "${model.modelId}.onnx")
            // The export script's pinned revision (`export-pet-embedder.py`) is the signed provenance.
            assertEquals("ed25f3a31f01632728cabb09d1542f84ab7b0056", entry.source?.revision)
        }
    }

    @Test
    fun onlyAndroidAndMacSeeIt_oneEntryEach() {
        val listed = parseModelCatalog(asSigned(manifestText)).models
        fun seen(runtimes: Set<String>) = usableModels(listed, supportedRuntimes = runtimes).count { it.modelId == model.modelId }
        assertEquals(1, seen(SUPPORTED_RUNTIMES + FaceModel.RUNTIME), "Android")
        assertEquals(1, seen(SUPPORTED_RUNTIMES + FaceModel.ONNX_VISION_RUNTIME), "macOS")
        assertEquals(0, seen(SUPPORTED_RUNTIMES), "Windows / Linux: no pet backend runs there")
        assertEquals(1, seen(setOf("coreml", FaceModel.COREML_VISION_RUNTIME)), "iOS: the Core ML archive only")
    }

    @Test
    fun theSignersOwnChecksAcceptTheManifest() {
        ModelCatalogBuilder.checkManifest(manifest)
    }

    /** [FaceCatalogEntryTest.asSigned]'s placeholder digests: the client drops an artifact without one. */
    private fun asSigned(manifest: String): String {
        val doc = Json.parseToJsonElement(manifest).jsonObject
        val models = doc.getValue("models").jsonArray.map { entry ->
            val artifacts = entry.jsonObject.getValue("artifacts").jsonArray.map { artifact ->
                JsonObject(artifact.jsonObject + ("sha256" to JsonPrimitive("00")))
            }
            JsonObject(entry.jsonObject + ("artifacts" to JsonArray(artifacts)))
        }
        return JsonObject(doc + ("models" to JsonArray(models))).toString()
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "no settings.gradle.kts above ${System.getProperty("user.dir")}" }
    }
}
