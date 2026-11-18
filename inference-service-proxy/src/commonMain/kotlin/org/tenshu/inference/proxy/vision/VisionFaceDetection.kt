package org.tenshu.inference.proxy.vision

import org.tenshu.inference.proxy.DetectedFace
import org.tenshu.inference.proxy.FaceBox
import org.tenshu.inference.proxy.FaceLandmarks
import org.tenshu.inference.proxy.VisionFaceLandmarks

/**
 * The Apple Vision face path's platform-neutral half, shared by the iOS backend
 * (`VisionCoreMlFaceBackend`, Core ML embedder) and the macOS desktop one (`VisionOnnxFaceBackend`,
 * ORT embedder): the decoded image, the detector port, and what Vision's observations become.
 * Vision itself is reached per platform (Kotlin/Native interop on iOS, FFM on the JVM).
 */

/**
 * The long edge the face path decodes at, at most (`MediaPipeFaceBackend.MAX_DECODE_EDGE`): a face
 * stays far above the template's 112 px while a 48 MP frame never becomes a full-size bitmap.
 */
const val FACE_DECODE_MAX_EDGE: Int = 2048

/** At most this many faces per photograph, the largest first: Android's cap. */
const val MAX_FACES_PER_PHOTO: Int = 10

/**
 * The long edge a `width × height` image is decoded at: halved while it is over
 * [FACE_DECODE_MAX_EDGE], as Android's power-of-two `inSampleSize` does
 * (`MediaPipeFaceBackend.decodeUpright`), so every platform sees a photograph at the same scale.
 */
fun faceDecodeEdge(width: Int, height: Int): Int {
    val long: Int = maxOf(width, height)
    var sample = 1
    while (long / sample > FACE_DECODE_MAX_EDGE) sample *= 2
    return (long / sample).coerceAtLeast(1)
}

/** One decoded photograph, upright: row-major packed ARGB, exactly `width * height` pixels. */
class FaceImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "${argb.size} pixels is not a ${width}x$height image" }
    }
}

/** The face path's decode: bounded, upright, sRGB. `null` for bytes that are not an image. */
fun interface FaceImageDecoder {
    /** @param orientation EXIF 1–8 to apply instead of the bytes' own (a preview carries none); `null` reads the bytes'. */
    fun decodeUpright(bytes: ByteArray, orientation: Int?): FaceImage?
}

/**
 * One face as Vision reported it, in plain numbers: no Vision type leaves the platform file.
 *
 * @param box top-left image fractions, Vision's own `confidence` in it.
 * @param regions the seven regions `VisionFaceLandmarks` reads, `pointsInImage` pixels (bottom-left
 *   origin) **of the image Vision ran on**; `null` when Vision gave no landmarks.
 */
class VisionFaceObservation(val box: FaceBox, val regions: VisionFaceLandmarks.Regions?)

/** What one Vision request returned: the faces, and **the revision Vision says it ran**. */
class VisionDetection(val revisionRun: Int, val faces: List<VisionFaceObservation>)

/** Vision's face detector, as the backends and the preflight use it: a port, so the guards are testable. */
interface VisionFaceDetector {
    /**
     * Every face in [image], asking for `FaceModel.VISION_LANDMARKS_REVISION`.
     * ⚠ Reports the revision Vision actually ran, not the one asked for: whether that is the pinned
     * one is the caller's check (the backend's, and the preflight judge's).
     * @throws Throwable on a Vision error. ⚠⚠ Never an empty list for a failure: an empty list is
     *   remembered against the photograph for good.
     */
    fun detect(image: FaceImage): VisionDetection
}

/** A Vision request that failed. */
class VisionRequestException(message: String) : Exception(message)

/**
 * Vision's `boundingBox` — normalised, origin **bottom-left** — as a top-left [FaceBox].
 * ⚠ The y-flip of the box; [VisionFaceLandmarks] flips the points.
 */
fun visionFaceBox(x: Double, y: Double, width: Double, height: Double, confidence: Float): FaceBox =
    FaceBox(left = x.toFloat(), top = (1.0 - (y + height)).toFloat(), right = (x + width).toFloat(), bottom = (1.0 - y).toFloat(), confidence = confidence)

/**
 * Vision's observations of an `imageWidth × imageHeight` image as the faces the pipeline embeds:
 * the five points mapped by [VisionFaceLandmarks] (`ios-face-grouping.md` §10.3's *corners-mid*),
 * the largest [MAX_FACES_PER_PHOTO] kept.
 *
 * ⚠ What F1a, F1b and the desktop calibration (§10.8) measured, and nothing more (§14): no
 * confidence floor of our own (Vision returned 1.0 on all 86 library faces), and no de-duplication
 * of our own (Vision's boxes matched 86 of 86 with none doubled). A face whose regions cannot carry
 * the mapping is left out: it cannot be aligned. Changing any of this is a new pipeline version on
 * every Vision platform (`FaceModel.VISION_PIPELINE_VERSION`, `FaceModel.MAC_VISION_PIPELINE_VERSION`).
 */
fun facesOf(observations: List<VisionFaceObservation>, imageWidth: Int, imageHeight: Int): List<DetectedFace> =
    observations.mapNotNull { observation ->
        val regions: VisionFaceLandmarks.Regions = observation.regions ?: return@mapNotNull null
        val landmarks: FaceLandmarks = VisionFaceLandmarks.landmarks(regions, imageWidth, imageHeight) ?: return@mapNotNull null
        DetectedFace(observation.box, landmarks)
    }.sortedByDescending { (it.box.right - it.box.left) * (it.box.bottom - it.box.top) }
        .take(MAX_FACES_PER_PHOTO)

/**
 * [stored] shown as EXIF [orientation] (1–8) says: the displayed pixel at `(x, y)` is read from
 * the stored pixel this names. Pure, so its eight cases are tested.
 */
fun orient(stored: FaceImage, orientation: Int): FaceImage {
    val w: Int = stored.width
    val h: Int = stored.height
    val swaps: Boolean = orientation in 5..8
    val width: Int = if (swaps) h else w
    val height: Int = if (swaps) w else h
    val source: IntArray = stored.argb
    val out = IntArray(width * height)
    for (y in 0 until height) for (x in 0 until width) {
        val (sx, sy) = when (orientation) {
            2 -> (w - 1 - x) to y
            3 -> (w - 1 - x) to (h - 1 - y)
            4 -> x to (h - 1 - y)
            5 -> y to x
            6 -> y to (h - 1 - x)
            7 -> (w - 1 - y) to (h - 1 - x)
            8 -> (w - 1 - y) to x
            else -> x to y
        }
        out[y * width + x] = source[sy * w + sx]
    }
    return FaceImage(width, height, out)
}
