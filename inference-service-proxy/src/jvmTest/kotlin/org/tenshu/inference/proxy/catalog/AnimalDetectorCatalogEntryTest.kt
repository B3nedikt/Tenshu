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
    private val entries = manifest.models.filter { it.id == model.modelId }
    /** Android's entry: the face veto's (PT-2c) and Pets' (PT-3c). */
    private val pinned = entries.single { it.runtime == FaceModel.RUNTIME }
    /** macOS's: Pets only — the Mac face pair uses Vision and has no veto. */
    private val mac = entries.single { it.runtime == FaceModel.ONNX_VISION_RUNTIME }

    @Test
    fun theEntryIsTheDescriptorsDetector() {
        assertEquals(3, entries.size, "Android's, macOS's and iOS's: ${entries.map { it.runtime }}")
        val ios = entries.single { it.runtime == "coreml" }
        assertEquals(listOf(InferenceCapability.PET_RECOGNITION.id), ios.capabilities)
        val iosArtifact = ios.artifacts.single()
        assertEquals(AnimalDetectorModel.RfDetrNanoCoreMl.sha256, iosArtifact.sha256)
        assertEquals(AnimalDetectorModel.RfDetrNanoCoreMl.byteSize, iosArtifact.byteSize)
        assertEquals(listOf(InferenceCapability.FACE_CLUSTERING.id, InferenceCapability.PET_RECOGNITION.id), pinned.capabilities)
        assertEquals(listOf(InferenceCapability.PET_RECOGNITION.id), mac.capabilities)
        for (entry in entries.filter { it.runtime != "coreml" }) {
            assertEquals(model.modelVersion, entry.version)
            assertEquals("Apache-2.0", entry.licence)
            val artifact = entry.artifacts.single()
            assertEquals(ModelArtifactRole.ANIMAL_DETECTOR, artifact.role)
            assertEquals(model.sha256, artifact.sha256)
            assertEquals(model.byteSize, artifact.byteSize)
            assertTrue(artifact.path.startsWith("${model.modelId}/${model.modelVersion}/"), artifact.path)
        }
    }

    /**
     * ⚠⚠ Android and macOS only — where a detector has a consumer (the veto; Pets). `onnx+mediapipe`
     * and `onnx+vision` are runtimes iOS, Windows and Linux never declare; plain `onnx` would offer a
     * Windows desktop a 28.8 MB download nothing there can run. ⚠ Each platform sees exactly ONE entry.
     */
    @Test
    fun onlyAndroidAndMacSeeIt_oneEntryEach() {
        val listed = parseModelCatalog(asSigned(manifestText)).models
        fun seen(runtimes: Set<String>) = usableModels(listed, supportedRuntimes = runtimes).count { it.modelId == model.modelId }
        assertEquals(1, seen(SUPPORTED_RUNTIMES + FaceModel.RUNTIME), "Android")
        assertEquals(1, seen(SUPPORTED_RUNTIMES + FaceModel.ONNX_VISION_RUNTIME), "macOS")
        assertEquals(0, seen(SUPPORTED_RUNTIMES), "Windows / Linux")
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
