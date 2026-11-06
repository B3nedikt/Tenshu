import SwiftUI
import TenshuSdk

// The Tenshu Control iOS app shell (Control-on-iOS C2b/C2c,
// docs/proposals/ios-control-handover.md).
//
// Control-on-iOS is a full, key-holding realm device (§3 / §11.1 of
// ios-proxy-architecture.md) — unlike Drive (a keyless EPC client), Control mints
// + holds the master key. C2b stood up the main sections (Home / Data / Apps /
// Settings) bound to the real `:control-shared-logic` view models through
// `ControlSurface` over the `Mock*` repositories. C2c adds **genesis onboarding**:
// the first screen is now the new-user flow (create realm → mint key → silent
// recovery phrase) or recovery-from-phrase, gating entry into the main tabs.
// The DEBUG screenshot routes run over fakes (`FakeControlSyncRepository`); every other
// launch boots the real, ProxyModule-backed in-process data plane (C4-RT), and the
// installed* seams start "not connected" rather than defaulting to a fake.
@main
struct ControlIosApp: App {
    // RT-3 (C4-RT): boot the **real**, ProxyModule-backed Control proxy at launch so genesis
    // mints a real master key + A4 device key and seeds the realm into the shared SQLite
    // replica (local-first — no relay needed; ADR 0019). The SwiftUI screens are
    // unchanged: they bind through `ControlSurface`'s `installed*` seams, only the data source
    // flips from the C2c fakes to the real `TenshuSyncRepository`.
    init() {
        #if DEBUG
        // The screenshot deep-links (-pairingStep / -settingsHome / -onboardingRoute) drive the
        // SwiftUI over fakes (no relay), so leave the real runtime off for those captures.
        let args = ProcessInfo.processInfo.arguments
        let screenshotRoute = args.contains("-pairingStep")
            || args.contains("-settingsHome")
            || args.contains("-onboardingRoute")
            || args.contains("-epcAppPairing")
            // NT-1 / SH-1 captures run over the Mock backends — a real runtime would render
            // an empty center and an empty inventory, which is exactly what a screenshot of
            // these two screens must not be.
            || args.contains("-notificationCenter")
            || args.contains("-shares")
            // NT-2b: the alert harness drives the same Mock feed (below).
            || args.contains("-requestAlerts")
            // -clearDriveSession clears the shared-Keychain provisioning (epc-session item + route-1
            // key material); pure Keychain work, no proxy runtime needed.
            || args.contains("-clearDriveSession")
            // -wipeDeviceState purges ALL at-rest identity (custody bucket + handoff items);
            // pure Keychain work — booting the runtime would resurrect the stale identity
            // mid-wipe and can re-write state after the purge.
            || args.contains("-wipeDeviceState")
        // `-genesisAutoComplete` (RT-5 verification) drives a *real* genesis over the proxy, so it
        // deliberately is NOT a screenshot route — it needs the real runtime installed.
        if screenshotRoute {
            // ⚠ Explicit, because the fakes stopped being ControlSurface's default: every
            // `installed*` seam now starts "not connected" so a path that forgets to install
            // anything shows failures rather than fabricated data. These routes are the one place
            // that genuinely wants the samples — a capture over a real runtime is empty.
            ControlSurface.shared.installFakes()
        } else {
            ControlSurface.shared.installRealRuntime()
        }
        #else
        ControlSurface.shared.installRealRuntime()
        #endif

        // NT-2b: BGTaskScheduler rejects registrations after launch completes, so this must
        // happen here — including on the screenshot routes, where it registers a handler that
        // simply never fires (the OS schedules nothing for a route that arms nothing).
        ControlBackgroundRefresh.register()
    }

    var body: some Scene {
        WindowGroup {
            StorageStateRoot { ControlRootView() }
        }
    }
}

