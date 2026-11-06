package org.tenshu.inference.proxy.coreml

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import org.tenshu.inference.proxy.onnx.ImageDecoder
import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetInterpolationQuality
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.CoreGraphics.kCGInterpolationHigh
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize

/**
 * What the iOS index feeds the model, as a number (`ios-on-device-inference.md` §3.2, *The runtime
 * fingerprint*).
 *
 * ⚠⚠ **Bump it by hand whenever [IosImageDecoder] — its resampler, its orientation handling, its
 * colour space — or [ImagePreprocessing] changes the tensor a photo becomes.** An app update can
 * change the input transform without touching the model archives, and the embedding store's stamp
 * is only the model id and version, so without this the store would keep old vectors beside new
 * ones made differently.
 */
const val IOS_PREPROCESSING_REVISION: Int = 1

/**
 * The long-edge cap of [decodeLongEdge], in model sides.
 *
 * ⚠ Chosen from a memory bound, not a look: with the short edge under `4 * size` the decoded RGBA
 * image is at most `16 * size × 4 * size × 4` bytes, 16.8 MB at side 256, against the ~88 MB a pass
 * was measured at (`ios-on-device-inference.md` §9.4). Uncapped, a 20:1 strip is 84 MB by itself.
 */
internal const val LONG_EDGE_CAP_SIDES: Int = 16

/**
 * The long edge ImageIO decodes a `width × height` image at, for a model of side [size].
 *
 * ⚠⚠ **The first two lines are `AndroidImageDecoder.kt:22-23`, and must stay them**: halve while
 * the short edge would still be at least `2 * size`. The decoded short edge is then in
 * `[2 * size, 4 * size)` for any image that large, and an image that is smaller is not scaled.
 * Bounding the long edge instead (`2 * size`, this decoder's first form) left a 6:1 panorama with
 * a short edge of 85 px, **upsampled** threefold before the squash.
 *
 * ⚠ The cap is this platform's own. It engages above an aspect ratio between 4:1 and 8:1,
 * depending on where the halving left the short edge, and the short edge stays at or above the
 * model's side up to **16:1**. Past 16:1 the short edge is upsampled: accepted, for a strip.
 */
internal fun decodeLongEdge(width: Int, height: Int, size: Int): Int {
    var sample = 1
    while (minOf(width, height) / (sample * 2) >= size * 2) sample *= 2
    return minOf(maxOf(width, height) / sample, LONG_EDGE_CAP_SIDES * size).coerceAtLeast(1)
}

