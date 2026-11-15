import CoreGraphics
import TenshuSdk
import UIKit

// Markup on iOS (`photos-editing.md` ED-4, §11) — the Core Graphics twin of Compose's `drawMarkup`.
//
// ⚠⚠ It draws the SHARED scene, `MarkupRender.scene` — where ink lands, how wide it is and what a
// redaction fills are decided in Kotlin, once. This file only strokes and fills what it is handed:
// the preview calls it inside the SwiftUI canvas, the save over the coloured picture, and the parity
// test (`PhotosIosTests/MarkupParityTests`) against the shared reference.

enum MarkupDrawing {
    private static let srgb = CGColorSpace(name: CGColorSpace.sRGB)!

    /// [ops] in output pixels. ⚠ [ctx] must be **y-down** (UIKit's convention) — the scene is.
    static func draw(_ ops: [MarkupOp], in ctx: CGContext) {
        for op in ops {
            switch onEnum(of: op) {
            case .strokeOp(let s):
                // ⚠ One path, its alpha in the colour: a highlighter crossing itself is one coverage,
                // as Compose draws it and the reference assumes.
                ctx.saveGState()
                ctx.addPath(path(s.points, close: false))
                ctx.setStrokeColor(color(s.argb, alpha: CGFloat(s.alpha)))
                ctx.setLineWidth(CGFloat(s.width))
                ctx.setLineCap(.round)
                ctx.setLineJoin(.round)
                ctx.strokePath()
                ctx.restoreGState()
            case .fillOp(let f):
                ctx.saveGState()
                ctx.addPath(path(f.polygon, close: true))
                ctx.setFillColor(color(f.argb, alpha: 1))
                ctx.fillPath()
                ctx.restoreGState()
            case .labelOp(let l):
                // One line of bold system text centred on the point — iOS's own font, as each
                // platform's label is (a copy is baked, ED-D2, so no device redraws another's).
                let font = UIFont.boldSystemFont(ofSize: CGFloat(l.size))
                let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor(cgColor: color(l.argb, alpha: 1))]
                let text = l.text as NSString
                let size = text.size(withAttributes: attrs)
                UIGraphicsPushContext(ctx)
                text.draw(at: CGPoint(x: CGFloat(l.x) - size.width / 2, y: CGFloat(l.y) - size.height / 2), withAttributes: attrs)
                UIGraphicsPopContext()
            }
        }
    }

    /// [picture] with [ops] drawn over it, at its own size — the save's last stage.
    static func over(_ picture: CGImage, _ ops: [MarkupOp]) -> CGImage? {
        guard let ctx = bitmap(picture.width, picture.height) else { return nil }
        // Drawn before the flip: a CGImage in a y-up context is upright.
        ctx.draw(picture, in: CGRect(x: 0, y: 0, width: picture.width, height: picture.height))
        flip(ctx, height: picture.height)
        draw(ops, in: ctx)
        return ctx.makeImage()
    }

    /// [picture] with [polygons] (source pixels, y down) filled black — the redaction's real work,
    /// done on the source before the transform reads it (`MarkupRender.redactionsInSource`).
    ///
    /// ⚠⚠ No anti-aliasing, and an outline of `SOURCE_FILL_PAD` px on top: a pixel the box only
    /// touches is destroyed too, never blended — so no later stage, the transform's filtered sampling
    /// least of all, reads an original pixel from under it.
    ///
    /// ⚠ In half-float extended-linear sRGB (`deepBitmap`), not the 8-bit sRGB the output uses: the
    /// source still has the colour stages ahead of it, and an 8-bit sRGB detour would clip a P3
    /// photo's gamut and band under strong sliders — only on saves that happen to hold a box.
    static func redacted(_ picture: CGImage, _ polygons: [KotlinFloatArray]) -> CGImage? {
        guard let ctx = deepBitmap(picture.width, picture.height) else { return nil }
        ctx.draw(picture, in: CGRect(x: 0, y: 0, width: picture.width, height: picture.height))
        flip(ctx, height: picture.height)
        ctx.setShouldAntialias(false)
        ctx.setFillColor(UIColor.black.cgColor)
        ctx.setStrokeColor(UIColor.black.cgColor)
        ctx.setLineWidth(CGFloat(MarkupRender.shared.SOURCE_FILL_PAD) * 2)
        ctx.setLineJoin(.miter)
        for p in polygons {
            ctx.addPath(path(p, close: true)); ctx.fillPath()
            ctx.addPath(path(p, close: true)); ctx.strokePath()
        }
        return ctx.makeImage()
    }

    /// An opaque 8-bit sRGB context, as the save's JPEG is.
    static func bitmap(_ width: Int, _ height: Int) -> CGContext? {
        CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: srgb,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )
    }

    /// The working space Core Image renders the source into for the redaction fill.
    static let deepSpace = CGColorSpace(name: CGColorSpace.extendedLinearSRGB)!

    static func deepBitmap(_ width: Int, _ height: Int) -> CGContext? {
        CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 16, bytesPerRow: 0, space: deepSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.floatComponents.rawValue
                | CGImageByteOrderInfo.order16Little.rawValue
        )
    }

    /// Core Graphics is y-up; the scene, UIKit and the plan are y-down.
    static func flip(_ ctx: CGContext, height: Int) {
        ctx.translateBy(x: 0, y: CGFloat(height))
        ctx.scaleBy(x: 1, y: -1)
    }

    private static func path(_ p: KotlinFloatArray, close: Bool) -> CGPath {
        let path = CGMutablePath()
        let n = Int(p.size) / 2
        guard n > 0 else { return path }
        path.move(to: CGPoint(x: CGFloat(p.get(index: 0)), y: CGFloat(p.get(index: 1))))
        for i in 1..<max(n, 1) {
            path.addLine(to: CGPoint(x: CGFloat(p.get(index: Int32(2 * i))), y: CGFloat(p.get(index: Int32(2 * i + 1)))))
        }
        if close { path.closeSubpath() }
        return path
    }

    private static func color(_ argb: Int32, alpha: CGFloat) -> CGColor {
        let v = UInt32(bitPattern: argb)
        let comps: [CGFloat] = [
            CGFloat((v >> 16) & 0xFF) / 255, CGFloat((v >> 8) & 0xFF) / 255, CGFloat(v & 0xFF) / 255, alpha,
        ]
        return CGColor(colorSpace: srgb, components: comps)!
    }
}
