import SwiftUI
import TenshuSdk

// Control-on-iOS EPC app-pairing host ("Connect an app") — the §11.1 third flow and the Control-side
// counterpart to drive-ios's EPC pairing client. It's the first surface to drive the proxy's
// `EpcPairingHost.run()` on iOS: Control provisions a relay rendezvous session, shows the
// `tenshu://epc/pair#…` invite as a QR (+ copyable text) for an app to scan, and surfaces the 6-digit
// SAS to confirm once the app handshakes. Binds `IosEpcPairingController` through `ControlSurface`
// (House Rule #5: native UI over the shared runtime). Strings mirror the canonical EPC pairing copy.
//
// ⚠ The invite only appears once the host provisions the relay session, so this needs a reachable
// relay (the iosMain proxy is wired to `localhost:8080` — a dev `:sync-relay-server:run` on the host
// Mac is reachable from the simulator). Without one the screen lands in the error state.

@MainActor
final class EpcAppPairingModel: ObservableObject {
    @Published var statusKey: String = EpcPairingStrings.statusStarting
    @Published var pairingUri: String? = nil
    @Published var sas: String? = nil
    @Published var errorMessage: String? = nil
    @Published var qrImage: UIImage? = nil

    private let controller = ControlSurface.shared.epcPairingController()

    func observe() async {
        for await s in controller.uiState {
            statusKey = s.statusKey
            if s.pairingUri != pairingUri {
                pairingUri = s.pairingUri
                qrImage = s.pairingUri.flatMap { qrCodeImage(from: $0) }
                // Also publish the live invite into the shared Keychain item. ⚠ No shipping
                // reader is left: Fit's silent `ControlAutoConnect` was deleted 2026-11-05 (route 1
                // covers the co-resident case; threat model, the retired "Co-resident EPC
                // auto-connect" sub-amendment). Only drive-ios's DEV_HARNESS
                // `-epcAutoConnectFromControl` still reads it.
                if let uri = s.pairingUri {
                    _ = ControlSurface.shared.publishEpcInvite(uri: uri)
                }
            }
            sas = s.sas
            errorMessage = s.error
        }
    }

    func start() { controller.start() }
    func stop() { controller.stop() }
}

struct EpcAppPairingView: View {
    let onClose: () -> Void

    @StateObject private var model = EpcAppPairingModel()

    var body: some View {
        NavigationStack {
            OnboardingScaffold {
                Text(EpcPairingStrings.heading)
                    .font(.onboardingHeadline)
                    .multilineTextAlignment(.center)
                vGap(8)
                Text(EpcPairingStrings.body)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                vGap(24)

                switch model.statusKey {
                case "connected": connectedContent
                case "error": errorContent
                case "waiting": waitingContent
                default: startingContent
                }
            }
            .navigationTitle(EpcPairingStrings.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(EpcPairingStrings.close) { onClose() }
                }
            }
            .task { await model.observe() }
            .onAppear { model.start() }
            .onDisappear { model.stop() }
        }
        .tint(OnboardingColors.primary)
    }

    // MARK: - States

    @ViewBuilder private var startingContent: some View {
        ProgressView()
            .tint(OnboardingColors.primary)
        vGap(12)
        Text(EpcPairingStrings.starting)
            .font(.callout)
            .foregroundStyle(.secondary)
    }

    @ViewBuilder private var waitingContent: some View {
        if let qr = model.qrImage {
            Image(uiImage: qr)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .frame(width: 220, height: 220)
            vGap(16)
        }
        if let uri = model.pairingUri {
            inviteField(uri)
            vGap(20)
        }
        HStack(spacing: 10) {
            ProgressView().tint(OnboardingColors.primary)
            Text(EpcPairingStrings.waiting)
                .font(.callout)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private var connectedContent: some View {
        Text(EpcPairingStrings.confirmHeading)
            .font(.callout.weight(.medium))
            .multilineTextAlignment(.center)
        vGap(16)
        Text(formattedSas)
            .font(.system(size: 34, weight: .regular, design: .monospaced))
            .tracking(4)
        vGap(16)
        Text(EpcPairingStrings.confirmBody)
            .font(.callout)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
    }

    @ViewBuilder private var errorContent: some View {
        Text(model.errorMessage ?? EpcPairingStrings.genericError)
            .font(.callout)
            .foregroundStyle(Color(uiColor: .systemRed))
            .multilineTextAlignment(.center)
        vGap(20)
        Button(EpcPairingStrings.retry) {
            model.stop()
            model.start()
        }
        .buttonStyle(PrimaryButtonStyle())
    }

    /// The invite URI as selectable monospaced text + a Copy affordance (iOS analogue of the canonical
    /// read-only field + copy IconButton).
    @ViewBuilder private func inviteField(_ uri: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text(uri)
                .font(.system(size: 12, design: .monospaced))
                .textSelection(.enabled)
                .lineLimit(2)
                .truncationMode(.middle)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                UIPasteboard.general.string = uri
            } label: {
                Image(systemName: "doc.on.doc")
            }
            .accessibilityLabel(EpcPairingStrings.copyClipboard)
        }
        .padding(12)
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(Color.secondary.opacity(0.4), lineWidth: 1)
        )
    }

    /// Render the 6-digit SAS as "XXX YYY" (the canonical 3-3 split), or verbatim if it isn't 6 digits.
    private var formattedSas: String {
        guard let sas = model.sas else { return "" }
        guard sas.count == 6 else { return sas }
        let mid = sas.index(sas.startIndex, offsetBy: 3)
        return "\(sas[sas.startIndex..<mid]) \(sas[mid...])"
    }
}

// MARK: - Strings (mirror the EPC app-pairing copy)

enum EpcPairingStrings {
    static let title = "Connect an App"
    static let close = "Cancel"

    static let heading = "Connect an app to this realm"
    static let body = "On the app (e.g. Tenshu Drive), choose \u{201C}Connect to Control\u{201D} and scan this code."

    static let starting = "Starting…"
    static let waiting = "Waiting for an app to connect…"

    static let confirmHeading = "An app connected. Confirm this code matches the one shown in the app:"
    static let confirmBody = "If the codes don't match, cancel and try again."

    static let retry = "Try again"
    static let genericError = "Couldn't start pairing. Check that the relay is reachable, then try again."
    static let copyClipboard = "Copy to clipboard"

    // Status keys (mirror IosEpcPairingController.EpcPairingUiState.STATUS_*).
    static let statusStarting = "starting"
}
