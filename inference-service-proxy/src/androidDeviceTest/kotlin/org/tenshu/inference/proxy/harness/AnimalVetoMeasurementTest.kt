package org.tenshu.inference.proxy.harness

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.tenshu.inference.proxy.AnimalVeto
import org.tenshu.inference.proxy.DetectedAnimal
import org.tenshu.inference.proxy.FaceBox
import org.tenshu.inference.proxy.FaceDetectionOutcome
import org.tenshu.inference.proxy.onnx.FaceDetectionStrategy
import org.tenshu.inference.proxy.onnx.MediaPipeFaceBackend
import org.tenshu.inference.proxy.onnx.OnnxAnimalDetector

/**
 * **M5 and M4 for the Android animal veto** (`photos-pets.md` §7, §10.1): which faces would PT-D9
 * remove, are any of them people, and what does the detector cost on this phone?
 *
 * **Measure-only.** The shipped backend runs with the veto OFF, exactly as the library was indexed.
 * The RF-DETR detector then runs on the backend's own upright decode, so its boxes share the faces'
 * frame. Nothing is decided on the device: every face is written with its best animal box
 * (`faces.tsv`), and every face–animal pair that overlaps at all (`pairs.tsv`: species, confidence,
 * the share of the face inside), so any threshold can be scored offline. The faces [AnimalVeto.Proposed] would remove are also drawn — face red, animal green —
 * so each can be checked by eye. ⚠⚠ That by-eye check IS M5: one real person among them fails it.
 *
 * ⚠ Not a gate, for [FacePipelineMeasurementTest]'s reasons. ⚠⚠ It writes the operator's own
 * photographs (downscaled, annotated) to the app's external files dir. Nothing leaves the device
 * unless the operator pulls it, and none of it is ever committed (§7).
 *
 * ```
 * P=/sdcard/Android/data/org.tenshu.inference.proxy.test/files
 * adb -s <serial> push rfdetr_nano_int8.onnx $P/animal_detector.onnx
 * adb -s <serial> shell am instrument -w -e class \
 *   org.tenshu.inference.proxy.harness.AnimalVetoMeasurementTest \
 *   [-e photos <dir under P>] [-e orientations <file>] \
 *   org.tenshu.inference.proxy.test/androidx.test.runner.AndroidJUnitRunner
 * adb -s <serial> pull $P/in6-veto
 * ```
 * Install, push the face pair and the photos as [FacePipelineMeasurementTest] says (not via
 * `connectedAndroidTest`, which deletes the files dir when it ends).
 */
@RunWith(AndroidJUnit4::class)
class AnimalVetoMeasurementTest {

    @Test
    fun measureAnimalVeto() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.getExternalFilesDir(null) ?: return@runBlocking println("IN-6 veto: no files dir")
        val faceDetector = File(root, "face_landmarker.task")
        val embedder = File(root, "face_model.onnx")
        val animalModel = File(root, "animal_detector.onnx")
        val args = InstrumentationRegistry.getArguments()
        val photoRoot = File(root, args.getString("photos") ?: HarnessArguments.DEFAULT_PHOTOS)
        val photos = HarnessArguments.photos(root, args.getString("photos"))
        val orientations = HarnessArguments.orientationManifest(root, args.getString("orientations"), photos.map { it.name })
        if (!faceDetector.isFile || !embedder.isFile || !animalModel.isFile || photos.isEmpty()) {
            println("IN-6 veto: skipped — need face_landmarker.task, face_model.onnx, animal_detector.onnx and photos under $root")
            return@runBlocking
        }
        val emulator = DeviceMeasurement.isEmulator()
        val out = File(root, "in6-veto").apply { deleteRecursively(); mkdirs() }
        val drawn = File(out, "removed").apply { mkdirs() }

        // The SHIPPED pipeline, veto off: these are the faces the library holds today.
        val backend = MediaPipeFaceBackend(
            context = context,
            locateDetector = { faceDetector.absolutePath },
            locateEmbedder = { embedder.absolutePath },
            strategy = FaceDetectionStrategy.TwoStage(tileGrid = 2),
        )
        val animals = OnnxAnimalDetector(locate = { animalModel.absolutePath })
        val veto = AnimalVeto.Proposed

