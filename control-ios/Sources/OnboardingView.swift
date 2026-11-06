import SwiftUI
import TenshuSdk

// Genesis onboarding (Control-on-iOS, docs/proposals/ios-control-handover.md §C2c).
//
// Reworked to track the canonical Control onboarding (Android/Desktop, the CMP screens
// in `control-shared/.../ui/screens`): a centered Welcome chooser, the cat MarkOutline,
// the Amethyst-purple three-tier button hierarchy, and matching copy. The screens bind
// the same shared `:control-shared-logic` view models through `ControlSurface`; only the
// rendering tech differs (House Rule #5: native UI, not Compose).
//
// All three canonical options are now live: genesis (start fresh), recovery-phrase restore,
// and — as of C6 — device-to-device pairing. "I have Tenshu on another device" presents the
// joiner pairing wizard (PairingView, joinerOnly) over the real ProxyModule-backed runtime;
// on success it provisions the Drive session and completes onboarding, same as genesis.

private enum OnboardingRoute: Hashable {
    case newUser
    case recovery
    /// S2-pre P4: the optional detour off "You're set up" into the email-OTP screen. A route
    /// rather than a `NavigationLink` inside `NewUserView`, so it pushes onto the onboarding
    /// stack the same way every other step does — and `SignInView`'s own `dismiss()` pops back
    /// to the terminal step, leaving the user to finish through Continue as before.
    case signIn
}

struct OnboardingView: View {
    let onComplete: () -> Void
    @State private var path: [OnboardingRoute] = OnboardingView.initialPath()
    @State private var showPairing = OnboardingView.initialShowPairing()

    /// DEBUG-only deep-link for screenshot/UI verification: `-onboardingRoute newUser|recovery`
    /// seeds the navigation stack so a screen can be captured without a tap. No effect in release.
    private static func initialPath() -> [OnboardingRoute] {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-onboardingRoute"), i + 1 < args.count {
            switch args[i + 1] {
            case "newUser": return [.newUser]
            case "recovery": return [.recovery]
            default: break
            }
        }
        #endif
        return []
    }

    /// DEBUG-only deep-link: `-onboardingRoute pairing` auto-presents the joiner pairing sheet
    /// over the Welcome chooser so it can be captured without a tap. No effect in release.
    private static func initialShowPairing() -> Bool {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-onboardingRoute"), i + 1 < args.count, args[i + 1] == "pairing" {
            return true
        }
        #endif
        return false
    }

    var body: some View {
        NavigationStack(path: $path) {
            WelcomeView(
                onStartFresh: { path.append(.newUser) },
                onRecover: { path.append(.recovery) },
                onExistingDevice: { showPairing = true }
            )
            .navigationDestination(for: OnboardingRoute.self) { route in
                switch route {
                case .newUser:
                    NewUserView(onComplete: onComplete, onSignIn: { path.append(.signIn) })
                case .recovery: RecoveryView(onComplete: onComplete)
                case .signIn: SignInView()
                }
            }
        }
        .tint(OnboardingColors.primary)
        .sheet(isPresented: $showPairing) {
            // Joiner-only: a realm-less device can't host, so the Scan|Show picker is hidden.
            // onPaired fires on the success-done path before the sheet closes — publish the
            // route-1 key material and complete onboarding, mirroring genesis's openControl.
            PairingView(
                onClose: { showPairing = false },
                onPaired: {
                    _ = ControlSurface.shared.ensureDriveProvisioned()
                    onComplete()
                },
                joinerOnly: true
            )
        }
    }
}

// MARK: - Welcome (the first-run chooser; LandingState.FirstTime → Destination.Welcome)

struct WelcomeView: View {
    let onStartFresh: () -> Void
    let onRecover: () -> Void
    let onExistingDevice: () -> Void

