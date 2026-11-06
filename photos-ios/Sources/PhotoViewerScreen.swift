import SwiftUI
import TenshuSdk
import UIKit

// The viewer (`photos-app.md` S3) — a pager over the list the grid showed (§21), with the chrome
// toggle, favourite, add to album, trash, zoom, drag-to-dismiss and the About sheet.
//
// ⚠⚠ Each page owns its hero bytes (`HeroImageView`); the view model never holds them
// (`photos-app.md` §21.3): a pager has a window, a view model does not, and bytes in the model make
// memory a function of how far the user swiped.

@MainActor
final class ViewerModel: ObservableObject {
    /// What the pager pages over — the live feed, or a collection's photographs (`ViewerSource`).
    let source: ViewerSource
    @Published var positions: [Int32] = []
    @Published var current: Int32
    private(set) var viewModel: PhotoViewerViewModel? {
        didSet { observeState() }
    }
    /// What the shared view model says about the photograph on screen — the star, the chrome, the
    /// place. `nil` until the view model exists (a tapped photograph still being looked for).
    @Published private(set) var state: PhotoViewerUiState?
    private var stateTask: Task<Void, Never>?

    /// A write the viewer started is still running — the star and the menu wait
    /// (`PhotoViewerUiState.writing`).
    var writing: Bool { state?.writing ?? false }

    private func observeState() {
        stateTask?.cancel()
        guard let viewModel else { state = nil; return }
        stateTask = Task { [weak self] in
            for await value in viewModel.state {
                self?.state = value
            }
        }
    }

    deinit { stateTask?.cancel() }

    /// The tapped photograph's path while it is not yet found in the current list.
    /// ⚠⚠ Kept, not discarded: the binding can be ahead of the tile that was
    /// tapped, and a position alone names whatever sits there NOW — another photograph, a header or
    /// an unloaded slot. It is resolved against every layout until found or proven gone.
    private var pendingPath: String? {
        didSet {
            resolving = pendingPath != nil
            // ⚠ A lookup that ended — found, or a new one begun — starts without the last one's
            // failure: a stale error would otherwise show at the next relocation.
            if pendingPath != oldValue { lookupFailed = false }
        }
    }
    /// Whether the tapped photograph is still being looked for. ⚠ The pager is NOT shown meanwhile:
    /// the fallback position can hold another photograph.
    @Published private(set) var resolving = false
    /// The tapped photograph is gone — absent from a COMPLETE layout (deleted between tap and open).
    @Published private(set) var missing = false
    /// Looking for the photograph failed — the feed could not page (Control unreachable, a read
    /// error). ⚠ Not a spinner for ever: layouts stop coming when paging fails, so
    /// the lookup watches the feed's status too, and offers a retry.
    @Published private(set) var lookupFailed = false

    func retryLookup() {
        // ⚠ The failure is a StateFlow: a fresh observer replays its stale `true` at once, before
        // the retry has begun. Ignore failures until a non-failed value shows the
        // new attempt is under way.
        awaitingRetry = true
        lookupFailed = false
        // ⚠ Paging's own retry — a re-collection would only replay the cached, failed generation.
        source.retryPaging()
    }

    private var awaitingRetry = false
    private let fallback: Int32
    /// The path of the photograph ON SCREEN — kept for the viewer's whole life, updated on every
    /// swipe, and re-resolved on every layout: a live refresh inserting or removing
    /// photographs ahead shifts `current`'s index, and without the path the pager silently shows
    /// another photograph.
    private var shownPath: String? {
        didSet {
            // ⚠⚠ The shared view model follows the IDENTITY on screen, not the index:
            // when a deletion slides the next photograph into the same position, `current` does not
            // change, `onChange(of: current)` never fires, and the star, Add to Album and Trash would
            // act on the deleted photograph under its neighbour's picture.
            guard shownPath != oldValue, let path = shownPath,
                  let position = source.positionOf(path: path),
                  let photo = source.photoAt(position) else { return }
            viewModel?.onPhotoShown(photo: photo)
        }
    }
    /// The paths of the photographs either side of [shownPath], read from the SAME snapshot. ⚠ A
    /// deletion's refresh can also shift everything ahead, so the old index cannot find "the next
    /// one" — its neighbours' identities can.
    private var neighbourPaths: (next: String?, previous: String?) = (nil, nil)

