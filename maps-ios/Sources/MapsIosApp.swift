import SwiftUI
import TenshuSdk

// The Tenshu Maps iOS app (docs/proposals/maps-app.md, `ios-fit-timeline-maps-parity.md` P5).
// Native SwiftUI over the shared `:maps-shared-logic` view models, resolved through the
// `MapsSurface` umbrella seam (ios-proxy-architecture.md §9.3 "share view models + repos, native
// UI"). The twin of `timeline-ios` / `fit-ios`.
//
// ⚠ Maps is **born on route 1** — it is the P4 the route-1 migration parked for want of a shell, so
// unlike Timeline and Fit there is no EPC tier to fall back to and none to migrate off. Provisioned
// → `MapsLocalRuntime` runs the production proxy graph in this process over the shared App-Group
// replica; not provisioned → the honest empty surface plus the banner saying why (D4).
@main
struct MapsIosApp: App {
    init() {
        #if DEV_HARNESS
        // ⚠ Before any view is built, not in a `.task`: each screen's `@StateObject` resolves its
        // view model at init and loads immediately, so a repository installed after the first render
        // arrives too late (the probe would render an empty list / "not found"). The Fit P2b probe
        // learned this the hard way; timeline-ios records the same note.
        if MapsScreenProbe.requested != nil {
            MapsSurface.shared.installMock()
        }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            #if DEV_HARNESS
            if let probe = MapsScreenProbe.requested {
                NavigationStack { probe.view }
            } else {
                StorageStateRoot { MapsShippingRoot() }
            }
            #else
            StorageStateRoot { MapsShippingRoot() }
            #endif
        }
    }
}

#if DEV_HARNESS
/// The `-mapsScreen <places|detail|regions> [placeId]` launch argument (DEV_HARNESS only): which
/// screen to mount head-less over the *shipping* views and view models.
///
/// It exists because `simctl` has no tap verb and the detail is two taps deep — the established
/// shape here (`drive-ios` has a dozen such arguments, `fit-ios` has `-workoutRouteMap`,
/// `timeline-ios` has `-mapHero`).
///
/// ⚠ It runs over `MapsSurface.installMock()`, so what it proves is the *view*, not the data path:
/// the shipping default is the not-connected surface, which by design has no places at all. The
/// default place id is a mock place sited inside the bundled Firenze extract, so the detail hero has
/// tiles to draw on. ⚠ **Every** mock place carries a coordinate (`MapsMockData.projectToGeo`), so
/// the probe cannot reach the no-coordinate arm of the hero — that arm is the *real* store's common
/// case (`MapsPlaceProjection` emits `(0, 0)` for a place with no fix) and is gated in
/// `MapsPlace.hasCoordinate`, not by nullability.
enum MapsScreenProbe {
    case places
    case detail(String)
    case regions

    static var requested: MapsScreenProbe? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-mapsScreen"), i + 1 < args.count else { return nil }
        let explicitId = (i + 2 < args.count && !args[i + 2].hasPrefix("-")) ? args[i + 2] : nil
        switch args[i + 1] {
        case "places": return .places
        case "detail": return .detail(explicitId ?? MapsScreenProbe.defaultPlaceId)
        case "regions": return .regions
        default: return nil
        }
    }

    /// A mock place that carries a coordinate inside the bundled demo archive (see `MapsMockData`).
    static let defaultPlaceId = "home"

    @ViewBuilder var view: some View {
        switch self {
        case .places: PlacesView(onOpenPlace: { _ in }, onOpenRegions: {})
        case .detail(let id): MapsPlaceDetailView(placeId: id)
        case .regions: RegionPickerView()
        }
    }
}
#endif

