#if DEV_HARNESS
import QuartzCore
import SwiftUI
import TenshuSdk

/// `-photosTabSwitch <n>` (with `-photosFixture`, DEV_HARNESS only): Library → Collections → Library,
/// `n` times, through the tab selection the bar writes — and what each switch cost the main thread
/// (photos-ios.md §8.22). `-photosTabSwitchScroll <line>` scrolls the Library there first, so
/// photographs lie under its navigation bar; `-photosLibrarySize <n>` sets the library's length.
/// ⚠ With `-photosArrivals`, or the feed is one static list and the Library's own cost is the mock's.
/// ⚠⚠ And with `-photosHeldReads <ms>`, or Collections' reads cost nothing wherever they run.
/// ⚠⚠ Each return to Collections follows an ARRIVAL, so it is a return that walks the library.
///
/// ⚠ The number to read is `cpu`, the main thread's own CPU time: it does not move with the load on
/// the machine. `stall` (frames the display link missed) and the wall clock do.
@MainActor
enum TabSwitchProbe {
    /// The finished script's medians, for `TabSwitchUITests`: `collections=<ms> library=<ms>`.
    final class Result: ObservableObject {
        static let shared = Result()
        @Published var value = ""
    }

    struct Readout: View {
        @ObservedObject private var result = Result.shared

        var body: some View {
            if ProcessInfo.processInfo.arguments.contains("-photosTabSwitch") {
                Color.clear.frame(width: 1, height: 1)
                    .accessibilityElement()
                    .accessibilityIdentifier("tab-switch-result")
                    .accessibilityValue(result.value)
            }
        }
    }

    static func run(select: @escaping (String) -> Void) async {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-photosTabSwitch"), i + 1 < args.count, let rounds = Int(args[i + 1]) else { return }
        func wait(_ seconds: Double) async { try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)) }
        await wait(5)
        if let s = args.firstIndex(of: "-photosTabSwitchScroll"), s + 1 < args.count, let line = Int(args[s + 1]) {
            SyncCapsuleProbe.shared.scroll?(line)
            await wait(4)
        }
        let watch = MainThreadWatch()
        var cost: [String: [MainThreadWatch.Cost]] = [:]
        for round in 0..<rounds {
            for tab in ["collections", "library"] {
                watch.begin()
                select(tab)
                await wait(1.5)
                let spent = watch.end()
                cost[tab, default: []].append(spent)
                print("[photos-probe] tab switch \(round) to=\(tab) \(spent)")
                // ⚠⚠ A photograph arrives before each return to Collections (§8.22.3): with nothing
                // changed the tab no longer walks the library at all, and this probe would measure a
                // return that reads nothing — green whatever thread a walk runs on.
                if tab == "library" {
                    PhotosSurface.shared.fixtureArrive(count: 1)
                    await wait(0.7)
                }
            }
        }
        var medians: [String] = []
        for tab in ["collections", "library"] {
            let all = cost[tab] ?? []
            // ⚠ The first round builds Collections; the median is the switch itself.
            let cpu = all.map(\.cpuMs).sorted()
            let median = cpu.isEmpty ? 0 : cpu[cpu.count / 2]
            let worst = all.map(\.worstFrameMs).max() ?? 0
            print("[photos-probe] tab switch total to=\(tab) rounds=\(all.count) cpuMedian=\(median)ms cpuMax=\(cpu.last ?? 0)ms worstFrame=\(worst)ms")
            medians.append("\(tab)=\(median)")
        }
        print("[photos-probe] tab switch done")
        Result.shared.value = medians.joined(separator: " ")
    }
}

/// What the main thread spent between `begin` and `end`: its own CPU time, and the frames it missed.
@MainActor
final class MainThreadWatch: NSObject {
    struct Cost: CustomStringConvertible {
        let cpuMs: Int
        let stallMs: Int
        let worstFrameMs: Int
        var description: String { "cpu=\(cpuMs)ms stall=\(stallMs)ms worstFrame=\(worstFrameMs)ms" }
    }

    private var link: CADisplayLink?
    private var last: CFTimeInterval = 0
    private var stall: CFTimeInterval = 0
    private var worst: CFTimeInterval = 0
    private var cpuAtBegin: Double = 0

    private static var mainCpuSeconds: Double {
        var time = timespec()
        clock_gettime(CLOCK_THREAD_CPUTIME_ID, &time)
        return Double(time.tv_sec) + Double(time.tv_nsec) / 1e9
    }

    func begin() {
        link?.invalidate()
        last = CACurrentMediaTime()
        stall = 0
        worst = 0
        cpuAtBegin = Self.mainCpuSeconds
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    @objc private func tick() {
        let now = CACurrentMediaTime()
        let gap = now - last
        last = now
        worst = max(worst, gap)
        // Two frames at 60 Hz: anything longer is a frame the main thread did not make.
        if gap > 0.034 { stall += gap }
    }

    func end() -> Cost {
        link?.invalidate()
        link = nil
        return Cost(cpuMs: Int((Self.mainCpuSeconds - cpuAtBegin) * 1000), stallMs: Int(stall * 1000),
                    worstFrameMs: Int(worst * 1000))
    }
}
#endif
