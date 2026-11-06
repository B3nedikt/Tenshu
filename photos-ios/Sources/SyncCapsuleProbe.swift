#if DEV_HARNESS
import SwiftUI
import TenshuSdk

/// `-photosSyncCapsule` (with `-photosFixture`, DEV_HARNESS only): the sync capsule without a relay
/// (`ios-sync-activity-indicator.md` §6). The simulator has no seeded relay and `simctl` has no
/// taps, so the capsule's INPUT is scripted through `SyncActivitySurface`'s harness — what the
/// script cannot fake is the rule, the debounce or the copy, which are the shipping ones.
///
/// The script: on · off · on, then the host reports a problem · on, then the flow ends. At every
/// step it records the grid's frame, `adjustedContentInset` and content offset; they must be the
/// same string with the capsule shown and hidden, and the last line says whether they were.
///
/// `-photosArrivals` may be added: photographs then arrive while the capsule is up, which is when
/// the grid is busiest and the comparison is run at the top, where the grid follows new arrivals.
@MainActor
final class SyncCapsuleProbe: ObservableObject {
    static let shared = SyncCapsuleProbe()
    static var enabled: Bool { ProcessInfo.processInfo.arguments.contains("-photosSyncCapsule") }

    /// The scripted "this root is showing a problem bar".
    @Published var hostProblem = false
    /// The step the script is on, and the geometry it last read — for the XCUITest to wait on.
    @Published private(set) var step = "idle"
    @Published private(set) var geometry = "-"

    /// Set by the Library: scroll the grid to a line.
    var scroll: ((Int) -> Void)?

    private var started = false
    private var samples: [(step: String, visible: Bool, geometry: String)] = []
    /// Every step so far as `name:visible:geometry`, joined by `|` — read whole by the XCUITest,
    /// which cannot be relied on to catch a step while it is the current one.
    @Published private(set) var record = ""

    /// `-photosSyncCapsuleLifetime`: a second, hidden mount the lifetime script adds and removes
    /// while the scene stays active.
    @Published private(set) var extraMount = false
    static var lifetime: Bool { ProcessInfo.processInfo.arguments.contains("-photosSyncCapsuleLifetime") }
    private var activations = 0

