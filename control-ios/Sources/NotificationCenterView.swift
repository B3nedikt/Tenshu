import SwiftUI
import TenshuSdk

// The notification center (NT-1, docs/proposals/ios-control-notifications-and-shares.md §3) —
// the *actionable* tier of Control's central notifications. Home's ambient event feed already
// ships; this is the surface that was missing: inbound membership grants and redeemed contact
// invites, each with the actions that resolve it.
//
// ⚠⚠ **In-app only.** iOS binds `NoopNotificationPresenter` deliberately — `UNUserNotificationCenter`
// is per app and every route-1 app runs the same proxy graph, so a real presenter in Control
// would post every Calendar reminder twice. Elevation is NT-2's ownership decision, not a
// missing class. Until then this surface is live only while Control is open, which is also
// what §9.4 (no persistent background process) allows.
//
// The Kotlin `PendingRequest` sealed leaves never cross the bridge: `ControlSurface` flattens
// them into `ControlPendingRequestRow` and takes the row's **id** back for every action,
// re-resolving the leaf where the `when` is exhaustive. ⚠⚠ Accept also takes the row's
// `shownFingerprint`: the leaf is re-resolved, the key the card showed is not.

/// Swift-native mirror of the bridged row, so the list never references the SKIE type directly
/// (the `DeviceRow` treatment in `SyncDashboardView`).
private struct RequestRow: Identifiable {
    let id: String
    let kind: ControlPendingRequestKind
    let subject: String
    /// The §5.3 SAS to compare out-of-band. Empty for a membership grant, which has no SAS step.
    let verificationCode: String
    /// The raw claimed fingerprint this card renders the code for. ⚠⚠ Sent back on accept as
    /// `shownFingerprint` from the row the user tapped — never re-read Kotlin-side — so a claim
    /// that flips before this view re-renders cannot be confirmed (threat model T-CI-10).
    let shownFingerprint: String
    let seen: Bool
    let inProgress: Bool
    let errorMessage: String?
    let timestampLabel: String
}

@MainActor
final class NotificationsModel: ObservableObject {
    @Published fileprivate var rows: [RequestRow] = []
    /// Drives the Home bell badge. Not `rows.count` — the center lists dismissed requests and
    /// the badge does not count them (the Phase-4 seen-vs-resolved distinction).
    @Published var unreadCount: Int = 0

    private let bound = ControlSurface.shared.notificationsBound()

    init() {
        apply(bound.snapshots.value)
    }

    func observe() async {
        for await snapshot in bound.snapshots {
            apply(snapshot)
        }
    }

    /// Re-poll the poll-only sources (membership grants are a 30 s sweep, not reactive).
    func refresh() {
        bound.viewModel.refresh()
    }

    fileprivate func accept(_ row: RequestRow, petname: String, sasConfirmed: Bool) {
        ControlSurface.shared.acceptRequest(
            bound: bound,
            id: row.id,
            shownFingerprint: row.shownFingerprint,
            petname: petname,
            sasConfirmed: sasConfirmed
        )
    }

    fileprivate func decline(_ row: RequestRow) {
        ControlSurface.shared.declineRequest(bound: bound, id: row.id)
    }

    fileprivate func dismiss(_ row: RequestRow) {
        ControlSurface.shared.dismissRequest(bound: bound, id: row.id)
    }

    fileprivate func clearError(_ row: RequestRow) {
        bound.viewModel.dismissError(id: row.id)
    }

    private func apply(_ snapshot: ControlNotificationsSnapshot) {
        rows = snapshot.rows.map {
            RequestRow(
                id: $0.id,
                kind: $0.kind,
                subject: $0.subject,
                verificationCode: $0.verificationCode,
                shownFingerprint: $0.shownFingerprint,
                seen: $0.seen,
                inProgress: $0.inProgress,
                errorMessage: $0.errorMessage,
                timestampLabel: $0.timestampLabel
            )
        }
        unreadCount = Int(snapshot.unreadCount)
    }
}

struct NotificationCenterView: View {
    @StateObject private var model = NotificationsModel()

    var body: some View {
        List {
            if model.rows.isEmpty {
                // Copy mirrors the Compose `notifications_empty_title` verbatim so the two
                // surfaces read identically (ADR 0016: the catalogue is the English source).
                ControlEmptyState(
                    title: "You are all caught up",
                    systemImage: "bell"
                )
                .listRowBackground(Color.clear)
            } else {
                ForEach(model.rows) { row in
                    PendingRequestCard(row: row, model: model)
                }
            }
        }
        .navigationTitle("Notifications")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { model.refresh() } label: { Image(systemName: "arrow.clockwise") }
                    .accessibilityLabel("Refresh")
            }
        }
        .task { await model.observe() }
        // NT-2b: the other in-context moment — a user who opened the notification centre is already
        // asking for these. See ControlSurface.requestNotificationPermission for why not at launch.
        .task { ControlSurface.shared.requestNotificationPermission() }
    }
}

