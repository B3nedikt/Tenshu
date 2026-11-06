import SwiftUI
import TenshuSdk

// App-global host-side pairing approval — the iOS twin of the Compose `HostPairingApprovalDialog`
// mounted at the App root (`control-shared/.../ui/App.kt`).
//
// Why it exists on top of the in-tab prompt in `PairingView`: the host session is process-lifetime.
// `HostPairingMonitor` keeps polling after the "Add a device" sheet is dismissed, so the request a
// joiner posts a minute later — while the user is on Home, or in Settings — has to land somewhere.
// On Compose it overlays any destination; here it is an alert on the ready-phase root.
//
// ⚠⚠ Not shown while the pairing surface is up. Both surfaces read ONE monitor, so a request is
// visible in both at once, and SwiftUI would stack this alert on top of the in-tab prompt the user
// is already answering. `PairingSurfacePresence` is how the pairing sheet says "I have this".
//
// ⚠ Deliberately no notification: an approval that arrives while Control is *closed* cannot be
// surfaced at all on iOS (no persistent background process — `ios-proxy-architecture.md` §9.4), and
// approving a device from a lock-screen button without the SAS in front of you is the wrong
// ceremony (`ios-control-notifications-and-shares.md` §3, `docs/sync/pairing-flow.md`).

/// Whether the pairing wizard is on screen. Set by `PairingView`; read here to stay out of its way.
@MainActor
final class PairingSurfacePresence: ObservableObject {
    static let shared = PairingSurfacePresence()

    /// A count, not a flag: the wizard can be presented from Settings while an onboarding instance
    /// is still tearing down, and a plain `false` on the loser's `onDisappear` would un-suppress
    /// the alert underneath a live prompt.
    ///
    /// ⚠ If the count ever drifted (an `onAppear` with no matching `onDisappear`), the failure is
    /// a *suppressed* root alert — the in-tab prompt still answers every request the wizard is open
    /// for — never two prompts over one request. That is the direction to keep it drifting in.
    @Published private(set) var visibleCount = 0

    var visible: Bool { visibleCount > 0 }

    func entered() { visibleCount += 1 }
    func left() { visibleCount = max(0, visibleCount - 1) }
}

@MainActor
final class HostPairingApprovalModel: ObservableObject {
    @Published var deviceName: String?
    @Published var sas: String?
    @Published var errorMessage: String?

    private let bound = ControlSurface.shared.hostPairingApprovalBound()

    init() { apply(bound.snapshots.value) }

    func observe() async {
        for await approval in bound.snapshots {
            apply(approval)
        }
    }

    func approve() { bound.approve() }
    func deny() { bound.deny() }

    private func apply(_ approval: ControlHostPairingApproval?) {
        deviceName = approval?.deviceName
        sas = approval?.sas
        errorMessage = approval?.error
    }
}

extension View {
    /// Present the app-global approval over this view. Attach once, at the ready-phase root.
    func hostPairingApprovalHost() -> some View {
        modifier(HostPairingApprovalHost())
    }
}

private struct HostPairingApprovalHost: ViewModifier {
    @StateObject private var model = HostPairingApprovalModel()
    @ObservedObject private var presence = PairingSurfacePresence.shared

    /// Non-nil only when there is a request AND the wizard isn't already showing it.
    private var pending: String? {
        presence.visible ? nil : model.deviceName
    }

    func body(content: Content) -> some View {
        content
            .task { await model.observe() }
            .alert(
                pending.map { String(format: PairingStrings.hostJoinRequest, $0) } ?? "",
                isPresented: Binding(get: { pending != nil }, set: { _ in }),
                actions: {
                    // SAS-bearing: no cancel role, no tap-outside — it resolves through Approve or
                    // Deny, exactly like the Compose modal's `dismissOnClickOutside = false`.
                    Button(PairingStrings.hostApprove) { model.approve() }
                    Button(PairingStrings.hostDeny, role: .destructive) { model.deny() }
                },
                message: {
                    if let error = model.errorMessage {
                        Text(error)
                    } else if let sas = model.sas {
                        Text("\(PairingStrings.hostSasPrompt)\n\(sas.count >= 6 ? "\(sas.prefix(3)) \(sas.dropFirst(3))" : sas)\n\n\(PairingStrings.sasVersionHint)")
                    }
                }
            )
    }
}