// Gates the app on the canonical three-way landing decision (mirrors the Compose
// `TenshuLandingStateRepository`): a launch-time probe of `ControlSurface.landingStateKey()`
// (backed by the real proxy's `getUnlockStatus()` / `getSyncStatus()`, persisted in secure
// storage), re-run on foreground — the iOS twin of Drive's `RootView` probe.
//  • first_time → onboarding (genesis / recovery)
//  • locked     → the returning-user unlock screen (a realm device whose keys aren't loaded)
//  • active     → the main sections
// A genesised iPhone reopens straight into Control (unlocking first when sealed) instead of
// re-showing onboarding every cold start (RT-5 durable probe; RT-6 let genesis complete on the
// sim, which surfaced the missing Locked branch this view now handles).
struct ControlRootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var phase: Phase = .checking
    // One ensure per process: the unlock hook covers the sealed-device path, and this covers a cold
    // start that lands `.ready` outright (an unlocked realm with no unlock wrapper never renders
    // LockedView, so the unlock hook alone would never fire there).
    @State private var ensuredCoResidentProvisioning = false
    #if DEBUG
    // Set by a probe route's Continue button. `argv` is immutable for the life of the process, so
    // this in-session flag is the ONLY way out of a probe route short of killing the app.
    @State private var debugRouteDismissed = false
    #endif

    private enum Phase: Equatable { case checking, reinstallGate, onboarding, locked, ready }

    /// RG-1 detector: written once the reinstall fork has resolved, whichever way it went.
    ///
    /// ⚠⚠ `UserDefaults`, deliberately **not** the Keychain (D1). A Keychain flag would survive the
    /// app deletion this is trying to detect, so it could never distinguish a reinstall from a
    /// relaunch — it would defeat the entire mechanism. `UserDefaults` lives in the app container,
    /// which iOS drops on delete: absent sentinel + present identity is exactly a reinstall.
    private static let installSentinelKey = "app.tenshu.control.installSentinel"

    var body: some View {
        #if DEBUG
        Group {
            if debugRouteDismissed {
                rootContent
            } else {
                debugRouteContent
            }
        }
        .environment(\.probeContinue, { debugRouteDismissed = true })
        #else
        rootContent
        #endif
    }

    #if DEBUG
    @ViewBuilder private var debugRouteContent: some View {
        // Screenshot / verification deep-links (no tap tooling), mirroring `OnboardingView`'s
        // `-onboardingRoute`:
        //  • `-pairingStep manual|name|approval|success` opens the C6 "Add a device" joiner wizard
        //    straight as the root, bypassing onboarding + the Settings entry.
        //  • `-settingsHome` renders Settings directly so the "Add a device" entry can be captured
        //    without driving genesis to reach the tabs.
        //  • `-syncDashboard` renders the RT-4 Devices read **over the real proxy** (init() does NOT
        //    skip installRealRuntime for it) — captures the live device/sync read without tap-driven
        //    genesis to reach the tabs.
        //  • `-genesisAutoComplete` (RT-5) drives a *real* genesis to completion over the proxy with
        //    no taps, so the durable realm-device probe can be exercised across a relaunch.
        //  • `-unlockAutoComplete` drives the returning-user unlock with the genesis verification
        //    passphrase (no taps), so the Locked → unlock → MainTabs path can be verified on the sim.
        //  • `-lockRoundTrip` unlocks (active) then calls lockRealm() and reports the landing state —
        //    verifies the "Lock now" primitive re-seals the realm in-session without tap tooling.
        // No effect in release.
        if ProcessInfo.processInfo.arguments.contains("-pairingStep") {
            PairingView(onClose: {})
        } else if ProcessInfo.processInfo.arguments.contains("-notificationCenter") {
            // NT-1: the actionable tier over `MockPendingRequestsRepository`'s sample (one
            // grant, one redeemed invite), so both card bodies — including the invite's SAS +
            // petname fields — are capturable without a second realm to grant from.
            NavigationStack { NotificationCenterView() }
        } else if ProcessInfo.processInfo.arguments.contains("-requestAlerts") {
            // NT-2b harness: the *live* half of the elevation, which no other route exercises —
            // the screenshot branches above return early and never reach the scenePhase/task
            // modifiers on the landing path, so `startRequestAlerts()` would otherwise be
            // compile-verified only. Renders the center over the same Mock feed and starts the
            // alerts, so the authorization prompt and the two posted notifications are
            // observable on the simulator. ⚠ Deliberately NOT folded into `-notificationCenter`:
            // a notification banner would land on top of that route's screenshot.
            NavigationStack { NotificationCenterView() }
                .task { ControlSurface.shared.startRequestAlerts() }
        } else if ProcessInfo.processInfo.arguments.contains("-shares") {
            // SH-1: the inventory over `MockShareManager`'s sample, whose folders deliberately
            // include the unnamed / unknown-count realm so the two fallback branches render.
            NavigationStack { SharesView() }
        } else if ProcessInfo.processInfo.arguments.contains("-settingsHome") {
            SettingsView()
        } else if ProcessInfo.processInfo.arguments.contains("-epcAppPairing") {
            // The Control EPC app-pairing host screen over the relay-free sample host (no real
            // runtime installed for this route), so the waiting/QR state is capturable on the sim.
            EpcAppPairingView(onClose: {})
        } else if ProcessInfo.processInfo.arguments.contains("-syncDashboard") {
            NavigationStack { SyncDashboardView() }
        } else if ProcessInfo.processInfo.arguments.contains("-genesisAutoComplete") {
            GenesisAutoCompleteView()
        } else if ProcessInfo.processInfo.arguments.contains("-unlockAutoComplete") {
            UnlockAutoCompleteView()
        } else if ProcessInfo.processInfo.arguments.contains("-lockRoundTrip") {
            LockRoundTripView()
        } else if ProcessInfo.processInfo.arguments.contains("-clearDriveSession") {
            ClearDriveSessionView()
        } else if ProcessInfo.processInfo.arguments.contains("-wipeDeviceState") {
            WipeDeviceStateView()
        } else {
            rootContent
        }
    }
    #endif

    @ViewBuilder private var rootContent: some View {
        Group {
            switch phase {
            case .checking:
                ProgressView()
            case .reinstallGate:
                // RG-1: shown once per install, ahead of the locked screen, when an identity is
                // present but this install has never seen it. Either choice writes the sentinel,
                // so the fork never appears twice.
                ReinstallGateView(
                    onContinue: {
                        markInstallSeen()
                        Task { await probe() }
                    },
                    onStartFresh: {
                        // The custody bucket (incl. UNLOCK_WRAPPER) is gone, so the durable probe
                        // now reads first-time; route straight to Welcome/genesis in-session.
                        markInstallSeen()
                        phase = .onboarding
                    }
                )
            case .onboarding:
                // `onComplete` flips to `.ready` in-session; the next cold start re-derives it
                // from the durable probe (genesis/recovery has written the unlock credential).
                OnboardingView(onComplete: { phase = .ready })
            case .locked:
                // Unlocking loads the keys in-session → straight to the tabs; the next cold start
                // re-derives `.locked` from the durable probe until unlocked again.
                LockedView(onUnlocked: {
                    // Keys are freshly in memory — the only moment the route-1 material is
                    // derivable, so ENSURE the co-resident provisioning here (publishing it if it
                    // is absent, not merely refreshing an existing one). The refresh-only shape
                    // this replaces left an onboarded device that had lost the bridge items with
                    // no path back: Drive fell to its connect screen and Fit banner-ed "Can't
                    // reach Tenshu Control" forever. See ControlSurface.ensureDriveProvisioned.
                    ensureCoResidentProvisioning()
                    phase = .ready
                }, onForget: {
                    // "Forget this device" wiped the local custody bucket (incl. UNLOCK_WRAPPER),
                    // so the durable probe now reads first-time on the next cold start too; route
                    // straight back to the Welcome/genesis flow in-session.
                    phase = .onboarding
                })
            case .ready:
                // "Lock now" (Settings) re-seals in-session → route straight back to the unlock screen.
                // The soft app-lock gate (control-keep-device-unlocked.md §4.2) wraps the active app:
                // a Face ID / passcode prompt on foreground when "Require unlock" is on. UI-only.
                AppLockGate {
                    // ⚠ `onForget` mirrors the reinstall gate's `onStartFresh`: the custody bucket is
                    // gone, so route straight to Welcome/genesis in-session rather than leaving the
                    // tabs standing over a realm this device no longer belongs to.
                    MainTabsView(onLock: { phase = .locked }, onForget: { phase = .onboarding })
                }
                // The app-global host-pairing approval (Compose's App-root modal). Mounted HERE
                // rather than on `rootContent`: the host session only exists on a realm-bound
                // device, and attaching it above would build the monitor — and its proxy read —
                // on every launch, including the locked and onboarding phases where there is
                // nothing to approve.
                .hostPairingApprovalHost()
            }
        }
        .task { await probe() }
        // ⚠⚠ Also here, not only in `onChange` below: SwiftUI's `onChange(of:)` fires on a
        // *change*, and a cold launch is already `.active` when this view appears — so the
        // scenePhase arm never runs for the first foreground. Starting the alerts only there
        // meant the live half silently did nothing until the user backgrounded and returned.
        // `startRequestAlerts` is idempotent, so having both is safe.
        .task { ControlSurface.shared.startRequestAlerts() }
        .onChange(of: scenePhase) { newPhase in
            if newPhase == .active {
                Task { await probe() }
                // NT-2b: the live half of the elevation — alert on requests that arrive while
                // this process is running. Idempotent, so re-entering the foreground is safe.
                ControlSurface.shared.startRequestAlerts()
            } else if newPhase == .background {
                // Arm the next wake as we leave. ⚠ Not at launch: a request submitted while the
                // app is foregrounded is one the OS may run immediately on backgrounding, and
                // re-arming here is what keeps the one-shot chain alive across terminations.
                ControlBackgroundRefresh.schedule()
            }
        }
    }

    private func probe() async {
        // A suspend fn bridges as `async throws` (throws on cancellation). A nil (cancelled /
        // failed) probe is treated as first-run (onboarding), never silently "ready" or "locked".
        let key = (try? await ControlSurface.shared.landingStateKey()) ?? "first_time"

        // RG-1: an identity this install has never seen means the Keychain outlived a delete.
        // ⚠ Checked against the landing state rather than the Keychain directly, so the two can
        // never disagree: "first_time" is precisely "no identity to resume", and a genuinely fresh
        // device therefore falls straight through to onboarding with the sentinel written below.
        let sentinel = UserDefaults.standard.bool(forKey: Self.installSentinelKey)
        // ⚠⚠ `hasResidentIdentity()` reads the Keychain custody bucket directly, NOT `key`. The
        // landing key answers "first_time" for a device whose runtime simply has not loaded yet, and
        // this probe runs before it has — so keying the sentinel on it latched "fresh device" on a
        // transient value and suppressed the gate forever. Caught on device: the console printed
        // `first_time` and then `active` from the very next probe.
        let residentIdentity = ControlSurface.shared.hasResidentIdentity()
        // ⚠ The gate is a screen, so a headless `--console` launch is the only way to see which
        // branch the detector took. Printed on every probe, so a device pass can also prove the fork
        // does NOT re-fire once the sentinel is written.
        let phraseState = ControlSurface.shared.residentPhraseState()
        // RG-2: which rescue branch the gate will take, printed so a device pass reads the
        // answer instead of inferring it from what the screen happens to render.
        print("[rg1] sentinel=\(sentinel) identity=\(residentIdentity) landing=\(key) phrase=\(phraseState) -> \(sentinel ? "normal" : (residentIdentity ? "REINSTALL-GATE" : "fresh-device"))")
        if !sentinel {
            if residentIdentity {
                phase = .reinstallGate
                return
            }
            markInstallSeen()
        }

        switch key {
        case "active":
            phase = .ready
            // Unlocked without passing through LockedView (no unlock wrapper) — the unlock hook
            // never fires here, so ensure the co-resident provisioning on this path too.
            ensureCoResidentProvisioning()
        case "locked": phase = .locked
        default: phase = .onboarding
        }
    }

    /// Record that this install has resolved the reinstall fork (or never needed it).
    private func markInstallSeen() {
        UserDefaults.standard.set(true, forKey: Self.installSentinelKey)
    }

    /// Publish the route-1 key material for the co-resident first-party apps if it is missing.
    /// Idempotent and once-per-process: the realm keys are in memory for the whole session, so a
    /// second call on the next foreground would only rewrite the same Keychain items.
    private func ensureCoResidentProvisioning() {
        guard !ensuredCoResidentProvisioning else { return }
        ensuredCoResidentProvisioning = true
        _ = ControlSurface.shared.ensureDriveProvisioned()
    }
}

