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
// A person's page (IN-6 F3b, F6a, F6b; `ios-face-grouping.md` F7) is the same grid under a header —
// the face, the name, the count and the "With…" chip — with naming in the toolbar. Who is a person,
// whether naming is offered and whose verdict a dialog is are the shared `personPage`; every word is
// `PersonPageCopy`'s, the words Compose says.

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
    /// What heads the grid and scrolls with it — a person's header (F7); `nil` for none.
    var header: AnyView? = nil
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
                if let header { header }
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
/// walk; nothing is re-queried (Compose's `Destination.Smart`). A person's opens with their header.
struct SmartGridScreen: View {
    let id: String
    @ObservedObject var collections: CollectionsModel
    /// ⚠⚠ The last resolution, kept for THIS screen: a person's aliases can vanish under an open screen
    /// (consent withdrawn after a naming chunk saved), and the last good answer shows their photographs
    /// rather than a blank grid — Compose's `lastResolved`.
    @State private var lastResolved: SmartAlbum?
    @State private var viewing: CollectionViewerRoute?
    /// The "With…" second person, by id — ⚠ this route's own (`PersonHeader.key`), so opening someone
    /// else starts un-narrowed; and it survives the viewer, which keeps this screen alive beneath it.
    @State private var withId: String?
    @State private var choosing = false
    @State private var naming = false
    @State private var typedName = ""

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
        // ⚠⚠ The shared `personPage`: who is a person, whether naming is offered, whose verdict this is —
        // and ⚠⚠ none of it where this phone groups no faces (`PhotosSurface.personPage`): with the flags
        // off a synced name is still a person in the state, but naming could never save, so the page is
        // the plain grid it was before F7.
        let person = PhotosSurface.shared.personPage(state: collections.state, smartId: id, page: page)
        let second = person.header?.chosenCompanion(withId: withId)
        let visible = second.map { AlbumDetailViewModelKt.photosWithBoth(first: photos, second: $0.photos) } ?? photos
        // ⚠⚠ No naming while narrowed: naming writes the name to EVERY photo of this person, not the
        // intersection on screen — from "Anna & Bob" it would label Anna-only photos nobody can see.
        let nameId: String? = second == nil ? person.nameId : nil
        let title = album?.title ?? ""
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
            } else if let header = person.header {
                PhotoGrid(
                    photos: visible,
                    onOpen: { viewing = CollectionViewerRoute(path: $0.path) },
                    header: AnyView(VStack(alignment: .leading, spacing: 0) {
                        PersonHeaderView(
                            name: title,
                            header: header,
                            fallback: photos.first,
                            shownCount: visible.count,
                            second: second,
                            onAddName: nameId == nil ? nil : { startNaming(unfinished: person.unfinishedName) },
                            onChooseWith: { choosing = true },
                            onClearWith: { withId = nil }
                        )
                        // IN-6 F6c name suggestions — ⚠ only where naming is offered (`nameId`, which is
                        // nil while narrowed and wherever this phone groups no faces): a Yes IS a naming.
                        if let nameId {
                            NameSuggestionView(
                                person: person,
                                name: title,
                                onYes: { collections.viewModel.acceptSuggestion(smartId: nameId) },
                                onNo: { collections.viewModel.rejectSuggestion(smartId: nameId) }
                            )
                        }
                    })
                )
            } else {
                PhotoGrid(photos: photos) { viewing = CollectionViewerRoute(path: $0.path) }
            }
        }
            // ⚠ A person's name heads the grid instead, beside their face — shown twice it reads as two things.
            .navigationTitle(person.header == nil ? title : "")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // ⚠⚠ Always offered on a person, and never "Rename": naming is ADDITIVE, so on a named
                // card it adds a SECOND person (`PersonPageCopy`). ⚠ Absent, not inert, elsewhere.
                if nameId != nil {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(PersonPageCopy.shared.nameAction(unfinished: person.unfinishedName != nil, titleBlank: title.isEmpty)) {
                            startNaming(unfinished: person.unfinishedName)
                        }
                    }
                }
            }
            .alert(PersonPageCopy.shared.dialogTitle(initial: person.unfinishedName ?? ""), isPresented: $naming) {
                TextField(PersonPageCopy.shared.NAME_FIELD, text: $typedName)
                Button(PersonPageCopy.shared.CANCEL, role: .cancel) {}
                Button(PersonPageCopy.shared.SAVE) {
                    if let nameId { collections.viewModel.nameGroup(smartId: nameId, name: typedName) }
                }
                // ⚠ Disabled rather than accepting-then-ignoring: a blank name is a no-op.
                .disabled(typedName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            } message: {
                Text(PersonPageCopy.shared.NAME_SYNCS)
            }
            // ⚠ Bounded at the field: the name is repeated in every call the write is split into.
            .onChange(of: typedName) { typed in
                let limit = Int(PersonPageCopy.shared.NAME_MAX_LENGTH)
                if typed.count > limit { typedName = String(typed.prefix(limit)) }
            }
            // ⚠⚠ Rendering a refusal is not optional: the alert closes on Save, so without this the
            // person is simply still unnamed and the user concludes it worked.
            .alert(
                person.nameFailure.map { PersonPageCopy.shared.failureTitle(outcome: $0) } ?? "",
                isPresented: Binding(
                    get: { person.nameFailure != nil },
                    set: { if !$0 { collections.viewModel.dismissNameRefusal() } }
                )
            ) {
                Button(PersonPageCopy.shared.OK) { collections.viewModel.dismissNameRefusal() }
            } message: {
                Text(person.nameFailure.map { PersonPageCopy.shared.failureText(outcome: $0) } ?? "")
            }
            .sheet(isPresented: $choosing) {
                CompanionPicker(companions: person.header?.companions ?? []) { chosen in
                    choosing = false
                    withId = chosen.id
                }
            }
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
                    // ⚠ The viewer pages through the list the user is LOOKING at — the "With…"
                    // intersection when one is chosen.
                    PhotoViewerScreen(photos: visible, path: viewing.path) { collections.viewModel.changed() }
                }
            }
    }

    /// ⚠⚠ The INTERRUPTED name when finishing one, and empty otherwise — never the card's title: on a
    /// named card the act is "add another person", and the data layer cannot rename.
    private func startNaming(unfinished: String?) {
        typedName = unfinished ?? ""
        naming = true
    }
}

