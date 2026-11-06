package org.tenshu.inference.proxy.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.FaceClustering
import org.tenshu.inference.proxy.FaceDetectionOutcome
import org.tenshu.inference.proxy.FaceRow
import org.tenshu.inference.proxy.StoredFace
import org.tenshu.inference.proxy.clusterFaces
import org.tenshu.inference.proxy.onnx.FaceDetectionStrategy
import org.tenshu.inference.proxy.onnx.MediaPipeFaceBackend

/**
 * **Does the SHIPPED backend group the library the way the measurement did?** (IN-6 F2, §12.12)
 *
 * [FacePipelineMeasurementTest] measured a chain; `MediaPipeFaceBackend` is that chain moved into
 * product code. A unit test cannot tell whether the move preserved it — the fake has no pixels — so
 * this runs the real backend over the same photographs, through the same `detectFaces` → `embedFace`
 * calls `MediaIndexer` makes, clusters at the production value, and writes each cluster's sorted
 * membership. Diff those against the harness listings in `in6-clusters` at the same threshold and
 * detector confidence: identical listings mean the shipped code reproduces the measured groups.
 *
 * ⚠⚠ Not a gate, for [FacePipelineMeasurementTest]'s reasons: it skips with a printed reason when the
 * models or photographs are absent, and it is unreachable from the JVM gate and the smoke gate.
 *
 * Uses the harness's files: `face_landmarker.task`, `face_model.onnx` (the embedder under test) and
 * `face_photos/`. Writes `in6-parity/cluster-NN-nK.txt` and `in6-parity/report.txt`.
 */
@RunWith(AndroidJUnit4::class)
class FaceBackendParityTest {

    @Test
    fun theShippedBackendReproducesTheMeasuredClusters() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.getExternalFilesDir(null) ?: return@runBlocking println("IN-6 parity: no files dir")
        val detector = File(root, "face_landmarker.task")
        val embedder = File(root, "face_model.onnx")
        val photos = File(root, "face_photos").walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic") }
            .sortedBy { it.absolutePath }
            .toList()
        if (!detector.isFile || !embedder.isFile || photos.isEmpty()) {
            println("IN-6 parity: skipped — need face_landmarker.task, face_model.onnx and face_photos/ under $root")
            return@runBlocking
        }

        val backend = MediaPipeFaceBackend(
            context = context,
            locateDetector = { detector.absolutePath },
            locateEmbedder = { embedder.absolutePath },
            // ⚠ Single stage: the harness it is diffed against measured that chain. The shipped
            // default is compared in `FaceDetectionStrategyExperimentTest`.
            strategy = FaceDetectionStrategy.SingleStage,
        )
        check(backend.supports(InferenceCapability.FACE_CLUSTERING)) { "backend refused the pair" }

        val photoRoot = File(root, "face_photos")
        val rows = mutableListOf<FaceRow>()
        var failed = 0
        var faces = 0
        for (photo in photos) {
            val bytes = photo.readBytes()
            val detected = backend.detectFaces(bytes) as? FaceDetectionOutcome.Detected ?: run {
                failed++
                null
            } ?: continue
            val stored = detected.faces.mapNotNull { face ->
                backend.embedFace(bytes, face)?.let { StoredFace(face.box, it.vector) }
            }
            faces += stored.size
            // ⚠ Relative to the corpus root — the harness's key, so the listings diff line for line.
            rows += FaceRow(photo.relativeTo(photoRoot).path, stored, backend.provenance)
        }

        val out = File(root, "in6-parity").apply { deleteRecursively(); mkdirs() }
        val clusters = clusterFaces(rows, FaceClustering.Measured)
        clusters.filter { it.size > 1 }.forEachIndexed { i, cluster ->
            File(out, "cluster-%02d-n%d.txt".format(i, cluster.size)).writeText(
                cluster.faces.map { "${it.filePath}#${it.faceIndex}" }.sorted().joinToString("\n", postfix = "\n"),
            )
        }
        val report = "photos ${photos.size}, faces $faces, detector failures $failed, " +
            "clusters>1 ${clusters.count { it.size > 1 }} at ${FaceClustering.Measured.threshold.cosine}"
        File(out, "report.txt").writeText(report + "\n")
        println("IN-6 parity: $report")
    }
}
