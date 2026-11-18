package org.tenshu.inference.proxy.coreml

import kotlin.concurrent.Volatile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tenshu.core.common.proxy.MediaWorkBudget
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.AnimalSpecies
import org.tenshu.inference.proxy.AnimalVeto
import org.tenshu.inference.proxy.DetectedAnimal
import org.tenshu.inference.proxy.DetectedFace
import org.tenshu.inference.proxy.FaceBox
import org.tenshu.inference.proxy.FaceCrop
import org.tenshu.inference.proxy.FaceDetectionOutcome
import org.tenshu.inference.proxy.FaceLandmarks
import org.tenshu.inference.proxy.FacePreflightModels
import org.tenshu.inference.proxy.FaceProbeDetection
import org.tenshu.inference.proxy.FaceProbeFace
import org.tenshu.inference.proxy.FaceProbeImage
import org.tenshu.inference.proxy.ImageEmbedding
import org.tenshu.inference.proxy.ImageTag
import org.tenshu.inference.proxy.InferenceBackend
import org.tenshu.inference.proxy.ModelBackedBackend
import org.tenshu.inference.proxy.PreflightTower
import org.tenshu.inference.proxy.ReadinessSlot
import org.tenshu.inference.proxy.TextRegion
import org.tenshu.inference.proxy.VisionFaceLandmarks
import org.tenshu.inference.proxy.alignFace
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.onnx.FaceModel
import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import org.tenshu.inference.proxy.onnx.TowerGuards
import org.tenshu.inference.proxy.resampleFace
import org.tenshu.inference.proxy.vision.FACE_DECODE_MAX_EDGE
import org.tenshu.inference.proxy.vision.FaceImage
import org.tenshu.inference.proxy.vision.FaceImageDecoder
import org.tenshu.inference.proxy.vision.VisionDetection
import org.tenshu.inference.proxy.vision.VisionFaceDetector
import org.tenshu.inference.proxy.vision.VisionFaceObservation
import org.tenshu.inference.proxy.vision.VisionRequestException
import org.tenshu.inference.proxy.vision.faceDecodeEdge
import org.tenshu.inference.proxy.vision.facesOf
import org.tenshu.inference.proxy.vision.orient
import org.tenshu.inference.proxy.vision.visionFaceBox
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetInterpolationQuality
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.CoreGraphics.kCGInterpolationHigh
import platform.Foundation.NSError
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVReturnSuccess
import platform.Vision.VNAnimalIdentifierCat
import platform.Vision.VNAnimalIdentifierDog
import platform.Vision.VNClassificationObservation
import platform.Vision.VNDetectFaceLandmarksRequest
import platform.Vision.VNRecognizeAnimalsRequest
import platform.Vision.VNRecognizedObjectObservation
import platform.Vision.VNFaceLandmarkRegion2D
import platform.Vision.VNFaceObservation
import platform.Vision.VNImageRequestHandler

/**
 * What the iOS face path feeds the detector and the crop, as a number: the face key's
 * `preprocessingRevision` (`ios-face-grouping.md` §6.2).
 *
 * ⚠⚠ **Bump it by hand whenever [IosFaceImageDecoder] — its bound, its orientation handling, its
 * colour space, its resampler — changes the pixels a photograph becomes.** Not
 * [IOS_PREPROCESSING_REVISION]: that is SigLIP's 256² squash, a different decode.
 */
const val IOS_FACE_PREPROCESSING_REVISION: Int = 1

/** What one animal request returned: the cats and dogs, and **the revision Vision says it ran**. */
class VisionAnimalDetection(val revisionRun: Int, val animals: List<DetectedAnimal>)

/**
 * Vision's cat and dog detector (`photos-pets.md` PT-D4), as the face backend's animal veto uses it.
 * ⚠ The same contract as [VisionFaceDetector]: reports the revision run, throws on a Vision error,
 * and never answers a failure with an empty list.
 */
fun interface VisionAnimalDetector {
    fun detect(image: FaceImage): VisionAnimalDetection
}

