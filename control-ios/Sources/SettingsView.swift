import SwiftUI
import TenshuSdk

// Settings — status + about + "Add a device". The Background Sync / unlock settings exist on
// Android/Desktop only.
struct SettingsView: View {
    // Fired after a successful "Lock now" so the host (ControlRootView) routes back to the
    // unlock screen. Defaulted so the standalone `-settingsHome` debug route still compiles.
    var onLock: () -> Void = {}
    /// The device discarded its identity — route back to onboarding, as the reinstall gate does.
    var onForget: () -> Void = {}

    @State private var showPairing = false
    @State private var showAppPairing = false
    @State private var locking = false
    @State private var showForget = false
    @State private var forgetting = false
    @State private var forgetOutcome: String?

    // Soft app-lock (control-keep-device-unlocked.md §4.2, "Require unlock") — device-local,
    // default on. Disabled when the device has no secure lock screen to prompt against.
    @AppStorage(AppLockPreference.requireUnlockKey) private var requireUnlock = true
    private var appLockAvailable: Bool { AppLockPreference.canAuthenticate() }

    // Realm biometric unlock (distinct from the soft app-lock above): when on, the returning-user
    // unlock screen offers Face ID / Touch ID to derive the sealed master keys instead of the
    // passphrase. Enrollment biometric-protects a wrap-key in the Keychain + wraps the realm secret
    // under it (ControlSurface.enableBiometricUnlock); the passphrase stays the fallback.
    @StateObject private var servicesModel = ServicesModel()
    @StateObject private var accountModel = SyncDashboardModel()
    @State private var biometricUnlockAvailable = false
    // Whether A1 is sealed behind an UNLOCK_WRAPPER (the advanced-passphrase opt-in) rather than
    // headless. Realm biometric unlock is only meaningful on a sealed realm — see the gate below.
    @State private var realmSealed = false
    @State private var biometricUnlockEnrolled = false
    @State private var biometricBusy = false
    @State private var biometricError: String?

