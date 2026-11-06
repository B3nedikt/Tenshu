import SwiftUI
import TenshuSdk

// The normalized route sketch — the Swift twin of `:ui-map`'s `RoutePolyline`, and the same fallback
// shape the desktop renders (M5's SwiftUI sibling, timeline-movement-capture.md §7 item 5).
//
// ⚠ This was the *hero* until P3; it is now the **fallback** behind `TimelineRouteMapCard`, drawn
// when there is no tile archive to put the route on. Deliberately still NOT MapKit: platform map
// tiles are Apple-served, so drawing the route over them would disclose the route's region to a third
// party on every detail open (threat-model A35). The real map that replaced it is MapLibre over
// device-local PMTiles — the constraint was never "no map", it was "no egress".

// The polyline itself: fitted to the surface, round caps, endpoint dots so start/end read
// clearly (mirrors `RoutePolyline`'s stroke = 3, dot = 1.6× stroke).
struct RouteSketch: View {
    let route: [GeoPoint]
    let color: Color

    var body: some View {
        Canvas { context, size in
            guard route.count >= 2, size.width > 0, size.height > 0 else { return }
            let points = normalizedRoute(route).map {
                CGPoint(x: $0.x * size.width, y: $0.y * size.height)
            }
            var path = Path()
            path.move(to: points[0])
            for point in points.dropFirst() { path.addLine(to: point) }
            context.stroke(
                path,
                with: .color(color),
                style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round)
            )
            let dot: CGFloat = 3 * 1.6
            for endpoint in [points[0], points[points.count - 1]] {
                let rect = CGRect(x: endpoint.x - dot, y: endpoint.y - dot, width: dot * 2, height: dot * 2)
                context.fill(Path(ellipseIn: rect), with: .color(color))
            }
        }
    }
}

// Pure twin of `ui-map`'s `normalizeRoute`: fit the lat/lng route into the unit square with
// x = longitude (west → 0) and y = latitude inverted (north → 0, so the sketch reads
// north-up); a degenerate span (all points share a lat or lng) collapses to the centre line
// rather than dividing by zero.
func normalizedRoute(_ route: [GeoPoint]) -> [CGPoint] {
    guard !route.isEmpty else { return [] }
    let lats = route.map(\.latitude)
    let lngs = route.map(\.longitude)
    let minLat = lats.min()!, maxLat = lats.max()!
    let minLng = lngs.min()!, maxLng = lngs.max()!
    let latSpan = maxLat - minLat
    let lngSpan = maxLng - minLng
    return route.map { point in
        CGPoint(
            x: lngSpan > 0 ? (point.longitude - minLng) / lngSpan : 0.5,
            y: latSpan > 0 ? (maxLat - point.latitude) / latSpan : 0.5
        )
    }
}
