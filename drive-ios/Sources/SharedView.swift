import SwiftUI
import TenshuSdk

// The Shared destination (full-parity UI, step B — sharing cluster), bound to the real
// `SharedScreenViewModel`. Two tabs: *My shares* (outbound link shares + Mode-B shared folders) and
// *Shared with me* (inbound realms + quarantined pending grants to accept/decline). A REDEEMED-invite
// banner opens the contact-handshake completion prompt; the resumed-redemption rows (invitee side) are
// a deferred follow-up.
struct SharedView: View {
    @StateObject private var model = SharedModel()
    @State private var tab: SharedTab = .mine
    @State private var showRedeemSheet = false

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $tab) {
                Text("My Shares").tag(SharedTab.mine)
                Text("Shared with Me").tag(SharedTab.theirs)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.vertical, 8)

            List {
                if model.state.hasRedeemedInvites { redeemedBanner }
                if tab == .mine { myShares } else { sharedWithMe }
            }
            .listStyle(.insetGrouped)
        }
        .navigationTitle("Shared")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showRedeemSheet = true
                } label: {
                    Label("Redeem invite", systemImage: "qrcode.viewfinder")
                }
                .accessibilityIdentifier("shared-redeem")
            }
        }
        .task { await model.observe() }
        .sheet(isPresented: $showRedeemSheet) {
            RedeemInviteSheet()
        }
        .sheet(isPresented: Binding(
            get: { model.state.showAcceptPicker },
            set: { if !$0 { model.dismissAcceptPicker() } }
        )) {
            AcceptFromContactSheet(contacts: model.state.grantableContacts) { fingerprint in
                model.acceptFromContact(fingerprint)
            }
        }
        .sheet(isPresented: Binding(
            get: { model.state.promptInviteId != nil },
            set: { if !$0 { model.dismissContactPrompt() } }
        )) {
            if let inviteId = model.state.promptInviteId {
                ContactPromptSheet(inviteId: inviteId)
            }
        }
    }

    // A redeemed invite is waiting for the inviter to confirm (handshake §2.3).
    @ViewBuilder private var redeemedBanner: some View {
        Section {
            Button {
                model.showContactPrompt()
            } label: {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Someone redeemed your invite")
                        Text("Tap to confirm and connect").font(.caption).foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "person.crop.circle.badge.questionmark")
                }
            }
        }
    }

    @ViewBuilder private var myShares: some View {
        if model.state.myShares.isEmpty && model.state.sharedFolders.isEmpty {
            Text("You haven't shared anything yet.").foregroundStyle(.secondary)
        } else {
            if !model.state.myShares.isEmpty {
                Section("Links") {
                    ForEach(model.state.myShares, id: \.shareId) { row in
                        HStack(spacing: 8) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.fileName)
                                // Was `revoked ? "Revoked" : "Active"` — dead copy: the repo's
                                // `shares()` flow already filters revoked rows out
                                // (`TenshuFilesRepository.shares()`), so the row could never
                                // render "Revoked". Show the path instead, matching Android.
                                Text(row.filePath).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 0)
                            shareOverflowButton(row)
                        }
                        .contextMenu { shareMenu(row) }
                        // Retained as the accelerator for the destructive action only.
                        .swipeActions {
                            Button("Revoke", role: .destructive) { model.revokeShare(row.shareId) }
                        }
                    }
                }
            }
            if !model.state.sharedFolders.isEmpty {
                Section("Shared folders") {
                    ForEach(model.state.sharedFolders, id: \.sharedRealmId) { folder in
                        Label(folder.name, systemImage: "folder.fill.badge.person.crop")
                    }
                }
            }
        }
    }

    // The action set for one outbound link share — Android's `MyShareRowItem` button pair
    // (`my_shares_copy_id` / `my_shares_revoke`), rendered by both the visible overflow menu
    // and the context menu. Copy ID hands out the FRAGMENT-LESS URL: the `#k=` secret is not
    // persisted (A11), so a listed share can be identified but never re-shared from here.
    @ViewBuilder
    private func shareMenu(_ row: MyShareRow) -> some View {
        Button {
            UIPasteboard.general.string = ShareLinkUrl.companion.pathOnly(shareId: row.shareId)
        } label: {
            Label("Copy ID", systemImage: "doc.on.doc")
        }

        Divider()

        Button(role: .destructive) {
            model.revokeShare(row.shareId)
        } label: {
            Label("Revoke", systemImage: "trash")
        }
    }

    // Visible twin of the context menu — see `FilesView.overflowButton` for why the actions
    // are not left to long-press alone.
    @ViewBuilder
    private func shareOverflowButton(_ row: MyShareRow) -> some View {
        Menu {
            shareMenu(row)
        } label: {
            Image(systemName: "ellipsis")
                .foregroundStyle(.secondary)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityIdentifier("share-overflow")
        .accessibilityLabel("Actions for \(row.fileName)")
    }

    @ViewBuilder private var sharedWithMe: some View {
        if !model.state.pendingGrants.isEmpty {
            Section("Pending") {
                ForEach(model.state.pendingGrants, id: \.fingerprint) { card in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(card.grantorName.isEmpty ? "Someone shared a folder with you" : "\(card.grantorName) shared a folder with you")
                        HStack {
                            Button("Accept") { model.acceptPending(card.fingerprint) }
                                .buttonStyle(.borderedProminent)
                            Button("Decline", role: .destructive) { model.declinePending(card.fingerprint) }
                                .buttonStyle(.bordered)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
        }

        Section("Shared with me") {
            if model.state.sharedWithMe.isEmpty {
                Text("Nothing shared with you yet.").foregroundStyle(.secondary)
            } else {
                ForEach(model.state.sharedWithMe, id: \.sharedRealmId) { realm in
                    Label(realm.name ?? "Shared folder", systemImage: "folder.fill.badge.person.crop")
                }
            }
        }

        Section {
            Button {
                model.showAcceptPicker()
            } label: {
                Label("Accept from a contact", systemImage: "tray.and.arrow.down")
            }
        }

        if let message = model.state.acceptMessage {
            Section { Text("Nothing shared from \(message) yet.").font(.caption).foregroundStyle(.secondary) }
        }
    }
}

enum SharedTab { case mine, theirs }

// Accept-a-grant picker (GR-4): pick a connected contact to pull a waiting grant from.
struct AcceptFromContactSheet: View {
    let contacts: [ContactModel]
    var onAccept: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if contacts.isEmpty {
                    Text("No connected contacts yet.").foregroundStyle(.secondary)
                } else {
                    ForEach(contacts, id: \.personId) { contact in
                        Button {
                            onAccept(contact.fingerprint)
                            dismiss()
                        } label: {
                            Label(contact.displayName, systemImage: "person.crop.circle")
                        }
                    }
                }
            }
            .navigationTitle("Accept a Folder")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
    }
}

@MainActor
final class SharedModel: ObservableObject {
    @Published var state: SharedScreenUiState

    private let vm: SharedScreenViewModel

    init() {
        let vm = DriveShareSurface.shared.sharedWithMe()
        self.vm = vm
        self.state = vm.uiState.value
    }

    func observe() async {
        for await s in vm.uiState { state = s }
    }

    func revokeShare(_ shareId: String) { vm.onRevokeShareClicked(shareId: shareId) }
    func acceptPending(_ fingerprint: String) { vm.onAcceptPendingGrant(grantorFingerprint: fingerprint) }
    func declinePending(_ fingerprint: String) { vm.onDeclinePendingGrant(grantorFingerprint: fingerprint) }
    func showAcceptPicker() { vm.onAcceptFromContactClicked() }
    func dismissAcceptPicker() { vm.onAcceptPickerDismissed() }
    func acceptFromContact(_ fingerprint: String) { vm.onAcceptFromContact(grantorFingerprint: fingerprint) }
    func showContactPrompt() { vm.onContactPromptBannerClicked() }
    func dismissContactPrompt() { vm.onContactPromptDismissed() }
}