    var body: some View {
        OnboardingScaffold {
            MarkOutline(dimension: 96)
            vGap(24)
            Text(OnboardingStrings.welcomeTitle)
                .font(.onboardingHeadline)
                .multilineTextAlignment(.center)
            vGap(16)
            Text(OnboardingStrings.welcomeBody)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            vGap(32)

            Button(OnboardingStrings.welcomeStartFresh, action: onStartFresh)
                .buttonStyle(OutlinedButtonStyle())
            vGap(12)

            Button(OnboardingStrings.welcomeExistingDevice, action: onExistingDevice)
                .buttonStyle(OutlinedButtonStyle())
            vGap(12)

            Button(OnboardingStrings.welcomeRecoverPhrase, action: onRecover)
                .buttonStyle(LinkButtonStyle())
        }
    }
}

// MARK: - New user (genesis)

@MainActor
final class NewUserModel: ObservableObject {
    @Published var step: String = "setup"
    @Published var busy = false
    @Published var errorMessage: String? = nil
    @Published var done = false

    /// True once the user opts into the expert passphrase lock from the device-protection
    /// default (mirrors `NewUserUiState.advancedUnlock`).
    @Published var advancedUnlock = false

    // Field state is owned locally and forwarded to the VM; the VM is the source of
    // truth for validation. observe() never writes back into the fields (avoids a
    // bind→echo loop / cursor jumps).
    @Published var passphrase = ""
    @Published var confirm = ""

    private let vm = ControlSurface.shared.genesisViewModel()

    func observe() async {
        // Headless default (control-keep-device-unlocked.md §4.1, mirrors PR A): the
        // setup step leads with device protection (no passphrase floor). We do NOT put
        // the VM into passphrase mode up front — the intro's "Continue" creates the realm
        // headless; the passphrase is an opt-in.
        for await s in vm.uiState {
            step = ControlSurface.shared.genesisStepKey(state: s)
            busy = s.unlockSetupBusy || s.biometricBusy
            errorMessage = ControlSurface.shared.genesisErrorMessage(state: s)
            advancedUnlock = s.advancedUnlock
            done = s.exitTo != nil
        }
    }

    /// Headless: create the realm with the recovery phrase keystore-sealed, no passphrase.
    func useDeviceProtection() { vm.onUseDeviceProtection() }

    /// Reveal the expert passphrase lock.
    func showAdvancedUnlock() { vm.onShowAdvancedUnlock() }

    /// Return to the device-protection default.
    func exitAdvancedUnlock() { vm.onExitAdvancedUnlock() }

    func setPassphrase(_ value: String) {
        passphrase = value
        vm.onUnlockPassphraseChanged(value: value)
    }

    func setConfirm(_ value: String) {
        confirm = value
        vm.onUnlockPassphraseConfirmChanged(value: value)
    }

    func submit() { vm.onUnlockSetupSubmit() }

    func openControl() {
        // Route-1 handoff: publish the shared-Keychain key material the co-resident first-party apps
        // run on, then proceed. (Cross-app read is signed-build-gated — see RealmKeyMaterialBridge.)
        _ = ControlSurface.shared.ensureDriveProvisioned()
        vm.onOpenDrive()
    }
}

struct NewUserView: View {
    let onComplete: () -> Void
    /// Offered on the terminal step only — see `youreSetUpContent` for why sync is the one thing
    /// worth surfacing here.
    let onSignIn: () -> Void
    @StateObject private var model = NewUserModel()