#if DEBUG
// MARK: - One-shot probe chrome
//
// Every `-<arg>` route below is a *one-shot* probe: run something, report it, done. They are
// entered only via `devicectl device process launch … -<arg>`, and iOS fixes `argv` at exec — so
// returning to the app from the home screen or the app switcher **resumes the same process** and
// re-renders the same probe. There is no way to "navigate out" of a route by relaunching from the
// icon; only killing the process clears it.
//
// That made every probe a dead end, and worse, an *indistinguishable* one: each kept its
// `ProgressView()` spinning after it had finished, so a settled probe looked exactly like a hung
// one. A real iPhone sat on "✓ Device state wiped" for weeks looking broken while the wipe had
// long since succeeded — the process was simply never killed, and the spinner said "working".
//
// `ProbeScaffold` fixes both halves: the spinner stops when the probe settles (so a finished probe
// reads as finished), and a **Continue** button drops the route in-session so the normal app is
// reachable without a relaunch. `probeContinue` is threaded through the environment so the call
// sites need no new initializer arguments.

/// Drops the current DEBUG probe route and renders `ControlRootView.rootContent` instead.
/// Defaults to a no-op so a probe view previewed outside `ControlRootView` still compiles.
private struct ProbeContinueKey: EnvironmentKey {
    static let defaultValue: () -> Void = {}
}

