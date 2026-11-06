import SwiftUI
import TenshuSdk

// The Library — the feed (`photos-app.md` S1) as SwiftUI draws it (photos-ios.md).
//
// ⚠⚠ Swift decides nothing here (PIO-D2). The lines — which are headers, which are rows, how many
// tiles each row holds, where the unloaded placeholders sit — are `FeedLayout`'s, built by the
// shared `PhotosFeedBinding` exactly as Compose builds them. This file draws `0 ..< lineCount`.
//
// The grid itself is a UICollectionView (`FeedGridView`) — see that file for why not a LazyVStack.
// Here: all four densities (Mosaic the default), the re-anchor, the sticky header, the scrubber
// (`FeedScrubberView`), multi-select (`FeedSelection.swift`).

/// Bridges the shared feed binding to SwiftUI.
@MainActor
final class LibraryModel: ObservableObject {
    let binding: PhotosFeedBinding = PhotosSurface.shared.feed()
    /// The fast-scroll thumb's state — its own object, written at scroll rate (`ScrubberState`).
    let scrubber = ScrubberState()
    /// The multi-select's mirror — its own object, so a toggle re-renders marks, not the screen.
    let selection: SelectionState
    /// The motion rotation's mirror — its own object, as the others are.
    let motion: MotionState
    @Published private(set) var layout: FeedLayout
    @Published private(set) var status: FeedStatus = .loading
    /// Set by the grid: scroll to a line (the demo harness's lever; re-anchors go through the binding).
    var gridScroller: ((Int) -> Void)?
    /// Set by the screen: open the viewer on a photograph by its path — a tile tap (demo harness).
    var opener: ((String) -> Void)?
    /// Set by the screen: close the viewer — the Back button (demo harness).
    var closer: (() -> Void)?
    @Published private(set) var density: FeedDensity = .mosaic
    /// A page load failed (`PhotosFeedBinding.pagingFailed`) — observed on its own, not only when the
    /// next layout arrives: a failed load changes no layout.
    @Published private(set) var pagingFailed = false

    init() {
        // Mosaic is the Day layout and the default, as on Android (`photos-app.md` §32) — its
        // `FeedLine.Feature` blocks are drawn by `FeedGridView`.
        binding.viewModel.setDensity(value: .mosaic)
        layout = binding.layout.value
        selection = SelectionState(viewModel: binding.viewModel)
        motion = MotionState(rotation: binding.viewModel.motion)
    }

    func observe() async {
        async let layouts: Void = observeLayout()
        async let statuses: Void = observeStatus()
        async let densities: Void = observeDensity()
        async let requests: Void = observeScrollRequests()
        async let failures: Void = observePagingFailures()
        async let years: Void = observeYearMarks()
        async let selections: Void = selection.observe()
        async let turns: Void = motion.observe()
        _ = await (layouts, statuses, densities, requests, failures, years, selections, turns)
    }

    /// ⚠ The whole library's years, read once (`PhotosFeedViewModel.yearMarks`), not the loaded
    /// pages — pills marked from what is paged in would move as the user scrolls.
    private func observeYearMarks() async {
        for await marks in binding.viewModel.yearMarks { scrubber.yearMarks = marks }
    }

    private func observePagingFailures() async {
        for await failed in binding.pagingFailed { pagingFailed = failed.boolValue }
    }

    private func observeDensity() async {
        for await value in binding.viewModel.density { density = value }
    }

    private func observeScrollRequests() async {
        // Only a trigger: the request itself is read synchronously through `scrollTarget`.
        // ⚠ The held layout first: a request names a line in the newest layout, and `scrollTarget`
        // offers nothing against an older one.
        for await _ in binding.scrollRequests {
            drawHeld()
            offerScroll()
        }
    }

    /// The toolbar's one button: the next grouping (`FeedDensity.next()`, the Compose control) —
    /// Mosaic → Day → Month → Year → Mosaic.
    func cycleDensity() {
        // ⚠ From the binding's value, not the async mirror: two quick taps would both
        // read the same stale `density` and one would be lost.
        binding.viewModel.setDensity(value: binding.viewModel.density.value.next())
    }

