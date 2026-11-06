import SwiftUI
import UIKit
import TenshuSdk

// The Tenshu Timeline iOS app (docs/proposals/timeline-app.md). Native SwiftUI over the shared
// `:timeline-shared-logic` view models, resolved through the `TimelineSurface` umbrella seam
// (ios-proxy-architecture.md §9.3). v1 renders over `MockTimelineRepository`; the live-EPC data
// path swaps in behind `TimelineSurface.installRepository(...)` with no change here.
@main
struct TimelineIosApp: App {
    init() {
        // Cross-app open, CALLER half (`control-cross-app-open.md` §5i, TT-2a on iOS). Timeline
        // hands Control a file's `DataId` and Control hands back a URL — which only Swift can
        // open, so the app installs that half here.
        //
        // ⚠ Installed at launch, unconditionally: the opener is asked for on a tap, and a tap can
        // come before the route-1 runtime is provisioned. A missing launcher would make that tap a
        // silent no-op rather than an honest refusal.
        IosOpenTargetCaller.shared.installLauncher { url in
            guard let url = URL(string: url) else { return false }
            // ⚠ Fire-and-forget: `open` is async and its completion says only that iOS accepted
            // the request. CO-D9's honest signal is the handler reporting in, never this.
            DispatchQueue.main.async { UIApplication.shared.open(url) }
            return true
        }
        // M4 live capture: a CLVisit / significant-location-change event can relaunch this app
        // straight into the background — no scene appears, so `TimelineShippingRoot.connect()`
        // never runs. Re-arm here, during launch, so the woken process rebuilds the route-1
        // runtime and its CLLocationManager and actually receives (and persists) the event that
        // woke it. No-ops when capture is off or Control hasn't provisioned.
        if TimelineCaptureRuntime.shared.isCaptureEnabled() && TimelineLocalRuntime.shared.isProvisioned() {
            TimelineLocalRuntime.shared.install()
            TimelineCaptureRuntime.shared.startIfEnabled()
        }
        #if DEV_HARNESS
        // ⚠ Before any view is built, not in a `.task`: each detail view's `@StateObject` resolves
        // its view model at init and loads immediately, so a repository installed after the first
        // render arrives too late (the probe would render "not found"). The Fit P2b probe learned
        // this the hard way.
        if MapHeroProbe.requested != nil {
            TimelineSurface.shared.installMock()
            // AM-3 parity pass (`ios-apple-maps-option.md` §8): force the renderer so one probe can
            // photograph BOTH engines against the same route without a human flipping a switch
            // between shots. ⚠ Same "before any view is built" rule as `installMock` — the heroes
            // read the election at construction, so selecting it in a `.task` would photograph the
            // previous engine.
            //
            // ⚠⚠ This WRITES the shipping election (`MapRendererControl` persists to
            // `UserDefaults`), so a probe run leaves the harness app elected until the next probe
            // resets it. That is confined to the DEV_HARNESS build and is why the flag is spelled
            // explicitly on every run rather than defaulted.
            if let forced = MapRendererProbe.requested {
                MapRendererControl.shared.select(forced)
            }
        }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            #if DEV_HARNESS
            if let probe = MapHeroProbe.requested {
                // P3 render probe (`ios-fit-timeline-maps-parity.md`): mount ONE of the three real
                // detail screens over the real view model, so a screenshot is evidence about the
                // shipping path rather than about a harness. It exists because `simctl` has no tap
                // verb and each hero is two taps deep — the established shape here (`drive-ios` has
                // a dozen such arguments, `fit-ios` has `-workoutRouteMap`).
                //
                // ⚠ Needs `installMock()` (done in `init()` above): the default surface is
                // `NotConnectedTimelineRepository` (D4 — an unprovisioned app shows an honest empty
                // state, never fabricated data), which has no moments, trips or places at all.
                NavigationStack { probe.view }
                    // AM-3: dark mode is half the parity pass, and the simulator's own appearance
                    // setting is a separate `simctl ui` call that races the launch. Forcing it here
                    // makes one screenshot one deterministic combination.
                    .preferredColorScheme(MapRendererProbe.forcedColorScheme)
            } else {
                StorageStateRoot { TimelineHarnessRoot() }
            }
            #elseif DEBUG
            // The store screenshots' data-only seed launch (StoreFixtureSeed.swift), never captured.
            if let folder = StoreFixtureSeed.requestedFolder {
                StoreFixtureSeedView(folder: folder)
            } else {
                StorageStateRoot { TimelineShippingRoot() }
            }
            #else
            StorageStateRoot { TimelineShippingRoot() }
            #endif
        }
    }
}