extension EnvironmentValues {
    var probeContinue: () -> Void {
        get { self[ProbeContinueKey.self] }
        set { self[ProbeContinueKey.self] = newValue }
    }
}

/// The escape hatch out of a probe route — shown only once the probe has settled, so it never
/// invites a tap while work is still in flight.
struct ProbeContinueButton: View {
    @Environment(\.probeContinue) private var probeContinue

    var body: some View {
        Button("Continue to Control") { probeContinue() }
            .buttonStyle(.borderedProminent)
            .padding(.top, 8)
    }
}

/// Runs a one-shot probe and flips `settled` when it returns — including via any early `return`
/// on an error path, which is exactly when a stuck-looking spinner is most misleading.
/// Use with `ProbeContinueButton` for probes whose layout is too bespoke for `ProbeScaffold`.
extension View {
    func probeTask(settled: Binding<Bool>, _ probe: @escaping () async -> Void) -> some View {
        task {
            await probe()
            settled.wrappedValue = true
        }
    }
}

/// Shared chrome for the uniform one-shot probes: spinner while running, status text, and a
/// Continue button once settled.
///
/// ⚠ Not for a route that keeps *serving* after its `.task` returns (a pairing host, say): "task
/// returned" is not "settled" there, and a Continue button would cut the session it exists to host.
struct ProbeScaffold: View {
    let status: String
    var mono: Bool = false
    var textAlignment: TextAlignment = .center
    var horizontalPadding: CGFloat = 32
    let probe: () async -> Void