    var body: some View {
        NavigationStack {
            List {
                Section("Account") {
                    // Live realm state. Vocabulary matches the Devices dashboard:
                    // Synced vs Local only.
                    LabeledContent("Status", value: accountModel.isSyncEnabled ? "Synced" : "Local only")
                    LabeledContent("Devices", value: accountModel.deviceCount > 0 ? "\(accountModel.deviceCount)" : "—")
                }
                Section("Services") {
                    // Read-only plan/usage status (ios-services-status-and-purchase.md);
                    // purchase is gated on the external-link/IAP call.
                    // The row carries the current plan as its value (the iCloud-row
                    // idiom: state at a glance, chevron for the detail).
                    NavigationLink {
                        ServicesView()
                    } label: {
                        LabeledContent {
                            Text(servicesModel.planShortLabel)
                        } label: {
                            Label("Storage", systemImage: "externaldrive.badge.icloud")
                        }
                    }
                }
                Section("Data") {
                    // The realm-wide inventory of what leaves this realm — link shares
                    // (created in Drive, listed and revoked here) plus the shared folders this
                    // realm owns or has joined.
                    NavigationLink {
                        SharesView()
                    } label: {
                        Label("Shares", systemImage: "link")
                    }
                }
                Section("Devices") {
                    // The Devices / sync read surface over the real proxy — the device list +
                    // sync status from SyncDashboardViewModel (live device-roster relation + getSyncStatus()).
                    NavigationLink {
                        SyncDashboardView()
                    } label: {
                        Label("This realm's devices", systemImage: "macbook.and.iphone")
                    }
                    // Opens the joiner ("Add a device") pairing wizard. Over fakes the handshake
                    // auto-completes; the real cross-Control ceremony runs over the proxy's pairing
                    // runtime.
                    Button {
                        showPairing = true
                    } label: {
                        Label("Add a device", systemImage: "plus.circle")
                    }
                }
                Section("Apps") {
                    // EPC app pairing (§11.1 third flow): start the proxy's EpcPairingHost so an
                    // app (e.g. Drive) can connect to this realm over the relay rendezvous channel. The
                    // Control-side counterpart to drive-ios's "Connect to Control".
                    Button {
                        showAppPairing = true
                    } label: {
                        Label("Connect an app", systemImage: "app.connected.to.app.below.fill")
                    }
                }
                Section("Recovery") {
                    // ⚠⚠ This row is the route to the recovery phrase on iOS — it must lead to the
                    // phrase itself, not to an onboarding step. See RecoveryPhraseView.
                    NavigationLink {
                        RecoveryPhraseScreen()
                    } label: {
                        Label(RecoveryPhraseStrings.title, systemImage: "key.horizontal")
                    }
                }
                Section {
                    Toggle("Require unlock", isOn: $requireUnlock)
                        .disabled(!appLockAvailable)
                    // No "Encrypt on unlock only" on iOS (control-keep-device-unlocked.md, the iOS
                    // headless decision): the passphrase hard mode is an Android/desktop artifact;
                    // iOS auth is the transparent device-owner primitive the toggle above already
                    // uses, and the OS encrypts keys at rest by default.

                    // Realm unlock via Face ID. Toggling on enrolls (may present a passcode/biometric
                    // confirmation); off clears the wrapper.
                    //
                    // ⚠⚠ Gated on `realmSealed`, not only on the hardware. Per the decision above,
                    // the default iOS realm is **headless** — nothing is sealed behind a Tenshu
                    // credential — so realm biometric unlock has nothing to unlock, and
                    // `setUpBiometricUnlock` refuses outright (`UNLOCK_WRAPPER == null -> false`, the
                    // passphrase-floor backstop): offered there, the toggle could only ever fail.
                    // It shows for a realm sealed via the advanced-passphrase opt-in.
                    if biometricUnlockAvailable && realmSealed {
                        Toggle("Unlock with Face ID", isOn: Binding(
                            get: { biometricUnlockEnrolled },
                            set: { setBiometricUnlock($0) }))
                            .disabled(biometricBusy)
                        if let biometricError {
                            Text(biometricError)
                                .font(.footnote)
                                .foregroundStyle(.red)
                        }
                    }
                } header: {
                    Text("Security")
                } footer: {
                    // ⚠ Three footers: a headless realm has no passphrase, so its footer must not
                    // promise one.
                    Text(!appLockAvailable
                        ? "Set up a passcode or Face ID in your device settings to use this."
                        : realmSealed
                            ? "Require unlock asks for Face ID, Touch ID, or your passcode to open Control. Unlock with Face ID also lets you re-open a locked realm without typing your passphrase — the passphrase stays your fallback. Your files keep syncing in the background either way."
                            : "Require unlock asks for Face ID, Touch ID, or your passcode to open Control. Your keys are protected by this device at rest, so there is no separate passphrase to set. Your files keep syncing in the background either way.")
                }
                // ⚠⚠ Gated on `realmSealed`, for the same reason as the Face ID row above. The
                // default iOS realm is headless and HAS no passphrase, so locking one seals the
                // session and sends the user to a screen demanding a credential that does not exist
                // — recoverable only by force-quitting (the autoload path re-derives from the
                // plaintext slot on the next launch). A manual lock is meaningful only where there
                // is something to unlock with — i.e. the advanced-passphrase opt-in.
                if realmSealed {
                Section("Lock") {
                    // Re-seals the realm (purges this session's in-memory keys via the LockRealm op);
                    // on success the host routes back to the unlock screen.
                    Button(role: .destructive) {
                        guard !locking else { return }
                        locking = true
                        Task {
                            _ = try? await ControlSurface.shared.lockRealm()
                            locking = false
                            onLock()
                        }
                    } label: {
                        Label(locking ? "Locking…" : "Lock now", systemImage: "lock")
                    }
                    .disabled(locking)
                }
                }

                // The iOS twin of desktop/Android's "Forget this device" (ForgetThisDevice +
                // ForgetDeviceDialog): the same `wipeDeviceState()` the reinstall gate runs,
                // reachable without deleting and reinstalling the app (which also drops the
                // developer-trust entry).
                Section("Device") {
                    Button(role: .destructive) {
                        showForget = true
                    } label: {
                        Label(forgetting ? "Discarding…" : "Forget this device", systemImage: "person.badge.minus")
                    }
                    .disabled(forgetting)
                    if let forgetOutcome {
                        Text(forgetOutcome).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Section("About") {
                    LabeledContent("App", value: "Tenshu Control")
                    // ⚠ The running bundle's version, because it is the thing a bug report can cite
                    // — a hardcoded label nobody can act on looks like an answer (the shape
                    // `hostAppVersion()` warns about).
                    LabeledContent("Version", value: Self.bundleVersion)
                    // In-app privacy policy (App Store guideline 5.1.1(i)); ios-release/Sources.
                    PrivacyPolicyLink()
                    // The terms of use, beside the policy (ios-release/Sources/PrivacyPolicy.swift).
                    Link(destination: TermsOfUse.current) {
                        Label("Terms of use", systemImage: "doc.text")
                    }
                    // The open-source notices (docs/oss-licences.md). Control serves the offline map
                    // regions and Photon lookups and downloads the models, so it credits all three.
                    OpenSourceLicencesLink(data: [.openStreetMap, .protomaps, .photon, .models])
                }
            }
            .navigationTitle("Settings")
            .task { await refreshBiometricState() }
            // ⚠ Reuses the reinstall-gate rescue sheet rather than a second dialog: it already
            // shows the phrase, gates the destructive button on the acknowledgement, and offers the
            // unlock when the phrase is wrapped. A separate Settings dialog would be a second copy
            // of the one flow that must never get this wrong.
            .sheet(isPresented: $showForget) {
                ReinstallPhraseRescueSheet(
                    onDiscard: {
                        showForget = false
                        forget()
                    },
                    onCancel: { showForget = false },
                )
            }
            .task { await servicesModel.observe() }
            .task {
                accountModel.refresh()
                await accountModel.observe()
            }
            .sheet(isPresented: $showPairing) {
                PairingView(onClose: { showPairing = false })
            }
            .sheet(isPresented: $showAppPairing) {
                EpcAppPairingView(onClose: { showAppPairing = false })
            }
        }
    }

    /// ⚠ Awaited, not fire-and-forget — `wipeDeviceState` retires this device's roster row and waits
    /// for the tombstone to leave BEFORE destroying the key that signs the push. The leftover-row
    /// outcome is surfaced rather than swallowed, exactly as the reinstall gate does.
    private func forget() {
        forgetting = true
        Task {
            let result = (try? await ControlSurface.shared.wipeDeviceState()) ?? ""
            await MainActor.run {
                forgetting = false
                forgetOutcome = result.isEmpty ? nil : result
                onForget()
            }
        }
    }

    /// The running bundle's marketing version + build, the same `CFBundleShortVersionString` that
    /// `hostAppVersion()` reports for `ErrorEvent.appVersion`. Falls back to the shared
    /// unknown-version marker rather than inventing one.
    private static var bundleVersion: String {
        let short = (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)?
            .trimmingCharacters(in: .whitespaces)
        let build = (Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String)?
            .trimmingCharacters(in: .whitespaces)
        guard let short, !short.isEmpty else { return "unknown" }
        guard let build, !build.isEmpty, build != short else { return short }
        return "\(short) (\(build))"
    }

    private func refreshBiometricState() async {
        // Kotlin `suspend fun … : Boolean` bridges to KotlinBoolean — unwrap via `.boolValue`.
        biometricUnlockAvailable = (try? await ControlSurface.shared.biometricUnlockAvailable())?.boolValue ?? false
        biometricUnlockEnrolled = (try? await ControlSurface.shared.biometricUnlockEnrolled())?.boolValue ?? false
        // Non-suspend: reads the standalone Keychain bucket, no runtime needed.
        realmSealed = ControlSurface.shared.realmIsSealed()
    }

    /// Enroll / disable realm biometric unlock. Re-reads the real enrolled state afterward so the
    /// toggle reflects the delegate's truth (a cancelled or failed enroll snaps back to off).
    private func setBiometricUnlock(_ enable: Bool) {
        guard !biometricBusy else { return }
        biometricBusy = true
        biometricError = nil
        Task {
            if enable {
                let result = (try? await ControlSurface.shared.enableBiometricUnlock()) ?? "error:Something went wrong."
                if result.hasPrefix("error:") {
                    biometricError = String(result.dropFirst("error:".count))
                }
                // "cancelled" / "unavailable" surface silently — the toggle just stays/returns to off.
            } else {
                _ = try? await ControlSurface.shared.disableBiometricUnlock()
            }
            biometricUnlockEnrolled = (try? await ControlSurface.shared.biometricUnlockEnrolled())?.boolValue ?? false
            biometricBusy = false
        }
    }
}
