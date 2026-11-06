import SwiftUI
import TenshuSdk

// The recovery phrase (A9), shown to the user — the thing iOS was missing entirely.
//
// ## ⚠⚠ Why this did not exist
//
// C2c shipped genesis as "create realm → mint master key → **silent** BIP-39 phrase", and left the
// Settings row as a placeholder pointing at a reveal it never wired (`"Set up in onboarding"`). The
// shared recovery *banner* is not bound on iOS either. So the phrase was minted, sealed, and
// unreachable by any route: an iOS-only user could never see the words their realm depends on.
//
// ⚠ Not a theoretical gap. It is the upstream cause of a real A1 loss — a device held the only copy,
// its owner had never been shown it, and a discard destroyed it
// (`admission-revocation-names-the-capability.md` §5b.12). Until RG-2 shipped, the only way to see
// your recovery phrase on iOS was to start deleting your identity.
//
// ⚠ §5b.12's link 1 reads "onboarding lets the phrase go *unsaved* — the deferral is deliberate
// friction". On iOS it was never *offered*, so that mitigation never applied. This restores the
// premise: the phrase is offered, and deferring is then the user's choice.
@MainActor
final class RecoveryPhraseModel: ObservableObject {
    @Published var phrase: String? = nil
    @Published var loaded = false
    @Published var copied = false

    func load() async {
        // ⚠ `try?` folds a bridge throw into the same `nil` a joiner produces. Both mean "this device
        // has no phrase to show", and the copy says so honestly rather than implying a failure.
        phrase = try? await ControlSurface.shared.recoveryPhrase()
        loaded = true
    }

    func copy() {
        guard let phrase else { return }
        UIPasteboard.general.string = phrase
        copied = true
    }
}

/// The words plus a copy action. `startRevealed` is the difference between the two call sites:
/// onboarding shows them outright (the user just made the realm and is alone with it), Settings
/// hides them behind a tap (a phone on a desk in an office is the normal case there).
struct RecoveryPhraseView: View {
    let startRevealed: Bool
    /// Shown under the words. Onboarding explains what to do; Settings explains what it is.
    let caption: String

    /// Ph-reauth (§6.1, RA-2): ask for Face ID / Touch ID / passcode before the words appear.
    ///
    /// ⚠⚠ **Explicit rather than derived from `startRevealed`.** The two happen to coincide today,
    /// but they answer different questions — "is it already open?" versus "must the user prove
    /// presence to open it?" — and a later call site that wants one without the other would
    /// silently get both.
    ///
    /// ⚠⚠ **Onboarding passes `false` deliberately.** At genesis the user has just created the
    /// realm and is alone with a phone they unlocked seconds ago; there is nothing to prove and
    /// possibly nothing enrolled yet. Prompting there would be friction with no threat behind it,
    /// and this screen's whole purpose is that the phrase gets *seen* (`§5b.12` — never being
    /// asked is how a realm lost its master identity).
    let requireAuthToReveal: Bool

    @StateObject private var model = RecoveryPhraseModel()
    @State private var revealed: Bool
    @Environment(\.scenePhase) private var scenePhase

    init(startRevealed: Bool, caption: String, requireAuthToReveal: Bool = false) {
        self.startRevealed = startRevealed
        self.caption = caption
        self.requireAuthToReveal = requireAuthToReveal
        _revealed = State(initialValue: startRevealed)
    }

    /// Gated reveal. Copy is not separately gated **because it cannot be reached while concealed** —
    /// the Copy button only renders once `revealed`. (The Compose twin has to gate copy explicitly:
    /// there the icon is always present, and copying discloses the phrase without rendering it.)
    private func reveal() {
        guard requireAuthToReveal else {
            revealed = true
            return
        }
        Task {
            if await Reauth.confirm(reason: ReauthStrings.recoveryPhraseReason) {
                revealed = true
            }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if !model.loaded {
                ProgressView()
            } else if let phrase = model.phrase {
                if revealed {
                    Text(phrase)
                        .font(.system(.body, design: .monospaced))
                        .textSelection(.enabled)
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.secondary.opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                } else {
                    Button(RecoveryPhraseStrings.reveal) { reveal() }
                        .buttonStyle(.bordered)
                }

                Text(caption)
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                if revealed {
                    Button(model.copied ? RecoveryPhraseStrings.copied : RecoveryPhraseStrings.copy) {
                        model.copy()
                    }
                    .buttonStyle(.bordered)
                    .disabled(model.copied)
                }
            } else {
                // A joiner. Not an error — say what is true, and where the phrase does live.
                Text(RecoveryPhraseStrings.noPhrase)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
        }
        .task { await model.load() }
        // ⚠ Re-conceal when the app leaves the foreground, or the gate is a formality: a phrase
        // left on screen rides into the app-switcher snapshot and into whoever picks the device up
        // next. `.inactive`, not `.background` — iOS takes that snapshot on the way out, so waiting
        // for `.background` re-conceals *after* the picture has been taken. (The soft app-lock's
        // own overlay has the same bug and is a separate fix; this screen must not depend on it,
        // since it only covers users who have "Require unlock" on.)
        //
        // Guarded on `requireAuthToReveal` so onboarding is untouched: hiding the words there would
        // send the user back to a button on every app switch, on the one screen whose job is that
        // the phrase gets seen.
        .onChange(of: scenePhase) { phase in
            guard requireAuthToReveal, phase != .active else { return }
            revealed = false
            model.copied = false
        }
    }
}

/// The Settings destination — the row that was a placeholder.
struct RecoveryPhraseScreen: View {
    var body: some View {
        Form {
            Section {
                RecoveryPhraseView(
                    startRevealed: false,
                    caption: RecoveryPhraseStrings.settingsCaption,
                    // Ph-reauth: the Settings route is the one an unattended phone exposes.
                    requireAuthToReveal: true,
                )
            } header: {
                Text(RecoveryPhraseStrings.title)
            } footer: {
                Text(RecoveryPhraseStrings.settingsFooter)
            }
        }
        .navigationTitle(RecoveryPhraseStrings.title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

enum RecoveryPhraseStrings {
    static let title = "Recovery phrase"
    static let reveal = "Show my recovery phrase"
    static let copy = "Copy"
    static let copied = "Copied"
    // ⚠ Bare apostrophes, matching the project's i18n convention.
    static let onboardingCaption = "Write these words down and keep them somewhere safe. They are the only way back into your account if you lose every device."
    static let settingsCaption = "These words are the only way back into your account if you lose every device."
    static let settingsFooter = "Anyone with these words has full access to your account. Keep them offline — a password manager or paper, not a note or a message."
    static let noPhrase = "This device joined your account from another device, so it doesn't hold the recovery phrase. Open Recovery phrase on the device you first set up."
    static let onboardingSkip = "I'll do this later"
    static let onboardingDone = "I've saved it"
}
