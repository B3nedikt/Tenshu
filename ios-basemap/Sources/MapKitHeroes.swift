import MapKit
import SwiftUI
import TenshuSdk

// The **elected** renderer (`ios-apple-maps-option.md`, accepted 2026-08-13). Nothing here runs for
// a user who has not turned it on in this app: `BasemapHeroes` reads `MapRendererControl` and takes
// the MapLibre path unless the election says otherwise, and the election is off by default (D1/D2).
//
// ⚠⚠ Reaching this file means tiles are fetched from Apple **at view time**, which discloses which
// place, trip or workout the user chose to look at, and when (§3 / threat model A35). That is the
// whole cost of the feature and it is why the switch exists. It does not change custody: no
// coordinate reaches the relay under either renderer, and House Rule #1 is untouched.
//
// ⚠ D6 bounds this to the three heroes. MapKit does not become the region picker, does not gain
// pan/zoom where MapLibre has it disabled, and does not grow an Explore/Navigate surface.

/// An `MKMapView` that reports its own layout passes.
///
/// ⚠⚠ **This exists because `updateUIView` is not a layout callback.** `makeUIView` runs at
/// `frame: .zero`, and SwiftUI does **not** guarantee another `updateUIView` once the view has a
/// real size — so a camera fitted only in those two places is fitted against a zero surface.
/// AM-3 caught this the expensive way: an intermediate version bailed out of fitting at zero bounds
/// (correct in itself) and the route hero then rendered **the default world view with no route on
/// it**, in both appearances. The map's own `layoutSubviews` is the only signal that the surface
/// the camera must fit actually exists.
final class LayoutReportingMapView: MKMapView {

    /// Called when the view settles at a new non-zero size. Passes `self` so a closure can act on
    /// the map without capturing it — the map owns the closure, so capturing it back would retain
    /// the pair forever.
    var onLayout: ((MKMapView) -> Void)?

    private var reported: CGSize = .zero

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.size != reported, bounds.size != .zero else { return }
        reported = bounds.size
        onLayout?(self)
    }
}

/// A **route over Apple's basemap** — the MapKit twin of ``TenshuRouteMap``.
///
/// `MKMapView` behind a `UIViewRepresentable` rather than SwiftUI's `Map` (§6.2): `MapPolyline` and
/// `MapCameraPosition` are iOS 17+, and this repo's shells deploy to **iOS 16.0**
/// (`timeline-ios/project.yml`). Raising the floor is a product decision with its own blast radius;
/// wrapping `MKMapView` is the same shape `TenshuMapView` already is, and costs nothing.
struct MapKitRouteMap: UIViewRepresentable {

    let route: [BasemapCoord]
    /// The route line's colour as `#RRGGBB`, the same boundary representation the MapLibre path
    /// takes (D8) so a caller does not learn which engine it is feeding.
    let colorHex: String
    var interactive: Bool = false

    func makeCoordinator() -> Coordinator { Coordinator(colorHex: colorHex) }

    func makeUIView(context: Context) -> MKMapView {
        let map = LayoutReportingMapView(frame: .zero)
        map.delegate = context.coordinator
        map.showsUserLocation = false
        map.showsCompass = false
        map.showsScale = false
        // Points of interest are suppressed to match the offline style's restraint — a hero is a
        // route, not a place directory.
        map.pointOfInterestFilter = .excludingAll
        apply(interactive: interactive, to: map)
        // The real fit happens here, on the first layout pass with a usable size.
        map.onLayout = { [weak coordinator = context.coordinator] laidOut in
            coordinator?.refit(on: laidOut)
        }
        context.coordinator.sync(route: route, on: map, forceFit: true)
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        context.coordinator.colorHex = colorHex
        apply(interactive: interactive, to: map)
        context.coordinator.sync(route: route, on: map, forceFit: false)
    }

    private func apply(interactive: Bool, to map: MKMapView) {
        map.isScrollEnabled = interactive
        map.isZoomEnabled = interactive
        map.isRotateEnabled = false
        map.isPitchEnabled = false
    }

    final class Coordinator: NSObject, MKMapViewDelegate {

