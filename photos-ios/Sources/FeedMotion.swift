import AVFoundation
import SwiftUI
import TenshuSdk
import UIKit

// The grid's motion tiles (photos-ios.md §8.10) — `photos-video-support.md` §5 / M5.
//
// ⚠⚠ Swift decides nothing (PIO-D2). WHICH tile plays, in what order, and whose clip is fetched are
// `MotionRotation`'s — one player for the whole feed, a visible video tile played through once, then
// the next in document order, wrapping; only a tile whose clip is already in hand is ever handed the
// turn. This file reports which video tiles are on screen, pauses the rotation when nobody can see
// it, and plays one turn.
//
// ⚠ "Play videos in the grid" is a DEVICE setting, on by default (`PhotosPreferences.PlayVideosInGrid`).
// iOS's Settings has no such switch, so the rotation keeps its default — on — here.

/// The rotation's current turn, mirrored for the tiles — its own object, so a hand-over re-renders
/// tiles, never the screen.
@MainActor
final class MotionState: ObservableObject {
    @Published private(set) var turn: MotionTurn?

    private let rotation: MotionRotation
    private var reported: [String]?
    private var appeared = false
    private var active = true

    init(rotation: MotionRotation) {
        self.rotation = rotation
    }

    func observe() async {
        for await value in rotation.turn {
            turn = value
            #if DEV_HARNESS
            // The probe's evidence (`-photosMotion`): which tile holds the turn, and which turn it is.
            let index = value?.path.split(separator: "_").last.flatMap { Int($0.prefix { $0.isNumber }) }.map { $0 - 10_000 }
            print("[photos-probe] motion turn=\(index.map { "#\($0)" } ?? "none") serial=\(value?.serial ?? 0)")
            #endif
        }
    }

    /// The video tiles on screen, in document order. ⚠ Deduplicated here: the grid reports on every
    /// scroll frame, and the rotation's input is a set that changes once a row.
    func setVisible(_ paths: [String]) {
        guard paths != reported else { return }
        reported = paths
        rotation.setVisible(paths: paths)
        #if DEV_HARNESS
        let indices = paths.compactMap { $0.split(separator: "_").last.flatMap { Int($0.prefix { $0.isNumber }) } }.map { "#\($0 - 10_000)" }
        print("[photos-probe] motion visible=\(indices.joined(separator: ","))")
        #endif
    }

    /// The tile played its turn through — the rotation's clock.
    func finished(_ turn: MotionTurn) {
        rotation.turnFinished(path: turn.path)
    }

    /// The Library is on screen (not behind the viewer, not gone).
    func setAppeared(_ value: Bool) {
        appeared = value
        updatePaused()
    }

    /// The scene is active. ⚠ A backgrounded app decoding video is a battery bug (Compose's Q1).
    func setSceneActive(_ value: Bool) {
        active = value
        updatePaused()
    }

    private func updatePaused() {
        rotation.setPaused(value: !(appeared && active))
    }
}

/// The player's place on a VIDEO tile: the turn's player while this tile holds it, else nothing —
/// the poster underneath shows. ⚠ Only video tiles carry one, so a hand-over re-renders them alone.
struct MotionSlot: View {
    let path: String
    @ObservedObject var state: MotionState

    var body: some View {
        if let turn = state.turn, turn.path == path {
            MotionTilePlayer(turn: turn) { state.finished(turn) }
                .id(turn.serial)
                .allowsHitTesting(false)
        }
    }
}

/// One turn's player: muted, no controls, no chrome, played through ONCE (`MotionTilePlayer`'s
/// contract, `photos-video-support.md` §6 / D8).
///
/// ⚠⚠ [onFinished] is the rotation's clock, so it fires EXACTLY ONCE PER TURN, AND ALWAYS — after a
/// clip that played, after one that failed to open or decode (held for a nominal turn first, so a
/// device where every clip fails degrades to a still grid instead of spinning), and at a ceiling if
/// the player goes silent. A player that stays quiet stalls every other video on screen.
/// ⚠ Keyed on the turn by the caller (`.id(turn.serial)`): a new turn — the one-video case, where
/// "next" is itself — is a fresh player from frame one.
struct MotionTilePlayer: UIViewRepresentable {
    let turn: MotionTurn
    let onFinished: () -> Void

    func makeUIView(context: Context) -> MotionPlayerView {
        let view = MotionPlayerView()
        view.start(clip: PhotosSurface.shared.motionClipData(turn: turn), onFinished: onFinished)
        return view
    }

