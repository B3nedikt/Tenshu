import SwiftUI
import TenshuSdk

// The Tenshu Calendar iOS app (docs/proposals/calendar-app.md §9 — CA-8a). Native SwiftUI over the
// shared `:calendar-shared-logic` view models, resolved through the `CalendarSurface` umbrella seam
// (ios-proxy-architecture.md §9.3 "share view models + repos, native UI"). The twin of
// `contacts-ios` / `maps-ios` / `timeline-ios` / `fit-ios`.
//
// ⚠ Calendar is **born on route 1** — it never had an EPC tier, so unlike Timeline and Fit there is
// nothing to migrate off and no live-Control channel to fall back to. Provisioned →
// `CalendarLocalRuntime` runs the production proxy graph in this process over the shared App-Group
// replica; not provisioned → the shared *Set Up Tenshu Control* screen (ADR 0022 §Decision 6).
@main
struct CalendarIosApp: App {
    var body: some Scene {
        WindowGroup {
            #if DEBUG
            // The store screenshots' data-only seed launch (StoreFixtureSeed.swift), never captured.
            if let directory = StoreFixtureSeed.requestedDirectory {
                StoreFixtureSeedView(directory: directory)
            } else {
                StorageStateRoot { CalendarShippingRoot() }
            }
            #else
            StorageStateRoot { CalendarShippingRoot() }
            #endif
        }
    }
}

/// Shipping shell: **route-1 in-process runtime, honest not-connected fallback**.
///
/// On launch and every return to the foreground: if Control has provisioned the route-1 key
/// material, Calendar runs its OWN in-process proxy over the shared App-Group replica
/// (`CalendarLocalRuntime`). Absent provisioning it shows the set-up screen instead of the app.
///
/// ⚠ **Calendar, not Control, owns the reminder
/// notification** here (CA-8c): `CalendarLocalRuntime` overrides the graph's no-op presenter, so
/// every reminder is posted, and its tap handled, by this app.
struct CalendarShippingRoot: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var provisioning: ControlProvisioning = .checking

    var body: some View {
        // ⚠ `onRecheck` is threaded so the shared forward-to-Control screen can drive the same gate
        // the shipping path drives through `scenePhase` (ADR 0022).
        ControlSetUpGate(
            provisioning: provisioning,
            appName: "Calendar",
            purpose: "keep your calendar end-to-end encrypted and synced across your devices",
            onRecheck: { adopt() }
        ) {
            CalendarRootView(onRecheck: { adopt() })
        }
            .onAppear {
                adopt()
                CalendarSurface.shared.refreshMirrors()
            }
            .onChange(of: scenePhase) { phase in
                guard phase == .active else { return }
                adopt()
                // CX-2b: calendars kept in sync from this iPhone catch up — reading the shared
                // replica, so the refresh diffs against what other processes wrote while we were away.
                // ⚠ The only schedule iOS has (no background scheduler, no EventKit trigger).
                CalendarSurface.shared.refreshMirrors()
            }
    }

    private func adopt() {
        if CalendarLocalRuntime.shared.isProvisioned() {
            CalendarLocalRuntime.shared.install()
            provisioning = .provisioned
            return
        }
        if CalendarLocalRuntime.shared.hasResidualCustody() {
            // Control tore the provisioning down while we held a copy. Sealing here is what stops
            // a persisted joiner payload silently resurrecting the adoption (the drive-ios P2a
            // teardown symmetry).
            CalendarLocalRuntime.shared.deprovision()
        }
        provisioning = .absent
    }
}

/// The agenda, plus the reachability banner above it.
///
/// ⚠ The banner here is for a **provisioned** app whose runtime reports Control unreachable. An
/// app Control never provisioned does not reach this view: the shipping root shows the set-up
/// screen instead (ADR 0022 §Decision 6), because an empty month under a banner read as a
/// calendar that had lost its events.
struct CalendarRootView: View {
    /// Re-read provisioning (the shipping root's gate).
    var onRecheck: () -> Void = {}

    @StateObject private var reachability = CalendarReachabilityModel()

    var body: some View {
        VStack(spacing: 0) {
            if reachability.reachable == false {
                ControlNotConnectedBanner(
                    appName: "Calendar",
                    purpose: "keep your calendar end-to-end encrypted and synced across your devices",
                    onRecheck: onRecheck
                )
            }
            // ⚠ The same expression that decides the banner above: while it shows, the sync
            // capsule does not (`ios-sync-activity-indicator.md` §4).
            CalendarTabs(notConnected: reachability.reachable == false)
        }
        .task { await reachability.observe() }
    }
}

/// Month, agenda, calendars.
///
/// ⚠ Three tabs rather than a view switcher in the nav bar, which is what the Compose shell has.
/// The shared view model reloads on every `show(...)` precisely so a view never renders the
/// previous view's window — so a switcher and separate tabs cost the same reload, and tabs keep
/// each screen's scroll position, which is what an agenda is mostly for.
///
/// ⚠ Week and day are deliberately absent. `CalendarViewModel` supports them and the layout maths
/// (`DayLayout`, `WeekGrid`) is shared and tested — but the *renderer* is CA-4's Compose canvas,
/// and a native time grid is a real slice rather than a tab entry. Recorded so its absence reads
/// as a decision.
private struct CalendarTabs: View {
    /// N6: hoisted above `AdaptiveAppShell` so a placement change (rotation, Split View resize)
    /// cannot take the selection with it.
    @State private var selection = "month"

    /// The root's not-connected banner is up.
    let notConnected: Bool

    /// The sync-activity capsule (`ios-sync-activity-indicator.md` §6a.2): observed ONCE here, drawn
    /// by the shell over whichever destination is showing.
    @StateObject private var syncActivity = SyncActivityModel()

    var body: some View {
        // ⚠ Above the shell, not inside a tab: the realm's storage state is true of the whole app.
        VStack(spacing: 0) {
        RealmStorageBanner()
        AdaptiveAppShell(selection: $selection, destinations: [
            AppDestination(id: "month", title: "Month", symbol: "calendar") {
                NavigationStack { CalendarMonthScreen() }
            },
            AppDestination(id: "agenda", title: "Agenda", symbol: "list.bullet") {
                NavigationStack { CalendarAgendaView() }
            },
            AppDestination(id: "calendars", title: "Calendars", symbol: "square.stack.3d.up",
                           filledSymbol: "square.stack.3d.up.fill") {
                NavigationStack { CalendarsScreen() }
            }
        ],
        destinationOverlay: AnyView(SyncActivityCapsuleSlot(
            model: syncActivity,
            hostProblem: notConnected,
            showsStorageBar: true,
            hidden: false
        )))
        }
        .syncActivityObservation(syncActivity)
    }
}

/// Observes the surface's one reachability signal. `nil` until the first outcome — the banner shows
/// nothing while it waits, because "not yet known" and "not connected" are different states.
@MainActor
final class CalendarReachabilityModel: ObservableObject {
    @Published var reachable: Bool?

    func observe() async {
        for await value in CalendarSurface.shared.controlReachable() {
            reachable = value?.boolValue
        }
    }
}
