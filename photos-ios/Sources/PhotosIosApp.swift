import AVFoundation
import SwiftUI
import TenshuSdk
import UIKit

// The Tenshu Photos iOS app (docs/proposals/photos-ios.md). Native SwiftUI over the shared
// `:photos-shared-logic` view models, resolved through the `PhotosSurface` umbrella seam
// (ios-proxy-architecture.md §9.3 "share view models + repos, native UI"). The twin of
// `calendar-ios` / `contacts-ios`.
//
// ⚠ Photos is **born on route 1** — provisioned → `PhotosLocalRuntime` runs the production proxy
// graph in this process over the shared App-Group replica; not provisioned → the shared
// forward-to-Control wall (PIO-D4).
@main
struct PhotosIosApp: App {
    // The application delegate is how iOS re-delivers a background URLSession's events after it
    // relaunched the app (`handleEventsForBackgroundURLSession`): the model download's.
    @UIApplicationDelegateAdaptor(PhotosAppDelegate.self) private var appDelegate

    init() {
        // ⚠⚠ The overnight index wake (ios-on-device-inference.md PR-6, §16): a `BGProcessingTask`
        // handler must be registered before launch finishes, so here, first, and in every build.
        // Only its submitting half is behind the build flag (`ProcessingWakeDriver.register`).
        ProcessingWakeDriver.register()
        // On-device AI's model download (ios-on-device-inference.md §14.11). ⚠ Behind the build
        // flag and the static gates: with either against it there is no bridge and no transfer.
        // Re-attaching lets a transfer that ended while Photos was dead deliver its file.
        if PhotosLocalRuntime.shared.modelDownloadsOffered() {
            ModelDownload.shared.reattach()
            PhotosLocalRuntime.shared.installModelTransferBridge(bridge: ModelDownload.shared)
            // *Index now* continued: iOS 26+ only, and only where a model is offered.
            IndexNowContinuation.install()
        } else if PhotosLocalRuntime.shared.modelDownloadsLeftBehind() {
            // ⚠⚠ This build offers no model and an enabled one downloaded here: its transfers may
            // still be running in the session. Cancelled, and what they delivered deleted.
            // ⚠ A phone that never had the feature creates no session at all.
            ModelDownload.shared.drain()
        }
        #if DEV_HARNESS
        // ⚠ Before any view is built, not in a `.task`: the Library resolves its binding at init and
        // pages immediately, so a repository installed after the first render arrives too late
        // (the contacts-ios / timeline-ios note).
        if PhotosScreenProbe.fixture {
            if PhotosScreenProbe.motion, let clip = PhotosScreenProbe.makeMotionClip() {
                PhotosSurface.shared.installScreenshotFixtureWithMotion(clip: clip)
            } else {
                PhotosSurface.shared.installScreenshotFixture()
            }
        }
        // `-photosSyncCapsule`: the capsule's INPUT is scripted; the rule, the debounce and the copy
        // stay the shipping ones. Before any view exists, as the fixture is.
        if SyncCapsuleProbe.enabled { SyncActivitySurface.shared.installHarness() }
        // `-photosModels <state>`: the Models screens' fixture, before the Library decides its button.
        _ = ModelsProbe.probe
        #endif
    }

    var body: some Scene {
        WindowGroup {
            #if DEV_HARNESS
            if PhotosScreenProbe.fixture {
                PhotosScreenProbe.root
            } else {
                StorageStateRoot { PhotosRootView() }
            }
            #else
            StorageStateRoot { PhotosRootView() }
            #endif
        }
    }
}

#if DEV_HARNESS
/// The `-photosFixture` launch argument (DEV_HARNESS only): the connected shell over
/// `PhotosSurface.installScreenshotFixture()`, skipping the route-1 gate. It proves the *layout*,
/// not the data path — the shipping default is the wall, and a simulator realm has no photographs.
///
/// `-photosCompactWidth` adds a 320 pt, compact-size-class container: an **emulation** of ⅓ Split
/// View for the iPad capture (§8.3 step 6), because entering real Split View needs a gesture `simctl`
/// cannot make. ⚠ Label any screenshot from it as emulated.
/// The open viewer's hooks, for `-photosViewerDemo` (DEV_HARNESS only).
@MainActor
enum ViewerProbe {
    static weak var model: ViewerModel?
    static var trash: (() -> Void)?
    static var dismissRefusal: (() -> Void)?
    static var refusal: (() -> String?)?
}