        val table = StringBuilder("# path left top right bottom faceConfidence bestSpecies bestConfidence faceInside wouldVeto\n")
        // ⚠ EVERY overlapping face–animal pair, not only the best: the veto's two thresholds are
        // independent, so a box ranked best by one product can be the one that fails them, and only the
        // full set lets an offline threshold reproduce `wouldVeto`.
        val pairs = StringBuilder("# path faceIndex faceLeft faceTop faceRight faceBottom species animalConfidence faceInside\n")
        var faces = 0
        var photosWithFaces = 0
        var removed = 0
        var detectorFailed = 0
        var faceFailed = 0
        val animalMs = mutableListOf<Long>()
        for (photo in photos) {
            val key = photo.relativeTo(photoRoot).path
            val bytes = photo.readBytes()
            val orientation = orientations[photo.name]
            val detected = backend.detectFaces(bytes, orientation) as? FaceDetectionOutcome.Detected ?: run { faceFailed++; null } ?: continue
            if (detected.faces.isEmpty()) continue
            photosWithFaces++
            val bitmap = backend.decodeUpright(bytes, orientation) ?: run { faceFailed++; null } ?: continue
            val w = bitmap.width
            val h = bitmap.height
            val argb = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
            val started = System.nanoTime()
            val found: List<DetectedAnimal> = animals.detect(argb, w, h) ?: run { detectorFailed++; null } ?: continue
            animalMs += (System.nanoTime() - started) / 1_000_000
            val vetoedHere = mutableListOf<FaceBox>()
            for ((index, face) in detected.faces.withIndex()) {
                faces++
                found.forEach { animal ->
                    val share = inside(face.box, animal)
                    if (share > 0f) {
                        pairs.appendLine(
                            "$key $index ${face.box.left} ${face.box.top} ${face.box.right} ${face.box.bottom} " +
                                "${animal.species} ${animal.confidence} $share",
                        )
                    }
                }
                val best = found.maxByOrNull { inside(face.box, it) * it.confidence }
                val share = best?.let { inside(face.box, it) } ?: 0f
                val would = veto.vetoes(face.box, found)
                if (would) {
                    removed++
                    vetoedHere += face.box
                }
                table.appendLine(
                    "$key ${face.box.left} ${face.box.top} ${face.box.right} ${face.box.bottom} ${face.box.confidence} " +
                        "${best?.species ?: "-"} ${best?.confidence ?: 0f} $share $would",
                )
            }
            if (vetoedHere.isNotEmpty()) draw(bitmap, vetoedHere, found, File(drawn, "%03d-%s.jpg".format(removed, photo.nameWithoutExtension)))
            bitmap.recycle()
        }
        File(out, "faces.tsv").writeText(table.toString())
        File(out, "pairs.tsv").writeText(pairs.toString())

        val sorted = animalMs.sorted()
        fun pct(p: Double): Long = if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * p).toInt()]
        val timing = if (emulator) "SUPPRESSED (emulator)" else "median ${pct(0.5)} ms, p90 ${pct(0.9)} ms, max ${sorted.lastOrNull() ?: 0} ms over ${sorted.size} photos"
        val report = """
            |IN-6 veto (M5/M4) — ${Build.MANUFACTURER} ${Build.MODEL} / sdk ${Build.VERSION.SDK_INT}
            |photos ${photos.size}, with faces $photosWithFaces, faces $faces
            |would remove at ${veto}: $removed (drawn under removed/)
            |animal detector per photo with faces (decode excluded): $timing
            |failures: face detection $faceFailed, animal detector $detectorFailed
        """.trimMargin()
        File(out, "report.txt").writeText(report + "\n")
        report.lines().forEach { println("IN-6 veto: $it") }
    }

    /** The share of [face]'s area inside [animal] — [AnimalVeto]'s own measure. */
    private fun inside(face: FaceBox, animal: DetectedAnimal): Float {
        val area = (face.right - face.left) * (face.bottom - face.top)
        if (area <= 0f) return 0f
        val iw = (minOf(face.right, animal.right) - maxOf(face.left, animal.left)).coerceAtLeast(0f)
        val ih = (minOf(face.bottom, animal.bottom) - maxOf(face.top, animal.top)).coerceAtLeast(0f)
        return iw * ih / area
    }

    /** The photo at ≤ 1024 px with the removed faces in red and every animal in green. */
    private fun draw(source: Bitmap, vetoed: List<FaceBox>, animals: List<DetectedAnimal>, file: File) {
        val scale = minOf(1f, 1024f / maxOf(source.width, source.height))
        // ⚠ A mutable copy: at scale 1 `createScaledBitmap` returns the decode itself, immutable and still the caller's.
        val image = Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
            .let { scaled -> scaled.copy(Bitmap.Config.ARGB_8888, true).also { if (scaled !== source) scaled.recycle() } }
        val canvas = Canvas(image)
        val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f }
        val label = Paint().apply { color = Color.GREEN; textSize = 28f }
        fun rect(l: Float, t: Float, r: Float, b: Float) = canvas.drawRect(l * image.width, t * image.height, r * image.width, b * image.height, stroke)
        stroke.color = Color.GREEN
        animals.forEach {
            rect(it.left, it.top, it.right, it.bottom)
            canvas.drawText("${it.species} %.2f".format(it.confidence), it.left * image.width + 6, it.top * image.height + 30, label)
        }
        stroke.color = Color.RED
        vetoed.forEach { rect(it.left, it.top, it.right, it.bottom) }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        image.recycle()
    }
}
