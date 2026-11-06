package org.tenshu.inference.proxy.harness

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.io.File
import java.nio.FloatBuffer
import kotlin.system.measureTimeMillis
import org.junit.Test
import org.junit.runner.RunWith
import org.tenshu.inference.proxy.FaceBox
import org.tenshu.inference.proxy.FaceCrop
import org.tenshu.inference.proxy.FaceClustering
import org.tenshu.inference.proxy.FaceLandmarks
import org.tenshu.inference.proxy.FaceMesh
import org.tenshu.inference.proxy.FacePoint
import org.tenshu.inference.proxy.FaceRef
import org.tenshu.inference.proxy.FaceRow
import org.tenshu.inference.proxy.FaceSimilarityThreshold
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.StoredFace
import org.tenshu.inference.proxy.alignFace
import org.tenshu.inference.proxy.clusterFaces
import org.tenshu.inference.proxy.onnx.ImagePreprocessing

/**
 * **IN-6 — does the face pipeline actually group people, on this phone's own photographs?**
 *
 * `models.md` §5 settled what we may *licence*; it settled nothing about whether the result is good
 * enough to show someone. That question has no desk answer: the published LFW figures are measured
 * on frontal, cropped, celebrity-photograph benchmarks, and a camera roll is none of those things.
 * So this runs the whole chain — MediaPipe detection → the shared [alignFace] → HSFace embedding →
 * [clusterFaces] — over real photographs and writes down what it grouped.
 *
 * ⚠⚠ **It is NOT a gate and must never become one**, for [DeviceMeasurementTest]'s reasons (§15.8):
 * it skips with a printed reason when a model or the photo directory is absent, it is excluded from
 * `scripts/android-smoke.sh`, and it is unreachable from the JVM gate.
 *
 * ⚠⚠ **It writes face crops to disk.** That is the entire point — a cluster count is not something
 * anyone can check, and "12 clusters" reads identically whether the grouping is right or nonsense.
 * The montages are what make the result falsifiable. They are written to the app-specific external
 * directory, they are of the operator's own photographs, and nothing leaves the device unless the
 * operator pulls it.
 *
 * ## Running it
 *
 * Nothing here is committed — not the weights, not the photographs, not the report (§15.3).
 *
 * ⚠⚠ **Not via `connectedAndroidTest`** (§15.12.6): AGP uninstalls the test APK when the run ends,
 * and that deletes `/sdcard/Android/data/<pkg>/files` — the corpus you pushed *and* the report.
 *
 * ```
 * P=/sdcard/Android/data/org.tenshu.inference.proxy.test/files
 * A=inference-service-proxy/build/outputs/apk/androidTest/debug
 * ./gradlew :inference-service-proxy:assembleAndroidDeviceTest
 * adb -s <serial> install -r $A/inference-service-proxy-debug-androidTest.apk
 * adb -s <serial> push hsface100k_int8.onnx $P/face_model.onnx
 * adb -s <serial> push face_landmarker.task $P/face_landmarker.task
 * adb -s <serial> shell 'mkdir -p '$P'/face_photos'
 * adb -s <serial> shell 'cp /sdcard/DCIM/Camera/2026*.jpg '$P'/face_photos/'
 * adb -s <serial> shell chmod -R 777 $P/face_photos
 * adb -s <serial> shell am instrument -w -e class \
 *   org.tenshu.inference.proxy.harness.FacePipelineMeasurementTest \
 *   org.tenshu.inference.proxy.test/androidx.test.runner.AndroidJUnitRunner
 * adb -s <serial> pull $P/in6-report.txt
 * adb -s <serial> pull $P/in6-clusters
 * ```
 *
 * ⚠⚠ **Two devices attached means `-s <serial>` on every line.** An emulator will happily accept all
 * of it and then report nothing (see [DeviceMeasurement.isEmulator]), which reads exactly like a
 * phone that found no faces.
 *
 * ⚠ `println` from here reaches logcat under `System.out`, not `am instrument`'s output, so
 * `adb logcat -d | grep IN-6` is how a run that skipped explains itself.
 *
 * ⚠ Shell globs above are spelt to avoid a literal slash-star: **Kotlin block comments nest**, so a
 * path like `debug/` followed by `*.apk` opens a comment inside this one and swallows the rest of
 * the file. It fails as "Unclosed comment" at the last line, naming everything except the cause.
 */
@RunWith(AndroidJUnit4::class)
class FacePipelineMeasurementTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun measureFacePipeline() {
        val log = StringBuilder()
        fun say(line: String) {
            println("IN-6: $line")
            log.appendLine(line)
        }

        say("IN-6 — face detection, embedding and clustering on real hardware")
        say("device: ${Build.MANUFACTURER} ${Build.MODEL} / sdk ${Build.VERSION.SDK_INT}")

        // ⚠⚠ An emulator cannot answer "how fast", and IN-3m returns here for that reason (§15.1).
        // It CAN answer "is the chain wired up correctly", because purity against known ground truth
        // is not a timing — the host CPU computes the same cosines. So this runs, and suppresses
        // every duration instead of suppressing the run: a correctness result that needs no phone is
        // worth having, and a speed result taken here is the kind of wrong number that gets quoted.
        val emulator = DeviceMeasurement.isEmulator()
        if (emulator) say("⚠⚠ emulator — correctness only; every duration below is SUPPRESSED, not measured")