enum PhotosScreenProbe {
    static var fixture: Bool { ProcessInfo.processInfo.arguments.contains("-photosFixture") }
    static var compactWidth: Bool { ProcessInfo.processInfo.arguments.contains("-photosCompactWidth") }
    /// `-photosMotion` (with `-photosFixture`): every seventh photograph is a video holding a 3 s test
    /// clip, so the grid's motion rotation has something to play (photos-ios.md §8.10).
    static var motion: Bool { ProcessInfo.processInfo.arguments.contains("-photosMotion") }

    /// `-photosTileTone white|black` (with `-photosFixture`): every tile a flat near-white or
    /// near-black, the two backdrops a bar control over photographs has to be read against
    /// (photos-ios.md §8.21, rule 11) — the fixture's own tiles are mid-tones.
    static let tileTone: Color? = {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-photosTileTone"), i + 1 < args.count else { return nil }
        switch args[i + 1] {
        case "white": return Color(white: 0.98)
        case "black": return Color(white: 0.03)
        default: return nil
        }
    }()

    /// A 3 s, 15 fps, 128 px H.264 clip with no audio — a white bar sweeping across a dark tile, so a
    /// screenshot shows which tile is playing and how far into its turn. Encoded here because MP4 is
    /// AVFoundation's job; blocking, but DEV_HARNESS only and ~100 ms.
    static func makeMotionClip() -> Foundation.Data? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("photos-probe-motion.mp4")
        try? FileManager.default.removeItem(at: url)
        guard let writer = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { return nil }
        let side = 128, fps: Int32 = 15, frames = 45
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: side, AVVideoHeightKey: side,
        ])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: side, kCVPixelBufferHeightKey as String: side,
        ])
        writer.add(input)
        guard writer.startWriting() else { return nil }
        writer.startSession(atSourceTime: .zero)
        for frame in 0..<frames {
            while !input.isReadyForMoreMediaData { usleep(1_000) }
            guard let pool = adaptor.pixelBufferPool else { return nil }
            var buffer: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
            guard let buffer else { return nil }
            CVPixelBufferLockBaseAddress(buffer, [])
            let base = CVPixelBufferGetBaseAddress(buffer)!.assumingMemoryBound(to: UInt8.self)
            let stride = CVPixelBufferGetBytesPerRow(buffer)
            let bar = frame * side / frames
            for y in 0..<side {
                for x in 0..<side {
                    let lit = abs(x - bar) < 10
                    let p = base + y * stride + x * 4
                    p[0] = lit ? 255 : 60; p[1] = lit ? 255 : 30; p[2] = lit ? 255 : 30; p[3] = 255
                }
            }
            CVPixelBufferUnlockBaseAddress(buffer, [])
            adaptor.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: fps))
        }
        input.markAsFinished()
        let done = DispatchSemaphore(value: 0)
        writer.finishWriting { done.signal() }
        done.wait()
        defer { try? FileManager.default.removeItem(at: url) }
        return try? Foundation.Data(contentsOf: url)
    }

    /// `-photosDemo <line>` (with `-photosFixture`): the grid's behaviour without taps, which
    /// `simctl` has none of. Scrolls the grid to `<line>` — the sticky header should take over —
    /// then presses the density button, and the re-anchor should keep the same photograph at the
    /// top. Screenshots taken between the steps are the evidence (photos-ios.md §8.6).
    @MainActor
    static func runDemo(scrollTo: @escaping (Int) -> Void, model: LibraryModel) async {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-photosDemo"), i + 1 < args.count, let line = Int(args[i + 1]) else { return }
        try? await Task.sleep(nanoseconds: 3_000_000_000)
        scrollTo(line)
        // Long enough for the pages around the jump to land and re-phase the lines — a screenshot
        // inside that window shows a transient top line and its label.
        try? await Task.sleep(nanoseconds: 8_000_000_000)
        model.cycleDensity()
        try? await Task.sleep(nanoseconds: 5_000_000_000)
        model.cycleDensity()
        // Then what a tile tap does: open the viewer on the photograph on top.
        try? await Task.sleep(nanoseconds: 5_000_000_000)
        model.opener?("/Backup/iPhone/All Photos/IMG_10277.jpg")
    }

    /// `-photosArrivalsDemo <line>` (with `-photosFixture -photosArrivals`): a sync, twice
    /// (photos-ios.md §8.20). At the top, twelve photographs arrive 80 ms apart — the grid must
    /// follow them. Then the grid is scrolled to `<line>` and twelve more arrive — the photograph on
    /// top must not move. `top=` is logged around each burst and the rows configured at the end;
    /// `-photosReloadAll` runs the same script with every layout drawn as a full reload.
    @MainActor
    static func runArrivals(scrollTo: @escaping (Int) -> Void) async {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-photosArrivalsDemo"), i + 1 < args.count, let line = Int(args[i + 1]) else { return }
        func wait(_ s: Double) async { try? await Task.sleep(nanoseconds: UInt64(s * 1_000_000_000)) }
        func log(_ step: String) {
            print("[photos-probe] arrivals \(step): top=\(FeedGridProbe.top?() ?? "-") draws=\(FeedGridProbe.draws) rowsConfigured=\(FeedGridProbe.configuredByDraws)")
        }
        func burst() async {
            for _ in 0..<12 {
                PhotosSurface.shared.fixtureArrive(count: 1)
                await wait(0.08)
            }
        }
        await wait(4)
        let (draws, rows) = (FeedGridProbe.draws, FeedGridProbe.configuredByDraws)
        log("at-top before")
        await burst()
        await wait(3)
        log("at-top after")
        await wait(4)
        scrollTo(line)
        await wait(4)
        log("scrolled before")
        await burst()
        await wait(3)
        log("scrolled after")
        print("[photos-probe] arrivals total: layoutsDrawn=\(FeedGridProbe.draws - draws) rowsConfigured=\(FeedGridProbe.configuredByDraws - rows) mode=\(FeedGridProbe.reloadAll ? "reload-all" : "diff")")
    }

    /// `-photosScrub <p1> <p2>` (with `-photosFixture`): the scrubber held without a finger. Grabs the
    /// thumb, seeks to `p1` of the track and HOLDS — the pills, the bubble and the paused previews are
    /// on screen — then seeks to `p2`, lets go, and the thumb should fade (photos-ios.md §8.8).
    @MainActor
    static func runScrub(model: LibraryModel) async {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-photosScrub"), i + 2 < args.count,
              let first = Double(args[i + 1]), let second = Double(args[i + 2]) else { return }
        try? await Task.sleep(nanoseconds: 3_000_000_000)
        let scrubber = model.scrubber
        scrubber.beginDrag()
        // A press-and-hold: the bubble must be there before any movement.
        print("[photos-probe] scrub grabbed bubble=\(scrubber.bubble ?? "nil")")
        // In steps, as a finger moves — each one a seek.
        for step in 1...10 {
            scrubber.drag(to: CGFloat(first) * CGFloat(step) / 10)
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        try? await Task.sleep(nanoseconds: 6_000_000_000)
        report(scrubber)
        scrubber.drag(to: CGFloat(second))
        try? await Task.sleep(nanoseconds: 6_000_000_000)
        report(scrubber)
        scrubber.endDrag()
        try? await Task.sleep(nanoseconds: 3_000_000_000)
        print("[photos-probe] scrub released rest=\(scrubber.progress) awake=\(scrubber.awake) paused=\(scrubber.previews.paused)")
    }

    @MainActor
    private static func report(_ scrubber: ScrubberState) {
        let years = scrubber.yearMarks.map { "\($0.year):\($0.startIndex)+\($0.count)" }.joined(separator: " ")
        print("[photos-probe] scrub held=\(scrubber.dragProgress) rest=\(scrubber.progress) bubble=\(scrubber.bubble ?? "nil") marks=\(years)")
    }

    /// `-photosSelect` (with `-photosFixture`): the multi-select without a long press, which `simctl`
    /// has none of. Selects #1–#4 through `SelectionState` (what the gesture calls), opens the album
    /// sheet, adds them to "Holiday", then trashes them — the fixture fails every fourth photograph's
    /// trash, so #3 must stay selected and the report must say so (photos-ios.md §8.9).
    @MainActor
    static func runSelect(model: LibraryModel) async {
        guard ProcessInfo.processInfo.arguments.contains("-photosSelect") else { return }
        let selection = model.selection
        try? await Task.sleep(nanoseconds: 3_000_000_000)
        for ordinal in 1...4 {
            if let photo = model.layout.photoOfOrdinal(ordinal: Int32(ordinal)) { selection.toggle(photo) }
        }
        try? await Task.sleep(nanoseconds: 4_000_000_000)
        print("[photos-probe] select paths=\(selection.paths.sorted())")
        selection.pickingAlbum = true
        try? await Task.sleep(nanoseconds: 4_000_000_000)
        selection.pickingAlbum = false
        selection.addToAlbum("fixture-album-1")
        try? await Task.sleep(nanoseconds: 4_000_000_000)
        print("[photos-probe] select album outcome=\(selection.outcome.map { "\($0.title) | \($0.message)" } ?? "nil")")
        selection.outcome = nil
        // The add succeeded for all four, which EMPTIED the selection (successes leave it) — so
        // select them again, as a user would, before the trash.
        for ordinal in 1...4 {
            if let photo = model.layout.photoOfOrdinal(ordinal: Int32(ordinal)) { selection.toggle(photo) }
        }
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        selection.trash()
        try? await Task.sleep(nanoseconds: 4_000_000_000)
        print("[photos-probe] select trash outcome=\(selection.outcome.map { "\($0.title) | \($0.message)" } ?? "nil") still=\(selection.paths.sorted())")
    }

    private static var backDemoStarted = false

    /// `-photosBackDemo` (with `-photosFixture`): open the viewer on #3, then the Back button — and the
    /// main thread must be free afterwards (photos-ios.md §8.11, the return hang).
    @MainActor
    static func startBackDemo(model: LibraryModel) {
        guard ProcessInfo.processInfo.arguments.contains("-photosBackDemo"), !backDemoStarted else { return }
        backDemoStarted = true
        Task {
            func wait(_ s: Double) async { try? await Task.sleep(nanoseconds: UInt64(s * 1_000_000_000)) }
            await wait(3)
            model.opener?("/Backup/iPhone/All Photos/IMG_10003.jpg")
            await wait(3)
            print("[photos-probe] back: closing")
            model.closer?()
            await wait(3)
            print("[photos-probe] back: returned, main thread free")
        }
    }

    /// `-photosViewerDemo` (with `-photosFixture`): the viewer's actions without taps. Opens #3,
    /// stars it, hides and shows the chrome, then trashes it — the fixture REFUSES every fourth trash,
    /// so the viewer must stay open and say so — then moves to #4 and trashes that, which must close
    /// the viewer (photos-ios.md §8.12).
    private static var viewerDemoStarted = false

    @MainActor
    static func startViewerDemo(model: LibraryModel) {
        guard !viewerDemoStarted else { return }
        viewerDemoStarted = true
        Task { await runViewerDemo(model: model) }
    }

    @MainActor
    static func runViewerDemo(model: LibraryModel) async {
        func wait(_ s: Double) async { try? await Task.sleep(nanoseconds: UInt64(s * 1_000_000_000)) }
        guard ProcessInfo.processInfo.arguments.contains("-photosViewerDemo") else { return }
        func report(_ step: String) {
            let state = ViewerProbe.model?.state
            print("[photos-probe] viewer \(step): photo=\(state?.path.split(separator: "_").last ?? "-") favourite=\(state?.favourite?.boolValue.description ?? "nil") canFavourite=\(state?.canFavourite ?? false) chrome=\(state?.chromeVisible ?? true) refusal=\(ViewerProbe.refusal?() ?? "nil")")
        }
        await wait(3)
        model.opener?("/Backup/iPhone/All Photos/IMG_10003.jpg")
        await wait(3); report("opened")
        ViewerProbe.model?.viewModel?.toggleFavourite()
        await wait(2); report("starred")
        ViewerProbe.model?.viewModel?.toggleChrome()
        await wait(3); report("chrome-off")
        ViewerProbe.model?.viewModel?.toggleChrome()
        await wait(1)
        ViewerProbe.trash?()
        await wait(3); report("trash-refused")
        ViewerProbe.dismissRefusal?()
        await wait(1)
        if let viewer = ViewerProbe.model, let four = viewer.source.positionOf(path: "/Backup/iPhone/All Photos/IMG_10004.jpg") {
            viewer.current = four
        }
        await wait(2); report("moved")
        ViewerProbe.trash?()
        await wait(3); report("after-trash")
    }

    @ViewBuilder static var root: some View {
        if compactWidth {
            HStack(spacing: 0) {
                PhotosConnectedRoot()
                    .frame(width: 320)
                    .environment(\.horizontalSizeClass, .compact)
                Color(white: 0.12)
            }
        } else {
            PhotosConnectedRoot()
        }
    }
}
#endif

