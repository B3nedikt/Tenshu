package app.tenshu.macos.ffi

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A serial dispatch queue is what keeps the P2 frame path off the main thread
 * (`desktop-qr-scanning.md` §13 P1) — `AVCaptureVideoDataOutput` delivers on the queue it
 * is given, so with our own queue nothing depends on the AWT `CFRunLoop` being pumped.
 * Creating one touches no capability and prompts for nothing.
 */
class DispatchTest {

    @Test
    fun createsAndReleasesASerialQueue() {
        if (!ObjC.isAvailable) return
        val queue = Dispatch.serialQueue("app.tenshu.test.queue")
        assertFalse(ObjC.isNull(queue), "dispatch_queue_create must return a queue")
        // Dispatch objects are ObjC objects on modern macOS, so the ordinary release balances it.
        ObjC.release(queue)
    }

    @Test
    fun reportsUnavailableOffMac() {
        // The inverse of the guard above: where ObjC is unreachable the queue is null rather
        // than an exception, so a caller can branch instead of crashing.
        if (ObjC.isAvailable) return
        assertTrue(ObjC.isNull(Dispatch.serialQueue("app.tenshu.test.queue")))
    }
}