/// One actionable request. The two variants differ enough — a grant is accept/decline, an
/// invite needs a name and a code comparison first — that they get separate bodies rather
/// than one parameterised card, mirroring the Compose `GrantBody` / `InviteBody` split.
private struct PendingRequestCard: View {
    let row: RequestRow
    @ObservedObject var model: NotificationsModel

    /// Invite-only. Seeded empty: the claimed name is the peer's *self-asserted* one, and
    /// prefilling it would quietly turn an unverified claim into the user's own label.
    @State private var petname: String = ""
    /// The fingerprint the "codes match" tick was made against, or nil. ⚠⚠ The tick is DERIVED from
    /// it, so a claim that moves under the card (threat model T-CI-10) is unticked in the same
    /// render that shows it; keyed to the fingerprint, not the six-digit code, because two keys can
    /// share a code.
    @State private var confirmedFingerprint: String?

    private var sasConfirmed: Bool {
        !row.shownFingerprint.isEmpty && confirmedFingerprint == row.shownFingerprint
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header

            switch row.kind {
            case .membershipGrant:
                Text(row.subject)
                    .font(.footnote.monospaced())
                    .foregroundStyle(.secondary)
            case .contactInviteRedeemed:
                inviteBody
            default:
                // SKIE bridges the Kotlin enum as a Swift enum, but an @unknown-style default
                // is still required for a non-frozen import. A new variant lands as a card
                // with no actions rather than a crash — and NT-2/Phase 5 will add its own case.
                EmptyView()
            }

            if let message = row.errorMessage {
                errorRow(message)
            }

            actions
        }
        .padding(.vertical, 4)
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.headline)
                Text(row.timestampLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            // A request already dismissed on any device keeps no dismiss affordance — the
            // marker is synced, so offering it again would be a no-op the user can't see.
            if !row.seen {
                Button {
                    model.dismiss(row)
                } label: {
                    Image(systemName: "xmark")
                }
                .buttonStyle(.borderless)
                .disabled(row.inProgress)
                .accessibilityLabel("Dismiss")
            }
        }
    }

    private var title: String {
        switch row.kind {
        case .membershipGrant: return "Someone wants to share a folder with you"
        case .contactInviteRedeemed: return "A contact accepted your invite"
        default: return "New request"
        }
    }

    private var inviteBody: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(row.subject)
                .font(.body)

            Text("Verification code")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(row.verificationCode)
                .font(.title3.monospaced())
                .textSelection(.enabled)
            Text("Compare this code with your contact in person or over a trusted channel, then confirm.")
                .font(.caption)
                .foregroundStyle(.secondary)

            Toggle("The codes match", isOn: Binding(
                get: { sasConfirmed },
                set: { confirmedFingerprint = $0 ? row.shownFingerprint : nil }
            ))
                .disabled(row.inProgress)

            TextField("Name for this contact", text: $petname)
                .textFieldStyle(.roundedBorder)
                .disabled(row.inProgress)
        }
    }

    private func errorRow(_ message: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
            Text(message)
                .font(.footnote)
            Spacer()
            Button("Dismiss") { model.clearError(row) }
                .font(.footnote)
                .buttonStyle(.borderless)
        }
    }

    private var actions: some View {
        HStack {
            Spacer()
            Button("Decline", role: .destructive) { model.decline(row) }
                .buttonStyle(.borderless)
                .disabled(row.inProgress)
            Button(confirmLabel) {
                model.accept(row, petname: petname, sasConfirmed: sasConfirmed)
            }
            .buttonStyle(.borderedProminent)
            .disabled(row.inProgress || !confirmEnabled)
        }
    }

    private var confirmLabel: String {
        row.kind == .contactInviteRedeemed ? "Confirm" : "Accept"
    }

    /// An invite needs a name before it can become a contact; a grant has nothing to fill in.
    /// ⚠ The SAS toggle deliberately does **not** gate this — it upgrades the resulting
    /// identity from UNVERIFIED to VERIFIED, it does not authorise the action (the shared
    /// repository KDoc and Drive's `ContactPromptDialog` take the same line). It does wait for the
    /// claim to load: the proxy refuses an empty shown fingerprint (T-CI-10).
    private var confirmEnabled: Bool {
        row.kind == .contactInviteRedeemed
            ? !petname.trimmingCharacters(in: .whitespaces).isEmpty && !row.shownFingerprint.isEmpty
            : true
    }
}

/// The pinned slot for Home: unresolved, undismissed requests, above the ambient feed.
/// Renders nothing when there is nothing to act on, so Home is unchanged in the common case.
struct PinnedRequestsSection: View {
    @ObservedObject var model: NotificationsModel

    var body: some View {
        let pinned = model.pinnedRows
        if !pinned.isEmpty {
            Section("Needs your attention") {
                ForEach(pinned) { row in
                    PendingRequestCard(row: row, model: model)
                }
            }
        }
    }
}

extension NotificationsModel {
    /// Undismissed requests only — the pinned slot is an elevation surface, so a dismissed
    /// request must leave it even though the center still lists it.
    fileprivate var pinnedRows: [RequestRow] { rows.filter { !$0.seen } }
}
