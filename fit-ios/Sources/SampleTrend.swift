import SwiftUI
import TenshuSdk

// A min–max-scaled trend line for a Body/Vitals sample series (§12 B2) — the iOS twin of the Compose
// `SampleTrend`. Geometry is pre-computed by `ChartMath.minMaxPolyline` (y = 0 at the top), so this is
// a pure renderer. Distinct from `TrendBars`: a weight trend needs a value-ranged axis, not the
// zero-anchored bars that would render 73–75 kg as near-identical full columns.
struct SampleTrend: View {
    let points: [NormalizedPoint]
    var tint: Color = .orange

    private let inset: CGFloat = 4

    var body: some View {
        GeometryReader { geo in
            let cg = mapped(into: geo.size)
            if cg.count > 1 {
                fill(cg, height: geo.size.height)
                    .fill(LinearGradient(colors: [tint.opacity(0.20), tint.opacity(0)],
                                         startPoint: .top, endPoint: .bottom))
                line(cg)
                    .stroke(tint, style: StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round))
                ForEach(Array(cg.enumerated()), id: \.offset) { _, pt in
                    Circle().fill(tint).frame(width: 5.2, height: 5.2).position(pt)
                }
            }
        }
    }

    private func mapped(into size: CGSize) -> [CGPoint] {
        points.map { p in
            CGPoint(x: CGFloat(p.x) * size.width,
                    y: inset + CGFloat(p.y) * (size.height - 2 * inset))
        }
    }

    private func line(_ cg: [CGPoint]) -> Path {
        Path { path in
            path.move(to: cg[0])
            cg.dropFirst().forEach { path.addLine(to: $0) }
        }
    }

    private func fill(_ cg: [CGPoint], height: CGFloat) -> Path {
        Path { path in
            path.move(to: cg[0])
            cg.dropFirst().forEach { path.addLine(to: $0) }
            path.addLine(to: CGPoint(x: cg.last?.x ?? 0, y: height))
            path.addLine(to: CGPoint(x: cg.first?.x ?? 0, y: height))
            path.closeSubpath()
        }
    }
}
