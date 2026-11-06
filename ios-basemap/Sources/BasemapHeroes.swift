import SwiftUI
import TenshuSdk

/// A **route over the offline basemap**, with a caller-supplied fallback for when there is nothing to
/// draw it on — the shape every detail-screen route hero wants (Fit's workout, Timeline's movement
/// and trip). The SwiftUI counterpart of `:ui-map`'s `RouteBasemap`.
///
/// It renders the map only when both halves hold: there is a real route (≥ 2 points, the same gate
/// `TripRouteMap` applies) **and** `:basemap-core` resolved an archive to draw it on. Otherwise the
/// `fallback` runs, so the surface degrades to a schematic sketch rather than to a blank rectangle.
///
/// ⚠ The `GeometryReader` is load-bearing, not a habit. `routeCameraFrame` fits **both** axes against
/// real dp, and its own KDoc records that an earlier version which assumed a 512 dp surface framed
/// every route about a third of a zoom too tight. A SwiftUI point and an Android dp are the same unit
/// for this purpose. Every parameter is passed explicitly because Kotlin defaults do not cross.
///
/// Sizing and decoration are the caller's: this view fills whatever it is given, which is what lets
/// one hero be 180 pt behind a title and another 220 pt flush.
struct TenshuRouteMap<Fallback: View>: View {

    let route: [BasemapCoord]
    /// The route line's colour as `#RRGGBB` — see ``SwiftUI/Color/basemapHex`` (D8: hex, not ARGB).
    let colorHex: String
    /// A downloaded region archive to prefer over the bundled demo; nil → demo → fallback.
    let regionBundlePath: String?
    /// `false` (the hero default) disables every gesture — a pannable map inside a vertically
    /// scrolling detail page would compete with the page scroll for the same drag.
    let interactive: Bool
    private let fallback: () -> Fallback

    @Environment(\.colorScheme) private var colorScheme
    @ObservedObject private var election = MapRendererControl.shared

    init(
        route: [BasemapCoord],
        colorHex: String,
        regionBundlePath: String? = nil,
        interactive: Bool = false,
        @ViewBuilder fallback: @escaping () -> Fallback
    ) {
        self.route = route
        self.colorHex = colorHex
        self.regionBundlePath = regionBundlePath
        self.interactive = interactive
        self.fallback = fallback
    }

    var body: some View {
        // The elected renderer, re-read on every construction so revoking takes effect on the next
        // screen rather than the next launch (`ios-apple-maps-option.md` D2).
        //
        // ⚠ MapKit needs **no archive**: it streams Apple's own tiles, so the `regionBundlePath`
        // arm and its "no region ⇒ fallback" branch simply do not apply here. That asymmetry is the
        // feature — §2.2's coverage gap is exactly where an elected user stops seeing a squiggle.
        // ⚠ And no ``BasemapAttributionChip``: it says "Offline · © OpenStreetMap", which would be false,
        // and MapKit draws Apple's own logo and legal link which may not be obscured (§6.4).
        if election.renderer == .mapKit, route.count >= 2 {
            MapKitRouteMap(route: route, colorHex: colorHex, interactive: interactive)
        } else if route.count >= 2,
           let style = BasemapStyle.route(
               route,
               colorHex: colorHex,
               dark: colorScheme == .dark,
               regionBundlePath: regionBundlePath
           ) {
            GeometryReader { geo in
                TenshuMapView(
                    styleJSON: style,
                    camera: routeCameraFrame(
                        route: route,
                        viewportWidthDp: Double(geo.size.width),
                        viewportHeightDp: Double(geo.size.height),
                        paddingFraction: 0.15,
                        minZoom: 3.0,
                        maxZoom: 15.0
                    ),
                    interactive: interactive
                )
                .overlay(alignment: .bottomTrailing) { BasemapAttributionChip() }
            }
        } else {
            // ⚠⚠ AM-4: the offer appears only when MapKit would actually *help*. Reaching here with
            // two or more points means the geometry is fine and no archive resolved — the coverage
            // gap §2.2 describes, which an elected renderer closes. Reaching here with fewer than
            // two points means there is no route to draw at all, and Apple could not draw it
            // either: offering the trade there would be selling a disclosure for nothing.
            fallback()
                .overlay(alignment: .bottom) {
                    if route.count >= 2 { BasemapElectionAffordance() }
                }
        }
    }
}