    @State private var settled = false

    var body: some View {
        VStack(spacing: 16) {
            if !settled {
                ProgressView()
            }
            Text(status)
                .font(mono ? .system(.callout, design: .monospaced) : .callout)
                .multilineTextAlignment(textAlignment)
                .padding(.horizontal, horizontalPadding)
                .textSelection(.enabled)
            if settled {
                ProbeContinueButton()
            }
        }
        .probeTask(settled: $settled, probe)
    }
}

// RT-5 verification harness (no tap tooling on the simulator): drive a *real* genesis to
// completion over the proxy — passphrase floor → createRealm → setUpUnlock — so it writes the
// durable unlock credential. A subsequent plain launch must then land in MainTabs through
// `ControlRootView`'s durable probe (`ControlSurface.isRealmDevice()`), proving the realm-device
// state survives process death. Reached via `-genesisAutoComplete`; the real runtime is installed
// (this is not a screenshot route). No effect in release.
struct GenesisAutoCompleteView: View {
    @State private var status = "Creating realm…"

    var body: some View {
        ProbeScaffold(status: status) {
            let pass = "rt5-verification-passphrase"
            let vm = ControlSurface.shared.genesisViewModel()
            vm.onChoosePassphrase()
            vm.onUnlockPassphraseChanged(value: pass)
            vm.onUnlockPassphraseConfirmChanged(value: pass)
            vm.onUnlockSetupSubmit()
            for await s in vm.uiState {
                if let err = ControlSurface.shared.genesisErrorMessage(state: s) {
                    status = "Genesis error: \(err)"
                    break
                }
                if ControlSurface.shared.genesisStepKey(state: s) == "set_up" {
                    status = "Realm created + persisted.\nRelaunch (no args) to verify the durable probe."
                    break
                }
            }
        }
    }
}

