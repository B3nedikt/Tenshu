import SwiftUI
import TenshuSdk

// The Control-gated app root (proposal §11.6; gate flipped by ios-drive-parity.md P1d).
// Probes the shared Keychain for the route-1 KEY MATERIAL Control provisioned — the thing
// that actually makes Drive work on this device (not the older C3 presence token) — and:
//
//   provisioned → install the in-process route-1 runtime + PairedAppView (live Files)
//   absent      → ConnectToControlView (the single forward-to-Control screen)
//
// No QR, no SAS — a first-party app does not run its own pairing ceremony (§11.6).
// The §10 QR+SAS pairing tab lives in the DEV_HARNESS scheme instead.
struct RootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var phase: Phase = .checking

    enum Phase: Equatable {
        case checking
        case connected(ControlSession)
        case absent
    }

    var body: some View {
        Group {
            switch phase {
            case .checking:
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .connected:
                PairedAppView()
            case .absent:
                ConnectToControlView(
                    appName: "Drive",
                    purpose: "keep your files end-to-end encrypted and synced across your devices",
                    onRecheck: recheck,
                    devStub: Self.devStub
                )
            }
        }
        .task { recheck() }
        .onChange(of: scenePhase) { newPhase in
            // Returning from Control (after the deep link) re-checks the shared
            // Keychain, so a freshly-provisioned session forwards into Files.
            if newPhase == .active { recheck() }
            // P6a: leaving the foreground arms the next background sync wake.
            if newPhase == .background { BackgroundSync.scheduleIfProvisioned() }
        }
        #if DEV_HARNESS
        // The Pair tab activates/tears down the live EPC client on a sibling tab; re-check on its
        // signal so the App tab routes in/out of Files without a scenePhase change (Tier 2 dev path).
        .onReceive(NotificationCenter.default.publisher(for: .liveSessionChanged)) { _ in recheck() }
        #endif
    }

    /// The shared screen's dev affordance (ADR 0022). ⚠ Resolved as a conditional constant
    /// rather than `#if`-ing the call site: `ControlSessionStore.provisionDevStub` only exists
    /// under `DEV_HARNESS`, so naming it unconditionally fails the shipping build — while
    /// duplicating the whole `ConnectToControlView(...)` initialiser across both branches is
    /// how the two copies drift.
    #if DEV_HARNESS
    private static let devStub: (() -> Void)? = { ControlSessionStore.provisionDevStub() }
    #else
    private static let devStub: (() -> Void)? = nil
    #endif

    private func recheck() {
        #if DEV_HARNESS
        // A dev EPC pairing (Pair tab) installed the live client — ride it into Files. The shipping
        // path never sets this; it routes only on the Control-provisioned key material.
        if LiveClientSession.shared.isActive {
            phase = .connected(ControlSession(handle: "epc-live"))
            return
        }
        #endif
        // P1d shipping gate: route on the route-1 key material. Provisioned → install the
        // in-process runtime (idempotent; adopts Control's identity, sync goes live) and
        // forward into Files.
        if DriveLocalRuntime.shared.isProvisioned() {
            DriveReadSurface.shared.installLocalRuntime()
            phase = .connected(ControlSession(handle: "route1-local"))
            return
        }
        // P2a teardown symmetry: the material is GONE but Drive still holds adopted custody —
        // Control cleared the provisioning deliberately (reset / sign-out / revocation). Purge
        // Drive's own at-rest copies (threat-model A4 duty) so the identity cannot resurrect,
        // and fall through to the connect screen. The shared replica is untouched; a
        // re-provision goes live again with no data loss.
        if DriveLocalRuntime.shared.isInstalled() || DriveLocalRuntime.shared.hasResidualCustody() {
            DriveLocalRuntime.shared.deprovision()
        }
        #if DEV_HARNESS
        // Dev-stub fallback (DRIVE_DEV_PROVISION_CONTROL / "Simulate Control set-up"): Files
        // over the fakes, for UI work with no Control on the sim — the XCUITests launch through
        // it. Never reachable in shipping. ⚠ Control no longer mints a presence token into this
        // item (retired 2026-11-05), so what it finds here is the dev stub — or, while Control's
        // "Connect an app" sheet is open, that sheet's invite.
        if let stub = ControlSessionStore.current() {
            phase = .connected(stub)
            return
        }
        #endif
        phase = .absent
    }
}
