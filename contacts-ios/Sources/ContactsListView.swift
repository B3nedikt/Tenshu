import SwiftUI
import TenshuSdk

/// The badged contact list (`contacts-app.md` D3), over the shared `ContactListViewModel`.
///
/// **One list, not two**: connected and import-only contacts sit together and are told apart by the
/// row badge, because users think in terms of people, not key material. Same rule the Compose
/// screen implements — this is the same view model, so the rule is not reimplemented here.
struct ContactsListView: View {
    let onOpenContact: (String) -> Void
    let onOpenDuplicates: () -> Void

    @StateObject private var model = ContactListModel()
    /// The search field's own text. ⚠ Not a binding read back from `state.query`: that value
    /// arrives a main-loop turn after each keystroke (the view model's StateFlow, through SKIE), so
    /// a fast typist's field was reset to the stale query mid-word and kept only some letters.
    @State private var query = ""

    var body: some View {
        List {
            let state = model.state
            // Somebody accepted an invitation and is waiting to be saved. Above everything,
            // including the empty state: the very first contact often arrives exactly this way.
            ForEach(state?.pendingConfirmations ?? [], id: \.inviteId) { confirmation in
                let name = confirmation.claimedName.isEmpty ? "Someone" : confirmation.claimedName
                Label("\"\(name)\" accepted your invitation", systemImage: "person.badge.plus")
                    .listRowBackground(Color.accentColor.opacity(0.12))
            }

            // ⚠ While a query is typed the list is the shared view model's `searchResults`, not
            // the whole book: rendering `contacts` here regardless (as this screen did) left the
            // search field filtering nothing at all.
            let searching = !(state?.query ?? "").trimmingCharacters(in: .whitespaces).isEmpty

            if !searching && (state?.duplicateCount ?? 0) > 0 {
                Button(action: onOpenDuplicates) {
                    Label(
                        "\(state?.duplicateCount ?? 0) possible duplicates",
                        systemImage: "arrow.triangle.merge"
                    )
                }
            }

            if state?.isEmpty == true {
                // An empty address book and an unreachable Control are different situations. The
                // banner above owns the second; this copy owns the first, and must not claim to
                // explain the other.
                // ⚠ iOS-only wording: this app can neither add a contact nor create or accept an
                // invitation, so every contact arrives through the realm from another of the user's
                // devices. The Compose string ("…anyone you add yourself") is true on Android, where
                // adding exists — do not copy it back here.
                ContentUnavailableCompat(
                    title: String(localized: "No contacts yet", comment: "Contacts list: empty state title"),
                    body: String(
                        localized: "People you add or connect with on your other Tenshu devices appear here.",
                        comment: "Contacts list: empty state body (iOS cannot add contacts or send invitations)"
                    )
                )
            } else if searching && state?.isEmptySearch == true {
                ContentUnavailableCompat(
                    title: String(localized: "No matches", comment: "Contacts search: no results title"),
                    body: String(
                        localized: "Nobody's name, phone number, email address or organisation matches \"\(state?.query ?? "")\".",
                        comment: "Contacts search: no results body; %@ = the query"
                    )
                )
            } else {
                ForEach((searching ? state?.searchResults : state?.contacts) ?? [], id: \.personId) { contact in
                    Button { onOpenContact(contact.personId) } label: {
                        ContactRowView(contact: contact)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Contacts")
        // Native `.searchable` rather than a hand-built field, the same call `drive-ios`'s search
        // made (§24.5): the platform's search chrome is what iOS users expect, and the filtering
        // itself is the shared view model's.
        .searchable(text: $query, prompt: "Search your contacts")
        .onChange(of: query) { model.viewModel.onQueryChange(query: $0) }
        .task { await model.observe() }
    }
}

/// One person, with the badge that tells connected from import-only.
struct ContactRowView: View {
    let contact: Contact

    var body: some View {
        HStack(spacing: 12) {
            ContactAvatarView(contact: contact)
            VStack(alignment: .leading, spacing: 2) {
                Text(contact.displayName.isEmpty ? "Unnamed" : contact.displayName)
                // Supporting text ONLY for the one actionable state. A calm list is what makes a
                // changed key visible at a glance; repeating "not verified" under every row would
                // bury it.
                if case .needsReverification = onEnum(of: contact.trust) {
                    Text("Key changed")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            TrustBadgeView(trust: contact.trust)
        }
        .padding(.vertical, 4)
        // ⚠ The whole row is the button. Under `.buttonStyle(.plain)` only what is drawn takes a
        // tap, so without this a tap on the row's empty middle (the Spacer) opened nothing.
        .contentShape(Rectangle())
    }
}

/// The four badge states (D3). ⚠ `NotConnected` renders **no badge**: once import landed it became
/// the majority state, and badging it reads as a warning about people the user simply typed in.
struct TrustBadgeView: View {
    let trust: TrustState

    var body: some View {
        // ⚠ `onEnum(of:)`, not `==` against a member: `TrustState` is a Kotlin **sealed interface**,
        // which SKIE bridges to Swift as a protocol plus this switchable companion — the members
        // are types (`TrustStateVerified`), never cases on `TrustState` itself. Switching this way
        // is also exhaustive, so a fifth state added in Kotlin fails the build here instead of
        // silently falling through to "no badge".
        switch onEnum(of: trust) {
        case .verified:
            Image(systemName: "checkmark.seal.fill")
                .foregroundStyle(.green)
                .accessibilityLabel("Verified")
        case .needsReverification:
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
                .accessibilityLabel("Key changed")
        case .unverified:
            Image(systemName: "seal")
                .foregroundStyle(.secondary)
                .accessibilityLabel("Not verified")
        case .notConnected:
            EmptyView()
        }
    }
}

/// The contact's photo when they have one (Ph8), otherwise the monogram.
///
/// ⚠ `UIImage(data:)` rather than anything shared: the Compose side decodes through its own
/// expect/actual, and the whole point of the surface split is that the *view* is native. Bytes that
/// will not decode fall back to the monogram — a contact whose picture this device cannot read is
/// still a contact.
///
/// ⚠ The bytes arrive through `avatarData()` (Kotlin `iosMain`) rather than off `contact.avatar`
/// directly: `KotlinByteArray`'s whole Swift surface is `size` + `get(index:)`, so reading it here
/// would mean one bridged call per byte, on every pass of a list `body`.
struct ContactAvatarView: View {
    let contact: Contact
    var size: CGFloat = 40

    var body: some View {
        if let data = contact.avatarData(), let image = UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: size, height: size)
                .clipShape(Circle())
        } else {
            ZStack {
                Circle().fill(Color.secondary.opacity(0.18))
                Text(initials(of: contact.displayName))
                    .font(.system(size: size * 0.38, weight: .medium))
            }
            .frame(width: size, height: size)
        }
    }

    /// Up to two initials — first letter of the first and last word; `?` for a name with no
    /// letters, which an imported record can carry.
    private func initials(of name: String) -> String {
        let letters = name
            .split(whereSeparator: { $0 == " " || $0 == "\t" || $0 == "\n" })
            .compactMap { $0.first(where: { $0.isLetter || $0.isNumber }) }
        switch letters.count {
        case 0: return "?"
        case 1: return String(letters[0]).uppercased()
        default: return "\(letters[0])\(letters[letters.count - 1])".uppercased()
        }
    }
}

/// A `ContentUnavailableView` that also builds on iOS 16 (the deployment target).
struct ContentUnavailableCompat: View {
    let title: String
    let body_: String

    init(title: String, body: String) {
        self.title = title
        self.body_ = body
    }

    var body: some View {
        VStack(spacing: 8) {
            Text(title).font(.headline)
            Text(body_).font(.subheadline).foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
        .listRowSeparator(.hidden)
    }
}

@MainActor
final class ContactListModel: ObservableObject {
    let viewModel = ContactsSurface.shared.contactListViewModel()
    /// `nil` until the first emission — the screen renders nothing rather than a fabricated
    /// "empty" while the first read is in flight.
    @Published var state: ContactListUiState?

    func observe() async {
        // SKIE renders a `StateFlow` as a throwing `AsyncSequence`; it never actually throws here
        // (the unreachable-`catch` warning is a known SKIE artefact — parity doc §6).
        do { for try await value in viewModel.uiState { state = value } } catch {}
    }
}
