import SwiftUI
import TenshuSdk

// The Tenshu Home iOS app (docs/proposals/home-and-mia-apps.md §3). Native SwiftUI over the shared
// `:home-shared-logic` view models, resolved through the `HomeSurface` umbrella seam. The twin of
// `calendar-ios` / `contacts-ios` / `photos-ios`.
//
// ⚠ Home runs on **route 1**. Provisioned → `HomeLocalRuntime` runs the production proxy graph in
// this process over the replica Control provisions; not provisioned → the shared *Set Up Tenshu
// Control* screen (ADR 0022 §Decision 6).
//
// ⚠⚠ READ-ONLY in this slice (2026-10-02): devices with their last-synced state, the hub/storage
// tile, the automation list, and run history. Device control, pairing, and automation
// toggle / remove / run-now / edit belong to the later "Home on iOS — control" slice
// (home-and-mia-apps.md §8). The repositories refuse writes, so this is enforced below the UI too.
@main
struct HomeIosApp: App {
    var body: some Scene {
        WindowGroup {
            StorageStateRoot { HomeShippingRoot() }
        }
    }
}

/// Shipping shell: **route-1 in-process runtime, or the set-up screen**.
///
/// On launch and every return to the foreground: if Control has provisioned the route-1 key
/// material, Home runs its OWN in-process proxy (`HomeLocalRuntime`). Absent provisioning it shows
/// the set-up screen instead of the app.
///
/// ⚠⚠ There is no mock behind this gate. This app used to render an invented household — "Sofa
/// lamp · On · 70%" — on a phone nothing had set up.
struct HomeShippingRoot: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var provisioning: ControlProvisioning = .checking

    var body: some View {
        ControlSetUpGate(
            provisioning: provisioning,
            appName: HomeCopy.appName,
            purpose: HomeCopy.purpose,
            onRecheck: { adopt() }
        ) {
            HomeRootView(onRecheck: { adopt() })
        }
            .onAppear { adopt() }
            .onChange(of: scenePhase) { phase in
                guard phase == .active else { return }
                adopt()
            }
    }

    private func adopt() {
        if HomeLocalRuntime.shared.isProvisioned() {
            HomeLocalRuntime.shared.install()
            provisioning = .provisioned
            return
        }
        if HomeLocalRuntime.shared.hasResidualCustody() {
            // Control tore the provisioning down while we held a copy. Sealing here is what stops
            // a persisted joiner payload silently resurrecting the adoption.
            HomeLocalRuntime.shared.deprovision()
        }
        provisioning = .absent
    }
}

enum HomeCopy {
    static let appName = "Home"
    /// Completes "Home uses Tenshu Control to …".
    static let purpose: LocalizedStringResource = "show your home's devices and automations, end-to-end encrypted and synced from your other devices"
}

/// The tabs, plus the reachability banner above them.
///
/// ⚠ The banner is for a **provisioned** app whose runtime reports Control unreachable. An app
/// Control never provisioned does not reach this view (ADR 0022 §Decision 6).
struct HomeRootView: View {
    /// Re-read provisioning (the shipping root's gate).
    var onRecheck: () -> Void = {}

    @StateObject private var reachability = HomeReachabilityModel()
    /// N6: hoisted above `AdaptiveAppShell` so a placement change (rotation, Split View resize)
    /// cannot take the selection with it.
    @State private var selection = "devices"

    /// The sync-activity capsule (`ios-sync-activity-indicator.md` §6a.2): observed ONCE here, drawn
    /// by the shell over whichever destination is showing.
    @StateObject private var syncActivity = SyncActivityModel()

    var body: some View {
        VStack(spacing: 0) {
            if reachability.reachable == false {
                ControlNotConnectedBanner(
                    appName: HomeCopy.appName,
                    purpose: HomeCopy.purpose,
                    onRecheck: onRecheck
                )
            }
            // ⚠ Below the not-connected banner, not instead of it: "Control is gone" and "the realm
            // cannot sync" are different facts. Above the shell, not inside a tab: the realm's
            // storage state is true of the whole app.
            RealmStorageBanner()
            AdaptiveAppShell(selection: $selection, destinations: [
                AppDestination(id: "devices", title: "Home", symbol: "house", filledSymbol: "house.fill") {
                    NavigationStack { DevicesView() }
                },
                AppDestination(id: "automations", title: "Automations", symbol: "sparkles") {
                    NavigationStack { AutomationsView() }
                },
                AppDestination(id: "activity", title: "Activity", symbol: "clock.arrow.circlepath") {
                    NavigationStack { ActivityView() }
                },
                AppDestination(id: "settings", title: "Settings", symbol: "gearshape", filledSymbol: "gearshape.fill") {
                    NavigationStack { HomeSettingsView() }
                }
            ],
            // The same expression that decides the banner above: while it shows, the capsule does not.
            destinationOverlay: AnyView(SyncActivityCapsuleSlot(
                model: syncActivity,
                hostProblem: reachability.reachable == false,
                showsStorageBar: true,
                hidden: false
            )))
        }
        .syncActivityObservation(syncActivity)
        .task { await reachability.observe() }
    }
}

/// Observes the surface's one reachability signal. `nil` until the first outcome — the banner shows
/// nothing while it waits, because "not yet known" and "not connected" are different states.
@MainActor
final class HomeReachabilityModel: ObservableObject {
    @Published var reachable: Bool?

    func observe() async {
        for await value in HomeSurface.shared.controlReachable() {
            reachable = value?.boolValue
        }
    }
}

/// The empty state every Home tab shares. Hand-rolled: SwiftUI's own unavailable-content view is
/// iOS 17+ and the floor is 16.0.
struct HomeEmptyState: View {
    let symbol: String
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: symbol).font(.largeTitle).foregroundStyle(.secondary)
            Text(title).font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// Settings placeholder — rooms, notifications and Hub management arrive with a later
// milestone (matching the Android/desktop placeholder tab).
struct HomeSettingsView: View {
    var body: some View {
        VStack {
            HomeEmptyState(
                symbol: "gearshape",
                title: "Settings",
                message: "Rooms, notifications and Hub management arrive with a later milestone."
            )
            // In-app privacy policy (App Store guideline 5.1.1(i)): a placeholder, but it is the
            // app's Settings tab. ios-release/Sources.
            PrivacyPolicyLink()
            // Then the open-source notices (docs/oss-licences.md), owed now, before the real
            // Settings.
            OpenSourceLicencesLink()
                .padding(.horizontal)
                .padding(.bottom, 24)
        }
        .navigationTitle("Settings")
    }
}
