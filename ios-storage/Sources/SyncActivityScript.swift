#if DEBUG
import SwiftUI
import TenshuSdk

/// `-syncCapsuleScript <name>` (debug builds only): the sync capsule without a relay, in any app
/// that mounts it (`ios-sync-activity-indicator.md` §6a.2). A simulator has no seeded relay and
/// `simctl` has no taps, so the capsule's INPUT is scripted through `SyncActivitySurface`'s
/// harness. What a script cannot fake is the rule, the debounce or the copy: those are the
/// shipping ones.
///
/// | name | does |
/// |---|---|
/// | `pull-hold` | a pull that never ends — for a screenshot of "Syncing…" |
/// | `upload-hold` | a run at 124 of 310 — "Uploading 124 of 310" |
/// | `tail-hold` | no run, two transfers in flight — "Uploading…" |
/// | `upload` | a run, its tail, a pull that outlasts it, then idle; records the line at each step |
/// | `lifetime` | adds and removes a second storage banner; records the Kotlin collector counts |
///
/// `-syncCapsuleIgnoreHostProblem` makes every slot treat `hostProblem` as `false`, so the capsule
/// can be looked at in an app whose only reachable state on a bare simulator is "not connected".
/// ⚠ A screenshot taken with it shows where the capsule sits, and nothing about precedence.
@MainActor
final class SyncActivityScript: ObservableObject {
    static let shared = SyncActivityScript()

    static var requested: String? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-syncCapsuleScript"), i + 1 < args.count else { return nil }
        return args[i + 1]
    }

    static var ignoresHostProblem: Bool {
        ProcessInfo.processInfo.arguments.contains("-syncCapsuleIgnoreHostProblem")
    }

    /// The step the script is on, and every step so far as `name=value` joined by `|`. The record
    /// is read whole by an XCUITest, which cannot be relied on to catch a step while it is current.
    @Published private(set) var step = "idle"
    @Published private(set) var record = ""
    /// `lifetime`: a second, invisible storage banner the script mounts and removes.
    @Published private(set) var extraBanner = false

    /// The line a visible slot is drawing, written by `Readout`. ⚠ Not published: a slot writes it
    /// from its own update, and publishing it would re-render the slot that wrote it.
    var line: String?

    private var started = false

    /// Called by `SyncActivityModel.observe()` before it asks for the flows: the harness replaces
    /// the surface's input at call time, so it has to be in place first.
    static func startIfRequested() {
        guard let name = requested, !shared.started else { return }
        shared.started = true
        SyncActivitySurface.shared.installHarness()
        Task { await shared.run(name) }
    }

    private func wait(_ seconds: Double) async {
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    private func mark(_ name: String, _ value: String) {
        record += (record.isEmpty ? "" : "|") + "\(name)=\(value)"
        step = name
        print("[sync-script] \(name)=\(value)")
    }

    private func markLine(_ name: String) { mark(name, line ?? "-") }

    private func run(_ name: String) async {
        let surface = SyncActivitySurface.shared
        switch name {
        case "pull-hold":
            surface.harnessSetCatchingUp(catchingUp: true)
            step = "holding"
        case "upload-hold":
            surface.harnessSetUpload(completed: 124, total: 310, transfersInFlight: 3)
            step = "holding"
        case "tail-hold":
            surface.harnessSetUpload(completed: 0, total: 0, transfersInFlight: 2)
            step = "holding"
        case "upload":
            await wait(3)
            markLine("idle")
            surface.harnessSetUpload(completed: 1, total: 3, transfersInFlight: 1)
            await wait(3)
            markLine("run-1")
            // A pull starts under the run: uploading still wins, and keeps its count.
            surface.harnessSetCatchingUp(catchingUp: true)
            surface.harnessSetUpload(completed: 2, total: 3, transfersInFlight: 2)
            await wait(2.5)
            markLine("run-2-with-pull")
            // The run is done; its chunks are still on their way.
            surface.harnessSetUpload(completed: 0, total: 0, transfersInFlight: 2)
            await wait(2.5)
            markLine("tail")
            await wait(3)                       // long enough for an XCUITest to look
            // The last chunk landed and the pull is still running: straight to it, never idle.
            surface.harnessSetUpload(completed: 0, total: 0, transfersInFlight: 0)
            await wait(0.4)
            markLine("handover-at-once")
            await wait(2.5)
            markLine("pull-after-upload")
            surface.harnessSetCatchingUp(catchingUp: false)
            await wait(4)
            markLine("idle-after")
            step = "done"
        case "lifetime":
            func collectors() -> String {
                "storage:\(RealmStorageSurface.shared.debugCollectors()),activity:\(SyncActivitySurface.shared.harnessCollectors())"
            }
            await wait(4)
            mark("mounted", collectors())
            extraBanner = true
            await wait(2)
            mark("banner-added", collectors())
            extraBanner = false
            await wait(2)
            mark("banner-removed", collectors())
            step = "done"
        default:
            step = "unknown-script"
        }
    }

    /// The script's state as accessibility values, behind a visible slot, and the place the slot
    /// reports its line. Invisible, one point, and not part of any layout that matters: it sits in
    /// the slot's background.
    struct Readout: View {
        let line: String?
        @ObservedObject private var script = SyncActivityScript.shared

        var body: some View {
            if SyncActivityScript.requested != nil {
                VStack(spacing: 0) {
                    probe("sync-script-step", script.step)
                    probe("sync-script-record", script.record)
                    if script.extraBanner {
                        RealmStorageBanner().frame(width: 1, height: 1).opacity(0)
                    }
                }
                .allowsHitTesting(false)
                .onAppear { script.line = line }
                .onChange(of: line) { script.line = $0 }
            }
        }

        private func probe(_ id: String, _ value: String) -> some View {
            Color.clear.frame(width: 1, height: 1)
                .accessibilityElement()
                .accessibilityIdentifier(id)
                .accessibilityValue(value)
        }
    }
}
#endif
