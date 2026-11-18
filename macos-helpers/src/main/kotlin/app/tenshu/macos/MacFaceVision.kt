package app.tenshu.macos

import app.tenshu.macos.ffi.ObjC
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.concurrent.ConcurrentHashMap

/**
 * The macOS desktop face path's two native halves, in-process over FFM
 * (`docs/proposals/ios-face-grouping.md` §10.8): **ImageIO** decodes a photograph upright and
 * bounded, and **Vision**'s `VNDetectFaceLandmarksRequest` finds its faces at a pinned revision.
 *
 * The iOS backend (`VisionCoreMlFaceBackend`, Kotlin/Native) runs the same two calls; this is their
 * JVM twin, so the desktop proxy can embed what Vision finds with the ONNX model it already links.
 * Everything crossing this boundary is plain numbers: no ObjC pointer escapes, and the module keeps
 * depending on nothing but the Kotlin stdlib.
 *
 * ⚠ Every entry point is safe to call off macOS: [isAvailable] is false there, [decodeUpright]
 * answers `null` and [detectFaces] throws.
 */
object MacFaceVision {

    /** Whether ImageIO and Vision can be reached from this process. */
    val isAvailable: Boolean get() = ObjC.isAvailable && imageIo != null && coreGraphics != null && vision != null

    /** One decoded photograph: row-major packed ARGB (alpha 0xFF), exactly `width * height` pixels. */
    class Pixels(val width: Int, val height: Int, val argb: IntArray)

    /**
     * One `VNFaceObservation`, unflattened: [x], [y], [width], [height] are Vision's normalised
     * `boundingBox` (origin **bottom-left**), [regions] the named landmark regions as
     * `pointsInImageOfSize:` pixels (origin bottom-left, `x0, y0, x1, y1, …`) of the image Vision
     * ran on, or `null` when Vision gave no landmarks.
     */
    class Face(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
        val confidence: Float,
        val regions: Map<String, FloatArray>?,
    )

    /** What one request returned, and the revision Vision says it ran. */
    class Detection(val revisionRun: Int, val faces: List<Face>)

    /** The regions read off `VNFaceLandmarks2D`, by their Objective-C property names. */
    val REGIONS: List<String> = listOf("leftEye", "rightEye", "leftPupil", "rightPupil", "nose", "noseCrest", "outerLips")

    /**
     * [bytes] decoded by ImageIO's thumbnail route, as `IosFaceImageDecoder` does on iOS: the long
     * edge [edgeFor]`(storedWidth, storedHeight)` at most, drawn into sRGB.
     *
     * @param applyExif true: the bytes' own EXIF orientation is applied
     *   (`kCGImageSourceCreateThumbnailWithTransform`); false: the stored pixels, unrotated.
     * @return `null` for bytes ImageIO cannot read, or off macOS.
     */
    fun decodeUpright(bytes: ByteArray, applyExif: Boolean, edgeFor: (Int, Int) -> Int): Pixels? {
        if (bytes.isEmpty() || !isAvailable) return null
        return ObjC.autoreleasePool {
            Arena.ofConfined().use { arena ->
                val data = nsData(arena, bytes)
                if (ObjC.isNull(data)) return@use null
                val source = call(imageIo, "CGImageSourceCreateWithData", ptr, ptr, ptr).invokeWithArguments(data, MemorySegment.NULL) as MemorySegment
                if (ObjC.isNull(source)) return@use null
                try {
                    val (storedWidth, storedHeight) = storedSize(source) ?: return@use null
                    val options = ObjC.send(ObjC.cls("NSMutableDictionary"), "dictionary")
                    put(options, "kCGImageSourceThumbnailMaxPixelSize", ObjC.sendWithInt(ObjC.cls("NSNumber"), "numberWithInt:", edgeFor(storedWidth, storedHeight)))
                    // ⚠ `…FromImageAlways`: an embedded EXIF thumbnail is 160 px.
                    put(options, "kCGImageSourceCreateThumbnailFromImageAlways", nsBool(true))
                    put(options, "kCGImageSourceCreateThumbnailWithTransform", nsBool(applyExif))
                    val image = call(imageIo, "CGImageSourceCreateThumbnailAtIndex", ptr, ptr, i64, ptr).invokeWithArguments(source, 0L, options) as MemorySegment
                    if (ObjC.isNull(image)) return@use null
                    try {
                        draw(image)
                    } finally {
                        cfRelease(image)
                    }
                } finally {
                    cfRelease(source)
                }
            }
        }
    }

