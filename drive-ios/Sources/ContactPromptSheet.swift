import SwiftUI
import TenshuSdk

// Completion prompt, bound to the real `ContactPromptViewModel` (handshake doc §2.3). Shown to the
// *inviter* once their invite is REDEEMED: confirm the SAS, name the contact (or link to an existing
// person), add your own name, then Accept/Decline. Pure UI over the synced ContactInvite row.
// ⚠ The §2 grant offer is NOT here — it is a card on the Shared screen
// (`SharedScreenUiState.grantOffers`), and iOS has no surface for it.
struct ContactPromptSheet: View {
    let inviteId: String

    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: ContactPromptModel

    init(inviteId: String) {
        self.inviteId = inviteId
        _model = StateObject(wrappedValue: ContactPromptModel(inviteId: inviteId))
    }

    var body: some View {
        NavigationStack {
            // ⚠ No grant-offer branch: the offer is `SharedScreenUiState.grantOffers`, pinned by
            // `ContactPromptViewModelTest.…theGrantOfferIsNotThisScreensJob`. This screen just
            // confirms the contact.
            Group { confirmForm }
            .navigationTitle("Connect")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
            .task { await model.observe() }
            .onChange(of: model.state.done) { done in
                if done { dismiss() }
            }
        }
    }

    // The §2.3 confirm form — claim header, SAS check, petname, link-to-existing, your name.
    private var confirmForm: some View {
        Form {
            Section {
                Text(model.state.claimedName.isEmpty ? "Someone wants to connect" : "\u{201C}\(model.state.claimedName)\u{201D} wants to connect")
                    .font(.headline)
                if !model.state.claimedFingerprint.isEmpty {
                    Text(model.state.claimedFingerprint)
                        .font(.footnote.monospaced())
                        .foregroundStyle(.secondary)
                }
                Text("They chose this name themselves — confirm it's really them before connecting.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if !model.state.claimedSas.isEmpty {
                Section("Security code") {
                    Text(model.state.claimedSas)
                        .font(.title3.monospaced())
                        .frame(maxWidth: .infinity, alignment: .center)
                    Toggle("This matches the code on their screen", isOn: Binding(
                        get: { model.state.sasConfirmed },
                        set: { model.setSasConfirmed($0) }
                    ))
                }
            }

            Section("Name this contact") {
                TextField("Their name", text: Binding(
                    get: { model.state.petname },
                    set: { model.setPetname($0) }
                ))
                .disabled(model.state.selectedPersonId != nil)
            }

            Section {
                DisclosureGroup("Link to an existing contact", isExpanded: Binding(
                    get: { model.state.linkExpanded },
                    set: { _ in model.toggleLinkExpanded() }
                )) {
                    if model.state.persons.isEmpty {
                        Text("No existing contacts yet.").font(.caption).foregroundStyle(.secondary)
                    } else {
                        ForEach(model.state.persons, id: \.personId) { person in
                            Button {
                                model.selectPerson(person.personId)
                            } label: {
                                HStack {
                                    Image(systemName: model.state.selectedPersonId == person.personId ? "largecircle.fill.circle" : "circle")
                                    Text(person.displayName.isEmpty ? "Unnamed" : person.displayName)
                                    Spacer()
                                }
                            }
                            .tint(.primary)
                        }
                    }
                }
            }

            Section {
                TextField("Your name", text: Binding(
                    get: { model.state.ownName },
                    set: { model.setOwnName($0) }
                ))
            } header: {
                Text("Your name")
            } footer: {
                Text("Shared with this contact so they know who connected.")
            }

            if let error = DriveShareSurface.shared.contactPromptErrorMessage(state: model.state) {
                Section { Text(error).font(.caption).foregroundStyle(.red) }
            }

            Section {
                Button {
                    model.accept()
                } label: {
                    if model.state.isSubmitting { ProgressView() } else { Text("Connect") }
                }
                // `canSave`, not just `isSubmitting`: no accept before the claim has loaded —
                // the proxy refuses an empty shownFingerprint (threat model T-CI-10).
                .disabled(!model.state.canSave)
                Button(role: .destructive) {
                    model.decline()
                } label: {
                    Text("Decline")
                }
                .disabled(model.state.isSubmitting)
            }
        }
    }

}

@MainActor
final class ContactPromptModel: ObservableObject {
    @Published var state: ContactPromptUiState

    private let vm: ContactPromptViewModel

    init(inviteId: String) {
        let vm = DriveShareSurface.shared.contactPrompt(inviteId: inviteId)
        self.vm = vm
        self.state = vm.uiState.value
    }

    func observe() async {
        for await s in vm.uiState { state = s }
    }

    func setPetname(_ value: String) { vm.onPetnameChanged(value: value) }
    func setOwnName(_ value: String) { vm.onOwnNameChanged(value: value) }
    func setSasConfirmed(_ value: Bool) { vm.onSasConfirmedChanged(confirmed: value) }
    func toggleLinkExpanded() { vm.onLinkExpandedToggled() }
    func selectPerson(_ personId: String) { vm.onPersonSelected(personId: personId) }
    func accept() { vm.onSaveClicked() }
    func decline() { vm.onDeclineClicked() }
}
