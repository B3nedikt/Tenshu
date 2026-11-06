import SwiftUI
import TenshuSdk

// The Collections tab (photos-ios.md §8.15) — Compose's `CollectionsScreen` on iOS.
//
// ⚠⚠ Swift decides nothing (PIO-D2): which sections show, which may show while empty, when *View all*
// is offered and every sentence are the shared `CollectionsLayout`; resolving a section by id or alias
// is `CollectionsUiState.smartAlbumFor`. This file lays them out.
//
// ⚠⚠ Every tile and card leads somewhere — a line with no receiving route is worse than none
// (`control-cross-app-open.md` §5g.2). The Duplicates tile has its screen (§8.18).

/// Bridges the shared `CollectionsViewModel` to SwiftUI.
@MainActor
final class CollectionsModel: ObservableObject {
    let viewModel: CollectionsViewModel = PhotosSurface.shared.collections()
    @Published private(set) var state: CollectionsUiState
    private var observation: Task<Void, Never>?

    /// ⚠⚠ The model observes for its WHOLE life, not inside the tab's `.task`: pushing a
    /// year, place or person cancels the root's tasks, and the pushed grids read this model — so a
    /// refresh after a trash in their viewer would update Kotlin's state while the grid behind kept
    /// showing the trashed photograph until the user went back to the root.
    init() {
        state = viewModel.state.value
        observation = Task { [weak self, viewModel] in
            for await value in viewModel.state {
                guard let self else { return }
                self.state = value
            }
        }
    }

    deinit { observation?.cancel() }

    /// ⚠ iOS does not group faces, so People's "why it is empty" line — a remedy this device
    /// cannot take — stays silent (`peopleStatusLine`'s `groupingRunsHere`).
    var layout: CollectionsLayout { CollectionsLayoutCompanion.shared.of(state: state, groupingRunsHere: false) }
}

/// Where the Collections tab navigates.
enum CollectionsRoute: Hashable {
    case album(id: String, title: String)
    case favourites
    /// A derived section — a place, a year, a person, Videos — addressed by id (or an alias).
    case smart(id: String)
    case section(CollectionSection)
    /// Utilities › Trash.
    case trash
    /// The Duplicates tile.
    case duplicates
}

struct CollectionsScreen: View {
    @StateObject private var model = CollectionsModel()
    @State private var naming = false
    @State private var newAlbumName = ""
    /// The album write was refused — said, never swallowed.
    @State private var createRefused = false
    /// ⚠ The view model's initialiser already refreshes; refreshing on the FIRST appearance as well
    /// would run the whole library walk twice. Only a later return asks — and it only ASKS
    /// (§8.22.3): the library is walked again only if something the tab draws may have changed.
    @State private var appeared = false

    var body: some View {
        content
            .navigationTitle("Collections")
            .navigationDestination(for: CollectionsRoute.self) { route in
                destination(route)
            }
            .onAppear {
                // ⚠ Shown first: a change that arrives while the tab is up is walked for at once.
                model.viewModel.setShown(surface: "tab", shown: true)
                if appeared { model.viewModel.refreshIfChanged() } else { appeared = true }
            }
            .onDisappear { model.viewModel.setShown(surface: "tab", shown: false) }
            .alert("New Album", isPresented: $naming) {
                TextField("Name", text: $newAlbumName)
                Button("Cancel", role: .cancel) { newAlbumName = "" }
                Button("Create") {
                    let name = newAlbumName.trimmingCharacters(in: .whitespaces)
                    if !name.isEmpty {
                        model.viewModel.createAlbum(name: name) { made in
                            if !made.boolValue { createRefused = true }
                        }
                    }
                    newAlbumName = ""
                }
            }
            .alert("Couldn’t Create the Album", isPresented: $createRefused) {
                Button("OK") {}
            } message: {
                Text("Tenshu couldn’t save the album right now. Try again in a moment.")
            }
    }

