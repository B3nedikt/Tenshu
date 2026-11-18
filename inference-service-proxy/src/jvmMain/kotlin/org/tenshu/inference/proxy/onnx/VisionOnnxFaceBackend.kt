package org.tenshu.inference.proxy.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import kotlin.jvm.Volatile
import kotlinx.coroutines.CancellationException
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
import org.tenshu.inference.proxy.ImageEmbedding
import org.tenshu.inference.proxy.ImageTag
import org.tenshu.inference.proxy.InferenceBackend
import org.tenshu.inference.proxy.ModelBackedBackend
import org.tenshu.inference.proxy.TextRegion
import org.tenshu.inference.proxy.alignFace
import org.tenshu.inference.proxy.resampleFace
import org.tenshu.inference.proxy.vision.FaceImage
import org.tenshu.inference.proxy.vision.FaceImageDecoder
import org.tenshu.inference.proxy.vision.VisionDetection
import org.tenshu.inference.proxy.vision.VisionFaceDetector
import org.tenshu.inference.proxy.vision.facesOf

/**
 * People on **macOS desktop** (`ios-face-grouping.md` §10.8): Apple Vision finds faces and their
 * landmarks, the shared `VisionFaceLandmarks` → [alignFace] → [resampleFace] frames them, exactly as
 * on iOS, and the AuraFace ONNX model Android ships embeds them on ORT. It is the twin of iOS's
 * `VisionCoreMlFaceBackend` with the embedder swapped, and of Android's `MediaPipeFaceBackend` with the
 * detector swapped.
 *
 * ⚠⚠ **Calibrated, not assumed**: this exact pairing (macOS Vision rev 3, corners-mid, ORT int8)
 * held ③ at the shared 0.44 with nearest different person 0.183 (§10.8). A change to the detector,
 * the mapping, the decode or the embedder runtime is a new [FaceModel.MAC_VISION_PIPELINE_VERSION]
 * and owes its own number.
 *
 * ⚠⚠ **Its stamp carries the OS build** ([FaceModel.macVisionStamp], FD9): Vision's points depend
 * on the OS that runs it, so a macOS update moves the stamp and `MediaIndexer` rebases the template
 * store at the next pass.
 *
 * ⚠⚠ **A Vision error is [FaceDetectionOutcome.Failed], never `Detected(empty)`**, and so is a run
 * at any revision but the pinned one. An empty detection is remembered against the photograph for
 * good; a failure is not.
 *
 * ⚠⚠ **Detects only while it can embed** ([supports]): faces recorded with no embedder are faces
 * nothing can use.
 *
 * ⚠ **One lock for everything**, as on Android and iOS: the indexer calls `detectFaces` and then
 * `embedFace` once per face with the SAME bytes, and the decoded image is cached between them, keyed
 * on the bytes' **identity** and the orientation, so a face is never cropped from another
 * photograph's pixels.
 *
 * ⚠ No animal veto: PT-D9 is off on every Vision platform (`photos-pets.md` §7, M5 on iOS found
 * Vision's animal request unhelpful), and turning it on is another pipeline number.
 *
 * @param locateEmbedder the installed `auraface_int8.onnx`, or `null`. Asked on every call.
 * @param osBuild the running macOS build (`sw_vers -buildVersion`), stamped into [provenance].
 */