    private func rememberNeighbours(of position: Int32) {
        let loaded = source.loadedPositions()
        guard let at = loaded.firstIndex(of: position) else { neighbourPaths = (nil, nil); return }
        let next = at + 1 < loaded.count ? source.photoAt(loaded[at + 1])?.path : nil
        let previous = at > 0 ? source.photoAt(loaded[at - 1])?.path : nil
        neighbourPaths = (next, previous)
    }

    init(source: ViewerSource, path: String, fallback: Int32) {
        self.source = source
        self.fallback = fallback
        shownPath = path
        if let found = source.positionOf(path: path) {
            current = found
        } else {
            current = fallback
            pendingPath = path
            resolving = true
        }
        refreshPositions()
        if pendingPath == nil, let photo = source.photoAt(current) {
            viewModel = PhotosSurface.shared.viewerViewModel(photo: photo)
        }
        observeState() // `didSet` does not run for assignments inside `init`
    }

    /// Re-read the pages as the feed grows under the pager (the last page asks for more), and keep
    /// looking for a tapped photograph not found yet.
    func observe() async {
        resolvePending()
        async let layouts: Void = observeLayouts()
        async let statuses: Void = observeStatus()
        _ = await (layouts, statuses)
    }

    private func observeLayouts() async {
        for await _ in source.changes() {
            refreshPositions()
            resolvePending()
            followShown()
        }
    }

    private func observeStatus() async {
        // ⚠ `pagingFailed`, not `status`: the lookup pages toward the EDGES, and `status` reports only
        // the refresh — an append or prepend that failed would leave the spinner for ever.
        for await failed in source.pagingFailures() {
            guard pendingPath != nil else { continue }
            if awaitingRetry {
                if !failed { awaitingRetry = false }
                continue
            }
            if failed { lookupFailed = true }
        }
    }

    /// Keep the photograph on screen as the list shifts under the pager.
    private func followShown() {
        guard pendingPath == nil, let path = shownPath else { return }
        if let found = source.positionOf(path: path) {
            if found != current { current = found }
            rememberNeighbours(of: current)
        } else if source.isComplete {
            // Gone (deleted while open).
            moveToNearest()
        } else {
            // ⚠ Pages away: the old index now shows ANOTHER photograph, so the pager
            // hides behind the loading state while the path is looked for — the same machinery a
            // stale tap uses, which pages toward both edges.
            relocating = true
            pendingPath = path
            resolvePending()
        }
    }

    /// The displayed photograph was ON SCREEN when it went missing — found again, or deleted.
    private var relocating = false

    /// ⚠ Not "what is at the old index": after a deletion that can be a header or
    /// past the end, and the pager goes blank. The nearest photograph — the next one, else the one
    /// before — or the missing state if none is left.
    private func moveToNearest() {
        // The neighbours it had, by identity — the next one, else the one before.
        for path in [neighbourPaths.next, neighbourPaths.previous].compactMap({ $0 }) {
            if let found = source.positionOf(path: path) {
                current = found
                shownPath = path
                rememberNeighbours(of: current)
                return
            }
        }
        // Neither survived: the nearest loaded photograph, or the missing state if none is left.
        let loaded = source.loadedPositions()
        if let next = loaded.first(where: { $0 >= current }) ?? loaded.last {
            current = next
            shownPath = source.photoAt(next)?.path
            rememberNeighbours(of: next)
        } else {
            shownPath = nil
            missing = true
        }
    }

    private func resolvePending() {
        guard let path = pendingPath else { return }
        if let found = source.positionOf(path: path) {
            pendingPath = nil
            relocating = false
            current = found
            if viewModel == nil, let photo = source.photoAt(current) {
                viewModel = PhotosSurface.shared.viewerViewModel(photo: photo)
            }
        } else if source.isComplete {
            // ⚠ Proven gone only by a COMPLETE layout — else it may be pages away.
            pendingPath = nil
            if relocating {
                // It was ON SCREEN and got deleted: give way to the nearest photograph.
                relocating = false
                moveToNearest()
            } else {
                // The TAPPED photograph is gone: say so rather than open another one.
                missing = true
            }
        } else {
            // ⚠⚠ The grid is gone, so nothing else asks for pages. Touching the
            // stale fallback alone stalls once its region is loaded — the photograph may have been
            // pushed pages further on — so page TOWARD BOTH EDGES of the loaded window: each layout
            // grows it, until the path turns up or the layout is complete and it is proven gone.
            source.touch(fallback)
            let loaded = source.loadedPositions()
            if let first = loaded.first { source.touch(first) }
            if let last = loaded.last { source.touch(last) }
        }
    }

