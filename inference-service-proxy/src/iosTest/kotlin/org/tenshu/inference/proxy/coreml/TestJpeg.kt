package org.tenshu.inference.proxy.coreml

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataCreateMutable
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFNumberDoubleType
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGColorRenderingIntent
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGDataProviderCreateWithCFData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreate
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.kCGImageDestinationLossyCompressionQuality
import platform.ImageIO.kCGImagePropertyOrientation

/**
 * A JPEG written in the test, pixel by pixel, with an optional EXIF orientation tag.
 *
 * @param colour the **stored** pixel at (x, y) as `0xRRGGBB` — before any orientation is applied.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun testJpeg(width: Int, height: Int, orientation: Int? = null, colour: (x: Int, y: Int) -> Int): ByteArray = memScoped {
    val rgbx = ByteArray(width * height * 4)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val rgb: Int = colour(x, y)
            val at: Int = (y * width + x) * 4
            rgbx[at] = (rgb shr 16).toByte()
            rgbx[at + 1] = (rgb shr 8).toByte()
            rgbx[at + 2] = rgb.toByte()
            rgbx[at + 3] = 0xFF.toByte()
        }
    }
    val pixelData = rgbx.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret<UByteVar>(), rgbx.size.convert()) }
    val provider = CGDataProviderCreateWithCFData(pixelData)
    val colorSpace = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
    val image = CGImageCreate(
        width = width.convert(),
        height = height.convert(),
        bitsPerComponent = 8u,
        bitsPerPixel = 32u,
        bytesPerRow = (width * 4).convert(),
        space = colorSpace,
        bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
        provider = provider,
        decode = null,
        shouldInterpolate = false,
        intent = CGColorRenderingIntent.kCGRenderingIntentDefault,
    )

    val properties = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    val quality = alloc<DoubleVar>()
    quality.value = 1.0
    val qualityNumber = CFNumberCreate(null, kCFNumberDoubleType, quality.ptr)
    CFDictionaryAddValue(properties, kCGImageDestinationLossyCompressionQuality, qualityNumber)
    if (orientation != null) {
        val tag = alloc<IntVar>()
        tag.value = orientation
        val tagNumber = CFNumberCreate(null, kCFNumberIntType, tag.ptr)
        CFDictionaryAddValue(properties, kCGImagePropertyOrientation, tagNumber)
        CFRelease(tagNumber)
    }

    val encoded = CFDataCreateMutable(null, 0)
    val type = CFStringCreateWithCString(null, "public.jpeg", kCFStringEncodingUTF8)
    val destination = CGImageDestinationCreateWithData(encoded, type, 1u, null)
    CGImageDestinationAddImage(destination, image, properties)
    check(CGImageDestinationFinalize(destination)) { "ImageIO did not write the test JPEG" }
    val bytes: ByteArray = CFDataGetBytePtr(encoded)!!.readBytes(CFDataGetLength(encoded).toInt())

    CFRelease(destination)
    CFRelease(type)
    CFRelease(encoded)
    CFRelease(qualityNumber)
    CFRelease(properties)
    CGImageRelease(image)
    CGColorSpaceRelease(colorSpace)
    CGDataProviderRelease(provider)
    CFRelease(pixelData)
    bytes
}