    @ViewBuilder
    private var content: some View {
        switch onEnum(of: model.layout) {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed(let failed):
            PhotosEmptyState(
                title: "Collections",
                systemImage: "exclamationmark.icloud",
                description: failed.message,
                actionTitle: "Try Again",
                action: { model.viewModel.retry() }
            )
        case .content(let layout):
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if let failure = layout.refreshFailure {
                        HStack {
                            Text(failure).font(.footnote).foregroundStyle(.red)
                            Spacer()
                            Button("Try Again") { model.viewModel.retry() }.font(.footnote)
                        }
                        .padding(.horizontal, 16)
                    }
                    tiles(layout.tiles)
                    if let people = layout.people { peopleRow(people) }
                    albumsRow(layout)
                    ForEach(layout.rows, id: \.section) { smartRow($0) }
                    // §36: the library walk is what Years waits for — a placeholder row while it runs.
                    if layout.libraryPending {
                        VStack(alignment: .leading, spacing: 8) {
                            header(CollectionSection.years.title, viewAll: nil)
                            placeholderRow(circles: false)
                        }
                    }
                    if !layout.utilities.isEmpty { utilities(layout.utilities) }
                    if let empty = layout.emptyText {
                        Text(empty)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                            .padding(24)
                    }
                }
                .padding(.vertical, 12)
            }
        }
    }

    // MARK: Tiles

    private func tiles(_ tiles: [CollectionsTile]) -> some View {
        // Every tile the shared layout lists — each has its destination now (`route(for:)`).
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
            ForEach(tiles, id: \.title) { tile in
                // ⚠ A failed tile retries on tap (§36) instead of opening what it kept.
                if tile.failed {
                    Button { model.viewModel.retry() } label: { tileLabel(tile) }
                        .buttonStyle(.plain)
                } else {
                    NavigationLink(value: route(for: tile)) { tileLabel(tile) }
                        .buttonStyle(.plain)
                }
            }
        }
        .padding(.horizontal, 16)
    }

    private func tileLabel(_ tile: CollectionsTile) -> some View {
        HStack(spacing: 10) {
            Image(systemName: symbol(for: tile))
                .font(.title3)
                .foregroundStyle(Color.accentColor)
            VStack(alignment: .leading, spacing: 2) {
                Text(tile.title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                Text(tile.subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(uiColor: .secondarySystemBackground)))
    }

    // MARK: Utilities

    /// Apple's *Utilities* (PIO-D3) — the shared layout's list; the Trash today.
    private func utilities(_ items: [CollectionsUtility]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(CollectionsUtility.companion.SECTION_TITLE)
                .font(.title3.weight(.semibold))
                .padding(.horizontal, 16)
            VStack(spacing: 0) {
                ForEach(items, id: \.title) { utility in
                    NavigationLink(value: route(for: utility)) {
                        HStack(spacing: 12) {
                            Image(systemName: "trash").foregroundStyle(Color.accentColor).frame(width: 24)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(utility.title).foregroundStyle(.primary)
                                Text(utility.subtitle).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
                        }
                        .padding(12)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(uiColor: .secondarySystemBackground)))
            .padding(.horizontal, 16)
        }
    }

    private func route(for utility: CollectionsUtility) -> CollectionsRoute {
        switch utility.kind {
        case .trash: return .trash
        }
    }

    private func route(for tile: CollectionsTile) -> CollectionsRoute {
        switch tile.kind {
        case .videos: return .smart(id: tile.smartAlbum?.id ?? "")
        case .duplicates: return .duplicates
        default: return .favourites
        }
    }

    private func symbol(for tile: CollectionsTile) -> String {
        switch tile.kind {
        case .videos: return "play.circle.fill"
        case .duplicates: return "square.on.square"
        default: return "heart.fill"
        }
    }

    // MARK: Rows

    private func header(_ title: String, viewAll: CollectionSection?) -> some View {
        HStack {
            Text(title).font(.title3.weight(.semibold))
            Spacer()
            // ⚠ Offered whenever the section has anything — a link that comes and goes makes the user
            // count cards to learn whether there are more (the shared layout decides).
            if let section = viewAll {
                NavigationLink("View all", value: CollectionsRoute.section(section)).font(.subheadline)
            }
        }
        .padding(.horizontal, 16)
    }

    private func peopleRow(_ people: CollectionsPeopleRow) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            header(CollectionSection.people.title, viewAll: people.viewAll ? .people : nil)
            if people.pending { placeholderRow(circles: true) }
            if let failure = people.failure { failureLine(failure) }
            if let line = people.statusLine {
                Text(line).font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 16)
            }
            if !people.people.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(people.people, id: \.id) { person in
                            NavigationLink(value: CollectionsRoute.smart(id: person.id)) {
                                PersonCardView(person: person)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
        }
    }

    private func albumsRow(_ layout: CollectionsLayoutContent) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            header(CollectionSection.albums.title, viewAll: layout.albumsViewAll ? .albums : nil)
            if let failure = layout.albumsFailure { failureLine(failure) }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 12) {
                    // ⚠ Always first, even with no albums: it is the one way to make one.
                    Button { naming = true } label: {
                        CollectionCardView(title: "New album", subtitle: nil, cover: nil, placeholderSymbol: "plus")
                    }
                    .buttonStyle(.plain)
                    ForEach(layout.albums, id: \.albumId) { album in
                        NavigationLink(value: CollectionsRoute.album(id: album.albumId, title: album.name)) {
                            CollectionCardView(
                                title: album.name,
                                subtitle: PhotoCountKt.photoCount(count: album.photoCount),
                                cover: album.coverPath.map { CoverRef(path: $0, width: album.coverPixelWidth?.int32Value ?? 0,
                                                                       height: album.coverPixelHeight?.int32Value ?? 0) },
                                placeholderSymbol: "rectangle.stack"
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }

    private func smartRow(_ row: CollectionsSmartRow) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            // ⚠ *View all* only with something to show: a failed, empty section is a header and its line.
            header(row.section.title, viewAll: row.albums.isEmpty ? nil : row.section)
            if !row.albums.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 12) {
                        ForEach(row.albums, id: \.id) { smart in
                            NavigationLink(value: CollectionsRoute.smart(id: smart.id)) {
                                CollectionCardView(title: smart.title, subtitle: smart.subtitle,
                                                   cover: CoverRef(smart: smart), placeholderSymbol: "photo")
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
            if let failure = row.failure { failureLine(failure) }
        }
    }

    /// A section's failed read (§36): said in the section, with its own Try Again — `retry()`, which
    /// abandons a refresh still in flight rather than being dropped behind it.
    private func failureLine(_ text: String) -> some View {
        HStack {
            Text(text).font(.footnote).foregroundStyle(.red)
            Spacer()
            Button("Try Again") { model.viewModel.retry() }.font(.footnote)
        }
        .padding(.horizontal, 16)
    }

    /// A section still waiting for its read (§36): grey shapes where its cards will be.
    private func placeholderRow(circles: Bool) -> some View {
        HStack(spacing: 12) {
            ForEach(0..<4, id: \.self) { _ in
                if circles {
                    Circle().fill(Color(uiColor: .secondarySystemBackground)).frame(width: 96, height: 96)
                } else {
                    RoundedRectangle(cornerRadius: 8).fill(Color(uiColor: .secondarySystemBackground))
                        .frame(width: 140, height: 140)
                }
            }
        }
        .padding(.horizontal, 16)
    }

    // MARK: Destinations

    @ViewBuilder
    private func destination(_ route: CollectionsRoute) -> some View {
        switch route {
        case .album(let id, let title):
            AlbumGridScreen(albumId: id, title: title) { model.viewModel.changed() }
        case .favourites:
            AlbumGridScreen(albumId: AlbumDetailViewModel.companion.FAVOURITES, title: "Favourites") {}
        case .smart(let id):
            SmartGridScreen(id: id, collections: model)
        case .section(let section):
            SectionScreen(section: section, collections: model)
        case .trash:
            // A restore or delete changes the tab's counts and years — re-read when one lands.
            TrashScreen { model.viewModel.changed() }
        case .duplicates:
            // A keep-only trashes or deletes photographs — the tab's counts and years re-read.
            DuplicatesScreen { model.viewModel.changed() }
        }
    }
}

// MARK: - Cards

/// A cover to draw: a photograph's path and its stored pixel size (for the rung).
struct CoverRef: Equatable {
    let path: String
    let width: Int32
    let height: Int32

    init(path: String, width: Int32, height: Int32) {
        self.path = path
        self.width = width
        self.height = height
    }

    /// A derived section's cover — its first photograph (or a person's chosen face photograph).
    init?(smart: SmartAlbum) {
        guard let path = smart.coverPath else { return nil }
        // ⚠ The card's own cover size (`SmartAlbum.coverPixelWidth`): a card drawn from the on-device
        // snapshot (§36 step 2) has no photographs to look it up in.
        self.init(path: path, width: smart.coverPixelWidth?.int32Value ?? 0, height: smart.coverPixelHeight?.int32Value ?? 0)
    }
}

/// A square cover, fetched at the size drawn through the shared rung choice (`tilePreview`).
struct CoverImageView: View {
    let cover: CoverRef?
    let edge: CGFloat
    let placeholderSymbol: String
    @State private var image: UIImage?
    @Environment(\.displayScale) private var scale

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10).fill(Color(uiColor: .secondarySystemFill))
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else if cover == nil {
                Image(systemName: placeholderSymbol).font(.title2).foregroundStyle(.secondary)
            }
        }
        .frame(width: edge, height: edge)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .task(id: cover?.path) {
            image = nil
            guard let cover else { return }
            let px = Int32(edge * scale)
            guard let data = try? await PhotosSurface.shared.tilePreview(
                path: cover.path, tileEdgePx: px, pixelWidth: cover.width, pixelHeight: cover.height
            ) else { return }
            // ⚠ Decoded OFF the main actor, as the viewer's hero is: a populated tab resumes many of
            // these at once, and a synchronous ImageIO decode each on the main actor stalls scrolling
            // and navigation.
            let bytes = data as Foundation.Data
            let maxPixels = Int(px) * 2
            let decoded = await Task.detached(priority: .userInitiated) {
                TileImageLoader.downsample(bytes, maxPixels: maxPixels)
            }.value
            if !Task.isCancelled { image = decoded }
        }
    }
}

struct CollectionCardView: View {
    let title: String
    let subtitle: String?
    let cover: CoverRef?
    let placeholderSymbol: String
    var edge: CGFloat = 140

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            CoverImageView(cover: cover, edge: edge, placeholderSymbol: placeholderSymbol)
            Text(title).font(.subheadline.weight(.medium)).foregroundStyle(.primary).lineLimit(1)
            if let subtitle {
                Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .frame(width: edge, alignment: .leading)
    }
}

/// ⚠ A round cover of the person's photograph. The face crop Compose draws (`FaceCover.region`)
/// waits on face grouping on iOS — here the photograph is centred.
struct PersonCardView: View {
    let person: SmartAlbum
    var edge: CGFloat = 84

    var body: some View {
        VStack(spacing: 4) {
            CoverImageView(cover: CoverRef(smart: person), edge: edge, placeholderSymbol: "person")
                .clipShape(Circle())
            Text(person.title.isEmpty ? "Add a name" : person.title)
                .font(.caption)
                .foregroundStyle(person.title.isEmpty ? .secondary : .primary)
                .lineLimit(1)
        }
        .frame(width: edge)
    }
}
