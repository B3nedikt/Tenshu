import SwiftUI
import TenshuSdk

// Data → Shares (SH-1, docs/proposals/ios-control-notifications-and-shares.md §4) — the
// realm-wide inventory of what leaves this realm, and the place to take it back.
//
// Two halves, matching the Compose screen: **links** (per-file share links, created in Drive,
// listed and revoked here) and **shared folders** (owned + joined realms). The folder half
// returned `emptyList()` on every platform until SH-0 unstubbed `TenshuShareManager` against
// the GR ops, so this is the first surface to render it anywhere.
//
// Revoked links stay in the list with a badge — Control is the inventory *and the history*,
// unlike Drive's *My shares*, which filters them out.

private struct ShareRow: Identifiable {
    let id: String
    let fileName: String
    let filePath: String
    /// Pre-formatted Kotlin-side so this list and the Compose one render dates identically.
    let createdLabel: String
    /// "Expires <date> · Up to N opens", or empty when the link has neither cap.
    let capsLabel: String
    let revoked: Bool
}

private struct FolderRow: Identifiable {
    let id: String
    /// ⚠ Nil while the realm's wrapper-root `SharedFile` has not synced — the joiner's first
    /// moments. Not an error state, and not a blank title: the view supplies the fallback.
    let name: String?
    /// ⚠ Nil means the roster read *failed*, not that the folder is empty. Rendering "0
    /// members" would be a wrong answer stated confidently, so the row omits the count.
    let memberCount: Int?
    let ownerSelf: Bool
}

@MainActor
final class SharesModel: ObservableObject {
    @Published fileprivate var shares: [ShareRow] = []
    @Published fileprivate var folders: [FolderRow] = []
    /// Non-nil while the user is confirming a revoke of that share.
    @Published fileprivate var pendingRevokeId: String?

    private let viewModel = ControlSurface.shared.sharesViewModel()

    init() {
        apply(viewModel.uiState.value)
    }

    func observe() async {
        for await state in viewModel.uiState {
            apply(state)
        }
    }

    fileprivate func armRevoke(_ row: ShareRow) {
        viewModel.onRevokeClicked(shareId: row.id)
    }

    fileprivate func confirmRevoke() {
        viewModel.onRevokeConfirmed()
    }

    fileprivate func cancelRevoke() {
        viewModel.onRevokeDismissed()
    }

    fileprivate func share(withId id: String) -> ShareRow? {
        shares.first { $0.id == id }
    }

    private func apply(_ state: SharesUiState) {
        shares = ControlSurface.shared.shareRows(state: state).map {
            ShareRow(
                id: $0.id,
                fileName: $0.fileName,
                filePath: $0.filePath,
                createdLabel: $0.createdLabel,
                capsLabel: $0.capsLabel,
                revoked: $0.revoked
            )
        }
        folders = ControlSurface.shared.sharedFolderRows(state: state).map {
            // `KotlinInt?` → `Int?`: the nullability is load-bearing (see FolderRow), so it is
            // carried across rather than defaulted here.
            FolderRow(
                id: $0.id,
                name: $0.name,
                memberCount: $0.memberCount.map { count in Int(truncating: count) },
                ownerSelf: $0.ownerSelf
            )
        }
        pendingRevokeId = state.pendingRevokeShareId
    }
}

struct SharesView: View {
    @StateObject private var model = SharesModel()

    var body: some View {
        List {
            if model.shares.isEmpty && model.folders.isEmpty {
                ControlEmptyState(
                    title: "Nothing shared yet",
                    systemImage: "link",
                    description: "Links you share from Drive show up here, so you can see and revoke them."
                )
                .listRowBackground(Color.clear)
            } else {
                if !model.shares.isEmpty {
                    Section("Links") {
                        ForEach(model.shares) { share in
                            shareRow(share)
                        }
                    }
                }
                // Renders only when this realm is in at least one shared folder — owned or
                // joined. Empty for most realms, and that is the honest state now that the
                // read is real (before SH-0 it was empty for everyone).
                if !model.folders.isEmpty {
                    Section("Shared folders") {
                        ForEach(model.folders) { folder in
                            folderRow(folder)
                        }
                    }
                }
            }
        }
        .navigationTitle("Shares")
        .confirmationDialog(
            "Revoke link?",
            isPresented: Binding(
                get: { model.pendingRevokeId != nil },
                set: { if !$0 { model.cancelRevoke() } }
            ),
            titleVisibility: .visible
        ) {
            Button("Revoke share", role: .destructive) { model.confirmRevoke() }
            Button("Cancel", role: .cancel) { model.cancelRevoke() }
        } message: {
            let name = model.pendingRevokeId.flatMap { model.share(withId: $0)?.fileName } ?? "this file"
            Text("Anyone holding the link to \(name) loses access immediately.")
        }
        .task { await model.observe() }
    }

    private func shareRow(_ share: ShareRow) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(share.fileName)
                    .font(.headline)
                if share.revoked {
                    Text("Revoked")
                        .font(.caption2)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.secondary.opacity(0.2), in: Capsule())
                }
            }
            Text(share.filePath)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(share.capsLabel.isEmpty ? share.createdLabel : "\(share.createdLabel) · \(share.capsLabel)")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .swipeActions(edge: .trailing) {
            // Revoked rows keep their place in the history but offer nothing to do.
            if !share.revoked {
                Button("Revoke", role: .destructive) { model.armRevoke(share) }
            }
        }
    }

    private func folderRow(_ folder: FolderRow) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            // Same fallback Drive's *Shared with me* uses for an unsynced wrapper root.
            Text(folder.name ?? "Shared folder")
                .font(.headline)
            Text(folderSubtitle(folder))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    /// Mirrors the Compose row exactly, including the null-count branch: a folder whose roster
    /// could not be read says what it knows rather than claiming zero members.
    private func folderSubtitle(_ folder: FolderRow) -> String {
        let ownerSuffix = folder.ownerSelf ? " · you own this" : ""
        guard let count = folder.memberCount else {
            return folder.ownerSelf ? "You own this" : "Shared with you"
        }
        return count == 1 ? "1 member\(ownerSuffix)" : "\(count) members\(ownerSuffix)"
    }
}
