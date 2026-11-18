package org.tenshu.inference.proxy.catalog

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.onnx.AnimalDetectorModel
import org.tenshu.inference.proxy.onnx.FaceModel
import org.tenshu.models.ModelCatalogBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pets PT-2c (`photos-pets.md` §10.3): the manifest's cat and dog filter IS
 * [AnimalDetectorModel.RfDetrNanoInt8], and only Android can see it.
 *
 * ⚠⚠ The failure this exists for is [FaceCatalogEntryTest]'s, and silent the same way: an id,
 * version or role that drifts from the descriptor downloads fine, and `animalDetectorStoreLocator`
 * — keyed by the descriptor — never finds it, so the veto never engages beside a filter the Models
 * screen shows as installed.
 */
class AnimalDetectorCatalogEntryTest {

    private val model = AnimalDetectorModel.RfDetrNanoInt8
    private val manifestText = File(repoRoot(), "deploy/models/models.json").readText()
    private val manifest = ModelCatalogBuilder.parseManifest(manifestText)
    private val pinned = manifest.models.single { it.id == model.modelId }

    @Test
    fun theEntryIsTheDescriptorsDetector() {
        assertEquals(model.modelVersion, pinned.version)
        assertEquals(listOf(InferenceCapability.FACE_CLUSTERING.id), pinned.capabilities)
        assertEquals("Apache-2.0", pinned.licence)
        val artifact = pinned.artifacts.single()
        assertEquals(ModelArtifactRole.ANIMAL_DETECTOR, artifact.role)
        assertEquals(model.sha256, artifact.sha256)
        assertEquals(model.byteSize, artifact.byteSize)
        assertTrue(artifact.path.startsWith("${model.modelId}/${model.modelVersion}/"), artifact.path)
    }

    /**
     * ⚠⚠ Android only. `onnx+mediapipe` is a runtime iOS and desktop never declare, so they drop the
     * entry at parse; plain `onnx` would offer desktop a 28.8 MB download it has no face backend for.
     */
    @Test
    fun onlyAndroidsRuntimeCanSeeIt() {
        assertEquals(FaceModel.RUNTIME, pinned.runtime)
        val entries = parseModelCatalog(asSigned(manifestText)).models
        val android = usableModels(entries, supportedRuntimes = SUPPORTED_RUNTIMES + FaceModel.RUNTIME).map { it.modelId }
        val desktop = usableModels(entries, supportedRuntimes = SUPPORTED_RUNTIMES).map { it.modelId }
        assertTrue(model.modelId in android, "$android")
        assertTrue(model.modelId !in desktop, "$desktop")
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
