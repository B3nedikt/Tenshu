import SwiftUI
import TenshuSdk

/// The sync-activity capsule (`ios-sync-activity-indicator.md` §4): one small line, bottom-centre,
/// while this app is pulling data in; nothing when idle.
///
/// ⚠ Compiled INTO each app target via `ios-storage/storage.yml`, beside `RealmStorageBanner`.
///
/// Swift decides nothing about *when*: the rule, the debounce and the copy are Kotlin's
/// (`SyncActivitySurface`). What Swift owns is the one precedence rule that needs facts only a
/// Swift root has — a problem bar on screen hides the capsule.
struct SyncActivityCapsule: View {
    let line: String
    let accessibilityLabel: String

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 6) {
            icon
            Text(line)
                .font(.footnote.weight(.medium))
                // ⚠ One line at every Dynamic Type size: the capsule is an overlay, and a wrapped
                // one would cover a second row of photographs.
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(.regularMaterial, in: Capsule())
        .overlay(Capsule().strokeBorder(Color.primary.opacity(0.08)))
        .shadow(color: .black.opacity(0.12), radius: 6, y: 2)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(.updatesFrequently)
        .accessibilityIdentifier("sync-activity-capsule")
        #if DEV_HARNESS
        .onAppear { SyncActivityCapsuleProbe.visible = true }
        .onDisappear { SyncActivityCapsuleProbe.visible = false }
        #endif
    }

    @ViewBuilder
    private var icon: some View {
        if reduceMotion {
            // A static icon under Reduce Motion.
            Image(systemName: "arrow.triangle.2.circlepath")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)
        } else {
            ProgressView()
                .controlSize(.small)
        }
    }
}

#if DEV_HARNESS
/// Whether a capsule is in the view tree — for a host's scripted probe, which cannot see the mount.
@MainActor
enum SyncActivityCapsuleProbe {
    static var visible = false
}
#endif

/// Mirrors the two Kotlin flows, plus the storage bar's text for the precedence rule.
///
/// ⚠⚠ It never holds a last value. `line` is `nil` when the Kotlin loop ends and when [observe]
/// returns; coming back re-reads from the flow.
///
/// ⚠⚠ It holds no task either. [observe] IS the observation: it returns when the task that called
/// it is cancelled, and every collector is a child of that task. A stored `Task` handle was the
/// earlier shape and it raced twice — a cancelled run cleared the handle of the run that replaced
/// it, and a mount removed while the scene stayed active cancelled nothing at all.
@MainActor
final class SyncActivityModel: ObservableObject {
    @Published private(set) var line: String?
    @Published private(set) var accessibilityLabel: String?
    /// `RealmStorageBanner` has text. Read here so the capsule yields without the host passing it.
    @Published private(set) var storageBarShown = false

    /// Which call of [observe] owns the published values. A cancelled call unwinds some time after
    /// its replacement has started, and must neither publish nor clear over it.
    private var run = 0

    /// Follow the flows until the calling task is cancelled. Publishes nothing once it returns.
    func observe() async {
        #if DEBUG
        // Before the flows are asked for: a scripted input replaces them at call time.
        SyncActivityScript.startIfRequested()
        #endif
        run += 1
        let mine = run
        clear()
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                for await text in RealmStorageSurface.shared.observeBannerText() {
                    guard self.run == mine else { return }
                    self.storageBarShown = text != nil
                }
            }
            group.addTask { @MainActor in
                await self.followActivity(mine)
            }
        }
        if run == mine { clear() }
    }

    private func followActivity(_ mine: Int) async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                for await line in SyncActivitySurface.shared.observeLine() {
                    guard self.run == mine else { return }
                    self.line = line
                }
            }
            group.addTask { @MainActor in
                for await label in SyncActivitySurface.shared.observeAccessibilityLabel() {
                    guard self.run == mine else { return }
                    self.accessibilityLabel = label
                }
            }
            // ⚠ The FIRST loop to end ends both: a flow that finished can report nothing more,
            // and the other half must not keep a line on screen.
            await group.next()
            group.cancelAll()
        }
        if run == mine {
            line = nil
            accessibilityLabel = nil
        }
    }

    private func clear() {
        line = nil
        accessibilityLabel = nil
        storageBarShown = false
    }
}

