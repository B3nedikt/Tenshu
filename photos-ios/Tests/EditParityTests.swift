import CoreGraphics
import CoreImage
import TenshuSdk
import XCTest
@testable import PhotosIos

// ⚠⚠ ED-D8's golden-image test for the Core Image engine (`photos-editing.md` §10): every shared
// fixture recipe drawn by `EditColorPipeline` — the chain `EditRendering.colour` calls — through the
// shell's own `CIContext`, against the CPU reference, within the shared stated tolerance. The Skia
// twin is `EditShaderParityTest` in `:photos-shared-ui`.
//
// ⚠ Here, in the app's process, and not as a Kotlin iOS test: a K/N simulator test process has no
// Metal device and Core Image creates no context there at all.
final class EditParityTests: XCTestCase {
    private let fixtures = EditParityFixtures.shared
    private var width: Int { Int(fixtures.WIDTH) }
    private var height: Int { Int(fixtures.HEIGHT) }
    private let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
    private let info = CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue

    /// The fixture as the shell meets a photograph: a `CGImage` as a `CIImage`, upright.
    private func fixture() -> CIImage {
        let px = fixtures.image()
        var buf = (0..<Int(px.size)).map { UInt32(bitPattern: px.get(index: Int32($0))) }
        let image = buf.withUnsafeMutableBytes { raw -> CGImage? in
            let ctx = CGContext(data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                bytesPerRow: width * 4, space: srgb, bitmapInfo: info)
            return ctx?.makeImage()
        }
        return CIImage(cgImage: image!)
    }

    /// Read back as a save is: `createCGImage` on the shell's context.
    private func argb(_ image: CIImage) -> KotlinIntArray {
        let cg = EditRendering.context.createCGImage(image, from: CGRect(x: 0, y: 0, width: width, height: height))!
        var buf = [UInt32](repeating: 0, count: width * height)
        buf.withUnsafeMutableBytes { raw in
            let ctx = CGContext(data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                bytesPerRow: width * 4, space: srgb, bitmapInfo: info)!
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        let out = KotlinIntArray(size: Int32(buf.count))
        for (i, v) in buf.enumerated() { out.set(index: Int32(i), value: Int32(bitPattern: v)) }
        return out
    }

    func testTheFixtureRoundTripsThroughTheShellsContext() {
        let diff = fixtures.difference(a: fixtures.image(), b: argb(fixture()))
        XCTAssertLessThanOrEqual((diff.first as! KotlinInt).intValue, 1, "the sRGB ↔ linear round trip alone")
    }

    func testCoreImageMatchesTheReference_withinTheStatedTolerance() {
        var report = ""
        var failures: [String] = []
        for pair in fixtures.recipes {
            let name = pair.first! as String
            let stage = pair.second!
            guard let out = EditColorPipeline.shared.apply(image: fixture(), stage: stage, width: fixtures.WIDTH, height: fixtures.HEIGHT) else {
                failures.append("\(name): Core Image refused the chain"); continue
            }
            let diff = fixtures.difference(a: argb(out), b: fixtures.reference(stage: stage))
            let worst = (diff.first as! KotlinInt).intValue
            let mean = (diff.second as! KotlinDouble).doubleValue
            report += "\(name): max \(worst), mean \(String(format: "%.3f", mean))\n"
            if worst > Int(fixtures.MAX_DIFF) || mean > fixtures.MEAN_DIFF { failures.append("\(name) (max \(worst), mean \(mean))") }
        }
        print("EditParityTests\n\(report)")
        let attachment = XCTAttachment(string: report)
        attachment.name = "core-image-parity"
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertTrue(failures.isEmpty, "beyond tolerance: \(failures)\n\(report)")
    }
}
