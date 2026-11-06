import SwiftUI
import TenshuSdk

// What the Collections tab opens (photos-ios.md §8.15): an album's grid (Favourites is
// one), a derived section's grid (a place, a year, a person, Videos), and a section's *View all* page.
//
// ⚠ A tapped photograph opens the viewer over THIS collection's photographs — whole, so nothing pages
// (`FixedViewerSource`, Compose's `PhotoCollection.Fixed`) — not over the feed.
//
// An album's edits — rename, delete, remove a photograph (§8.16): the shared
// `AlbumDetailViewModel` decides and reports each; this file only asks and says what it answered.
//
// ⚠ Not here: a person's header, naming and "Photos with…" — they wait on face grouping on iOS.

/// A grid of photographs — the shared layout of every collection's page.
struct PhotoGrid: View {
    let photos: [PhotosPhoto]
    let onOpen: (PhotosPhoto) -> Void
    /// A tile's "Remove from Album" — `nil` where there is no membership to edit (a derived section,
    /// Favourites): the action is absent there, not disabled.
    var onRemove: ((PhotosPhoto) -> Void)? = nil
    /// An edit is in flight (`AlbumDetailUiState.writing`): the edits are single-flight.
    var editing = false
    /// A line under a tile, or `nil` for none — Search's source badge (`PhotoSearchCopy.badge`, §8.19).
    var caption: ((PhotosPhoto) -> String?)? = nil
    /// Lines under the whole grid — Search's "showing the first N" (§8.19).
    var footers: [String] = []
    private let previews = PreviewGate()
    @Environment(\.displayScale) private var scale

    var body: some View {
        GeometryReader { geometry in
            // ⚠ The column count is the shared rule (`gridWidthScale`): three on a phone, more on a wider
            // grid — the same answer Compose's `WidthScaledGridCells` gets.
            let columns = Int(GridWidthScaleKt.collectionGridColumns(widthDp: Float(geometry.size.width)))
            let spacing: CGFloat = 2
            let edge = floor((geometry.size.width - spacing * CGFloat(columns - 1)) / CGFloat(columns))
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.fixed(edge), spacing: spacing), count: columns), spacing: spacing) {
                    ForEach(photos, id: \.path) { photo in
                        VStack(alignment: .leading, spacing: 1) {
                            Button { onOpen(photo) } label: {
                                PhotoTileView(photo: photo, edgePoints: edge, scale: scale, previews: previews)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier(photo.path.split(separator: "/").last.map(String.init) ?? photo.path)
                            .contextMenu {
                                if let onRemove {
                                    // ⚠ "from Album": the photograph stays in the library — `RemoveFromAlbum`
                                    // cannot reach it. Android's long-press does the same, at once.
                                    Button(role: .destructive) { onRemove(photo) } label: {
                                        Label("Remove from Album", systemImage: "rectangle.stack.badge.minus")
                                    }
                                    .disabled(editing)
                                }
                            }
                            if let line = caption?(photo) {
                                Text(line)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                                    .padding(.horizontal, 2)
                                    .frame(width: edge, alignment: .leading)
                            }
                        }
                    }
                }
                ForEach(footers, id: \.self) { footer in
                    Text(footer)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                }
            }
        }
    }
}

/// The viewer route over a collection: the photograph tapped, by its path.
private struct CollectionViewerRoute: Hashable {
    let path: String
}

/// An album's grid — or Favourites, the pseudo-album (`AlbumDetailViewModel.FAVOURITES`).
struct AlbumGridScreen: View {
    let albumId: String
    let title: String
    /// An edit HAPPENED — the parent re-reads. ⚠⚠ Back stays enabled while an edit runs, so a user
    /// can leave first and the Collections tab's return refresh can land BEFORE the edit: without
    /// this the card keeps its old name, count or presence. ⚠ Handed to the VIEW MODEL
    /// (`onEditLanded`), never observed here: an edit can land after this screen has gone, and only
    /// the view model is still there to hear it.
    let onEdited: () -> Void
    @StateObject private var model: AlbumGridModel
    @State private var viewing: CollectionViewerRoute?
    /// ⚠ The view model's initialiser already reads the album; only a RETURN re-reads.
    @State private var appeared = false
    @State private var renaming = false
    @State private var newName = ""
    @State private var confirmingDelete = false
    /// What an edit that did not simply happen says — refused, or unconfirmed (`AlbumEdit.report`).
    @State private var report: AlbumEditReport?
    @Environment(\.dismiss) private var dismiss

    init(albumId: String, title: String, onEdited: @escaping () -> Void = {}) {
        self.albumId = albumId
        self.title = title
        self.onEdited = onEdited
        _model = StateObject(wrappedValue: AlbumGridModel(albumId: albumId, title: title, onEditLanded: onEdited))
    }