/**
 * The face pipeline on iOS (`ios-face-grouping.md` §4): Apple Vision finds faces and their
 * landmarks, the shared [VisionFaceLandmarks] → [alignFace] → [resampleFace] frames them, and the
 * AuraFace Core ML archive embeds them. Android's [org.tenshu.inference.proxy.InferenceBackend]
 * contract, `MediaPipeFaceBackend`'s twin.
 *
 * ⚠⚠ **Bound in no graph** (F4). F5 binds it in Photos behind the face flag.
 *
 * ⚠⚠ **Claims nothing until its readiness is `Ready`** ([ready]): `supports`, `detectFaces` and
 * `embedFace` all ask it, and both ask again after the blocking call, because a teardown or a
 * realm reset can land while Vision or Core ML runs.
 *
 * ⚠⚠ **A Vision error is [FaceDetectionOutcome.Failed], never `Detected(empty)`.** An empty
 * detection is remembered against the photograph for good; a failure is not.
 *
 * ⚠⚠ **One lock for everything**, as Android: the indexer calls `detectFaces` and then `embedFace`
 * once per face with the SAME bytes, and the decoded image is cached between them, keyed on the
 * bytes' **identity** and the orientation. Any other bytes are decoded afresh, so a face is never
 * cropped from another photograph's pixels.
 *
 * @param locate the compiled embedder readiness serves, or `null`. Asked on every call.
 * @param open [CoreMlSession.open] with the shipped compute units; a test passes a fake.
 * @param recordedOpen readiness's record around every open of the embedder (`AiReadiness.servingOpen`).
 */
