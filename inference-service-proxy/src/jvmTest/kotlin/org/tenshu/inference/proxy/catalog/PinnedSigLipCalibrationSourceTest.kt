package org.tenshu.inference.proxy.catalog

import org.tenshu.inference.proxy.onnx.PinnedSigLipCalibrationPinTest
import org.tenshu.inference.proxy.onnx.VisionModel
import org.tenshu.models.ModelCatalogBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The checkpoint `PinnedSigLipCalibrationPinTest` says the calibration was read from is the one
 * the tracked manifest pins (`deploy/models/models.json`, the Core ML entry's `source`).
 *
 * ⚠ A new export from another revision moves the catalog's pin, and then this fails until the
 * constants have been re-read from that revision's weights.
 * ⚠ The ONNX entry records no source, so this binds the calibration to the checkpoint by the
 * Core ML entry alone (`CoreMlCatalogEntryTest`).
 */
class PinnedSigLipCalibrationSourceTest {

    @Test
    fun theCalibrationsProvenanceIsTheCatalogsPinnedCheckpoint() {
        val manifest = ModelCatalogBuilder.parseManifest(File(repoRoot(), "deploy/models/models.json").readText())
        val entry = manifest.models.single { it.id == VisionModel.PinnedSigLip.modelId && it.source != null }
        val source = assertNotNull(entry.source)

        assertEquals(PinnedSigLipCalibrationPinTest.SOURCE_REPO, source.repo)
        assertEquals(PinnedSigLipCalibrationPinTest.SOURCE_REVISION, source.revision)
        assertEquals(PinnedSigLipCalibrationPinTest.WEIGHTS_SHA256, source.files["model.safetensors"])
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "no settings.gradle.kts above ${System.getProperty("user.dir")}" }
    }
}