    func updateUIView(_ view: MotionPlayerView, context: Context) {}

    /// ⚠ Stops without reporting: a player torn down because the tile left the screen is not a turn
    /// that finished — the rotation hears that through the visible set instead.
    static func dismantleUIView(_ view: MotionPlayerView, coordinator: ()) {
        view.stop()
    }
}

final class MotionPlayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    private var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }

    private var player: AVPlayer?
    /// ⚠ Held here: `AVAssetResourceLoader` keeps its delegate weakly.
    private var loader: MemoryClipLoader?
    private var observers: [NSObjectProtocol] = []
    private var status: NSKeyValueObservation?
    private var timers: [DispatchWorkItem] = []
    private var onFinished: (() -> Void)?
    private var done = false

    /// A nominal clip's length — how long a failed turn holds the slot (`MOTION_FAILED_TURN_MS`).
    private static let failedTurn: TimeInterval = 3
    /// The ceiling on any turn whose player reports neither end nor error (`MOTION_TURN_CEILING_MS`).
    private static let ceiling: TimeInterval = 10

    func start(clip: Foundation.Data, onFinished: @escaping () -> Void) {
        self.onFinished = onFinished
        playerLayer.videoGravity = .resizeAspectFill
        isUserInteractionEnabled = false
        after(Self.ceiling) { [weak self] in self?.finish("ceiling") }

        // ⚠⚠ From MEMORY, never a file: a clip is a decrypted derivative of the user's video, and a
        // temporary file outlives a crash or a kill — nothing runs to delete it, so plaintext would
        // accumulate on disk against the threat model's local-minimisation rule (I8).
        // A custom scheme sends AVFoundation to `MemoryClipLoader` for the bytes.
        let loader = MemoryClipLoader(clip: clip)
        self.loader = loader
        let asset = AVURLAsset(url: URL(string: "\(MemoryClipLoader.scheme)://turn.mp4")!)
        asset.resourceLoader.setDelegate(loader, queue: MemoryClipLoader.queue)
        let item = AVPlayerItem(asset: asset)
        let player = AVPlayer(playerItem: item)
        player.isMuted = true
        player.actionAtItemEnd = .pause
        // ⚠ A grid tile moving is not the user watching a video: it must not keep the screen awake.
        player.preventsDisplaySleepDuringVideoPlayback = false
        playerLayer.player = player
        self.player = player

        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            self?.finish("end")
        })
        observers.append(center.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            self?.failLater()
        })
        status = item.observe(\.status) { [weak self] item, _ in
            guard item.status == .failed else { return }
            DispatchQueue.main.async { self?.failLater() }
        }
        player.play()
    }

    func stop() {
        onFinished = nil
        teardown()
    }

    private func failLater() {
        after(Self.failedTurn) { [weak self] in self?.finish("failed") }
    }

    private func finish(_ why: String) {
        guard !done else { return }
        done = true
        #if DEV_HARNESS
        print("[photos-probe] motion finished why=\(why)")
        #endif
        let report = onFinished
        onFinished = nil
        teardown()
        report?()
    }

    private func after(_ seconds: TimeInterval, _ work: @escaping () -> Void) {
        let item = DispatchWorkItem(block: work)
        timers.append(item)
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: item)
    }

    private func teardown() {
        timers.forEach { $0.cancel() }
        timers.removeAll()
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        status?.invalidate()
        status = nil
        player?.pause()
        player = nil
        loader = nil
    }
}

/// Serves one turn's clip to AVFoundation from memory, byte ranges and all.
final class MemoryClipLoader: NSObject, AVAssetResourceLoaderDelegate {
    static let scheme = "tenshu-motion"
    static let queue = DispatchQueue(label: "app.tenshu.photos.motion-loader")

    private let clip: Foundation.Data

    init(clip: Foundation.Data) {
        self.clip = clip
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource request: AVAssetResourceLoadingRequest
    ) -> Bool {
        if let info = request.contentInformationRequest {
            info.contentType = AVFileType.mp4.rawValue
            info.contentLength = Int64(clip.count)
            info.isByteRangeAccessSupported = true
        }
        if let data = request.dataRequest {
            let start = Int(data.requestedOffset)
            let end = data.requestsAllDataToEndOfResource
                ? clip.count
                : min(start + data.requestedLength, clip.count)
            if start < end { data.respond(with: clip.subdata(in: start..<end)) }
        }
        request.finishLoading()
        return true
    }
}
