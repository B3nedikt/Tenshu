import SwiftUI
import TenshuSdk

// RG-1 (docs/proposals/ios-identity-reinstall-gate.md §3): name the reinstall, and offer the
// alternative, before the user hits a lock screen that cannot explain itself.
//
// ## Why this screen exists
//
// On iOS the Keychain outlives the app, so deleting Control and reinstalling it does NOT produce a
// fresh device — the realm identity (device id, A4 signing key, unlock wrapper) is still sitting
// there and the app silently resumes it. Two things go wrong with that:
//
//  1. The user who reinstalled *to start over* does not start over, and is never told.
//  2. The app comes up **locked**, asking for a passphrase that a user who is reinstalling has
//     often already lost — with no visible way forward.
//
// ⚠⚠ It becomes strictly worse under option F (`admission-revocation-names-the-capability.md`
// §4.3): a device whose identity was revoked returns presenting the SAME device id, which F
// refuses permanently. Today that path silently self-heals; after F it is a dead end. This gate is
// F's prerequisite for exactly that reason.
//
// ## What it is not
//
// Not a nag: it is shown **once per install** (the sentinel is written when the fork resolves,
// whichever way), because a prompt on every launch trains people to dismiss it. And "Continue" is
// the default (D5) — a reinstall is a normal, safe event and the screen must not imply a fault.
//
// D2 shipped as option (a) — a plain destructive confirm. **RG-2 replaced it**
// (`ReinstallPhraseRescueSheet`): destroying A1 material now puts the recovery phrase in front of
// the user first, offering the unlock when the phrase is wrapped behind it.
//
// ⚠⚠ The naive form of RG-2 does not work here and the reason is worth keeping. The phrase is
// directly readable only on a device with **no** unlock wrapper — which is also a device that never
// comes up locked, i.e. one whose user was not stuck. On the trapped device the phrase is behind the
// lock, so a port of desktop's `ForgetDeviceDialog` would render an empty warning in exactly the
// case that matters. See `ControlSurface.residentPhraseState`.
struct ReinstallGateView: View {
    /// Continue as this device — nothing is destroyed; proceed to the normal locked/ready routing.
    let onContinue: () -> Void
    /// The identity was discarded; the caller routes back to onboarding as a genuinely fresh device.
    let onStartFresh: () -> Void

    @State private var showDiscardConfirm = false
    @State private var working = false
    @State private var outcome: String? = nil

    var body: some View {
        OnboardingScaffold {
            if working {
                ProgressView()
                    .tint(OnboardingColors.primary)
                vGap(16)
                Text(ReinstallGateStrings.discarding)
                    .font(.body)
                    .foregroundStyle(.secondary)
            } else {
                content
            }
        }
        // RG-2: a sheet rather than a confirmationDialog, because the rescue path needs a phrase
        // block, an acknowledgement and (usually) an unlock — none of which a dialog can host.
        .sheet(isPresented: $showDiscardConfirm) {
            ReinstallPhraseRescueSheet(
                onDiscard: {
                    showDiscardConfirm = false
                    discard()
                },
                onCancel: { showDiscardConfirm = false },
            )
        }
    }

    @ViewBuilder private var content: some View {
        MarkOutline(dimension: 96)
        vGap(24)
        Text(ReinstallGateStrings.title)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
        vGap(16)
        Text(ReinstallGateStrings.body)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)

        // ⚠ Surfaced rather than swallowed: the discard retires this device's roster row first and
        // waits for that tombstone to be pushed, which cannot succeed offline. The wipe proceeds
        // anyway (by design — people discard precisely when things are broken), so the leftover row
        // becomes an announced outcome with a named remedy instead of a silent one.
        if let outcome {
            vGap(16)
            Text(outcome)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }

        vGap(32)
        Button(action: onContinue) {
            Text(ReinstallGateStrings.continueAsThisDevice)
        }
        .buttonStyle(PrimaryButtonStyle())

        vGap(24)
        Button(role: .destructive, action: { showDiscardConfirm = true }) {
            Text(ReinstallGateStrings.startFresh)
                .font(.body)
                .foregroundStyle(.red)
        }
        .buttonStyle(.plain)
    }

    private func discard() {
        working = true
        // ⚠ Awaited, not fire-and-forget: `wipeDeviceState` retires this device's own roster row
        // and waits for the tombstone to actually leave BEFORE deleting the A4 key that signs the
        // push. Bounded (~8s worst case) and it never blocks the wipe itself.
        Task {
            let result = (try? await ControlSurface.shared.wipeDeviceState()) ?? ""
            await MainActor.run {
                working = false
                outcome = result.isEmpty ? nil : result
                onStartFresh()
            }
        }
    }
}

enum ReinstallGateStrings {
    static let title = "This device already has an identity"
    // ⚠ Says what is true and what the choice is, without implying a fault. The Keychain detail is
    // the one thing a user cannot possibly infer, so it is stated plainly rather than softened.
    static let body = "Tenshu found an existing device identity in this device's keychain — it stays there when the app is deleted, so reinstalling picks up where you left off. You can carry on as this device, or discard it and set this device up from scratch."
    static let continueAsThisDevice = "Continue as this device"
    static let startFresh = "Start fresh on this device"
    static let discarding = "Discarding this identity…"
    // ⚠ The confirm copy moved to `PhraseRescueStrings` with RG-2 — the sheet owns the whole
    // destructive conversation now, so keeping a second copy here would let the two drift.
}
