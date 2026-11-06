import SwiftUI
import TenshuSdk

// Returning-user unlock screen (Control-on-iOS, the LandingState.Locked branch —
// docs/proposals/ios-control-handover.md §C4-RT). A genesised/recovered device reopened in a
// fresh process is *sealed*: the unlock wrapper is persisted but the master keys aren't derived
// until the passphrase is entered. RT-5 made the launch probe durable and RT-6 let genesis
// actually complete on the sim — which surfaced that a reopened device landed in MainTabs with a
// locked, undecryptable realm and no way to unlock. This screen closes that gap.
//
// Mirrors the canonical Compose `LockedScreen` ("Welcome back") in the OnboardingKit vocabulary.
// When biometric unlock has been enrolled from Settings (ControlSurface.enableBiometricUnlock), the
// real IosBiometricUnlockDelegate reports available+enrolled and this screen leads with a Face ID /
// Touch ID prompt (auto-presented on entry), with the passphrase as the fallback; otherwise it is
// the passphrase-only floor. The path decision mirrors LockedScreen.kt (biometricAvailable &&
// biometricEnrolled && !passphraseExpanded → biometric primary).

@MainActor
final class LockedModel: ObservableObject {
    @Published var passphrase = ""
    @Published var initializing = true
    @Published var isLoading = false
    @Published var unlocked = false
    @Published var errorMessage: String? = nil
    @Published var tooManyTries = false
    @Published var biometricAvailable = false
    @Published var biometricEnrolled = false
    @Published var passphraseExpanded = false

    // Auto-present the Face ID sheet at most once per screen appearance (the VM has its own
    // in-flight guard, but we also don't want to re-fire it every uiState emission).
    private var didAutoPrompt = false

    private let vm = ControlSurface.shared.unlockViewModel()

    // Lead with Face ID only while it's available, enrolled, and the user hasn't fallen back to
    // the passphrase (a non-success biometric outcome flips passphraseExpanded on in the VM).
    var biometricPrimary: Bool { biometricAvailable && biometricEnrolled && !passphraseExpanded }

    func observe() async {
        for await s in vm.uiState {
            initializing = s.initializing
            isLoading = s.isLoading
            unlocked = s.unlocked
            errorMessage = ControlSurface.shared.unlockErrorMessage(state: s)
            biometricAvailable = s.biometricAvailable
            biometricEnrolled = s.biometricEnrolled
            passphraseExpanded = s.passphraseExpanded
            // Canonical LockedScreen nudges to recovery after 5 failed passphrase tries.
            tooManyTries = s.failureCount >= 5

            // Auto-present Face ID once the screen settles into the biometric-primary state.
            if !didAutoPrompt, !s.initializing,
               s.biometricAvailable, s.biometricEnrolled, !s.passphraseExpanded {
                didAutoPrompt = true
                submitBiometric()
            }
        }
    }

    func setPassphrase(_ value: String) {
        passphrase = value
        vm.onPassphraseChanged(value: value)
    }

    func submit() { vm.onUnlock() }

    func submitBiometric() {
        vm.onUnlockBiometric(
            host: ControlSurface.shared.biometricPromptHost(),
            promptCopy: ControlSurface.shared.unlockBiometricPromptCopy(),
        )
    }

    /// "Use passphrase instead" — reveal the passphrase field (also implicitly revealed by the VM
    /// on any non-success biometric outcome).
    func usePassphrase() { vm.onUsePassphrase() }
}

struct LockedView: View {
    let onUnlocked: () -> Void
    let onForget: () -> Void
    @StateObject private var model = LockedModel()
    @State private var showForgetConfirm = false

    var body: some View {
        OnboardingScaffold {
            if model.initializing {
                ProgressView()
                    .tint(OnboardingColors.primary)
            } else {
                content
            }
        }
        .task { await model.observe() }
        .onChange(of: model.unlocked) { unlocked in
            if unlocked { onUnlocked() }
        }
        .confirmationDialog(
            LockedStrings.forgetConfirmTitle,
            isPresented: $showForgetConfirm,
            titleVisibility: .visible
        ) {
            Button(LockedStrings.forgetDevice, role: .destructive) {
                // Reuse the existing local-custody wipe (device id + A4 + UNLOCK/BIOMETRIC
                // wrappers + phrase + relay latches + cross-app handoff). Safe with the real
                // runtime installed (standalone delegate on the same Keychain service).
                //
                // ⚠ Awaited, not fire-and-forget: the wipe now retires this device's own roster
                // row FIRST and waits for that tombstone to be pushed — the A4 key that signs the
                // push is one of the things it then deletes, so a tombstone left queued could
                // never leave, and the realm would keep a row describing a device that no longer
                // exists. Bounded (~8s worst case) and it never blocks the wipe itself.
                Task {
                    _ = try? await ControlSurface.shared.wipeDeviceState()
                    await MainActor.run { onForget() }
                }
            }
            Button(LockedStrings.forgetCancel, role: .cancel) {}
        } message: {
            // ⭐ Q50 (B.18.8): the forget removes a kept copy of damaged data; say so first.
            Text([LockedStrings.forgetConfirmMessage, StorageStateSurface.shared.damagedCopyForgetWarning()].compactMap { $0 }.joined(separator: " "))
        }
    }

