package app.tenshu.macos.camera

import app.tenshu.macos.ffi.BgraFrame
import app.tenshu.macos.ffi.Dispatch
import app.tenshu.macos.ffi.ObjC
import java.lang.foreign.MemorySegment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Camera authorization as macOS reports it, plus the two "can't ask" cases. */
enum class CameraAuthorization {
    /** Granted — a session can be opened with no dialog. */
    Authorized,

    /** Never asked. Asking is what can kill a *helper*-based host (§9); in-process it's safe. */
    NotDetermined,

    /** Refused, or restricted by policy. Only Settings can change it. */
    Denied,

    /** Authorized in principle, but the machine exposes no video device. */
    NoCamera,

    /** Not macOS, or Objective-C/FFM is unreachable here. */
    Unavailable,
}

/**
 * In-process camera capture through AVFoundation, driven from the JVM over FFM.
 *
 * **The point of doing this in-process** is `docs/proposals/desktop-qr-scanning.md` §9: when
 * a *spawned helper* touches the camera, macOS holds the JVM responsible and SIGKILLs it on
 * the first grant. When the JVM itself opens the session it is both the accessor and the
 * responsible TCC subject — the ordinary native-app case, which doesn't crash. This class is
 * the mechanism that lets the JVM be that accessor. §13 P2.
 *
 * Deliberately **Compose-free**: it lives in `:macos-helpers` (the FFM-capable, JDK-25
 * module) and speaks only in [BgraFrame]s, so `:ui-core` can adapt it to `ScanEvent`
 * without ever seeing `java.lang.foreign`. §13.6.
 *
 * Frames arrive on a private serial [Dispatch] queue — never the AWT thread — so nothing
 * here depends on a `CFRunLoop` being pumped.
 */
object MacCamera {

    private const val MEDIA_TYPE_VIDEO_SYMBOL = "AVMediaTypeVideo"
    private const val DELEGATE_CLASS = "TenshuCaptureDelegate"

    /** `kCVPixelFormatType_32BGRA` — 'BGRA' as a FourCC. */
    private const val PIXEL_FORMAT_32BGRA = 1111970369

    private val mediaTypeVideo: MemorySegment
        get() = ObjC.frameworkConstant("AVFoundation", MEDIA_TYPE_VIDEO_SYMBOL)

    /**
     * Read the authorization status **without opening a session**, so this can never
     * activate the camera or provoke a prompt. Safe to call at any time, which is what
     * makes the pre-grant explainer possible (§9).
     */
    fun authorizationStatus(): CameraAuthorization {
        if (!ObjC.isAvailable) return CameraAuthorization.Unavailable
        val deviceClass = ObjC.cls("AVCaptureDevice")
        if (ObjC.isNull(deviceClass)) return CameraAuthorization.Unavailable

        return when (ObjC.sendLong(deviceClass, "authorizationStatusForMediaType:", mediaTypeVideo)) {
            AUTH_AUTHORIZED -> if (hasVideoDevice()) {
                CameraAuthorization.Authorized
            } else {
                CameraAuthorization.NoCamera
            }
            AUTH_NOT_DETERMINED -> CameraAuthorization.NotDetermined
            // Restricted (policy) is indistinguishable from Denied to us: neither can be
            // resolved by asking, and both send the user to Settings.
            else -> CameraAuthorization.Denied
        }
    }

    private fun hasVideoDevice(): Boolean = ObjC.autoreleasePool {
        val device = ObjC.send(
            ObjC.cls("AVCaptureDevice"), "defaultDeviceWithMediaType:", mediaTypeVideo,
        )
        !ObjC.isNull(device)
    }

    /**
     * Ask for camera access, blocking until the user answers (or [timeoutSeconds] elapses).
     *
     * ⚠ **This is the call that used to kill the app** when a spawned helper made it. Made
     * in-process it is the ordinary path, and the spike confirmed the process stays alive
     * *through* the grant (§9 "Spike result"). Returns immediately when already authorized —
     * no dialog — which is why callers should check [authorizationStatus] first and only
     * reach here on [CameraAuthorization.NotDetermined].
     *
     * The completion fires on an internal queue, so this parks a latch rather than pumping
     * any run loop.
     */
    fun requestAccess(timeoutSeconds: Long = 120): Boolean {
        if (!ObjC.isAvailable) return false
        val latch = CountDownLatch(1)
        val granted = AtomicBoolean(false)
        val block = ObjC.boolBlock { _, value ->
            granted.set(value.toInt() != 0)
            latch.countDown()
        }
        if (ObjC.isNull(block)) return false

        ObjC.sendVoid(
            ObjC.cls("AVCaptureDevice"),
            "requestAccessForMediaType:completionHandler:",
            mediaTypeVideo,
            block,
        )
        return latch.await(timeoutSeconds, TimeUnit.SECONDS) && granted.get()
    }