/// A **single place on the offline basemap**, pinned — the point twin of ``TenshuRouteMap`` and the
/// SwiftUI counterpart of `:ui-map`'s `OfflineBasemap` + `BasemapChrome(showPin: true)`.
///
/// A point is not a one-element route: it needs no camera fitting (the coordinate's own zoom is the
/// answer) and no route line, so this takes the plain `offlineBasemapStyleJson` arm. See
/// ``BasemapStyle/point(dark:regionBundlePath:)`` for why forcing it through the route builder would
/// draw a degenerate line under the pin.
///
/// A place with no known coordinate must not be handed to this view — `(0, 0)` is the Gulf of Guinea,
/// which would render a confidently-wrong empty sea rather than an honest placeholder. Callers gate
/// on that; the ``fallback`` here is only for "no archive to draw on".
struct TenshuPointMap<Fallback: View>: View {

    let center: BasemapCoord
    let pinTint: Color
    let regionBundlePath: String?
    private let fallback: () -> Fallback

    @Environment(\.colorScheme) private var colorScheme
    @ObservedObject private var election = MapRendererControl.shared

    init(
        center: BasemapCoord,
        pinTint: Color,
        regionBundlePath: String? = nil,
        @ViewBuilder fallback: @escaping () -> Fallback
    ) {
        self.center = center
        self.pinTint = pinTint
        self.regionBundlePath = regionBundlePath
        self.fallback = fallback
    }

    var body: some View {
        // Same two asymmetries as the route hero: MapKit needs no archive, and carries Apple's own
        // attribution rather than our "Offline · © OpenStreetMap" chip (§6.4). The pin is shared, so the
        // one piece of chrome the user looks at cannot drift between engines.
        if election.renderer == .mapKit {
            MapKitPointMap(center: center)
                .overlay { BasemapPin(tint: pinTint) }
        } else if let style = BasemapStyle.point(
            center,
            dark: colorScheme == .dark,
            regionBundlePath: regionBundlePath
        ) {
            TenshuMapView(
                styleJSON: style,
                camera: RouteCameraFrame(
                    centerLatitude: center.latitude,
                    centerLongitude: center.longitude,
                    zoom: center.zoom
                ),
                interactive: false
            )
            .overlay { BasemapPin(tint: pinTint) }
            .overlay(alignment: .bottomTrailing) { BasemapAttributionChip() }
        } else {
            // AM-4. Unconditional here, unlike the route hero: this view's ``fallback`` means
            // exactly one thing — no archive to draw on — because a place with no coordinate is
            // gated out by the caller (D11) and never reaches it. So MapKit always helps.
            fallback()
                .overlay(alignment: .bottom) { BasemapElectionAffordance() }
        }
    }
}

extension Color {
    /// This colour as the opaque `#RRGGBB` string `:basemap-core` takes for the route line.
    ///
    /// Hex is the boundary representation on purpose (D8): an ARGB `Int` exports to Swift as `Int32`
    /// and `0xFFF0ECE4` does not fit a signed 32-bit integer, so the natural literal is a compile
    /// error; a Kotlin inline class would be name-mangled by the Obj-C export. A hex string is also
    /// what the style serialises to anyway.
    var basemapHex: String {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        // Resolve first: a dynamic system colour has no components until it is given a trait
        // collection to resolve against.
        let resolved = UIColor(self).resolvedColor(with: UITraitCollection.current)
        guard resolved.getRed(&r, green: &g, blue: &b, alpha: &a) else { return "#2F6FEB" }
        let clamp = { (v: CGFloat) in Int((max(0, min(1, v)) * 255).rounded()) }
        return String(format: "#%02X%02X%02X", clamp(r), clamp(g), clamp(b))
    }
}
