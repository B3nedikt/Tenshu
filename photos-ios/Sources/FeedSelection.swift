import SwiftUI
import TenshuSdk

// The Library's multi-select (photos-ios.md §8.9) — Android's S1 (`photos-app.md` §27).
//
// ⚠⚠ Swift decides nothing (PIO-D2). The selection, the bulk actions and their partial-failure
// contract are `PhotosFeedViewModel`'s: the successes leave the selection, the FAILURES STAY
// SELECTED so the user can retry them, and `BulkOutcome.title` / `message` are the sentences both
// shells say. This file draws the marks, the chrome and the album sheet.
//
// ⚠ Empty means NOT selecting — there is no separate "select mode" flag, as on Android. A long press
// begins a selection; while one exists a tap toggles instead of opening the viewer.
//
// ⚠ No Share action, deliberately: parity keeps it absent (§7 Q3, `photos-app.md` §27.1).

/// The selected paths, mirrored for the tiles and the chrome. ⚠ Its own object, as `PreviewGate` is:
/// a toggle re-renders the marks, never the screen.
@MainActor
final class SelectionState: ObservableObject {
    @Published private(set) var paths: Set<String> = []
    /// The album sheet is up.
    @Published var pickingAlbum = false
    /// ⚠⚠ The last bulk action's report, held until dismissed — ALWAYS reported, the all-succeeded
    /// case included: a bulk action with no feedback leaves the user counting tiles (Android's
    /// `lastBulk`).
    @Published var outcome: BulkOutcome?
    /// A bulk action is running (`PhotosFeedViewModel.acting`) — the bar's actions wait. ⚠ The guard
    /// is the view model's own (single-flight); this only stops offering what it would drop.
    @Published private(set) var acting = false

    private let viewModel: PhotosFeedViewModel

    init(viewModel: PhotosFeedViewModel) {
        self.viewModel = viewModel
    }

    func observe() async {
        async let selections: Void = observeSelected()
        async let running: Void = observeActing()
        _ = await (selections, running)
    }

    private func observeSelected() async {
        for await selected in viewModel.selected { paths = Set(selected.keys) }
    }

    private func observeActing() async {
        for await value in viewModel.acting { acting = value.boolValue }
    }

    /// ⚠ Read from the view model, not the async mirror (as the density button is):
    /// a tap right after a long press must see the selection the press just made.
    var isSelecting: Bool { !viewModel.selected.value.isEmpty }

    func toggle(_ photo: PhotosPhoto) { viewModel.toggleSelection(photo: photo) }

    func clear() { viewModel.clearSelection() }

    /// ⚠ No confirmation: trash is recoverable (Android's reasoning for its unconfirmed Delete key).
    func trash() { viewModel.trashSelected { [weak self] in self?.outcome = $0 } }

    func addToAlbum(_ albumId: String) {
        viewModel.addSelectedToAlbum(albumId: albumId) { [weak self] in self?.outcome = $0 }
    }
}

/// A grid tile that can be selected: a tap opens, or toggles while a selection exists; a long press
/// toggles.
struct SelectableTile: View {
    let photo: PhotosPhoto
    /// Where it is drawn — the viewer's place to stand (see `FeedGridView.onOpen`).
    let position: Int32
    let edge: CGFloat
    let scale: CGFloat
    let previews: PreviewGate
    @ObservedObject var selection: SelectionState
    let motion: MotionState
    let onOpen: (PhotosPhoto, Int32) -> Void

    var body: some View {
        let selected = selection.paths.contains(photo.path)
        PhotoTileView(photo: photo, edgePoints: edge, scale: scale, previews: previews, selected: selected,
                      motion: motion)
            .onTapGesture {
                if selection.isSelecting { selection.toggle(photo) } else { onOpen(photo, position) }
            }
            .onLongPressGesture { selection.toggle(photo) }
            .accessibilityAddTraits(.isButton)
            // The file's name — how a UI test finds one photograph among hundreds of "Photo"s.
            .accessibilityIdentifier(photo.path.split(separator: "/").last.map(String.init) ?? photo.path)
            .accessibilityAction(named: selected ? "Deselect" : "Select") { selection.toggle(photo) }
    }
}

