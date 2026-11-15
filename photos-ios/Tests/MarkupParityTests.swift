import CoreGraphics
import ImageIO
import TenshuSdk
import UniformTypeIdentifiers
import XCTest
@testable import PhotosIos

// Markup on iOS (`photos-editing.md` ED-4, §11.5): the Core Graphics drawer against the shared
// reference (the Compose twin is `MarkupRenderTest` in `:photos-shared-ui`), and the redaction proof
// through the shell's real save — a copy that is the same bytes whatever was under the box.
//
// ⚠ Hosted in the app, as `EditParityTests` is: the save runs Core Image, which has no context in a
// K/N simulator test process.
final class MarkupParityTests: XCTestCase {
    private let f = MarkupParityFixtures.shared
    private let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
    // Little-endian premultiplied-first: each pixel reads back as 0xAARRGGBB, the fixtures' layout.
    private let info = CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue

    private func kotlin(_ buf: [UInt32]) -> KotlinIntArray {
        let out = KotlinIntArray(size: Int32(buf.count))
        for (i, v) in buf.enumerated() { out.set(index: Int32(i), value: Int32(bitPattern: v)) }
        return out
    }

    private func swift(_ px: KotlinIntArray) -> [UInt32] {
        (0..<Int(px.size)).map { UInt32(bitPattern: px.get(index: Int32($0))) }
    }

    func testCoreGraphicsMatchesTheReference_withinTheStatedTolerance() {
        let w = Int(f.WIDTH)
        let h = Int(f.HEIGHT)
        var buf = swift(f.background())
        buf.withUnsafeMutableBytes { raw in
            let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8,
                                bytesPerRow: w * 4, space: srgb, bitmapInfo: info)!
            MarkupDrawing.flip(ctx, height: h)
            MarkupDrawing.draw(f.scene(), in: ctx)
        }
        let diff = f.difference(a: kotlin(buf), b: f.reference())
        let mean = (diff.first as! KotlinDouble).doubleValue
        let far = (diff.second as! KotlinDouble).doubleValue
        let report = "MarkupParityTests: mean \(String(format: "%.3f", mean)), far \(String(format: "%.3f", far * 100))%"
        print(report)
        let attachment = XCTAttachment(string: report)
        attachment.name = "core-graphics-markup-parity"
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertLessThanOrEqual(mean, f.MEAN_DIFF, report)
        XCTAssertLessThanOrEqual(far, f.FAR_FRACTION, report)
    }

    /// A fixture source as the shell meets a photograph: encoded bytes.
    private func png(_ px: KotlinIntArray) -> Foundation.Data {
        let w = Int(f.REDACT_W)
        let h = Int(f.REDACT_H)
        var buf = swift(px)
        let image = buf.withUnsafeMutableBytes { raw -> CGImage? in
            CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8,
                      bytesPerRow: w * 4, space: srgb, bitmapInfo: info)?.makeImage()
        }!
        let out = NSMutableData()
        let dest = CGImageDestinationCreateWithData(out, UTType.png.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(dest, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(dest))
        return out as Foundation.Data
    }

    private func source(_ seed: Int32, secret: Int32? = nil) -> Foundation.Data {
        png(f.redactSource(seed: seed, secret: secret.map { KotlinInt(int: $0) }))
    }

    func testARedactedCopy_isTheSameBytes_whateverWasUnderTheBox() throws {
        let recipe = f.redactRecipe
        let a = try XCTUnwrap(EditRendering.render(original: source(1), recipe: recipe))
        let b = try XCTUnwrap(EditRendering.render(original: source(1, secret: 2), recipe: recipe))
        let c = try XCTUnwrap(EditRendering.render(original: source(1, secret: 3), recipe: recipe))
        XCTAssertEqual(a, b, "the copy depends on what was under the box")
        XCTAssertEqual(a, c, "the copy depends on what was under the box")

        // ⚠ The test can fail: without the box, the same two sources save differently.
        let bare = EditRecipe(transform: recipe.transform, adjust: recipe.adjust, filter: recipe.filter, markup: MarkupEdit(items: []))
        let bareA = try XCTUnwrap(EditRendering.render(original: source(1), recipe: bare))
        let bareB = try XCTUnwrap(EditRendering.render(original: source(1, secret: 2), recipe: bare))
        XCTAssertNotEqual(bareA, bareB, "the two sources differ")
    }

    func testARedactedCopy_isBlackUnderTheBox() throws {
        let recipe = f.redactRecipe
        let jpeg = try XCTUnwrap(EditRendering.render(original: source(1), recipe: recipe))
        let src = try XCTUnwrap(CGImageSourceCreateWithData(jpeg as CFData, nil))
        let image = try XCTUnwrap(CGImageSourceCreateImageAtIndex(src, 0, nil))
        let plan = recipe.transform.plan(sourceWidth: f.REDACT_W, sourceHeight: f.REDACT_H, includeCrop: true)
        XCTAssertEqual(image.width, Int(plan.outputWidth))
        XCTAssertEqual(image.height, Int(plan.outputHeight))
        let w = image.width
        let h = image.height
        var buf = [UInt32](repeating: 0, count: w * h)
        buf.withUnsafeMutableBytes { raw in
            let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8,
                                bytesPerRow: w * 4, space: srgb, bitmapInfo: info)!
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        }
        var checked = 0
        for y in 0..<h {
            for x in 0..<w {
                let s = plan.unmap(x: Float(x) + 0.5, y: Float(y) + 0.5)
                let sx = (s.first as! KotlinFloat).floatValue
                let sy = (s.second as! KotlinFloat).floatValue
                guard f.deepInsideBox(sx: sx, sy: sy, margin: 4) else { continue }
                let p = buf[y * w + x]
                let worst = max((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF)
                XCTAssertLessThan(worst, 13, "(\(x),\(y)) is not black: \(String(p, radix: 16))")
                checked += 1
            }
        }
        XCTAssertGreaterThan(checked, 1000)
    }
}