// Unlock verification harness (no tap tooling): drive the returning-user unlock with the genesis
// verification passphrase, then render MainTabs on success — proving the Locked → unlock → main
// path on the simulator. Run after a `-genesisAutoComplete` + relaunch has left the realm sealed.
// Reached via `-unlockAutoComplete`; the real runtime is installed (not a screenshot route).
struct UnlockAutoCompleteView: View {
    @State private var status = "Unlocking…"
    @State private var done = false

    var body: some View {
        if done {
            MainTabsView()
        } else {
            ProbeScaffold(status: status) {
                let vm = ControlSurface.shared.unlockViewModel()
                vm.onPassphraseChanged(value: "rt5-verification-passphrase")
                vm.onUnlock()
                for await s in vm.uiState {
                    if let err = ControlSurface.shared.unlockErrorMessage(state: s) {
                        status = "Unlock error: \(err)"
                        break
                    }
                    if s.unlocked {
                        done = true
                        break
                    }
                }
            }
        }
    }
}

// "Lock now" primitive verification (no tap tooling): unlock the (sealed) realm to reach active,
// then call lockRealm() and report the resulting landing state — which must be "locked", proving
// the re-seal works in-session and ControlRootView would route back to the unlock screen. Reached
// via `-lockRoundTrip` over the real runtime (run after a `-genesisAutoComplete`).
struct LockRoundTripView: View {
    @State private var status = "Unlocking → locking…"

    var body: some View {
        ProbeScaffold(status: status) {
            // 1. Unlock so the realm is active (keys loaded) in this process.
            let vm = ControlSurface.shared.unlockViewModel()
            vm.onPassphraseChanged(value: "rt5-verification-passphrase")
            vm.onUnlock()
            for await s in vm.uiState { if s.unlocked { break } }
            // 2. Lock now.
            _ = try? await ControlSurface.shared.lockRealm()
            // 3. The landing decision must now be "locked".
            let key = (try? await ControlSurface.shared.landingStateKey()) ?? "?"
            status = key == "locked"
                ? "Lock now ✓ — landing is now \"locked\" (re-seal works)."
                : "Unexpected landing after lock: \(key)"
        }
    }
}