/// The Library's chrome while a selection exists: the count and Cancel in the navigation bar, Add to
/// Album and Trash in a bottom bar, the album sheet and the outcome report. Otherwise the density
/// button.
struct LibraryChrome: ViewModifier {
    @ObservedObject var selection: SelectionState
    let density: FeedDensity
    /// The date of the row at the top once its header has scrolled away — under the title on
    /// iOS 26+, where Apple's Photos has it (photos-ios.md §8.21).
    let date: String?
    /// Photographs lie under the navigation bar, so the bar is read against the dark edge effect.
    let underBar: Bool
    let symbol: String
    let cycleDensity: () -> Void
    /// Opens Settings, or `nil` for no button. ⚠ Every build has Settings (the privacy-policy row).
    var openSettings: (() -> Void)? = nil

    /// The bar's controls draw their own glass (iOS 26+, where a bar item has any) — the title's
    /// `underBar`, and nothing else, decides.
    private var overPhotos: Bool {
        if #available(iOS 26.0, *) { return underBar }
        return false
    }

    func body(content: Content) -> some View {
        let selecting = !selection.paths.isEmpty
        content
            .navigationTitle(selecting ? "\(selection.paths.count) Selected" : "Library")
            .modifier(LibraryTitleStyle(selecting: selecting, date: date, underBar: underBar))
            .toolbar {
                // Settings, where this build has any (ios-on-device-inference.md §14.18, M1). Over
                // photographs it is the same clear glass as the density button, from the same flag.
                // ⚠ The identifier stays bare: `ModelsUITests` finds it by exactly this name. ⚠⚠
                // Its own item, never one pill shared with the density button: over photographs
                // each draws its own glass with the SOLO metrics, and from a shared pill the gear
                // moves 2–6 pt when the backdrop changes (BarControlUITests, 26.5 and 27.0). So
                // both items share ONE placement and the spacer is declared BETWEEN them — a
                // `.primaryAction` density button is placed trailing-most whatever the order, and a
                // spacer before the gear sits on its far side, separating nothing (measured).
                if let openSettings, !selecting {
                    ToolbarItem(placement: .topBarTrailing) {
                        BarButtonOverPhotos(label: .symbol("gearshape"), overPhotos: overPhotos, action: openSettings)
                            .accessibilityLabel(PhotosModelsCopy.shared.SETTINGS)
                            .accessibilityIdentifier("library-settings")
                    }
                    .barItemOverPhotos(overPhotos)
                    if #available(iOS 26.0, *) {
                        ToolbarSpacer(.fixed, placement: .topBarTrailing)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    // ⚠⚠ Both are `BarButtonOverPhotos`, from the title's own `underBar`: the forced dark
                    // scheme pins a system item's label white on glass that iOS 26.x turns light over
                    // a bright photograph (photos-ios.md §8.21, rule 11).
                    // ⚠ The identifier's suffix is the state, for the UI test — never spoken.
                    if selecting {
                        BarButtonOverPhotos(label: .text("Cancel"), overPhotos: overPhotos) { selection.clear() }
                            .accessibilityIdentifier("library-cancel-selection" + (overPhotos ? ".over-photos" : ""))
                    } else {
                        // ⚠ One button that CYCLES, as on Android (`photos-app.md` §32): the label
                        // names the CURRENT grouping, shared with Compose.
                        BarButtonOverPhotos(label: .symbol(symbol), overPhotos: overPhotos, action: cycleDensity)
                            .accessibilityLabel(density.description_)
                            .accessibilityIdentifier("library-density" + (overPhotos ? ".over-photos" : ""))
                    }
                }
                .barItemOverPhotos(overPhotos)
                ToolbarItemGroup(placement: .bottomBar) {
                    if selecting {
                        Button { selection.pickingAlbum = true } label: {
                            Label("Add to Album", systemImage: "rectangle.stack.badge.plus")
                        }
                        .disabled(selection.acting)
                        Spacer()
                        if selection.acting { ProgressView() }
                        Spacer()
                        Button(role: .destructive) { selection.trash() } label: {
                            Label("Move to Trash", systemImage: "trash")
                        }
                        .disabled(selection.acting)
                    }
                }
            }
            .toolbar(selecting ? .visible : .hidden, for: .bottomBar)
            .sheet(isPresented: $selection.pickingAlbum) {
                AlbumPickerSheet { albumId in selection.addToAlbum(albumId) }
            }
            .alert(
                selection.outcome?.title ?? "",
                isPresented: Binding(get: { selection.outcome != nil }, set: { if !$0 { selection.outcome = nil } }),
                presenting: selection.outcome
            ) { _ in
                Button("OK") {}
            } message: { outcome in
                Text(outcome.message)
            }
    }
}

