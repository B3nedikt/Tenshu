import SwiftUI
import TenshuSdk

// The Tenshu Mia iOS app (docs/proposals/home-and-mia-apps.md §4; mia-query-layer.md §19). Native
// SwiftUI over the shared `:mia-shared-logic` view models, resolved through the `MiaSurface`
// umbrella seam.
//
// ⚠ Mia is on **route 1**: provisioned → `MiaLocalRuntime` runs the production proxy graph in this
// process over the shared replica, and `askMia` is answered here; not provisioned → the shared
// *Set Up Tenshu Control* screen (ADR 0022 §Decision 6). There is no mock in a shipping build.
//
// ⚠⚠ **Read-only on this platform.** Mia answers questions and offers no action — no device
// control, no routine run, no new calendar event. That is enforced in the proxy
// (`MiaActionOffer.WITHHELD` in the iOS `PlatformProxyModule`), not here: an utterance asking for
// one is answered "I can't … from this device yet", and no confirmation ever reaches this app.
//
// Siri cannot be replaced on iOS (D7) — the Action Button / App Intents tier is a follow-up slice.
@main
struct MiaIosApp: App {
    var body: some Scene {
        WindowGroup {
            StorageStateRoot { MiaShippingRoot() }
        }
    }
}

/// Shipping shell: **route-1 in-process runtime, or the set-up screen**.
///
/// On launch and every return to the foreground: if Control has provisioned the route-1 key
/// material, Mia runs its own in-process proxy (`MiaLocalRuntime`). Absent provisioning it shows
/// the set-up screen instead of the app.
///
/// ⚠⚠ **The gate is ahead of onboarding, not behind it.** Walking someone through setting up an
/// assistant that cannot answer is the same defect one screen earlier — ADR 0025 records Android
/// putting its wall ahead of onboarding for exactly that reason. So `miaOnboarded` is not even
/// read until Control has provisioned the app.
struct MiaShippingRoot: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var provisioning: ControlProvisioning = .checking

    var body: some View {
        ControlSetUpGate(
            provisioning: provisioning,
            appName: "Mia",
            purpose: "answer questions about your own files, photos, places and calendar",
            onRecheck: { adopt() }
        ) {
            MiaRootView()
        }
            .onAppear { adopt() }
            .onChange(of: scenePhase) { phase in
                guard phase == .active else { return }
                adopt()
            }
    }

    private func adopt() {
        if MiaLocalRuntime.shared.isProvisioned() {
            // ⚠ Before `provisioning` flips: the gate builds the shell on that flip, and each
            // screen's view model binds whatever repository is installed at that moment.
            MiaLocalRuntime.shared.install()
            provisioning = .provisioned
            return
        }
        if MiaLocalRuntime.shared.hasResidualCustody() {
            // Control tore the provisioning down while we held a copy. Sealing here is what stops
            // a persisted joiner payload silently resurrecting the adoption (the drive-ios P2a
            // teardown symmetry).
            MiaLocalRuntime.shared.deprovision()
        }
        provisioning = .absent
    }
}

/// The provisioned app: the one-time intro, then the shell.
struct MiaRootView: View {
    // The one-time intro gate (M5). Device-local by design — iOS keeps it in AppStorage; the
    // Kotlin OnboardingStore is the Android/desktop twin (each device shows its own intro: it has
    // its own integration setup to offer).
    @AppStorage("miaOnboarded") private var onboarded = false

    /// N6: hoisted above `AdaptiveAppShell` so a placement change (rotation, Split View resize)
    /// cannot take the selection with it.
    @State private var selection = "chat"

    /// The sync-activity capsule (`ios-sync-activity-indicator.md` §6a.2): observed ONCE here, drawn
    /// by the shell over whichever destination is showing.
    @StateObject private var syncActivity = SyncActivityModel()

    var body: some View {
        if onboarded {
            mainShell
        } else {
            OnboardingView(onboarded: $onboarded)
        }
    }

    private var mainShell: some View {
        // ⚠ Above the shell, not inside a tab: the realm's storage state is true of the whole app.
        VStack(spacing: 0) {
        RealmStorageBanner()
        AdaptiveAppShell(selection: $selection, destinations: [
            AppDestination(id: "chat", title: "Chat", symbol: "bubble.left.and.text.bubble.right") {
                NavigationStack { ChatView() }
            },
            AppDestination(id: "history", title: "History", symbol: "clock.arrow.circlepath") {
                NavigationStack { HistoryView() }
            },
            AppDestination(id: "settings", title: "Settings", symbol: "gearshape", filledSymbol: "gearshape.fill") {
                NavigationStack { MiaSettingsView() }
            }
        ],
        // Mia takes the wall and has no not-connected bar of its own.
        // ⚠ Hidden on Chat: the composer owns the bottom edge there, and a capsule over the text
        // field is in the way of the one thing that screen is for.
        destinationOverlay: AnyView(SyncActivityCapsuleSlot(
            model: syncActivity,
            hostProblem: false,
            showsStorageBar: true,
            hidden: selection == "chat"
        )))
        }
        .syncActivityObservation(syncActivity)
    }
}
