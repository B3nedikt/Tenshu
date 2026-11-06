import SwiftUI
import TenshuSdk

// The Tenshu Fit iOS app (docs/proposals/fit-app.md). Native SwiftUI over the shared
// `:fit-shared-logic` view models, resolved through the `FitSurface` umbrella seam
// (ios-proxy-architecture.md §9.3). v1 renders over `MockFitRepository` + `MockRecordingEngine`;
// the live-EPC data path swaps in behind `FitSurface.installRepository(...)` with no change here.
@main
struct FitIosApp: App {
    // R-iOS-5: register the BGAppRefreshTask handler during launch (BGTaskScheduler rejects
    // registrations afterwards). Scheduling is armed on backgrounding by the root views below.
    init() {
        FitBackgroundRefresh.register()
        #if DEV_HARNESS
        // ⚠ Before any view is built, not in a `.task`: `WorkoutDetailView`'s `@StateObject` resolves
        // its view model at init and the VM loads the workout immediately, so a repository installed
        // after the first render arrives too late (the probe renders "Workout not found").
        if ProcessInfo.processInfo.arguments.contains("-workoutRouteMap")
            || ProcessInfo.processInfo.arguments.contains("-mockHome") {
            FitSurface.shared.installMock()
        }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            #if DEV_HARNESS
            if ProcessInfo.processInfo.arguments.contains("-workoutRouteMap") {
                // P2b render probe (`ios-fit-timeline-maps-parity.md`): mount the REAL workout detail
                // for the one mock workout that carries real GPS (`w-sun-run`, an Arno-embankment loop
                // sited inside the bundled Firenze extract). Not a bespoke view — this is the shipping
                // `WorkoutDetailView` over the shipping view model, so a screenshot of it is evidence
                // about the production path and not about a harness.
                //
                // It exists because the alternative is two taps a headless `simctl` run cannot make:
                // `simctl` has no tap verb, and the established way to reach a deep screen head-less
                // here is a DEV_HARNESS launch argument (`drive-ios` has a dozen). The shipping scheme
                // never compiles it.
                //
                // ⚠ It needs `installMock()` (done in `init()` above) — `FitSurface` defaults to
                // `NotConnectedFitRepository` (D4: an unprovisioned app shows an honest empty state,
                // never fabricated data), so without it the probe renders "Workout not found". The
                // mock survives precisely for tests and previews, which is what this is.
                //
                // The id is overridable (`-workoutRouteMap w-sat-ride`) so the *negative* half is
                // provable by the same probe: a workout with a schematic route and no `geoRoute` must
                // still draw the sketch, never an empty map.
                NavigationStack { WorkoutDetailView(workoutId: Self.probeWorkoutId) }
            } else if ProcessInfo.processInfo.arguments.contains("-mockHome") {
                // Render probe for the tab shell over the mock (Home's week cards, §18.18): the real
                // `MainShell`, minus the onboarding gate and the Pair tab, so a headless simulator
                // run can screenshot Home with data in it. DEV_HARNESS only, like the probe above.
                MainShell()
            } else {
                StorageStateRoot { FitHarnessRoot() }
            }
            #else
            #if DEBUG
            // The store screenshots' data-only seed launch (StoreFixtureSeed.swift), never captured.
            if let directory = StoreFixtureSeed.requestedDirectory {
                StoreFixtureSeedView(directory: directory)
            } else {
                StorageStateRoot { FitShippingRoot() }
            }
            #else
            StorageStateRoot { FitShippingRoot() }
            #endif
            #endif
        }
    }

    #if DEV_HARNESS
    /// The workout the `-workoutRouteMap` probe opens: the argument after the flag, else the one
    /// mock workout carrying real GPS.
    static var probeWorkoutId: String {
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-workoutRouteMap"), i + 1 < args.count,
           !args[i + 1].hasPrefix("-") {
            return args[i + 1]
        }
        return "w-sun-run"
    }
    #endif
}