/// The Library's title: large and at the leading edge with the date of the top row under it, where
/// Apple's Photos has both, and white once photographs lie under the bar (photos-ios.md §8.21).
///
/// ⚠⚠ The colour follows the BACKDROP, never the appearance: the top edge effect is dark in both
/// appearances (`FeedGridView`), and it is drawn only while something is under the bar. At rest the
/// bar is over the page, and white on a white page is no title at all.
/// ⚠ A toolbar item, not the navigation title: the system's large inline title collapses to a small
/// centred one as soon as the grid scrolls. The navigation title stays, for VoiceOver and the back
/// button; only its drawing in the bar is removed.
private struct LibraryTitleStyle: ViewModifier {
    let selecting: Bool
    let date: String?
    let underBar: Bool

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content
                .navigationBarTitleDisplayMode(.inline)
                .toolbar(removing: selecting ? nil : .title)
                .toolbar {
                    if !selecting {
                        ToolbarItem(placement: .topBarLeading) {
                            VStack(alignment: .leading, spacing: 0) {
                                Text("Library").font(.title.bold())
                                // ⚠ Always laid out: the title must not move when the date arrives.
                                Text(date ?? " ").font(.footnote.weight(.semibold))
                            }
                            .fixedSize()
                            .dynamicTypeSize(...DynamicTypeSize.xxLarge) // the bar does not grow
                            // ⚠ The soft effect has faded most of the way out where the date sits, and
                            // over a white photograph that is white on light grey without this.
                            .shadow(color: .black.opacity(underBar ? 0.45 : 0), radius: 2, y: 0.5)
                            .accessibilityElement(children: .combine)
                            .accessibilityAddTraits(.isHeader)
                            .accessibilityIdentifier("library-title")
                        }
                        .sharedBackgroundVisibility(.hidden)
                    }
                }
                .toolbarColorScheme(underBar ? .dark : nil, for: .navigationBar)
        } else {
            content.navigationBarTitleDisplayMode(selecting ? .inline : .automatic)
        }
    }
}

/// The albums to add the selection to.
///
/// ⚠ Lists what exists and nothing else, as Android's picker does: creating an album belongs where
/// the user can see the ones they have (Collections).
struct AlbumPickerSheet: View {
    let onPick: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var state: Load = .loading

    private enum Load {
        case loading
        case albums([AlbumSummary])
        /// ⚠⚠ Not "no albums": Control is down, and the albums are safe.
        case unreachable
        case failed
    }

    var body: some View {
        NavigationStack {
            Group {
                switch state {
                case .loading:
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                case .albums(let albums) where albums.isEmpty:
                    // ⚠ Points nowhere: a sentence naming a tab is wrong wherever that tab does not
                    // exist, so the copy names none.
                    PhotosEmptyState(
                        title: "No Albums Yet",
                        systemImage: "rectangle.stack",
                        description: "You haven’t made an album yet."
                    )
                case .albums(let albums):
                    List(albums, id: \.albumId) { album in
                        Button {
                            onPick(album.albumId)
                            dismiss()
                        } label: {
                            HStack {
                                Text(album.name).foregroundStyle(.primary)
                                Spacer()
                                Text("\(album.photoCount)").foregroundStyle(.secondary)
                            }
                        }
                    }
                case .unreachable:
                    PhotosEmptyState(
                        title: "Can’t Reach Your Albums",
                        systemImage: "exclamationmark.icloud",
                        description: "Photos can’t reach Tenshu Control right now. Your albums are safe.",
                        actionTitle: "Try Again",
                        action: { Task { await load() } }
                    )
                case .failed:
                    PhotosEmptyState(
                        title: "Couldn’t Load Albums",
                        systemImage: "exclamationmark.triangle",
                        actionTitle: "Try Again",
                        action: { Task { await load() } }
                    )
                }
            }
            .navigationTitle("Add to Album")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .task { await load() }
    }

    private func load() async {
        state = .loading
        do {
            let result = try await PhotosSurface.shared.albums()
            switch onEnum(of: result) {
            case .albums(let loaded): state = .albums(loaded.albums)
            case .unreachable: state = .unreachable
            case .failed: state = .failed
            }
        } catch {
            state = .failed
        }
    }
}
