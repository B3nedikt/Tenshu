// macOS image-decode helper — ImageIO bridge for formats Skia can't read.
//
// Compose Desktop decodes images through Skia (`org.jetbrains.skia.Image
// .makeFromEncoded`), and JetBrains' Skiko builds ship *without* the HEIF/HEVC
// codec — HEIC is patent-encumbered and Skia's `SkHeifDecoder` is effectively
// Android-platform-only. So every photo straight off an iPhone fails to decode
// on the Mac, even though macOS itself decodes HEIC perfectly well through
// ImageIO (that's what Preview.app uses).
//
// This helper is that ImageIO path, reachable from the JVM: it reads the
// encoded bytes on **stdin** and writes re-encoded bytes Skia understands to
// **stdout**. Nothing ever touches the disk — the bytes arriving here are
// decrypted user plaintext, and piping them keeps the threat model's A5
// (transient plaintext) surface unchanged. Do NOT rewrite this as a
// `sips`-style file conversion.
//
// Protocol
//   stdin   encoded image bytes (any format ImageIO can read)
//   stdout  PNG (source has alpha) or JPEG (it doesn't)
//   exit 0  success
//   exit 1  empty/unreadable stdin
//   exit 2  ImageIO could not decode the input
//   exit 3  re-encode failed
//   stderr  one-line diagnostic on any non-zero exit
//
// Kotlin side: `app.tenshu.ui.image.MacImageDecoder` (:ui-core desktopMain).
// Build + packaging mirror `qr-scan-helper` — see ui-core/build.gradle.kts and
// docs/proposals/desktop-qr-scanning.md §7–§8 for the resolve-from-bundle,
// fall-back-to-extracted-resource convention.

import Foundation
import CoreGraphics
import ImageIO

/// Cap on the decoded image's larger dimension.
///
/// Matches the Android decoder's `TARGET_MAX_DIMENSION`: 4096×4096 is ~64 MB at
/// 32-bit colour, plenty for the pinch-zoom viewer on any desktop display, and
/// it keeps a 48 MP phone panorama from turning into a several-hundred-MB
/// bitmap on the JVM heap. ImageIO never upscales, so smaller sources come back
/// at their own resolution.
private let maxPixelSize = 4096

private func fail(_ code: Int32, _ message: String) -> Never {
    FileHandle.standardError.write(Data("image-decode-helper: \(message)\n".utf8))
    exit(code)
}

let input = FileHandle.standardInput.readDataToEndOfFile()
if input.isEmpty {
    fail(1, "no input on stdin")
}

guard let source = CGImageSourceCreateWithData(input as CFData, nil) else {
    fail(2, "CGImageSourceCreateWithData returned nil")
}

// `CreateThumbnailAtIndex` with `FromImageAlways` is ImageIO's fast decode+scale
// path (it can decode straight to the target size rather than full-res-then-
// resample). `WithTransform` bakes in the EXIF orientation, which iPhone photos
// rely on and which the JVM side has no way to apply.
let options: [CFString: Any] = [
    kCGImageSourceCreateThumbnailFromImageAlways: true,
    kCGImageSourceCreateThumbnailWithTransform: true,
    kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
]

guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
    fail(2, "could not decode image (unsupported or corrupt)")
}

// Keep alpha where the source has it (screenshots, stickers); otherwise JPEG,
// which encodes an order of magnitude faster than PNG at these sizes. This is a
// display-only re-encode — the stored file is never touched.
let hasAlpha: Bool
switch image.alphaInfo {
case .first, .last, .premultipliedFirst, .premultipliedLast:
    hasAlpha = true
default:
    hasAlpha = false
}

let outputType: CFString = (hasAlpha ? "public.png" : "public.jpeg") as CFString
let output = NSMutableData()

guard let destination = CGImageDestinationCreateWithData(output as CFMutableData, outputType, 1, nil) else {
    fail(3, "CGImageDestinationCreateWithData returned nil")
}

CGImageDestinationAddImage(
    destination,
    image,
    [kCGImageDestinationLossyCompressionQuality: 0.92] as CFDictionary
)

guard CGImageDestinationFinalize(destination) else {
    fail(3, "CGImageDestinationFinalize failed")
}

FileHandle.standardOutput.write(output as Data)
exit(0)