extension Notification.Name {
    /// Posted when the data path under the app changes — the route-1 runtime installed or
    /// deprovisioned, or (DEV_HARNESS Pair tab) the live EPC client activated / torn down — so the
    /// shell remounts and rebuilds its view models over the now-current repository.
    static let liveSessionChanged = Notification.Name("app.tenshu.fit.liveSessionChanged")
}

// Shipping shell: **route-1 in-process runtime** (ios-first-party-route-1-migration.md P2). On launch
// and every return to the foreground: if Control has provisioned the shared-Keychain key material,
// Fit runs its OWN in-process proxy over the shared App-Group replica (`FitLocalRuntime`) — no live
// Control channel, so no "Connect an app" dependency. When it installs, the shell remounts so view
// models rebuild over the now-current repository (the surface swap happens at VM-construction time).
//
// ⚠ Not provisioned, the root is the shared *Set Up Tenshu Control* screen (ADR 0022 §Decision 6):
// every tab is empty by construction, and a banner above "Your activity couldn't be loaded. Still
// trying…" read as an app that had lost its data. The next foreground (or *Check again*) re-probes.
//
// The pre-route-1 co-resident EPC fallback (`ControlAutoConnect`, which read an invite Control
// published into the shared Keychain while its "Connect an app" sheet was open) was deleted
// 2026-11-05 — route 1 covers the co-resident case (route-1 migration §8).
struct FitShippingRoot: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var liveToken = 0
    @State private var provisioning: ControlProvisioning = .checking

    var body: some View {
        ControlSetUpGate(
            provisioning: provisioning,
            appName: "Fit",
            purpose: "record and sync your workouts, end-to-end encrypted",
            onRecheck: { connect() }
        ) {
            FitRootView()
                .id(liveToken)
        }
            .task { connect() }
            .onChange(of: scenePhase) { phase in
                if phase == .active {
                    connect()
                    // fit-app.md §9.18: `connect()` alone is not enough. The launch import fires
                    // once per PROCESS (HealthImportBootstrap's init) and `install()` is
                    // idempotent, so re-foregrounding a live runtime re-read the store cache and
                    // nothing else — a workout recorded since launch stayed invisible. No-ops when
                    // nothing live is installed, and debounced Kotlin-side, so it is safe to fire
                    // on every activation including the one right after launch.
                    FitSurface.shared.refreshHealthImport()
                } else if phase == .background {
                    FitBackgroundRefresh.schedule()
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .liveSessionChanged)) { _ in
                liveToken += 1
                provisioning = FitLocalRuntime.shared.isInstalled() ? .provisioned : .absent
            }
    }

    private func connect() {
        // Provisioned → run Fit's in-process proxy over the shared replica (idempotent; adopts
        // Control's ONE device identity, sync goes live).
        if FitLocalRuntime.shared.isProvisioned() {
            let wasInstalled = FitLocalRuntime.shared.isInstalled()
            FitLocalRuntime.shared.install()
            if !wasInstalled {
                // First install swapped FitSurface onto the route-1 repository — remount so the view
                // models rebuild over it.
                NotificationCenter.default.post(name: .liveSessionChanged, object: nil)
            }
            provisioning = .provisioned
            return
        }
        // Not provisioned: purge any residual adopted custody (threat-model A4 duty) so the identity
        // cannot resurrect.
        if FitLocalRuntime.shared.isInstalled() || FitLocalRuntime.shared.hasResidualCustody() {
            FitLocalRuntime.shared.deprovision()
            NotificationCenter.default.post(name: .liveSessionChanged, object: nil)
        }
        provisioning = .absent
    }
}

#if DEV_HARNESS
// Dev/test harness shell (docs/proposals/ios-proxy-architecture.md §12): the normal app on one tab
// plus a `Pair` tab that reaches a live Control over EPC and installs the real `FitProjectionRepository`
// (`FitLiveClientSession`). A mock-only shipping build never compiles this. When the live client
// installs (or tears down), the App tab remounts so its view models rebuild over the now-current
// repository — the surface swap happens at VM-construction time, so a fresh mount is what picks it up.
struct FitHarnessRoot: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var liveToken = 0

    var body: some View {
        TabView {
            FitRootView()
                .id(liveToken)
                .tabItem { Label("App", systemImage: "figure.run") }
            EpcPairingView()
                .tabItem { Label("Pair", systemImage: "shield.lefthalf.filled") }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                FitSurface.shared.refreshHealthImport()   // §9.18, as the shipping root does
            } else if phase == .background {
                FitBackgroundRefresh.schedule()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .liveSessionChanged)) { _ in
            liveToken += 1
        }
    }
}
#endif

