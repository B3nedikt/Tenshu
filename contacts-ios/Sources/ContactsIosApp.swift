import SwiftUI
import TenshuSdk

// The Tenshu Contacts iOS app (docs/proposals/contacts-app.md §9.9 — Ph10). Native SwiftUI over
// the shared `:contacts-shared-logic` view models, resolved through the `ContactsSurface` umbrella
// seam (ios-proxy-architecture.md §9.3 "share view models + repos, native UI"). The twin of
// `maps-ios` / `timeline-ios` / `fit-ios`.
//
// ⚠ Contacts is **born on route 1** — it never had an EPC tier, so unlike Timeline and Fit there is
// nothing to migrate off and no live-Control channel to fall back to. Provisioned →
// `ContactsLocalRuntime` runs the production proxy graph in this process over the shared App-Group
// replica; not provisioned → the honest empty surface plus the banner saying why.
@main
struct ContactsIosApp: App {
    init() {
        #if DEV_HARNESS
        // ⚠ Before any view is built, not in a `.task`: each screen's `@StateObject` resolves its
        // view model at init and loads immediately, so a repository installed after the first
        // render arrives too late (the probe would show an empty list). Fit's P2b probe learned
        // this the hard way; timeline-ios and maps-ios record the same note.
        if ContactsScreenProbe.requested != nil {
            ContactsSurface.shared.installMock()
        }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            #if DEV_HARNESS
            if let probe = ContactsScreenProbe.requested {
                NavigationStack { probe.view }
            } else {
                StorageStateRoot { ContactsShippingRoot() }
            }
            #elseif DEBUG
            // The store screenshots' data-only seed launch (StoreFixtureSeed.swift), never captured.
            if let file = StoreFixtureSeed.requestedFile {
                StoreFixtureSeedView(file: file)
            } else {
                StorageStateRoot { ContactsShippingRoot() }
            }
            #else
            StorageStateRoot { ContactsShippingRoot() }
            #endif
        }
    }
}

#if DEV_HARNESS
/// The `-contactsScreen <list|detail|profile|duplicates> [personId]` launch argument (DEV_HARNESS
/// only): which screen to mount head-less over the *shipping* views and view models.
///
/// It exists because `simctl` has no tap verb and the detail is a tap deep — the established shape
/// here (`drive-ios` has a dozen such arguments, `maps-ios` has `-mapsScreen`).
///
/// ⚠ It runs over `ContactsSurface.installMock()`, so what it proves is the *view*, not the data
/// path: the shipping default is the not-connected surface, which by design has no contacts at all.
/// The default person id is a mock contact whose keyring is deliberately mixed, so the detail probe
/// renders the Q2b *key changed* state rather than the easy one.
enum ContactsScreenProbe {
    case list
    case detail(String)
    case profile
    case duplicates

    static var requested: ContactsScreenProbe? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-contactsScreen"), i + 1 < args.count else { return nil }
        let explicitId = (i + 2 < args.count && !args[i + 2].hasPrefix("-")) ? args[i + 2] : nil
        switch args[i + 1] {
        case "list": return .list
        case "detail": return .detail(explicitId ?? ContactsScreenProbe.defaultPersonId)
        case "profile": return .profile
        case "duplicates": return .duplicates
        default: return nil
        }
    }

    /// A mock contact with a *mixed* keyring — the Q2b demotion, the state worth looking at.
    static let defaultPersonId = "person-sara"

    @ViewBuilder var view: some View {
        switch self {
        case .list: ContactsListView(onOpenContact: { _ in }, onOpenDuplicates: {})
        case .detail(let id): ContactDetailView(personId: id)
        case .profile: ContactsProfileView()
        case .duplicates: ContactsDuplicatesView()
        }
    }
}
#endif

/// Shipping shell: **route-1 in-process runtime, honest not-connected fallback**.
///
/// On launch and every return to the foreground: if Control has provisioned the route-1 key
/// material, Contacts runs its OWN in-process proxy over the shared App-Group replica
/// (`ContactsLocalRuntime`). Absent provisioning it shows the shared *Set Up Tenshu Control* screen
/// instead of the app (ADR 0022 §Decision 6).
struct ContactsShippingRoot: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var provisioning: ControlProvisioning = .checking

    var body: some View {
        // ⚠ `onRecheck` is threaded so the shared forward-to-Control screen's DEV_HARNESS stub can
        // drive the same gate the shipping path drives through `scenePhase` (ADR 0022).
        ControlSetUpGate(
            provisioning: provisioning,
            appName: "Contacts",
            purpose: "keep your contacts end-to-end encrypted and synced across your devices",
            onRecheck: { adopt() }
        ) {
            ContactsRootView(onRecheck: { adopt() })
        }
            .onAppear { adopt() }
            .onChange(of: scenePhase) { phase in
                guard phase == .active else { return }
                adopt()
            }
    }

    private func adopt() {
        if ContactsLocalRuntime.shared.isProvisioned() {
            ContactsLocalRuntime.shared.install()
            provisioning = .provisioned
            return
        }
        if ContactsLocalRuntime.shared.hasResidualCustody() {
            // Control tore the provisioning down while we held a copy. Sealing here is what stops
            // a persisted joiner payload silently resurrecting the adoption (the drive-ios P2a
            // teardown symmetry).
            ContactsLocalRuntime.shared.deprovision()
        }
        provisioning = .absent
    }
}