/// WHO a person's page is about, before any photograph — the face, the name and the count, and the
/// "With…" chip (Compose's `PersonHeaderRow`).
///
/// ⚠ An unnamed group's name slot IS the naming action, where one is offered.
/// ⚠⚠ No "Only them" chip, as on Android: the face count it would rely on is the detector's, which
/// misses the people behind (`photos-people-and-pets.md` §6a).
private struct PersonHeaderView: View {
    let name: String
    let header: PersonHeader
    /// The first photograph, whole — the face when this phone can place none (a synced name).
    let fallback: PhotosPhoto?
    let shownCount: Int
    let second: SmartAlbum?
    let onAddName: (() -> Void)?
    let onChooseWith: () -> Void
    let onClearWith: () -> Void

    private var copy: PersonPageCopy { PersonPageCopy.shared }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: second == nil ? 16 : 28) {
                ZStack(alignment: .topLeading) {
                    FaceCoverView(cover: header.cover, edge: 72, fallback: fallback.map {
                        // ⚠⚠ `previewPath`: an edited photograph is drawn by its rendition or not at all — the
                        // original can hold what a crop or Redact hides (ED-3, `photos-editing.md` §12.4).
                        CoverRef(path: $0.previewPath, width: $0.pixelWidth?.int32Value ?? 0, height: $0.pixelHeight?.int32Value ?? 0)
                    })
                    // The second face, smaller and overlapping the first's lower corner.
                    if let second {
                        FaceCoverView(cover: second.coverFace, edge: 40, fallback: CoverRef(smart: second))
                            .offset(x: 44, y: 40)
                    }
                }
                VStack(alignment: .leading, spacing: 2) {
                    if let second {
                        Text(copy.both(first: name, second: second.title)).font(.title2.weight(.semibold))
                    } else if !name.isEmpty {
                        Text(name).font(.title2.weight(.semibold))
                    } else if let onAddName {
                        Button(copy.ADD_A_NAME, action: onAddName).font(.title3)
                    } else {
                        Text(copy.UNNAMED).font(.title2.weight(.semibold))
                    }
                    Text(PhotoCountKt.photoCount(count: Int32(shownCount))).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            // ⚠ Absent when there is nobody else to pick, rather than a chip that opens an empty list.
            if !header.companions.isEmpty {
                if let second {
                    // ⚠ Tapping the chip anywhere removes the second person — the ✕ says so.
                    Button(action: onClearWith) {
                        Label(copy.withChip(second: second.title), systemImage: "xmark")
                    }
                    .buttonStyle(.bordered)
                    .accessibilityLabel(copy.removeWith(second: second.title))
                } else {
                    Button(action: onChooseWith) { Label(copy.WITH, systemImage: "plus") }
                        .buttonStyle(.bordered)
                }
            }
            if second != nil && shownCount == 0 {
                Text(copy.NO_PHOTOS_TOGETHER)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(32)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// *"Is this Anna?"* on an unnamed person, or a display fold's unconfirmed photographs on a named one
/// (IN-6 F6c name suggestions) — Compose's `NameSuggestionRow`, in `PersonPageCopy`'s words. Absent when
/// there is nothing to ask.
private struct NameSuggestionView: View {
    let person: PersonPage
    let name: String
    let onYes: () -> Void
    let onNo: () -> Void

    private var copy: PersonPageCopy { PersonPageCopy.shared }

    var body: some View {
        let asked = person.suggestedName
        let folded = Int(person.unconfirmedCount)
        if asked != nil || folded > 0 {
            VStack(alignment: .leading, spacing: 6) {
                if let asked {
                    Text(copy.suggestionQuestion(name: asked)).font(.headline)
                    Text(copy.SUGGESTION_EXPLAINED).font(.footnote).foregroundStyle(.secondary)
                } else {
                    Text(copy.unconfirmed(count: Int32(folded), name: name)).font(.subheadline)
                }
                if person.suggestionRejectFailed {
                    Text(copy.SUGGESTION_NOT_SAVED).font(.footnote).foregroundStyle(.red)
                }
                HStack(spacing: 12) {
                    Button(asked != nil ? copy.SUGGESTION_YES : copy.CONFIRM, action: onYes)
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("suggestion.yes")
                    Button(asked != nil ? copy.SUGGESTION_NO : copy.notThem(name: name), action: onNo)
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("suggestion.no")
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Pick the second person for the "With…" chip — Compose's `CompanionPicker`. Unnamed people are
/// offered too: the intersection is by photo path (`photosWithBoth`), the same for both.
private struct CompanionPicker: View {
    let companions: [SmartAlbum]
    let onChoose: (SmartAlbum) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(companions, id: \.id) { companion in
                Button { onChoose(companion) } label: {
                    HStack(spacing: 16) {
                        FaceCoverView(cover: companion.coverFace, edge: 40, fallback: CoverRef(smart: companion))
                        Text(PersonPageCopy.shared.name(title: companion.title)).foregroundStyle(.primary)
                    }
                }
            }
            .navigationTitle(PersonPageCopy.shared.PICKER_TITLE)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(PersonPageCopy.shared.CANCEL) { dismiss() }
                }
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
            // ⚠ This phone's `groupingRunsHere`, as the tab's (`PhotosSurface.sectionFailure`).
            if let failure = PhotosSurface.shared.sectionFailure(section: section, state: collections.state) {
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
                        NavigationLink(value: CollectionsRoute.smart(id: person.id)) {
                            PersonCardView(person: person, groupingRunsHere: collections.groupingRunsHere)
                        }
                            .buttonStyle(.plain)
                            .personCardAccessibility(person, groupingRunsHere: collections.groupingRunsHere)
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