// Push routes shared by the tab stacks.
enum FitRoute: Hashable {
    case workout(id: String)
    /// [metric] picks one of Activity's series — Home's week cards and the values under the ring
    /// open steps, active minutes, distance and energy burned (fit-app.md §18.18). nil = the
    /// category's default, which is what Browse opens; other categories ignore it.
    case category(id: CategoryId, metric: CategoryMetric? = nil)
    case medical
    // DS-5. ⚠ The device travels with the kind: two phones in one realm mean two source rows,
    // and a route keyed on kind alone would open one device's screen showing the other's figures.
    case dataSource(kind: DataSourceKind, deviceId: String?)
    case batchImport
}

// Onboarding gate (fit-app.md D5): `AppViewModel.onboarded` is nil until the first value arrives,
// then routes to Onboarding (first run) or the main tab shell.
struct FitRootView: View {
    @StateObject private var model = RootModel()

    var body: some View {
        Group {
            switch model.onboarded {
            case .some(true): MainShell()
            case .some(false): OnboardingView(onDone: { model.markOnboarded() })
            case .none: Color(.systemBackground).ignoresSafeArea().overlay { ProgressView() }
            @unknown default: EmptyView()
            }
        }
        // observe once, for the app's lifetime
        .task { await model.observe() }
    }
}

@MainActor
final class RootModel: ObservableObject {
    @Published var onboarded: Bool?
    private let vm = FitSurface.shared.appViewModel()

    func observe() async {
        for await value in vm.onboarded {
            onboarded = value?.boolValue
        }
    }

    // Onboarding calls `completeOnboarding()` on its own VM/repo; the shared repo flips `onboarded`,
    // which `observe()` already picks up. This is a belt-and-braces immediate flip for the UI.
    func markOnboarded() { onboarded = true }
}

// The main tab shell: Home / History / Record / Browse / Profile (fit-app.md §2). Each tab owns a
// NavigationStack so detail pushes stay within the tab.
struct MainShell: View {
    // A tag per destination so the Browse "connect a source" prompt (§12 B2b) can jump to
    // Profile → Data sources. N6: hoisted above `AdaptiveAppShell` — a placement change (rotation,
    // Split View resize) must not take the selection with it — and keyed by id rather than ordinal.
    @State private var selection = "home"
    /// Home's stack, typed and hoisted so a Home card can push without a `NavigationLink`
    /// (`WeekTrendCard`: its chart is a UIKit view no SwiftUI link sees a tap through).
    @State private var homePath: [FitRoute] = []

    /// The sync-activity capsule (`ios-sync-activity-indicator.md` §6a.2): observed ONCE here, drawn
    /// by the shell over whichever destination is showing.
    @StateObject private var syncActivity = SyncActivityModel()

    /// Neither route is live: the expression that decides the not-connected banner below, and so
    /// also what makes the sync capsule yield to it. ⚠ Only the DEV_HARNESS Pair tab can activate
    /// `FitLiveClientSession` now (the shipping co-resident EPC fallback is gone), so in a shipping
    /// build the second arm is always false — it stays because this shell is the harness's too.
    private var notConnected: Bool {
        !FitLocalRuntime.shared.isInstalled() && !FitLiveClientSession.shared.isActive
    }

