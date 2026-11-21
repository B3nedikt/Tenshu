package org.tenshu.inference.proxy.onnx

import org.tenshu.inference.proxy.vision.FaceImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pets pipeline on **the app's** onnxruntime and **our** Kotlin crop, against `m2-pet-embedding.py`'s
 * vectors for the same photographs (`photos-pets.md` PT-3): the 0.85 cut was measured on those, so these
 * must be the same vectors or the cut means nothing here.
 *
 * ```
 * ./gradlew :inference-service-proxy:jvmTest --tests '*OnnxPetEmbedderRealTest*' \
 *     -Dtenshu.inference.petEmbedder=$HOME/tenshu-models/pet-embedder-2026-11-19/dinov2_small_cls_mean.onnx \
 *     -Dtenshu.inference.animalDetector=$HOME/tenshu-models/animal-detector-2026-11-17/rfdetr_nano_int8.onnx \
 *     -Dtenshu.inference.petReferences=$HOME/tenshu-models/pet-embedder-2026-11-19/commons-cat-references.tsv \
 *     -Dtenshu.inference.petPhotos=$HOME/tenshu-models/m2-commons-2026-11-18/cat
 * ```
 *
 * ⚠ Skips without the flags, and says so: the photographs are evaluation data and never committed.
 * ⚠ The references are a run of the M2 script's detector, crop and embedder with the photo read by Pillow
 * and NO EXIF transpose (ImageIO applies none); the JPEG decoders differ, so the bar is a cosine, not equality.
 */
class OnnxPetEmbedderRealTest {

    private val embedderPath: String? = System.getProperty("tenshu.inference.petEmbedder")
    private val detectorPath: String? = System.getProperty("tenshu.inference.animalDetector")
    private val references: String? = System.getProperty("tenshu.inference.petReferences")
    private val photos: String? = System.getProperty("tenshu.inference.petPhotos")

    @Test
    fun theArtifactIsTheDescribedBytes() {
        val path = embedderPath ?: return println("PETS-REAL skipped: no -Dtenshu.inference.petEmbedder")
        val file = File(path)
        assertEquals(PetEmbedderModel.DinoV2SmallZeroShot.byteSize, file.length())
        assertEquals(PetEmbedderModel.DinoV2SmallZeroShot.sha256, MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun theKotlinPipelineGivesTheM2ScriptsVectors() {
        val embedder = embedderPath ?: return println("PETS-REAL skipped: no -Dtenshu.inference.petEmbedder")
        val detectorFile = detectorPath ?: return println("PETS-REAL skipped: no -Dtenshu.inference.animalDetector")
        val refs = references ?: return println("PETS-REAL skipped: no -Dtenshu.inference.petReferences")
        val root = photos ?: return println("PETS-REAL skipped: no -Dtenshu.inference.petPhotos")
        val detector = OnnxAnimalDetector(locate = { detectorFile })
        val pets = OnnxPetEmbedder(locate = { embedder })
        val cosines = ArrayList<Double>()
        val sameBox = ArrayList<Double>()
        var missed = 0
        fun cosine(output: FloatArray, expected: FloatArray): Double {
            val cls = output.copyOf(PetEmbedderModel.TEMPLATE)
            val norm = sqrt(cls.sumOf { it.toDouble() * it })
            return cls.indices.sumOf { cls[it] / norm * expected[it] }
        }
        File(refs).readLines().filter { it.isNotBlank() }.forEach { line ->
            val (name, box, vector) = line.split("\t")
            val expected = vector.split(" ").map { it.toFloat() }.toFloatArray()
            val image = ImageIO.read(File(root, name)) ?: return@forEach
            val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val frame = FaceImage(image.width, image.height, argb)
            // ⚠⚠ The crop and the embedder alone, on the script's own box: only the JPEG decoders differ.
            val (l, t, r, b) = box.split(",").map { it.toFloat() }
            val theirs = org.tenshu.inference.proxy.DetectedAnimal(org.tenshu.inference.proxy.AnimalSpecies.CAT, l, t, r, b, 1f)
            sameBox += cosine(checkNotNull(pets.embed(PetCropInput.tensor(frame, theirs))), expected)
            val best = checkNotNull(detector.detect(argb, image.width, image.height)).filter { it.confidence >= 0.5f }.maxByOrNull { it.confidence }
            if (best == null) {
                missed++
                return@forEach
            }
            cosines += cosine(checkNotNull(pets.embed(PetCropInput.tensor(frame, best))), expected)
        }
        cosines.sort()
        sameBox.sort()
        println("PETS-REAL ${cosines.size} photographs, $missed with no box here; cosine to the M2 vector min %.4f median %.4f; on the script's box min %.4f median %.4f".format(cosines.first(), cosines[cosines.size / 2], sameBox.first(), sameBox[sameBox.size / 2]))
        assertTrue(missed <= 2, "$missed photographs lost their box to the JDK's decoder")
        // ⚠⚠ The crop and the embedder: the script's own box must give the script's own vector. Measured
        // 2026-11-19: min 0.9992, median 1.0000 over 64 Commons cats — only the JPEG decoders differ.
        assertTrue(sameBox.first() >= 0.998, "on the script's box, min ${sameBox.first()}")
        // ⚠ End to end the detector re-runs on ImageIO's pixels, and a box a few pixels off moves the
        // crop: measured median 0.9948, min 0.85 (one photograph). At PT-D11's 0.85 cut that is a SPLIT,
        // the recoverable direction — so the median is asserted, and the floor only catches a broken run.
        assertTrue(cosines[cosines.size / 2] >= 0.99, "median ${cosines[cosines.size / 2]}")
        assertTrue(cosines.first() >= 0.75, "min ${cosines.first()}")
    }
}