/// The two top-level surfaces the Compose shell has — the badged contact list (D3) and the own
/// profile card (D6) — plus the reachability banner above both.
struct ContactsRootView: View {
    /// Re-read provisioning (the shipping root's gate). Defaulted for the render probes, which have
    /// no gate of their own.
    var onRecheck: () -> Void = {}

    @StateObject private var reachability = ReachabilityModel()

    var body: some View {
        VStack(spacing: 0) {
            if reachability.reachable == false {
                // Names Control rather than "the network", because that is what is actually
                // missing — and it is the difference between a blank list that looks like data
                // loss and one that explains itself.
                //
                // ⚠ The SHARED banner (ADR 0022), which adds the *Set up* action the local copy
                // lacked: "your contacts will appear once it is running" described a state
                // without offering any way to reach it. ⚠ Only a provisioned app gets here; one
                // Control never provisioned shows the set-up screen at the root (§Decision 6).
                ControlNotConnectedBanner(
                    appName: "Contacts",
                    purpose: "keep your contacts end-to-end encrypted and synced across your devices",
                    onRecheck: onRecheck
                )
            }
            // ⚠ The same expression that decides the banner above: while it shows, the sync
            // capsule does not (`ios-sync-activity-indicator.md` §4).
            ContactsTabs(notConnected: reachability.reachable == false)
        }
        .task { await reachability.observe() }
    }
}

private struct ContactsTabs: View {
    @State private var pendingCount = 0

    /// N6: hoisted above `AdaptiveAppShell` so a placement change (rotation, Split View resize)
    /// cannot take the selection with it.
    @State private var selection = "contacts"

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
            // The badge rides the destination rather than a modifier: the shell renders it in
            // whichever placement is current, so it stays visible when the tab bar becomes a sidebar.
            AppDestination(id: "contacts", title: "Contacts", symbol: "person.2",
                           filledSymbol: "person.2.fill", badge: pendingCount) {
                ContactsListRoute()
            },
            AppDestination(id: "you", title: "You", symbol: "person.crop.circle",
                           filledSymbol: "person.crop.circle.fill") {
                NavigationStack { ContactsProfileView() }
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
        // ⚠ Read at the shell, not in the list's view model: that VM only exists while the
        // Contacts tab is on screen, and the badge's whole job is to be visible from the other
        // tab. The Compose shell records the same reasoning.
        .task {
            for await confirmations in ContactsSurface.shared.repository().pendingConfirmations() {
                pendingCount = confirmations.count
            }
        }
    }
}

/// Push-navigation routes, the `TimelineRoute` twin. The list opens a contact by id, and the
/// duplicate-suggestions screen by itself.
enum ContactsRoute: Hashable {
    case contact(personId: String)
    case duplicates
}

/// ⚠ Value-based navigation over an explicit path, **not** `navigationDestination(item:)` — that
/// overload is iOS 17 and this app deploys to 16 (the same reason `ContentUnavailableCompat` exists
/// one file over). The list signals through callbacks rather than `NavigationLink(value:)`, so the
/// push has to be programmatic, which is what the path is for.
private struct ContactsListRoute: View {
    @State private var path: [ContactsRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            ContactsListView(
                onOpenContact: { path.append(.contact(personId: $0)) },
                onOpenDuplicates: { path.append(.duplicates) }
            )
            .navigationDestination(for: ContactsRoute.self) { route in
                switch route {
                case .contact(let personId): ContactDetailView(personId: personId)
                case .duplicates: ContactsDuplicatesView()
                }
            }
        }
    }
}

/// Observes the surface's one reachability signal. `nil` until the first outcome — the banner shows
/// nothing while it waits, because "not yet known" and "not connected" are different states.
@MainActor
final class ReachabilityModel: ObservableObject {
    @Published var reachable: Bool?

    func observe() async {
        for await value in ContactsSurface.shared.controlReachable() {
            reachable = value?.boolValue
        }
    }
}
