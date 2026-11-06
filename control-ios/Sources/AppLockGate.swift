import LocalAuthentication
import SwiftUI

// Control's soft app-lock UI gate on iOS — the mirror of the Android/desktop `AppLockGate`
// (docs/proposals/control-keep-device-unlocked.md §4.2, "Require unlock"). Wraps the active
// app content: when "Require unlock" is on it draws an opaque overlay and presents a Face ID /
// device-passcode sheet (`LAContext.evaluatePolicy(.deviceOwnerAuthentication)`) when the app
// comes to the foreground; it re-arms when the app is backgrounded.
//
// UI ONLY — it never touches key material. The realm stays headless (keychain wrap key is
// `AfterFirstUnlock`) and background sync is unaffected whether the gate is showing or not. The
// preference is device-local (UserDefaults via @AppStorage), never synced — a per-device choice.
struct AppLockGate<Content: View>: View {
    @ViewBuilder var content: () -> Content

    // Default on (privacy-first, near-zero friction). Device-local, not synced.
    @AppStorage(AppLockPreference.requireUnlockKey) private var requireUnlock = true
    @Environment(\.scenePhase) private var scenePhase

    // Armed on cold start; re-armed whenever the app is backgrounded.
    @State private var lockRequested = true

    /// Covered — but not necessarily locked. See the `.onChange` below for why these are two
    /// states and not one.
    @State private var obscured = false

    /// Whether the gate is in use at all on this device: the user wants it, and the OS can
    /// actually present a prompt (a secure lock screen exists) — otherwise we would lock the
    /// user out.
    private var gateActive: Bool { requireUnlock && AppLockPreference.canAuthenticate() }

    /// Content is hidden AND a successful prompt is required to get it back.
    private var locked: Bool { gateActive && lockRequested }

    var body: some View {
        ZStack {
            content()
            if locked {
                LockOverlay(onUnlock: authenticate)
            } else if obscured {
                // Covered without being locked — no prompt, no button, nothing to act on. It
                // disappears by itself when the app becomes active again.
                PrivacyCover()
            }
        }
        .onChange(of: scenePhase) { phase in
            // ⚠⚠ **Covering and locking are separate, and they happen at different moments.**
            //
            // iOS captures the app-switcher snapshot when the app resigns active — at `.inactive`,
            // *before* `.background`. This gate used to re-arm only on `.background`, so by the time
            // the overlay went up the picture had already been taken: the comment on `LockOverlay`
            // claimed it doubled as the app-switcher privacy screen, and it did not. Covering on
            // `.inactive` is what makes that claim true.
            //
            // ⚠⚠ But re-arming the *lock* on `.inactive` would be worse than the bug. `.inactive`
            // fires for every transient interruption that never leaves the app — pulling down
            // Control Centre or the notification shade, an incoming-call banner, a system
            // permission alert — and, self-defeatingly, for our own Face ID sheet. Demanding
            // re-authentication after each of those turns a curtain into a nuisance and trains the
            // user to switch it off.
            //
            // So: `.inactive` covers (cheap, invisible, no interaction), `.background` locks
            // (needs proof). This is the standard shape, and it is why `obscured` exists.
            switch phase {
            case .inactive:
                obscured = gateActive
            case .background:
                obscured = gateActive
                lockRequested = true // re-arm — content stays hidden until re-auth
            case .active:
                obscured = false
                if locked { authenticate() } // returning to the foreground → prompt
            @unknown default:
                // Unknown phases are treated as "not active": covering costs nothing and a future
                // phase that hides the app must not leave content on screen.
                obscured = gateActive
            }
        }
        .task {
            if locked { authenticate() } // cold start
        }
    }

    private func authenticate() {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            lockRequested = false // no secure lock screen ⇒ don't lock the user out
            return
        }
        context.evaluatePolicy(
            .deviceOwnerAuthentication,
            localizedReason: AppLockStrings.reason
        ) { success, _ in
            // Cancel / error leaves the overlay up; the manual Unlock button retries.
            if success {
                DispatchQueue.main.async { lockRequested = false }
            }
        }
    }
}

private struct LockOverlay: View {
    let onUnlock: () -> Void

    var body: some View {
        ZStack {
            // Opaque cover so the underlying UI never shows through the gate. (The app-switcher
            // snapshot is covered by `PrivacyCover`, which goes up at `.inactive` — early enough
            // for iOS to photograph it. This overlay alone never was: see `AppLockGate`.)
            Color(uiColor: .systemBackground).ignoresSafeArea()
            VStack(spacing: 24) {
                MarkOutline(dimension: 120)
                Text(AppLockStrings.title)
                    .font(.title2)
                    .multilineTextAlignment(.center)
                Button(AppLockStrings.unlock, action: onUnlock)
                    .buttonStyle(PrimaryButtonStyle())
                    .frame(maxWidth: 240)
            }
            .padding(24)
        }
    }
}

/// The app-switcher curtain: the same opaque ground as `LockOverlay`, with nothing to interact
/// with. Shown while the app is not active and the gate is in use, so the snapshot iOS takes on
/// the way out shows the mark rather than the user's realm.
///
/// ⚠ Deliberately not `LockOverlay` itself: an *Unlock* button in a snapshot invites a tap on a
/// screenshot, and re-entering the app usually needs no prompt at all (a Control Centre pull is
/// not a lock event).
private struct PrivacyCover: View {
    var body: some View {
        ZStack {
            Color(uiColor: .systemBackground).ignoresSafeArea()
            MarkOutline(dimension: 120)
        }
    }
}

enum AppLockPreference {
    /// Shared with the Kotlin `AppLockSettings` key name for parity (iOS stores it in
    /// UserDefaults; the value is a per-device UI choice, never synced).
    static let requireUnlockKey = "app_lock_require_unlock"

    /// Whether the OS can present a biometric or device-passcode prompt — a secure lock
    /// screen exists. When false the "Require unlock" toggle is shown disabled.
    ///
    /// ⚠ Delegates to `Reauth` rather than repeating the probe: the front door and the
    /// Ph-reauth gate must never disagree about what this device can ask.
    static func canAuthenticate() -> Bool { Reauth.canAuthenticate() }
}

enum AppLockStrings {
    static let title = "Unlock Control"
    static let reason = "Confirm it's you to open Control."
    static let unlock = "Unlock"
}
