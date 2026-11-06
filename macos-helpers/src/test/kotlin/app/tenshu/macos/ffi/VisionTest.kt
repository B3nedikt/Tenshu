package app.tenshu.macos.ffi

import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * QR decode, proven end to end **without a camera** (`desktop-qr-scanning.md` §13.3): render a
 * QR with `CIQRCodeGenerator`, hand the image to Vision, assert the payload comes back.
 *
 * That round trip is the whole risk of P3. The capture side is already covered; what's new
 * here is whether the Vision bridge — symbology constant, request configuration,
 * `performRequests:`, walking `results` — is wired correctly, and a generated image exercises
 * every one of those without hardware or permissions.
 *
 * ⚠ It also pins the framework choice: this passes through **Vision**, which on macOS is the
 * only QR path (`AVCaptureMetadataOutput` decodes barcodes on iOS but not here — §7).
 */
class VisionTest {

    private val mac = ObjC.isAvailable

    @Test
    fun decodesAQrCodeItJustGenerated() {
        if (!mac) return
        val payload = "tenshu://pair?code=P3-VISION"
        ObjC.autoreleasePool {
            val image = qrImage(payload)
            assertFalse(ObjC.isNull(image), "CIQRCodeGenerator must produce an image")
            val handler = Vision.ciImageHandler(image)
            assertFalse(ObjC.isNull(handler), "the Vision request handler must be constructible")
            try {
                assertEquals(payload, Vision.detectQrPayload(handler))
            } finally {
                ObjC.release(handler)
            }
        }
    }

    @Test
    fun decodesAUnicodePayload() {
        if (!mac) return
        // Pairing payloads are URLs today, but a contact invite can carry a petname — so the
        // path must survive non-ASCII rather than mangling it at the NSString boundary.
        val payload = "tenshu://invite?name=Ben%20✓&lang=日本語"
        ObjC.autoreleasePool {
            val handler = Vision.ciImageHandler(qrImage(payload))
            try {
                assertEquals(payload, Vision.detectQrPayload(handler))
            } finally {
                ObjC.release(handler)
            }
        }
    }

    @Test
    fun returnsNullForAnImageWithNoQrCode() {
        if (!mac) return
        ObjC.autoreleasePool {
            // A plain colour fill: a valid image with nothing to find. The interesting part is
            // that this is null rather than a throw or a phantom payload — on a live camera
            // this is the *common* case, once per frame until a code enters view.
            val filter = ObjC.send(ObjC.cls("CIFilter"), "filterWithName:", ObjC.nsString("CIConstantColorGenerator"))
            assertFalse(ObjC.isNull(filter), "CIConstantColorGenerator must exist")
            val image = ObjC.send(filter, "outputImage")
            val handler = Vision.ciImageHandler(image)
            try {
                assertNull(Vision.detectQrPayload(handler))
            } finally {
                ObjC.release(handler)
            }
        }
    }

    @Test
    fun toleratesANullHandlerInsteadOfThrowing() {
        // The capture path builds a handler per frame; a construction failure must degrade to
        // "no code this frame", never take down the frame callback.
        assertNull(Vision.detectQrPayload(MemorySegment.NULL))
    }

    /** Render [text] as a QR `CIImage` via `CIQRCodeGenerator`. */
    private fun qrImage(text: String): MemorySegment {
        val filter = ObjC.send(
            ObjC.cls("CIFilter"), "filterWithName:", ObjC.nsString("CIQRCodeGenerator"),
        )
        if (ObjC.isNull(filter)) return MemorySegment.NULL
        val message = ObjC.send(
            ObjC.nsString(text), "dataUsingEncoding:", MemorySegment.ofAddress(NS_UTF8),
        )
        ObjC.sendVoid(filter, "setValue:forKey:", message, ObjC.nsString("inputMessage"))
        return ObjC.send(filter, "outputImage")
    }

    /** `NSUTF8StringEncoding`. */
    private val NS_UTF8 = 4L
}
