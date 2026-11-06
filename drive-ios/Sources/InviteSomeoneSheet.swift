import SwiftUI
import UIKit
import CoreImage.CIFilterBuiltins
import TenshuSdk

// Invite-someone sheet (full-parity UI, step B — sharing cluster), bound to the real
// `InviteLinkViewModel`. Presented from Manage Access: generate a one-session contact-invite link for
// the folder, then show it as a scannable QR + copyable URL (handshake doc §2.2 — the raw link lives
// only in this session, so dismissing discards it; the *Invited* row offers Revoke, never re-show).
struct InviteSomeoneSheet: View {
    let folderName: String

    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: InviteLinkModel

    init(folderName: String) {
        self.folderName = folderName
        _model = StateObject(wrappedValue: InviteLinkModel(folderName: folderName))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label(folderName.isEmpty ? "Invite someone" : "Share \u{201C}\(folderName)\u{201D}",
                          systemImage: "person.badge.plus")
                }

                if let link = model.state.link {
                    Section("Scan to connect") {
                        if let qr = Self.qrImage(from: link) {
                            Image(uiImage: qr)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(maxWidth: .infinity)
                                .frame(height: 220)
                                .padding(.vertical, 8)
                                .accessibilityLabel("Invite QR code")
                        }
                    }
                    Section("Or send this link") {
                        Text(link)
                            .font(.footnote.monospaced())
                            .textSelection(.enabled)
                        if let url = URL(string: link) {
                            ShareLink(
                                item: url,
                                subject: Text("An invitation to connect on Tenshu")
                            ) {
                                Label("Share", systemImage: "square.and.arrow.up")
                            }
                        }
                        Button {
                            model.copy()
                        } label: {
                            Label(model.copied ? "Copied" : "Copy Link",
                                  systemImage: model.copied ? "checkmark" : "doc.on.doc")
                        }
                    }
                } else {
                    Section {
                        Button {
                            model.generate()
                        } label: {
                            if model.state.isCreating {
                                ProgressView()
                            } else {
                                Label("Generate Invite", systemImage: "qrcode")
                            }
                        }
                        .disabled(model.state.isCreating)
                        .accessibilityIdentifier("invite-generate")
                    } footer: {
                        Text("Creates a one-time link. Share it in person or send it — it works only until you connect.")
                    }
                }

                if let error = DriveShareSurface.shared.inviteLinkErrorMessage(state: model.state) {
                    Section { Text(error).font(.caption).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Invite")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .task { await model.observe() }
        }
    }

    /// Render [string] to a crisp QR via CoreImage (no QR generator existed on iOS — scanning only).
    static func qrImage(from string: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let context = CIContext()
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

@MainActor
final class InviteLinkModel: ObservableObject {
    @Published var state: InviteLinkUiState
    @Published var copied = false

    private let vm: InviteLinkViewModel

    init(folderName: String) {
        let vm = DriveShareSurface.shared.inviteLink(folderName: folderName)
        self.vm = vm
        self.state = vm.uiState.value
    }

    func observe() async {
        for await s in vm.uiState { state = s }
    }

    func generate() { vm.onGenerateClicked() }

    func copy() {
        guard let link = state.link else { return }
        UIPasteboard.general.string = link
        copied = true
    }
}
