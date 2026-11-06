import Foundation
import TenshuSdk

/// Where the offline basemap's bytes live in the **app bundle**, and the two URLs the shared
/// `:basemap-core` style needs to name them.
///
/// This is the SwiftUI-path replacement for `:ui-map`'s `IosBasemapAssets.kt`. Android reads the
/// archive and the SDF glyph atlas out of `assets/` through the APK asset manager, which has no iOS
/// counterpart; the Compose iOS renderer therefore staged them as *Compose resources*. Under D7
/// (`ios-fit-timeline-maps-parity.md`) iOS no longer links `:ui-map` at all, so that route is gone
/// and the same two payloads ride as ordinary bundle resources instead — mirrored into the target by
/// `project.yml` from `ui-map/src/androidMain/assets`, which stays the single source of truth
/// because the `:ui-map` archive tests open it there by relative path.
///
/// Two properties of the iOS bundle make this simpler than the Android path, not merely different:
///
/// 1. **Nothing is staged.** `pmtiles://asset://` SIGABRTs maplibre-android's native reader, so the
///    Android actual copies the archive into `filesDir` first. A bundle resource already *is* a real
///    file, so `pmtiles://file://` names it in place. Nothing is copied and nothing is written.
/// 2. **Nothing is fetched.** Both URLs are `file://`, which is what keeps House Rule #1 intact —
///    `BasemapStyleTest.everyUrlInTheStyleIsDeviceLocal` (in `:basemap-core`, where the style is
///    authored) walks every string in the emitted style and rejects anything else.
enum BasemapAssets {

    /// The bundled Firenze demo archive as a MapLibre source URL, or nil when it is missing.
    ///
    /// ⚠ `pmtiles://` + `file://` + an **absolute path** — the exact triple the Android renderer
    /// builds from the same `commonMain` style. MapLibre 6.x's `PMTilesFileSource` parses the
    /// `pmtiles://` prefix and opens the remainder itself.
    static var demoArchiveTileUrl: String? {
        guard let path = Bundle.main.path(forResource: "tenshu_basemap", ofType: "pmtiles") else {
            return nil
        }
        return "pmtiles://file://\(path)"
    }

    /// What the bundled demo extract **declares it covers** — read from its own PMTiles header
    /// rather than hard-coded, so re-cutting the extract cannot silently invalidate it (AM-4b,
    /// `ios-apple-maps-option.md` §8.3).
    ///
    /// Nil means *unknown* (no archive, unreadable, or no declared bbox) and resolves as it did
    /// before the coverage check existed — a hero must not lose its map because sixteen bytes could
    /// not be read.
    ///
    /// ⚠ Cached for the process: the file is a bundle resource, so it cannot change under a running
    /// app, and every hero construction would otherwise re-open it.
    static let demoArchiveBounds: BasemapBounds? = {
        guard let path = Bundle.main.path(forResource: "tenshu_basemap", ofType: "pmtiles"),
              let handle = FileHandle(forReadingAtPath: path)
        else {
            return nil
        }
        defer { try? handle.close() }
        // 127 bytes is the whole v3 header; `:basemap-core` owns the parse so Swift and Kotlin
        // cannot disagree about where the bbox lives.
        guard let header = try? handle.read(upToCount: 127), header.count == 127 else { return nil }
        return basemapBoundsFromPmtilesHeader(header: kotlinBytes(header))
    }()

    /// `Foundation.Data` → `KotlinByteArray`, the only direction the export does not give us free.
    ///
    /// ⚠⚠ **`Foundation.Data`, spelled out.** `:core` exports a Kotlin marker trait called `Data`
    /// (`org.tenshu.core.Data`) through `TenshuSdk`, so in any file that imports that framework the
    /// bare name is *ambiguous for type lookup* and the compiler stops. It only bites where the type
    /// is written down — `contact.avatarData()` elsewhere infers it — which is why this is the first
    /// place in the repo to hit it.
    ///
    /// ⚠ One bridged call per byte — the cost `ContactsListView` avoids by having Kotlin hand Swift
    /// a `Data` instead. It is acceptable *here* and nowhere near a list body: 127 bytes, once per
    /// process, to keep the header parse in `:basemap-core` where both platforms read the same
    /// offsets. Re-deriving those offsets in Swift would be the more expensive mistake.
    private static func kotlinBytes(_ data: Foundation.Data) -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() {
            array.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return array
    }

    /// The glyph atlas directory as a `file://` URL with **no trailing slash**, ready to hand to
    /// `:basemap-core`'s `fileGlyphsUrl(...)`; nil when the atlas is absent from the bundle.
    ///
    /// ⚠ Returned **decoded**. MapLibre substitutes the template's `{fontstack}` segment verbatim,
    /// and the fontstack names contain spaces (`"Noto Sans Regular"`), so a percent-encoded base
    /// would compose into a path that does not exist. `Bundle.resourceURL.path` is already decoded;
    /// building the string by hand rather than via `URL.absoluteString` is what keeps it that way.
    static var glyphsRoot: String? {
        guard let resources = Bundle.main.resourceURL?.path else { return nil }
        let glyphs = "\(resources)/glyphs"
        guard FileManager.default.fileExists(atPath: glyphs) else { return nil }
        return "file://\(glyphs)"
    }

    /// The glyph URL template for the style, or a deliberately-unresolvable device-local one when the
    /// atlas is missing.
    ///
    /// A missing atlas degrades to a **label-free** map rather than to no map: MapLibre drops the
    /// labels and still draws every fill and line. Anything device-local keeps the no-egress guard
    /// honest, which is why the fallback names `/dev/null` rather than a remote font server — the
    /// same choice `OfflineBasemap.ios.kt` made.
    static var glyphsUrl: String {
        guard let root = glyphsRoot else { return "file:///dev/null/{fontstack}/{range}.pbf" }
        return fileGlyphsUrl(glyphsRoot: root)
    }

    /// The `name:<lang>` suffix the style should prefer for this device, or nil for the endonym.
    ///
    /// The gate is `:basemap-core`'s (`labelLanguageFor`), not ours: the tiles carry ~20 language
    /// variants including scripts the shipped glyph ranges do not cover, and asking for one of those
    /// renders a row of tofu boxes — strictly worse than the endonym.
    static var labelLanguage: String? {
        labelLanguageFor(languageTag: Locale.preferredLanguages.first)
    }
}
