package app.tenshu.macos.camera

import app.tenshu.macos.ffi.BgraFrame
import app.tenshu.macos.ffi.CoreVideo
import app.tenshu.macos.ffi.ObjC
import app.tenshu.macos.ffi.Vision
import java.util.concurrent.atomic.AtomicLong
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap

/**
 * A wired capture graph: session + output + delegate + queue.
 *
 * Created **not running** — [start] is what activates the camera. Everything the graph owns
 * is released by [close], which is also what unregisters the frame callback; after that the
 * delegate can still be messaged by an in-flight frame, and finding no callback it does
 * nothing. That ordering matters more than it looks: AVFoundation may deliver a frame
 * *concurrently* with teardown, and a callback removed first means the late frame is a no-op
 * rather than a use-after-free.
 */
class MacCameraSession internal constructor(
    private val session: MemorySegment,
    private val output: MemorySegment,
    private val delegate: MemorySegment,
    private val queue: MemorySegment,
) {

    @Volatile
    private var closed = false

    /** Start capture. Idempotent; lights the camera indicator. */
    fun start() {
        if (closed) return
        ObjC.sendVoid(session, "startRunning")
    }

    /** Whether the session is currently capturing. */
    fun isRunning(): Boolean = !closed && ObjC.sendBool(session, "isRunning")

    /**
     * Stop capture and release everything. Idempotent, and safe to call from any thread —
     * including from inside a frame callback.
     */
    fun close() {
        if (closed) return
        closed = true
        FrameDelegate.unregister(delegate)
        ObjC.sendVoid(session, "stopRunning")
        // Drop the delegate before releasing the output, so nothing is delivered into a
        // half-released graph.
        ObjC.sendVoid(output, "setSampleBufferDelegate:queue:", MemorySegment.NULL, MemorySegment.NULL)
        ObjC.release(delegate)
        ObjC.release(output)
        ObjC.release(session)
        ObjC.release(queue)
    }
}

/**
 * The runtime-registered `AVCaptureVideoDataOutputSampleBufferDelegate`.
 *
 * The ObjC class is registered **once per process** (the runtime rejects a second
 * `allocateClassPair` for a live name), so the per-session Kotlin callback can't live on the
 * class. It's keyed by the delegate *instance* address instead — which also means several
 * sessions can run at once without stepping on each other, rather than the last one winning.
 */
internal object FrameDelegate {

    private const val CLASS_NAME = "TenshuCaptureDelegate"
    private const val SELECTOR = "captureOutput:didOutputSampleBuffer:fromConnection:"

    /** `v@:@@@` — void, (self, _cmd, output, sampleBuffer, connection). */
    private const val TYPES = "v@:@@@"

    /**
     * How often Vision runs, at most. `VNDetectBarcodesRequest` is real image analysis and the
     * camera delivers ~30 fps; a QR code missed this frame is read on the next one, so
     * detecting ~10×/s costs a third of the work and no perceptible latency. The preview
     * itself is never throttled — that would be visible.
     */
    private val QR_DETECT_INTERVAL_NANOS = 100_000_000L

    /** Per-delegate-instance state: where frames go, and when detection last ran. */
    private class Sink(
        val onFrame: (BgraFrame) -> Unit,
        val onQrPayload: ((String) -> Unit)?,
    ) {
        val lastDetectNanos = AtomicLong(0)
    }

    private val callbacks = ConcurrentHashMap<Long, Sink>()

    private val registeredClass: MemorySegment by lazy {
        ObjC.registerClass(
            name = CLASS_NAME,
            superclassName = "NSObject",
            methods3 = mapOf(
                SELECTOR to (TYPES to ObjC.Method3 { self, _, _, sampleBuffer, _ ->
                    // A frame arriving after close() finds no sink and is dropped.
                    val sink = callbacks[self.address()] ?: return@Method3
                    val pixelBuffer = CoreVideo.imageBufferOf(sampleBuffer) ?: return@Method3

                    // Preview first: it is what the user sees, and detection can afford to
                    // wait a frame. Both read the same pixel buffer, unwrapped once.
                    CoreVideo.readBgra(pixelBuffer)?.let(sink.onFrame)
                    detectQr(sink, pixelBuffer)
                }),
            ),
        )
    }

    /**
     * Run Vision over [pixelBuffer], at most once per [QR_DETECT_INTERVAL_NANOS].
     *
     * ⚠ Never throws out of here: this runs on AVFoundation's delivery queue, where an escaping
     * exception would take down capture rather than skip a frame.
     */
    private fun detectQr(sink: Sink, pixelBuffer: MemorySegment) {
        val onPayload = sink.onQrPayload ?: return
        val now = System.nanoTime()
        val last = sink.lastDetectNanos.get()
        if (now - last < QR_DETECT_INTERVAL_NANOS) return
        if (!sink.lastDetectNanos.compareAndSet(last, now)) return

        runCatching {
            val handler = Vision.pixelBufferHandler(pixelBuffer)
            if (ObjC.isNull(handler)) return
            try {
                Vision.detectQrPayload(handler)?.let(onPayload)
            } finally {
                ObjC.release(handler)
            }
        }
    }

    /**
     * A fresh delegate instance bound to [onFrame] (and optionally QR detection); the caller
     * owns it and releases it via `close()`.
     */
    fun instance(
        onFrame: (BgraFrame) -> Unit,
        onQrPayload: ((String) -> Unit)? = null,
    ): MemorySegment {
        val cls = registeredClass
        if (ObjC.isNull(cls)) return MemorySegment.NULL
        val instance = ObjC.send(ObjC.send(cls, "alloc"), "init")
        if (ObjC.isNull(instance)) return MemorySegment.NULL
        callbacks[instance.address()] = Sink(onFrame, onQrPayload)
        return instance
    }

    fun unregister(instance: MemorySegment) {
        callbacks.remove(instance.address())
    }
}