    /// The top of the viewport, for the re-anchor — reported only when it moves to another line or
    /// across a line's half, which is all `FeedLayout.photoAt(line:scrolledPast:)` distinguishes.
    /// ⚠ Keyed on the LAYOUT too: the line indexes the layout on screen, and the binding resolves it
    /// against that one — so a newly drawn layout is reported even at the same line.
    private var reportedTop: (line: Int, pastHalf: Bool, layout: ObjectIdentifier)?
    /// ⚠⚠ [measured] is the layout the grid is DRAWING, not `layout`: the model can
    /// advance before the grid reloads, and tagging the drawn line numbers with the newer layout
    /// re-anchors a density change on another photograph.
    func viewportChanged(topLine: Int, scrolledPast: CGFloat, measured: FeedLayout) {
        let pastHalf = scrolledPast > 0.5
        let drawn = ObjectIdentifier(measured)
        if let last = reportedTop, last.line == topLine, last.pastHalf == pastHalf, last.layout == drawn { return }
        reportedTop = (topLine, pastHalf, drawn)
        binding.setViewport(topLine: Int32(topLine), scrolledPast: Float(scrolledPast), layout: measured)
        #if DEV_HARNESS
        // The probe's evidence (`-photosDemo`): which photograph the grid has on top, by library index.
        print("[photos-probe] top line=\(topLine) photo=#\(measured.photoAt(line: Int32(topLine))?.intValue ?? -1) columns=\(measured.columns)")
        #endif
    }

    /// ⚠⚠ Layouts are DRAWN at the pacer's rate, not the binding's (photos-ios.md §8.20): a sync is a
    /// layout per photograph. What the user did is never held (`FeedLayoutPacer`), and neither is a
    /// layout the grid says it needs now.
    private let pacer = FeedLayoutPacer(minIntervalMs: FeedLayoutPacer.companion.MIN_INTERVAL_MS)
    /// Set by the grid: a finger or the scrubber is on the list, or it is still looking for the
    /// photograph it kept (`FeedGridView.Coordinator.needsLayoutsNow`).
    var gridNeedsLayoutsNow: (() -> Bool)?
    /// ⚠ Unstructured: the screen's `.task` is cancelled when the viewer is pushed, and a held layout
    /// is owed its draw regardless.
    private var release: Task<Void, Never>?

    #if DEV_HARNESS
    /// Layouts the binding has emitted — what a status-row write must not add to (`-photosSyncRowProbe`).
    private(set) var layoutsSeen = 0
    #endif

    private static var nowMs: Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

    private func observeLayout() async {
        for await value in binding.layout {
            #if DEV_HARNESS
            layoutsSeen += 1
            #endif
            var urgent = binding.reanchorPending || (gridNeedsLayoutsNow?() ?? false)
            #if DEV_HARNESS
            // `-photosNoPacing`: every layout drawn, unpaced — the probe's baseline.
            if ProcessInfo.processInfo.arguments.contains("-photosNoPacing") { urgent = true }
            #endif
            if let due = pacer.offer(layout: value, nowMs: Self.nowMs, urgent: urgent) {
                release?.cancel()
                release = nil
                draw(due)
            } else if release == nil, let wait = pacer.heldDueInMs(nowMs: Self.nowMs)?.int64Value {
                release = Task { [weak self] in
                    try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000)
                    // ⚠ A cancelled sleep returns at once; whoever cancelled has drawn or will.
                    if !Task.isCancelled { self?.drawHeld() }
                }
            }
        }
    }

    /// Draw the held layout now — it is due, or a scroll request needs the newest layout drawn first.
    private func drawHeld() {
        release?.cancel()
        release = nil
        if let held = pacer.release(nowMs: Self.nowMs) { draw(held) }
    }

    private func draw(_ value: FeedLayout) {
        layout = value
        offerScroll()
    }

    /// A scroll the grid should perform — offered once per request serial. ⚠ Only a trigger: the
    /// line is asked of the binding again at every hop (`PhotosFeedBinding.scrollTarget`), which is
    /// what decides — synchronously — whether it is still wanted and where.
    @Published private(set) var offeredScroll: PhotosFeedBinding.ScrollTarget?
    private var offeredSerial: Int32?

    private func offerScroll() {
        guard let target = binding.scrollTarget(drawn: layout), target.serial != offeredSerial else { return }
        offeredSerial = target.serial
        offeredScroll = target
    }

    private func observeStatus() async {
        for await value in binding.status { status = value }
    }

    /// The grid's measured width, in points — the shared binding derives the column count from it
    /// (`columnsAt`, the rule Compose's feed uses). ⚠⚠ Without it an iPad keeps the phone's three
    /// columns: 340 pt tiles at full width (photos-ios.md §8.5).
    func gridWidthChanged(_ width: CGFloat) {
        pacer.expediteNext()
        binding.setGridWidth(widthDp: KotlinFloat(value: Float(width)))
    }

}

struct LibraryScreen: View {
    @StateObject private var model = LibraryModel()
    @Environment(\.scenePhase) private var scenePhase
    /// Navigation only: Settings is pushed (ios-on-device-inference.md §14.18, M1).
    @State private var showSettings = LibraryScreen.opensSettings

