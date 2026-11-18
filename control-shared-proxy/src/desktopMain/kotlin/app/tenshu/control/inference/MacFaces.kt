package app.tenshu.control.inference

import app.tenshu.macos.MacFaceVision
import java.io.File
import org.tenshu.inference.proxy.VisionFaceLandmarks
import org.tenshu.inference.proxy.onnx.FaceModel
import org.tenshu.inference.proxy.vision.FaceImage
import org.tenshu.inference.proxy.vision.FaceImageDecoder
import org.tenshu.inference.proxy.vision.VisionDetection
import org.tenshu.inference.proxy.vision.VisionFaceDetector
import org.tenshu.inference.proxy.vision.VisionFaceObservation
import org.tenshu.inference.proxy.vision.VisionRequestException
import org.tenshu.inference.proxy.vision.faceDecodeEdge
import org.tenshu.inference.proxy.vision.orient
import org.tenshu.inference.proxy.vision.visionFaceBox

/**
 * People on macOS desktop: the platform halves `VisionOnnxFaceBackend` is built from
 * (`ios-face-grouping.md` §10.8), over `:macos-helpers`' FFM bridge ([MacFaceVision]).
 *
 * ⚠⚠ macOS only, and decided at run time: one desktop build ships to macOS, Windows and Linux, and
 * only macOS has Vision. [runsHere] is the one test every caller asks.
 */
object MacFaces {

    /** Whether this process can run the macOS face pair: macOS, with ImageIO and Vision reachable. */
    val runsHere: Boolean by lazy { isMac() && runCatching { MacFaceVision.isAvailable }.getOrDefault(false) }

    /**
     * The running macOS build (`ProductBuildVersion`, e.g. `26B123`), stamped into every template
     * (FD9). Read from the system's own version file rather than a spawned `sw_vers`. `"unknown"`
     * when unreadable: still one stable stamp, so nothing re-indexes on every launch.
     */
    val osBuild: String by lazy { readOsBuild(File("/System/Library/CoreServices/SystemVersion.plist")) ?: "unknown" }

    internal fun readOsBuild(plist: File): String? = runCatching {
        Regex("<key>ProductBuildVersion</key>\\s*<string>([^<]+)</string>").find(plist.readText())?.groupValues?.get(1)?.trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun isMac(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    /** `VNDetectFaceLandmarksRequest` at the pinned revision, over exactly the decoded pixels. */
    object Detector : VisionFaceDetector {
        override fun detect(image: FaceImage): VisionDetection {
            val detection: MacFaceVision.Detection = try {
                MacFaceVision.detectFaces(image.width, image.height, image.argb, FaceModel.VISION_LANDMARKS_REVISION)
            } catch (failure: IllegalStateException) {
                throw VisionRequestException(failure.message ?: "Vision failed")
            }
            return VisionDetection(detection.revisionRun, detection.faces.map(::observationOf))
        }

        internal fun observationOf(face: MacFaceVision.Face): VisionFaceObservation = VisionFaceObservation(
            box = visionFaceBox(face.x, face.y, face.width, face.height, face.confidence),
            regions = face.regions?.let { regions ->
                VisionFaceLandmarks.Regions(
                    leftEye = regions["leftEye"] ?: return@let null,
                    rightEye = regions["rightEye"] ?: return@let null,
                    leftPupil = regions["leftPupil"] ?: return@let null,
                    rightPupil = regions["rightPupil"] ?: return@let null,
                    nose = regions["nose"] ?: return@let null,
                    noseCrest = regions["noseCrest"] ?: return@let null,
                    outerLips = regions["outerLips"] ?: return@let null,
                )
            },
        )
    }

    /**
     * The face path's decode over ImageIO, as `IosFaceImageDecoder` on iOS: bounded by Android's
     * power-of-two rule ([faceDecodeEdge]), drawn into sRGB, upright two ways — the bytes' own EXIF,
     * or a caller-named [orientation] applied over the stored pixels (a preview carries none).
     */
    object Decoder : FaceImageDecoder {
        override fun decodeUpright(bytes: ByteArray, orientation: Int?): FaceImage? {
            if (orientation != null && orientation !in 1..8) return null
            val pixels: MacFaceVision.Pixels = MacFaceVision.decodeUpright(bytes, applyExif = orientation == null, edgeFor = ::faceDecodeEdge) ?: return null
            val stored = FaceImage(pixels.width, pixels.height, pixels.argb)
            return if (orientation == null) stored else orient(stored, orientation)
        }
    }
}
