package org.tenshu.inference.proxy.onnx

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tenshu.inference.proxy.AnimalSpecies
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The export's self-check again, on **the app's** onnxruntime and **our** Kotlin preprocessing
 * (`photos-pets.md` §10.1): the held-out half of COCO val2017, image-level cat and dog recall and
 * precision at 0.5, against the bars the export holds itself to.
 *
 * ```
 * ./gradlew :inference-service-proxy:jvmTest --tests '*OnnxAnimalDetectorRealTest*' \
 *     -Dtenshu.inference.animalDetector=$HOME/tenshu-models/animal-detector-2026-11-17/rfdetr_nano_int8.onnx \
 *     -Dtenshu.inference.coco=$HOME/tenshu-models/coco-val2017/corpus
 * ```
 *
 * ⚠ Skips without both flags — and says so. ⚠ The JPEG decoder is the JDK's, not Pillow's, so the
 * numbers may move a little from the export's; the bars, not equality, are the assertion.
 */
class OnnxAnimalDetectorRealTest {

    private val modelPath: String? = System.getProperty("tenshu.inference.animalDetector")
    private val coco: String? = System.getProperty("tenshu.inference.coco")

    @Test
    fun theArtifactIsTheDescribedBytes() {
        val path = modelPath ?: return println("ANIMALS-REAL skipped: no -Dtenshu.inference.animalDetector")
        val file = File(path)
        assertEquals(AnimalDetectorModel.RfDetrNanoInt8.byteSize, file.length())
        assertEquals(AnimalDetectorModel.RfDetrNanoInt8.sha256, sha256(file.readBytes()))
    }

    @Test
    fun theHeldOutHalfClearsTheExportsBarsOnTheAppsRuntime() {
        val path = modelPath ?: return println("ANIMALS-REAL skipped: no -Dtenshu.inference.animalDetector")
        val corpus = coco ?: return println("ANIMALS-REAL skipped: no -Dtenshu.inference.coco")
        val annotations = Json.parseToJsonElement(File(corpus, "instances_val2017.json").readText()).jsonObject
        val truth = HashMap<Int, MutableSet<Int>>()
        annotations.getValue("annotations").jsonArray.forEach { element ->
            val annotation = element.jsonObject
            val category = annotation.getValue("category_id").jsonPrimitive.int
            if (category in setOf(AnimalDetectorModel.COCO_CAT, AnimalDetectorModel.COCO_DOG) && annotation.getValue("iscrowd").jsonPrimitive.int == 0) {
                truth.getOrPut(annotation.getValue("image_id").jsonPrimitive.int) { HashSet() } += category
            }
        }
        val heldOut = annotations.getValue("images").jsonArray.map { it.jsonObject }
            .map { it.getValue("id").jsonPrimitive.int to it.getValue("file_name").jsonPrimitive.content }
            .filter { (_, name) -> File(corpus, name).isFile && half(name) == 1 }
        assertEquals(2547, heldOut.size, "the export's held-out half")

        val detector = OnnxAnimalDetector(locate = { path })
        val fired = mapOf(AnimalSpecies.CAT to HashSet<Int>(), AnimalSpecies.DOG to HashSet<Int>())
        // ⚠ A few COCO JPEGs carry a colour layout the JDK's reader refuses; they are left out of numerator
        // AND denominator, counted, and capped, so a decoder regression cannot pass by shrinking the set.
        val undecodable = HashSet<Int>()
        val started = System.nanoTime()
        heldOut.forEach { (id, name) ->
            val image = runCatching { ImageIO.read(File(corpus, name)) }.getOrNull()
            if (image == null) {
                undecodable += id
                return@forEach
            }
            val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val animals = checkNotNull(detector.detect(argb, image.width, image.height)) { "the detector could not run on $name" }
            animals.filter { it.confidence >= FLOOR }.forEach { fired.getValue(it.species) += id }
        }
        val msPerImage = (System.nanoTime() - started) / 1e6 / heldOut.size
        assertTrue(undecodable.size <= 10, "${undecodable.size} held-out images did not decode")
        val report = StringBuilder("ANIMALS-REAL ${heldOut.size} held-out images (${undecodable.size} undecodable by ImageIO), %.1f ms/img (decode + resize + run)".format(msPerImage))
        val failed = mutableListOf<String>()
        for ((species, category, bars) in listOf(
            Triple(AnimalSpecies.CAT, AnimalDetectorModel.COCO_CAT, 0.88 to 0.95),
            Triple(AnimalSpecies.DOG, AnimalDetectorModel.COCO_DOG, 0.80 to 0.86),
        )) {
            val positives = heldOut.map { it.first }.filter { it !in undecodable && category in truth[it].orEmpty() }.toSet()
            val hits = fired.getValue(species).intersect(positives).size
            val recall = hits.toDouble() / positives.size
            val precision = hits.toDouble() / fired.getValue(species).size.coerceAtLeast(1)
            report.append("; $species recall %.3f (%d/%d) precision %.3f".format(recall, hits, positives.size, precision))
            if (recall < bars.first) failed += "$species recall $recall < ${bars.first}"
            if (precision < bars.second) failed += "$species precision $precision < ${bars.second}"
        }
        println(report)
        assertTrue(failed.isEmpty(), "$failed — $report")
    }

    /** The export's split: `sha256("20261117:<file>") mod 2`, 1 = held out. */
    private fun half(name: String): Int = java.math.BigInteger(1, MessageDigest.getInstance("SHA-256").digest("20261117:$name".toByteArray())).mod(java.math.BigInteger.TWO).toInt()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val FLOOR = 0.5f
    }
}
