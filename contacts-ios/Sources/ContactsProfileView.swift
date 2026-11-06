import SwiftUI
import TenshuSdk

/// The own profile card (`contacts-app.md` D6), over the shared `ContactsProfileViewModel`.
///
/// One card, not a settings screen: the name people see when you invite them, and the invitation
/// itself. ⚠ The name is the **platform** one (`OwnerPreferences.Name`, the same row Drive and
/// Fit's profiles read) — editing it here is editing it everywhere, which is the point.
struct ContactsProfileView: View {
    @StateObject private var model = ProfileModel()
    @State private var editingName = false
    @State private var draft = ""

    var body: some View {
        let state = model.state
        List {
            Section("Your name") {
                HStack {
                    Text(displayName(state))
                    Spacer()
                    Button("Edit") {
                        draft = state?.profile.displayName ?? ""
                        editingName = true
                    }
                    .font(.footnote)
                }
                Text("This is the name people see when you invite them.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Your identity") {
                if let fingerprint = state?.profile.fingerprint, !fingerprint.isEmpty {
                    Text(fingerprint)
                        .font(.system(.caption, design: .monospaced))
                        .lineLimit(1)
                        .truncationMode(.middle)
                } else {
                    // ⚠ Honest about a real gap rather than inventing a value: there is no
                    // client-readable own-identity op anywhere in the tree yet (§9, Ph2).
                    Text("Your identity code and invitation link are not available on this device yet.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            // The legal entries: the in-app privacy policy (App Store guideline 5.1.1(i); "You" is
            // Contacts' only settings-like screen; ios-release/Sources), then the open-source notices
            // (docs/oss-licences.md). Contacts shows no third-party data.
            Section {
                PrivacyPolicyLink()
                OpenSourceLicencesLink()
            }
        }
        .navigationTitle("You")
        .task { await model.observe() }
        .alert("Your name", isPresented: $editingName) {
            TextField("Name", text: $draft)
            Button("Cancel", role: .cancel) {}
            Button("Save") { model.setName(draft) }
        }
    }

    private func displayName(_ state: ContactsProfileUiState?) -> String {
        let name = state?.profile.displayName ?? ""
        return name.isEmpty ? "No name set" : name
    }
}

@MainActor
final class ProfileModel: ObservableObject {
    private let viewModel = ContactsSurface.shared.profileViewModel()
    @Published var state: ContactsProfileUiState?

    func observe() async {
        do { for try await value in viewModel.uiState { state = value } } catch {}
    }

    func setName(_ name: String) {
        viewModel.startEditing()
        viewModel.onDraftChange(name: name)
        viewModel.save()
    }
}
