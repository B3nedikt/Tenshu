import Foundation
import SwiftUI

/// Which engine draws the map heroes on this device.
///
/// `mapLibre` is what every user gets and what a user who never opens Settings must keep getting
/// (`ios-apple-maps-option.md` D1). `mapKit` exists only because a user asked for it, in this app,
/// and can take it back — see ``MapRendererControl``.
enum BasemapRenderer: String, CaseIterable {

    /// MapLibre over our own offline PMTiles. The default, on every platform, forever (D1).
    case mapLibre

    /// Apple's MapKit, drawn from Apple-served tiles. ⚠ Reaching this case means the user has
    /// elected it: rendering a hero discloses to Apple **which** place, trip or workout they chose
    /// to look at, and when (§3). Nothing else about the platform changes — no coordinate reaches
    /// the relay under either renderer, and custody is untouched.
    case mapKit
}

/// The user's per-app election of a map renderer: persisted, **off by default**, revocable, and
/// re-read at every hero construction so revoking takes effect on the next screen rather than the
/// next launch.
///
/// This is the `PlaceGeocodingControl` pattern (`place-geocoding.md` D5) in shape and in copy
/// discipline — disclose what the other party learns *before* the switch — but deliberately **in
/// Swift rather than in Kotlin** (`ios-apple-maps-option.md` §8, "what moved"). That control is
/// Kotlin because Kotlin's own enricher re-checks it before every lookup; here the only reader is
/// `BasemapHeroes.swift`, so a Kotlin twin would mean a new `iosMain` source set in
/// `:basemap-core` (commonMain/commonTest only today), a framework export and a SKIE crossing, all
/// to carry one `Bool` that never leaves Swift.
///
/// ⚠⚠ **The scope is one app, not one device** (D9). Under route 1 each first-party app is its own
/// sandbox, and `Timeline.device.entitlements` deliberately carries no App Group — that is the
/// paid-tier shared-replica gate. So electing MapKit in Timeline leaves Fit and Maps on MapLibre,
/// and any copy that says "on this device" would overstate the grant. A grant whose stated scope
/// exceeds its real one is defective consent, not a cosmetic slip.
///
/// ⚠ Do **not** reach for `UserDefaults(suiteName: "group.app.tenshu.shared")` to widen this. Where
/// the App-Group entitlement is absent it degrades silently, so the consent scope would vary by
/// build with nothing on screen changing. Widening app → device is a deliberate migration with new
/// copy (D9).
@MainActor
final class MapRendererControl: ObservableObject {

    /// The app-wide instance. One per process, which is exactly the scope of the election (D9).
    static let shared = MapRendererControl()

    /// ⚠ Namespaced by app bundle only implicitly — `UserDefaults.standard` **is** the app's own
    /// suite. The `tenshu_` prefix matches `IosPlaceGeocodingControl`'s key discipline so the two
    /// controls read as siblings in a defaults dump.
    static let storageKey = "tenshu_basemap_renderer"

    private let defaults: UserDefaults

    /// The elected renderer. Absence is `mapLibre`: an unset default, an unreadable value and a
    /// value written by an older build all resolve to the platform's own posture rather than to
    /// Apple's — the same "absence = NO" rule the platform's other consent switches follow.
    @Published private(set) var renderer: BasemapRenderer

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.renderer = Self.read(from: defaults)
    }

    /// Record the user's choice. Persists first, then publishes, so a crash between the two cannot
    /// leave the UI claiming an election the next launch would not honour.
    func select(_ renderer: BasemapRenderer) {
        defaults.set(renderer.rawValue, forKey: Self.storageKey)
        self.renderer = renderer
    }

    /// Revoke back to the platform default. Named rather than expressed as `select(.mapLibre)` at
    /// call sites because "revoke" is the thing the consent copy promises.
    func revoke() {
        select(.mapLibre)
    }

    private static func read(from defaults: UserDefaults) -> BasemapRenderer {
        guard let raw = defaults.string(forKey: storageKey),
              let renderer = BasemapRenderer(rawValue: raw)
        else {
            return .mapLibre
        }
        return renderer
    }
}
