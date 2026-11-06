import SwiftUI
import TenshuSdk

/// One contact (`contacts-app.md` D3/D4), over the shared `ContactDetailViewModel`.
///
/// The header carries the petname and the derived trust state; underneath it the **keyring**, one
/// row per key. Showing the keyring rather than a single "verified" flag is the point: trust lives
/// on the key, and a person can hold several. It is what makes the Q2b demotion legible — the user
/// sees the old verified key sitting beside the new unverified one, instead of being told without
/// explanation that a checkmark disappeared.
struct ContactDetailView: View {
    let personId: String

    @StateObject private var model: ContactDetailModel

    init(personId: String) {
        self.personId = personId
        _model = StateObject(wrappedValue: ContactDetailModel(personId: personId))
    }

    var body: some View {
        let state = model.state
        List {
            if let contact = state?.contact {
                Section {
                    VStack(spacing: 12) {
                        ContactAvatarView(contact: contact, size: 96)
                        Text(contact.displayName.isEmpty ? "Unnamed" : contact.displayName)
                            .font(.title2)
                        TrustSummaryView(trust: contact.trust)
                        if contact.avatar != nil {
                            // Only when there is one to remove. No confirmation: an imported card
                            // keeps its PHOTO, so re-importing brings it back.
                            Button("Remove photo") { model.viewModel.onRemovePhotoClicked() }
                                .font(.footnote)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .listRowSeparator(.hidden)
                }

                if !contact.details.isEmpty {
                    Section("Contact info") {
                        ForEach(Array(contact.details.phones.enumerated()), id: \.offset) { _, field in
                            LabeledRow(label: field.label, value: field.value)
                        }
                        ForEach(Array(contact.details.emails.enumerated()), id: \.offset) { _, field in
                            LabeledRow(label: field.label, value: field.value)
                        }
                        // ⚠ Addresses are part of `details` (and of its `isEmpty`), so leaving them
                        // out showed an address-only contact an empty "Contact info" section.
                        ForEach(Array(contact.details.addresses.enumerated()), id: \.offset) { _, field in
                            LabeledRow(label: field.label, value: field.value)
                        }
                        if !contact.details.organisation.isEmpty {
                            LabeledRow(label: "Organisation", value: contact.details.organisation)
                        }
                        if !contact.details.note.isEmpty {
                            LabeledRow(label: "Note", value: contact.details.note)
                        }
                    }
                }

                Section("Keys") {
                    if contact.identities.isEmpty {
                        Text("This contact has no Tenshu key. Exchange an invitation to connect with them.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(contact.identities, id: \.fingerprint) { identity in
                        KeyRow(identity: identity, model: model)
                    }
                }
            } else if state?.loaded == true {
                ContentUnavailableCompat(
                    title: "Contact not found",
                    body: "They may have been deleted on another device."
                )
            }
        }
        .navigationTitle("Contact")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
        // ⚠ The compare-and-confirm gesture (Q2a). The copy has to make clear that confirming is
        // an assertion the user is making about a comparison they performed — nothing here is a
        // cryptographic check, so any other trigger would fabricate trust (threat-model A24).
        .alert("Compare your codes", isPresented: verifyingBinding) {
            Button("Cancel", role: .cancel) { model.viewModel.onVerifyDismissed() }
            Button("They match") { model.viewModel.onVerifyConfirmed() }
                .disabled(state?.verifying?.hasSas != true)
        } message: {
            if let prompt = state?.verifying, prompt.hasSas {
                Text("Read these six digits to them — over a call, or in person. They see the same six on their side. Only confirm if they match.\n\n\(prompt.sas)")
            } else {
                // No digits: still loading, or the realm is locked. Either way there is nothing to
                // compare, and confirm stays disabled above.
                Text("The code is not available right now. Unlock this device and try again.")
            }
        }
    }

    private var verifyingBinding: Binding<Bool> {
        Binding(
            get: { model.state?.verifying != nil },
            set: { if !$0 { model.viewModel.onVerifyDismissed() } }
        )
    }
}

/// The badge, with the sentence that earns it. `NeedsReverification` is the one that has to say
/// what happened: "not verified" alone would read as though the user had never got round to it.
private struct TrustSummaryView: View {
    let trust: TrustState

    var body: some View {
        HStack(spacing: 6) {
            TrustBadgeView(trust: trust)
            Text(label)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    // ⚠ `onEnum(of:)` — `TrustState` is a Kotlin sealed interface, which SKIE bridges as a protocol
    // whose members are types rather than cases. See `TrustBadgeView` for the full note.
    private var label: String {
        switch onEnum(of: trust) {
        case .verified: return "Verified"
        case .needsReverification: return "Key changed"
        case .unverified: return "Not verified"
        case .notConnected: return "Not connected"
        }
    }
}

private struct KeyRow: View {
    let identity: ContactIdentity
    let model: ContactDetailModel

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // ⚠ The claimed name is quoted because that is what it is — a claim by whoever holds
            // the key, never promoted to the contact's name.
            if !identity.selfAssertedName.isEmpty {
                Text("Introduced themselves as \"\(identity.selfAssertedName)\"")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Text(identity.fingerprint)
                .font(.system(.caption, design: .monospaced))
                .lineLimit(1)
                .truncationMode(.middle)
            HStack {
                if identity.verification == Verification.verified {
                    Button("Un-verify") {
                        model.viewModel.onUnverifyClicked(fingerprint: identity.fingerprint)
                    }
                } else {
                    Button("Verify") {
                        model.viewModel.onVerifyClicked(fingerprint: identity.fingerprint)
                    }
                }
                Spacer()
                Button("Remove key", role: .destructive) {
                    model.viewModel.onUnlinkClicked(fingerprint: identity.fingerprint)
                }
            }
            .font(.footnote)
        }
        .padding(.vertical, 2)
    }
}

private struct LabeledRow: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if !label.isEmpty {
                Text(label).font(.caption).foregroundStyle(.secondary)
            }
            Text(value)
        }
    }
}

@MainActor
final class ContactDetailModel: ObservableObject {
    let viewModel: ContactDetailViewModel
    @Published var state: ContactDetailUiState?

    init(personId: String) {
        viewModel = ContactsSurface.shared.contactDetailViewModel(personId: personId)
    }

    func observe() async {
        do { for try await value in viewModel.uiState { state = value } } catch {}
    }
}
