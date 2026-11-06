package org.tenshu.inference.proxy.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.tenshu.inference.proxy.FaceBox
import org.tenshu.inference.proxy.FaceClustering
import org.tenshu.inference.proxy.FaceDetectionOutcome
import org.tenshu.inference.proxy.FaceRow
import org.tenshu.inference.proxy.StoredFace
import org.tenshu.inference.proxy.clusterFaces
import org.tenshu.inference.proxy.onnx.FaceDetectionStrategy
import org.tenshu.inference.proxy.onnx.MediaPipeFaceBackend

/**
 * **Which [FaceDetectionStrategy] should ship?** (face recall, `photos-people-and-pets.md` §12.14)
 *
 * Runs the SHIPPED backend once per strategy over `face_photos/`, through the calls `MediaIndexer`
 * makes, and writes per strategy: detection time, faces found, the cluster listings at the
 * production threshold (diff them — a verified cluster must not change membership), and
 * `new-faces.txt`: every face the strategy found that single stage did not, as `path box`, so each
 * can be cropped and looked at. ⚠⚠ A higher count is not a better strategy until those are faces.
 *
 * ⚠ Not a gate, for [FacePipelineMeasurementTest]'s reasons. Writes `in6-recall/<strategy>/`.
 */
@RunWith(AndroidJUnit4::class)
class FaceDetectionStrategyExperimentTest {

    @Test
    fun compareStrategies() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.getExternalFilesDir(null) ?: return@runBlocking println("IN-6 recall: no files dir")
        val detector = File(root, "face_landmarker.task")
        val embedder = File(root, "face_model.onnx")
        // ⚠ `-e photos <dir>` measures another folder — e.g. the 1024 px PREVIEWS the indexer reads
        // for photos stored only on the relay (§12.15: a threshold set on originals failed on
        // previews). A preview carries no EXIF. Rungs are minted upright (`ExifUpright`) and the
        // indexer passes no orientation; `-e orientations <file>` ("name orientation" per line) is
        // for measuring an older Android/JVM-minted rung, which lies the way the sensor wrote it.
        val args = InstrumentationRegistry.getArguments()
        // ⚠⚠ Every `-e` argument follows one rule, in [HarnessArguments] and tested on the JVM
        // (`HarnessArgumentsTest`): OMITTED keeps the default — and the skip below when the default
        // corpus is absent — while SUPPLIED but missing, unreadable, empty or matching nothing
        // FAILS the run. A supplied argument that quietly yields nothing passes having measured the
        // wrong thing: a typo'd corpus writes no report, an empty manifest analyses portrait
        // previews sideways.
        val photoRoot = File(root, args.getString("photos") ?: HarnessArguments.DEFAULT_PHOTOS)
        val photos = HarnessArguments.photos(root, args.getString("photos"))
        val orientations = HarnessArguments.orientationManifest(root, args.getString("orientations"), photos.map { it.name })
        val all = listOf(
            "single" to FaceDetectionStrategy.SingleStage,
            "two-stage" to FaceDetectionStrategy.TwoStage(tileGrid = 1),
            "two-stage-grid2" to FaceDetectionStrategy.TwoStage(tileGrid = 2),
        )
        val selected = HarnessArguments.strategies(args.getString("strategies"), all.map { it.first })
        val strategies = all.filter { it.first in selected }
        if (!detector.isFile || !embedder.isFile || photos.isEmpty()) {
            println("IN-6 recall: skipped — need face_landmarker.task, face_model.onnx and face_photos/ under $root")
            return@runBlocking
        }
        val out = File(root, "in6-recall").apply { deleteRecursively(); mkdirs() }
        var baseline: Map<String, List<FaceBox>> = emptyMap()
        val summary = StringBuilder()

        for ((name, strategy) in strategies) {
            val backend = MediaPipeFaceBackend(
                context = context,
                locateDetector = { detector.absolutePath },
                locateEmbedder = { embedder.absolutePath },
                strategy = strategy,
            )
            val dir = File(out, name).apply { mkdirs() }
            val rows = mutableListOf<FaceRow>()
            val boxes = mutableMapOf<String, List<FaceBox>>()
            // ⚠ The raw (pre-normalisation) norm per face, in [boxes]' order — §12.15's gate signal.
            val norms = mutableMapOf<String, List<Float>>()
            var notAFace = 0
            var embedFailed = 0
            var detectMs = 0L
            var faces = 0
            var failed = 0
            for (photo in photos) {
                val key = photo.relativeTo(photoRoot).path
                val bytes = photo.readBytes()
                val started = System.nanoTime()
                val orientation = orientations[photo.name]
                val outcome = backend.detectFaces(bytes, orientation)
                detectMs += (System.nanoTime() - started) / 1_000_000
                val detected = outcome as? FaceDetectionOutcome.Detected ?: run { failed++; null } ?: continue
                val faceNorms = mutableListOf<Float>()
                val stored = detected.faces.mapNotNull { face ->
                    val embedding = backend.embedFace(bytes, face, orientation)
                    // ⚠ NaN for a failed embed (the backend resets it per call), and the failure counted.
                    faceNorms += if (embedding == null) Float.NaN else backend.lastRawNorm
                    when {
                        embedding == null -> null.also { embedFailed++ }
                        embedding.notAFace -> null.also { notAFace++ }
                        else -> StoredFace(face.box, embedding.vector)
                    }
                }
                norms[key] = faceNorms
                faces += stored.size
                boxes[key] = detected.faces.map { it.box }
                rows += FaceRow(key, stored, backend.provenance)
            }
            val clusters = clusterFaces(rows, FaceClustering.Measured)
            clusters.filter { it.size > 1 }.forEachIndexed { i, cluster ->
                File(dir, "cluster-%02d-n%d.txt".format(i, cluster.size)).writeText(
                    cluster.faces.map { it.filePath }.sorted().joinToString("\n", postfix = "\n"),
                )
            }
            if (name == "single") baseline = boxes
            val newFaces = boxes.flatMap { (path, found) ->
                val old = baseline[path].orEmpty()
                found.filter { b -> old.none { overlap(it, b) >= 0.3f } }.map { "$path ${it.left} ${it.top} ${it.right} ${it.bottom} ${it.confidence}" }
            }
            File(dir, "all-faces.txt").writeText(
                boxes.flatMap { (path, found) ->
                    found.mapIndexed { i, it -> "$path ${it.left} ${it.top} ${it.right} ${it.bottom} ${it.confidence} ${norms[path]?.getOrNull(i)}" }
                }
                    .joinToString("\n", postfix = "\n"),
            )
            File(dir, "new-faces.txt").writeText(newFaces.joinToString("\n", postfix = "\n"))
            val line = "$name: photos ${photos.size}, faces $faces, new vs single ${newFaces.size}, " +
                "failures $failed, detect ${detectMs} ms (${detectMs / photos.size} ms/photo), " +
                "clusters>1 ${clusters.count { it.size > 1 }}, notAFace $notAFace, embedFailed $embedFailed"
            println("IN-6 recall: $line")
            summary.appendLine(line)
        }
        File(out, "report.txt").writeText(summary.toString())
    }

    private fun overlap(a: FaceBox, b: FaceBox): Float {
        val iw = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
        val ih = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        val smaller = minOf((a.right - a.left) * (a.bottom - a.top), (b.right - b.left) * (b.bottom - b.top))
        return if (smaller <= 0f) 0f else iw * ih / smaller
    }
}