    var body: some View {
        OnboardingScaffold {
            switch model.step {
            case "setup": setupContent
            case "bootstrapping": bootstrappingContent
            case "set_up": youreSetUpContent
            default: EmptyView()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
        .onChange(of: model.done) { done in
            if done { onComplete() }
        }
    }

    // SetupUnlock — device-protection default (control-keep-device-unlocked.md §4.1),
    // with the passphrase floor as an opt-in. Mirrors the Compose NewUserScreen.
    @ViewBuilder private var setupContent: some View {
        if model.advancedUnlock {
            passphraseContent
        } else {
            deviceProtectionContent
        }
    }

    // The headless default: "Protected by your device" + Continue (no passphrase).
    @ViewBuilder private var deviceProtectionContent: some View {
        MarkOutline(dimension: 120)
        vGap(24)
        Text(OnboardingStrings.protectTitle)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
        vGap(16)
        Text(OnboardingStrings.protectBody)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)

        if let error = model.errorMessage {
            vGap(12)
            Text(error)
                .font(.callout)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }

        vGap(32)
        Button(action: { model.useDeviceProtection() }) {
            Text(model.busy ? OnboardingStrings.setupUnlockSettingUp : OnboardingStrings.newUserContinue)
        }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(model.busy)
        vGap(8)
        Button(OnboardingStrings.protectAddLock) { model.showAdvancedUnlock() }
            .buttonStyle(LinkButtonStyle())
            .disabled(model.busy)
    }

    // The expert opt-in: the passphrase floor (canonical SetupUnlockStep, passphrase branch).
    @ViewBuilder private var passphraseContent: some View {
        Text(OnboardingStrings.setupUnlockTitle)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
        vGap(16)
        Text(OnboardingStrings.setupUnlockPassphraseBody)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        vGap(32)

        SecureField(OnboardingStrings.setupUnlockPassphraseLabel, text: Binding(
            get: { model.passphrase },
            set: { model.setPassphrase($0) }))
            .textFieldStyle(.roundedBorder)
            .textContentType(.newPassword)
            .submitLabel(.next)
        vGap(12)
        SecureField(OnboardingStrings.setupUnlockConfirmLabel, text: Binding(
            get: { model.confirm },
            set: { model.setConfirm($0) }))
            .textFieldStyle(.roundedBorder)
            .textContentType(.newPassword)
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
            if model.busy {
                Text(OnboardingStrings.setupUnlockSettingUp)
            } else {
                Text(OnboardingStrings.setupUnlockSetPassphrase)
            }
        }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(model.busy)
        vGap(8)
        Button(OnboardingStrings.protectBack) { model.exitAdvancedUnlock() }
            .buttonStyle(LinkButtonStyle())
            .disabled(model.busy)
    }

    // Bootstrapping — registering the realm.
    @ViewBuilder private var bootstrappingContent: some View {
        Text(OnboardingStrings.newUserRegistering)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
        vGap(24)
        ProgressView()
            .tint(OnboardingColors.primary)
    }

    // YoureSetUp — success.
    @ViewBuilder private var youreSetUpContent: some View {
        MarkOutline(dimension: 160)
        vGap(24)
        Text(OnboardingStrings.newUserYoureSetUp)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
        vGap(8)
        Text(OnboardingStrings.newUserYoureSetUpBody)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        vGap(8)
        // ⚠⚠ Directly under the sentence it answers, and BEFORE the phrase ceremony below —
        // fact then remedy, while the user is still reading the fact. A realm created here is
        // held local-only by `StorageSyncGate` until an account is bound, and a host cannot be
        // rescued by a peer (`joinedFundedRealm` is false for the device that started the realm),
        // so this is the difference between a realm that syncs and one that silently never can
        // (ios-services-status-and-purchase.md §3.5). ⚠ A link, never a gate: finishing without
        // an account is a legitimate local-first choice (ADR 0019), not a mistake to block.
        Button(OnboardingStrings.newUserTurnOnSync, action: onSignIn)
            .buttonStyle(LinkButtonStyle())
        vGap(24)
        // RG/§5b.12 link 1: **offer** the phrase here. C2c minted it silently and nothing on iOS ever
        // showed it — not this screen, not Settings (a placeholder), not the shared banner (unbound).
        // A user could never see the words their realm depends on, which is how a real A1 loss
        // happened. Deferring stays possible ("I'll do this later"); never being asked does not.
        RecoveryPhraseView(
            startRevealed: true,
            caption: RecoveryPhraseStrings.onboardingCaption,
        )
        vGap(24)
        // ⚠ Both buttons do the same thing, deliberately. "I've saved it" records nothing, because
        // nothing on iOS reads a saved-flag yet (the shared recovery banner is not bound here), and a
        // preference written for no reader is the inert-by-construction trap. The pair exists so the
        // user answers the question rather than dismissing a screen — if the banner is ever wired,
        // this is the call site that gains a `setPhraseSaved`.
        Button(RecoveryPhraseStrings.onboardingDone) { model.openControl() }
            .buttonStyle(PrimaryButtonStyle())
        vGap(12)
        Button(RecoveryPhraseStrings.onboardingSkip) { model.openControl() }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
    }
}
