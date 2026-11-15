import SwiftUI
import TenshuSdk
import UIKit

// The Search tab (PIO-4 slice 4, photos-ios.md §8.19) — Compose's `PhotoSearchScreen` on iOS.
//
// ⚠⚠ Swift decides nothing (PIO-D2). Which state is showing — type to search, searching, results,
// nothing matched, could not look — the note above the results, when *Try Again* is offered, the
// badge under a tile and every sentence are the shared `PhotoSearchLayout` / `PhotoSearchCopy`; which
// sources run, in what order, the debounce and "a newer query supersedes an older one" are the shared
// `PhotoSearchViewModel`. This file owns the bar, lays the layout out, and opens the viewer.
//
// ⚠⚠ What iOS can truthfully offer: names, tags another device's model wrote and sync delivered
// (AI-R), and — where this build and this phone bind the on-device model — search by description.
// Where they do not, the view model is built with `DescriptionSearch.NotBuilt` (`PhotosSurface.search`):
// the source is never asked, and the screen says "isn't available on this iPhone yet" — never "no
// results", and never a switch or a download this device cannot honour. ⚠ The idle line follows the
// proxy's answer (`PhotoSearchCopy.idle`), never this file: it is `layout.body`'s text.

/// Bridges the shared `PhotoSearchViewModel` to SwiftUI.
@MainActor
final class SearchModel: ObservableObject {
    let viewModel: PhotoSearchViewModel
    @Published private(set) var state: PhotoSearchUiState
    private var observation: Task<Void, Never>?

    /// What the bar holds. ⚠ Owned HERE and pushed down, never read back from the Kotlin state: that
    /// arrives a turn later, and a text field bound to it drops characters typed in between.
    @Published var query = "" {
        didSet {
            guard query != oldValue else { return }
            viewModel.onQueryChanged(text: query)
        }
    }

    /// ⚠⚠ The model observes for its WHOLE life, not inside the screen's `.task` (§8.15 note 4): pushing
    /// the viewer cancels the root's tasks, and a re-read after a trash there would update Kotlin's
    /// state while the grid behind kept the trashed photograph.
    init() {
        // The one word of the screen Kotlin cannot know (`SemanticSearchStatus.NotBuiltHere`).
        let device = UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
        let viewModel = PhotosSurface.shared.search(device: device)
        self.viewModel = viewModel
        state = viewModel.state.value
        observation = Task { [weak self] in
            for await value in viewModel.state {
                guard let self else { return }
                self.state = value
            }
        }
    }

    deinit { observation?.cancel() }
}

/// The viewer route over the results: the photograph tapped, by its path.
private struct SearchViewerRoute: Hashable {
    let path: String
}

struct SearchScreen: View {
    @StateObject private var model = SearchModel()
    @State private var viewing: SearchViewerRoute?
    /// ⚠ Only a RETURN re-reads: on the first appearance nothing has been asked yet.
    @State private var appeared = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let layout = model.state.layout
        VStack(spacing: 0) {
            notes(layout)
            content(layout.body)
        }
        .navigationTitle("Search")
        .navigationBarTitleDisplayMode(.inline)
        .modifier(SearchBar(text: $model.query))
        // ⚠ A result opens the viewer over THE RESULTS, in the order shown (`PhotoSearchUiState.photos`,
        // Compose's `PhotoCollection.Fixed`) — as a collection does, never over the feed behind them.
        .navigationDestination(isPresented: Binding(get: { viewing != nil }, set: { if !$0 { viewing = nil } })) {
            if let viewing {
                // ⚠ A trash in the viewer re-reads the results before they are seen again.
                PhotoViewerScreen(photos: model.state.photos, path: viewing.path) { model.viewModel.refresh() }
            }
        }
        // ⚠⚠ Back from the viewer re-reads, quietly: a result must not go on citing a photograph that
        // was trashed there — or on another device meanwhile (`PhotoSearchViewModel.refresh`).
        .onAppear {
            if appeared {
                if viewing == nil {
                    model.viewModel.refresh()
                    // ⚠ The idle line asks again (a consent switched, an index finished meanwhile);
                    // Kotlin ignores it over a typed bar or a running search.
                    model.viewModel.screenShown()
                }
            } else {
                appeared = true
            }
        }
        // ⚠ The same, back from another app: Settings → Models may have changed what runs here.
        .onChange(of: scenePhase) { phase in
            if phase == .active && appeared && viewing == nil { model.viewModel.screenShown() }
        }
    }

    /// Why a source did not contribute — above the results, never instead of them.
    @ViewBuilder
    private func notes(_ layout: PhotoSearchLayout) -> some View {
        if !layout.notes.isEmpty {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(layout.notes, id: \.self) { note in
                        Text(note)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("searchNotes")
                Spacer(minLength: 0)
                if layout.retryInNotes {
                    Button(PhotoSearchCopy.shared.RETRY) { model.viewModel.retry() }
                        .font(.footnote)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            Divider()
        }
    }

    @ViewBuilder
    private func content(_ body: PhotoSearchLayoutBody) -> some View {
        switch onEnum(of: body) {
        case .idle(let idle):
            message(idle.text)
        case .searching:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .empty(let empty):
            message(empty.text)
        case .couldNotLook(let failed):
            // ⚠⚠ Not the empty state: nothing was found because not everything was looked through, and
            // the way to look again is right here. ⚠ Hand-rolled — the floor is iOS 16 (PIO-D11), and
            // `ContentUnavailableView` is 17+.
            VStack(spacing: 16) {
                Text(failed.text)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .accessibilityIdentifier("searchStatus")
                Button(PhotoSearchCopy.shared.RETRY) { model.viewModel.retry() }
                    .buttonStyle(.bordered)
            }
            .padding(32)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .results(let results):
            // ⚠ The badges come from the hits by path: one row per photograph (`PhotoHit`).
            let badges = Dictionary(
                results.hits.compactMap { hit in PhotoSearchCopy.shared.badge(hit: hit).map { (hit.photo.path, $0) } },
                uniquingKeysWith: { first, _ in first }
            )
            PhotoGrid(
                photos: results.hits.map { $0.photo },
                onOpen: { viewing = SearchViewerRoute(path: $0.path) },
                caption: { badges[$0.path] },
                footers: results.footers
            )
        }
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(32)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier("searchStatus")
    }
}

/// The search field.
///
/// ⚠ Where it sits is the OS's call on iOS 26, where a `Tab(role: .search)` morphs into the field;
/// before that the automatic placement HIDES the field until the list is pulled down, on a screen
/// that is nothing but its field — so it is pinned in the navigation bar there.
private struct SearchBar: ViewModifier {
    @Binding var text: String

    func body(content: Content) -> some View {
        Group {
            if #available(iOS 26.0, *) {
                content.searchable(text: $text, prompt: PhotoSearchCopy.shared.PROMPT)
            } else {
                content.searchable(
                    text: $text,
                    placement: .navigationBarDrawer(displayMode: .always),
                    prompt: PhotoSearchCopy.shared.PROMPT
                )
            }
        }
        // ⚠ A file name is not prose: "img_1004" must not be corrected or capitalised into no match.
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
    }
}
