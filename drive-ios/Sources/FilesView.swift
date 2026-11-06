import SwiftUI
import PhotosUI
import TenshuSdk

// Drive's home + folder screen (proposal §11.6). A SwiftUI list bound to the
// **real** `FilesScreenViewModel` from `:drive-shared-logic`: it observes the VM's
// paged `StateFlow<PagingData<FileUiModel>>` through `TenshuPagingPresenter`
// (surfaced by SKIE as an `AsyncSequence`), so this is "share the view models,
// write the UI native" (§9.3) end-to-end in an app binary.
//
// Folder rows push a child `FilesView`; the **+ Add** toolbar action opens the iOS
// Document / Photo picker and hands the pick to the real `AddFileActionsViewModel`
// (foreground upload — §9.4 / O3 Option A). The listing *contents* are canned
// (`DriveReadSurface`) until a proxy serves Drive ops on iOS (6d/6e).
struct FilesView: View {
    /// `FileModel.Root.path` — the realm root the home screen lists.
    static let rootPath = "/"

    let folderPath: String
    let title: String
    let isRoot: Bool

    @EnvironmentObject private var router: FilesRouter
    @StateObject private var model: FilesModel
    @StateObject private var search = SearchModel()
    @State private var showingDocPicker = false
    @State private var showingPhotoPicker = false
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showingSettings = false
    @State private var showingCreateFolder = false
    @State private var showingManageAccess = false
    // Per-row action surfaces, driven from the row context menu (see `rowMenu`). A single
    // `item`-bound sheet carries the target file's path/name; `deleteTarget` drives a confirm alert.
    @State private var rowSheet: RowSheet?
    @State private var deleteTarget: FileUiModel?

    init(folderPath: String, title: String, isRoot: Bool) {
        self.folderPath = folderPath
        self.title = title
        self.isRoot = isRoot
        _model = StateObject(wrappedValue: FilesModel(folderPath: folderPath))
    }

