import SwiftUI
import TenshuSdk

// Maps' place-detail hero over the offline basemap — the SwiftUI twin of `:maps-shared-ui`'s
// `MapSnippet`, and Maps' *only* consumer of the shared `ios-basemap` layer.
//
// It is thin on purpose: the map view, the style, the camera framing and the attribution chip all
// come from `ios-basemap` (which `fit-ios` and `timeline-ios` compile too). What lives here is what
// is Maps' — the card geometry and which fallback to draw. Maps is consumer #3 of that layer and
// needed nothing beyond the two `project.yml` lines the include documents.
//
// ⛔ No MapKit. Apple-served tiles would disclose the viewed region to a third party on every detail
// open (threat model A35). MapLibre over device-local PMTiles is what removes the trade-off.
//
// ⚠ Only `regionBundlePath` is threaded, never `PlaceDetailUiState.Loaded.regionStream`. The D10
// streamed source cannot be constructed from Swift at all — `RegionStreamSource.readRange` is a
// suspend function type, exported as a Kotlin interface Swift cannot conform to — so the streamed
// region path is Kotlin work (B-4). Until then a place outside a *downloaded* region draws on the
// bundled demo archive, which is a Firenze extract: a place elsewhere frames onto empty tiles.

/// A place on the offline basemap, pinned — a **point** hero, not a one-element route (D11).
///
/// The caller gates on the place actually having a coordinate before reaching this
/// (`MapsPlace.hasCoordinate`): `(0, 0)` is a real location in the Atlantic, and
/// `MapsPlaceProjection` fills `location` unconditionally, so an ungated hero would render a
/// confidently-wrong patch of empty sea where the honest answer is the flat placeholder.
///
/// The `fallback` here is only for "no archive to draw on".
struct MapsPlaceMapCard: View {
    let latitude: Double
    let longitude: Double
    var regionBundlePath: String? = nil
    var height: CGFloat = 208

    var body: some View {
        TenshuPointMap(
            center: BasemapCoord(latitude: latitude, longitude: longitude, zoom: 14.5),
            pinTint: .accentColor,
            regionBundlePath: regionBundlePath
        ) {
            Rectangle().fill(Color.accentColor.opacity(0.12))
        }
        .frame(height: height)
        .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 20))
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }
}