    /**
     * Build a capture session delivering BGRA frames to [onFrame].
     *
     * Returns a session that is **wired but not running** — [MacCameraSession.start] is what
     * activates the camera (and lights the indicator). The split is deliberate: it keeps the
     * whole graph — device, input, output, delegate, queue — constructible and therefore
     * *testable* without ever turning the camera on.
     *
     * Pass [onQrPayload] to also run **Vision** QR detection over each (throttled) frame —
     * the same pixel buffer, no second capture path. Omit it for a preview-only session.
     *
     * Returns `null` when the camera can't be opened at all (no device, no permission,
     * device in use by another app).
     */
    fun createSession(
        onFrame: (BgraFrame) -> Unit,
        onQrPayload: ((String) -> Unit)? = null,
    ): MacCameraSession? {
        if (!ObjC.isAvailable) return null
        return ObjC.autoreleasePool {
            val device = ObjC.send(
                ObjC.cls("AVCaptureDevice"), "defaultDeviceWithMediaType:", mediaTypeVideo,
            )
            if (ObjC.isNull(device)) return@autoreleasePool null

            val input = ObjC.send(
                ObjC.cls("AVCaptureDeviceInput"),
                "deviceInputWithDevice:error:",
                device,
                MemorySegment.NULL,
            )
            if (ObjC.isNull(input)) return@autoreleasePool null

            val session = ObjC.allocInit("AVCaptureSession")
            if (ObjC.isNull(session)) return@autoreleasePool null

            if (!ObjC.sendBool(session, "canAddInput:", input)) {
                ObjC.release(session)
                return@autoreleasePool null
            }
            ObjC.sendVoid(session, "addInput:", input)

            val output = ObjC.allocInit("AVCaptureVideoDataOutput")
            // Dropping late frames is right for scanning: a QR code is re-readable on the
            // next frame, and a backlog would only add latency to the decode we care about.
            ObjC.sendVoidWithBool(output, "setAlwaysDiscardsLateVideoFrames:", true)
            configureBgra(output)

            if (!ObjC.sendBool(session, "canAddOutput:", output)) {
                ObjC.release(output)
                ObjC.release(session)
                return@autoreleasePool null
            }
            ObjC.sendVoid(session, "addOutput:", output)

            val queue = Dispatch.serialQueue("app.tenshu.camera")
            val delegate = FrameDelegate.instance(onFrame, onQrPayload)
            if (ObjC.isNull(delegate) || ObjC.isNull(queue)) {
                ObjC.release(output)
                ObjC.release(session)
                return@autoreleasePool null
            }
            ObjC.sendVoid(output, "setSampleBufferDelegate:queue:", delegate, queue)

            MacCameraSession(session, output, delegate, queue)
        }
    }

    /**
     * Ask the output for BGRA rather than the device's native format, so the frame path
     * hands us bytes Skia can raster directly — no JPEG round-trip, which is what the Swift
     * helper had to do to get frames across a pipe (D2).
     */
    private fun configureBgra(output: MemorySegment) {
        val key = ObjC.frameworkConstant("CoreVideo", "kCVPixelBufferPixelFormatTypeKey")
        if (ObjC.isNull(key)) return
        val value = ObjC.sendWithInt(ObjC.cls("NSNumber"), "numberWithInt:", PIXEL_FORMAT_32BGRA)
        val settings = ObjC.send(
            ObjC.cls("NSDictionary"), "dictionaryWithObject:forKey:", value, key,
        )
        if (!ObjC.isNull(settings)) ObjC.sendVoid(output, "setVideoSettings:", settings)
    }

    private const val AUTH_NOT_DETERMINED = 0L
    private const val AUTH_AUTHORIZED = 3L
}