    var body: some View {
        Group {
            if model.state.loading && model.state.photos.isEmpty {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let status = model.state.statusText {
                // The shared sentence — the error, else the empty copy (`AlbumDetailUiState.statusText`).
                Text(status)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(32)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                PhotoGrid(
                    photos: model.state.photos,
                    onOpen: { viewing = CollectionViewerRoute(path: $0.path) },
                    onRemove: model.state.editable ? { remove($0) } : nil,
                    editing: model.state.writing
                )
            }
        }
        .navigationTitle(shownTitle)
        .navigationBarTitleDisplayMode(.inline)
        // ⚠⚠ No leaving while an edit runs — the button AND the swipe: a reopened
        // album is a new view model whose own lock is free, and a delete there could run while this
        // edit's rename — an upsert — can still land and re-create the album.
        .navigationBarBackButtonHidden(model.state.writing)
        .toolbar {
            // ⚠ Favourites has no name or membership of its own: no menu at all (`editable`).
            if model.state.editable {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button {
                            newName = shownTitle
                            renaming = true
                        } label: { Label("Rename Album", systemImage: "pencil") }
                        Button(role: .destructive) { confirmingDelete = true } label: {
                            Label("Delete Album", systemImage: "trash")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .disabled(model.state.writing)
                    .accessibilityLabel("Album options")
                }
            }
        }
        .alert("Rename Album", isPresented: $renaming) {
            TextField("Name", text: $newName)
            Button("Cancel", role: .cancel) {}
            Button("Save") {
                model.viewModel.rename(name: newName) { outcome in report = AlbumEdit.rename.report(outcome: outcome) }
            }
            // ⚠ A blank name is not a rename (the view model refuses it too) — and not Control's
            // failure either, so it is never offered rather than reported.
            // ⚠ Nor the name it already has: nothing to write (`boundedWrite` step 0 agrees).
            .disabled(newName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                || newName.trimmingCharacters(in: .whitespacesAndNewlines) == shownTitle)
        }
        .confirmationDialog("Delete “\(shownTitle)”?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Delete Album", role: .destructive) {
                // ⚠⚠ Leaves only when the delete HAPPENED; a refusal keeps the album on screen and
                // says so — the viewer's trash rule.
                // The screen closes on `deleted` (below) — a late delete must close it too.
                model.viewModel.delete { outcome in report = AlbumEdit.delete.report(outcome: outcome) }
            }
        } message: {
            Text(AlbumDetailViewModelKt.ALBUM_DELETE_CONFIRMATION)
        }
        .alert(
            report?.title ?? "",
            isPresented: Binding(get: { report != nil }, set: { if !$0 { report = nil } }),
            presenting: report
        ) { _ in
            Button("OK") {}
        } message: { report in
            Text(report.message)
        }
        // ⚠⚠ Leaves only once the delete HAPPENED — answered, or confirmed late — never on a refusal:
        // the viewer's trash rule.
        .onChange(of: model.state.deleted) { deleted in if deleted { dismiss() } }
        .task { await model.observe() }
        .navigationDestination(isPresented: Binding(get: { viewing != nil }, set: { if !$0 { viewing = nil } })) {
            if let viewing {
                // ⚠ No refresh from the viewer: the return below re-reads, which also covers a favourite
                // removed there — one read, not two.
                PhotoViewerScreen(photos: model.state.photos, path: viewing.path) {}
            }
        }
        // ⚠ Back from the viewer re-reads the album: a favourite removed or a photograph trashed there
        // must not still be on this grid. Not on the first appearance — the view model just read it.
        .onAppear {
            if appeared {
                if viewing == nil { model.viewModel.refresh() }
            } else {
                appeared = true
            }
        }
    }
}

extension AlbumGridScreen {
    private var shownTitle: String { model.state.title.isEmpty ? title : model.state.title }

    private func remove(_ photo: PhotosPhoto) {
        model.viewModel.remove(photo: photo) { outcome in report = AlbumEdit.remove.report(outcome: outcome) }
    }
}

@MainActor
final class AlbumGridModel: ObservableObject {
    let viewModel: AlbumDetailViewModel
    @Published private(set) var state: AlbumDetailUiState

    init(albumId: String, title: String, onEditLanded: @escaping () -> Void) {
        viewModel = PhotosSurface.shared.albumDetail(albumId: albumId, title: title, onEditLanded: onEditLanded)
        state = viewModel.state.value
    }

    func observe() async {
        for await value in viewModel.state { state = value }
    }
}

/// A derived section's grid — its photographs come from the Collections state, computed from one feed
/// walk; nothing is re-queried (Compose's `Destination.Smart`).
struct SmartGridScreen: View {
    let id: String
    @ObservedObject var collections: CollectionsModel
    /// ⚠⚠ The last resolution, kept for THIS screen: a person's aliases can vanish under an open screen
    /// (consent withdrawn after a naming chunk saved), and the last good answer shows their photographs
    /// rather than a blank grid — Compose's `lastResolved`.
    @State private var lastResolved: SmartAlbum?
    @State private var viewing: CollectionViewerRoute?

