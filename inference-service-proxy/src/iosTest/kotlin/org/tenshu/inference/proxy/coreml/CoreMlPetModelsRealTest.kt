package org.tenshu.inference.proxy.coreml

import kotlinx.coroutines.runBlocking
import org.tenshu.inference.proxy.onnx.PetEmbedderModel
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The two archives `export-coreml-pets.py` writes, compiled by the real prepare step and run through
 * **this** session code (`photos-pets.md` PT-4b): the Kotlin/Native marshalling is the one part the Python
 * parity runs (§10.13) never touched.
 *
 * ```
 * ./gradlew :inference-service-proxy:iosSimulatorArm64Test --tests '*CoreMlPetModelsRealTest*' \
 *     -Dtenshu.inference.coremlPetsExport=$HOME/tenshu-models/pt4/export3
 * ```
 *
 * ⚠ The references are Core ML's own answer (coremltools, 2026-11-19) for the same
 * synthetic tensor, `sin(0.001 i) × 1.5`; a few thousandths of drift between compute paths is allowed,
 * a marshalling error (wrong order, wrong type, strides) is not. Skips without the flag, and says so.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class CoreMlPetModelsRealTest {

    private val fixtures = CoreMlFixtures()

    @AfterTest fun cleanUp() = fixtures.cleanUp()

    private fun export(): String? =
        (NSProcessInfo.processInfo.environment["TENSHU_COREML_PETS_EXPORT"] as? String).also {
            if (it == null) println("SKIPPED: TENSHU_COREML_PETS_EXPORT is not set")
        }

    private fun compiled(directory: String, stem: String, spec: CoreMlTowerSpec): String {
        NSFileManager.defaultManager.createDirectoryAtPath("${fixtures.root}/archives", true, null, null)
        NSFileManager.defaultManager.copyItemAtPath("$directory/$stem.mlpackage.tar", "${fixtures.root}/archives/$stem.tar", null)
        return runBlocking { fixtures.cache().prepare(spec, "archives/$stem.tar", CoreMlFixtures.digestFor(stem)) }
    }

    private fun probe(side: Int) = FloatArray(3 * side * side) { (sin(it * 0.001) * 1.5).toFloat() }

    @Test
    fun theEmbedderAnswersAsCoreMlDoes() {
        val directory = export() ?: return
        val path = compiled(directory, "dinov2_small_cls_mean_fp16", CoreMlTowerSpec.petEmbedder())
        val out = CoreMlPetEmbedder(locate = { path }).embed(probe(224)) ?: error("the embedder did not run")
        val cls = out.copyOf(PetEmbedderModel.TEMPLATE)
        val norm = sqrt(cls.sumOf { it.toDouble() * it })
        val expected = floatArrayOf(-0.04122f, 0.02591f, 0.01230f, 0.00085f, 0.03360f, -0.01969f, 0.05529f, -0.02652f)
        expected.forEachIndexed { i, want ->
            assertTrue(abs(cls[i] / norm - want) < 0.004, "CLS[$i] ${cls[i] / norm} vs $want")
        }
    }

    @Test
    fun theDetectorAnswersAsCoreMlDoes() {
        val directory = export() ?: return
        val path = compiled(directory, "rfdetr_nano_fp16", CoreMlTowerSpec.animalDetector())
        val session = SystemPetCoreMlOpener.open(path, CoreMlTowerSpec.animalDetector())
        val outputs = session.predictAll(probe(384))
        val dets = outputs.getValue("dets")
        // ⚠ Two references, one per compute path: on noise no query is an animal, and Core ML's CPU and
        // Neural Engine paths place those meaningless boxes up to 0.07 apart (the simulator runs the CPU's).
        // Either one matched to 0.01 proves the marshalling; a wrong order or type matches neither.
        val cpu = floatArrayOf(0.49731f, 0.35913f, 1.01465f, 0.70166f, 0.49780f, 0.73682f, 1.00879f, 0.48315f)
        val neuralEngine = floatArrayOf(0.48438f, 0.41772f, 0.98340f, 0.77686f, 0.49023f, 0.73535f, 0.98242f, 0.48340f)
        fun matches(reference: FloatArray) = reference.indices.all { abs(dets[it] - reference[it]) < 0.01 }
        assertTrue(matches(cpu) || matches(neuralEngine), "dets ${dets.take(8)} match neither compute path")
        // Noise holds no animal: the decoder over these outputs finds none, and says so as an empty list.
        val animals = CoreMlAnimalDetector(locate = { path }).detect(IntArray(384 * 384) { 0xFF808080.toInt() }, 384, 384)
        assertTrue(animals != null, "the detector ran")
        session.close()
    }
}
