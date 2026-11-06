import LocalAuthentication

// The iOS half of Ph-reauth (`docs/proposals/control-keep-device-unlocked.md` §6.1, RA-2) — a
// presence check in front of an action that cannot be undone, today the recovery-phrase reveal.
//
// The Kotlin twin is `Reauthenticator` / `Reauthenticator.confirm` in `:control-shared-logic`.
// Control-on-iOS is native SwiftUI over `ControlSurface`, so the Kotlin binding here is the inert
// `UnavailableReauthenticator` and *this* is the real implementation — the same split the soft
// app-lock already has (`AppLockGate.swift` vs the Compose `AppLockGate`).
//
// ⚠⚠ Deliberately independent of the "Require unlock" preference. That front door is optional and
// off for some users; A1's plaintext should not be. A user with the soft lock off previously had no
// authentication at all in front of the recovery phrase.
//
// ⚠ UI barrier, never a key gate: `.deviceOwnerAuthentication` proves presence and touches no key
// material, so the realm stays headless and the §5 threat-model posture is unchanged.
enum Reauth {

    /// Whether the OS can present a biometric **or** device-passcode prompt — i.e. a secure lock
    /// screen exists. One implementation, shared with the soft lock's availability probe, so the
    /// two can never disagree about what this device can ask.
    static func canAuthenticate() -> Bool {
        var error: NSError?
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: &error)
    }

    /// Proceed when the user proves presence — **and also when this device cannot ask at all.**
    ///
    /// ⚠⚠ The pass-through is the whole safety argument, and it is the same rule the Kotlin
    /// `Reauthenticator.confirm` encodes. A gate that cannot be satisfied is a lockout, and the
    /// action being gated is *reading the recovery phrase*: failing closed on a device with no
    /// secure lock screen would not hide the phrase, it would make it **unrecoverable there**.
    ///
    /// A cancel or a failed attempt is a refusal, so repeatedly failing the prompt cannot open it.
    static func confirm(reason: String) async -> Bool {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            return true // unaskable ⇒ proceed, exactly as `Unavailable` does on the Kotlin side
        }
        return await withCheckedContinuation { continuation in
            context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { success, _ in
                continuation.resume(returning: success)
            }
        }
    }
}

enum ReauthStrings {
    // ⚠ Bare apostrophes, matching the project's i18n convention.
    static let recoveryPhraseReason = "Confirm it's you to show your recovery phrase."
    static let revokeDeviceReason = "Confirm it's you to remove a device from your account."
}