    /// The section as the Collections state resolves it right now — `nil` once its id and every alias
    /// are gone. ⚠ Only a LIVE resolution: a card drawn from the on-device snapshot (§36 step 2) has no
    /// photographs, so it is never kept as [lastResolved].
    private var resolved: SmartAlbum? {
        guard let album = collections.state.smartAlbumFor(id: id), album.snapshotCard == nil else { return nil }
        return album
    }

    /// ⚠⚠ The shared answer (`CollectionsUiState.smartPage`, Compose's `Destination.Smart`): a snapshot
    /// card tapped before its live read lands is LOADING — never an empty grid, which on a person reads
    /// as "no photos" — and a failed read says so.
    private var page: SmartPage { collections.state.smartPage(smartId: id, lastResolved: lastResolved) }

    var body: some View {
        let page = self.page
        let album = page.album
        let photos = (page.loading || page.failed) ? [] : (album?.photos ?? [])
        Group {
            if page.loading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if page.failed {
                Text("Something went wrong loading this album.")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(32)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                PhotoGrid(photos: photos) { viewing = CollectionViewerRoute(path: $0.path) }
            }
        }
            .navigationTitle(album?.title ?? "")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                if let resolved { lastResolved = resolved }
                // This grid draws the Collections state too (§8.22.3).
                collections.viewModel.setShown(surface: "smart", shown: true)
            }
            .onDisappear { collections.viewModel.setShown(surface: "smart", shown: false) }
            // ⚠⚠ The WHOLE resolution, not its id: a person's id can stay put while their name and
            // photographs change, and keeping only the first version would make a later alias loss
            // fall back to stale photographs. Kotlin's data-class `equals` is the comparison.
            .onChange(of: resolved) { newer in if let newer { lastResolved = newer } }
            .navigationDestination(isPresented: Binding(get: { viewing != nil }, set: { if !$0 { viewing = nil } })) {
                if let viewing {
                    PhotoViewerScreen(photos: photos, path: viewing.path) { collections.viewModel.changed() }
                }
            }
    }
}

/// A section's *View all* page — every card in it, as a grid (`photos-app.md` §35).
struct SectionScreen: View {
    let section: CollectionSection
    @ObservedObject var collections: CollectionsModel
    /// ⚠ A RETURN re-reads (§8.16): an album renamed or deleted from this page must not still be on
    /// it. Not the first appearance — the Collections state it opened from is current.
    @State private var appeared = false

    var body: some View {
        ScrollView {
            // §36: a failed read is said on the section's own page too — the shared sentence, with Try Again.
            if let failure = CollectionsLayoutCompanion.shared.sectionFailure(
                section: section, state: collections.state, groupingRunsHere: false
            ) {
                HStack {
                    Text(failure).font(.footnote).foregroundStyle(.red)
                    Spacer()
                    Button("Try Again") { collections.viewModel.retry() }.font(.footnote)
                }
                .padding(.horizontal, 16)
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: section == .people ? 96 : 150), spacing: 12)], spacing: 16) {
                switch section {
                case .albums:
                    ForEach(collections.state.albums, id: \.albumId) { album in
                        NavigationLink(value: CollectionsRoute.album(id: album.albumId, title: album.name)) {
                            CollectionCardView(
                                title: album.name,
                                subtitle: PhotoCountKt.photoCount(count: album.photoCount),
                                cover: album.coverPath.map { CoverRef(path: $0, width: album.coverPixelWidth?.int32Value ?? 0,
                                                                       height: album.coverPixelHeight?.int32Value ?? 0) },
                                placeholderSymbol: "rectangle.stack",
                                edge: 150
                            )
                        }
                        .buttonStyle(.plain)
                    }
                case .people:
                    // Named first, as Compose orders them (`CollectionsUiState.peopleNamedFirst`).
                    ForEach(collections.state.peopleNamedFirst, id: \.id) { person in
                        NavigationLink(value: CollectionsRoute.smart(id: person.id)) { PersonCardView(person: person) }
                            .buttonStyle(.plain)
                    }
                default:
                    ForEach(smartAlbums, id: \.id) { smart in
                        NavigationLink(value: CollectionsRoute.smart(id: smart.id)) {
                            CollectionCardView(title: smart.title, subtitle: smart.subtitle,
                                               cover: CoverRef(smart: smart), placeholderSymbol: "photo", edge: 150)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(16)
        }
        .navigationTitle(section.title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            collections.viewModel.setShown(surface: "section", shown: true)
            if appeared { collections.viewModel.refreshIfChanged() } else { appeared = true }
        }
        .onDisappear { collections.viewModel.setShown(surface: "section", shown: false) }
    }

    private var smartAlbums: [SmartAlbum] {
        switch section {
        case .places: return collections.state.places
        case .things: return collections.state.things
        case .years: return collections.state.years
        default: return []
        }
    }
}