class VisionCoreMlFaceBackend(
    private val locate: () -> String?,
    private val ready: () -> Boolean = { true },
    private val decoder: FaceImageDecoder = IosFaceImageDecoder,
    private val detector: VisionFaceDetector = SystemVisionFaceDetector,
    private val model: FaceModel = FaceModel.AuraFaceCoreMl,
    private val open: (compiledPath: String, spec: CoreMlTowerSpec) -> CoreMlSessionPort =
        { compiledPath, spec -> CoreMlSession.open(compiledPath, spec) },
    private val lane: CoroutineDispatcher = MediaWorkBudget.lane,
    /** Awaited before the embedder can be opened, outside the lock: an earlier graph may still be unloading. */
    private val beforeLoad: suspend () -> Unit = {},
    private val recordedOpen: suspend (role: String, open: () -> CoreMlSessionPort) -> CoreMlSessionPort = { _, opening -> opening() },
    /**
     * PT-D9: drop faces inside a confident cat or dog box before they are embedded. ⚠⚠ `null` — off —
     * in every shipping graph until M5 has measured it (`photos-pets.md` §7); on, it is another
     * pipeline with another stamp ([FaceModel.VISION_ANIMAL_VETO_PIPELINE_VERSION]).
     */
    private val animalVeto: AnimalVeto? = null,
    private val animalDetector: VisionAnimalDetector = SystemVisionAnimalDetector,
) : InferenceBackend, ModelBackedBackend {

    override val id: String get() = "vision-core-ml-faces"

    /**
     * ⚠⚠ The model's stamp with the iOS detection pipeline in its third field — the SAME value on
     * every vector [embedFace] returns, and never one of Android's (FD9, `FaceModel.VISION_PIPELINE_VERSION`).
     */
    override val provenance: ModelProvenance = model.provenance.copy(
        labelSetVersion = if (animalVeto == null) FaceModel.VISION_PIPELINE_VERSION else FaceModel.VISION_ANIMAL_VETO_PIPELINE_VERSION,
    )

    private val spec: CoreMlTowerSpec = CoreMlTowerSpec.faceEmbedder(model)

    private val lock = Mutex()

    // Under [lock]. ⚠ The cached image is up to 2048 × 1536 ARGB, ~12–16 MB, held until the next
    // photograph or [closeSessions]: the price of decoding a photograph once for all its faces.
    private var session: CoreMlSessionPort? = null
    private var cachedBytes: ByteArray? = null
    private var cachedOrientation: Int? = null
    private var cachedImage: FaceImage? = null

    /** ⚠⚠ Latched per path: an archive that is not the embedder is a property of the artifact. Read without the lock. */
    @Volatile
    private var rejectedPath: String? = null

    /** How many decodes ran: for the cache's tests. */
    internal var decodes: Int = 0
        private set

    override fun supports(capability: InferenceCapability): Boolean {
        if (capability != InferenceCapability.FACE_CLUSTERING || !ready()) return false
        val path: String = locate() ?: return false
        return path != rejectedPath
    }

    override suspend fun detectFaces(bytes: ByteArray): FaceDetectionOutcome = detectFaces(bytes, null)

    override suspend fun embedFace(bytes: ByteArray, face: DetectedFace): ImageEmbedding? = embedFace(bytes, face, null)

    override suspend fun detectFaces(bytes: ByteArray, orientation: Int?): FaceDetectionOutcome = withContext(lane) {
        lock.withLock {
            // ⚠ A backend that does not serve has not looked: Failed, which nothing remembers.
            if (!serving()) return@withLock FaceDetectionOutcome.Failed
            // ⚠ Only a decode refusal is Unreadable.
            val image: FaceImage = imageFor(bytes, orientation) ?: return@withLock FaceDetectionOutcome.Unreadable
            val detection: VisionDetection = try {
                detector.detect(image)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // ⚠⚠ A detector that THREW is not a photograph with nobody in it.
                println("VisionCoreMlFaceBackend -> Vision failed on ${image.width}x${image.height}: $failure")
                return@withLock FaceDetectionOutcome.Failed
            }
            // ⚠⚠ Another revision finds other points: never stamped as this pipeline's (FD9).
            if (detection.revisionRun != FaceModel.VISION_LANDMARKS_REVISION) {
                println("VisionCoreMlFaceBackend -> Vision ran revision ${detection.revisionRun}, not ${FaceModel.VISION_LANDMARKS_REVISION}")
                return@withLock FaceDetectionOutcome.Failed
            }
            val faces: List<DetectedFace> = facesOf(detection.faces, image.width, image.height)
            val vetoed: AnimalVeto.Vetoed = vetoAnimals(faces, image) ?: return@withLock FaceDetectionOutcome.Failed
            // ⚠⚠ Asked again after the blocking call: a teardown under it means nothing of this is recorded.
            if (!serving()) return@withLock FaceDetectionOutcome.Failed
            FaceDetectionOutcome.Detected(vetoed.kept, insideAnimals = vetoed.removed)
        }
    }

    /**
     * [faces] after the animal veto, or `null` when the animal request could not run.
     *
     * ⚠⚠ `null` is [FaceDetectionOutcome.Failed], never "keep them all": this backend's stamp says the
     * veto ran, so a photograph recorded without it would be a different pipeline's record under this
     * one's name, and `visited` would never bring it back.
     * ⚠ Asked only when there are faces: a faceless photograph — most of a library — costs nothing.
     */
    private fun vetoAnimals(faces: List<DetectedFace>, image: FaceImage): AnimalVeto.Vetoed? {
        val veto: AnimalVeto = animalVeto ?: return AnimalVeto.Vetoed(faces, 0)
        if (faces.isEmpty()) return AnimalVeto.Vetoed(faces, 0)
        val animals: VisionAnimalDetection = try {
            animalDetector.detect(image)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            println("VisionCoreMlFaceBackend -> Vision's animal request failed on ${image.width}x${image.height}: $failure")
            return null
        }
        if (animals.revisionRun != FaceModel.VISION_ANIMALS_REVISION) {
            println("VisionCoreMlFaceBackend -> Vision ran animal revision ${animals.revisionRun}, not ${FaceModel.VISION_ANIMALS_REVISION}")
            return null
        }
        return veto.apply(faces, animals.animals)
    }

    override suspend fun embedFace(bytes: ByteArray, face: DetectedFace, orientation: Int?): ImageEmbedding? = withContext(lane) {
        if (!ready()) return@withContext null
        // ⚠ Before the lock, never under it: the wait can be long, and it is not this backend's.
        beforeLoad()
        lock.withLock {
            val held: CoreMlSessionPort = sessionNow() ?: return@withLock null
            val image: FaceImage = imageFor(bytes, orientation) ?: return@withLock null.also {
                println("VisionCoreMlFaceBackend -> embedFace could not decode the image")
            }
            val side: Int = model.template.side
            val input: FloatArray = try {
                val crop: FaceCrop = alignFace(face.landmarks, model.template, image.width, image.height)
                ImagePreprocessing.preprocessPixels(resampleFace(image.argb, image.width, image.height, side, crop), side, model.profile)
            } catch (failure: IllegalArgumentException) {
                println("VisionCoreMlFaceBackend -> align/resample refused: $failure")
                return@withLock null
            }
            val vector: FloatArray = try {
                held.predict(input)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                println("VisionCoreMlFaceBackend -> the embedder failed: $failure")
                return@withLock null
            }
            // ⚠⚠ On EVERY vector, here and not only in the session: a NaN normalises to NaN and a
            // zero vector to itself, and either would be stored as a face no cluster can hold.
            TowerGuards.unusableReason(vector, model.dimensions)?.let { reason ->
                println("VisionCoreMlFaceBackend -> refused the embedder's vector: $reason")
                return@withLock null
            }
            // ⚠⚠ Asked again AFTER the prediction, for the reason detectFaces gives.
            if (!ready()) return@withLock null
            ImageEmbedding(ImagePreprocessing.normalise(vector), provenance)
        }
    }

    /** Closes the embedder and drops the cached image: leaving *ready*, or a memory warning. */
    suspend fun closeSessions() {
        lock.withLock {
            close()
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

    /** Whether a call may run now: ready, and an embedder to run it on. ⚠ Detecting with nothing to embed records faces nothing can use. */
    private fun serving(): Boolean = ready() && locate()?.let { it != rejectedPath } == true

    /** The decoded [bytes], from the cache when they are the very bytes it holds. Under [lock]. */
    private fun imageFor(bytes: ByteArray, orientation: Int?): FaceImage? {
        if (cachedBytes === bytes && cachedOrientation == orientation) cachedImage?.let { return it }
        cachedBytes = null
        cachedImage = null
        decodes++
        val decoded: FaceImage = decoder.decodeUpright(bytes, orientation) ?: return null
        cachedBytes = bytes
        cachedOrientation = orientation
        cachedImage = decoded
        return decoded
    }

    /** The session for what [locate] names now. Under [lock]. */
    private suspend fun sessionNow(): CoreMlSessionPort? {
        val path: String? = if (ready()) locate() else null
        val held: CoreMlSessionPort? = session
        if (held != null && held.compiledPath == path) return held
        close()
        if (path == null || path == rejectedPath) return null
        rejectedPath = null
        val opened: CoreMlSessionPort = try {
            recordedOpen(spec.role) { open(path, spec) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (rejected: CoreMlRejectedModelException) {
            rejectedPath = path
            return null
        } catch (failure: Throwable) {
            // ⚠ Not latched: a load that failed this time says nothing about the next.
            println("VisionCoreMlFaceBackend -> the embedder did not open: $failure")
            return null
        }
        session = opened
        return opened
    }

    private fun close() {
        val held: CoreMlSessionPort = session ?: return
        session = null
        runCatching { held.close() }
    }
}

/**
 * The backend as Photos would bind it (F5): every path and every claim from the face [readiness]
 * (FD7), never from the install record.
 *
 * ⚠⚠ [readiness] must be a [ReadinessSlot.FACES] readiness, and is refused otherwise. ⚠⚠ **F5:** its
 * `applyFingerprint` must be a custody over [ReadinessSlot.FACES]' `fingerprintPath`, never SigLIP's
 * `InferenceStoreCustody`: the face key's fingerprint written over `/inference/runtime.fingerprint`
 * would read as another vision archive and clear every SigLIP vector.
 */
fun faceBackendOver(
    readiness: AiReadiness,
    decoder: FaceImageDecoder = IosFaceImageDecoder,
    detector: VisionFaceDetector = SystemVisionFaceDetector,
    open: (compiledPath: String, spec: CoreMlTowerSpec) -> CoreMlSessionPort =
        { compiledPath, spec -> CoreMlSession.open(compiledPath, spec) },
    beforeLoad: suspend () -> Unit = {},
    foregroundNow: () -> Long? = { null },
    nowEpochMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
): VisionCoreMlFaceBackend {
    // ⚠⚠ By construction: a face backend over search's readiness is refused, not wired.
    require(readiness.slot == ReadinessSlot.FACES) { "the face backend needs the face readiness, not ${readiness.slot}" }
    return VisionCoreMlFaceBackend(
    locate = { readiness.serving()?.paths?.get(ModelArtifactRole.FACE_EMBEDDER) },
    ready = readiness::serves,
    decoder = decoder,
    detector = detector,
    open = open,
    beforeLoad = beforeLoad,
    recordedOpen = { role, opening -> readiness.servingOpen(role, foregroundNow(), nowEpochMs(), opening) },
    )
}

/**
 * The face preflight's models (§6.2): the embedder opened through [open] with the backend's spec,
 * the very detector and mapping the backend runs, and the bundled face ([FaceProbePhoto]) through the
 * backend's decode.
 */
class CoreMlFacePreflightModels(
    private val open: (compiledPath: String, spec: CoreMlTowerSpec) -> CoreMlSessionPort =
        { compiledPath, spec -> CoreMlSession.open(compiledPath, spec) },
    private val detector: VisionFaceDetector = SystemVisionFaceDetector,
    model: FaceModel = FaceModel.AuraFaceCoreMl,
    private val decoder: FaceImageDecoder = IosFaceImageDecoder,
) : FacePreflightModels {

    private val spec: CoreMlTowerSpec = CoreMlTowerSpec.faceEmbedder(model)

    /** [FaceProbePhoto] through the backend's own decode. */
    override fun faceProbe(): FaceProbeImage {
        val image: FaceImage = decoder.decodeUpright(FaceProbePhoto.jpeg, null) ?: throw IllegalStateException("the bundled face probe does not decode")
        return FaceProbeImage(image.width, image.height, image.argb)
    }

    override fun openEmbedder(compiledPath: String): PreflightTower {
        val session: CoreMlSessionPort = open(compiledPath, spec)
        return object : PreflightTower {
            override fun predict(floats: FloatArray): FloatArray = session.predict(floats)
            override fun predict(ids: IntArray): FloatArray = throw UnsupportedOperationException("the face embedder takes pixels")
            override fun close() = session.close()
        }
    }

    override fun detect(argb: IntArray, width: Int, height: Int): FaceProbeDetection {
        val detection: VisionDetection = detector.detect(FaceImage(width, height, argb))
        return FaceProbeDetection(
            revision = detection.revisionRun,
            faces = detection.faces.map { observation ->
                FaceProbeFace(observation.box, observation.regions?.let { VisionFaceLandmarks.landmarks(it, width, height) })
            },
        )
    }
}

/**
 * `VNDetectFaceLandmarksRequest` at [FaceModel.VISION_LANDMARKS_REVISION] (FD9), over a pixel
 * buffer made from the decoded pixels themselves, so Vision and the crop read the same image.
 *
 * ⚠ `imageSize` for `pointsInImage` is the image Vision ran on — the upright decode — never the
 * file's stored size (`VisionFaceLandmarks`).
 * ⚠ A new handler and request per image, as F1a and F1b ran it.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object SystemVisionFaceDetector : VisionFaceDetector {

    private val revision: Int = FaceModel.VISION_LANDMARKS_REVISION

    override fun detect(image: FaceImage): VisionDetection = autoreleasepool {
        val buffer: CVPixelBufferRef = pixelBufferOf(image) ?: throw VisionRequestException("the pixels could not be wrapped for Vision")
        try {
            val request = VNDetectFaceLandmarksRequest()
            request.setRevision(revision.toULong())
            val handler = VNImageRequestHandler(cVPixelBuffer = buffer, options = emptyMap<Any?, Any?>())
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                if (!handler.performRequests(listOf(request), error.ptr)) {
                    throw VisionRequestException("Vision failed: ${error.value?.localizedDescription ?: "no NSError reported"}")
                }
            }
            val w: Double = image.width.toDouble()
            val h: Double = image.height.toDouble()
            VisionDetection(revisionRun = request.revision.toInt(), faces = (request.results ?: emptyList<Any?>()).map { result ->
                val face: VNFaceObservation = result as? VNFaceObservation ?: throw VisionRequestException("Vision returned $result")
                val box: FaceBox = face.boundingBox.useContents { visionFaceBox(origin.x, origin.y, size.width, size.height, face.confidence) }
                val regions: VisionFaceLandmarks.Regions? = face.landmarks?.let { landmarks ->
                    fun points(region: VNFaceLandmarkRegion2D?): FloatArray? = region?.let { inImage(it, w, h) }
                    VisionFaceLandmarks.Regions(
                        leftEye = points(landmarks.leftEye) ?: return@let null,
                        rightEye = points(landmarks.rightEye) ?: return@let null,
                        leftPupil = points(landmarks.leftPupil) ?: return@let null,
                        rightPupil = points(landmarks.rightPupil) ?: return@let null,
                        nose = points(landmarks.nose) ?: return@let null,
                        noseCrest = points(landmarks.noseCrest) ?: return@let null,
                        outerLips = points(landmarks.outerLips) ?: return@let null,
                    )
                }
                VisionFaceObservation(box, regions)
            })
        } finally {
            CVPixelBufferRelease(buffer)
        }
    }

    /** `pointsInImage(imageSize:)`: pixels, origin bottom-left, flattened. */
    private fun inImage(region: VNFaceLandmarkRegion2D, width: Double, height: Double): FloatArray? {
        val count: Int = region.pointCount.toInt()
        if (count <= 0) return null
        val points = region.pointsInImageOfSize(CGSizeMake(width, height)) ?: return null
        val out = FloatArray(count * 2)
        for (i in 0 until count) {
            out[2 * i] = points[i].x.toFloat()
            out[2 * i + 1] = points[i].y.toFloat()
        }
        return out
    }

    /**
     * The decoded pixels as a BGRA `CVPixelBuffer`. ⚠⚠ Not a `CGImage`: on the iOS simulator Vision
     * finds no face in a CGImage-backed request — the bundled face, 0 faces through every CGImage
     * route tried (bitmap context in four layouts, ImageIO's own image), 1 through a pixel buffer and
     * through encoded data (§14.4). A pixel buffer is the camera's own input type and copies once.
     */
    internal fun pixelBufferOf(image: FaceImage): CVPixelBufferRef? = memScoped {
        val created = alloc<CVPixelBufferRefVar>()
        if (CVPixelBufferCreate(null, image.width.convert(), image.height.convert(), kCVPixelFormatType_32BGRA, null, created.ptr) != kCVReturnSuccess) return@memScoped null
        val buffer: CVPixelBufferRef = created.value ?: return@memScoped null
        CVPixelBufferLockBaseAddress(buffer, 0u)
        try {
            val base: CPointer<ByteVar> = CVPixelBufferGetBaseAddress(buffer)?.reinterpret() ?: run {
                CVPixelBufferRelease(buffer)
                return@memScoped null
            }
            val stride: Int = CVPixelBufferGetBytesPerRow(buffer).toInt()
            val argb: IntArray = image.argb
            for (y in 0 until image.height) {
                val row: Int = y * stride
                for (x in 0 until image.width) {
                    val pixel: Int = argb[y * image.width + x]
                    val at: Int = row + 4 * x
                    base[at] = pixel.toByte()
                    base[at + 1] = (pixel shr 8).toByte()
                    base[at + 2] = (pixel shr 16).toByte()
                    base[at + 3] = 0xFF.toByte()
                }
            }
        } finally {
            CVPixelBufferUnlockBaseAddress(buffer, 0u)
        }
        buffer
    }
}

/**
 * `VNRecognizeAnimalsRequest` at [FaceModel.VISION_ANIMALS_REVISION], over the same pixel buffer
 * route the face detector uses (a CGImage finds nothing on the simulator, [SystemVisionFaceDetector]).
 *
 * ⚠ Each observation's species is its top label; an observation whose top label is neither cat nor
 * dog is dropped (PT-D5). Its confidence is that label's.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object SystemVisionAnimalDetector : VisionAnimalDetector {

    override fun detect(image: FaceImage): VisionAnimalDetection = autoreleasepool {
        val buffer: CVPixelBufferRef = SystemVisionFaceDetector.pixelBufferOf(image)
            ?: throw VisionRequestException("the pixels could not be wrapped for Vision")
        try {
            val request = VNRecognizeAnimalsRequest()
            request.setRevision(FaceModel.VISION_ANIMALS_REVISION.toULong())
            val handler = VNImageRequestHandler(cVPixelBuffer = buffer, options = emptyMap<Any?, Any?>())
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                if (!handler.performRequests(listOf(request), error.ptr)) {
                    throw VisionRequestException("Vision failed: ${error.value?.localizedDescription ?: "no NSError reported"}")
                }
            }
            VisionAnimalDetection(revisionRun = request.revision.toInt(), animals = (request.results ?: emptyList<Any?>()).mapNotNull { result ->
                val observation: VNRecognizedObjectObservation = result as? VNRecognizedObjectObservation
                    ?: throw VisionRequestException("Vision returned $result")
                val top: VNClassificationObservation = observation.labels.firstOrNull() as? VNClassificationObservation ?: return@mapNotNull null
                val species: AnimalSpecies = when (top.identifier) {
                    VNAnimalIdentifierCat -> AnimalSpecies.CAT
                    VNAnimalIdentifierDog -> AnimalSpecies.DOG
                    else -> return@mapNotNull null
                }
                observation.boundingBox.useContents { visionAnimal(species, origin.x, origin.y, size.width, size.height, top.confidence) }
            })
        } finally {
            CVPixelBufferRelease(buffer)
        }
    }
}

/** Vision's `boundingBox` — normalised, origin bottom-left — as a top-left [DetectedAnimal], [visionFaceBox]'s flip. */
internal fun visionAnimal(species: AnimalSpecies, x: Double, y: Double, width: Double, height: Double, confidence: Float): DetectedAnimal =
    DetectedAnimal(
        species = species,
        left = x.toFloat(),
        top = (1.0 - (y + height)).toFloat(),
        right = (x + width).toFloat(),
        bottom = (1.0 - y).toFloat(),
        confidence = confidence,
    )

/** An sRGB RGBX context, 8 bits a channel, as `IosImageDecoder` draws into. */
@OptIn(ExperimentalForeignApi::class)
private fun rgbxContext(width: Int, height: Int): CGContextRef? {
    val colorSpace = CGColorSpaceCreateWithName(kCGColorSpaceSRGB) ?: return null
    val context = CGBitmapContextCreate(
        data = null,
        width = width.convert(),
        height = height.convert(),
        bitsPerComponent = 8u,
        bytesPerRow = (width * 4).convert(),
        space = colorSpace,
        bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
    )
    CGColorSpaceRelease(colorSpace)
    return context
}

/**
 * The face path's decode over ImageIO: bounded at [FACE_DECODE_MAX_EDGE] by Android's
 * power-of-two rule ([faceDecodeEdge]), upright, drawn into sRGB.
 *
 * ⚠ Upright two ways: the bytes' own EXIF through `kCGImageSourceCreateThumbnailWithTransform`, as
 * `IosImageDecoder` does, or — when the caller names an [orientation], which a preview needs —
 * that orientation applied here, over the stored pixels ([orient]).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosFaceImageDecoder : FaceImageDecoder {

    override fun decodeUpright(bytes: ByteArray, orientation: Int?): FaceImage? {
        if (bytes.isEmpty()) return null
        if (orientation != null && orientation !in 1..8) return null
        return autoreleasepool {
            val thumbnail: CGImageRef = thumbnailOf(bytes, applyExif = orientation == null) ?: return@autoreleasepool null
            try {
                val stored: FaceImage = draw(thumbnail) ?: return@autoreleasepool null
                if (orientation == null) stored else orient(stored, orientation)
            } finally {
                CGImageRelease(thumbnail)
            }
        }
    }

    private fun thumbnailOf(bytes: ByteArray, applyExif: Boolean): CGImageRef? = memScoped {
        val data = bytes.usePinned { pinned ->
            CFDataCreate(null, pinned.addressOf(0).reinterpret<UByteVar>(), bytes.size.convert())
        } ?: return@memScoped null
        val source = CGImageSourceCreateWithData(data, null)
        CFRelease(data)
        if (source == null) return@memScoped null
        val edge: Int = pixelDimensionsOf(source)?.let { (width, height) -> faceDecodeEdge(width, height) } ?: FACE_DECODE_MAX_EDGE
        val limit = alloc<IntVar>()
        limit.value = edge
        val limitNumber = CFNumberCreate(null, kCFNumberIntType, limit.ptr)
        val options = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionaryAddValue(options, kCGImageSourceThumbnailMaxPixelSize, limitNumber)
        // ⚠ `…FromImageAlways`: an embedded EXIF thumbnail is 160 px.
        CFDictionaryAddValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
        CFDictionaryAddValue(options, kCGImageSourceCreateThumbnailWithTransform, if (applyExif) kCFBooleanTrue else kCFBooleanFalse)
        try {
            CGImageSourceCreateThumbnailAtIndex(source, 0u, options)
        } finally {
            CFRelease(options)
            CFRelease(limitNumber)
            CFRelease(source)
        }
    }

    private fun pixelDimensionsOf(source: CGImageSourceRef): Pair<Int, Int>? = memScoped {
        val properties = CGImageSourceCopyPropertiesAtIndex(source, 0u, null) ?: return@memScoped null
        try {
            val width = alloc<IntVar>()
            val height = alloc<IntVar>()
            val widthNumber: CFNumberRef? = CFDictionaryGetValue(properties, kCGImagePropertyPixelWidth)?.reinterpret()
            val heightNumber: CFNumberRef? = CFDictionaryGetValue(properties, kCGImagePropertyPixelHeight)?.reinterpret()
            if (widthNumber == null || heightNumber == null) return@memScoped null
            if (!CFNumberGetValue(widthNumber, kCFNumberIntType, width.ptr)) return@memScoped null
            if (!CFNumberGetValue(heightNumber, kCFNumberIntType, height.ptr)) return@memScoped null
            (width.value to height.value).takeIf { width.value > 0 && height.value > 0 }
        } finally {
            CFRelease(properties)
        }
    }

    /** [image] drawn at its own size into sRGB, as packed ARGB. */
    private fun draw(image: CGImageRef): FaceImage? {
        val width: Int = CGImageGetWidth(image).toInt()
        val height: Int = CGImageGetHeight(image).toInt()
        if (width <= 0 || height <= 0) return null
        val context: CGContextRef = rgbxContext(width, height) ?: return null
        try {
            CGContextSetInterpolationQuality(context, kCGInterpolationHigh)
            CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
            val pixels: CPointer<ByteVar> = CGBitmapContextGetData(context)?.reinterpret() ?: return null
            val argb = IntArray(width * height)
            for (i in argb.indices) {
                val red: Int = pixels[4 * i].toInt() and 0xFF
                val green: Int = pixels[4 * i + 1].toInt() and 0xFF
                val blue: Int = pixels[4 * i + 2].toInt() and 0xFF
                argb[i] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
            return FaceImage(width, height, argb)
        } finally {
            CGContextRelease(context)
        }
    }
}