    var body: some View {
        // The listing renders as either a row list or a thumbnail grid, driven by the
        // persisted `FileViewMode` (list/grid toggle in the toolbar's view-options menu).
        // Both share the ambiguous-empty overlay below.
        Group {
            if search.isSearching {
                searchResults
            } else if model.viewMode == .grid {
                gridView
            } else {
                listView
            }
        }
        .overlay {
            // An empty snapshot is ambiguous: spinner while the initial refresh is in flight,
            // real empty state once it settled. Over the canned fakes the listing was never
            // empty, so gating on `files.isEmpty` alone hid an eternal spinner on a genuinely
            // empty folder — the first real (route-1) listing surfaced it.
            if search.isSearching {
                // Search owns the surface while active — the listing's own empty state would
                // otherwise claim "No files yet" over a perfectly full drive.
                if search.state.files.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "magnifyingglass")
                            .font(.largeTitle)
                            .foregroundStyle(.secondary)
                        Text("No files matching \u{201C}\(search.state.query)\u{201D}")
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding()
                }
            } else if model.files.isEmpty {
                if model.settled {
                    VStack(spacing: 8) {
                        Image(systemName: "folder")
                            .font(.largeTitle)
                            .foregroundStyle(.secondary)
                        Text("No files yet")
                            .foregroundStyle(.secondary)
                    }
                } else {
                    ProgressView("Loading…")
                }
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(isRoot ? .large : .inline)
        // Default placement on purpose (§24.2): the navigation-bar drawer under the title (the
        // Files.app placement). On-device iOS 26 confirms this holds there too — inside the
        // TabView shell the tab bar owns the bottom edge, so `.automatic` stays in the drawer
        // (the iOS 26 bottom Liquid Glass field applies without a tab bar, or via
        // Tab(role: .search), which is not Drive's shape — see §24.2 as-observed note).
        // Search is drive-wide, not folder-scoped, so it is offered at every level.
        .searchable(text: searchBinding, prompt: "Search files")
        .toolbar { toolbarContent }
        .task { await search.observe() }
        .task { await model.observe() }
        .task { await model.observeUploads() }
        .task { await model.observeSettled() }
        .task { await model.observeSort() }
        .task { await model.observeViewMode() }
        .photosPicker(isPresented: $showingPhotoPicker, selection: $photoItems, maxSelectionCount: 1, matching: .images)
        .onChange(of: photoItems) { items in
            guard !items.isEmpty else { return }
            Task { await model.upload(photoItems: items); photoItems = [] }
        }
        .fileImporter(isPresented: $showingDocPicker, allowedContentTypes: [.item]) { result in
            model.upload(documentResult: result)
        }
        .sheet(isPresented: $showingSettings) {
            NavigationStack { SettingsView() }
        }
        .sheet(isPresented: $showingCreateFolder) {
            CreateFolderSheet(folderPath: folderPath)
        }
        .sheet(isPresented: $showingManageAccess) {
            ManageAccessView(folderPath: folderPath, folderName: title)
        }
        .sheet(item: $rowSheet) { sheet in
            switch sheet {
            case let .shareLink(path, _):
                CreateShareLinkSheet(filePath: path)
            case let .manageAccess(path, name):
                ManageAccessView(folderPath: path, folderName: name)
            case let .rename(path, _):
                RenameSheet(filePath: path)
            case let .move(path, _):
                MovePickerView(sourcePath: path, onMoved: { Task { await model.refresh() } })
            }
        }
        .alert(
            "Delete \u{201C}\(deleteTarget?.name ?? "")\u{201D}?",
            isPresented: Binding(get: { deleteTarget != nil }, set: { if !$0 { deleteTarget = nil } })
        ) {
            Button("Delete", role: .destructive) {
                if let target = deleteTarget { model.delete(path: target.path, isFolder: target.isFolder) }
                deleteTarget = nil
            }
            Button("Cancel", role: .cancel) { deleteTarget = nil }
        } message: {
            Text(deleteTarget?.isFolder == true
                ? "This folder and everything inside it will be deleted."
                : "This file will be deleted.")
        }
        // An upload that hits an existing name, and one that fails outright. Both were silent on
        // iOS — nothing observed the add-file view model, so a picked file just never appeared.
        .alert(
            "File already exists",
            isPresented: Binding(
                get: { model.uploads.uploadConflict != nil },
                set: { if !$0 { model.cancelConflict() } }
            )
        ) {
            Button("Keep both") { model.keepBoth() }
            Button("Cancel", role: .cancel) { model.cancelConflict() }
        } message: {
            Text("A file named \u{201C}\(model.uploads.uploadConflict?.fileName ?? "")\u{201D} already exists. Keep both or cancel.")
        }
        .alert(
            "Upload failed",
            isPresented: Binding(
                get: { model.uploads.uploadError != nil },
                set: { if !$0 { model.dismissUploadError() } }
            )
        ) {
            Button("Ok", role: .cancel) { model.dismissUploadError() }
        } message: {
            Text(uploadErrorMessage)
        }
    }

    /// The view model reports a stable token; the UI owns the copy (ADR 0016). The Compose twin
    /// resolves the same tokens against `upload_error_*`.
    private var uploadErrorMessage: String {
        switch model.uploads.uploadError {
        case AddFileActionsViewModel.companion.ERROR_TOO_MANY_COPIES:
            return "There are already too many copies of this file here. Rename it or choose a different folder."
        default:
            return "Couldn't upload this file. Please try again."
        }
    }

    // MARK: List / grid layouts

    private var listView: some View {
        List {
            // Enumerated on purpose: the index is what pages forward. `model.files` is the
            // presenter's *snapshot*, a passive mirror — Paging appends only when an access
            // is recorded through `get(index:)`, so a plain `ForEach(model.files)` renders
            // the first window and never grows (see `FilesModel.onRowAppear`).
            ForEach(Array(model.files.enumerated()), id: \.element.path) { index, file in
                HStack(spacing: 8) {
                    rowButton(for: file) { FileRow(file: file) }
                    overflowButton(file)
                }
                .contextMenu { rowMenu(file) }
                .onAppear { model.onRowAppear(index) }
            }
        }
        .listStyle(.plain)
        .refreshable { await model.refresh() }
    }

