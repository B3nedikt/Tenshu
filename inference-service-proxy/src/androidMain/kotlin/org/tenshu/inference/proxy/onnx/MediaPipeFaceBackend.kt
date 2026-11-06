package org.tenshu.inference.proxy.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import java.nio.ByteOrder
import java.nio.ByteBuffer
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import java.io.File
import java.nio.FloatBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tenshu.core.common.proxy.MediaWorkBudget
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.DetectedFace
import org.tenshu.inference.proxy.FaceCrop
import org.tenshu.inference.proxy.FaceDetectionOutcome
import org.tenshu.inference.proxy.FaceMesh
import org.tenshu.inference.proxy.DetectionBox
import org.tenshu.inference.proxy.FacePoint
import org.tenshu.inference.proxy.overlapOverSmaller
import org.tenshu.inference.proxy.selectFaceBoxes
import org.tenshu.inference.proxy.ImageEmbedding
import org.tenshu.inference.proxy.ImageTag
import org.tenshu.inference.proxy.InferenceBackend
import org.tenshu.inference.proxy.ModelBackedBackend
import org.tenshu.inference.proxy.TextRegion
import org.tenshu.inference.proxy.alignFace

/**
 * The face pipeline on Android — MediaPipe Face Landmarker detects, the shared
 * alignment frames, and an ONNX ArcFace-family embedder (pinned: AuraFace int8) produces the
 * template (`photos-people-and-pets.md` §3.3, §7a, §12).
 *
 * ⚠⚠ **This is the F2m harness's pipeline, moved, not re-derived.** The same bounded EXIF-upright
 * decode, the same 468-point mesh reduction ([FaceMesh]), the same `alignFace` and the same
 * nearest-neighbour resample through [FaceCrop.unmap]. The calibration (`FaceClustering.Measured`)
 * was measured on exactly that chain, and any "improvement" here — a bilinear resample, a different
 * decode edge — moves every cosine and silently invalidates it. Change it with a re-measurement.
 *
 * ⚠ A separate backend from [OnnxInferenceBackend] rather than two more methods on it: its runtime
 * is a different catalog entry (`FaceModel.RUNTIME`), it needs an Android [Context] for MediaPipe,
 * and it must be claimable on its own — a device may hold the face pair and not SigLIP.
 */