#if DEV_HARNESS
/// The AM-3 parity arguments (DEV_HARNESS only), layered on top of `-mapHero`:
/// `-mapRenderer <maplibre|mapkit>` and `-appearance <light|dark>`.
///
/// Together they turn "do the two engines frame the same route the same way?" — a question that
/// otherwise needs a human flipping a Settings switch between two screenshots — into four
/// deterministic launches. ⚠ Absent, both leave the app alone: no election is written and the
/// system appearance stands, so an ordinary `-mapHero` run behaves exactly as it did before AM-3.
enum MapRendererProbe {

    /// The renderer to force, or nil to leave the stored election untouched.
    static var requested: BasemapRenderer? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-mapRenderer"), i + 1 < args.count else { return nil }
        switch args[i + 1] {
        case "maplibre": return .mapLibre
        case "mapkit": return .mapKit
        default: return nil
        }
    }

    /// The appearance to force, or nil to follow the simulator.
    static var forcedColorScheme: ColorScheme? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-appearance"), i + 1 < args.count else { return nil }
        switch args[i + 1] {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }
}

/// The `-mapHero <moment|trip|place> [id]` launch argument (DEV_HARNESS only): which of the three
/// map heroes to mount head-less.
///
/// The default ids are the mock rows that actually carry geometry inside the bundled Firenze demo
/// extract. Overriding the id is what makes the *negative* half provable by the same probe — e.g.
/// `-mapHero place mogg` is a place with no coordinate and must still draw the flat hero, and
/// `-mapHero moment lunch` is a non-movement moment that must still draw its glyph card.
enum MapHeroProbe {
    case moment(String)
    case trip(String)
    case place(String)

    static var requested: MapHeroProbe? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-mapHero"), i + 1 < args.count else { return nil }
        let kind = args[i + 1]
        let explicitId = (i + 2 < args.count && !args[i + 2].hasPrefix("-")) ? args[i + 2] : nil
        switch kind {
        case "moment": return .moment(explicitId ?? "lis-riverwalk")
        case "trip": return .trip(explicitId ?? "lisbon-2026-04")
        case "place": return .place(explicitId ?? "ponte-vecchio")
        default: return nil
        }
    }

    @ViewBuilder var view: some View {
        switch self {
        case .moment(let id): MomentDetailView(momentId: id)
        case .trip(let id): TripDetailView(tripId: id)
        case .place(let id): PlaceDetailView(placeId: id)
        }
    }
}
#endif

// Shipping shell: **route-1 in-process runtime, honest not-connected fallback**
// (ios-first-party-route-1-migration.md P3). On launch and every return to the foreground: if Control
// has provisioned the route-1 key material, Timeline runs its OWN in-process proxy over the shared
// App-Group replica (`TimelineLocalRuntime`) — no live Control channel. Absent provisioning it shows
// the shared *Set Up Tenshu Control* screen instead of the app (ADR 0022 §Decision 6).
//
// ⚠ That fallback used to be `MockTimelineRepository`, which meant an unprovisioned user was shown
// invented places, trips and an invented "interlude" with nothing marking them as fake — strictly
// worse than an empty app (the Fit §11.7 precedent; the mock is a test/preview surface only now).
// The EPC live client remains a `DEV_HARNESS`-only path. When the runtime installs, the shell
// remounts so view models rebuild over the now-current repository (the surface swap happens at
// VM-construction time).
struct TimelineShippingRoot: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var liveToken = 0
    @State private var provisioning: ControlProvisioning = .checking

    var body: some View {
        // ⚠ `onRecheck` is threaded so the shared forward-to-Control screen's DEV_HARNESS stub can
        // drive the same gate the shipping path drives through `scenePhase` (ADR 0022).
        ControlSetUpGate(
            provisioning: provisioning,
            appName: "Timeline",
            purpose: "keep your places, moments and trips end-to-end encrypted on your own devices",
            onRecheck: { connect() }
        ) {
            TimelineRootView(onRecheck: { connect() })
                .id(liveToken)
        }
            .task { connect() }
            .onChange(of: scenePhase) { phase in
                if phase == .active { connect() }
            }
    }

    private func connect() {
        // Route-1 first: provisioned → run Timeline's in-process proxy over the shared replica
        // (idempotent; adopts Control's ONE device identity, sync goes live).
        if TimelineLocalRuntime.shared.isProvisioned() {
            let wasInstalled = TimelineLocalRuntime.shared.isInstalled()
            TimelineLocalRuntime.shared.install()
            if !wasInstalled {
                // First install swapped TimelineSurface onto the route-1 repository — remount so the
                // view models rebuild over it.
                liveToken += 1
            }
            // M4: with the live runtime up, arm capture if the user has opted in (idempotent).
            TimelineCaptureRuntime.shared.startIfEnabled()
            // Warm the Wi-Fi cache (§4.5). The capture path awaits its own refresh, but the
            // *rename* binding reads through `WifiPlaceFingerprint` synchronously from shared code
            // that cannot suspend — so the value it sees is whatever was last cached. Refreshing on
            // every foreground is what makes "name the place you are standing in" bind the network
            // you are actually on. No-ops when the opt-in is off (consent is checked before the
            // radio is touched).
            TimelineSurface.shared.refreshWifi()
            provisioning = .provisioned
            return
        }
        // Not provisioned: purge any residual adopted custody (threat-model A4 duty) so the identity
        // cannot resurrect, and show the set-up screen.
        if TimelineLocalRuntime.shared.isInstalled() || TimelineLocalRuntime.shared.hasResidualCustody() {
            TimelineLocalRuntime.shared.deprovision()
            liveToken += 1
        }
        provisioning = .absent
    }
}

