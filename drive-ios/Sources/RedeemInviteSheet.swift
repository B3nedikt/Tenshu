import SwiftUI
import TenshuSdk

// Redeem-invite flow (full-parity UI, step B — sharing cluster), bound to the real `RedeemInviteViewModel`
// (handshake doc §3). The *invitee* pastes an invite link → confirms their own name → redeems → waits for
// the inviter to confirm. Stages: EnterLink → ConfirmName → Waiting → Accepted / Rejected / Gone / Invalid.
// QR-scan entry (feeding `initialLink`) is a follow-up; this slice is paste-based.
struct RedeemInviteSheet: View {
    let initialLink: String?

    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: RedeemInviteModel
    @State private var showScanner = false

    init(initialLink: String? = nil) {
        self.initialLink = initialLink
        _model = StateObject(wrappedValue: RedeemInviteModel(initialLink: initialLink))
    }

    var body: some View {
        NavigationStack {
            Form {
                switch onEnum(of: model.state.stage) {
                case .enterLink:
                    enterLink
                case .confirmName:
                    confirmName
                case .waiting:
                    waiting
                case .accepted(let accepted):
                    terminal(
                        icon: "checkmark.circle.fill", tint: .green,
                        title: accepted.ack.peerName.isEmpty ? "Connected" : "Connected with \u{201C}\(accepted.ack.peerName)\u{201D}",
                        message: "You're now connected."
                    )
                case .rejected:
                    terminal(icon: "xmark.circle.fill", tint: .secondary,
                             title: "Invite declined", message: "They chose not to connect.")
                case .gone:
                    terminal(icon: "exclamationmark.triangle.fill", tint: .orange,
                             title: "This invite is no longer valid", message: "It may have expired, been revoked, or already been used.")
                case .invalid:
                    terminal(icon: "exclamationmark.triangle.fill", tint: .red,
                             title: "Couldn't verify this invite", message: "Something didn't check out. Ask them to send a new one.")
                }
            }
            .navigationTitle("Redeem Invite")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
            .task { await model.observe() }
            .sheet(isPresented: $showScanner) {
                NavigationStack {
                    QrScannerView { scanned in
                        showScanner = false
                        model.enterScanned(scanned)
                    }
                    .ignoresSafeArea()
                    .navigationTitle("Scan Invite QR")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel") { showScanner = false }
                        }
                    }
                }
            }
        }
    }

    private var enterLink: some View {
        Group {
            Section {
                Button {
                    showScanner = true
                } label: {
                    Label("Scan invite QR code", systemImage: "qrcode.viewfinder")
                }
                .accessibilityIdentifier("redeem-scan")
            } footer: {
                Text("Scan the QR they're showing you, or paste their invite link below.")
            }
            Section("Or paste your invite link") {
                TextField("https://tenshu.app/i#…", text: Binding(
                    get: { model.state.pastedLink },
                    set: { model.setPastedLink($0) }
                ))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .accessibilityIdentifier("redeem-link-field")
            }
            if let paste = model.state.pasteError {
                Section { Text(pasteErrorText(paste)).font(.caption).foregroundStyle(.red)
                    .accessibilityIdentifier("redeem-paste-error") }
            }
            Section {
                Button("Continue") { model.enterLink() }
                    .disabled(model.state.pastedLink.isEmpty)
                    .accessibilityIdentifier("redeem-continue")
            }
        }
    }

    private var confirmName: some View {
        Group {
            Section {
                if !model.state.inviterFingerprint.isEmpty {
                    Text("Their security fingerprint")
                        .font(.caption).foregroundStyle(.secondary)
                    Text(model.state.inviterFingerprint)
                        .font(.footnote.monospaced())
                }
            }
            Section("Your name as they'll see it") {
                TextField("Your name", text: Binding(
                    get: { model.state.selfName },
                    set: { model.setSelfName($0) }
                ))
            }
            if let error = DriveShareSurface.shared.redeemInviteErrorMessage(state: model.state) {
                Section { Text(error).font(.caption).foregroundStyle(.red) }
            }
            Section {
                Button {
                    model.confirm()
                } label: {
                    if model.state.isSubmitting { ProgressView() } else { Text("Connect") }
                }
                .disabled(model.state.isSubmitting || model.state.selfName.isEmpty)
            }
        }
    }

    private var waiting: some View {
        Group {
            Section {
                HStack(spacing: 12) {
                    ProgressView()
                    Text("Waiting for them to confirm…")
                }
            }
            if !model.state.sas.isEmpty {
                Section("Security code") {
                    Text(model.state.sas)
                        .font(.title3.monospaced())
                        .frame(maxWidth: .infinity, alignment: .center)
                    Toggle("This matches the code on their screen", isOn: Binding(
                        get: { model.state.sasConfirmed },
                        set: { model.setSasConfirmed($0) }
                    ))
                }
            }
        }
    }

    private func terminal(icon: String, tint: Color, title: String, message: String) -> some View {
        Section {
            VStack(spacing: 12) {
                Image(systemName: icon).font(.largeTitle).foregroundStyle(tint)
                Text(title).font(.headline).multilineTextAlignment(.center)
                Text(message).font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Button("Done") { dismiss() }
                    .buttonStyle(.borderedProminent)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
    }

    private func pasteErrorText(_ error: PasteError) -> String {
        switch error {
        case .unsupportedVersion: return "This invite was made by a newer version of Tenshu. Update the app to redeem it."
        default: return "That doesn't look like a Tenshu invite link."
        }
    }
}

@MainActor
final class RedeemInviteModel: ObservableObject {
    @Published var state: RedeemInviteUiState

    private let vm: RedeemInviteViewModel

    init(initialLink: String?) {
        let vm = DriveShareSurface.shared.redeemInvite(initialLink: initialLink)
        self.vm = vm
        self.state = vm.uiState.value
    }

    func observe() async {
        for await s in vm.uiState { state = s }
    }

    func setPastedLink(_ value: String) { vm.onPastedLinkChanged(value: value) }
    func enterLink() { vm.onLinkEntered(link: state.pastedLink) }
    /// A scanned QR string (a `tenshu-invite:` URI or messaging link) — parse it directly.
    func enterScanned(_ link: String) { vm.onLinkEntered(link: link) }
    func setSelfName(_ value: String) { vm.onSelfNameChanged(value: value) }
    func confirm() { vm.onConfirmClicked() }
    func setSasConfirmed(_ value: Bool) { vm.onSasConfirmedChanged(confirmed: value) }
}
