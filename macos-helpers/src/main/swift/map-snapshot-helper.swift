import AppKit
import MapKit

// The macOS map-snapshot helper — AM-5 of `docs/proposals/ios-apple-maps-option.md`.
//
// Reads one JSON request on stdin, writes one PNG on stdout. It exists because Compose Desktop
// renders through Skia and cannot host an `MKMapView`, while §6.5's design does not need to: our
// map heroes are **static frames** (gestures are disabled by design), and a static frame is an
// image. `MKMapSnapshotter` returns exactly that, so the JVM asks for pixels and draws them itself
// — the `MacCameraSession` pattern, and the same shape as the sibling `image-decode-helper`.
//
// ⚠⚠ Nothing here runs unless the user elected Apple Maps in the app that spawned this process.
// Reaching this binary means tiles are fetched from Apple, which discloses which place or trip the
// user opened, and when (threat model A35(f) / T-MR-05). No coordinate reaches the relay either
// way; House Rule #1 is untouched.
//
// ⚠ No entitlement is claimed, and that is load-bearing: AM-5a proved a snapshot renders from a
// bundle carrying none, while *claiming* `com.apple.developer.maps` without a provisioning profile
// is an instant SIGKILL. Adding the Maps capability here would break this helper, not enable it.

struct Request: Decodable {
    let centerLat: Double
    let centerLon: Double
    /// Slippy-map zoom, in `:basemap-core`'s 512 dp-tile convention.
    let zoom: Double
    let width: Double
    let height: Double
    let dark: Bool
    /// `[[lat, lon], …]` — empty for a point hero.
    let route: [[Double]]
    /// The route line as `#RRGGBB`, the same boundary representation the iOS path uses (D8).
    let colorHex: String
}

/// Web-Mercator ground resolution for `:basemap-core`'s zoom.
///
/// ⚠ 512, not 256: `MAPLIBRE_TILE_SIZE_DP = 512.0`, so one of its zoom levels covers **half** the
/// ground of the classic 256 px-tile zoom of the same number. The iOS path got this wrong first and
/// rendered every point hero one zoom level too far out (§8.2 F1) — the constant is spelled the same
/// way here so the two engines cannot drift apart.
func metersPerPoint(latitude: Double, zoom: Double) -> Double {
    78_271.51696 * cos(latitude * .pi / 180) / pow(2, zoom)
}

func color(fromHex hex: String) -> NSColor {
    let digits = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
    guard digits.count == 6, let value = UInt32(digits, radix: 16) else {
        return NSColor(red: 0x2F / 255, green: 0x6F / 255, blue: 0xEB / 255, alpha: 1)
    }
    return NSColor(
        red: CGFloat((value >> 16) & 0xFF) / 255,
        green: CGFloat((value >> 8) & 0xFF) / 255,
        blue: CGFloat(value & 0xFF) / 255,
        alpha: 1
    )
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("map-snapshot-helper: \(message)\n".utf8))
    exit(1)
}

let input = FileHandle.standardInput.readDataToEndOfFile()
guard let request = try? JSONDecoder().decode(Request.self, from: input) else {
    fail("could not decode the request")
}

let options = MKMapSnapshotter.Options()
options.size = CGSize(width: request.width, height: request.height)
// The snapshot follows the app's appearance, so a dark-mode hero gets Apple's dark basemap rather
// than a light map dropped into a dark page.
options.appearance = NSAppearance(named: request.dark ? .darkAqua : .aqua)
options.pointOfInterestFilter = .excludingAll
options.showsBuildings = false

if request.route.count >= 2 {
    // Frame the route: its bounding box plus the same 0.15 padding fraction `routeCameraFrame` uses,
    // so the two renderers agree about what "the same route" looks like.
    let lats = request.route.map { $0[0] }
    let lons = request.route.map { $0[1] }
    let midLat = (lats.min()! + lats.max()!) / 2
    let midLon = (lons.min()! + lons.max()!) / 2
    let latSpan = max(lats.max()! - lats.min()!, 0.0005) * 1.3
    let lonSpan = max(lons.max()! - lons.min()!, 0.0005) * 1.3
    options.region = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: midLat, longitude: midLon),
        span: MKCoordinateSpan(latitudeDelta: latSpan, longitudeDelta: lonSpan)
    )
} else {
    let metres = metersPerPoint(latitude: request.centerLat, zoom: request.zoom)
    options.region = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: request.centerLat, longitude: request.centerLon),
        latitudinalMeters: request.height * metres,
        longitudinalMeters: request.width * metres
    )
}

let done = DispatchSemaphore(value: 0)
var output: Data?

// ⚠ `start(with:)` on a background queue, never the bare `start {}`: that one completes on the MAIN
// queue, and this tool blocks main on the semaphore below. The AM-5a probe lost a run to exactly
// that deadlock and it read like MapKit refusing to work (§8.1).
MKMapSnapshotter(options: options).start(with: DispatchQueue.global()) { snapshot, error in
    defer { done.signal() }
    guard let snapshot, error == nil else { return }

    let image = snapshot.image
    let canvas = NSImage(size: image.size)
    canvas.lockFocus()
    image.draw(at: .zero, from: .zero, operation: .copy, fraction: 1)

    if request.route.count >= 2, let context = NSGraphicsContext.current?.cgContext {
        // `snapshot.point(for:)` is the snapshotter's own projection — exact, and the reason the
        // route is drawn here rather than reprojected JVM-side from the region we asked for.
        context.setStrokeColor(color(fromHex: request.colorHex).cgColor)
        context.setLineWidth(4)
        context.setLineJoin(.round)
        context.setLineCap(.round)
        for (index, point) in request.route.enumerated() {
            let position = snapshot.point(
                for: CLLocationCoordinate2D(latitude: point[0], longitude: point[1])
            )
            if index == 0 { context.move(to: position) } else { context.addLine(to: position) }
        }
        context.strokePath()
    }

    canvas.unlockFocus()

    if let tiff = canvas.tiffRepresentation,
       let rep = NSBitmapImageRep(data: tiff),
       let png = rep.representation(using: .png, properties: [:]) {
        output = png
    }
}

// A snapshot is a network fetch; generous, and the caller times out independently.
_ = done.wait(timeout: .now() + 20)

guard let png = output else { fail("no snapshot produced") }
FileHandle.standardOutput.write(png)