/// The route-1 gate: **the wall, not the banner** (PIO-D4, ADR 0022 §Decision 4).
///
/// ⚠⚠ A photo library has nothing legible without data. Calendar can keep its grid of dates with no
/// realm; a Photos grid with no realm is an empty grid, and "no photos" for an app that simply
/// cannot see the library tells the user their photographs are gone (`photos-app.md` §10.3).
struct PhotosRootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var phase: Phase = .checking

    enum Phase: Equatable {
        case checking
        case connected
        case absent
    }

    var body: some View {
        Group {
            switch phase {
            case .checking:
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .connected:
                PhotosConnectedRoot()
            case .absent:
                ConnectToControlView(
                    appName: "Photos",
                    purpose: "keep your photos end-to-end encrypted and backed up across your devices",
                    onRecheck: adopt
                )
            }
        }
        .task {
            // ⚠ `onChange` below does not fire for the phase a scene starts in.
            PhotosLocalRuntime.shared.modelTransfersSettleNow(
                inForeground: UIApplication.shared.applicationState != .background
            )
            adopt()
        }
        .onChange(of: scenePhase) { newPhase in
            // ⚠ A model transfer's end is acted on in the foreground only; `adopt` below is what
            // then settles whatever ended meanwhile, from the journal and the disk.
            if newPhase != .inactive { PhotosLocalRuntime.shared.modelTransfersSettleNow(inForeground: newPhase == .active) }
            guard newPhase == .active else { return }
            let wasInstalled = PhotosLocalRuntime.shared.isInstalled()
            adopt()
            if wasInstalled {
                // The inference stamp's foreground check point: a reset can land while Photos is
                // resident and suspended.
                Task.detached(priority: .utility) {
                    PhotosLocalRuntime.shared.checkAtForeground()
                }
            }
        }
    }

    private func adopt() {
        if PhotosLocalRuntime.shared.isProvisioned() {
            PhotosLocalRuntime.shared.install()
            phase = .connected
        } else {
            if PhotosLocalRuntime.shared.hasResidualCustody() {
                // Control tore the provisioning down while we held a copy. Sealing here stops a
                // persisted joiner payload silently resurrecting the adoption (as in drive-ios).
                PhotosLocalRuntime.shared.deprovision()
            }
            phase = .absent
        }
    }
}