        var colorHex: String
        /// The route currently drawn, so `updateUIView` can tell a real change from SwiftUI simply
        /// re-invalidating — re-adding the overlay every pass would flicker and re-fit the camera
        /// mid-gesture, the same hazard `TenshuMapView.apply(camera:)` guards against.
        private var drawn: [BasemapCoord] = []
        /// ⚠⚠ The size the camera was last fitted against. `makeUIView` runs at `frame: .zero`, so
        /// the first fit happens before SwiftUI has laid anything out — padding computed against a
        /// zero surface, and no later pass re-ran it because the *route* had not changed. AM-3's
        /// screenshots caught it: the framing was in the right place but not to the right margin.
        /// This is the MapKit instance of the exact hazard `routeCameraFrame`'s KDoc records for the
        /// MapLibre side ("an earlier version … implicitly assumed a 512dp-wide viewport").
        private var fittedFor: CGSize = .zero

        init(colorHex: String) {
            self.colorHex = colorHex
        }

        func sync(route: [BasemapCoord], on map: MKMapView, forceFit: Bool) {
            let routeChanged = !route.elementsEqual(drawn, by: Self.sameCoord)
            // A real layout pass is a reason to re-fit even when the route is identical.
            let resized = map.bounds.size != fittedFor && map.bounds.size != .zero
            guard forceFit || routeChanged || resized else { return }
            if routeChanged || map.overlays.isEmpty {
                drawn = route
                map.removeOverlays(map.overlays)
                guard route.count >= 2 else { return }
                let coords = route.map {
                    CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                }
                map.addOverlay(MKPolyline(coordinates: coords, count: coords.count))
            }
            refit(on: map)
        }

        /// Re-frame the drawn route against the map's *current* size. Called on every real layout
        /// pass, which is the only moment the surface is known to exist.
        func refit(on map: MKMapView) {
            guard let line = map.overlays.compactMap({ $0 as? MKPolyline }).first else { return }
            fit(line, in: map)
        }

        /// ⚠ Proportional padding, not a fixed inset. `:basemap-core`'s `routeCameraFrame` frames
        /// against a 0.15 fraction of the viewport, and its own KDoc records that an earlier version
        /// assuming a fixed surface framed every route about a third of a zoom too tight. Matching
        /// the *fraction* is what keeps the two engines agreeing about what "the same route" looks
        /// like; matching it to the pixel is AM-3's screenshot pass.
        private func fit(_ line: MKPolyline, in map: MKMapView) {
            let size = map.bounds.size
            guard size != .zero else { return }
            fittedFor = size

            // ⚠⚠ Clamp before fitting. A route whose points sit within a few centimetres — a
            // workout recorded standing still — has a bounding box of effectively zero, and
            // `setVisibleMapRect` honours it: the device log for workout "Test 2" showed a span of
            // 8.9e-05° × 2.3e-04°, a **10 m window**, at which Apple's basemap has no roads or
            // labels to draw and renders a bare land fill under our route. MapKit was working
            // perfectly — `didFinishLoadingMap`, no error — we asked for the wrong thing.
            //
            // `:basemap-core` already refuses to go tighter than `BASEMAP_MAX_ZOOM`, and its KDoc
            // names the degenerate case, which is why the MapLibre hero renders this same workout
            // fine. Reusing that constant rather than spelling 15.0 here is the §8.2 F1 lesson:
            // when two renderers share a number, they must share the *definition*.
            let fitted = MKCoordinateRegion(line.boundingMapRect)
            let floorMetersPerPoint = MapKitPointMap.metersPerPoint(
                latitude: fitted.center.latitude,
                zoom: BASEMAP_MAX_ZOOM
            )
            let floor = MKCoordinateRegion(
                center: fitted.center,
                latitudinalMeters: Double(size.height) * floorMetersPerPoint,
                longitudinalMeters: Double(size.width) * floorMetersPerPoint
            )
            if fitted.span.latitudeDelta < floor.span.latitudeDelta
                || fitted.span.longitudeDelta < floor.span.longitudeDelta {
                map.setRegion(map.regionThatFits(floor), animated: false)
                return
            }

            let inset = max(16, min(size.width, size.height) * 0.15)
            map.setVisibleMapRect(
                line.boundingMapRect,
                edgePadding: UIEdgeInsets(top: inset, left: inset, bottom: inset, right: inset),
                animated: false
            )
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            guard let line = overlay as? MKPolyline else {
                return MKOverlayRenderer(overlay: overlay)
            }
            let renderer = MKPolylineRenderer(polyline: line)
            renderer.strokeColor = UIColor(basemapHex: colorHex)
            renderer.lineWidth = 4
            renderer.lineJoin = .round
            renderer.lineCap = .round
            return renderer
        }