    private func refreshPositions() {
        positions = source.loadedPositions()
    }

    func shown(_ position: Int32) {
        // ⚠ The same load trigger as the grid: reaching the loaded window's end pages the feed on.
        source.touch(position)
        if let photo = source.photoAt(position) {
            if pendingPath == nil { // a swipe moves the identity — and its neighbours — with it
                shownPath = photo.path
                rememberNeighbours(of: position)
            }
            viewModel?.onPhotoShown(photo: photo)
        }
    }

    func title(for position: Int32) -> String {
        guard let photo = source.photoAt(position) else { return "" }
        return PhotoDates.shared.shortDate(epochMs: photo.capturedAtMs)
    }

    func subtitle(for position: Int32) -> String {
        guard let photo = source.photoAt(position) else { return "" }
        let time = PhotoDates.shared.timeOfDay(epochMs: photo.capturedAtMs)
        // ⚠ The place only when a NAMED place claims this photograph — most have none, and the
        // line is omitted rather than invented. Read off the state only while it describes this one.
        guard let place = state?.place, state?.path == photo.path else { return time }
        return "\(time) · \(place.displayName)"
    }
}

struct PhotoViewerScreen: View {
    @StateObject private var model: ViewerModel
    @Environment(\.dismiss) private var dismiss
    @State private var pickingAlbum = false
    /// The photograph whose menu opened the album sheet. ⚠⚠ Captured when the sheet opens, not read
    /// when the album is picked: a live refresh can move the viewer to a neighbour while the sheet is
    /// up, and the pick would silently file that one instead.
    @State private var albumFor: PhotosPhoto?
    /// A write the user asked for did not happen — said, never swallowed (`TrashViewModel`'s lesson).
    @State private var refusal: String?

    /// The viewer over the live feed — the Library's.
    init(binding: PhotosFeedBinding, path: String, fallback: Int32) {
        _model = StateObject(wrappedValue: ViewerModel(source: FeedViewerSource(binding: binding), path: path, fallback: fallback))
    }

    /// The viewer over a collection's photographs — whole, so nothing pages. [onChanged] lets the
    /// collection reload after a write the viewer made (a trash).
    init(photos: [PhotosPhoto], path: String, onChanged: @escaping () -> Void) {
        let position = Int32(photos.firstIndex { $0.path == path } ?? 0)
        _model = StateObject(wrappedValue: ViewerModel(
            source: FixedViewerSource(photos: photos, onChanged: onChanged), path: path, fallback: position
        ))
    }

    var body: some View {
        // ⚠⚠ ONE observer for the viewer's whole life, on the container — not one per branch:
        // Try Again swaps the error branch for the spinner, and a branch-scoped task is cancelled
        // and recreated across that swap, missing the retry's Loading → Error and leaving the
        // spinner for ever.
        Group { content }
            .task { await model.observe() }
            // ⚠ The viewer is full screen in EVERY state — the pager, but also while it looks for a
            // photograph, a paging error and a missing photograph: a viewer pushed inside a tab must
            // not carry the tab bar over the black ground.
            .toolbar(.hidden, for: .tabBar)
            #if DEV_HARNESS
            .onAppear {
                ViewerProbe.model = model
                ViewerProbe.trash = { trash() }
                ViewerProbe.dismissRefusal = { refusal = nil }
                ViewerProbe.refusal = { refusal }
            }
            .onDisappear { ViewerProbe.model = nil; print("[photos-probe] viewer closed") }
            #endif
            // The About sheet, driven by the view model's `infoOpen` — opening it is what reads the
            // details, the albums and the EXIF; closing it is what the pager's lock waits for.
            .sheet(isPresented: Binding(
                get: { model.state?.infoOpen ?? false },
                set: { if !$0 { model.viewModel?.setInfoOpen(open: false) } }
            )) {
                if let state = model.state {
                    AboutSheetView(content: PhotosSurface.shared.aboutSheet(state: state))
                        .presentationDetents([.medium, .large])
                }
            }
            .sheet(isPresented: $pickingAlbum) {
                AlbumPickerSheet { albumId in
                    guard let photo = albumFor else { return }
                    model.viewModel?.addToAlbum(albumId: albumId, photo: photo) { ok in
                        if !ok.boolValue { refusal = "This photo couldn’t be added to the album." }
                    }
                }
            }
            .alert(
                refusal ?? "",
                isPresented: Binding(get: { refusal != nil }, set: { if !$0 { refusal = nil } })
            ) {
                Button("OK") {}
            }
    }