    private static var opensSettings: Bool {
        #if DEV_HARNESS
        return ModelsProbe.opens != nil
        #else
        return false
        #endif
    }

    var body: some View {
        content
            // "Syncing…" while a pull is bringing photographs in (`ios-sync-activity-indicator.md`).
            // ⚠⚠ On the screen ROOT and as an overlay: it must not change the grid's frame, insets
            // or offset, and it appears exactly when `FeedGridView` is busiest (photos-ios.md §8.20).
            .modifier(librarySyncCapsule)
            // The title, the density button, and — while a selection exists — its chrome.
            .modifier(LibraryChrome(
                selection: model.selection,
                density: model.density,
                date: model.status == .loaded ? stickyLabel : nil,
                underBar: model.status == .loaded && underBar,
                symbol: Self.symbol(for: model.density),
                cycleDensity: model.cycleDensity,
                openSettings: { showSettings = true }
            ))
            .navigationDestination(isPresented: $showSettings) { PhotosSettingsScreen() }
            .navigationDestination(isPresented: Binding(
                get: { openRoute != nil },
                set: { if !$0 { openRoute = nil } }
            )) {
                if let route = openRoute {
                    // The viewer keeps the identity and resolves it against its own snapshots.
                    PhotoViewerScreen(binding: model.binding, path: route.path, fallback: route.position)
                }
            }
            .task { await model.observe() }
            // ⚠ Leaving the Library ends a re-anchor still waiting for pages: it
            // stays alive behind a pushed viewer, and a later page would move the grid the user is
            // coming back to. Every way out lands here, not only a tile.
            .onDisappear {
                model.binding.cancelReanchor()
                // Behind the viewer or gone: nobody sees a moving tile (Compose's DisposableEffect).
                model.motion.setAppeared(false)
            }
            .onAppear { model.motion.setAppeared(true) }
            // ⚠ A backgrounded app decoding video is a battery bug — paused with the scene, resumed
            // with it, in document order (Compose's `LifecycleResumeEffect`).
            .onChange(of: scenePhase) { phase in model.motion.setSceneActive(phase == .active) }
    }

    private var librarySyncCapsule: LibrarySyncCapsule {
        #if DEV_HARNESS
        LibrarySyncCapsule(selection: model.selection, status: model.status, layoutsSeen: { [model] in model.layoutsSeen })
        #else
        LibrarySyncCapsule(selection: model.selection, status: model.status)
        #endif
    }

    @ViewBuilder
    private var content: some View {
        switch model.status {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .empty:
            PhotosEmptyState(
                title: "No Photos Yet",
                systemImage: "photo.on.rectangle",
                description: "Photos backed up from any of your devices appear here. Turn on backup in Drive."
            )
        case .unreachable:
            // ⚠ Not the empty state (photos-app.md §10.3): the library exists, this app cannot see it.
            PhotosEmptyState(
                title: "Can’t Reach Your Library",
                systemImage: "exclamationmark.icloud",
                description: "Photos can’t reach Tenshu Control right now. Your photos are safe.",
                actionTitle: "Try Again",
                action: { model.binding.retry() }
            )
        case .failed:
            PhotosEmptyState(
                title: "Couldn’t Load Photos",
                systemImage: "exclamationmark.triangle",
                description: "Something went wrong reading your library.",
                actionTitle: "Try Again",
                action: { model.binding.retry() }
            )
        case .loaded:
            grid
        }
    }

    @State private var stickyLabel: String?
    /// Photographs lie under the navigation bar — the top edge effect is drawn, and it is dark.
    @State private var underBar = false
    @State private var openRoute: ViewerRoute?

    private func open(path: String, drawnAt: Int32) {
        // A tap never begins a drag; cancel a waiting re-anchor at once rather than after the push
        // (`onDisappear` is the catch-all).
        model.binding.cancelReanchor()
        // ⚠⚠ The route carries the photograph's PATH — its identity — not an index:
        // a page can land between the tap and the viewer's construction. The drawn position is only
        // the viewer's place to stand while it looks.
        openRoute = ViewerRoute(path: path, position: drawnAt)
    }

