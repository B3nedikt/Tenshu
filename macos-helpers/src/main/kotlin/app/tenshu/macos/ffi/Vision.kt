package app.tenshu.macos.ffi

import java.lang.foreign.MemorySegment

/**
 * QR detection through the **Vision** framework.
 *
 * ⚠ **Vision, never `AVCaptureMetadataOutput`.** On iOS the metadata output decodes barcodes
 * for free; on macOS it does not — that asymmetry is the single hardest-won lesson in the
 * Swift helper (`docs/proposals/desktop-qr-scanning.md` §7), and re-learning it costs a day.
 * Vision is the only QR path on macOS. §13 P3.
 *
 * The work runs on whatever thread calls in — for the capture path that is the engine's
 * serial frame queue, which is also why detection is throttled rather than run on every
 * frame: `VNDetectBarcodesRequest` is real image analysis, and a QR code that isn't read this
 * frame is read on the next one.
 */
internal object Vision {

    /** `VNBarcodeSymbologyQR` — read from the framework, not reconstructed. */
    private val symbologyQr: MemorySegment
        get() = ObjC.frameworkConstant("Vision", "VNBarcodeSymbologyQR")

    /**
     * A request handler over a capture `CVPixelBuffer` — the production path. Vision reads the
     * buffer in place, so this must be called while the buffer is still valid (inside the
     * frame callback), never after it has been recycled.
     */
    fun pixelBufferHandler(pixelBuffer: MemorySegment): MemorySegment =
        handler("initWithCVPixelBuffer:options:", pixelBuffer)

    /**
     * A request handler over a `CIImage`.
     *
     * This exists so QR decoding can be **tested without a camera**: pair it with a
     * `CIQRCodeGenerator` image and the whole detect path runs unattended (§13.3 prescribes
     * exactly that test). Production uses [pixelBufferHandler].
     */
    fun ciImageHandler(ciImage: MemorySegment): MemorySegment =
        handler("initWithCIImage:options:", ciImage)

    private fun handler(selector: String, image: MemorySegment): MemorySegment {
        if (ObjC.isNull(image)) return MemorySegment.NULL
        val cls = ObjC.cls("VNImageRequestHandler")
        if (ObjC.isNull(cls)) return MemorySegment.NULL
        val options = ObjC.send(ObjC.cls("NSDictionary"), "dictionary")
        return ObjC.send(ObjC.send(cls, "alloc"), selector, image, options)
    }

    /**
     * Run QR detection over [requestHandler] and return the first payload found, or `null`.
     *
     * The caller owns [requestHandler] (it was `alloc`'d) and must [ObjC.release] it — this
     * function doesn't, because the handler is reusable for further requests.
     *
     * Restricted to the QR symbology on purpose: Vision will happily hunt for a dozen barcode
     * families, and every extra one is per-frame work spent looking for something a pairing
     * screen would reject anyway.
     */
    fun detectQrPayload(requestHandler: MemorySegment): String? {
        if (ObjC.isNull(requestHandler) || !ObjC.isAvailable) return null
        return ObjC.autoreleasePool {
            val request = ObjC.allocInit("VNDetectBarcodesRequest")
            if (ObjC.isNull(request)) return@autoreleasePool null
            try {
                val qr = symbologyQr
                if (!ObjC.isNull(qr)) {
                    val symbologies = ObjC.send(ObjC.cls("NSArray"), "arrayWithObject:", qr)
                    ObjC.sendVoid(request, "setSymbologies:", symbologies)
                }

                val requests = ObjC.send(ObjC.cls("NSArray"), "arrayWithObject:", request)
                // `performRequests:error:` returns NO on failure; a NULL error out-param is
                // fine because there is nothing actionable to do with a detection failure
                // beyond trying the next frame.
                if (!ObjC.sendBool(requestHandler, "performRequests:error:", requests, MemorySegment.NULL)) {
                    return@autoreleasePool null
                }

                firstPayload(ObjC.send(request, "results"))
            } finally {
                ObjC.release(request)
            }
        }
    }

    private fun firstPayload(results: MemorySegment): String? {
        if (ObjC.isNull(results)) return null
        val count = ObjC.sendLong(results, "count")
        for (index in 0 until count) {
            val observation = ObjC.sendWithLong(results, "objectAtIndex:", index)
            if (ObjC.isNull(observation)) continue
            val payload = ObjC.send(observation, "payloadStringValue")
            if (ObjC.isNull(payload)) continue
            val text = ObjC.kotlinString(payload)
            // A detected-but-empty payload is not a scan; keep looking rather than reporting
            // a successful decode of nothing.
            if (text.isNotEmpty()) return text
        }
        return null
    }
}
