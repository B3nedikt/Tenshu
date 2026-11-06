import SwiftUI

/// The one forward-to-Control screen, shared by every first-party route-1 iOS app
/// (ADR 0022). Shown when no Control session is present.
///
/// These are first-party apps, so this is NOT a "connect to an external thing" pairing
/// flow — that framing belongs to the third-party EPC apps. It is "set up the Tenshu
/// platform on this device": either install Control from the App Store, or open the
/// installed-but-not-set-up Control to finish setup (genesis / join a realm). On return
/// the host re-checks the shared Keychain (scenePhase → active) and forwards into the
/// app. No QR and no SAS in the first-party path.
///
/// ⚠ The caller supplies `appName` + `purpose` rather than the view guessing: the copy
/// has to name what THIS app loses without Control ("keep your files encrypted and
/// synced" vs "record and sync your workouts"), and a generic sentence would be true of
/// nothing in particular.
///
/// ⚠⚠ Localisation: every sentence here must reach `Text` as a **literal** (a
/// `LocalizedStringKey`), never as a `String`. `Text(cond ? "a" : "b")` types the ternary as
/// `String` and renders it verbatim, which is how this screen stayed English under German.
/// Branch on `Text`, not on the string. `purpose` is a `LocalizedStringResource` for the same
/// reason: its translation lives in each host app's `Localizable.xcstrings` (this file is
/// compiled into every app target, so `.main` is the host's bundle), and the German template
/// takes it as a whole clause ("…, um %2$@.") — so a host's purpose is translated as an
/// infinitive clause ending in "zu …".
struct ConnectToControlView: View {
    /// The host app's display name, e.g. "Drive".
    let appName: String
    /// One clause completing "<appName> uses Tenshu Control to …", e.g.
    /// "keep your files end-to-end encrypted and synced across your devices". Pass a literal:
    /// it is the catalogue key the host app translates.
    let purpose: LocalizedStringResource
    /// Re-read provisioning after the user returns (host decides what that means).
    var onRecheck: () -> Void
    /// DEV_HARNESS-only affordance to fake provisioning. Nil = no dev row.
    var devStub: (() -> Void)? = nil

    @Environment(\.openURL) private var openURL
    @State private var controlMissing = false

    /// Control is missing AND we cannot offer the Store — the honest dead-end.
    /// ⚠ Distinguished from `controlMissing` alone so we never render a "Get" button
    /// that opens a broken page (see `Control.appStoreURL`).
    private var storeUnavailable: Bool { controlMissing && Control.appStoreURL == nil }

    var body: some View {
        VStack(spacing: 20) {
            Spacer()

            Image(systemName: "shield.lefthalf.filled")
                .font(.system(size: 64))
                .foregroundStyle(Color.accentColor)

            Text("Set Up Tenshu Control")
                .font(.title.bold())

            explanation
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 32)

            if !storeUnavailable {
                Button {
                    openControl()
                } label: {
                    (controlMissing ? Text("Get Tenshu Control") : Text("Open Tenshu Control"))
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding(.horizontal, 32)
            }

            if storeUnavailable {
                // Pre-launch state: Control exists but has no App Store page yet. Saying so
                // beats a button that goes nowhere.
                Label("Tenshu Control isn’t on the App Store yet. Install it from TestFlight or Xcode to continue.",
                      systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            } else if controlMissing {
                Label("Tenshu Control isn’t installed on this iPhone yet.",
                      systemImage: "arrow.down.app")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }

            Spacer()

            #if DEV_HARNESS
            if let devStub { devStubControls(devStub) }
            #endif
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }

    /// The body sentence. Two literals, one per state, so each is its own catalogue key.
    private var explanation: Text {
        let clause = String(localized: purpose)
        return controlMissing
            ? Text("\(appName) uses Tenshu Control to \(clause). Get Control from the App Store, then come back to \(appName).")
            : Text("\(appName) uses Tenshu Control to \(clause). Set it up on this iPhone to continue.")
    }

    private func openControl() {
        // Once we know Control isn't installed, the button becomes a straight App Store
        // hand-off — no point re-trying the deep link.
        if controlMissing {
            if let store = Control.appStoreURL { openURL(store) }
            return
        }
        openURL(Control.url) { accepted in
            // Not accepted → no Control build handles the scheme (not installed): flip to
            // the "Get Tenshu Control" / App Store variant.
            // Accepted → on return, scenePhase→active re-checks automatically.
            controlMissing = !accepted
        }
    }

    #if DEV_HARNESS
    /// Lets the forward-to-Control flow be exercised on a simulator with no Control build.
    /// Never compiled into a shipping scheme.
    private func devStubControls(_ stub: @escaping () -> Void) -> some View {
        VStack(spacing: 8) {
            Divider()
            Text("Developer")
                .font(.caption2.smallCaps())
                .foregroundStyle(.tertiary)
            Button("Simulate Control set-up") {
                stub()
                onRecheck()
            }
            .font(.footnote)
        }
        .padding(.bottom, 8)
    }
    #endif
}