    /**
     * Every face Vision finds in the `width × height` [argb] image, asking for [revision].
     *
     * The pixels reach Vision as an sRGB `CGImage` of exactly these pixels, so Vision and the crop
     * read the same image: the route the calibration harness ran (`vision_detect.swift` over a
     * decoded PNG, §10.1).
     *
     * @throws IllegalStateException on a Vision error, or off macOS. ⚠⚠ Never an empty result for a
     *   failure: an empty detection is remembered against the photograph for good.
     */
    fun detectFaces(width: Int, height: Int, argb: IntArray, revision: Int): Detection {
        check(isAvailable) { "Vision is not reachable from this process" }
        require(width > 0 && height > 0 && argb.size == width * height) { "${argb.size} pixels is not a ${width}x$height image" }
        return ObjC.autoreleasePool {
            Arena.ofConfined().use { arena ->
                val image = cgImageOf(width, height, argb) ?: throw IllegalStateException("the pixels could not be wrapped for Vision")
                val request = ObjC.allocInit("VNDetectFaceLandmarksRequest")
                var handler = MemorySegment.NULL
                try {
                    check(!ObjC.isNull(request)) { "VNDetectFaceLandmarksRequest is unavailable" }
                    msgSend(FunctionDescriptor.ofVoid(ptr, ptr, i64)).invokeWithArguments(request, ObjC.sel("setRevision:"), revision.toLong())
                    handler = ObjC.send(ObjC.send(ObjC.cls("VNImageRequestHandler"), "alloc"), "initWithCGImage:options:", image, ObjC.send(ObjC.cls("NSDictionary"), "dictionary"))
                    check(!ObjC.isNull(handler)) { "VNImageRequestHandler did not initialise" }
                    val errorOut = arena.allocate(ptr)
                    errorOut.set(ptr, 0, MemorySegment.NULL)
                    val requests = ObjC.send(ObjC.cls("NSArray"), "arrayWithObject:", request)
                    if (!ObjC.sendBool(handler, "performRequests:error:", requests, errorOut)) {
                        val error = errorOut.get(ptr, 0)
                        val why = if (ObjC.isNull(error)) "no NSError reported" else ObjC.kotlinString(ObjC.send(error, "localizedDescription"))
                        throw IllegalStateException("Vision failed: $why")
                    }
                    Detection(revisionRun = ObjC.sendLong(request, "revision").toInt(), faces = observations(arena, ObjC.send(request, "results"), width.toDouble(), height.toDouble()))
                } finally {
                    ObjC.release(handler)
                    ObjC.release(request)
                    cfRelease(image)
                }
            }
        }
    }

    // ── Vision results ─────────────────────────────────────────────────────────────

    private fun observations(arena: Arena, results: MemorySegment, width: Double, height: Double): List<Face> {
        if (ObjC.isNull(results)) return emptyList()
        val count = ObjC.sendLong(results, "count")
        val faceClass = ObjC.cls("VNFaceObservation")
        return (0 until count).map { index ->
            val observation = ObjC.sendWithLong(results, "objectAtIndex:", index)
            check(!ObjC.isNull(observation) && isKindOf(observation, faceClass)) { "Vision returned something that is not a face observation" }
            val box = msgSendStruct(FunctionDescriptor.of(CG_RECT, ptr, ptr)).invokeWithArguments(arena, observation, ObjC.sel("boundingBox")) as MemorySegment
            val confidence = msgSend(FunctionDescriptor.of(f32, ptr, ptr)).invokeWithArguments(observation, ObjC.sel("confidence")) as Float
            Face(
                x = box.get(f64, 0),
                y = box.get(f64, 8),
                width = box.get(f64, 16),
                height = box.get(f64, 24),
                confidence = confidence,
                regions = regionsOf(arena, ObjC.send(observation, "landmarks"), width, height),
            )
        }
    }

    /** Every region in [REGIONS], or `null` when any is missing: the mapping needs all seven. */
    private fun regionsOf(arena: Arena, landmarks: MemorySegment, width: Double, height: Double): Map<String, FloatArray>? {
        if (ObjC.isNull(landmarks)) return null
        val size = arena.allocate(CG_SIZE)
        size.set(f64, 0, width)
        size.set(f64, 8, height)
        val inImage = msgSend(FunctionDescriptor.of(ptr, ptr, ptr, CG_SIZE))
        val out = LinkedHashMap<String, FloatArray>()
        for (name in REGIONS) {
            val region = ObjC.send(landmarks, name)
            if (ObjC.isNull(region)) return null
            val count = ObjC.sendLong(region, "pointCount").toInt()
            if (count <= 0) return null
            val points = inImage.invokeWithArguments(region, ObjC.sel("pointsInImageOfSize:"), size) as MemorySegment
            if (ObjC.isNull(points)) return null
            val read = points.reinterpret(count * 16L)
            out[name] = FloatArray(count * 2) { i -> read.get(f64, i * 8L).toFloat() }
        }
        return out
    }