    /// `-photosSyncCapsuleLifetime`: who is still collecting the Kotlin flows.
    ///
    /// One live observation holds two collectors of the scripted input (line and label). The script
    /// records the count with the Library's mount alone, with a second mount added and then
    /// REMOVED while the scene stays active, after twenty observations of one model each cancelled
    /// by the next, and after that last one is cancelled. Then it publishes the live count while
    /// the XCUITest sends the app away and brings it back.
    private func runLifetime() async {
        func wait(_ s: Double) async { try? await Task.sleep(nanoseconds: UInt64(s * 1_000_000_000)) }
        func mark(_ name: String) {
            let collectors = Int(SyncActivitySurface.shared.harnessCollectors())
            samples.append((name, false, "\(collectors)"))
            record = samples.map { "\($0.step):\($0.visible):\($0.geometry)" }.joined(separator: "|")
            step = name
            print("[photos-probe] capsule lifetime \(name): collectors=\(collectors)")
        }
        await wait(4)
        mark("mounted")

        extraMount = true
        await wait(2)
        mark("second-mount")
        extraMount = false
        await wait(2)
        mark("second-mount-removed")

        let model = SyncActivityModel()
        var observation: Task<Void, Never>?
        for _ in 0..<20 {
            observation?.cancel()
            observation = Task { await model.observe() }
            await Task.yield()
        }
        await wait(2)
        mark("restarted-twenty-times")
        observation?.cancel()
        await wait(2)
        mark("last-restart-cancelled")

        let token = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in Task { @MainActor in self?.activations += 1 } }
        _ = token
        step = "lifetime-ready"
        // From here the XCUITest sends the app away and brings it back, and reads the count live.
        while !Task.isCancelled {
            let now = "collectors=\(SyncActivitySurface.shared.harnessCollectors()) activations=\(activations)"
            if now != live { live = now }
            await wait(0.3)
        }
    }

    /// `collectors=<n> activations=<n>`, refreshed while the lifetime script idles at its end.
    @Published private(set) var live = "-"

    func run() async {
        guard Self.enabled, !started else { return }
        started = true
        if Self.lifetime { return await runLifetime() }
        func wait(_ s: Double) async { try? await Task.sleep(nanoseconds: UInt64(s * 1_000_000_000)) }
        func mark(_ name: String) {
            geometry = FeedGridProbe.geometry?() ?? "-"
            let visible = SyncActivityCapsuleProbe.visible
            samples.append((name, visible, geometry))
            record = samples.map { "\($0.step):\($0.visible):\($0.geometry)" }.joined(separator: "|")
            step = name
            print("[photos-probe] capsule \(name): visible=\(visible) \(geometry) draws=\(FeedGridProbe.draws)")
        }
        let surface = SyncActivitySurface.shared

        await wait(4)
        // `-photosSyncCapsuleScroll <line>`: run the script off the top, where the grid keeps a
        // photograph in place instead of following arrivals. Long enough for the pages around the
        // jump to land before the first sample.
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-photosSyncCapsuleScroll"), i + 1 < args.count, let line = Int(args[i + 1]) {
            scroll?(line)
            await wait(8)
        }
        mark("hidden-before")

        surface.harnessSetCatchingUp(catchingUp: true)
        await wait(0.4)
        mark("on-inside-show-delay")           // 700 ms have not passed: nothing yet
        await wait(2.6)
        mark("shown")
        await wait(4)                          // long enough for a screenshot, or an XCUITest query

        surface.harnessSetCatchingUp(catchingUp: false)
        await wait(3)
        mark("hidden-after-off")

        surface.harnessSetCatchingUp(catchingUp: true)
        await wait(3)
        mark("shown-again")
        hostProblem = true                     // a problem bar has text: it shows alone
        await wait(2)
        mark("hidden-by-host-problem")
        hostProblem = false
        await wait(2)
        mark("shown-after-problem")

        surface.harnessEndFlow()               // the runtime went away: nothing may be held
        await wait(2)
        mark("hidden-flow-ended")

        let distinct = Set(samples.map(\.geometry))
        print("[photos-probe] capsule geometry identical=\(distinct.count == 1) samples=\(samples.count) distinct=\(distinct.count)")
        step = "done identical=\(distinct.count == 1)"
    }

    /// `-photosSyncRowProbe` (a REAL runtime, no `-photosFixture`): does writing this app's status
    /// row re-run the feed? Pulses the real row 20 times and logs the grid's draw counter and the
    /// layouts the binding emitted, before and after (`ios-sync-activity-indicator.md` §8).
    private var rowProbeStarted = false

    func runRowProbe(layoutsSeen: @escaping () -> Int) async {
        guard ProcessInfo.processInfo.arguments.contains("-photosSyncRowProbe"), !rowProbeStarted else { return }
        rowProbeStarted = true
        func wait(_ s: Double) async { try? await Task.sleep(nanoseconds: UInt64(s * 1_000_000_000)) }
        await wait(10)
        let (draws, layouts) = (FeedGridProbe.draws, layoutsSeen())
        print("[photos-probe] row-probe before: draws=\(draws) layouts=\(layouts)")
        let pulses = (try? await SyncActivitySurface.shared.debugPulseRow(times: 20, gapMs: 150))?.intValue ?? -1
        await wait(3)
        print("[photos-probe] row-probe after: pulses=\(pulses) rowWrites=\(pulses * 2) drawsDuring=\(FeedGridProbe.draws - draws) layoutsDuring=\(layoutsSeen() - layouts)")
        // The control: the same wait with no writes, so background layouts are not blamed on the row.
        let (idleDraws, idleLayouts) = (FeedGridProbe.draws, layoutsSeen())
        await wait(9)
        print("[photos-probe] row-probe control: drawsDuring=\(FeedGridProbe.draws - idleDraws) layoutsDuring=\(layoutsSeen() - idleLayouts)")
    }

    /// A one-point, invisible element carrying the step and the geometry as accessibility values.
    /// ⚠ An overlay, so it is not itself a reason for the grid to move.
    struct Readout: View {
        @ObservedObject private var probe = SyncCapsuleProbe.shared

        var body: some View {
            if SyncCapsuleProbe.enabled {
                VStack(spacing: 0) {
                    Color.clear.frame(width: 1, height: 1)
                        .accessibilityElement()
                        .accessibilityIdentifier("sync-capsule-step")
                        .accessibilityValue(probe.step)
                    Color.clear.frame(width: 1, height: 1)
                        .accessibilityElement()
                        .accessibilityIdentifier("sync-capsule-record")
                        .accessibilityValue(probe.record)
                    Color.clear.frame(width: 1, height: 1)
                        .accessibilityElement()
                        .accessibilityIdentifier("sync-capsule-live")
                        .accessibilityValue(probe.live)
                    if probe.extraMount {
                        Color.clear.frame(width: 1, height: 1)
                            .syncActivityCapsule(hostProblem: false, showsStorageBar: false, hidden: true)
                    }
                }
                .allowsHitTesting(false)
            }
        }
    }
}
#endif
