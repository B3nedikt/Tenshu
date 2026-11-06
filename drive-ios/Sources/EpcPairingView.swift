import SwiftUI
import TenshuSdk

// Debug-only: the EPC scan/pair surface lives in the DEV_HARNESS scheme (§11.6) — it is the
// dev/test harness + future third-party path, not the first-party forward-to-Control onboarding.
#if DEV_HARNESS

extension Notification.Name {
    /// Posted when the dev-harness live EPC client is activated/torn down, so `RootView` (a sibling
    /// tab) re-checks and routes into / out of `PairedAppView` without waiting for a scenePhase change.
    static let liveSessionChanged = Notification.Name("app.tenshu.dev.liveSessionChanged")
}

// EPC pairing client (proposal §10.6, client side). The user opens this on the app that wants to
// reach a Control, scans the QR that Control shows (slice 2b) — or pastes the `tenshu://epc/pair#…`
// link for the camera-less path — and the **real** `EpcConnector` runs over a Darwin-backed
// `RelayRendezvousTaskTransport` against the relay the URI names. The resulting 6-digit SAS is the
// thing the user confirms against Control's screen (EPC *measure 1*, §10.5).
//
// This needs a live Control serving EPC (the in-app `EpcLoopback` SAS proof went with the Smokes
// tab, 2026-11-05; the Kotlin unit tests cover the protocol). On the simulator the handshake fails at the transport
// (connection refused) — proving the wiring links/runs; the live SAS round-trip is the on-device
// gate. SKIE renders the Kotlin `suspend connect()` as Swift `async`.
struct EpcPairingView: View {
    @State private var uriText: String = ""
    @State private var state: PairingState = .idle
    @State private var showScanner = false
    // The established channel, held between showing the SAS and the user confirming it — activating it
    // installs the real client (Tier 2). SKIE bridges the Kotlin `EpcRelayPairing.Channel`.
    @State private var pairedChannel: EpcRelayPairing.Channel?

    enum PairingState: Equatable {
        case idle
        case connecting
        case established(sas: String)
        case active(sas: String)
        case failed(String)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                switch state {
                case .idle:
                    entryForm
                case .connecting:
                    ProgressView("Connecting to Control over the relay…")
                case .established(let sas):
                    sasCard(sas: sas)
                case .active(let sas):
                    activeCard(sas: sas)
                case .failed(let message):
                    failureCard(message)
                }
            }
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .navigationTitle("Pair with Control")
            .sheet(isPresented: $showScanner) {
                QrScannerView { scanned in
                    showScanner = false
                    uriText = scanned
                    connect(scanned)
                }
            }
            .task { await autoConnectFromControlIfRequested() }
        }
    }

    // Same-device round-trip CLIENT half (no tap tooling): with `-epcAutoConnectFromControl`, read the
    // live `tenshu://epc/pair#…` invite Control published into the shared `epc-session` Keychain
    // (ControlSessionStore), connect over the relay, and auto-confirm the SAS (a human step we trust in
    // the harness) so the live client installs and RootView's App tab flips to real Control data.
    private func autoConnectFromControlIfRequested() async {
        guard ProcessInfo.processInfo.arguments.contains("-epcAutoConnectFromControl"),
              case .idle = state else { return }
        guard let handle = ControlSessionStore.current()?.handle,
              handle.hasPrefix("tenshu://epc/pair") else {
            state = .failed("No EPC invite in the shared Keychain. Open Control's Connect an app screen first.")
            return
        }
        uriText = handle
        state = .connecting
        guard let channel = try? await EpcRelayPairing.shared.connectOrNull(pairingUri: handle) else {
            state = .failed("Auto-connect failed: stale invite or unreachable relay.")
            return
        }
        pairedChannel = channel
        // Auto-confirm: the SAS check is a human anti-MITM step; the harness trusts the local relay.
        confirm(sas: channel.sas)
    }

    private var entryForm: some View {
        VStack(spacing: 16) {
            Text("Scan the QR on your Control, or paste its pairing link.")
                .font(.headline)
                .multilineTextAlignment(.center)

            Button {
                showScanner = true
            } label: {
                Label("Scan QR code", systemImage: "qrcode.viewfinder")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)

            TextField("tenshu://epc/pair#…", text: $uriText, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .font(.system(.footnote, design: .monospaced))
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .accessibilityIdentifier("epc-uri-field")

            Button("Connect") { connect(uriText) }
                .buttonStyle(.bordered)
                .disabled(uriText.isEmpty)
        }
        .frame(maxWidth: 480)
    }

    private func sasCard(sas: String) -> some View {
        VStack(spacing: 16) {
            Text("Confirm this code matches the one on your Control")
                .font(.headline)
                .multilineTextAlignment(.center)

            Text(spaced(sas))
                .font(.system(size: 44, weight: .bold, design: .monospaced))
                .tracking(4)
                .accessibilityIdentifier("epc-sas")

            Label("If the codes match, no one is in the middle", systemImage: "checkmark.shield.fill")
                .foregroundStyle(.green)
                .font(.subheadline)

            // Confirm → install the live client across the whole app (Tier 2). Reject → drop the
            // channel: a SAS mismatch means a MITM, so we tear down rather than use it.
            Button("Codes match — use this connection") { confirm(sas: sas) }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("epc-confirm")
                .padding(.top, 8)

            Button("Codes don’t match", role: .destructive) { disconnect() }
                .buttonStyle(.bordered)
        }
        .padding(24)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private func activeCard(sas: String) -> some View {
        VStack(spacing: 16) {
            Label("Live client active", systemImage: "bolt.horizontal.circle.fill")
                .foregroundStyle(.green)
                .font(.headline)
                .accessibilityIdentifier("epc-active")

            Text("Drive is now talking to Control over this channel (SAS \(spaced(sas))). Switch to the App tab to use real data.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Button("Disconnect", role: .destructive) { disconnect() }
                .buttonStyle(.bordered)
                .padding(.top, 8)
        }
        .padding(24)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private func failureCard(_ message: String) -> some View {
        VStack(spacing: 16) {
            Label(message, systemImage: "xmark.octagon.fill")
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
            Button("Try again") { reset() }
                .buttonStyle(.bordered)
        }
    }

    // Group the 6 digits "123 456" for readability, matching Control's display.
    private func spaced(_ sas: String) -> String {
        guard sas.count == 6 else { return sas }
        let mid = sas.index(sas.startIndex, offsetBy: 3)
        return "\(sas[..<mid]) \(sas[mid...])"
    }

    private func reset() {
        state = .idle
        uriText = ""
        pairedChannel = nil
    }

    private func connect(_ uri: String) {
        let trimmed = uri.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        state = .connecting
        Task {
            guard let channel = try? await EpcRelayPairing.shared.connectOrNull(pairingUri: trimmed) else {
                state = .failed("Pairing failed — is the host still showing the invite?")
                return
            }
            pairedChannel = channel
            state = .established(sas: channel.sas)
        }
    }

    // SAS confirmed: install the channel as the live client and let RootView (App tab) ride it.
    private func confirm(sas: String) {
        pairedChannel?.activate()
        NotificationCenter.default.post(name: .liveSessionChanged, object: nil)
        state = .active(sas: sas)
    }

    // Tear the live client down (SAS mismatch or explicit disconnect) → back to fakes + entry form.
    private func disconnect() {
        LiveClientSession.shared.teardown()
        NotificationCenter.default.post(name: .liveSessionChanged, object: nil)
        reset()
    }
}

#endif
