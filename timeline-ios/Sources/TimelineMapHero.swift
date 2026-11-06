import SwiftUI
import TenshuSdk

// Timeline's three detail heroes over the offline basemap (P3,
// `ios-fit-timeline-maps-parity.md` §3.1.2) — the SwiftUI twins of `:timeline-shared-ui`'s
// `TripRouteMap` and `PlaceMap`.
//
// Both cards are thin: the map view, the camera framing, the style and the attribution chip all come
// from `:ios-basemap`, which `fit-ios` compiles too. What lives here is what is Timeline's — the card
// geometry, the accent tint, and which fallback to draw.
//
// ⛔ Still no MapKit, and for the reason `RouteSketch.swift` records: Apple-served tiles would
// disclose the viewed region to a third party on every detail open (A35). MapLibre over device-local
// PMTiles is what removes the trade-off, not a relaxation of it.
//
// ⚠ Only `regionBundlePath` is threaded, never `regionStream`. The D10 streamed source cannot be
// constructed from Swift at all — `RegionStreamSource.readRange` is a suspend function type, exported
// as a Kotlin interface Swift cannot conform to (`basemap-core-agent.md`) — so the streamed region
// path is Kotlin work (B-4). Until then a place/route outside a *downloaded* region draws on the
// bundled demo archive, which is a Firenze extract: geometry outside it frames onto empty tiles.

/// A route (movement or trip) over the offline basemap, falling back to the normalized sketch when
/// there is no archive to draw it on. Renders nothing meaningful for a route under two points — the
/// caller gates, mirroring `TripRouteMap`'s `route.size < 2` early return.
struct TimelineRouteMapCard: View {
    let route: [GeoPoint]
    let accent: Color
    var regionBundlePath: String? = nil
    var height: CGFloat = 200

    var body: some View {
        TenshuRouteMap(
            route: route.map {
                BasemapCoord(latitude: $0.latitude, longitude: $0.longitude, zoom: 14.5)
            },
            colorHex: accent.basemapHex,
            regionBundlePath: regionBundlePath
        ) {
            // No tiles at all → the schematic sketch this screen shipped before the map landed. The
            // shape of the walk still reads; only the geography is missing.
            RouteSketch(route: route, color: accent).padding(20)
        }
        .frame(height: height)
        .background(accent.opacity(0.15), in: RoundedRectangle(cornerRadius: 20))
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }
}

/// A place on the offline basemap, pinned — the point hero, not a one-point route (see
/// `BasemapStyle.point`). Falls back to the flat personalised rectangle, which is also what a place
/// with **no coordinate** must get: the caller gates on `hasCoordinate` because `(0, 0)` would render
/// a confidently-wrong patch of the Gulf of Guinea instead of an honest placeholder — the same gate
/// `PlaceDetailScreen` applies on Compose.
struct TimelinePlaceMapCard: View {
    let latE7: Int64
    let lngE7: Int64
    let accent: Color
    let soft: Color
    var regionBundlePath: String? = nil
    var height: CGFloat = 160

    var body: some View {
        TenshuPointMap(
            center: BasemapCoord(
                latitude: Double(latE7) / 1e7,
                longitude: Double(lngE7) / 1e7,
                zoom: 14.5
            ),
            pinTint: accent,
            regionBundlePath: regionBundlePath
        ) {
            Rectangle().fill(soft)
        }
        .frame(height: height)
        .background(soft, in: RoundedRectangle(cornerRadius: 20))
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }
}

extension Place {
    /// Whether this place has a real coordinate. `(0, 0)` is not "unknown" to a map — it is a
    /// specific point in the Atlantic — so the map hero must not be offered without this.
    var hasCoordinate: Bool { latE7 != 0 || lngE7 != 0 }
}