/// The drawing half: the capsule, or nothing, from a model someone else is observing.
///
/// Put it in a bottom-aligned overlay. A root with one `NavigationStack` uses
/// `.syncActivityCapsule(...)`, which does that and the observing; a tab-shaped app hands one of
/// these to `AdaptiveAppShell(destinationOverlay:)` and observes once at its root
/// (`.syncActivityObservation`), so every tab draws the same capsule from ONE observation
/// (`ios-sync-activity-indicator.md` §6a.2).
///
/// ⚠⚠ None of the three facts has a default, on purpose. A centrally installed surface cannot see
/// what a Swift root decided to draw, so each root states it:
/// - `hostProblem`: the expression that decides this root's own problem bar or problem screen.
/// - `showsStorageBar`: whether this host mounts `RealmStorageBanner`. `false` makes the capsule
///   ignore the storage state, since nothing here would be showing it.
/// - `hidden`: the host's own reason to hide it, e.g. a selection bar is up.
struct SyncActivityCapsuleSlot: View {
    @ObservedObject var model: SyncActivityModel
    let hostProblem: Bool
    let showsStorageBar: Bool
    let hidden: Bool

    @Environment(\.scenePhase) private var scenePhase

    /// One thing at a time (ADR 0025 Decision 1c): a problem bar with text shows alone.
    /// ⚠ The storage half counts only in a host that RENDERS that bar — otherwise the host would
    /// show neither.
    private var shown: String? {
        // Not active: nothing, at once. The cancelled observation clears the model a moment later.
        if scenePhase != .active { return nil }
        #if DEBUG
        if hidden || (hostProblem && !SyncActivityScript.ignoresHostProblem) { return nil }
        #else
        if hidden || hostProblem { return nil }
        #endif
        if showsStorageBar && model.storageBarShown { return nil }
        return model.line
    }

    var body: some View {
        ZStack {
            if let shown {
                SyncActivityCapsule(line: shown, accessibilityLabel: model.accessibilityLabel ?? shown)
                    .padding(.bottom, 12)
                    .transition(.opacity)
            }
            #if DEBUG
            // Renders nothing unless a script was asked for on the command line.
            SyncActivityScript.Readout(line: shown)
            #endif
        }
        .animation(.easeInOut(duration: 0.25), value: shown)
        // Decoration: a touch that lands on it belongs to the content underneath.
        .allowsHitTesting(false)
    }
}

/// The observing half: follows the Kotlin flows into `model` for as long as the view is mounted
/// and the scene is active.
private struct SyncActivityObservation: ViewModifier {
    let model: SyncActivityModel
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content
            // ⚠⚠ The task's body AWAITS the observation. SwiftUI cancels it when the mount goes
            // away and when the scene stops or starts being active, and that cancellation is the
            // only thing that ends the Kotlin collectors — so nothing here may be detached.
            .task(id: scenePhase == .active) {
                guard scenePhase == .active else { return }
                await model.observe()
            }
    }
}

private struct SyncActivityCapsuleMount: ViewModifier {
    let hostProblem: Bool
    let showsStorageBar: Bool
    let hidden: Bool

    @StateObject private var model = SyncActivityModel()

    func body(content: Content) -> some View {
        content
            // ⚠⚠ An overlay, and it must stay one. Never `.safeAreaInset`, never a `contentInset`
            // change, never a stack sibling: the capsule appears exactly when the feed is busiest,
            // and anything that resizes the content moves the photographs under the user.
            .overlay(alignment: .bottom) {
                SyncActivityCapsuleSlot(
                    model: model, hostProblem: hostProblem, showsStorageBar: showsStorageBar, hidden: hidden
                )
            }
            .syncActivityObservation(model)
    }
}

extension View {
    /// Mount the sync-activity capsule over this view: one observation and one place to draw.
    /// For a root with a single `NavigationStack`; the parameters are `SyncActivityCapsuleSlot`'s.
    func syncActivityCapsule(hostProblem: Bool, showsStorageBar: Bool, hidden: Bool) -> some View {
        modifier(SyncActivityCapsuleMount(hostProblem: hostProblem, showsStorageBar: showsStorageBar, hidden: hidden))
    }

    /// Observe sync activity into `model` — ONCE per app, above whatever draws it.
    ///
    /// ⚠⚠ A `SyncActivityCapsuleSlot` over a model nobody observes draws nothing, silently. The
    /// two always go together: the root holds the model as a `@StateObject`, applies this, and
    /// hands slots built over the same model to its shell.
    func syncActivityObservation(_ model: SyncActivityModel) -> some View {
        modifier(SyncActivityObservation(model: model))
    }
}
