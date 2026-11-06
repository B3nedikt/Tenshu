import SwiftUI
import TenshuSdk

/// A workout's **real GPS route** over the offline basemap — the map counterpart to the schematic
/// `RoutePathView`, and the first consumer of the SwiftUI map view (P2b, the slice that exists to
/// put a real tile on an iOS screen).
///
/// The SwiftUI twin of `:fit-shared-ui`'s Compose `WorkoutRouteMap`, and deliberately the same
/// shape: an inline, gesture-free hero at a fixed height. It renders only when the workout carries
/// real GPS (`Workout.geoRoute`); a workout without one keeps the normalized `RoutePathView` sketch,
/// which is the common case (Health Connect / Google Fit rarely expose per-fix tracks).
///
/// Falls back to the sketch — never to a blank rectangle — when there are no bundled tiles at all.
///
/// The map itself, the camera framing and the attribution chip come from `:ios-basemap`'s shared
/// `TenshuRouteMap` (P3), which both this and Timeline's two route heroes compile. What stays here is
/// exactly what is Fit's: which fallback to draw, and the card's own size and chrome.
struct WorkoutRouteMapView: View {
    let route: [GeoCoord]
    /// The normalized 0..1 sketch to draw when there is nothing to draw a map *on*.
    let sketchFallback: [RoutePoint]
    var height: CGFloat = 200

    var body: some View {
        TenshuRouteMap(
            route: route.map {
                BasemapCoord(latitude: $0.latitude, longitude: $0.longitude, zoom: 14.5)
            },
            colorHex: Color.accentColor.basemapHex
        ) {
            RoutePathView(points: sketchFallback)
        }
        .frame(height: height)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .background(
            Color(.secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 16)
        )
    }
}
