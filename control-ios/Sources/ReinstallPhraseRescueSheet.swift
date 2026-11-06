import SwiftUI
import TenshuSdk

// RG-2 (`admission-revocation-names-the-capability.md` §5b.12.2): a flow that destroys A1 material
// must put the recovery phrase in front of the user first, so the phrase *resolves* the risk rather
// than the dialog deferring it.
//
// ## ⚠⚠ Why this is not desktop's `ForgetDeviceDialog` ported
//
// Desktop can just read the phrase. On iOS it usually cannot, and the reason inverts the feature:
//
//  • `getRecoveryPhrase()` reads a disk slot that `setUpLocalUnlock` / `setUpBiometricUnlock`
//    **destructively delete** once the phrase is wrapped under a credential, falling back to an
//    in-memory field that is empty on a fresh process — and a reinstall *is* a fresh process.
//  • The reinstall gate renders ahead of the locked screen.
//
// So the phrase is directly readable exactly when the device has **no** unlock wrapper — which is
// also the case where the app never would have come up locked, i.e. where the user was not stuck.
// On the trapped device it sits behind the lock. Hence: offer the unlock, and when the phrase still
// cannot be produced, say so instead of discarding in silence.
//
// ⚠ `behindLock` means *may be*, never *is*. `setUpLocalUnlock` wraps the recovery phrase on a
// master device and the derived-keys tuple on a joiner, and both leave the same wrapper key — so
// nothing distinguishes the roles before unlocking. Only the post-unlock read can answer, and for a
// joiner the honest answer is "this device never held one", not "we could not read it".
@MainActor
final class PhraseRescueModel: ObservableObject {
    enum Step: Equatable {
        /// The opening choice, shaped by `ControlSurface.residentPhraseState()`.
        case decide
        /// The user asked to unlock so the phrase can be rescued.
        case unlocking
        /// Unlock finished. `phrase == nil` means this device genuinely never held one.
        case revealed
    }

    @Published var step: Step = .decide
    @Published var phrase: String? = nil
    @Published var acknowledged = false

    /// `"available"` | `"behind_lock"` | `"none"` — read once, before the runtime is up.
    let state: String

    init() {
        state = ControlSurface.shared.residentPhraseState()
        if state == "available" {
            phrase = ControlSurface.shared.residentRecoveryPhrase()
        }
    }

    /// Nothing to lose here — a device that never held A1, or one whose phrase we already showed.
    var nothingToRescue: Bool { state == "none" }

    /// The destructive button is gated only when there is a phrase in front of the user to
    /// acknowledge. Asking a joiner to tick a box about a phrase it never had is theatre, and
    /// theatre is what trains people to click through the acknowledgements that matter.
    var canDiscard: Bool { phrase == nil || acknowledged }

    func reveal() async {
        // ⚠ `try?` collapses a bridge throw into the same `nil` a joiner produces. That is the
        // right shape here: both mean "this device cannot show you a phrase", and the copy already
        // says the honest thing for that case.
        phrase = try? await ControlSurface.shared.revealPhraseAfterUnlock()
        step = .revealed
    }
}

struct ReinstallPhraseRescueSheet: View {
    @StateObject private var model = PhraseRescueModel()
    @StateObject private var unlock = LockedModel()

