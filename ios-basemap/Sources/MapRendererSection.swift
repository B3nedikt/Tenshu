import SwiftUI

/// The Settings section that offers the map-renderer election — one view, compiled into every
/// map-bearing shell, so the **disclosure copy cannot drift** between the apps that carry it.
///
/// Shape and copy discipline come from `place-geocoding.md` D5 and its shipped twin, Timeline's
/// *Look up place names* toggle: state what the other party learns **before** the switch, say that
/// it is off by default, and say it in the app's own voice rather than in policy language.
///
/// ⚠⚠ **"In this app", never "on this device"** (`ios-apple-maps-option.md` D9). Under route 1 each
/// first-party app is its own sandbox and `*.device.entitlements` carries no App Group, so this
/// election really is per-app: turning it on in Timeline leaves Fit and Maps on MapLibre. A grant
/// whose stated scope exceeds its real one is defective consent, not a cosmetic slip — so the copy
/// says the true scope even though the true scope is the less tidy one.
///
/// ⚠ The section is unconditional here because, unlike `geocodingAvailable`, there is nothing to be
/// unavailable: both renderers are compiled into every shell that shows this. If that ever stops
/// being true, gate it the way `SettingsView.swift` gates geocoding — an affordance that does
/// nothing would be fabricated.
struct MapRendererSection: View {

    @ObservedObject private var election = MapRendererControl.shared

    var body: some View {
        Section {
            Toggle(
                "Use Apple Maps",
                isOn: Binding(
                    get: { election.renderer == .mapKit },
                    set: { election.select($0 ? .mapKit : .mapLibre) }
                )
            )
            Text(Self.disclosure)
                .font(.caption)
                .foregroundStyle(.secondary)
        } header: {
            Text("Maps")
        }
    }

    /// ⚠ Every clause here is load-bearing; none of it is marketing.
    ///
    /// - *"Off by default"* — the D2 promise, stated where the user can check it.
    /// - *"stored on your device … nothing leaves"* — what the default actually buys, said before
    ///   the alternative is described, so the trade is legible rather than implied.
    /// - *"which place, trip or workout you open, and when"* — the precise disclosure (§3). Not
    ///   "Apple sees your location": they may already, via Location Services, and overstating the
    ///   cost is as dishonest as understating it.
    /// - *"in this app"* — D9.
    /// - *"turn it off at any time"* — revocability, which is what makes the election defensible.
    private static let disclosure = """
        Off by default. Our maps draw from map data stored on your device, so opening one sends \
        nothing anywhere. Apple Maps covers everywhere and will look familiar — but Apple sees \
        which place, trip or workout you open, and when. This applies in this app only, and you \
        can turn it off at any time.
        """
}