#if DEV_HARNESS
// Dev/test harness shell (docs/proposals/ios-proxy-architecture.md §12): the normal app on one tab
// plus a `Pair` tab that reaches a live Control over EPC and installs the real
// `TimelineProjectionRepository` (`TimelineLiveClientSession`). A mock-only shipping build never
// compiles this. When the live client installs (or tears down), the App tab remounts so its view
// models rebuild over the now-current repository — the surface swap happens at VM-construction time,
// so a fresh mount is what picks it up.
struct TimelineHarnessRoot: View {
    @State private var liveToken = 0

    var body: some View {
        TabView {
            TimelineRootView()
                .id(liveToken)
                .tabItem { Label("App", systemImage: "map.fill") }
            EpcPairingView()
                .tabItem { Label("Pair", systemImage: "shield.lefthalf.filled") }
        }
        .onReceive(NotificationCenter.default.publisher(for: .liveSessionChanged)) { _ in
            liveToken += 1
        }
    }
}
#endif

// Push-navigation routes. Moment / Trip / Place open a detail by id; Trip detail can push a nested
// Trip route recursively (the model nests trips).
enum TimelineRoute: Hashable {
    case moment(id: String)
    case trip(id: String)
    case place(id: String)
}

struct TimelineRootView: View {
    /// Re-read provisioning (the shipping root's gate). Defaulted for the harness shell, which has
    /// no gate of its own.
    var onRecheck: () -> Void = {}

    var body: some View {
        VStack(spacing: 0) {
            // Honest not-connected signal, the twin of Fit's / Maps' / Contacts' shell banners
            // (fit-app.md §11.7) and of the Compose shell's `repository.controlReachable()` read
            // (timeline-shared-ui App.kt). Without a live runtime every screen is empty by
            // construction (`NotConnectedTimelineRepository`) — this banner is what says why,
            // instead of the fabricated mock data the shipping app used to show. Read on mount; the
            // tree remounts whenever the runtime installs or tears down, so it clears itself.
            //
            // ⚠ The SHARED banner (ADR 0022), which carries a *Set up* action into the shared
            // forward-to-Control screen. ⚠ Only a provisioned app (or the DEV_HARNESS shell) gets
            // here; one Control never provisioned shows the set-up screen at the root (§Decision 6).
            if !TimelineSurface.shared.controlReachable() {
                ControlNotConnectedBanner(
                    appName: "Timeline",
                    purpose: "keep your places, moments and trips end-to-end encrypted on your own devices",
                    onRecheck: onRecheck
                )
            }
            // ⚠ Below the not-connected banner, not instead of it: "Control is gone" and "the realm
            // cannot sync" are different facts, and a user can be owed both. Same ordering the
            // Compose shells use.
            RealmStorageBanner()
            NavigationStack {
                TimelineHomeView()
                    .navigationDestination(for: TimelineRoute.self) { route in
                        switch route {
                        case .moment(let id): MomentDetailView(momentId: id)
                        case .trip(let id): TripDetailView(tripId: id)
                        case .place(let id): PlaceDetailView(placeId: id)
                        }
                    }
            }
            // The sync-activity capsule (`ios-sync-activity-indicator.md` §6a.2). One stack, so
            // one mount. `hostProblem` is the expression that decides the banner above.
            .syncActivityCapsule(
                hostProblem: !TimelineSurface.shared.controlReachable(),
                showsStorageBar: true,
                hidden: false
            )
        }
        .tint(.primary)
    }
}

// The "not connected to Control" bar now lives in `ios-onboarding/Sources` and is shared by every
// route-1 app (ADR 0022). The local copy that stood here was message-only — it named UNLOCKING
// Control, not "Connect an app" (a route-1 app needs Control's realm unlocked, not an EPC pairing,
// even though Control's Settings offers that host) — but offered no way to get there. The shared
// banner keeps the same posture and adds the action.