    /// ⚠⚠ Closes only when the trash HAPPENED. A refusal keeps the photograph on screen and says so:
    /// a viewer that closed as though it had gone is the `/Trash` incident (`TrashViewModel`).
    private func trash() {
        model.viewModel?.trash { ok in
            if ok.boolValue {
                // The file moved to `/Trash`, which the feed excludes — its tile must be gone before
                // the grid is seen again (Android refreshes in the same place).
                model.source.refresh()
                dismiss()
            } else {
                refusal = "This photo couldn’t be moved to the trash."
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if model.missing {
            Text("This photo is no longer in your library.")
                .foregroundStyle(.white.opacity(0.85))
                .multilineTextAlignment(.center)
                .padding(32)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black.ignoresSafeArea())
        } else if model.resolving && model.lookupFailed {
            VStack(spacing: 16) {
                Text("Couldn’t load this photo.")
                    .foregroundStyle(.white.opacity(0.85))
                Button("Try Again") { model.retryLookup() }
                    .buttonStyle(.bordered)
                    .tint(.white)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black.ignoresSafeArea())
        } else if model.resolving {
            ProgressView()
                .tint(.white)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black.ignoresSafeArea())
        } else {
            pager
        }
    }

    private var pager: some View {
        GeometryReader { geometry in
            TabView(selection: $model.current) {
                ForEach(model.positions, id: \.self) { position in
                    page(at: position, size: geometry.size)
                        .tag(position)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .background(Color.black.ignoresSafeArea())
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    // ⚠ Explicitly light: the viewer's ground is black and its bar has no
                    // background, so the system's light-mode primary draws the date black on black
                    // — only the caption shows. `toolbarColorScheme` does not reach a bar without a
                    // background, which is why it is not used here.
                    Text(model.title(for: model.current)).font(.headline).foregroundStyle(.white)
                    Text(model.subtitle(for: model.current)).font(.caption).foregroundStyle(.white.opacity(0.7))
                }
            }
            ToolbarItemGroup(placement: .primaryAction) {
                let canFavourite = model.state?.canFavourite ?? false
                let on = model.state?.favourite?.boolValue ?? false
                // ⚠⚠ DISABLED, not hollow, for a photograph with no content id: the flag ops refuse it,
                // and a star drawn empty and failing on tap is a quiet lie.
                Button { model.viewModel?.toggleFavourite() } label: {
                    Image(systemName: on ? "heart.fill" : "heart")
                }
                // ⚠ And until the flag is KNOWN: a tap during the read would write over a favourite
                // it had not seen yet (`PhotoViewerViewModel.toggleFavourite`).
                .disabled(!canFavourite || model.state?.favourite == nil || model.writing)
                .accessibilityLabel(on ? "Remove from Favourites" : "Add to Favourites")
                Menu {
                    // Compose's first item: the panel of what is known about the photograph.
                    Button { model.viewModel?.setInfoOpen(open: true) } label: {
                        Label("About", systemImage: "info.circle")
                    }
                    // ⚠ Albums hold content ids — the same gate as the star.
                    if canFavourite {
                        Button {
                            albumFor = model.state?.photo
                            pickingAlbum = albumFor != nil
                        } label: {
                            Label("Add to Album", systemImage: "rectangle.stack.badge.plus")
                        }
                    }
                    // ⚠ Behind the same gate: the trash op refuses a file with no content id, so offering it
                    // could only ever end in the refusal alert.
                    if canFavourite {
                        Button(role: .destructive, action: trash) {
                            Label("Move to Trash", systemImage: "trash")
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                // ⚠ While a write runs the view model drops a second one without calling back, so the
                // menu is not offered at all rather than let a choice vanish.
                // ⚠ Only while a write runs: About is always offered, whatever the content id.
                .disabled(model.writing)
                .accessibilityLabel("More")
            }
        }
        // ⚠⚠ ONE flag for our chrome AND the status bar (§3.1b): hiding one without the other leaves
        // the layout describing bars that are not there.
        .toolbar((model.state?.chromeVisible ?? true) ? .visible : .hidden, for: .navigationBar)
        .statusBarHidden(!(model.state?.chromeVisible ?? true))
        .animation(.easeInOut(duration: 0.2), value: model.state?.chromeVisible ?? true)
        .onChange(of: model.current) { position in model.shown(position) }
        .onAppear { model.shown(model.current) }
    }

    @ViewBuilder
    private func page(at position: Int32, size: CGSize) -> some View {
        if let photo = model.source.photoAt(position) {
            // A tap on the photograph toggles the chrome — ours and the OS bars, together; a drag down at
            // fit-scale past the line closes the viewer (`ZoomableImage`).
            HeroImageView(
                photo: photo,
                viewport: size,
                onTap: { model.viewModel?.toggleChrome() },
                onDismiss: { dismiss() },
                // Upward opens About — Compose's "upward scrolls into About" (§11).
                onSwipeUp: { model.viewModel?.setInfoOpen(open: true) }
            )
        } else {
            Color.black
        }
    }
}

/// One page's photograph: the best rung present now, then sharper ones as they arrive.
struct HeroImageView: View {
    let photo: PhotosPhoto
    let viewport: CGSize
    var onTap: () -> Void = {}
    var onDismiss: () -> Void = {}
    var onSwipeUp: () -> Void = {}

    @State private var image: UIImage?
    /// The stream finished and nothing in it decoded — `HeroState.Absent` on Compose.
    @State private var absent = false

    var body: some View {
        ZStack {
            Color.black
            if let image {
                ZoomableImage(image: image, identity: photo.path, onTap: onTap, onDismiss: onDismiss,
                              onSwipeUp: onSwipeUp)
            } else if absent {
                // ⚠⚠ Not a spinner: the stream has ENDED, so a spinner here spins for ever over a
                // photograph that is not coming (a file whose uploader minted no rung).
                // Android's words, so the two apps say the same thing about the same state.
                Text("This photo hasn't been downloaded to this device yet.")
                    .foregroundStyle(.white.opacity(0.85))
                    .multilineTextAlignment(.center)
                    .padding(32)
            } else {
                ProgressView().tint(.white)
            }
        }
        .accessibilityLabel(photo.isVideo ? "Video" : "Photo")
        .task(id: photo.path) {
            // ⚠ A pager page is reused per POSITION; when a refresh moves another photograph onto it,
            // the previous occupant's image must not stay on screen. Keyed by path,
            // so the same photograph never flickers.
            image = nil
            absent = false
            let scale = UIScreen.main.scale
            let widthPx = Int32(viewport.width * scale)
            let heightPx = Int32(viewport.height * scale)
            // ⚠ The request size is the shared `heroPreviewRequestPx` (§34), inside the surface.
            let stream = PhotosSurface.shared.heroPreview(
                path: photo.path,
                viewportWidthPx: widthPx,
                viewportHeightPx: heightPx,
                pixelWidth: photo.pixelWidth?.int32Value ?? 0,
                pixelHeight: photo.pixelHeight?.int32Value ?? 0
            )
            for await element in stream {
                // ⚠ A Flow's element crosses SKIE as the Obj-C class (`NSData`), unlike a suspend
                // return, which bridges to `Data`. `as Data` is a bridging conversion either way.
                let data = element as Foundation.Data
                // Decode off the main actor: a 4096 px rung is tens of milliseconds.
                let maxPixels = Int(max(widthPx, heightPx))
                let decoded = await Task.detached(priority: .userInitiated) {
                    TileImageLoader.downsample(data, maxPixels: maxPixels)
                }.value
                if let decoded { image = decoded }
            }
            // ⚠ Only once the stream is over, and only when nothing was drawn: a page that already
            // shows a picture keeps it, and a page swiped away (cancelled) decides nothing.
            if image == nil && !Task.isCancelled { absent = true }
        }
    }
}