    private var grid: some View {
        FeedGridView(
            model: model,
            onOpen: { open(path: $0.path, drawnAt: $1) },
            onStickyLabel: {
                stickyLabel = $0
                underBar = $1
            }
        )
        // ⚠⚠ Under BOTH bars (photos-ios.md §8.21): the bars are the system's glass, and glass with
        // nothing behind it is a white slab. The grid's own `adjustedContentInset` keeps the first
        // and last rows clear of them.
        .ignoresSafeArea(.container, edges: .vertical)
        // ⚠ The overlays are laid out INSIDE the safe area — below the navigation bar, above the tab
        // bar — which is where the date and the scrubber's track belong.
        .overlay { LibraryStickyDate(label: stickyLabel) }
        // ⚠ Over the grid, not beside it — the reference apps float it on the photographs.
        .overlay { FeedScrubberView(state: model.scrubber) }
        #if DEV_HARNESS
        .task {
            model.opener = { path in
                open(path: path, drawnAt: model.binding.positionOf(path: path)?.int32Value ?? 0)
            }
            model.closer = { openRoute = nil }
            SyncCapsuleProbe.shared.scroll = { model.gridScroller?($0) }
            await PhotosScreenProbe.runDemo(scrollTo: { model.gridScroller?($0) }, model: model)
        }
        .task {
            await PhotosScreenProbe.runScrub(model: model)
        }
        .task {
            await PhotosScreenProbe.runArrivals(scrollTo: { model.gridScroller?($0) })
        }
        .task {
            await PhotosScreenProbe.runSelect(model: model)
        }
        .onAppear {
            // ⚠ Unstructured, not `.task`: this demo pushes the viewer, which cancels the Library's
            // tasks — and a cancelled `Task.sleep` returns at once, so every step would race ahead.
            PhotosScreenProbe.startBackDemo(model: model)
            PhotosScreenProbe.startViewerDemo(model: model)
        }
        #endif
    }

    /// The grid symbol for each grouping — denser grids for coarser groupings.
    private static func symbol(for density: FeedDensity) -> String {
        switch density {
        case .mosaic: return "rectangle.grid.2x2"
        case .month: return "square.grid.4x3.fill"
        case .year: return "circle.grid.3x3.fill"
        default: return "square.grid.3x3"
        }
    }

}

/// The date of the row at the top, once its own header has scrolled away.
///
/// ⚠⚠ iOS 26+ draws NOTHING here: the date is under the title in the navigation bar (`LibraryChrome`), which
/// is where Apple's Photos puts it, and a strip of material under a glass bar is the opaque slab the
/// scroll edge effect replaces (photos-ios.md §8.21).
/// Before iOS 26 the bars are the translucent kind, and this strip continues the navigation bar.
private struct LibraryStickyDate: View {
    let label: String?

    var body: some View {
        if #available(iOS 26.0, *) {
            EmptyView()
        } else if let label {
            Text(label)
                .font(.headline)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
                .background(.bar)
                .frame(maxHeight: .infinity, alignment: .top)
                // ⚠ Decoration: a swipe that starts on it must reach the grid — to scroll, and to
                // cancel a waiting re-anchor.
                .allowsHitTesting(false)
        }
    }
}

/// The Library's mount of the shared capsule, with the three facts only this screen has.
///
/// ⚠ Its own modifier so that it — not the whole screen — re-renders when the selection changes.
private struct LibrarySyncCapsule: ViewModifier {
    @ObservedObject var selection: SelectionState
    let status: FeedStatus
    #if DEV_HARNESS
    let layoutsSeen: () -> Int
    #endif
    #if DEV_HARNESS
    @ObservedObject private var probe = SyncCapsuleProbe.shared
    #endif

    func body(content: Content) -> some View {
        content.syncActivityCapsule(
            // Photos takes the wall, so there is no not-connected BAR to yield to: when the
            // runtime is absent this screen is not built at all. Its own problem is the Library
            // saying it cannot be read, which fills the screen.
            hostProblem: hostProblem,
            // `PhotosConnectedRoot` mounts `RealmStorageBanner` above the tabs.
            showsStorageBar: true,
            // The selection's bar is up: its actions own the bottom of the screen.
            hidden: !selection.paths.isEmpty
        )
        #if DEV_HARNESS
        .overlay(alignment: .topLeading) { SyncCapsuleProbe.Readout() }
        .task { await SyncCapsuleProbe.shared.run() }
        .task { await SyncCapsuleProbe.shared.runRowProbe(layoutsSeen: layoutsSeen) }
        #endif
    }

    private var hostProblem: Bool {
        #if DEV_HARNESS
        if probe.hostProblem { return true }
        #endif
        return status == .unreachable || status == .failed
    }
}

/// Where the viewer opens: a position in the feed's list, not a photograph — the viewer is a pager
/// over the list the grid showed (`photos-app.md` §21).
struct ViewerRoute: Hashable {
    /// The photograph's identity — its path (`file-content-addressing.md` §3.3.1) — resolved again
    /// by the viewer against its own snapshots.
    let path: String
    /// Where it was drawn at the tap — the viewer's place to stand while it looks.
    let position: Int32
}