/// The library, once connected: the realm's storage banner above it, and the tabs.
///
/// ⚠ A tab exists only WITH its screen (§8.15–§8.19): a tab bar with a dead tab is worse than
/// one honest screen (`photos-app.md` §10.2).
struct PhotosConnectedRoot: View {
    var body: some View {
        VStack(spacing: 0) {
            // ⚠ Above the library, not inside it: the realm's storage state is true of the whole
            // app, and Photos has the strongest claim of any app to say it out loud.
            RealmStorageBanner()
            PhotosTabs()
        }
    }
}

/// The app's tabs (PIO-D3, Q1): Library · Collections — the naming Google Photos and Apple Photos
/// share — and Search. Settings sits behind a toolbar button on Library, as Apple's Photos does.
///
/// ⚠⚠ Through the shared `AdaptiveAppShell` (`ios-shell`), as PIO-D3 says and as Calendar and the
/// others do: a tab bar on phones and narrow iPads, a sidebar from 1100 pt — without
/// rebuilding the subtree on a rotation or Split View resize, so each tab's navigation path survives.
///
/// ⚠ Search is the shell's `search` destination (§8.19), so the OS draws it as its own
/// search affordance: iOS 26's detached round button (`Tab(role: .search)`, what Apple's Photos does),
/// a trailing magnifying-glass tab on iOS 18, and a plain third tab on iOS 16–17.
struct PhotosTabs: View {
    /// Hoisted above the shell, so a placement change cannot take the selection with it (N6).
    @State private var selection = PhotosTabs.initialTab