    let onDiscard: () -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    switch model.step {
                    case .decide:   decide
                    case .unlocking: unlocking
                    case .revealed: revealed
                    }
                }
                .padding(24)
            }
            .navigationTitle(PhraseRescueStrings.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(PhraseRescueStrings.cancel, action: onCancel)
                }
            }
        }
        // ⚠⚠ Both of these are load-bearing and neither is checkable by the compiler. Without the
        // `.task` the unlock view model emits into nothing, so the passphrase field, the error text
        // and the Face ID affordance all stay frozen at their initial values. Without the
        // `.onChange` a *successful* unlock does nothing at all: the sheet sits on the unlock step
        // while the phrase it exists to show is sitting right there, unread.
        .task { await unlock.observe() }
        .onChange(of: unlock.unlocked) { didUnlock in
            if didUnlock { Task { await model.reveal() } }
        }
    }

    // MARK: - Steps

    @ViewBuilder private var decide: some View {
        Text(PhraseRescueStrings.irreversible)
            .font(.body)
            .foregroundStyle(.secondary)
        // ⭐ Q50 (B.18.8): the forget removes a kept copy of damaged data; say so first.
        if let warning = StorageStateSurface.shared.damagedCopyForgetWarning() {
            Text(warning)
                .font(.body)
                .foregroundStyle(.orange)
        }

        if model.state == "available", let phrase = model.phrase {
            phraseBlock(phrase)
            acknowledgeToggle
            discardButton()
        } else if model.state == "behind_lock" {
            warning(PhraseRescueStrings.behindLock)
            Button(PhraseRescueStrings.unlockAndShow) { model.step = .unlocking }
                .buttonStyle(PrimaryButtonStyle())
            // ⚠ Still offered. The user may have the phrase saved elsewhere, or may simply be
            // unable to unlock — which is the situation this whole gate exists for. Removing the
            // escape hatch would trap them on the screen that was supposed to free them.
            discardButton(label: PhraseRescueStrings.discardAnyway)
        } else {
            warning(PhraseRescueStrings.neverHeld)
            discardButton()
        }
    }

    @ViewBuilder private var unlocking: some View {
        Text(PhraseRescueStrings.unlockBody)
            .font(.body)
            .foregroundStyle(.secondary)

        SecureField(PhraseRescueStrings.passphrase, text: Binding(
            get: { unlock.passphrase },
            set: { unlock.setPassphrase($0) }
        ))
        .textFieldStyle(.roundedBorder)
        .textContentType(.password)

        if let error = unlock.errorMessage {
            Text(error).font(.callout).foregroundStyle(.red)
        }

        Button(PhraseRescueStrings.unlock) { unlock.submit() }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(unlock.isLoading || unlock.passphrase.isEmpty)

        if unlock.biometricAvailable && unlock.biometricEnrolled {
            Button(PhraseRescueStrings.useBiometric) { unlock.submitBiometric() }
                .buttonStyle(.plain)
        }

        discardButton(label: PhraseRescueStrings.discardAnyway)
    }

    @ViewBuilder private var revealed: some View {
        if let phrase = model.phrase {
            warning(PhraseRescueStrings.lastChance)
            phraseBlock(phrase)
            acknowledgeToggle
        } else {
            // Unlocked fine, and there is still no phrase — a joiner. Say that, rather than
            // implying a failure the user might waste time retrying.
            warning(PhraseRescueStrings.neverHeld)
        }
        discardButton()
    }

    // MARK: - Pieces

    @ViewBuilder private func warning(_ text: String) -> some View {
        Text(text)
            .font(.callout)
            .foregroundStyle(.red)
    }

    @ViewBuilder private func phraseBlock(_ phrase: String) -> some View {
        // Shown, not behind a reveal: at this moment the phrase is about to stop existing on this
        // device, so a second tap between the user and it is friction with no upside.
        Text(phrase)
            .font(.system(.body, design: .monospaced))
            .textSelection(.enabled)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.secondary.opacity(0.12))
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    @ViewBuilder private var acknowledgeToggle: some View {
        Toggle(isOn: Binding(
            get: { model.acknowledged },
            set: { model.acknowledged = $0 }
        )) {
            Text(PhraseRescueStrings.acknowledge).font(.callout)
        }
        .toggleStyle(.switch)
    }

    @ViewBuilder private func discardButton(label: String = PhraseRescueStrings.startFresh) -> some View {
        Button(role: .destructive, action: onDiscard) {
            Text(label).foregroundStyle(model.canDiscard ? .red : .secondary)
        }
        .buttonStyle(.plain)
        .disabled(!model.canDiscard)
    }
}

enum PhraseRescueStrings {
    static let title = "Start fresh on this device?"
    static let irreversible = "This device's identity and its access to your realm are removed. To use Tenshu here again you'll set this device up as a new one — with your recovery phrase, or from another device that's still signed in. This can't be undone."
    // ⚠ "may be", not "is": a joiner has the same unlock wrapper and never held a phrase, and
    // nothing tells the two apart until the realm is unlocked.
    static let behindLock = "Your recovery phrase may be stored behind this device's lock. Unlock to check before discarding — if it is here and you have no other copy, it goes with the identity."
    static let neverHeld = "This device doesn't hold your recovery phrase, so discarding it here loses nothing you can't get back from another device that's still signed in."
    static let lastChance = "Write this down now. After you discard this identity it is gone from this device."
    static let unlockAndShow = "Unlock and show my phrase"
    static let unlockBody = "Unlock the realm so this device can show you the recovery phrase before it is discarded."
    static let unlock = "Unlock"
    static let useBiometric = "Use Face ID instead"
    static let passphrase = "Passphrase"
    static let acknowledge = "I have written this down"
    static let startFresh = "Start fresh on this device"
    static let discardAnyway = "Discard without my phrase"
    static let cancel = "Cancel"
}