// Shipping shell: **route-1 in-process runtime, honest not-connected fallback**
// (ios-first-party-route-1-migration.md P4). On launch and every return to the foreground: if
// Control has provisioned the route-1 key material, Maps runs its OWN in-process proxy over the
// shared App-Group replica (`MapsLocalRuntime`) — no live Control channel. Absent provisioning it
// shows the shared *Set Up Tenshu Control* screen instead of the app (ADR 0022 §Decision 6).
//
// The gate is copied from `TimelineShippingRoot` deliberately — it is the same gate, and the two
// should stay recognisably the same until there is a reason for them to differ.
struct MapsShippingRoot: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var liveToken = 0
    @State private var provisioning: ControlProvisioning = .checking

    var body: some View {
        // ⚠ `onRecheck` is threaded so the shared forward-to-Control screen's DEV_HARNESS stub can
        // drive the same gate the shipping path drives through `scenePhase` (ADR 0022).
        ControlSetUpGate(
            provisioning: provisioning,
            appName: "Maps",
            purpose: "keep the places you save end-to-end encrypted and synced across your devices",
            onRecheck: { connect() }
        ) {
            MapsRootView(onRecheck: { connect() })
                .id(liveToken)
        }
            .task { connect() }
            .onChange(of: scenePhase) { phase in
                if phase == .active { connect() }
            }
    }

    private func connect() {
        if MapsLocalRuntime.shared.isProvisioned() {
            let wasInstalled = MapsLocalRuntime.shared.isInstalled()
            MapsLocalRuntime.shared.install()
            if !wasInstalled {
                // The first install swapped MapsSurface onto the route-1 repositories — remount so
                // the view models rebuild over them (the swap happens at VM-construction time).
                liveToken += 1
            }
            provisioning = .provisioned
            return
        }
        // Not provisioned: purge any residual adopted custody (threat-model A4 duty) so the identity
        // cannot resurrect, and show the set-up screen.
        if MapsLocalRuntime.shared.isInstalled() || MapsLocalRuntime.shared.hasResidualCustody() {
            MapsLocalRuntime.shared.deprovision()
            liveToken += 1
        }
        provisioning = .absent
    }
}

/// Push-navigation routes. Places is the single top-level surface (its four tabs live *inside* it —
/// `maps-app.md` D1); a tapped place pushes the detail, the gear pushes the region picker. Exactly
/// the `:maps-shared-ui` `App.kt` back stack.
enum MapsRoute: Hashable {
    case place(id: String)
    case regions
}

struct MapsRootView: View {
    /// Re-read provisioning (the shipping root's gate). Defaulted for the render probes, which have
    /// no gate of their own.
    var onRecheck: () -> Void = {}

    var body: some View {
        VStack(spacing: 0) {
            // Honest not-connected signal — the twin of Timeline's, Contacts' and Fit's shell
            // banners, and of the Compose shell's `repository.controlReachable()` read
            // (`:maps-shared-ui` App.kt). Without a live runtime every screen is empty by
            // construction; this is what says why. Read on mount; the tree remounts whenever the
            // runtime installs or tears down, so it clears itself.
            //
            // ⚠ The SHARED banner (ADR 0022), carrying a *Set up* action into the shared
            // forward-to-Control screen. ⚠ Only a provisioned app gets here; one Control never
            // provisioned shows the set-up screen at the root (§Decision 6).
            if !MapsSurface.shared.controlReachable() {
                ControlNotConnectedBanner(
                    appName: "Maps",
                    purpose: "keep the places you save end-to-end encrypted and synced across your devices",
                    onRecheck: onRecheck
                )
            }
            // ⚠ Below the not-connected banner, not instead of it: "Control is gone" and "the realm
            // cannot sync" are different facts, and a user can be owed both.
            RealmStorageBanner()
            NavigationStack {
                PlacesRoot()
            }
            // The sync-activity capsule (`ios-sync-activity-indicator.md` §6a.2). One stack, so
            // one mount. `hostProblem` is the expression that decides the banner above.
            .syncActivityCapsule(
                hostProblem: !MapsSurface.shared.controlReachable(),
                showsStorageBar: true,
                hidden: false
            )
        }
        .tint(.primary)
    }
}

/// Places plus its two push destinations. Split out so the `NavigationStack` owns exactly one root.
private struct PlacesRoot: View {
    @State private var path: [MapsRoute] = []

    var body: some View {
        PlacesView(
            onOpenPlace: { path.append(.place(id: $0)) },
            onOpenRegions: { path.append(.regions) }
        )
        .navigationDestination(for: MapsRoute.self) { route in
            switch route {
            case .place(let id): MapsPlaceDetailView(placeId: id)
            case .regions: RegionPickerView()
            }
        }
    }
}

// The "not connected to Control" bar now lives in `ios-onboarding/Sources` and is shared by every
// route-1 app (ADR 0022). The local copy that stood here was a character-for-character duplicate of
// Timeline's — which is exactly the divergence risk the ADR was written about, caught before it
// diverged.
