import SwiftUI
import TenshuSdk

// The folder's Share screen (full-parity UI, step B — sharing cluster), bound to the real
// `ManageAccessViewModel`. Presented for a folder: the access list (you + cross-user members) + invites,
// with revoke / remove / grant actions, plus *Invite someone* (link/QR). Both adds go through the view
// model, which shares the folder first when it isn't yet (drive-manage-access-ux.md §11). The
// SAS-confirm + completion-prompt sub-flows are a deferred follow-up.
struct ManageAccessView: View {
    let folderPath: String
    let folderName: String

    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: ManageAccessModel

    init(folderPath: String, folderName: String) {
        self.folderPath = folderPath
        self.folderName = folderName
        _model = StateObject(wrappedValue: ManageAccessModel(folderPath: folderPath))
    }

    var body: some View {
        NavigationStack {
            List {
                if model.state.sharing {
                    Section {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text("Sharing this folder…").foregroundStyle(.secondary)
                        }
                    }
                }

                Section("People with access") {
                    Label("You", systemImage: "person.crop.circle.fill")
                        .badge("Owner")
                    ForEach(model.state.members, id: \.memberId) { member in
                        Label(member.displayName, systemImage: "person.crop.circle")
                            .swipeActions {
                                Button("Remove", role: .destructive) { model.removeMember(member.memberId) }
                            }
                    }
                }

                if !model.state.invites.isEmpty {
                    Section("Invites") {
                        ForEach(model.state.invites, id: \.inviteId) { invite in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(invite.claimedName.isEmpty ? "Pending invite" : "\u{201C}\(invite.claimedName)\u{201D}")
                                Text(statusLabel(invite.status)).font(.caption).foregroundStyle(.secondary)
                            }
                            .swipeActions {
                                if invite.status == "PENDING" {
                                    Button("Revoke", role: .destructive) { model.revoke(invite.inviteId) }
                                }
                            }
                        }
                    }
                }

                Section {
                    Button {
                        model.showGrantPicker()
                    } label: {
                        Label("Grant access to a contact", systemImage: "person.badge.plus")
                    }
                    Button {
                        model.inviteSomeone()
                    } label: {
                        Label("Invite someone new", systemImage: "qrcode")
                    }
                    .accessibilityIdentifier("manage-invite-someone")
                } footer: {
                    Text("Grant access to someone you've already connected with, or invite someone new with a link or QR code.")
                }

                if let error = model.state.removeError ?? model.state.grantError ?? model.state.shareError {
                    Section { Text(error).font(.caption).foregroundStyle(.red) }
                }
            }
            .disabled(model.state.sharing)
            .navigationTitle(folderName.isEmpty ? "Share" : "Share \(folderName)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .task { await model.observe() }
            .sheet(isPresented: Binding(
                get: { model.state.showGrantPicker },
                set: { if !$0 { model.dismissGrantPicker() } }
            )) {
                GrantPickerSheet(contacts: model.state.grantableContacts) { personId in
                    model.grant(personId)
                }
            }
            .sheet(isPresented: Binding(
                get: { model.state.showInviteSheet },
                set: { if !$0 { model.dismissInviteSheet() } }
            )) {
                InviteSomeoneSheet(folderName: folderName)
            }
            // Sharing creates synced content, so the first add is storage-gated
            // (storage-capacity-gating.md §2) — the view model raises it, this renders it.
            .alert(
                "Storage needed",
                isPresented: Binding(
                    get: { model.state.storageGateBlock != nil },
                    set: { if !$0 { model.dismissStorageGate() } }
                )
            ) {
                Button("OK", role: .cancel) { model.dismissStorageGate() }
            } message: {
                Text("Sharing folders needs Tenshu storage. Set up storage in Control to start sharing.")
            }
        }
    }

    private func statusLabel(_ status: String) -> String {
        switch status {
        case "PENDING": return "Invited — link not yet redeemed"
        case "REDEEMED": return "Awaiting your confirmation"
        case "COMPLETED": return "Connected"
        case "COMPROMISED": return "Needs attention"
        default: return status.capitalized
        }
    }
}

// The grant-to-contact picker (GR-4): pick a connected, grantable contact to add to the realm.
struct GrantPickerSheet: View {
    let contacts: [ContactModel]
    var onGrant: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if contacts.isEmpty {
                    Text("No connected contacts to grant yet.").foregroundStyle(.secondary)
                } else {
                    ForEach(contacts, id: \.personId) { contact in
                        Button {
                            onGrant(contact.personId)
                            dismiss()
                        } label: {
                            Label(contact.displayName, systemImage: "person.crop.circle")
                        }
                    }
                }
            }
            .navigationTitle("Grant Access")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
    }
}

@MainActor
final class ManageAccessModel: ObservableObject {
    @Published var state: ManageAccessUiState

    private let vm: ManageAccessViewModel

    init(folderPath: String) {
        let vm = DriveShareSurface.shared.manageAccess(folderPath: folderPath)
        self.vm = vm
        self.state = vm.uiState.value
    }

    func observe() async {
        for await s in vm.uiState { state = s }
    }

    func revoke(_ inviteId: String) { vm.onRevokeInviteClicked(inviteId: inviteId) }
    func removeMember(_ memberId: String) { vm.onRemoveMember(memberId: memberId) }
    func showGrantPicker() { vm.onGrantAccessClicked() }
    func dismissGrantPicker() { vm.onGrantPickerDismissed() }
    func grant(_ personId: String) { vm.onGrantToContact(personId: personId) }
    func inviteSomeone() { vm.onInviteSomeoneClicked() }
    func dismissInviteSheet() { vm.onInviteSheetDismissed() }
    func dismissStorageGate() { vm.onStorageGateDismissed() }
}
