import MapLibre
import SwiftUI
import TenshuSdk

/// The SwiftUI map view — a `UIViewRepresentable` over MapLibre's native `MLNMapView`, driven
/// entirely by a style JSON string and a camera frame that both come from `:basemap-core`
/// (`ios-fit-timeline-maps-parity.md` D7 / P2b).
///
/// ⛔ **MapKit is not an option here, and that is a threat-model constraint rather than a taste.**
/// Apple-served tiles would disclose the viewed region to a third party on *every* detail open —
/// A35, and the reason `timeline-ios/Sources/RouteSketch.swift` draws an abstract sketch instead of
/// a map. MapLibre over device-local PMTiles is what removes the trade-off: no network egress, no
/// API key, House Rule #1 intact. Do not "simplify" any of this to MapKit.
///
/// ## Why this is so thin
///
/// Everything cartographic — the style, the palette, the label-language gate, the camera framing —
/// lives in `:basemap-core` and is shared verbatim with the Compose renderer. What is left for the
/// view is exactly what cannot be shared: instantiating a `UIView`, handing it a string, and
/// pointing a camera. That split is the whole point of the D7 decision; if a change belongs in the
/// *style*, it belongs in `:basemap-core`, where the no-egress guard tests it.
///
/// ⚠ **Kotlin default arguments do not survive to Swift**, so every parameter of
/// `offlineBasemapStyleJson` / `routeBasemapStyleJson` / `routeCameraFrame` must be passed
/// explicitly. `BasemapStyle` (its own file, beside this one) is that convenience wrapper, kept on
/// the Swift side (the `basemap-core-agent.md` recommendation) rather than adding overloads to the
/// Kotlin surface.
struct TenshuMapView: UIViewRepresentable {

    /// A complete MapLibre style document. `MLNMapView.styleJSON` takes it directly — no temp file,
    /// no `styleURL` round-trip.
    let styleJSON: String

    /// Where to point the camera. Comes from `:basemap-core`'s `routeCameraFrame(...)`.
    let camera: RouteCameraFrame

    /// `false` (the hero default) disables every gesture. A pannable map inside a vertically
    /// scrolling detail page would compete with the page scroll for the same drag.
    var interactive: Bool = false

    func makeUIView(context: Context) -> MLNMapView {
        // Constructing WITH the style avoids a frame of the default (network-backed) demo style —
        // `MLNMapView(frame:)` alone would briefly resolve MapLibre's built-in style URL, which is
        // remote. There is no window in which this view can reach the network.
        let map = MLNMapView(frame: .zero, styleJSON: styleJSON)
        map.backgroundColor = .clear
        map.logoView.isHidden = true
        map.attributionButton.isHidden = true
        map.showsCompassView = false
        map.showsUserLocation = false
        apply(camera: camera, to: map, animated: false)
        apply(interactive: interactive, to: map)
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        if map.styleJSON != styleJSON { map.styleJSON = styleJSON }
        apply(camera: camera, to: map, animated: false)
        apply(interactive: interactive, to: map)
    }

    private func apply(camera: RouteCameraFrame, to map: MLNMapView, animated: Bool) {
        let target = CLLocationCoordinate2D(
            latitude: camera.centerLatitude,
            longitude: camera.centerLongitude
        )
        // Only move the camera when it actually differs: `updateUIView` runs on every SwiftUI
        // invalidation, and re-setting the same centre mid-gesture would fight the user's pan.
        let moved = abs(map.centerCoordinate.latitude - target.latitude) > 1e-9
            || abs(map.centerCoordinate.longitude - target.longitude) > 1e-9
            || abs(map.zoomLevel - camera.zoom) > 1e-6
        guard moved else { return }
        map.setCenter(target, zoomLevel: camera.zoom, animated: animated)
    }

    private func apply(interactive: Bool, to map: MLNMapView) {
        map.isScrollEnabled = interactive
        map.isZoomEnabled = interactive
        map.isRotateEnabled = false
        map.isPitchEnabled = false
    }
}

/// The attribution chip — the SwiftUI twin of `:ui-map`'s `BasemapChrome` over live tiles.
///
/// The engine's own logo and attribution button are hidden (matching `OrnamentOptions.AllDisabled`
/// on the Compose side, which is the established shape here), so this carries the attribution — and
/// says the thing a privacy-flagship app most wants said about its map: the tiles are local.
///
/// ⚠⚠ "© OpenStreetMap contributors" is the ODbL credit for the tiles (`docs/oss-licences.md` §3); it
/// replaced "Offline · MapLibre" and must not be shortened away. It only ever overlays MapLibre — the
/// MapKit heroes carry Apple's own attribution instead.
struct BasemapAttributionChip: View {
    var body: some View {
        Text("Offline · © OpenStreetMap contributors")
            .font(.caption2)
            .foregroundStyle(.secondary)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(.thinMaterial, in: Capsule())
            .padding(8)
    }
}

/// The single centre pin a **point** map wears — the SwiftUI twin of `BasemapChrome(showPin: true)`.
///
/// ⚠ The `-14` offset is not decoration. `mappin`'s tip is at the bottom of its own bounds, and the
/// camera centres the *place* in the middle of the surface, so an unshifted glyph would point at
/// somewhere half a pin south of the place it names. The Compose chrome shifts its `Icons.Outlined.Place`
/// by the same fraction of its own height for the same reason.
///
/// There is no pin on a route map: a journey is not a point, and `TripRouteMap` records the same
/// decision on the Compose side (`BasemapChrome(showPin: false)`).
struct BasemapPin: View {
    var tint: Color = .accentColor
    var size: CGFloat = 28

    var body: some View {
        Image(systemName: "mappin")
            .font(.system(size: size, weight: .semibold))
            .foregroundStyle(tint)
            .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
            .offset(y: -size / 2)
    }
}