        val root = context.getExternalFilesDir(null)
        if (root == null) {
            say("skipped: no external files dir")
            return
        }
        val embedderModel = File(root, EMBEDDER_NAME)
        val detectorModel = File(root, DETECTOR_NAME)
        val photoDir = File(root, PHOTO_DIR)
        if (!embedderModel.isFile || !detectorModel.isFile) {
            say("skipped: need $EMBEDDER_NAME and $DETECTOR_NAME under ${root.absolutePath}")
            writeReport(root, log)
            return
        }
        val photos = photoDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.absolutePath }
            .toList()
        if (photos.isEmpty()) {
            say("skipped: no photos under ${photoDir.absolutePath}")
            // ⚠⚠ Also what an *unreadable* directory looks like — `walkTopDown` swallows the
            // permission error, so `adb push` leaving mode 0770 reads as "empty" (§15.12.6).
            say("⚠ if you pushed photos there: adb shell chmod -R 777 ${photoDir.absolutePath}")
            writeReport(root, log)
            return
        }
        say("photos: ${photos.size}, embedder ${embedderModel.length() / 1_000_000} MB")
        say("")

        // ⚠⚠ Recall is a KNOB, not a constant, and the first real run made that the live question:
        // 35 faces in 400 photographs. `face_landmarker.task` bundles BlazeFace **short-range**,
        // which is tuned for roughly arm's length — so a group shot across a room is a miss, and
        // that is indistinguishable from "a camera roll with few faces in it" unless the knob moves.
        // Settable from `am instrument -e minConfidence 0.2 -e maxEdge 4096`.
        val args = InstrumentationRegistry.getArguments()
        val minConfidence = args.getString("minConfidence")?.toFloatOrNull() ?: DEFAULT_MIN_CONFIDENCE
        val maxEdge = args.getString("maxEdge")?.toIntOrNull() ?: DEFAULT_MAX_DECODE_EDGE
        say("detector: minConfidence=$minConfidence, maxDecodeEdge=$maxEdge, maxFaces=$MAX_FACES_PER_PHOTO")

        val landmarker = FaceLandmarker.createFromOptions(
            context,
            FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(detectorModel.absolutePath).build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(MAX_FACES_PER_PHOTO)
                .setMinFaceDetectionConfidence(minConfidence)
                .setMinFacePresenceConfidence(minConfidence)
                .build(),
        )
        // ⚠⚠ **A comparison detector, deliberately NOT wired into the pipeline.** §12.6 left one
        // question open: ~90% of photographs yield no face, and nobody knows whether that is a
        // library of landscapes or a detector that cannot see across a room.
        // `face_landmarker.task` bundles BlazeFace **short-range**, tuned for roughly arm's length.
        //
        // ⚠ A detector cannot simply replace the landmarker here: it reports six keypoints — eyes,
        // nose, mouth CENTRE and two ear tragions — and `alignFace` needs mouth CORNERS. So this
        // only counts what each model finds, on the same photographs, which is what decides whether
        // a two-stage detect-then-landmark pipeline is worth building at all. Present a
        // `face_detector.tflite` to switch it on.
        var comparisonBroken = false
        var comparisonFailure: String? = null
        val comparisonModel = File(root, COMPARISON_DETECTOR_NAME)
        // ⚠⚠ CONSTRUCTION is inside the guard too. A malformed or non-Tasks model is rejected while
        // MediaPipe builds its graph — before any per-photo `runCatching` is ever reached — so
        // isolating only `detect` and `close` still let an optional diagnostic abort the
        // measurement it is optional to. The same defect as the first version, one call earlier.
        val comparison = if (comparisonModel.isFile) {
            say("comparison detector: ${comparisonModel.name} (${comparisonModel.length() / 1000} kB)")
            runCatching {
                    FaceDetector.createFromOptions(
                    context,
                    FaceDetector.FaceDetectorOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath(comparisonModel.absolutePath).build())
                        .setRunningMode(RunningMode.IMAGE)
                        .setMinDetectionConfidence(minConfidence)
                        .build(),
                )
            }.onFailure {
                comparisonBroken = true
                comparisonFailure = it.message?.lines()?.firstOrNull() ?: it::class.simpleName
            }.getOrNull()
        } else {
            null
        }
        var comparisonFaces = 0
        var comparisonPhotos = 0
        /** ⚠ Photographs BOTH paths completed — never `photos.size`, and never "the comparison ran". */
        var comparedPhotos = 0

        /** ⚠ Decoded photographs the comparison handled and the primary threw on; excluded above. */
        var comparisonOnly = 0
        var extrasSaved = 0
        /** ⚠ Every unmatched detection, including ones the cap stopped us saving. */
        var extrasFound = 0
        /** Of the unmatched ones, how many the landmarker accepted when run on the crop. */
        var extrasLandmarked = 0
        /** Detector boxes that DID match a full-image face — the replacement pipeline's other half. */
        var matchedCrops = 0
        var matchedCropsKept = 0
        /** ⭐ Distinct faces after de-duplication — the honest replacement / hybrid totals. */
        var replacementFaces = 0
        var hybridExtraFaces = 0
        /** ⚠ Crop-stage calls that THREW. Never counted as a rejection. */
        var cropStageFailures = 0
        /** ⚠ The unmatched share of them — the acceptance ratio's denominator depends on it. */
        var unmatchedCropFailures = 0
        /** ⚠ Crops that could not be WRITTEN — distinct from the cap, and from inference failures. */
        var extrasWriteFailures = 0
        val matchedCropMs = mutableListOf<Long>()
        val unmatchedCropMs = mutableListOf<Long>()
        val extrasDir = File(root, EXTRAS_DIR).apply { deleteRecursively(); mkdirs() }
        val comparisonMs = mutableListOf<Long>()

        val environment = OrtEnvironment.getEnvironment()
        val session = environment.createSession(embedderModel.absolutePath, OrtSession.SessionOptions())
        val inputName = session.inputNames.first()

        val rows = mutableListOf<FaceRow>()
        val crops = mutableMapOf<FaceRef, Bitmap>()
        // ⚠ Per-face fraction of the aligned square that fell outside the photograph — see `resample`.
        val clipping = mutableMapOf<FaceRef, Float>()
        val detectMs = mutableListOf<Long>()
        val embedMs = mutableListOf<Long>()
        var undecodable = 0
        var detectorThrew = 0

        photos.forEach { photo ->
            val bitmap = decodeUpright(photo, maxEdge)
            if (bitmap == null) {
                undecodable++
                return@forEach
            }
            // ⚠⚠ Relative to the corpus root, never `photo.name`. The walk is recursive, so two
            // subdirectories holding an `IMG_0001.jpg` would share a `FaceRow.filePath` and
            // therefore their `FaceRef`s — silently overwriting each other in the crop, truth and
            // vector maps, and corrupting every montage and purity figure downstream.
            val id = photo.relativeTo(photoDir).path
            /** Every mesh the crop stage produced on THIS photograph, in source pixels. */
            val cropFaceBoxes = mutableListOf<android.graphics.RectF>()
            /**
             * ⚠⚠ The same, restricted to UNMATCHED boxes — the hybrid's own pool.
             *
             * A hybrid never runs crop inference on a box that already matched a full-image face, so
             * crediting it with a mesh found in such a crop counts work that pipeline would not do.
             * That is reachable: partial occlusion lets two faces both overlap one detection, so a
             * matched box's crop can legitimately return a second mesh.
             */
            val unmatchedMeshBoxes = mutableListOf<android.graphics.RectF>()
            // ⚠⚠ **Before the primary, and before any early-out** — the landmarker throwing takes a
            // `return@forEach` below, which would otherwise skip this and leave the two counts
            // covering different photograph sets. But the result is only HELD here, never counted
            // yet: counting on the spot was the mirror of that bug, putting photographs the primary
            // went on to throw for into the "shared" sample.
            var comparisonBoxes: List<android.graphics.RectF>? = null
            if (comparison != null && !comparisonBroken) {
                runCatching {
                    var boxes: List<android.graphics.RectF> = emptyList()
                    comparisonMs += measureTimeMillis {
                        boxes = comparison.detect(BitmapImageBuilder(bitmap).build())
                            .detections()
                            .map { it.boundingBox() }
                    }
                    comparisonBoxes = boxes
                }.onFailure {
                    comparisonBroken = true
                    comparisonFailure = it.message?.lines()?.firstOrNull() ?: it::class.simpleName
                }
            }

            val faces = mutableListOf<StoredFace>()
            val result = try {
                var r: com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult? = null
                detectMs += measureTimeMillis { r = landmarker.detect(BitmapImageBuilder(bitmap).build()) }
                r
            } catch (t: Throwable) {
                say("⚠ detection threw on $id: ${t.message}")
                null
            }
            // ⚠⚠ A detector that THREW is not a photograph with nobody in it, and this harness
            // exists to measure recall — so folding the two together would make a device or model
            // fault read as a low recall rate and corrupt the decision the measurement informs.
            if (result == null) {
                detectorThrew++
                // ⚠ The comparison's answer for this photograph is DISCARDED, not counted: the
                // primary never produced one, so including it would compare two different samples.
                if (comparisonBoxes != null) comparisonOnly++
                bitmap.recycle()
                return@forEach
            }

            // ⚠ Both paths succeeded on this photograph — only now is it part of the shared sample.
            comparisonBoxes?.let { boxes ->
                comparisonFaces += boxes.size
                comparedPhotos++
                if (boxes.isNotEmpty()) comparisonPhotos++
            }
            result.faceLandmarks().forEachIndexed { index, mesh ->
                val landmarks = fiveFrom(mesh) ?: return@forEachIndexed
                // ⚠⚠ The SHARED alignment, not a crop of the detector's box — `alignFace`'s whole
                // reason. A crop taken to this detector's taste would land the same face in a
                // different region of the model's space than any other platform's.
                val crop = alignFace(landmarks, ARCFACE_TEMPLATE, bitmap.width, bitmap.height)
                val (square, outside) = resample(bitmap, crop.side, crop)
                clipping[FaceRef(id, index)] = outside
                val pixels = IntArray(crop.side * crop.side)
                square.getPixels(pixels, 0, crop.side, 0, 0, crop.side, crop.side)
                val tensor = ImagePreprocessing.preprocessPixels(
                    argb = pixels,
                    size = crop.side,
                    // ⚠ SIGLIP's constants ARE ArcFace's: mean .5, std .5, RGB, CHW. Sharing the one
                    // that already exists beats a second spelling of (x/255 - .5)/.5 that can drift.
                    profile = ImagePreprocessing.Profile.SIGLIP,
                )
                val vector = FloatArray(EMBEDDING_WIDTH)
                embedMs += measureTimeMillis {
                    OnnxTensor.createTensor(
                        environment,
                        FloatBuffer.wrap(tensor),
                        longArrayOf(1, 3, crop.side.toLong(), crop.side.toLong()),
                    ).use { input ->
                        session.run(mapOf(inputName to input)).use { out ->
                            val raw = (out[0].value as Array<*>)[0] as FloatArray
                            raw.copyInto(vector)
                        }
                    }
                }
                faces += StoredFace(boxOf(mesh), ImagePreprocessing.normalise(vector))
                crops[FaceRef(id, index)] = square
            }
            // ⚠⚠⚠ **The experiment §12.7 asks for.** The counts alone cannot say what the extra
            // detections ARE — the earlier "overwhelmingly false positives" was a classification
            // nobody performed. These are the detections the comparison model made and the
            // landmarker did not, cropped and written out so a human can say which are faces. That
            // answers recall and the two-stage question together, and nothing else short of ground
            // truth over the whole library does.
            comparisonBoxes?.let { boxes ->
                boxes.forEach { box ->
                    // ⚠⚠⚠ **Classified and COUNTED before the crop stage runs.** Counting after it
                    // meant a thrown call left the box out of its own denominator — so an inference
                    // fault showed up as *fewer unmatched detections*, and if every unmatched crop
                    // threw, `extrasFound` stayed 0 and the failure warning was suppressed with it,
                    // because that warning printed inside `if (extrasFound > 0)`. A fault could
                    // therefore erase the evidence that it happened.
                    val matched = matchesAKnownFace(box, faces, bitmap.width, bitmap.height)
                    if (matched) matchedCrops++ else extrasFound++

                    val crop = cropAround(bitmap, box)
                    if (crop == null) {
                        cropStageFailures++
                        if (!matched) unmatchedCropFailures++
                        return@forEach
                    }

                    // ⚠⚠⚠ **The SECOND STAGE, actually run — and run on MATCHED boxes too.** A
                    // detect-then-landmark pipeline does not embed a detector box: it runs the
                    // landmarker on the crop, and a hit that yields no mesh never reaches alignment.
                    // Measuring only the UNMATCHED boxes answers a different question — what a
                    // hybrid adds on top of today's full-image pass — and says nothing about whether
                    // a REPLACEMENT pipeline would keep the faces the full-image pass already finds.
                    // Cropping changes scale and context, so that is not free.
                    //
                    // ⚠⚠ A thrown call is NOT a rejection. Counting it as one would let an
                    // inference or resource failure masquerade as the landmarker declining a face,
                    // which is precisely the distinction this experiment turns on.
                    var ownMeshes: List<android.graphics.RectF> = emptyList()
                    val ran = runCatching {
                        // ⚠⚠ Timed per KIND, not prorated. A matched crop and an unmatched one are
                        // different sizes, so splitting one total by count assumes the very thing
                        // the comment beside it denies.
                        val elapsed = measureTimeMillis {
                            ownMeshes = meshesCoveringTheirDetection(
                                landmarker.detect(BitmapImageBuilder(crop.bitmap).build()).faceLandmarks(),
                                crop,
                                box,
                            )
                        }
                        if (matched) matchedCropMs += elapsed else unmatchedCropMs += elapsed
                    }.isSuccess
                    if (!ran) {
                        cropStageFailures++
                        if (!matched) unmatchedCropFailures++
                        return@forEach
                    }

                    // ⚠⚠⚠ **Boxes are pooled and de-duplicated per PHOTOGRAPH**, because a Boolean
                    // per detection is not a face count: a crowded crop returns several meshes and
                    // would be reduced to one, while two overlapping detections can each be credited
                    // with the SAME face and counted twice. Both distort the very figure this
                    // experiment publishes as replacement recall.
                    cropFaceBoxes += ownMeshes
                    val accepted = ownMeshes.isNotEmpty()
                    if (matched) {
                        if (accepted) matchedCropsKept++
                        return@forEach
                    }

                    unmatchedMeshBoxes += ownMeshes
                    if (accepted) extrasLandmarked++
                    if (extrasSaved >= MAX_EXTRAS) return@forEach
                    // ⚠⚠ `compress` returns a Boolean and the stream can throw — a full disk or an
                    // unwritable directory. Incrementing regardless made the report claim crops were
                    // written that are not there, and the whole point of them is that a human
                    // classifies them; publishing totals without the artefacts to check is worse
                    // than publishing nothing.
                    val verdict = if (accepted) "mesh" else "nomesh"
                    val written = runCatching {
                        File(extrasDir, "extra-%02d-%s-%s.png".format(extrasSaved, verdict, id.replace('/', '_')))
                            .outputStream()
                            .use { crop.bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                    }.getOrDefault(false)
                    if (written) extrasSaved++ else extrasWriteFailures++
                }
            }

            // ⭐ Distinct faces the crop stage found here, after de-duplication — what a
            // REPLACEMENT pipeline would actually have. And the subset of them the full-image pass
            // did not already have, which is what a HYBRID adds.
            val distinct = deduplicate(cropFaceBoxes)
            replacementFaces += distinct.size
            // ⚠⚠ Mesh against mesh — `faces` boxes are mesh extents too — so this is the STRICT
            // question. It used the loose detector threshold, which would call a recovered face the
            // same as a known one it merely overlaps and quietly understate the hybrid.
            hybridExtraFaces += deduplicate(unmatchedMeshBoxes).count { mesh ->
                faces.none { face ->
                    isTheSameFace(
                        mesh,
                        android.graphics.RectF(
                            face.box.left * bitmap.width,
                            face.box.top * bitmap.height,
                            face.box.right * bitmap.width,
                            face.box.bottom * bitmap.height,
                        ),
                    )
                }
            }

            rows += FaceRow(id, faces, PROVENANCE)

            // ⚠ The SAME bitmap — same EXIF handling, same decode cap — so the two counts differ
            // only by the model. Comparing against a differently-prepared image would measure the
            // preparation.
            bitmap.recycle()
        }
        landmarker.close()
        session.close()

        val found = rows.sumOf { it.faces.size }
        say("faces found: $found across ${rows.count { it.faces.isNotEmpty() }} photographs")
        say(
            "photographs with no face: ${rows.count { it.faces.isEmpty() }}, " +
                "undecodable: $undecodable, detector threw: $detectorThrew",
        )
        runCatching { comparison?.close() }
        // ⚠⚠ Keyed on the MODEL's presence, not on the detector object. Construction failure is
        // exactly the case where `comparison` is null, so gating the diagnostic on it meant the one
        // failure the guard exists for printed nothing at all — the operator saw a model-name line
        // and silence.
        if (comparisonModel.isFile && comparisonBroken) {
            say("")
            say("⚠⚠ comparison detector UNUSABLE — the measurement below is unaffected:")
            say("  $comparisonFailure")
            // ⚠ The common cause, stated because the message names a tensor and not the reason:
            // the legacy full-range models under `mediapipe-assets` are built for the old solution
            // graph and carry a different anchor count, so the Tasks FaceDetector rejects them.
            // Only models published under `mediapipe-models/face_detector/` are Tasks models.
            say("  ⚠ a legacy (non-Tasks) detector model will fail exactly this way")
        } else if (comparison != null && !comparisonBroken) {
            say("")
            // ⚠ The SHARED sample, stated as such. `photos.size` counts files, including ones
            // neither path ever decoded.
            say("⭐ DETECTOR COMPARISON — the $comparedPhotos photographs BOTH models completed:")
            if (extrasFound > 0) {
                say("  ⭐ $extrasFound unmatched detections; $extrasSaved written to $EXTRAS_DIR — LOOK AT THEM.")
                // ⚠⚠ Truncation is REPORTED, never silent. The cap keeps a low-confidence run from
                // writing hundreds of crops, but the ones it keeps are the first in sorted path
                // order — a biased prefix, not a sample — so a classification drawn from them is
                // only valid when nothing was dropped.
                if (extrasWriteFailures > 0) {
                    say(
                        "  ⚠⚠ $extrasWriteFailures crops could NOT BE WRITTEN — the detections exist " +
                            "but there is no image to classify them from",
                    )
                }
                // ⚠⚠ Only what the CAP actually omitted. `extrasFound - extrasSaved` also contains
                // crops that failed inference and crops that failed to write, both counted and
                // reported on their own lines — folding them in here would blame the cap for
                // failures and overstate how biased the saved prefix is.
                val capOmitted = extrasFound - extrasSaved - extrasWriteFailures - unmatchedCropFailures
                if (capOmitted > 0) {
                    say(
                        "  ⚠⚠ $capOmitted NOT written (cap $MAX_EXTRAS). The saved ones are the first " +
                            "in path order, a biased prefix — do not classify from them.",
                    )
                }
                // ⭐ The second stage, measured rather than assumed: of the crops written, how many
                // the landmarker accepts when it is actually run on them.
                // ⚠⚠ Over the boxes whose crop call actually RAN. `extrasFound` counts every
                // unmatched detection including ones that failed, and a failure cannot increment
                // `extrasLandmarked` — so dividing by it would let a device fault read as the
                // landmarker rejecting more faces.
                val extrasRun = extrasFound - unmatchedCropFailures
                say(
                    "  ⭐ of $extrasRun unmatched crops that RAN, the landmarker returned a mesh for " +
                        "$extrasLandmarked" +
                        if (unmatchedCropFailures > 0) " ($unmatchedCropFailures more failed, excluded)" else "",
                )
                // ⭐⭐ Both designs, because they are different numbers and only one of them was
                // measured before: a HYBRID keeps today's full-image faces and adds the crop-stage
                // survivors; a REPLACEMENT runs the crop stage on every detection, including the
                // ones the full-image pass already found.
                // ⚠⚠ Suppressed outright when ANY crop call failed. A failure cannot increment a
                // kept counter, so it lowers these totals exactly as a legitimate no-mesh rejection
                // would — which would contradict, in the same report, the line below promising that
                // failures are never counted as rejections.
                // ⚠⚠ `comparisonOnly` too: those photographs' detector boxes were dropped when the
                // full-image pass threw, and a REPLACEMENT pipeline does not need that pass — so its
                // crops were never attempted and both its face total and its cost are understated.
                val complete = cropStageFailures == 0 && comparisonOnly == 0
                if (complete) {
                    say(
                        "  ⭐ of $matchedCrops matched detections, the crop stage kept $matchedCropsKept; " +
                            "DISTINCT faces after de-duplication — REPLACEMENT $replacementFaces, " +
                            "HYBRID ${found + hybridExtraFaces}",
                    )
                } else {
                    say(
                        "  ⚠⚠ replacement/hybrid totals SUPPRESSED — $cropStageFailures crop calls " +
                            "failed and $comparisonOnly photographs lost their boxes to a full-image " +
                            "throw; both understate the derived designs",
                    )
                }
                // ⭐⭐ The COST side, which call counts alone get backwards: a hybrid pays the
                // full-image pass on every photograph AND the crop stage on what it missed; a
                // replacement pays only the crop stage, once per detection.
                // ⚠⚠ The COSTS are gated on the same completeness as the face totals. A failed
                // crop appends no duration, so a total taken over fewer calls than the pipeline
                // needs is biased downward — and suppressing the faces while publishing the cost
                // would leave the more quotable half of the pair unguarded.
                if (complete && !emulator && unmatchedCropMs.isNotEmpty() && detectMs.isNotEmpty()) {
                    val fullImage = detectMs.sum()
                    val unmatched = unmatchedCropMs.sum()
                    val everyCrop = unmatched + matchedCropMs.sum()
                    say(
                        "  ⭐ landmarker cost: hybrid ${fullImage + unmatched}ms " +
                            "(${detectMs.size} full-image + ${unmatchedCropMs.size} crop), " +
                            "replacement ${everyCrop}ms " +
                            "(${matchedCropMs.size + unmatchedCropMs.size} crop, no full-image pass)",
                    )
                }

            }
            // ⚠⚠ OUTSIDE the extras block: a run where every crop call failed has no extras to
            // report, and that is exactly when this line matters most.
            if (cropStageFailures > 0) {
                say(
                    "  ⚠⚠ $cropStageFailures crop-stage calls FAILED — counted in the detection " +
                        "totals above but never as landmark-stage rejections",
                )
            }
            if (comparisonOnly > 0) {
                say("  ⚠ $comparisonOnly further photographs are excluded — the landmarker threw on them")
            }
            say("  bundled (short-range): $found faces across ${rows.count { it.faces.isNotEmpty() }} photographs")
            say("  comparison model:      $comparisonFaces faces across $comparisonPhotos photographs")
            if (!emulator && comparisonMs.isNotEmpty()) {
                say("  comparison detect: median ${median(comparisonMs)}ms")
            }
            // ⚠⚠⚠ **This says what the comparison establishes and stops there.** An earlier version
            // printed "these photographs genuinely have few faces in them" when the counts agreed —
            // a conclusion `photos-people-and-pets.md` §12.7 now explicitly rejects, because both
            // paths run BlazeFace **short-range** and therefore share a blind spot: two short-range
            // detectors agreeing says nothing about the distant faces both of them miss. The doc was
            // corrected and this line was not, so the tool an operator actually reads still carried
            // the rejected verdict into the F2 measurement.
            say(
                if (comparedPhotos == 0 || (comparisonFaces == 0 && found == 0)) {
                    // ⚠ Two zeroes are not agreement, and the old wording called them that.
                    "  ⚠⚠ nothing to compare — neither path returned a face on any decoded photograph"
                } else if (comparisonFaces > found * 3 / 2) {
                    "  ⭐ the bare detector finds substantially more — the landmarker's presence " +
                        "stage is filtering heavily at this confidence"
                } else if (found > comparisonFaces * 3 / 2) {
                    // ⚠⚠ The comparison model is ANY Tasks-compatible detector, so the counts are
                    // not ordered. Without this branch a 0-against-20 reads as "the two track each
                    // other" — the falsest reading available, in the report that feeds F2.
                    "  ⚠ the LANDMARKER finds substantially more — the comparison model is the " +
                        "weaker detector here, so it cannot say anything about the landmarker"
                } else {
                    "  ⭐ the two track each other — the landmarker adds little filtering here"
                },
            )
            say(
                "  ⚠⚠ Neither reading settles overall RECALL: both paths are BlazeFace short-range, " +
                    "so a face they both miss is invisible to this comparison. Ground truth, or a " +
                    "detector without that blind spot, is what closes it (§12.7).",
            )
        }
        say("")

        if (emulator) {
            say("detect/embed timings: suppressed — this is an emulator (§15.1)")
        } else {
            if (detectMs.isNotEmpty()) say("detect: median ${median(detectMs)}ms over ${detectMs.size}")
            if (embedMs.isNotEmpty()) say("embed:  median ${median(embedMs)}ms over ${embedMs.size} faces")
        }
        say("")

        if (found == 0) {
            say("⚠⚠ nothing to cluster — a threshold sweep over zero faces would print a tidy table of nothing")
            writeReport(root, log)
            return
        }

        // ⚠⚠ A SWEEP, not a number. The threshold is the one value nobody can derive from the model
        // card: it depends on this detector's framing and this alignment, and picking one here would
        // bake a guess in as if it had been measured.
        // ⚠⚠ **`minFaces = 1` here, and that is not a detail.** The production default is 2, which
        // DISCARDS one-off faces before returning — so a sweep at the default reports
        // "singletons 0" at every threshold by construction, which reads as "every face matched
        // something" and is really "I filtered them out before counting". It also loses the number
        // this measurement most needs: how many faces appear exactly once, which is what decides
        // whether a People grid is mostly strangers.
        say("threshold sweep — the number F3 has to choose (minFaces=1, so singletons are VISIBLE):")
        THRESHOLDS.forEach { t ->
            val clusters = clusterFaces(rows, FaceClustering.Calibrated(FaceSimilarityThreshold(t), minFaces = 1))
            val singles = clusters.count { it.size == 1 }
            // ⚠ The report states its own arithmetic so a dropped face is visible rather than
            // something a reader has to notice by hand — which is how the `minFaces` filter went
            // unnoticed for a whole run.
            val covered = clusters.sumOf { it.size }
            say(
                "  ${"%.2f".format(t)}: ${clusters.size} clusters, ${clusters.size - singles} with >1 face, " +
                    "singletons $singles, largest ${clusters.maxOf { it.size }}, " +
                    "faces accounted $covered/$found${if (covered == found) "" else "  ⚠⚠ MISMATCH"}",
            )
        }
        say("")

        // ⚠⚠ If the corpus carries ground truth in its filenames, SCORE it rather than asking
        // someone to look. The public synthetic set (`id<NN>_*.jpg`) exists so the whole chain —
        // MediaPipe, the shared alignment, ORT, the clusterer — can be checked on the phone without
        // a private photograph being involved, and so that "it grouped things" is a claim with a
        // number behind it. A mirrored alignment or a transposed inverse still produces tidy
        // clusters; only ground truth notices that they are the wrong ones.
        val truth = rows.flatMap { row ->
            // ⚠ The prefix is on the file NAME, and `filePath` is now relative to the corpus
            // root, so a ground-truth set inside a subdirectory would otherwise score as unlabelled.
            val id = GROUND_TRUTH.find(row.filePath.substringAfterLast('/'))?.groupValues?.get(1)
            row.faces.indices.map { i -> FaceRef(row.filePath, i) to id }
        }.toMap()
        if (truth.values.count { it != null } > found / 2) {
            say("ground truth present (${truth.values.filterNotNull().distinct().size} identities) — scoring:")
            THRESHOLDS.forEach { t ->
                // ⚠ `minFaces = 1` for the same reason as the sweep: at the default, a face dropped
                // as a singleton contributes 0 to `correct` and still counts in the denominator, so
                // purity silently conflates "put in the wrong cluster" with "never returned at all".
                val clusters = clusterFaces(rows, FaceClustering.Calibrated(FaceSimilarityThreshold(t), minFaces = 1))
                var correct = 0
                clusters.forEach { cluster ->
                    val ids = cluster.faces.mapNotNull { truth[it] }
                    val majority = ids.groupingBy { it }.eachCount().maxByOrNull { it.value }
                    correct += majority?.value ?: 0
                }
                // ⚠⚠ Divided by the LABELLED faces, not every face found. A directory holding
                // ground truth *and* ordinary photographs puts unlabelled faces in the denominator
                // while `mapNotNull` keeps them out of the numerator, so even perfect clustering
                // would report a poor score — a measurement that cannot come back clean.
                val labelled = truth.values.count { it != null }
                val purity = if (labelled == 0) 0f else correct.toFloat() / labelled
                val ids = truth.values.filterNotNull().distinct()
                val split = ids.count { id ->
                    clusters.count { c -> c.faces.any { truth[it] == id } } > 1
                }
                say(
                    "  ${"%.2f".format(t)}: purity ${"%.3f".format(purity)}, " +
                        "${clusters.size} clusters for ${ids.size} identities, $split identities split",
                )
            }
            say("")
        }

        // ⚠ The montages use the PRODUCTION default (`minFaces` = 2) on purpose — this is what a
        // People grid would actually show, one-off strangers already dropped. The sweep above is the
        // diagnostic; this is the product.
        // ⚠ Overridable with `-e montageThreshold`, so a candidate calibration can be checked by
        // MEMBERSHIP rather than by count (§12.8c) without rebuilding the harness.
        val chosen = args.getString("montageThreshold")?.toFloatOrNull() ?: DEFAULT_MONTAGE_THRESHOLD
        val clusters = clusterFaces(rows, FaceClustering.Calibrated(FaceSimilarityThreshold(chosen)))
        say("montages written at threshold ${"%.2f".format(chosen)} — LOOK AT THEM, the counts above cannot be checked:")
        val outDir = File(root, CLUSTER_DIR)
        outDir.deleteRecursively()
        outDir.mkdirs()
        clusters.filter { it.size > 1 }.take(MAX_MONTAGES).forEachIndexed { i, cluster ->
            val members = cluster.faces.mapNotNull { crops[it] }
            if (members.isEmpty()) return@forEachIndexed
            val montage = montage(members)
            File(outDir, "cluster-%02d-n%d.png".format(i, cluster.size)).outputStream().use {
                montage.compress(Bitmap.CompressFormat.PNG, 90, it)
            }
            // ⚠ Membership, not just the picture: two embedders can agree on every count above and
            // still disagree on WHO is in a cluster. Diffing these sorted lists across runs is what
            // tells the two apart (§12.8a).
            File(outDir, "cluster-%02d-n%d.txt".format(i, cluster.size)).writeText(
                cluster.faces.map { "${it.filePath}#${it.faceIndex}" }.sorted().joinToString("\n", postfix = "\n"),
            )
            say("  cluster $i: ${cluster.size} faces over ${cluster.filePaths.size} photographs")
        }

        // ⚠⚠ **Why two clusters of one person did not become one.** The maintainer identified
        // clusters 0 and 1 as the same person, so this is the diagnostic that separates the two
        // possible causes, which want OPPOSITE fixes:
        //
        //  - **high nearest-pair, lower centroid** → the embedder DID see them as the same person and
        //    `clusterFaces` still split them. It is greedy and single-pass: once two centroids exist
        //    they never merge, however close they drift. The fix is an agglomerative merge pass, not
        //    a lower threshold.
        //  - **low nearest-pair** → the embedder genuinely places this person's photographs far
        //    apart, and no clustering change rescues it; only a lower threshold would join them, at
        //    the cost of merging different people.
        val byRef = rows.flatMap { row -> row.faces.mapIndexed { i, f -> FaceRef(row.filePath, i) to f.vector } }.toMap()
        val top = clusters.filter { it.size > 1 }.take(MAX_COMPARED_CLUSTERS)
        if (top.size > 1) {
            say("")
            // ⚠⚠ Printed BEFORE the similarity table on purpose: if two clusters of one person
            // differ here, the embedding gap is an artefact of how they were CROPPED, and any
            // conclusion about the model drawn from the table below is unsound.
            say("cluster shape — is a split explained by the crop rather than the face?")
            top.forEachIndexed { i, cluster ->
                val clips = cluster.faces.mapNotNull { clipping[it] }
                val sizes = cluster.faces.mapNotNull { ref ->
                    rows.firstOrNull { it.filePath == ref.filePath }
                        ?.faces?.getOrNull(ref.faceIndex)
                        ?.box?.let { (it.right - it.left) * (it.bottom - it.top) }
                }
                if (clips.isEmpty() || sizes.isEmpty()) return@forEachIndexed
                say(
                    "  cluster $i (${cluster.size}): off-frame " +
                        "${"%.1f".format(clips.average() * 100)}% " +
                        "(max ${"%.1f".format((clips.maxOrNull() ?: 0f) * 100)}%), " +
                        "mean face area ${"%.4f".format(sizes.average())} of the photo",
                )
            }
            say("")
            say("why clusters did not merge — centroid cosine / nearest member pair:")
            top.indices.forEach { a ->
                (a + 1 until top.size).forEach { b ->
                    val va = top[a].faces.mapNotNull { byRef[it] }
                    val vb = top[b].faces.mapNotNull { byRef[it] }
                    if (va.isEmpty() || vb.isEmpty()) return@forEach
                    val nearest = va.maxOf { x -> vb.maxOf { y -> cosineOf(x, y) } }
                    val centroid = cosineOf(meanOf(va), meanOf(vb))
                    say(
                        "  cluster $a (${top[a].size}) vs $b (${top[b].size}): " +
                            "centroid ${"%.3f".format(centroid)}, nearest pair ${"%.3f".format(nearest)}",
                    )
                }
            }
        }

        writeReport(root, log)
    }

    /** ⚠ Both vectors are already unit-length, so cosine is a plain dot product. */
    private fun cosineOf(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private fun meanOf(vectors: List<FloatArray>): FloatArray {
        val out = FloatArray(vectors.first().size)
        vectors.forEach { v -> for (i in out.indices) out[i] += v[i] }
        return ImagePreprocessing.normalise(out)
    }

    // ---- the five points, read out of MediaPipe's 478 ------------------------------------------

    /**
     * ⚠⚠ **Named by where they sit in the IMAGE, not on the subject.** MediaPipe's mesh is indexed on
     * the face — its "right eye" is the subject's, which appears on the image's *left* — while every
     * published ArcFace template is a list of destination pixels, and its first point has the smaller
     * x. Mixing the two conventions mirrors every crop, which is not an error any arithmetic reports:
     * the model simply returns a worse vector for a face it now sees inside out.
     */
    private fun fiveFrom(
        mesh: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
    ): FaceLandmarks? = FaceMesh.landmarks(mesh.map { FacePoint(it.x(), it.y()) })

    // ⚠ Both through the SHIPPED reduction (`FaceMesh`), so this measurement and
    // `MediaPipeFaceBackend` cannot drift apart — the calibration was taken here.
    private fun boxOf(mesh: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>) =
        FaceMesh.box(mesh.map { FacePoint(it.x(), it.y()) })

    // ---- pixels ---------------------------------------------------------------------------------

    /**
     * ⚠⚠ **EXIF or nothing.** A phone camera writes the sensor's pixels and an orientation tag; a
     * decode that ignores the tag hands the detector a sideways face, which it mostly does not find.
     * That failure is indistinguishable from "the detector is bad on my photographs" — it was the
     * single most likely way this measurement could have produced a confident wrong conclusion.
     */
    private fun decodeUpright(file: File, edge: Int): Bitmap? {
        // ⚠⚠ Bounded decode, or a 400-photograph run dies on someone's camera roll: a 12 MP frame is
        // ~48 MB as ARGB_8888 and the EXIF rotation below transiently holds two. Capping the long
        // edge keeps a face that occupies a few hundred pixels well above the 112 the template wants,
        // so this costs nothing that matters and removes an OOM that would read as "the detector
        // failed on my photographs".
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > edge) sample *= 2
        val decoded = runCatching {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            // ⚠⚠ The two ROTATED mirrors. Handling only the plain flips leaves these decoded
            // sideways, which MediaPipe mostly does not detect at all — and a photograph the
            // detector never saw is indistinguishable from one with nobody in it, which is the exact
            // ambiguity §12.4's recall question is already fighting.
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return decoded
        }
        return runCatching {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }.getOrDefault(decoded)
    }

    /**
     * Nearest-neighbour read through [FaceCrop.unmap]; the aligned square the model consumes.
     *
     * ⚠⚠ Returns how much of the square fell OUTSIDE the photograph. The template's 112 pixels
     * include forehead and chin margin beyond the detected face, so a face that fills the frame —
     * a selfie — aligns to a square that runs off the edge, and every one of those pixels is a
     * CLAMPED smear of the border rather than an image of a person. That corrupts the embedding
     * systematically for one kind of photograph and not another, which is indistinguishable from
     * "the model is bad at real faces" unless it is counted.
     */
    private fun resample(source: Bitmap, side: Int, crop: FaceCrop): Pair<Bitmap, Float> {
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val w = source.width
        val h = source.height
        var outside = 0
        for (y in 0 until side) {
            for (x in 0 until side) {
                // ⚠ Pixel centres: the transform is fitted in image FRACTIONS, so a destination
                // pixel index has to be turned back into one before it is inverted.
                // ⚠ `unmap` answers in the same space the landmarks were given in — pixels.
                val at = crop.unmap(FacePoint(x + 0.5f, y + 0.5f))
                val rawX = at.x.toInt()
                val rawY = at.y.toInt()
                if (rawX < 0 || rawY < 0 || rawX >= w || rawY >= h) outside++
                out.setPixel(x, y, source.getPixel(rawX.coerceIn(0, w - 1), rawY.coerceIn(0, h - 1)))
            }
        }
        return out to outside.toFloat() / (side * side)
    }

    /**
     * Does [box] (detector pixels) cover a face the landmarker already reported?
     *
     * ⚠ Intersection over the SMALLER area, not IoU. The landmarker's box is the landmark mesh's
     * extent and the detector's is its own framing; those differ in size for the same face, and IoU
     * would call a correct match a miss.
     */
    private fun matchesAKnownFace(
        box: android.graphics.RectF,
        faces: List<StoredFace>,
        width: Int,
        height: Int,
    ): Boolean = faces.any { face ->
        val l = maxOf(box.left, face.box.left * width)
        val t = maxOf(box.top, face.box.top * height)
        val r = minOf(box.right, face.box.right * width)
        val b = minOf(box.bottom, face.box.bottom * height)
        if (r <= l || b <= t) return@any false
        val intersection = (r - l) * (b - t)
        val smaller = minOf(
            box.width() * box.height(),
            (face.box.right - face.box.left) * width * (face.box.bottom - face.box.top) * height,
        )
        smaller > 0f && intersection / smaller > MATCH_OVERLAP
    }

    /** A crop plus where it came from, so a mesh found inside it can be put back on the photograph. */
    private class Crop(val bitmap: Bitmap, val left: Int, val top: Int)

    /** ⚠ With margin: a tight detector box is hard for a human to judge out of context. */
    private fun cropAround(source: Bitmap, box: android.graphics.RectF): Crop? {
        val margin = maxOf(box.width(), box.height()) * EXTRA_CROP_MARGIN
        val left = (box.left - margin).toInt().coerceIn(0, source.width - 1)
        val top = (box.top - margin).toInt().coerceIn(0, source.height - 1)
        val right = (box.right + margin).toInt().coerceIn(left + 1, source.width)
        val bottom = (box.bottom + margin).toInt().coerceIn(top + 1, source.height)
        if (right - left < 8 || bottom - top < 8) return null
        return runCatching {
            Crop(Bitmap.createBitmap(source, left, top, right - left, bottom - top), left, top)
        }.getOrNull()
    }

    /**
     * ⚠⚠⚠ **Did the landmarker accept THIS detection, or merely find some face in the crop?**
     *
     * The crop carries margin, so a neighbouring face can sit inside it. `faceLandmarks().isNotEmpty()`
     * cannot tell the two apart: it reports success when the landmarker rejected the face under test
     * and returned a mesh for the neighbour instead — and two overlapping crops can each be credited
     * with the same neighbour, counting one face twice. Both inflate a "what a replacement pipeline
     * would keep" figure.
     *
     * So every mesh is put back on the photograph — crop-normalised to crop pixels, then offset by
     * the crop's origin — and only a mesh overlapping the detection it came from counts.
     */
    /** Raw geometry. ⚠ Never called directly — go through [coversTheSameFace] or [isTheSameFace]. */
    private fun overlapOverSmaller(a: android.graphics.RectF, b: android.graphics.RectF): Float {
        val l = maxOf(a.left, b.left)
        val t = maxOf(a.top, b.top)
        val r = minOf(a.right, b.right)
        val bo = minOf(a.bottom, b.bottom)
        if (r <= l || bo <= t) return 0f
        val smaller = minOf(a.width() * a.height(), b.width() * b.height())
        return if (smaller > 0f) (r - l) * (bo - t) / smaller else 0f
    }

    /**
     * A detector's box against a mesh's extent, for the SAME face — deliberately loose, because the
     * two frame a face differently.
     */
    private fun coversTheSameFace(detection: android.graphics.RectF, mesh: android.graphics.RectF) =
        overlapOverSmaller(detection, mesh) > MATCH_OVERLAP

    /**
     * ⚠⚠⚠ **Mesh against mesh — a much stricter question, and it has its own predicate for a
     * reason.** Two DIFFERENT faces in a group photograph, one partly occluding the other, clear the
     * loose detector threshold easily; treating that as identity merges them and undercounts the
     * crowded photographs this analysis exists for. The loose threshold was reached for here twice
     * by hand, so the two questions now have two names and the raw geometry has none.
     */
    private fun isTheSameFace(a: android.graphics.RectF, b: android.graphics.RectF) =
        overlapOverSmaller(a, b) > SAME_MESH_OVERLAP

    /**
     * ⚠ One face found by two overlapping crops must count once — **without merging two different
     * faces that happen to overlap.**
     *
     * ⚠⚠ This deliberately does NOT reuse [MATCH_OVERLAP]. That threshold is loose on purpose,
     * because it compares a detector's framing with a landmark mesh's extent for the *same* face.
     * Two *different* faces in a group photograph, one partly occluding the other, can clear 30%
     * over the smaller box easily — so reusing it here would silently drop the second face and
     * undercount exactly the crowded photographs this de-duplication exists to handle.
     */
    private fun deduplicate(boxes: List<android.graphics.RectF>): List<android.graphics.RectF> {
        val kept = mutableListOf<android.graphics.RectF>()
        boxes.forEach { box -> if (kept.none { isTheSameFace(it, box) }) kept += box }
        return kept
    }

    private fun meshesCoveringTheirDetection(
        meshes: List<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>>,
        crop: Crop,
        detection: android.graphics.RectF,
    ): List<android.graphics.RectF> = meshes.mapNotNull { mesh ->
        if (mesh.isEmpty()) return@mapNotNull null
        val w = crop.bitmap.width
        val h = crop.bitmap.height
        val inSource = android.graphics.RectF(
            crop.left + mesh.minOf { it.x() } * w,
            crop.top + mesh.minOf { it.y() } * h,
            crop.left + mesh.maxOf { it.x() } * w,
            crop.top + mesh.maxOf { it.y() } * h,
        )
        if (coversTheSameFace(detection, inSource)) inSource else null
    }

    private fun montage(faces: List<Bitmap>): Bitmap {
        val side = faces.first().width
        val out = Bitmap.createBitmap(side * faces.size, side, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        faces.forEachIndexed { i, face -> canvas.drawBitmap(face, (i * side).toFloat(), 0f, null) }
        return out
    }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private fun writeReport(root: File, log: CharSequence) {
        val report = File(root, REPORT_NAME)
        runCatching { report.writeText(log.toString()) }
            .onSuccess { println("IN-6: report written to ${report.absolutePath}") }
            .onFailure { println("IN-6: could not write report — ${it.message}") }
    }

    private companion object {
        const val EMBEDDER_NAME = "face_model.onnx"
        const val DETECTOR_NAME = "face_landmarker.task"
        const val PHOTO_DIR = "face_photos"

        /** ⚠ Optional. Absent means the comparison is simply not run — never a failure. */
        const val COMPARISON_DETECTOR_NAME = "face_detector.tflite"
        const val REPORT_NAME = "in6-report.txt"
        const val CLUSTER_DIR = "in6-clusters"
        const val EXTRAS_DIR = "in6-extras"

        /** ⚠ Over the smaller box — see `matchesAKnownFace`. Loose, because it compares a
         * detector's framing with a mesh's extent for the SAME face. */
        const val MATCH_OVERLAP = 0.3f

        /** ⚠⚠ Mesh-against-mesh identity, and deliberately much stricter — see `deduplicate`. */
        const val SAME_MESH_OVERLAP = 0.8f
        const val EXTRA_CROP_MARGIN = 0.25f
        const val MAX_EXTRAS = 60
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic")

        const val MAX_FACES_PER_PHOTO = 10
        const val MAX_MONTAGES = 25
        const val MAX_COMPARED_CLUSTERS = 6
        const val EMBEDDING_WIDTH = 512

        /** Long-edge cap for the decode; overridable with `-e maxEdge`. */
        const val DEFAULT_MAX_DECODE_EDGE = 2048

        /** MediaPipe's own default; overridable with `-e minConfidence`. */
        const val DEFAULT_MIN_CONFIDENCE = 0.5f


        /** The shipped template (`FaceModel.ArcFaceTemplate`) — one copy, so the harness measures what ships. */
        val ARCFACE_TEMPLATE = org.tenshu.inference.proxy.onnx.FaceModel.ArcFaceTemplate

        val THRESHOLDS = listOf(0.20f, 0.28f, 0.36f, 0.40f, 0.44f, 0.52f, 0.60f)

        /** The production operating point at the time of writing (`FaceClustering.Measured`). */
        const val DEFAULT_MONTAGE_THRESHOLD = 0.44f

        /** `id07_v03.jpg` — the public synthetic set's identity prefix. Absent on a real camera roll. */
        val GROUND_TRUTH = Regex("^id(\\d+)_")

        val PROVENANCE = ModelProvenance(modelId = "hsface100k-int8", modelVersion = 1)
    }
}