class VisionOnnxFaceBackend(
    private val locateEmbedder: () -> String?,
    private val decoder: FaceImageDecoder,
    private val detector: VisionFaceDetector,
    osBuild: String,
    private val model: FaceModel = FaceModel.AuraFaceOnnxMac,
    private val sessions: OnnxSessionFactory = OnnxSessionFactory(),
    private val environment: () -> OrtEnvironment = { OrtEnvironment.getEnvironment() },
    private val lane: CoroutineDispatcher = MediaWorkBudget.lane,
    /** The embedder, as a port: production runs ORT ([OrtFaceEmbedder]); a test passes a fake. */
    private val openEmbedder: (path: String) -> FaceEmbedderSession = { path -> OrtFaceEmbedder.open(path, model, sessions, environment) },
) : InferenceBackend, ModelBackedBackend {

    override val id: String get() = "vision-onnx-faces"

    /** ⚠⚠ The SAME value on every vector [embedFace] returns: the model, and this OS build's Vision pipeline. */
    override val provenance: ModelProvenance = model.provenance.copy(labelSetVersion = FaceModel.macVisionStamp(osBuild))

    private val lock = Mutex()

    // Under [lock]. ⚠ The cached image is up to 2048 × 1536 ARGB, ~12–16 MB, held until the next
    // photograph or [close]: the price of decoding a photograph once for all its faces.
    private var session: FaceEmbedderSession? = null
    private var sessionKey: String? = null
    private var cachedBytes: ByteArray? = null
    private var cachedOrientation: Int? = null
    private var cachedImage: FaceImage? = null

    /** ⚠⚠ Latched per file: a model that is not the embedder is a property of the artifact. */
    @Volatile
    private var rejectedKey: String? = null

    /** How many decodes ran: for the cache's tests. */
    internal var decodes: Int = 0
        private set

    override fun supports(capability: InferenceCapability): Boolean =
        capability == InferenceCapability.FACE_CLUSTERING && embedderPath() != null

    override suspend fun detectFaces(bytes: ByteArray): FaceDetectionOutcome = detectFaces(bytes, null)

    override suspend fun embedFace(bytes: ByteArray, face: DetectedFace): ImageEmbedding? = embedFace(bytes, face, null)

    override suspend fun detectFaces(bytes: ByteArray, orientation: Int?): FaceDetectionOutcome = withContext(lane) {
        lock.withLock {
            // ⚠ A backend that cannot embed has not looked: Failed, which nothing remembers.
            if (embedderPath() == null) return@withLock FaceDetectionOutcome.Failed
            // ⚠ Only a decode refusal is Unreadable.
            val image: FaceImage = imageFor(bytes, orientation) ?: return@withLock FaceDetectionOutcome.Unreadable
            val detection: VisionDetection = try {
                detector.detect(image)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // ⚠⚠ A detector that THREW is not a photograph with nobody in it.
                println("VisionOnnxFaceBackend -> Vision failed on ${image.width}x${image.height}: $failure")
                return@withLock FaceDetectionOutcome.Failed
            }
            // ⚠⚠ Another revision finds other points: never stamped as this pipeline's (FD9).
            if (detection.revisionRun != FaceModel.VISION_LANDMARKS_REVISION) {
                println("VisionOnnxFaceBackend -> Vision ran revision ${detection.revisionRun}, not ${FaceModel.VISION_LANDMARKS_REVISION}")
                return@withLock FaceDetectionOutcome.Failed
            }
            FaceDetectionOutcome.Detected(facesOf(detection.faces, image.width, image.height))
        }
    }

    override suspend fun embedFace(bytes: ByteArray, face: DetectedFace, orientation: Int?): ImageEmbedding? = withContext(lane) {
        lock.withLock {
            val held: FaceEmbedderSession = sessionNow() ?: return@withLock null
            val image: FaceImage = imageFor(bytes, orientation) ?: return@withLock null.also {
                println("VisionOnnxFaceBackend -> embedFace could not decode the image")
            }
            val side: Int = model.template.side
            val input: FloatArray = try {
                val crop: FaceCrop = alignFace(face.landmarks, model.template, image.width, image.height)
                ImagePreprocessing.preprocessPixels(resampleFace(image.argb, image.width, image.height, side, crop), side, model.profile)
            } catch (failure: IllegalArgumentException) {
                println("VisionOnnxFaceBackend -> align/resample refused: $failure")
                return@withLock null
            }
            val vector: FloatArray = try {
                held.embed(input)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                println("VisionOnnxFaceBackend -> the embedder failed: $failure")
                return@withLock null
            }
            // ⚠⚠ On EVERY vector: a NaN normalises to NaN and a zero vector to itself, and either
            // would be stored as a face no cluster can hold.
            TowerGuards.unusableReason(vector, model.dimensions)?.let { reason ->
                println("VisionOnnxFaceBackend -> refused the embedder's vector: $reason")
                return@withLock null
            }
            ImageEmbedding(ImagePreprocessing.normalise(vector), provenance)
        }
    }

    /** Closes the embedder and drops the cached image. */
    suspend fun close() {
        lock.withLock {
            closeSession()
            cachedBytes = null
            cachedOrientation = null
            cachedImage = null
        }
    }

    // ⚠ Faces and nothing else.
    override suspend fun embedImage(bytes: ByteArray): ImageEmbedding? = null
    override suspend fun embedText(text: String): ImageEmbedding? = null
    override suspend fun tagImage(bytes: ByteArray, vocabulary: List<String>): List<ImageTag> = emptyList()
    override suspend fun recognizeText(bytes: ByteArray): List<TextRegion> = emptyList()

    /** The embedder to run, unless it is the one already refused. */
    private fun embedderPath(): String? = locateEmbedder()?.takeIf { fileKey(it) != rejectedKey }

    /** The decoded [bytes], from the cache when they are the very bytes it holds. Under [lock]. */
    private fun imageFor(bytes: ByteArray, orientation: Int?): FaceImage? {
        if (cachedBytes === bytes && cachedOrientation == orientation) cachedImage?.let { return it }
        cachedBytes = null
        cachedImage = null
        decodes++
        val decoded: FaceImage = runCatching { decoder.decodeUpright(bytes, orientation) }
            .onFailure { println("VisionOnnxFaceBackend -> decode threw: $it") }
            .getOrNull() ?: return null
        cachedBytes = bytes
        cachedOrientation = orientation
        cachedImage = decoded
        return decoded
    }

    /** The session for what [locateEmbedder] names now. Under [lock]. */
    private fun sessionNow(): FaceEmbedderSession? {
        val path: String = embedderPath() ?: return null.also { closeSession() }
        val key: String = fileKey(path)
        session?.let { if (sessionKey == key) return it }
        closeSession()
        val opened: FaceEmbedderSession = try {
            openEmbedder(path)
        } catch (rejected: RejectedFaceEmbedderException) {
            println("VisionOnnxFaceBackend -> refused $path: ${rejected.message}")
            rejectedKey = key
            return null
        } catch (failure: Throwable) {
            // ⚠ Not latched: a load that failed this time says nothing about the next.
            println("VisionOnnxFaceBackend -> the embedder did not open: $failure")
            return null
        }
        session = opened
        sessionKey = key
        return opened
    }

    private fun closeSession() {
        val held: FaceEmbedderSession = session ?: return
        session = null
        sessionKey = null
        runCatching { held.close() }
    }

    /**
     * ⚠ What a loaded embedder is cached against — the path **and** the file's size and mtime, for
     * `MediaPipeFaceBackend.fileKey`'s reason: a side-loaded file replaced by hand at the same name.
     */
    private fun fileKey(path: String): String = File(path).let { "$path:${it.length()}:${it.lastModified()}" }
}