// P2a teardown-symmetry test (no tap tooling): clear the whole Drive provisioning — the
// `epc-session` item AND the route-1 key material — the way a realm reset / sign-out does. Then
// launch drive-ios: its gate must detect the vanished material, purge its own adopted custody
// (DriveLocalRuntime.deprovision), and fall back to Connect-to-Control. Reached via
// `-clearDriveSession`.
struct ClearDriveSessionView: View {
    @State private var status = "Clearing Drive provisioning…"

    var body: some View {
        ProbeScaffold(status: status) {
            ControlSurface.shared.clearDriveSession()
            status = "✓ Drive provisioning cleared (epc-session item + route-1 key material).\n"
                + "Launch drive-ios — it must deprovision and show Connect to Control."
        }
    }
}

// Fresh-device wipe (no tap tooling): purge ALL at-rest Tenshu identity — Control's Keychain
// custody bucket (device id + A4, wrappers, phrase, relay latches) + the cross-app handoff
// items — so the NEXT launch is a genuinely fresh device. The Keychain survives app uninstall,
// so without this a reinstalled Control resurrects a stale identity whose registered-latch
// blocks re-registration against a wiped relay. Reached via `-wipeDeviceState`; relaunch
// afterwards for a fresh genesis.
struct WipeDeviceStateView: View {
    @State private var status = "Wiping device state…"

    var body: some View {
        ProbeScaffold(status: status) {
            // Suspends now: the wipe first retires this device's own roster row and waits for that
            // tombstone to be pushed, since the key that signs the push is one of the things it is
            // about to delete. `ProbeScaffold.probe` was already `async`, so the await is free here.
            let result = (try? await ControlSurface.shared.wipeDeviceState()) ?? "wipe failed"
            status = "✓ Device state \(result).\nRelaunch for a fresh genesis."
        }
    }
}
#endif

// The post-onboarding home: the four Control sections bound to their real view
// models (C2b).
struct MainTabsView: View {
    var onLock: () -> Void = {}
    /// Settings' "Forget this device" discarded the identity — the root routes back to onboarding.
    var onForget: () -> Void = {}

    /// N6: hoisted above `AdaptiveAppShell` so a placement change (rotation, Split View resize)
    /// cannot take the selection with it.
    @State private var selection = "home"

    /// The sync-activity capsule (`ios-sync-activity-indicator.md` §6a.2): observed ONCE here, drawn
    /// by the shell over whichever destination is showing.
    @StateObject private var syncActivity = SyncActivityModel()

    var body: some View {
        AdaptiveAppShell(selection: $selection, destinations: [
            AppDestination(id: "home", title: "Home", symbol: "house", filledSymbol: "house.fill") {
                HomeView()
            },
            AppDestination(id: "data", title: "Data", symbol: "tray.full", filledSymbol: "tray.full.fill") {
                DataView()
            },
            AppDestination(id: "apps", title: "Apps", symbol: "square.grid.2x2",
                           filledSymbol: "square.grid.2x2.fill") {
                AppsView()
            },
            AppDestination(id: "settings", title: "Settings", symbol: "gearshape",
                           filledSymbol: "gearshape.fill") {
                SettingsView(onLock: onLock, onForget: onForget)
            }
        ],
        // Control is the runtime: it has no not-connected bar. This view is the `.ready` phase only,
        // so the capsule is never over onboarding, the reinstall gate or the locked screen.
        // ⚠ `showsStorageBar: false` is true today: Control mounts no `RealmStorageBanner` (its
        // Services screen states the plan), so the capsule must not yield to a bar nobody draws.
        destinationOverlay: AnyView(SyncActivityCapsuleSlot(
            model: syncActivity,
            hostProblem: false,
            showsStorageBar: false,
            hidden: false
        )))
        .syncActivityObservation(syncActivity)
    }
}