class MediaPipeFaceBackend(
    private val context: Context,
    /** The installed `.task` detector, or `null`. ⚠ Per call, so an install needs no restart. */
    private val locateDetector: () -> String?,
    /** The installed ONNX embedder, or `null`. */
    private val locateEmbedder: () -> String?,
    private val model: FaceModel = FaceModel.PinnedAuraFace,
    /**
     * ⚠ MediaPipe's own default, and the value both F2m runs agree at: every verified cluster is
     * identical at 0.5 and 0.2 (§12.8a–b), while 0.2 adds non-faces — a cat, two pastries — for
     * clustering to sort out. Fewer false faces is the cheaper side to err on.
     */
    private val minConfidence: Float = 0.5f,
    /**
     * How faces are found — see [FaceDetectionStrategy]. ⭐ The default is what ships: the tiled
     * two-stage pipeline, chosen on the verified library (`photos-people-and-pets.md` §12.14).
     */
    private val strategy: FaceDetectionStrategy = FaceDetectionStrategy.TwoStage(tileGrid = 2),
    private val environment: () -> OrtEnvironment = { OrtEnvironment.getEnvironment() },
    /** D4: the embedder session opens with the capped, non-spinning options. */
    private val sessions: OnnxSessionFactory = OnnxSessionFactory(environment = environment),
    /** Where detection and embedding run: the process's media lane (D4). */
    private val lane: CoroutineDispatcher = MediaWorkBudget.lane,
) : InferenceBackend, ModelBackedBackend {

    override val id: String get() = "mediapipe-onnx-faces"

    /**
     * ⚠⚠ The model's stamp WITH the detection pipeline in its third field, and the SAME value on
     * every vector [embedFace] returns: the template store adopts the vectors' stamp and the indexer
     * clears it when this one differs, so two spellings would wipe the store on every pass.
     *
     * Why the pipeline is in it at all: freshness is `visited`, so without a stamp change a better
     * detector would never look at a photograph already seen — the library keeps the old faces.
     */
    override val provenance: ModelProvenance = model.provenance.copy(labelSetVersion = strategy.pipelineVersion)

    /**
     * ⚠⚠ One lock for everything below. `MediaIndexer` calls [detectFaces] then [embedFace] once
     * per face with the SAME bytes; holding the decoded bitmap between them saves a 12 MP decode per
     * face, and the lock is what makes that cache safe to recycle.
     */
    private val lock = Mutex()
    private var landmarker: FaceLandmarker? = null
    /** [fileKey] of the loaded detector — not just its path. */
    private var landmarkerPath: String? = null
    private var session: OrtSession? = null
    private var sessionPath: String? = null
    private var cachedBytes: ByteArray? = null
    private var cachedBitmap: Bitmap? = null
    private var cachedOrientation: Int? = null

    /** ⚠⚠ Latched: a wrong-shaped embedder is a property of the artifact, not of the call. */
    @Volatile
    private var rejected = false

    override fun supports(capability: InferenceCapability): Boolean =
        capability == InferenceCapability.FACE_CLUSTERING &&
            !rejected && locateDetector() != null && locateEmbedder() != null

    override suspend fun detectFaces(bytes: ByteArray): FaceDetectionOutcome = detectFaces(bytes, null)

    override suspend fun embedFace(bytes: ByteArray, face: DetectedFace): ImageEmbedding? = embedFace(bytes, face, null)

    /**
     * ⚠⚠ [orientation] overrides the bytes' own EXIF — a PREVIEW has none, and without it a portrait
     * photo's preview reaches the detector on its side.
     */
    override suspend fun detectFaces(bytes: ByteArray, orientation: Int?): FaceDetectionOutcome = withContext(lane) {
        lock.withLock {
            val detector = landmarker() ?: return@withLock FaceDetectionOutcome.Failed.also {
                println("MediaPipeFaceBackend -> no landmarker (detector file missing or failed to open)")
            }
            // ⚠ Only a decode refusal is Unreadable — a library holds files that are not photographs.
            val bitmap = bitmapFor(bytes, orientation) ?: return@withLock FaceDetectionOutcome.Unreadable
            // ⚠⚠ A detector that THREW is not a photograph with nobody in it. Failed is not
            // remembered against the file; Detected(empty) is.
            val meshes = when (val s = strategy) {
                FaceDetectionStrategy.SingleStage -> runCatching { detector.detect(BitmapImageBuilder(bitmap).build()) }
                    .onFailure { println("MediaPipeFaceBackend -> detect threw on ${bitmap.width}x${bitmap.height}: $it") }
                    .getOrNull()
                    ?.faceLandmarks()
                    ?.map { mesh -> ScoredMesh(mesh.map { FacePoint(it.x(), it.y()) }, 1f) }
                is FaceDetectionStrategy.TwoStage -> twoStage(bitmap, detector, s)
            } ?: return@withLock FaceDetectionOutcome.Failed
            val faces = meshes.mapNotNull { mesh ->
                FaceMesh.landmarks(mesh.points)?.let { DetectedFace(FaceMesh.box(mesh.points).copy(confidence = mesh.score), it) }
            }
            FaceDetectionOutcome.Detected(faces)
        }
    }

    override suspend fun embedFace(bytes: ByteArray, face: DetectedFace, orientation: Int?): ImageEmbedding? =
        withContext(lane) {
            lock.withLock {
                // ⚠ Reset first, so a failed embed never reports the previous face's norm.
                lastRawNorm = Float.NaN
                val session = session() ?: return@withLock null.also {
                    println("MediaPipeFaceBackend -> no embedder session (rejected=$rejected)")
                }
                val bitmap = bitmapFor(bytes, orientation) ?: return@withLock null.also {
                    println("MediaPipeFaceBackend -> embedFace could not decode the image")
                }
                val side = model.template.side
                val pixels = runCatching {
                    resample(bitmap, side, alignFace(face.landmarks, model.template, bitmap.width, bitmap.height))
                }.onFailure { println("MediaPipeFaceBackend -> align/resample threw: $it") }
                    .getOrNull() ?: return@withLock null
                val input = ImagePreprocessing.preprocessPixels(pixels, side, model.profile)
                val vector = runCatching {
                    OnnxTensor.createTensor(
                        environment(),
                        FloatBuffer.wrap(input),
                        longArrayOf(1, 3, side.toLong(), side.toLong()),
                    ).use { tensor ->
                        session.run(mapOf(session.inputNames.first() to tensor)).use { out ->
                            ((out[0].value as Array<*>)[0] as FloatArray).copyOf()
                        }
                    }
                }.onFailure { println("MediaPipeFaceBackend -> embedder run threw: $it") }
                    .getOrNull() ?: return@withLock null
                if (vector.size != model.dimensions) return@withLock null.also {
                    println("MediaPipeFaceBackend -> embedding width ${vector.size} != ${model.dimensions}")
                }
                val norm = kotlin.math.sqrt(vector.fold(0.0) { acc, v -> acc + v * v }).toFloat()
                lastRawNorm = norm
                ImageEmbedding(
                    ImagePreprocessing.normalise(vector),
                    provenance,
                    notAFace = model.minFaceNorm?.let { norm < it } ?: false,
                )
            }
        }

    /** The raw norm of the last [embedFace] — for the device measurement harness only. */
    @Volatile
    var lastRawNorm: Float = Float.NaN
        private set

    // ⚠ This backend claims faces and nothing else — the rest of the seam answers "cannot".
    override suspend fun embedImage(bytes: ByteArray): ImageEmbedding? = null
    override suspend fun embedText(text: String): ImageEmbedding? = null
    override suspend fun tagImage(bytes: ByteArray, vocabulary: List<String>): List<ImageTag> = emptyList()
    override suspend fun recognizeText(bytes: ByteArray): List<TextRegion> = emptyList()

    // ---- models ---------------------------------------------------------------------------------

    // ---- the two-stage path ----------------------------------------------------------------------

    /**
     * Detect, then landmark each detection's CROP — §12.8's replacement pipeline, optionally over a
     * tile grid so faces far from the camera become large enough for the short-range detector.
     *
     * @return meshes in image FRACTIONS, de-duplicated; `null` when a model call threw.
     */
    private fun twoStage(bitmap: Bitmap, landmarker: FaceLandmarker, s: FaceDetectionStrategy.TwoStage): List<ScoredMesh>? {
        val detector = boxDetector() ?: return null.also { println("MediaPipeFaceBackend -> no box detector") }
        val w = bitmap.width
        val h = bitmap.height
        val boxes = mutableListOf<DetectionBox>()
        fun detectIn(left: Int, top: Int, right: Int, bottom: Int): Boolean {
            // ⚠ Inside the failure handling: a throw here would escape `detectFaces` and abort the
            // whole index pass, not just this photo.
            val region = runCatching {
                if (left == 0 && top == 0 && right == w && bottom == h) bitmap
                else Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            }.onFailure { println("MediaPipeFaceBackend -> tile ${right - left}x${bottom - top} failed: $it") }
                .getOrNull() ?: return false
            val result = runCatching { detector.detect(BitmapImageBuilder(region).build()) }
                .onFailure { println("MediaPipeFaceBackend -> box detect threw: $it") }
                .getOrNull()
            if (region !== bitmap) region.recycle()
            result ?: return false
            result.detections().forEach { d ->
                val bb = d.boundingBox()
                boxes += DetectionBox(
                    left = bb.left + left, top = bb.top + top, right = bb.right + left, bottom = bb.bottom + top,
                    score = d.categories().firstOrNull()?.score() ?: 0f,
                )
            }
            return true
        }
        if (!detectIn(0, 0, w, h)) return null
        // ⚠ Tiles only where a tile is still an image: a photo smaller than the grid would get a
        // zero-sized tile, and one this small has no distant face to find anyway.
        if (s.tileGrid > 1 && w / s.tileGrid >= MIN_TILE_EDGE && h / s.tileGrid >= MIN_TILE_EDGE) {
            // ⚠ Overlapping tiles, so a face on a seam is whole in at least one of them.
            val stepX = w / s.tileGrid
            val stepY = h / s.tileGrid
            val padX = (stepX * TILE_OVERLAP).toInt()
            val padY = (stepY * TILE_OVERLAP).toInt()
            for (i in 0 until s.tileGrid) for (j in 0 until s.tileGrid) {
                if (!detectIn(
                        (i * stepX - padX).coerceAtLeast(0), (j * stepY - padY).coerceAtLeast(0),
                        ((i + 1) * stepX + padX).coerceAtMost(w), ((j + 1) * stepY + padY).coerceAtMost(h),
                    )
                ) return null
            }
        }
        // ⚠ One box per face before the expensive stage: tiles and the full image see the same face.
        // ⚠⚠ The largest wins, not the best score — see [selectFaceBoxes].
        val kept = selectFaceBoxes(boxes, BOX_OVERLAP)

        val meshes = mutableListOf<ScoredMesh>()
        for (box in kept) {
            val mw = (box.right - box.left) * CROP_MARGIN
            val mh = (box.bottom - box.top) * CROP_MARGIN
            val cl = (box.left - mw).toInt().coerceIn(0, w - 1)
            val ct = (box.top - mh).toInt().coerceIn(0, h - 1)
            val cr = (box.right + mw).toInt().coerceIn(cl + 1, w)
            val cb = (box.bottom + mh).toInt().coerceIn(ct + 1, h)
            val crop = runCatching { Bitmap.createBitmap(bitmap, cl, ct, cr - cl, cb - ct) }.getOrNull() ?: return null
            val result = runCatching { landmarker.detect(BitmapImageBuilder(crop).build()) }
                .onFailure { println("MediaPipeFaceBackend -> crop landmark threw: $it") }
                .getOrNull()
            val cw = crop.width
            val ch = crop.height
            // ⚠⚠ Never the source: a crop that clamps to the whole image IS the cached bitmap
            // (`createBitmap` returns an immutable source as-is), and recycling it breaks the
            // `embedFace` that follows — the pass then latches faces as failed.
            if (crop !== bitmap) crop.recycle()
            result ?: return null
            result.faceLandmarks().forEach { mesh ->
                // ⚠ Crop fractions → image fractions, so the shared alignment sees the whole photo.
                val points = mesh.map { FacePoint((cl + it.x() * cw) / w, (ct + it.y() * ch) / h) }
                val mb = FaceMesh.box(points)
                val meshBox = DetectionBox(mb.left * w, mb.top * h, mb.right * w, mb.bottom * h, 0f)
                // ⚠⚠ The mesh must be THIS detection's face — the crop margin can pull in a neighbour.
                if (overlapOverSmaller(meshBox, box) < MESH_MATCHES_BOX) return@forEach
                // ⚠⚠ And one mesh per face: two detections of one face must not become two faces —
                // the "same face twice" defect of §12.8b.
                val duplicate = meshes.any { other ->
                    val ob = FaceMesh.box(other.points)
                    overlapOverSmaller(DetectionBox(ob.left * w, ob.top * h, ob.right * w, ob.bottom * h, 0f), meshBox) >= SAME_FACE_OVERLAP
                }
                if (!duplicate) meshes += ScoredMesh(points, box.score)
            }
        }
        return meshes
    }

    /** A mesh and the detector score of the box it came from (single stage has none: 1). */
    private class ScoredMesh(val points: List<FacePoint>, val score: Float)



    private var boxDetector: FaceDetector? = null
    private var boxDetectorKey: String? = null

    /**
     * The standalone BlazeFace detector — read from INSIDE the installed `face_landmarker.task`, a zip
     * whose `face_detector.tflite` is byte-identical to the one §12.8 measured. ⚠ So the two-stage
     * path needs no new catalog artifact and no second download.
     */
    private fun boxDetector(): FaceDetector? {
        val path = locateDetector() ?: return null
        val key = fileKey(path)
        boxDetector?.let { if (boxDetectorKey == key) return it }
        boxDetector?.let { runCatching { it.close() } }
        boxDetector = null
        boxDetectorKey = null
        val created = runCatching {
            val bytes = bundleEntry(java.io.File(path).readBytes(), DETECTOR_ENTRY) ?: error("no $DETECTOR_ENTRY in bundle")
            val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
            FaceDetector.createFromOptions(
                context,
                FaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buffer).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMinDetectionConfidence(minConfidence)
                    .build(),
            )
        }.onFailure { println("MediaPipeFaceBackend -> box detector failed to open: $it") }.getOrNull() ?: return null
        boxDetector = created
        boxDetectorKey = key
        return created
    }

    /**
     * One entry of a `.task` bundle. ⚠⚠ Not `java.util.zip.ZipFile`: MediaPipe's bundle starts with two
     * padding bytes before the first local header, which Python's reader skips and Android's rejects
     * ("Entry at offset zero has invalid LFH signature"). A stream from the first `PK\u0003\u0004`
     * reads it on both.
     */
    private fun bundleEntry(bundle: ByteArray, name: String): ByteArray? {
        val start = (0..bundle.size - 4).firstOrNull {
            bundle[it] == 0x50.toByte() && bundle[it + 1] == 0x4b.toByte() && bundle[it + 2] == 3.toByte() && bundle[it + 3] == 4.toByte()
        } ?: return null
        ZipInputStream(ByteArrayInputStream(bundle, start, bundle.size - start)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == name) return zip.readBytes()
            }
        }
    }

    private fun landmarker(): FaceLandmarker? {
        val path = locateDetector() ?: return null
        val key = fileKey(path)
        landmarker?.let { if (landmarkerPath == key) return it }
        // ⚠ A different path is a different artifact — close the old one rather than answer with it.
        landmarker?.let { runCatching { it.close() } }
        landmarker = null
        landmarkerPath = null
        val created = runCatching {
            FaceLandmarker.createFromOptions(
                context,
                FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(path).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(MAX_FACES_PER_PHOTO)
                    .setMinFaceDetectionConfidence(minConfidence)
                    .setMinFacePresenceConfidence(minConfidence)
                    .build(),
            )
        }.getOrNull() ?: return null
        landmarker = created
        landmarkerPath = key
        return created
    }

    private fun session(): OrtSession? {
        if (rejected) return null
        val path = locateEmbedder() ?: return null
        val key = fileKey(path)
        session?.let { if (sessionPath == key) return it }
        session?.let { runCatching { it.close() } }
        session = null
        sessionPath = null
        val created = runCatching {
            sessions.open(path)
        }.getOrNull() ?: return null
        if (!accepts(created)) {
            runCatching { created.close() }
            rejected = true
            return null
        }
        session = created
        sessionPath = key
        return created
    }

    /**
     * ⚠⚠ What a loaded runtime is cached against — the path **and** the file's size and mtime.
     * `DefaultModelStore` lands every update at a NEW content-addressed path, so the path alone
     * tells an update apart; size and mtime stay for an install made before content addressing
     * and for a side-loaded file replaced by hand at the same name, where a path-only key would keep
     * answering with the previous checkpoint until the process died.
     */
    private fun fileKey(path: String): String = File(path).let { "$path:${it.length()}:${it.lastModified()}" }

    /**
     * ⚠ The shape guard: an NCHW input at the template's side and an output of [FaceModel.dimensions].
     * A different model under the pinned name is refused rather than stamped as this one.
     */
    private fun accepts(session: OrtSession): Boolean {
        val input = session.inputInfo[session.inputNames.firstOrNull()]?.info as? TensorInfo ?: return false
        val shape = input.shape
        val side = model.template.side.toLong()
        if (shape.size != 4 || shape[1] != 3L || shape[2] != side || shape[3] != side) return false
        val output = session.outputInfo.values.firstOrNull()?.info as? TensorInfo ?: return false
        return output.shape.lastOrNull() == model.dimensions.toLong()
    }

    // ---- pixels ---------------------------------------------------------------------------------

    private fun bitmapFor(bytes: ByteArray, orientation: Int?): Bitmap? {
        // ⚠ The orientation is part of the key: the same bytes rotated differently are a different image.
        if (cachedBytes === bytes && cachedOrientation == orientation) cachedBitmap?.let { return it }
        cachedBitmap?.recycle()
        cachedBitmap = null
        cachedBytes = null
        val decoded = decodeUpright(bytes, orientation) ?: return null
        cachedBitmap = decoded
        cachedBytes = bytes
        cachedOrientation = orientation
        return decoded
    }

    /**
     * ⚠⚠ **EXIF or nothing, and bounded.** A camera writes sensor pixels and an orientation tag;
     * ignoring the tag hands the detector a sideways face it mostly does not find, which reads as
     * "nobody in this photograph". The long-edge cap keeps a 12 MP frame off Control's heap while
     * leaving a face far above the 112 px the template wants. Both exactly as F2m measured them.
     */
    private fun decodeUpright(bytes: ByteArray, orientationOverride: Int? = null): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_EDGE) sample *= 2
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null
        // ⚠ The bytes' own EXIF unless a caller overrides it (1–8). The indexer never does: a
        // preview rung is stored upright (`ExifUpright`), an original carries its tag.
        val orientation = orientationOverride ?: runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            // ⚠ The two ROTATED mirrors — handling only the plain flips leaves these sideways.
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
        val rotated = runCatching {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }.getOrNull() ?: return decoded
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    /**
     * The aligned square as packed ARGB — nearest-neighbour through [FaceCrop.unmap], clamped at the
     * border, exactly as measured (§12.5 is why this is not re-derived casually).
     */
    private fun resample(source: Bitmap, side: Int, crop: FaceCrop): IntArray {
        val w = source.width
        val h = source.height
        val out = IntArray(side * side)
        for (y in 0 until side) {
            for (x in 0 until side) {
                // ⚠ Pixel centres, and `unmap` answers in the pixel space the landmarks were fitted in.
                val at = crop.unmap(FacePoint(x + 0.5f, y + 0.5f))
                out[y * side + x] = source.getPixel(at.x.toInt().coerceIn(0, w - 1), at.y.toInt().coerceIn(0, h - 1))
            }
        }
        return out
    }

    private companion object {
        const val MAX_FACES_PER_PHOTO = 10
        const val DETECTOR_ENTRY = "face_detector.tflite"
        /** §12.8's measured values: a 25% crop margin, and the two overlap tests. */
        const val CROP_MARGIN = 0.25f
        const val MESH_MATCHES_BOX = 0.3f
        const val SAME_FACE_OVERLAP = 0.8f
        const val BOX_OVERLAP = 0.5f
        const val TILE_OVERLAP = 0.2f
        /** Below this a tile holds no face the whole-image pass could have missed. */
        const val MIN_TILE_EDGE = 128
        const val MAX_DECODE_EDGE = 2048
    }
}