    private fun isKindOf(obj: MemorySegment, cls: MemorySegment): Boolean = ObjC.sendBool(obj, "isKindOfClass:", cls)

    // ── Pixels in and out ──────────────────────────────────────────────────────────

    /** `kCGImageAlphaNoneSkipLast`: RGBX, 8 bits a channel, as `IosImageDecoder` draws into. */
    private const val RGBX: Int = 5
    private const val INTERPOLATION_HIGH: Int = 3

    private fun storedSize(source: MemorySegment): Pair<Int, Int>? {
        val properties = call(imageIo, "CGImageSourceCopyPropertiesAtIndex", ptr, ptr, i64, ptr).invokeWithArguments(source, 0L, MemorySegment.NULL) as MemorySegment
        if (ObjC.isNull(properties)) return null
        try {
            fun dimension(key: String): Int {
                val number = ObjC.send(properties, "objectForKey:", ObjC.frameworkConstant("ImageIO", key))
                return if (ObjC.isNull(number)) 0 else ObjC.sendLong(number, "integerValue").toInt()
            }
            val width = dimension("kCGImagePropertyPixelWidth")
            val height = dimension("kCGImagePropertyPixelHeight")
            return (width to height).takeIf { width > 0 && height > 0 }
        } finally {
            cfRelease(properties)
        }
    }

    /** [image] drawn at its own size into sRGB, as packed ARGB. */
    private fun draw(image: MemorySegment): Pixels? {
        val width = (call(coreGraphics, "CGImageGetWidth", i64, ptr).invokeWithArguments(image) as Long).toInt()
        val height = (call(coreGraphics, "CGImageGetHeight", i64, ptr).invokeWithArguments(image) as Long).toInt()
        if (width <= 0 || height <= 0) return null
        val context = rgbxContext(width, height) ?: return null
        try {
            call(coreGraphics, "CGContextSetInterpolationQuality", null, ptr, i32).invokeWithArguments(context, INTERPOLATION_HIGH)
            Arena.ofConfined().use { arena ->
                val rect = arena.allocate(CG_RECT)
                rect.set(f64, 16, width.toDouble())
                rect.set(f64, 24, height.toDouble())
                call(coreGraphics, "CGContextDrawImage", null, ptr, CG_RECT, ptr).invokeWithArguments(context, rect, image)
            }
            val data = call(coreGraphics, "CGBitmapContextGetData", ptr, ptr).invokeWithArguments(context) as MemorySegment
            if (ObjC.isNull(data)) return null
            val bytes = data.reinterpret(width.toLong() * height * 4).toArray(ValueLayout.JAVA_BYTE)
            val argb = IntArray(width * height) { i ->
                val red = bytes[4 * i].toInt() and 0xFF
                val green = bytes[4 * i + 1].toInt() and 0xFF
                val blue = bytes[4 * i + 2].toInt() and 0xFF
                (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
            return Pixels(width, height, argb)
        } finally {
            cfRelease(context)
        }
    }

    /** An sRGB `CGImage` of exactly [argb]; the caller releases it. */
    private fun cgImageOf(width: Int, height: Int, argb: IntArray): MemorySegment? {
        val context = rgbxContext(width, height) ?: return null
        try {
            val data = call(coreGraphics, "CGBitmapContextGetData", ptr, ptr).invokeWithArguments(context) as MemorySegment
            if (ObjC.isNull(data)) return null
            val bytes = ByteArray(width * height * 4)
            for (i in argb.indices) {
                val pixel = argb[i]
                bytes[4 * i] = (pixel shr 16).toByte()
                bytes[4 * i + 1] = (pixel shr 8).toByte()
                bytes[4 * i + 2] = pixel.toByte()
                bytes[4 * i + 3] = 0xFF.toByte()
            }
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, data.reinterpret(bytes.size.toLong()), 0, bytes.size.toLong())
            val image = call(coreGraphics, "CGBitmapContextCreateImage", ptr, ptr).invokeWithArguments(context) as MemorySegment
            return image.takeUnless { ObjC.isNull(it) }
        } finally {
            cfRelease(context)
        }
    }

    private fun rgbxContext(width: Int, height: Int): MemorySegment? {
        val colorSpace = call(coreGraphics, "CGColorSpaceCreateWithName", ptr, ptr).invokeWithArguments(ObjC.frameworkConstant("CoreGraphics", "kCGColorSpaceSRGB")) as MemorySegment
        if (ObjC.isNull(colorSpace)) return null
        try {
            val context = call(coreGraphics, "CGBitmapContextCreate", ptr, ptr, i64, i64, i64, i64, ptr, i32).invokeWithArguments(
                MemorySegment.NULL, width.toLong(), height.toLong(), 8L, width * 4L, colorSpace, RGBX,
            ) as MemorySegment
            return context.takeUnless { ObjC.isNull(it) }
        } finally {
            cfRelease(colorSpace)
        }
    }

    // ── Foundation helpers ─────────────────────────────────────────────────────────

    private fun nsData(arena: Arena, bytes: ByteArray): MemorySegment {
        val native = arena.allocate(bytes.size.toLong())
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, native, 0, bytes.size.toLong())
        return ObjC.sendWithPointerAndLong(ObjC.cls("NSData"), "dataWithBytes:length:", native, bytes.size.toLong())
    }