/** One open face embedder: pixels in, the raw (un-normalised) vector out. */
interface FaceEmbedderSession : AutoCloseable {
    fun embed(input: FloatArray): FloatArray
}

/** The file opened is not the face embedder this build pins: latched, never retried. */
class RejectedFaceEmbedderException(message: String) : Exception(message)

/** [FaceEmbedderSession] on ORT, through the module's one session policy ([OnnxSessionFactory]). */
internal class OrtFaceEmbedder private constructor(
    private val session: OrtSession,
    private val side: Int,
    private val environment: () -> OrtEnvironment,
) : FaceEmbedderSession {

    override fun embed(input: FloatArray): FloatArray =
        OnnxTensor.createTensor(environment(), FloatBuffer.wrap(input), longArrayOf(1, 3, side.toLong(), side.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { out ->
                ((out[0].value as Array<*>)[0] as FloatArray).copyOf()
            }
        }

    override fun close() = session.close()

    companion object {
        /**
         * Opens [path] and refuses it unless it is the face embedder: an NCHW input at the
         * template's side and an output [FaceModel.dimensions] wide (`MediaPipeFaceBackend.accepts`).
         */
        fun open(path: String, model: FaceModel, sessions: OnnxSessionFactory, environment: () -> OrtEnvironment): OrtFaceEmbedder {
            val session: OrtSession = sessions.open(path)
            val side: Long = model.template.side.toLong()
            val input = session.inputInfo[session.inputNames.firstOrNull()]?.info as? TensorInfo
            val output = session.outputInfo.values.firstOrNull()?.info as? TensorInfo
            val fits: Boolean = input != null && output != null &&
                input.shape.size == 4 && input.shape[1] == 3L && input.shape[2] == side && input.shape[3] == side &&
                output.shape.lastOrNull() == model.dimensions.toLong()
            if (!fits) {
                runCatching { session.close() }
                throw RejectedFaceEmbedderException("not a ${model.dimensions}-wide ${side}px face embedder")
            }
            return OrtFaceEmbedder(session, model.template.side, environment)
        }
    }
}