    var body: some View {
        VStack(spacing: 0) {
            // Honest not-connected signal (fit-app.md §11.7): when there's no live Control the
            // screens are empty (NotConnectedFitRepository), not fabricated — this banner explains
            // why. Connected = the **route-1** in-process runtime is installed OR the route-2 EPC
            // live client is active; the banner shows only when neither is. Read on mount; the tree
            // remounts on `.liveSessionChanged`, so it clears the moment either installs.
            //
            // ⚠ The SHARED banner (ADR 0022). ⚠ The shipping root no longer builds this shell
            // while neither route is live (§Decision 6), so here it covers a route that dropped
            // under a mounted shell, and the DEV_HARNESS shell, which has no gate.
            if notConnected {
                ControlNotConnectedBanner(
                    appName: "Fit",
                    purpose: "record and sync your workouts, end-to-end encrypted",
                    onRecheck: { NotificationCenter.default.post(name: .liveSessionChanged, object: nil) }
                )
            }
            // N6: ⚠ the tags moved from `Int` to a stable string id. The old ordinals were
            // positional — `selectedTab = 4` meant "Profile" only for as long as Profile stayed
            // fifth — and the cross-destination jumps below are exactly the code that silently
            // breaks when a destination is inserted. The ids say what they mean.
            //
            // ⚠ Symbols are now outline + filled pairs: the HIG wants the **outlined** variant in
            // a sidebar and the filled one in a tab bar, and Fit was filled-only. `record.circle`
            // has no meaningfully different filled form, so it passes one symbol for both.
            // ⚠ Above the shell, not inside a tab: the realm's storage state is true of the whole
            // app, and someone who only opens History is owed it just as much.
            VStack(spacing: 0) {
            RealmStorageBanner()
            AdaptiveAppShell(selection: $selection, destinations: [
                AppDestination(id: "home", title: "Home", symbol: "house", filledSymbol: "house.fill") {
                    stack(path: $homePath) {
                        HomeView(onStartRecording: { selection = "record" }, open: { route in
                            // Once: a tap that reached both the card and its chart pushes one screen.
                            if homePath.last != route { homePath.append(route) }
                        })
                    }
                },
                AppDestination(id: "history", title: "History", symbol: "clock", filledSymbol: "clock.fill") {
                    stack { HistoryView() }
                },
                AppDestination(id: "record", title: "Record", symbol: "record.circle") {
                    stack { RecordView() }
                },
                AppDestination(id: "browse", title: "Browse", symbol: "square.grid.2x2",
                               filledSymbol: "square.grid.2x2.fill") {
                    stack { BrowseView(onImport: { selection = "profile" }) }
                },
                AppDestination(id: "profile", title: "Profile", symbol: "person", filledSymbol: "person.fill") {
                    stack { ProfileView() }
                }
            ],
            destinationOverlay: AnyView(SyncActivityCapsuleSlot(
                model: syncActivity,
                hostProblem: notConnected,
                showsStorageBar: true,
                hidden: false
            )))
            }
        }
        .syncActivityObservation(syncActivity)
    }

    // A NavigationStack wired with the shared detail destinations.
    private func stack<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        NavigationStack { routed(content()) }
    }

    /// The same over a typed path the caller owns (Home).
    private func stack<Content: View>(path: Binding<[FitRoute]>, @ViewBuilder _ content: () -> Content) -> some View {
        NavigationStack(path: path) { routed(content()) }
    }

    private func routed<Content: View>(_ content: Content) -> some View {
        content
                .navigationDestination(for: FitRoute.self) { route in
                    switch route {
                    case .workout(let id): WorkoutDetailView(workoutId: id)
                    case .category(let id, let metric): CategoryDetailView(categoryId: id, metric: metric)
                    case .medical: MedicalView()
                    case .dataSource(let kind, let deviceId):
                        DataSourceDetailView(kind: kind, deviceId: deviceId)
                    case .batchImport: BatchImportView()
                    }
                }
    }
}

// The "not connected to Control" bar shown above the tabs when Fit has no live client
// (fit-app.md §11.7) now lives in `ios-onboarding/Sources`, shared by every route-1 app
// (ADR 0022). Reconnection stays automatic — the route-1 gate re-probes on every foreground — so the
// bar's job was only ever to explain the empty screens; what it lacked was a way to act on the
// explanation.
