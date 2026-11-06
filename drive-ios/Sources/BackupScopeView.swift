import SwiftUI
import UIKit
import TenshuSdk

// Per-album backup selection (docs/proposals/drive-synced-folders-new-folder-prompt.md §5).
//
// The UI that lets the user narrow photo backup below the whole library — and, in doing
// so, unlocks the new-album prompt (albums are only enumerable, hence diffable, once
// "All Photos" is deselected).
//
// Shape: a two-way MODE choice, not a flat multi-select that happens to include an
// "All Photos" row. That is deliberate — All Photos and an album are overlapping views
// of the same assets, so selecting both would upload every album member twice. Making
// the exclusivity the shape of the screen means the UI cannot express the broken state
// at all, rather than relying on a rule it has to remember not to break.
struct BackupScopeView: View {
    @StateObject private var model = BackupScopeModel()

    var body: some View {
        List {
            Section {
                scopeRow(
                    title: "All Photos",
                    subtitle: "Everything in your library, including new photos as you take them.",
                    isSelected: model.allPhotos
                ) { model.chooseAllPhotos() }

                scopeRow(
                    title: "Selected albums",
                    subtitle: "Only the albums you choose. Drive will ask you when a new album appears.",
                    isSelected: !model.allPhotos
                ) { model.chooseAlbums() }
            } header: {
                Text("What to back up")
            } footer: {
                if !model.identityResolved {
                    Text("This device is still connecting to Tenshu Control. Backup choices are stored per device, so they cannot be saved yet.")
                        .foregroundStyle(.orange)
                }
            }

            if !model.allPhotos {
                Section {
                    if model.loading {
                        HStack { ProgressView(); Text("Loading albums…").foregroundStyle(.secondary) }
                    } else if model.albums.isEmpty {
                        // An empty list needs to say WHICH empty this is: a limited grant
                        // hides albums, whereas a full grant with no albums means there
                        // genuinely are none — and albums are made in Photos, not here, so
                        // hunting for an "add" button in Drive is a dead end.
                        VStack(alignment: .leading, spacing: 6) {
                            Text(model.mediaAccess == "limited"
                                 ? "No albums visible"
                                 : "No albums yet")
                                .foregroundStyle(.primary)
                            Text(model.mediaAccess == "limited"
                                 ? "Drive can only see the photos you selected, so it cannot see your albums. Give Drive access to all photos to choose albums."
                                 : "Albums are created in the Photos app. Make one there and it will show up here.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                            Button(model.mediaAccess == "limited" ? "Open Settings" : "Open Photos") {
                                model.openAlbumSource()
                            }
                            .font(.footnote)
                        }
                        .padding(.vertical, 2)
                    } else {
                        ForEach(model.albums, id: \.id) { album in
                            Button {
                                model.toggle(album)
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(album.name).foregroundStyle(.primary)
                                        Text("\(album.itemCount) photos")
                                            .font(.footnote)
                                            .foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if model.isSelected(album) {
                                        Image(systemName: "checkmark")
                                            .foregroundStyle(Color.accentColor)
                                    }
                                }
                            }
                        }
                    }
                } header: {
                    Text("Albums")
                } footer: {
                    // The honest empty state: per-album mode with nothing ticked backs up
                    // nothing, and saying so beats a silently idle backup.
                    Text(model.selectedCount == 0
                         ? "No albums selected — nothing is being backed up."
                         : "^[\(model.selectedCount) album](inflect: true) selected.")
                }
            }
        }
        .navigationTitle("Photos to back up")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.load() }
    }

    @ViewBuilder
    private func scopeRow(
        title: String,
        subtitle: String,
        isSelected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(.primary)
                    Text(subtitle).font(.footnote).foregroundStyle(.secondary)
                }
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                }
            }
        }
    }
}

@MainActor
final class BackupScopeModel: ObservableObject {
    @Published var allPhotos = true
    @Published var albums: [BackupAlbumOption] = []
    @Published var loading = false
    /// "full" | "limited" | "denied" — decides which empty state to show.
    @Published var mediaAccess = "full"
    /// Backup preferences are DEVICE-scoped; with no sync identity nothing can be stored.
    @Published var identityResolved = true

    /// Selection comes from the collected preference flow, never from a post-write read.
    @Published var selectedAlbumIds: Set<String> = []

    func isSelected(_ album: BackupAlbumOption) -> Bool { selectedAlbumIds.contains(album.id) }

    var selectedCount: Int { selectedAlbumIds.count }

    private var collecting = false

    func load() async {
        loading = true
        mediaAccess = DriveBackupSurface.shared.mediaAccessState()
        identityResolved = (try? await DriveBackupSurface.shared.isDeviceIdentityResolved())?.boolValue ?? false
        // Collect the preference flows — the same shape SyncedFoldersViewModel uses on
        // Android. Every write is reflected here when it actually lands, which is what
        // makes the checkmark truthful without any post-write polling.
        if !collecting {
            collecting = true
            Task {
                for await all in DriveBackupSurface.shared.observeIsAllPhotosScope() {
                    self.allPhotos = all.boolValue
                    await self.reloadAlbums()
                }
            }
            Task {
                for await ids in DriveBackupSurface.shared.observeSelectedAlbumIds() {
                    self.selectedAlbumIds = Set(ids)
                }
            }
        }
        await reloadAlbums()
        loading = false
    }

    // Writes are fire-and-forget; the collected flows above report what actually landed.
    func chooseAllPhotos() {
        guard !allPhotos else { return }
        Task { try? await DriveBackupSurface.shared.setAllPhotosScope() }
    }

    func chooseAlbums() {
        guard allPhotos else { return }
        Task { try? await DriveBackupSurface.shared.setAlbumScope() }
    }

    func toggle(_ album: BackupAlbumOption) {
        let wanted = !selectedAlbumIds.contains(album.id)
        // Optimistic so the row answers the tap immediately; the collected flow is
        // authoritative and will correct this if the write does not land.
        if wanted { selectedAlbumIds.insert(album.id) } else { selectedAlbumIds.remove(album.id) }
        Task {
            try? await DriveBackupSurface.shared.setAlbumSelected(albumId: album.id, selected: wanted)
        }
    }

    /// Limited access is fixed in Settings; a missing album is fixed in Photos. Send the
    /// user to whichever one can actually resolve their empty list.
    func openAlbumSource() {
        let target = mediaAccess == "limited"
            ? URL(string: UIApplication.openSettingsURLString)
            : URL(string: "photos-redirect://")
        guard let target else { return }
        UIApplication.shared.open(target)
    }

    private func reloadAlbums() async {
        albums = (try? await DriveBackupSurface.shared.listAlbums()) ?? []
    }
}