    @ViewBuilder private var content: some View {
        MarkOutline(dimension: 96)
        vGap(24)
        Text(LockedStrings.welcomeBack)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
        vGap(16)
        Text(model.biometricPrimary ? LockedStrings.biometricBody : LockedStrings.passphraseBody)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        vGap(32)

        if model.biometricPrimary {
            biometricContent
        } else {
            passphraseContent
        }

        // Escape hatch for a sealed device with no passphrase: the Keychain survives app
        // uninstall, so a reinstalled/recovered device lands here with the unlock wrapper still
        // present and — without this — no way back to onboarding. Clearly secondary to Unlock:
        // a tertiary text link (same shape as `LinkButtonStyle`) tinted red because it's
        // destructive; the tap confirms before wiping local custody.
        vGap(24)
        Button(role: .destructive, action: { showForgetConfirm = true }) {
            Text(LockedStrings.forgetDevice)
                .font(.body)
                .foregroundStyle(.red)
        }
        .buttonStyle(.plain)
    }

    // Face ID / Touch ID primary path — auto-presented on entry; the button re-triggers on retry.
    @ViewBuilder private var biometricContent: some View {
        Button(action: { model.submitBiometric() }) {
            Label(model.isLoading ? LockedStrings.unlocking : LockedStrings.unlockWithFaceId,
                  systemImage: "faceid")
        }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(model.isLoading)

        if let error = model.errorMessage {
            vGap(12)
            Text(error)
                .font(.callout)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }

        vGap(16)
        Button(action: { model.usePassphrase() }) {
            Text(LockedStrings.usePassphrase)
                .font(.body)
        }
        .buttonStyle(.plain)
        .disabled(model.isLoading)
    }

    // Passphrase floor — the fallback, and the only path when biometric isn't enrolled.
    @ViewBuilder private var passphraseContent: some View {
        SecureField(LockedStrings.passphraseLabel, text: Binding(
            get: { model.passphrase },
            set: { model.setPassphrase($0) }))
            .textFieldStyle(.roundedBorder)
            .textContentType(.password)
            .submitLabel(.go)
            .onSubmit { model.submit() }

        if let error = model.errorMessage {
            vGap(12)
            Text(error)
                .font(.callout)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }

        vGap(24)
        Button(action: { model.submit() }) {
            Text(model.isLoading ? LockedStrings.unlocking : LockedStrings.unlock)
        }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(model.isLoading || model.passphrase.isEmpty)

        if model.tooManyTries {
            vGap(16)
            Text(LockedStrings.tooManyTries)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }
}

// Canonical copy mirrors `control-shared/.../ui/screens/locked/LockedScreen.kt`.
enum LockedStrings {
    static let welcomeBack = "Welcome back"
    static let passphraseBody = "Your Tenshu realm is locked. Enter your unlock passphrase to continue."
    static let biometricBody = "Your Tenshu realm is locked. Unlock with Face ID to continue."
    static let passphraseLabel = "Passphrase"
    static let unlock = "Unlock"
    static let unlockWithFaceId = "Unlock with Face ID"
    static let usePassphrase = "Use passphrase instead"
    static let unlocking = "Unlocking…"
    static let tooManyTries = "Too many tries — recovery from your phrase is the cleanest path."

    // "Forget this device" escape hatch — clears this device's local realm custody and returns
    // to onboarding (see LockedView).
    static let forgetDevice = "Forget this device"
    static let forgetConfirmTitle = "Forget this device?"
    static let forgetConfirmMessage = "This removes this device's access to your Tenshu realm. You'll need your recovery phrase — or another device that's still signed in — to get back in. Downloaded files on this device will be cleared. This can't be undone."
    static let forgetCancel = "Cancel"
}