    /// `-photosStartTab collections|search` (DEV_HARNESS): open on that tab — `simctl` cannot tap one.
    private static var initialTab: String {
        #if DEV_HARNESS
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-photosStartTab"), i + 1 < args.count,
           ["collections", "search"].contains(args[i + 1]) {
            return args[i + 1]
        }
        #endif
        return "library"
    }

    var body: some View {
        AdaptiveAppShell(
            selection: $selection,
            destinations: [
                AppDestination(id: "library", title: "Library", symbol: "photo.on.rectangle") {
                    NavigationStack { LibraryScreen() }
                },
                AppDestination(id: "collections", title: "Collections", symbol: "rectangle.stack",
                               filledSymbol: "rectangle.stack.fill") {
                    NavigationStack { CollectionsScreen() }
                },
            ],
            search: AppDestination(id: "search", title: "Search", symbol: "magnifyingglass") {
                NavigationStack { SearchScreen() }
            }
        )
        #if DEV_HARNESS
        .task { await TabSwitchProbe.run(select: { selection = $0 }) }
        .overlay(alignment: .topLeading) { TabSwitchProbe.Readout() }
        .overlay(alignment: .bottomTrailing) { ModelsProbe.Readout() }
        // ⚠ Bottom-leading, above the tab bar: clear of every bar's buttons, and of the head of each
        // screen, where the things a UI test taps are.
        .overlay(alignment: .bottomLeading) { ReadsOnMainProbe.Readout().padding(.bottom, 96) }
        #endif
    }
}