    private fun nsBool(value: Boolean): MemorySegment =
        msgSend(FunctionDescriptor.of(ptr, ptr, ptr, i8)).invokeWithArguments(ObjC.cls("NSNumber"), ObjC.sel("numberWithBool:"), if (value) 1.toByte() else 0.toByte()) as MemorySegment

    private fun put(dictionary: MemorySegment, imageIoKey: String, value: MemorySegment) {
        val key = ObjC.frameworkConstant("ImageIO", imageIoKey)
        check(!ObjC.isNull(key)) { "ImageIO has no $imageIoKey" }
        ObjC.sendVoid(dictionary, "setObject:forKey:", value, key)
    }

    private fun cfRelease(obj: MemorySegment) {
        if (!ObjC.isNull(obj)) call(coreFoundation, "CFRelease", null, ptr).invokeWithArguments(obj)
    }

    // ── Binding ────────────────────────────────────────────────────────────────────

    private val ptr = ValueLayout.ADDRESS
    private val i64 = ValueLayout.JAVA_LONG
    private val i32 = ValueLayout.JAVA_INT
    private val i8 = ValueLayout.JAVA_BYTE
    private val f32 = ValueLayout.JAVA_FLOAT
    private val f64 = ValueLayout.JAVA_DOUBLE

    private val CG_SIZE: MemoryLayout = MemoryLayout.structLayout(f64.withName("width"), f64.withName("height"))
    private val CG_RECT: MemoryLayout = MemoryLayout.structLayout(
        f64.withName("x"), f64.withName("y"), f64.withName("width"), f64.withName("height"),
    )

    private val imageIo: SymbolLookup? by lazy { ObjC.framework("ImageIO") }
    private val coreGraphics: SymbolLookup? by lazy { ObjC.framework("CoreGraphics") }
    private val coreFoundation: SymbolLookup? by lazy { ObjC.framework("CoreFoundation") }
    private val vision: SymbolLookup? by lazy { ObjC.framework("Vision") }

    private val handles = ConcurrentHashMap<String, MethodHandle>()

    /** A plain C function, bound once per symbol. [returns] `null` is void. */
    private fun call(lookup: SymbolLookup?, symbol: String, returns: MemoryLayout?, vararg args: MemoryLayout): MethodHandle =
        handles.getOrPut(symbol) {
            val address = lookup?.find(symbol)?.orElse(null) ?: throw IllegalStateException("$symbol is not reachable")
            val descriptor = if (returns == null) FunctionDescriptor.ofVoid(*args) else FunctionDescriptor.of(returns, *args)
            ObjC.linkerOrNull()?.downcallHandle(address, descriptor) ?: throw IllegalStateException("no native linker")
        }

    private fun msgSend(descriptor: FunctionDescriptor): MethodHandle =
        ObjC.msgSend(descriptor) ?: throw IllegalStateException("objc_msgSend is not reachable")

    private fun msgSendStruct(descriptor: FunctionDescriptor): MethodHandle =
        ObjC.msgSendReturningStruct(descriptor) ?: throw IllegalStateException("no struct-return objc_msgSend on this architecture")
}