/**
 * iOS's half of preprocessing, over ImageIO (§1.2) — the arithmetic that follows is
 * [ImagePreprocessing.preprocessPixels], shared with `AndroidImageDecoder` and `AwtImageDecoder`.
 *
 * The contract is [ImageDecoder]'s: packed ARGB, row-major, exactly `size * size` entries,
 * **squashed** to the square unless `centreCrop` asks for the centre (`Profile.SIGLIP` does not,
 * `ImagePreprocessing.kt:32-36`), and `null` for bytes that are not an image.
 *
 * ⚠⚠ **Bounded by the SHORT edge, as Android's decode is** ([decodeLongEdge]). The image is
 * decoded with its short edge between `2 * size` and `4 * size`, so the squash only ever
 * downsamples, on a panorama as on a 4:3 photo, and a 48 MP original never becomes a full-size
 * bitmap. ⚠ ImageIO takes its bound as a long edge, so the rule needs the image's dimensions first.
 *
 * ⚠ **And capped on the long edge, which Android's is not**: [LONG_EDGE_CAP_SIDES] sides, so one
 * extreme panorama cannot cost what a hundred photos do.
 *
 * ⭐ **Upright, which Android and AWT are not.** `kCGImageSourceCreateThumbnailWithTransform`
 * applies the EXIF orientation, so a portrait photo reaches the model the way it is displayed. The
 * vectors therefore differ from another platform's for the same file, which is allowed: they are
 * device-local (`ImagePreprocessing.kt:51-61`).
 *
 * ⚠ Drawn into **sRGB**. An iPhone photo is Display P3, and the same bytes read as P3 numbers are
 * a different picture from the one the model was trained on.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosImageDecoder : ImageDecoder {

    override fun decode(bytes: ByteArray, size: Int, centreCrop: Boolean): IntArray? {
        if (bytes.isEmpty() || size <= 0) return null
        val argb = IntArray(size * size)
        return argb.takeIf { decodeInto(bytes, size, centreCrop, it) }
    }

    override fun decodeInto(bytes: ByteArray, size: Int, centreCrop: Boolean, target: IntArray): Boolean {
        if (bytes.isEmpty() || size <= 0) return false
        require(target.size == size * size) { "expected a buffer of ${size * size} pixels, got ${target.size}" }
        // ⚠ An index pass decodes in a loop on a thread with no run loop.
        return autoreleasepool {
            val thumbnail: CGImageRef = thumbnailOf(bytes, size) ?: return@autoreleasepool false
            try {
                val source: CGImageRef = if (centreCrop) centreSquareOf(thumbnail) ?: return@autoreleasepool false else thumbnail
                try {
                    squash(source, size, target)
                } finally {
                    if (source != thumbnail) CGImageRelease(source)
                }
            } finally {
                CGImageRelease(thumbnail)
            }
        }
    }

    /** The decoded image's width and height before the squash, for the tests of the bound. */
    internal fun decodedDimensions(bytes: ByteArray, size: Int): Pair<Int, Int>? = autoreleasepool {
        val thumbnail: CGImageRef = thumbnailOf(bytes, size) ?: return@autoreleasepool null
        try {
            CGImageGetWidth(thumbnail).toInt() to CGImageGetHeight(thumbnail).toInt()
        } finally {
            CGImageRelease(thumbnail)
        }
    }

    private fun thumbnailOf(bytes: ByteArray, size: Int): CGImageRef? = memScoped {
        val data = bytes.usePinned { pinned ->
            CFDataCreate(null, pinned.addressOf(0).reinterpret<UByteVar>(), bytes.size.convert())
        } ?: return@memScoped null
        val source = CGImageSourceCreateWithData(data, null)
        CFRelease(data)
        if (source == null) return@memScoped null

        // ⚠ A source that reports no dimensions is decoded at the cap: still bounded, and the
        // thumbnail call below is what decides whether it is an image at all.
        val maxPixelSize: Int = pixelDimensionsOf(source)
            ?.let { (width, height) -> decodeLongEdge(width, height, size) }
            ?: (LONG_EDGE_CAP_SIDES * size)

        val limit = alloc<IntVar>()
        limit.value = maxPixelSize
        val limitNumber = CFNumberCreate(null, kCFNumberIntType, limit.ptr)
        val options = CFDictionaryCreateMutable(
            null,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionaryAddValue(options, kCGImageSourceThumbnailMaxPixelSize, limitNumber)
        // ⚠ `…FromImageAlways`: an embedded EXIF thumbnail is usually 160 px, smaller than the model's input.
        CFDictionaryAddValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
        CFDictionaryAddValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
        try {
            // ⚠ Bytes that are not an image still produce a source; it is this call that returns null.
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

    private fun centreSquareOf(image: CGImageRef): CGImageRef? {
        val width: Int = CGImageGetWidth(image).toInt()
        val height: Int = CGImageGetHeight(image).toInt()
        val side: Int = minOf(width, height)
        return CGImageCreateWithImageInRect(
            image,
            CGRectMake(((width - side) / 2).toDouble(), ((height - side) / 2).toDouble(), side.toDouble(), side.toDouble()),
        )
    }

    /** Draws [image] over the whole `size × size` square: both axes scaled, nothing cut off. */
    private fun squash(image: CGImageRef, size: Int, argb: IntArray): Boolean {
        val colorSpace = CGColorSpaceCreateWithName(kCGColorSpaceSRGB) ?: return false
        // RGBX, 8 bits a channel. ⚠ `kCGImageAlphaNoneSkipLast` and not a premultiplied format: preprocessing
        // reads the colour channels alone, and premultiplying would darken a translucent pixel.
        val context = CGBitmapContextCreate(
            data = null,
            width = size.convert(),
            height = size.convert(),
            bitsPerComponent = 8u,
            bytesPerRow = (size * 4).convert(),
            space = colorSpace,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
        )
        CGColorSpaceRelease(colorSpace)
        if (context == null) return false
        try {
            CGContextSetInterpolationQuality(context, kCGInterpolationHigh)
            CGContextDrawImage(context, CGRectMake(0.0, 0.0, size.toDouble(), size.toDouble()), image)
            val pixels: CPointer<ByteVar> = CGBitmapContextGetData(context)?.reinterpret() ?: return false
            for (i in argb.indices) {
                val red: Int = pixels[4 * i].toInt() and 0xFF
                val green: Int = pixels[4 * i + 1].toInt() and 0xFF
                val blue: Int = pixels[4 * i + 2].toInt() and 0xFF
                argb[i] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
            return true
        } finally {
            CGContextRelease(context)
        }
    }
}