/**
 * How [MediaPipeFaceBackend] finds faces.
 *
 * - [SingleStage]: the Face Landmarker over the whole photograph — what §12.6 calibrated on.
 * - [TwoStage]: a box detector first, then the landmarker on each detection's crop (§12.8's
 *   replacement, 93% against 80% of detectable faces on the verified library). [TwoStage.tileGrid] > 1
 *   also runs the detector over an overlapping grid, so a face across the room is large enough for a
 *   short-range model.
 */
sealed interface FaceDetectionStrategy {
    /**
     * ⚠⚠ Stamped into every face template's provenance: changing strategy re-indexes the library,
     * which is the point — a photograph searched with the old one keeps the old faces otherwise.
     * ⚠ Never reuse a value for a different pipeline.
     */
    val pipelineVersion: Int

    data object SingleStage : FaceDetectionStrategy {
        override val pipelineVersion = 0
    }

    data class TwoStage(val tileGrid: Int = 1) : FaceDetectionStrategy {
        init {
            require(tileGrid in 1..MAX_TILE_GRID) { "tileGrid $tileGrid" }
        }

        // ⚠ 10 × the pipeline revision + the grid. Revision 0 (stamp 2) kept the best-scoring box and
        // had no non-face gate; revision 1 (stamp 12) kept the largest box and gated on
        // `FaceModel.minFaceNorm`; revision 2 (stamp 22) keeps the largest box with the gate
        // OFF, so libraries indexed under 12 re-index and get back the real faces it wrongly dropped.
        // A new number is what re-indexes; never reuse one.
        override val pipelineVersion get() = PIPELINE_REVISION * 10 + tileGrid

        companion object {
            const val MAX_TILE_GRID = 4
            private const val PIPELINE_REVISION = 2
        }
    }
}
