import SwiftUI
import UIKit

// Shared onboarding design vocabulary — the SwiftUI port of the Control Compose onboarding's look
// (centered Box/Column, Amethyst-Neutral M3 theme, three-tier button hierarchy), so Control-on-iOS
// onboarding matches Android/Desktop as closely as SwiftUI allows. The screens bind the SAME shared
// `:control-shared-logic` view models; only the rendering tech differs (House Rule #5: native UI, not
// Compose). Strings mirror the canonical CMP resources (`control-shared/.../composeResources`).

// MARK: - Colours (Material 3 "Amethyst Neutral", from ui-core theme)

enum OnboardingColors {
    /// `primary` — #5A4A96 light / #CBC0FF dark (the brand purple the Compose theme uses).
    static let primary = Color(uiColor: dynamicPrimary)

    private static let primaryLight = UIColor(red: 0x5A / 255.0, green: 0x4A / 255.0, blue: 0x96 / 255.0, alpha: 1)
    private static let primaryDark = UIColor(red: 0xCB / 255.0, green: 0xC0 / 255.0, blue: 0xFF / 255.0, alpha: 1)

    private static let dynamicPrimary = UIColor { trait in
        trait.userInterfaceStyle == .dark ? primaryDark : primaryLight
    }

    /// `onPrimary` — text on a `primary` fill: #FFFFFF light / #2C1E71 dark (`--on-primary` in
    /// `web/shared/tokens.css`). ⚠ Never a fixed white: dark `primary` is a light lilac, and white on
    /// it is unreadable.
    static let onPrimary = Color(uiColor: dynamicOnPrimary)

    private static let onPrimaryDark = UIColor(red: 0x2C / 255.0, green: 0x1E / 255.0, blue: 0x71 / 255.0, alpha: 1)

    private static let dynamicOnPrimary = UIColor { trait in
        trait.userInterfaceStyle == .dark ? onPrimaryDark : .white
    }
}

// MARK: - Type scale (M3 sizes on SF Pro)

extension Font {
    /// M3 `headlineMedium` ≈ 28pt.
    static let onboardingHeadline = Font.system(size: 28, weight: .regular)
}

// MARK: - Button styles (Compose Button / OutlinedButton / TextButton)

/// Filled primary CTA — "Set passphrase" / "Continue" / "Recover".
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(OnboardingColors.primary.opacity(isEnabled ? 1 : 0.35))
            .foregroundStyle(OnboardingColors.onPrimary)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .opacity(configuration.isPressed ? 0.85 : 1)
    }
}

/// Equally-weighted secondary option — the Welcome chooser entries.
struct OutlinedButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.medium))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .foregroundStyle(isEnabled ? OnboardingColors.primary : Color.secondary)
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke((isEnabled ? OnboardingColors.primary : Color.secondary).opacity(0.5), lineWidth: 1)
            )
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}

/// Tertiary text link — "Recover from a recovery phrase" / "Paste from clipboard".
struct LinkButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body)
            .foregroundStyle(OnboardingColors.primary)
            .opacity(configuration.isPressed ? 0.5 : 1)
    }
}

// MARK: - Centered scaffold (Compose: Box(fillMaxSize, center) { Column(widthIn(max=…)) })

/// A vertically-centered, max-width onboarding canvas that scrolls when the keyboard or tall content
/// needs it — the SwiftUI analogue of the Compose onboarding's centered, width-capped column.
struct OnboardingScaffold<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        GeometryReader { geo in
            ScrollView {
                VStack(spacing: 0) { content() }
                    // Claim natural height so wrapping Text isn't compressed (truncated) when the
                    // centering frame's minHeight competes with tall content.
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: 480)
                    .frame(maxWidth: .infinity, minHeight: geo.size.height, alignment: .center)
                    .padding(.horizontal, 24)
            }
            .scrollDismissesKeyboard(.interactively)
        }
    }
}

/// A fixed-height spacer, matching Compose's `Spacer(Modifier.height(n.dp))`.
func vGap(_ height: CGFloat) -> some View {
    Spacer().frame(height: height)
}

// MARK: - Canonical strings (mirror the CMP string resources)

enum OnboardingStrings {
    // Welcome
    static let welcomeTitle = "Welcome to Tenshu"
    static let welcomeBody =
        "Tenshu is a private place for your files. Everything is encrypted on this device — nobody else can read it, including us."
    static let welcomeStartFresh = "Start fresh on this device"
    static let welcomeExistingDevice = "I have Tenshu on another device"
    static let welcomeRecoverPhrase = "Recover from a recovery phrase"

    // New user — device-protection default (control-keep-device-unlocked.md §4.1)
    static let protectTitle = "Protected by your device"
    static let protectBody =
        "Tenshu secures your keys with this device's secure storage, so your files start syncing right away — even after a restart, with no passphrase to remember."
    static let protectAddLock = "Add a passphrase for extra security"
    static let protectBack = "Use device protection instead"

    // New user — passphrase setup (the expert opt-in; no biometric delegate on iOS yet)
    static let setupUnlockTitle = "Lock Tenshu when you're not using it"
    static let setupUnlockPassphraseBody =
        "Pick a passphrase to seal your realm on this device. You'll enter it when Tenshu locks."
    static let setupUnlockPassphraseLabel = "Passphrase"
    static let setupUnlockConfirmLabel = "Confirm passphrase"
    static let setupUnlockSettingUp = "Setting up…"
    static let setupUnlockSetPassphrase = "Set passphrase"

    // New user — bootstrapping + success
    static let newUserRegistering = "Registering your realm"
    static let newUserYoureSetUp = "You're set up"
    // ⚠⚠ "Your realm is ready." was true of the realm and misleading about the outcome: a realm
    // created here is held local-only by `StorageSyncGate` until an account is bound, so a user who
    // taps straight through gets a Tenshu that will never reach their other devices and is never
    // told so (ios-services-status-and-purchase.md §3.5). Local-first is the design (ADR 0019), so
    // this states it as the fact it is rather than as a warning — and `newUserTurnOnSync` beside it
    // is the way to change it. Mirrors the CMP `new_user_youre_set_up_body`.
    static let newUserYoureSetUpBody =
        "Your realm is ready on this device. Until you turn on sync, everything stays here — nothing leaves this device."
    static let newUserTurnOnSync = "Turn on sync"
    static let newUserContinue = "Continue"

    // Recovery — entry
    static let recoveryEntryTitle = "Recover your realm"
    static let recoveryEntryBody =
        "If you've lost access to all your devices, enter your 24-word recovery phrase below to recover your realm."
    static let recoveryEntrySlowNote =
        "This is a slow operation by design — it takes a few seconds to derive your master key from your recovery phrase. That delay is what protects your phrase from brute-force guessing."
    static let recoveryEntryWarning = "Don't share this phrase with anyone — including Tenshu."
    static let recoveryEntryRecover = "Recover"
    static let recoveryEntryPaste = "Paste from clipboard"
    static func recoveryWordCount(_ n: Int) -> String { "\(n) of 24 words" }

    // Recovery — processing + success
    static let recoveryProcessingHeading = "Recovering your realm…"
    static let recoveryProcessingBody = "This may take 5–10 seconds."
    static let recoverySuccessHeading = "Welcome back"
    static let recoverySuccessBody =
        "Your realm is recovered. This device is now your only realm device — if you find your other devices later, you'll need to re-pair them."
    static let recoverySuccessContinue = "Continue"
}
