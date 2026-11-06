import SwiftUI
import UIKit
import TenshuSdk

// Create-share-link sheet (full-parity UI, step B — sharing cluster), bound to the real
// `CreateShareLinkViewModel`. Presented from the file detail screen: generate a Mode-A link for a
// file, then hand it to another app or copy its canonical URL.
//
// Both actions are offered, as on the KMP side (`ui/common/ShareText.kt`): `ShareLink` is the
// system share sheet — how WhatsApp / Messages / AirDrop get the link — and copying is a separate
// intent, not a fallback. ⚠ Never route the link through a web share-intent redirector
// (`wa.me/?text=`): the `#k=` fragment is only invisible to servers while it stays a fragment.
struct CreateShareLinkSheet: View {
    let filePath: String

    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: ShareLinkModel

    init(filePath: String) {
        self.filePath = filePath
        _model = StateObject(wrappedValue: ShareLinkModel(filePath: filePath))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label(model.state.fileName, systemImage: "link")
                }

                if model.state.link != nil {
                    Section("Anyone with this link can view") {
                        Text(model.copyableURL)
                            .font(.footnote.monospaced())
                            .textSelection(.enabled)
                        if let url = URL(string: model.copyableURL) {
                            ShareLink(
                                item: url,
                                subject: Text("A file shared with you: \(model.state.fileName)")
                            ) {
                                Label("Share", systemImage: "square.and.arrow.up")
                            }
                        }
                        Button {
                            model.copy()
                        } label: {
                            Label(model.copied ? "Copied" : "Copy Link", systemImage: model.copied ? "checkmark" : "doc.on.doc")
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
                                Label("Generate Link", systemImage: "link.badge.plus")
                            }
                        }
                        .disabled(model.state.isCreating)
                    }
                }

                if let error = model.state.errorMessage {
                    Section { Text(error).font(.caption).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Share Link")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task { await model.observe() }
        }
    }
}

@MainActor
final class ShareLinkModel: ObservableObject {
    @Published var state: CreateShareLinkUiState
    @Published var copied = false

    private let vm: CreateShareLinkViewModel

    init(filePath: String) {
        let vm = DriveShareSurface.shared.createShareLink(filePath: filePath)
        self.vm = vm
        self.state = vm.uiState.value
    }

    /// The canonical URL (the VM assembles host + shareId + secret fragment).
    var copyableURL: String { vm.onCopyLinkRequested() ?? "" }

    func observe() async {
        for await s in vm.uiState { state = s }
    }

    func generate() { vm.onGenerateLinkClicked() }

    func copy() {
        if let url = vm.onCopyLinkRequested() {
            UIPasteboard.general.string = url
            copied = true
        }
    }
}
