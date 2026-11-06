import SwiftUI
import UIKit

// MarkOutline — Tenshu cat mark, primary (outline). SwiftUI port of the Compose
// `MarkOutline` (ui-core/.../components/marks/Outline.kt), itself codified from
// docs/design/brand/marks/Outline.kt. Source units are 64×64; the whole canvas is
// scaled to `dimension`. Do not edit the path values here — round-trip through the
// brand folder, exactly as the Compose copy.
//
// `tint` inherits the surrounding label colour (≈ Compose LocalContentColor). The
// `surface` colour fills the head + ears (so whiskers visually tuck under at the rim)
// and punches the eye catchlights; default it to the grouped-list cell background,
// which is where the mark is used (the genesis "You're set up" screen).
//
// Note on the head: Compose draws it as an elliptical `arcTo` (M14 50 A18 22 0 0 1 50 50 Z).
// SwiftUI's `addArc` has a y-down / clockwise ambiguity, so the top half-ellipse is
// reproduced here with two kappa-approximated cubic Béziers (k = 0.5523) — visually
// identical to the arc, direction-unambiguous.
struct MarkOutline: View {
    var dimension: CGFloat = 64
    var stroke: CGFloat = 2.5
    var tint: Color = .primary
    var surface: Color = Color(uiColor: .secondarySystemGroupedBackground)

    var body: some View {
        Canvas { ctx, _ in
            // Work in source 64-space; scaling the context also scales stroke widths,
            // matching Compose's `sw = stroke * s`.
            let unit = dimension / 64
            ctx.scaleBy(x: unit, y: unit)

            let mainStroke = StrokeStyle(lineWidth: stroke, lineCap: .round, lineJoin: .round)

            // Head — top half-ellipse, centre (32,50), rx 18, ry 22. Two cubic
            // quarters (kappa) from the left rim up over the top to the right rim,
            // closed along the bottom. Fill with surface, then stroke with tint.
            let k: CGFloat = 0.5523
            let cx: CGFloat = 32, cy: CGFloat = 50, rx: CGFloat = 18, ry: CGFloat = 22
            var head = Path()
            head.move(to: CGPoint(x: cx - rx, y: cy))                 // (14,50) left rim
            head.addCurve(
                to: CGPoint(x: cx, y: cy - ry),                       // (32,28) top
                control1: CGPoint(x: cx - rx, y: cy - ry * k),
                control2: CGPoint(x: cx - rx * k, y: cy - ry))
            head.addCurve(
                to: CGPoint(x: cx + rx, y: cy),                       // (50,50) right rim
                control1: CGPoint(x: cx + rx * k, y: cy - ry),
                control2: CGPoint(x: cx + rx, y: cy - ry * k))
            head.closeSubpath()
            ctx.fill(head, with: .color(surface))
            ctx.stroke(head, with: .color(tint), style: mainStroke)

            // Triangle "pagoda-eave" ears (v7, ADR 0004).
            // EAR_L = "M 28.8 24.2 L 18 14.1 L 15.1 31.7 C 15.1 33.9 18.4 25.9 28.8 24.2 Z"
            // EAR_R = "M 35.1 23.9 C 38.9 20.3 42.6 17.1 45.8 13.8 L 48.7 31.4 C 48.7 33.6 45.4 25.5 35.1 23.9 Z"
            var ears = Path()
            ears.move(to: CGPoint(x: 28.8, y: 24.2))
            ears.addLine(to: CGPoint(x: 18, y: 14.1))
            ears.addLine(to: CGPoint(x: 15.1, y: 31.7))
            ears.addCurve(
                to: CGPoint(x: 28.8, y: 24.2),
                control1: CGPoint(x: 15.1, y: 33.9),
                control2: CGPoint(x: 18.4, y: 25.9))
            ears.closeSubpath()
            ears.move(to: CGPoint(x: 35.1, y: 23.9))
            ears.addCurve(
                to: CGPoint(x: 45.8, y: 13.8),
                control1: CGPoint(x: 38.9, y: 20.3),
                control2: CGPoint(x: 42.6, y: 17.1))
            ears.addLine(to: CGPoint(x: 48.7, y: 31.4))
            ears.addCurve(
                to: CGPoint(x: 35.1, y: 23.9),
                control1: CGPoint(x: 48.7, y: 33.6),
                control2: CGPoint(x: 45.4, y: 25.5))
            ears.closeSubpath()
            ctx.fill(ears, with: .color(surface))
            ctx.stroke(ears, with: .color(tint), style: mainStroke)

            // Whiskers — quadratic curves, thin round stroke.
            func curve(_ x1: CGFloat, _ y1: CGFloat, _ cxx: CGFloat, _ cyy: CGFloat, _ x2: CGFloat, _ y2: CGFloat) -> Path {
                var p = Path()
                p.move(to: CGPoint(x: x1, y: y1))
                p.addQuadCurve(to: CGPoint(x: x2, y: y2), control: CGPoint(x: cxx, y: cyy))
                return p
            }
            let whiskerStroke = StrokeStyle(lineWidth: stroke * 0.55, lineCap: .round, lineJoin: .round)
            // Left whiskers
            ctx.stroke(curve(20, 39, 13, 37, 4, 35), with: .color(tint), style: whiskerStroke)
            ctx.stroke(curve(20, 41, 12, 41, 3, 41), with: .color(tint), style: whiskerStroke)
            ctx.stroke(curve(20, 43, 13, 45, 4, 48), with: .color(tint), style: whiskerStroke)
            // Right whiskers
            ctx.stroke(curve(44, 39, 51, 37, 60, 35), with: .color(tint), style: whiskerStroke)
            ctx.stroke(curve(44, 41, 52, 41, 61, 41), with: .color(tint), style: whiskerStroke)
            ctx.stroke(curve(44, 43, 51, 45, 60, 48), with: .color(tint), style: whiskerStroke)

            // Mouth: M 27 43 Q 29 46 31 44 Q 32 43 33 44 Q 35 46 37 43
            var mouth = Path()
            mouth.move(to: CGPoint(x: 27, y: 43))
            mouth.addQuadCurve(to: CGPoint(x: 31, y: 44), control: CGPoint(x: 29, y: 46))
            mouth.addQuadCurve(to: CGPoint(x: 33, y: 44), control: CGPoint(x: 32, y: 43))
            mouth.addQuadCurve(to: CGPoint(x: 37, y: 43), control: CGPoint(x: 35, y: 46))
            ctx.stroke(
                mouth,
                with: .color(tint),
                style: StrokeStyle(lineWidth: stroke * 0.85, lineCap: .round, lineJoin: .round))

            // Eyes + catchlights.
            func dot(_ x: CGFloat, _ y: CGFloat, _ r: CGFloat, _ color: Color) {
                ctx.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)), with: .color(color))
            }
            dot(26.5, 36, 2.1, tint)
            dot(37.5, 36, 2.1, tint)
            dot(27.2, 35.3, 0.55, surface)
            dot(38.2, 35.3, 0.55, surface)
        }
        .frame(width: dimension, height: dimension)
    }
}