    private var gridView: some View {
        ScrollView {
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 96), spacing: 16)],
                spacing: 16
            ) {
                // Enumerated for the same reason as `listView` — the index drives paging.
                ForEach(Array(model.files.enumerated()), id: \.element.path) { index, file in
                    rowButton(for: file) { FileTile(file: file) }
                        // The tile has no trailing edge to sit on, so the overflow rides the
                        // thumbnail's top-right corner with a material backing for legibility
                        // over arbitrary image content.
                        .overlay(alignment: .topTrailing) { overflowButton(file, overThumbnail: true) }
                        .contextMenu { rowMenu(file) }
                        .onAppear { model.onRowAppear(index) }
                }
            }
            .padding()
        }
        .refreshable { await model.refresh() }
    }

    // The **visible** per-item action affordance, rendering the exact same `rowMenu` as the
    // long-press context menu.
    //
    // §22 shipped the item actions as a context menu only, on the reading that the ⓘ →
    // `FileDetailView` chain was a "harmless secondary". It isn't: that chain is three levels
    // deep and exists for *files* only, so a user who never long-presses could not rename, move
    // or delete a **folder** at all. Apple's own guidance treats context menus as accelerators
    // rather than a sole path, and the toolbar's visible `ellipsis.circle` made it worse by
    // holding view options only — a user scanning for an overflow menu found one with no item
    // actions in it. Hence: menu visible, context menu retained as the accelerator, single
    // `rowMenu` source so the two can never drift.
    @ViewBuilder
    private func overflowButton(_ file: FileUiModel, overThumbnail: Bool = false) -> some View {
        Menu {
            rowMenu(file)
        } label: {
            // Bare `ellipsis`, not `ellipsis.circle`: the circled variant reads as a chunky
            // primary control next to a filename (OneDrive/Drive both use the bare glyph in
            // rows). The circle stays only on the grid tile, where the ring doubles as the
            // legibility backing over arbitrary thumbnail content.
            Image(systemName: overThumbnail ? "ellipsis.circle.fill" : "ellipsis")
                .foregroundStyle(overThumbnail ? Color.primary : Color.secondary)
                .padding(overThumbnail ? 4 : 0)
                .background {
                    if overThumbnail { Circle().fill(.thinMaterial) }
                }
                // A 44pt target (the HIG minimum) without widening the drawn glyph;
                // `contentShape` makes the padding itself tappable.
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        // Without an explicit borderless style the enclosing List row treats the menu as part
        // of the row's tap target and the NavigationLink swallows it.
        .buttonStyle(.borderless)
        .accessibilityIdentifier("row-overflow")
        .accessibilityLabel("Actions for \(file.name)")
    }

    // MARK: Search (§24)

    // The query text field. Reads/writes the shared `FilesSearchBarViewModel`, which owns the
    // 250 ms debounce — so this fires per keystroke and the VM collapses the burst into one
    // drive-wide scan. No debouncing Swift-side.
    private var searchBinding: Binding<String> {
        Binding(get: { search.state.query }, set: { search.onQueryChanged($0) })
    }

    // Results reuse the listing's row treatment wholesale — same `FileRow`, same `rowButton`
    // routing through `FilesRouter`, same `overflowButton` — so a hit behaves exactly like the
    // file does in its folder (§23). Always a list, never the grid: results span folders, where a
    // thumbnail wall without parent context reads as a pile of unrelated tiles.
    private var searchResults: some View {
        List {
            ForEach(search.state.files, id: \.path) { file in
                HStack(spacing: 8) {
                    rowButton(for: file) { FileRow(file: file) }
                    overflowButton(file)
                }
                .contextMenu { rowMenu(file) }
            }
        }
        .listStyle(.plain)
    }

    // Folder rows push a child `FilesView`; file rows push the preview route — the same
    // routing for both layouts, so the list and grid differ only in the item view.
    //
    // A `Button` over the shared `FilesRouter`, NOT a `NavigationLink`: in a `List` the link
    // draws a disclosure chevron that cannot be suppressed, and a chevron sitting beside the
    // row's overflow menu is both clutter and a second competing trailing tap target (see
    // `FilesRouter`). `.contentShape` keeps the whole row tappable including its empty space.
    @ViewBuilder
    private func rowButton<Content: View>(
        for file: FileUiModel,
        @ViewBuilder content: () -> Content
    ) -> some View {
        Button {
            if file.isFolder {
                router.push(FolderRoute(path: file.path, name: file.name))
            } else {
                router.push(FileRoute(path: file.path, name: file.name))
            }
        } label: {
            // Two separate duties, both load-bearing:
            //
            // `.frame(maxWidth:)` expands the row's LAYOUT bounds, so the overflow accessory
            // sits on the trailing edge instead of floating in right after the filename.
            //
            // `.contentShape` expands the row's HIT region to match. Without it, tapping only
            // works on the drawn glyph + text and the gap between them and the accessory is
            // dead — SwiftUI hit-tests the rendered content, not the frame. It MUST be inside
            // the label: outside the Button it shapes the button's outer boundary, not the
            // label's tap area, which is the bug that shipped the dead middle.
            //
            // A `NavigationLink` row would get all of this for free (as does a UIKit
            // `UICollectionViewListCell`, which is why Files.app has no such gap) — owning
            // hit-testing is the price of dropping the link to drop the chevron.
            content()
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // The per-item action set, mirroring Android's `FileActionsBottomSheet`. Rendered by BOTH
    // the visible `overflowButton` and the long-press context menu — one source, so the two
    // can never drift. Sharing a *folder* (Manage Access) is reachable here on any folder row,
    // including at the root, which is what makes root-level folders shareable without first
    // navigating into them.
    @ViewBuilder
    private func rowMenu(_ file: FileUiModel) -> some View {
        if file.isFolder {
            Button {
                rowSheet = .manageAccess(path: file.path, name: file.name)
            } label: {
                Label("Manage Access", systemImage: "person.2")
            }
        } else {
            // Mode-A link: file-only (a folder has no single blob to seal under K_share).
            Button {
                rowSheet = .shareLink(path: file.path, name: file.name)
            } label: {
                Label("Share Link", systemImage: "link")
            }
        }

        Button {
            rowSheet = .rename(path: file.path, name: file.name)
        } label: {
            Label("Rename", systemImage: "pencil")
        }
        Button {
            rowSheet = .move(path: file.path, name: file.name)
        } label: {
            Label("Move", systemImage: "folder")
        }

        Divider()

        Button(role: .destructive) {
            deleteTarget = file
        } label: {
            Label("Delete", systemImage: "trash")
        }
    }

    // MARK: Sort + view-mode bindings (persisted per device via the shared VM)

    // The Sort By selection. ⚠ It was `FileSortKey?`, where `nil` meant the shared VM's natural
    // (store) order — a second listing mode on its own repository path. SP-6 of
    // `drive-sorted-folder-paging.md` retired that mode (D5): there is always a sort, a device
    // that has never chosen gets Name ascending (D6), and the shared VM's `sort` is non-optional.
    // The menu never offered "None" as a row anyway (neither Files.app nor Drive/Dropbox do), so
    // the visible change is only that a key is always checked and Order is always shown.
    //
    // Picking a key keeps the current direction.
    private var sortKeyBinding: Binding<FileSortKey> {
        Binding(
            get: { model.sort.key },
            set: { newKey in
                model.setSort(FileSort(key: newKey, descending: model.sort.descending))
            }
        )
    }

    // The sort direction, independent of the chosen key.
    private var descendingBinding: Binding<Bool> {
        Binding(
            get: { model.sort.descending },
            set: { descending in
                model.setSort(FileSort(key: model.sort.key, descending: descending))
            }
        )
    }

    private var viewModeBinding: Binding<FileViewMode> {
        Binding(get: { model.viewMode }, set: { model.setViewMode($0) })
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        // View options — the iOS-idiomatic single menu (à la Files.app): a View section
        // (Icons/List) + a Sort By submenu + Order, using inline pickers so the active
        // selection renders a native checkmark. Both choices persist per device.
        ToolbarItem(placement: .primaryAction) {
            Menu {
                Picker("View", selection: viewModeBinding) {
                    Label("Icons", systemImage: "square.grid.2x2").tag(FileViewMode.grid)
                    Label("List", systemImage: "list.bullet").tag(FileViewMode.list)
                }
                .pickerStyle(.inline)

                // No "None" row — there is no unsorted state to offer (SP-6, see
                // `sortKeyBinding`). One key is always checked.
                Picker("Sort By", selection: sortKeyBinding) {
                    Text("Name").tag(FileSortKey.name)
                    Text("Date Modified").tag(FileSortKey.modified)
                    Text("Date Created").tag(FileSortKey.created)
                    Text("Size").tag(FileSortKey.size)
                }
                .pickerStyle(.inline)

                // Always shown: a key is always active, so a direction always applies.
                Picker("Order", selection: descendingBinding) {
                    Label("Ascending", systemImage: "arrow.up").tag(false)
                    Label("Descending", systemImage: "arrow.down").tag(true)
                }
                .pickerStyle(.inline)
            } label: {
                // Deliberately NOT `ellipsis.circle`: that glyph now means "actions for this
                // item" on every row, and two competing ellipses would restore exactly the
                // ambiguity this slice removes. The sort/filter glyph says "view options".
                Image(systemName: "line.3.horizontal.decrease.circle")
            }
            .accessibilityIdentifier("view-options")
            .accessibilityLabel("View options")
        }
        ToolbarItem(placement: .primaryAction) {
            Menu {
                Button { showingCreateFolder = true } label: {
                    Label("New Folder", systemImage: "folder.badge.plus")
                }
                // iOS Document picker (UIDocumentPickerViewController) + Photo
                // picker (PHPickerViewController), via their SwiftUI wrappers.
                Button { showingDocPicker = true } label: {
                    Label("Choose File", systemImage: "doc")
                }
                Button { showingPhotoPicker = true } label: {
                    Label("Choose Photo", systemImage: "photo")
                }
            } label: {
                Image(systemName: "plus")
            }
            .accessibilityIdentifier("add-file")
        }
        if isRoot {
            ToolbarItem(placement: .topBarLeading) {
                Button { showingSettings = true } label: {
                    Image(systemName: "gearshape")
                }
                .accessibilityIdentifier("settings")
            }
        } else {
            // Inside a folder: manage who this folder is shared with.
            ToolbarItem(placement: .topBarTrailing) {
                Button { showingManageAccess = true } label: {
                    Image(systemName: "person.2")
                }
                .accessibilityIdentifier("manage-access")
            }
        }
    }
}

// Identifies which per-row action sheet is presented, carrying the target file's path (+ name
// where the sheet shows it). `Identifiable` (keyed on action + path) drives `.sheet(item:)`.
private enum RowSheet: Identifiable {
    case shareLink(path: String, name: String)
    case manageAccess(path: String, name: String)
    case rename(path: String, name: String)
    case move(path: String, name: String)

    var id: String {
        switch self {
        case let .shareLink(path, _): return "share:\(path)"
        case let .manageAccess(path, _): return "access:\(path)"
        case let .rename(path, _): return "rename:\(path)"
        case let .move(path, _): return "move:\(path)"
        }
    }
}

private struct FileRow: View {
    let file: FileUiModel

    // P4e gap #2: the rendered list-row thumbnail, once loaded. Nil = show the glyph
    // (loading, non-thumbnailable, or no thumbnail available). Viewport-driven: the
    // `.task(id:)` below runs when the row appears and SwiftUI cancels it when the row
    // scrolls off, so only visible rows fetch — the iOS twin of the Android/desktop
    // per-row `previewLoader` (drive-shared TenshuFilesRepository §"per-row previewLoader").
    @State private var thumbnail: UIImage?

    var body: some View {
        HStack(spacing: 12) {
            leadingIcon
            VStack(alignment: .leading, spacing: 2) {
                Text(file.name).font(.body)
                Text(file.subLine).font(.caption).foregroundStyle(.secondary)
            }
        }
        .task(id: file.path) { thumbnail = await loadFileThumbnail(file, maxPx: 100) }
    }

    @ViewBuilder private var leadingIcon: some View {
        if let thumbnail {
            Image(uiImage: thumbnail)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: 28, height: 28)
                .clipShape(RoundedRectangle(cornerRadius: 4))
        } else {
            Image(systemName: file.previewIcon.sfSymbol)
                .foregroundStyle(file.isFolder ? Color.accentColor : .secondary)
                .frame(width: 28)
        }
    }
}

// The grid (Icons) counterpart of `FileRow`: a thumbnail-forward tile — a large preview (or
// the type glyph as fallback) with the name beneath. Shares the exact viewport-driven,
// cancel-on-scroll thumbnail loader with `FileRow`, at a larger request size for the tile.
private struct FileTile: View {
    let file: FileUiModel

    @State private var thumbnail: UIImage?

    var body: some View {
        VStack(spacing: 6) {
            // ⚠ The tile's size comes from the grid column, never from the image: the thumbnail is
            // an *overlay* on a fixed-size backing, so it cannot widen the tile. As a ZStack sibling,
            // a `.fill` landscape image reported its aspect-scaled width, `.frame(maxWidth:)` with
            // no `minWidth` adopted it, and the tiles overlapped their neighbours.
            Color(uiColor: .secondarySystemBackground)
                .frame(maxWidth: .infinity)
                .frame(height: 96)
                .overlay {
                    if let thumbnail {
                        Image(uiImage: thumbnail)
                            .resizable()
                            .scaledToFill()
                    } else {
                        Image(systemName: file.previewIcon.sfSymbol)
                            .font(.largeTitle)
                            .foregroundStyle(file.isFolder ? Color.accentColor : .secondary)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 8))
                // `clipShape` clips the drawing only; this keeps the cropped-away overhang from
                // taking taps meant for the neighbouring tile.
                .contentShape(RoundedRectangle(cornerRadius: 8))

            Text(file.name)
                .font(.caption)
                .lineLimit(1)
                .truncationMode(.middle)
                .foregroundStyle(.primary)
        }
        .task(id: file.path) { thumbnail = await loadFileThumbnail(file, maxPx: 200) }
    }
}

// Shared per-row/-tile thumbnail fetch (P4e gap #2). Returns nil = show the glyph (loading,
// non-thumbnailable, folder, or no thumbnail available). Called from a `.task(id:)` so the fetch
// is viewport-driven and SwiftUI cancels it when the item scrolls off — the iOS twin of the
// Android/desktop per-row `previewLoader`.
private func loadFileThumbnail(_ file: FileUiModel, maxPx: Int32) async -> UIImage? {
    // Only previewable, non-folder items attempt a thumbnail; the resolver would return
    // null for the rest anyway, but gating here skips the wasted `getPreview` call.
    guard !file.isFolder, file.previewIcon.isThumbnailable else { return nil }
    // The resolver serves the eager-synced File.Thumbnail on a byte-less device, or computes
    // from local bytes on the uploader. SKIE bridges the Kotlin `NSData?` return to
    // `Foundation.Data?`; the leading `Foundation.` disambiguates it from the SDK's
    // `TenshuSdk.Data` (the Kotlin core `Data` type is also exported into this module).
    let data: Foundation.Data? = try? await DrivePreviewSurface.shared.loadRowThumbnail(
        path: file.path, maxPx: maxPx
    )
    guard let data, let image = UIImage(data: data) else { return nil }
    return image
}

// Bridges the shared `FilesSearchBarViewModel` (`:drive-shared-logic`, commonMain — the same VM
// Android and desktop use) to SwiftUI via `DriveSearchSurface`. The VM owns the debounced
// search-as-you-type pipeline over `FilesRepository.searchFiles`; this holds no search logic.
@MainActor
final class SearchModel: ObservableObject {
    @Published var state: FileSearchBarUiState

    private let vm: FilesSearchBarViewModel

    init() {
        let vm = DriveSearchSurface.shared.search()
        self.vm = vm
        self.state = vm.uiState.value
    }

    /// Whether search should own the screen. Driven by the query rather than
    /// `@Environment(\.isSearching)` — that is only readable from a *child* of the searchable
    /// view, and an empty query has nothing to show anyway (the VM emits no results for one).
    var isSearching: Bool { !state.query.trimmingCharacters(in: .whitespaces).isEmpty }

    func observe() async {
        for await s in vm.uiState { state = s }
    }

    func onQueryChanged(_ query: String) {
        vm.onQueryChanged(s: query)
        // Clearing the field is a dismissal: let the VM reset so the next open starts clean
        // (it drops both the query and the last results).
        if query.isEmpty { vm.onActiveChanged(active: false) }
    }
}

// Bridges the Kotlin VM + paging presenter to SwiftUI. The `Bound` (VM + add-file
// VM + presenter) is built once per folder in `DriveReadSurface`; the presenter's
// `snapshot` StateFlow (rendered by SKIE as an `AsyncSequence`) drives `files`.
@MainActor
final class FilesModel: ObservableObject {
    @Published var files: [FileUiModel] = []
    @Published var settled = false
    /// The add-file view model's state — upload conflicts and failures (see `observeUploads`).
    @Published var uploads: AddFileActionsUiState
    // The persisted listing display prefs, mirrored from the shared VM's StateFlows. ⚠ `sort` was
    // optional, `nil` meaning natural (store) order; SP-6 retired that mode, so it is always a
    // real selection and the seed matches the shared VM's (Name ascending, D6). `viewMode` drives
    // list-vs-grid rendering.
    @Published var sort: FileSort = FileSort(key: .name, descending: false)
    @Published var viewMode: FileViewMode = .list

    private let folderPath: String
    private let bound: DriveReadSurface.Bound
    // Retains the last one-shot actions VM so its `viewModelScope` delete coroutine outlives the
    // menu tap that spawned it (the VM is otherwise unreferenced after `onDeleteClicked`).
    private var lastActionsVm: FileActionsViewModel?

    init(folderPath: String) {
        self.folderPath = folderPath
        // Root uses the no-arg builder (main dispatchers); children pass the path.
        let bound = folderPath == FilesView.rootPath
            ? DriveReadSurface.shared.create()
            : DriveReadSurface.shared.create(folderPath: folderPath)
        self.bound = bound
        self.uploads = bound.addFileActions.uiState.value
    }

    func observe() async {
        for await items in bound.presenter.snapshot {
            files = items
        }
    }

    /// ⚠⚠ Paging's load-next trigger — call it from every row's `onAppear`.
    ///
    /// `presenter.snapshot` is a passive mirror of what Paging has already presented;
    /// `get(index:)` is what *records the access*, and an access near the end of the loaded
    /// range is the only thing that makes Paging append the next page. Compose gets this for
    /// free from `collectAsLazyPagingItems()` — driving the presenter by hand does not.
    ///
    /// Without it a folder listed at `pageSize = 20` shows exactly 20 rows forever: a folder of
    /// 786 photos rendered 20, while its "786 files" subtitle stayed correct (that count is a
    /// separate `Folder.FileCount` member-hash count, never the page window) — so it read as a
    /// sync or thumbnail failure rather than a paging one. The presenter side was always right:
    /// `TenshuPagingPresenterTest.accessing_last_item_pages_forward` has pinned it all along.
    func onRowAppear(_ index: Int) {
        _ = bound.presenter.get(index: Int32(index))
    }

    /// What an upload has to say. ⚠ Nothing observed this before, so a picked file that collided
    /// with an existing name — or failed for any other reason — simply never appeared, silently.
    /// The Compose screens have shown the conflict + its *Keep both* remedy all along.
    func observeUploads() async {
        for await state in bound.addFileActions.uiState { uploads = state }
    }

    func keepBoth() { bound.addFileActions.resolveConflictKeepBoth() }
    func cancelConflict() { bound.addFileActions.cancelConflict() }
    func dismissUploadError() { bound.addFileActions.dismissUploadError() }

    // Whether the initial refresh has landed (distinguishes "loading" from "empty").
    func observeSettled() async {
        for await value in bound.presenter.refreshSettled {
            settled = value.boolValue
        }
    }

    // The listing sort selection — a change re-runs `pagedFiles` (the VM `flatMapLatest`s on it),
    // which the presenter is already collecting, so the snapshot refreshes with no extra plumbing.
    func observeSort() async {
        for await value in bound.viewModel.sort {
            sort = value
        }
    }

    // The list/grid view mode — pure rendering, does not touch the listing chain.
    func observeViewMode() async {
        for await value in bound.viewModel.viewMode {
            viewMode = value
        }
    }

    // Persist the sort selection / view mode. Both are per-device, written through the
    // preference service by the shared VM.
    func setSort(_ sort: FileSort) {
        bound.viewModel.setSort(sort: sort)
    }

    func setViewMode(_ mode: FileViewMode) {
        bound.viewModel.setViewMode(mode: mode)
    }

    // Pull-to-refresh: re-collect the paged source — `collect` replaces the previous generation,
    // so a fresh Pager load reads the shared replica again (a co-resident app's writes reach no
    // listener in this process). A no-op over the fakes.
    func refresh() async {
        _ = bound.presenter.collect(flow: bound.viewModel.pagedFiles)
    }

    // Delete a file/folder from the row context menu. Uses the actions-only surface (no detail
    // streaming); the paged snapshot is reactive over the live runtime, and `refresh` nudges the
    // fakes. `lastActionsVm` keeps the VM alive until its delete coroutine settles.
    func delete(path: String, isFolder: Bool) {
        let vm = DriveDetailSurface.shared.actions(path: path, isFolder: isFolder)
        lastActionsVm = vm
        vm.onDeleteClicked()
        Task { await refresh() }
    }

    // MARK: Foreground upload (§9.4 / O3 Option A)

    func upload(documentResult: Result<URL, Error>) {
        guard case let .success(url) = documentResult else { return }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        // Copy WHILE the security scope is live (P3a): the VM's upload runs async, after this
        // function returned and the scope was released — reading the original there fails for
        // out-of-sandbox documents (invisible over the fakes, which never read bytes).
        guard let tmp = try? stageForUpload(url) else {
            bound.addFileActions.reportUploadFailed()
            return
        }
        bound.addFileActions.uploadFile(uriString: tmp.absoluteString, parentPath: folderPath)
    }

    func upload(photoItems: [PhotosPickerItem]) async {
        for item in photoItems {
            // `PickedPhoto` rather than `Data.self`: a file representation carries the asset's
            // ORIGINAL NAME, which raw bytes cannot.
            guard let picked = try? await item.loadTransferable(type: PickedPhoto.self) else {
                bound.addFileActions.reportUploadFailed()
                continue
            }
            bound.addFileActions.uploadFile(
                uriString: picked.url.absoluteString,
                parentPath: folderPath
            )
        }
    }
}

/// Copy [url] into a fresh per-upload temp directory and return the copy.
///
/// The subdirectory is what lets the **leaf name survive**: it is the name the file is uploaded
/// under (both `getFileName` implementations read the URL's last path component), and a unique
/// parent means two uploads of the same name cannot collide on disk before the repository ever
/// sees them. `copyItem` also preserves the modification date — the `File.CreatedAt` seed.
private func stageForUpload(_ url: URL) throws -> URL {
    let dir = FileManager.default.temporaryDirectory
        .appendingPathComponent(UUID().uuidString, isDirectory: true)
    let copy = dir.appendingPathComponent(url.lastPathComponent)
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    try FileManager.default.copyItem(at: url, to: copy)
    return copy
}

/// A picked photo as a **file** rather than raw bytes.
///
/// ⚠⚠ `loadTransferable(type: Data.self)` hands over bytes with no name, which is why every photo
/// used to upload as `photo.jpg`: the old code fell back to `item.itemIdentifier`, and that is nil
/// unless the picker is built with `photoLibrary: .shared()`. A `FileRepresentation` instead hands
/// over a URL whose last path component is the asset's **original filename** (`IMG_0042.HEIC`) —
/// which also stops the extension from lying, since the bytes were never JPEG to begin with.
///
/// ⚠ Deliberately NOT the `photoLibrary: .shared()` route to the same information: that makes
/// `itemIdentifier` a PHAsset local id (`"UUID/L0/001"`) whose **slashes** would build nested temp
/// directories that do not exist (a silent write failure), and would carry a `/` into a file name
/// that `UploadFile` now refuses outright.
///
/// The received file is deleted the moment the importing closure returns, so it is staged
/// immediately — through the same helper the document picker uses.
private struct PickedPhoto: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .image) { photo in
            SentTransferredFile(photo.url)
        } importing: { received in
            PickedPhoto(url: try stageForUpload(received.file))
        }
    }
}
