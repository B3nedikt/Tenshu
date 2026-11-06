import Foundation
import TenshuSdk

/// Swift-side convenience over `:basemap-core`'s String-returning style builders.
///
/// It exists for one reason, recorded in `basemap-core-agent.md`: **Kotlin default arguments do not
/// cross to Swift**, so `glyphsUrl:` and `labelLanguage:` would otherwise be spelled out at every
/// call site. Resolving them from `BasemapAssets` here also means one place decides what a missing
/// glyph atlas degrades to.
///
/// ⚠ It deliberately builds only *inputs* to the Kotlin builders and never a URL that goes into the
/// style directly: `BasemapStyleTest.everyUrlInTheStyleIsDeviceLocal` is the only thing testing that
/// no URL in an emitted style is remote (House Rule #1), and it can only see what `:basemap-core`
/// composes. A consumer that hand-assembles a style escapes the guard.
enum BasemapStyle {

    /// The offline basemap with a route line + endpoint dots overlaid, or nil when there are no
    /// bundled tiles to draw it on (the caller then falls back to the schematic sketch).
    ///
    /// ⚠ `tileUrl` resolves through `:basemap-core`'s `resolveBasemapSource(...)` rather than a
    /// local `if` so the region → bundled-demo → schematic order stays the one that is unit-tested
    /// once for every platform. A `regionBundlePath` whose file is missing therefore falls THROUGH
    /// to the demo rather than blanking the surface.
    static func route(
        _ route: [BasemapCoord],
        colorHex: String,
        dark: Bool,
        regionBundlePath: String? = nil
    ) -> String? {
        // AM-4b: the route's own bounding box is what an archive has to cover. A route the demo
        // extract cannot reach now resolves to the schematic rather than to empty tiles.
        guard let tileUrl = tileUrl(
            regionBundlePath: regionBundlePath,
            target: boundsOf(route: route)
        ) else { return nil }
        return routeBasemapStyleJson(
            pal: mapPalette(dark: dark),
            tileUrl: tileUrl,
            route: route,
            routeColorHex: colorHex,
            labelLanguage: BasemapAssets.labelLanguage,
            glyphsUrl: BasemapAssets.glyphsUrl
        )
    }

    /// The plain offline basemap — **no route line** — for a surface whose subject is a *point*
    /// rather than a path (a place hero). Nil under the same no-tiles condition as ``route(_:…)``.
    ///
    /// Kept as its own arm rather than "a route of one coordinate": `routeBasemapStyleJson` adds a
    /// line layer and two endpoint circles, which for a single point draw a degenerate line and two
    /// stacked dots *underneath* the pin that is already marking the spot. `PlaceMap` on the Compose
    /// side makes the same split — it calls `OfflineBasemap`, not `RouteBasemap`.
    static func point(
        _ center: BasemapCoord,
        dark: Bool,
        regionBundlePath: String? = nil
    ) -> String? {
        guard let tileUrl = tileUrl(
            regionBundlePath: regionBundlePath,
            target: boundsOf(route: [center])
        ) else { return nil }
        return offlineBasemapStyleJson(
            pal: mapPalette(dark: dark),
            tileUrl: tileUrl,
            labelLanguage: BasemapAssets.labelLanguage,
            glyphsUrl: BasemapAssets.glyphsUrl
        )
    }

    private static func tileUrl(regionBundlePath: String?, target: BasemapBounds?) -> String? {
        #if DEV_HARNESS
        // ⚠⚠ `-forceSchematic` (DEV_HARNESS only) is the ONLY way to reach the schematic fallback in
        // a built app, and that is itself the finding AM-4 recorded: every map-bearing shell mirrors
        // `tenshu_basemap.pmtiles` in through `basemap.yml`, so `bundledDemoAvailable` is always
        // true and this function never returns nil in production. Without this seam AM-4's offer
        // would have shipped unverified as well as inert.
        if ProcessInfo.processInfo.arguments.contains("-forceSchematic") { return nil }
        #endif
        let demo = BasemapAssets.demoArchiveTileUrl
        let source = resolveBasemapSource(
            regionBundlePath: regionBundlePath,
            regionBundleExists: regionBundlePath.map {
                FileManager.default.fileExists(atPath: $0)
            } ?? false,
            bundledDemoAvailable: demo != nil,
            demoBounds: BasemapAssets.demoArchiveBounds,
            target: target
        )
        switch onEnum(of: source) {
        // Every path this process can read is already inside its own sandbox, so a region bundle is
        // opened where it lies — none of the Android staging applies.
        case .region(let region): return "pmtiles://file://\(region.path)"
        case .bundledDemo: return demo
        case .schematic: return nil
        }
    }
}