        private static func sameCoord(_ a: BasemapCoord, _ b: BasemapCoord) -> Bool {
            abs(a.latitude - b.latitude) < 1e-9 && abs(a.longitude - b.longitude) < 1e-9
        }
    }
}

/// A **single place on Apple's basemap**, pinned — the MapKit twin of ``TenshuPointMap``.
///
/// The pin is the same SwiftUI ``BasemapPin`` the MapLibre hero overlays rather than an
/// `MKAnnotationView`, so the two engines cannot drift apart on the one piece of chrome the user
/// actually looks at. D11 still holds: a point is not a one-element route, and a place with no
/// coordinate must never reach this view.
struct MapKitPointMap: UIViewRepresentable {

    let center: BasemapCoord

    func makeCoordinator() -> Coordinator { Coordinator(center: center) }

    func makeUIView(context: Context) -> MKMapView {
        let map = LayoutReportingMapView(frame: .zero)
        map.showsUserLocation = false
        map.showsCompass = false
        map.showsScale = false
        map.pointOfInterestFilter = .excludingAll
        map.isScrollEnabled = false
        map.isZoomEnabled = false
        map.isRotateEnabled = false
        map.isPitchEnabled = false
        // Same reason as the route hero: the span is computed from the surface's size, so it cannot
        // be computed correctly before the surface has one.
        map.onLayout = { [weak coordinator = context.coordinator] laidOut in
            coordinator?.apply(to: laidOut)
        }
        context.coordinator.apply(to: map)
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        context.coordinator.center = center
        context.coordinator.apply(to: map)
    }

    final class Coordinator {

        var center: BasemapCoord

        init(center: BasemapCoord) {
            self.center = center
        }

        func apply(to map: MKMapView) {
            let size = map.bounds.size
            // ⚠ No "assume 320×200" fallback. A guessed surface is exactly how the MapLibre side
            // once framed every route a third of a zoom too tight (`routeCameraFrame` KDoc); here
            // the layout callback guarantees a real size arrives, so waiting for it is free.
            guard size != .zero else { return }
            let target = CLLocationCoordinate2D(
                latitude: center.latitude,
                longitude: center.longitude
            )
            let metersPerPoint = MapKitPointMap.metersPerPoint(
                latitude: center.latitude,
                zoom: center.zoom
            )
            let region = MKCoordinateRegion(
                center: target,
                latitudinalMeters: Double(size.height) * metersPerPoint,
                longitudinalMeters: Double(size.width) * metersPerPoint
            )
            map.setRegion(map.regionThatFits(region), animated: false)
        }
    }

    /// Web-Mercator ground resolution — the bridge between `:basemap-core`'s **zoom** (a slippy-map
    /// concept MapLibre consumes directly) and MapKit's **span in metres**.
    ///
    /// ⚠⚠ **512, not 256.** `:basemap-core` states its own convention — `MAPLIBRE_TILE_SIZE_DP =
    /// 512.0`, *"MapLibre's world is 512 × 2^zoom density-independent pixels across"* — so one of
    /// its zoom levels is **half** the ground span of the classic 256 px-tile zoom of the same
    /// number. The first draft of this function used the 256 constant (156543.03392) and the point
    /// hero rendered exactly one zoom level too far out: same centre, twice the area, which reads as
    /// "MapKit just looks different" rather than as an arithmetic bug. AM-3's side-by-side is what
    /// made it obvious — MapLibre showed the Vasari Corridor, MapKit showed half of Florence.
    ///
    /// 40075016.686 m (equatorial circumference) ÷ 512 dp = 78271.517 m per dp at zoom 0;
    /// `cos(latitude)` is the projection's own convergence toward the poles.
    static func metersPerPoint(latitude: Double, zoom: Double) -> Double {
        78_271.51696 * cos(latitude * .pi / 180) / pow(2, zoom)
    }
}

extension UIColor {

    /// The inverse of ``SwiftUI/Color/basemapHex`` — `#RRGGBB` back to a colour MapKit's renderer
    /// can stroke with. Falls back to the same blue that helper does, so a malformed string degrades
    /// to a visible route rather than to an invisible one.
    convenience init(basemapHex hex: String) {
        let digits = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard digits.count == 6, let value = UInt32(digits, radix: 16) else {
            self.init(red: 0x2F / 255, green: 0x6F / 255, blue: 0xEB / 255, alpha: 1)
            return
        }
        self.init(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: 1
        )
    }
}
