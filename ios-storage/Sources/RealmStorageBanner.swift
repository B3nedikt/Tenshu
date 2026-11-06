import SwiftUI
import TenshuSdk

/// The realm-storage banner (`drive-synced-folders.md` §8b.5c) — the iOS half of the strip every
/// Android app shows, shared by every first-party iOS app.
///
/// ⚠ Compiled INTO each app target via `ios-storage/storage.yml`, the same convention
/// `ios-shell` / `ios-onboarding` / `ios-basemap` use — a SwiftPM package cannot be used here
/// because a `.binaryTarget` resolves before any build phase runs, and the Gradle-built
/// `TenshuSdk` framework does not exist yet at that point.
///
/// The Android/desktop banner is Compose and no iOS app links a Compose UI module, so this is a
/// second renderer rather than a port. What it is *not* is a second decision: the `NoPlan` / `Full`
/// / `Ok` rule stays in Kotlin (`evaluateRealmStorage`, in `:storage-service-common` with its own
/// tests) and `RealmStorageSurface` hands over a finished string — the same shape
/// `DriveBackupSurface.observeStatusText()` already uses. Two renderers, one rule, so the platforms
/// cannot come to disagree about when a realm counts as full.
///
/// ⚠⚠ iOS is where this gap is worst, not least. `ios-services-status-and-purchase.md` §3.5
/// measured a realm created on iOS sitting at **0 blocks** on the relay with two devices correctly
/// paired: `StorageSyncGate` holds the personal realm's push until a plan is bound, and nothing
/// anywhere said so. The rescue needed a *second, non-iOS device* — an iOS-only user had no in-app
/// path out at all.
struct RealmStorageBanner: View {
    @StateObject private var model = RealmStorageModel()

    var body: some View {
        // ⚠ One container with ONE observation on it, whether or not there is text. The bar and
        // the "nothing to say" case used to be two branches that each started the observation, so
        // the first line of text switched branch under its own observer.
        ZStack {
            // Still has to observe with nothing to say, or a realm that fills while the app is
            // open never says so — and a view with no content never runs its task.
            Color.clear.frame(height: 0)
            // `nil` text means "nothing to say" — the whole strip disappears, rather than
            // rendering an empty coloured bar.
            if let text = model.text {
                bar(text)
            }
        }
        .modifier(RealmStorageObservation(model: model))
    }

    private func bar(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: model.isError ? "exclamationmark.triangle.fill" : "icloud.slash")
                .foregroundStyle(model.isError ? Color.orange : Color.secondary)
                .accessibilityHidden(true)

            Text(text)
                .font(.footnote)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)

            // ⚠ Offered only when Control can actually be reached. Drive on iOS runs its own
            // in-process runtime and works perfectly without Control installed, so "no route"
            // is an ordinary state — and a button that goes nowhere is worse than no button.
            if model.canOpenControl {
                Button("Manage storage") { RealmStorageSurface.shared.openControlServices() }
                    .font(.footnote.weight(.semibold))
                    .fixedSize()
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        // ⚠ Full is an error tone; no plan is NOT. The app works and the data is safe, it is
        // simply not leaving the device — colouring that as a fault on every screen of an
        // unfunded realm is the loudest possible way to say "you have not paid".
        .background(model.isError ? Color.orange.opacity(0.16) : Color.secondary.opacity(0.14))
    }
}

/// Follows the Kotlin flows into the model for as long as the banner is mounted and the scene is
/// active.
///
/// ⚠⚠ The task's body AWAITS the observation, and that is the whole fix
/// (`ios-sync-activity-indicator.md` §10.2 item 11). The model used to start two detached `Task`s
/// behind a `started` flag: nothing cancelled them, so their Kotlin collectors outlived a banner
/// that had been discarded, for the life of the process. SwiftUI cancels this task when the banner
/// goes away and when the scene stops being active, and cancellation is what ends a collector.
///
/// ⚠ A restart (the scene coming back) re-reads the same values from the flows and does not clear
/// the model first, so a bar that is up does not blink.
private struct RealmStorageObservation: ViewModifier {
    let model: RealmStorageModel
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content.task(id: scenePhase == .active) {
            guard scenePhase == .active else { return }
            await model.observe()
        }
    }
}

@MainActor
final class RealmStorageModel: ObservableObject {
    @Published var text: String?
    @Published var isError = false
    @Published var canOpenControl = false

    /// Which call of [observe] owns the published values: a cancelled call unwinds some time after
    /// its replacement has started, and must not publish over it.
    private var run = 0

    /// Follow the two Kotlin flows (via SKIE) until the calling task is cancelled.
    ///
    /// ⚠ It holds no task. This IS the observation, and both collectors are children of the task
    /// that called it.
    /// ⚠ `canOpenControl` is read once per observation rather than observed: it depends on whether
    /// Control is installed and on this app's `LSApplicationQueriesSchemes`. Neither changes while
    /// the app is in the foreground, and iOS offers no notification for it.
    func observe() async {
        run += 1
        let mine = run
        canOpenControl = RealmStorageSurface.shared.canOpenControl()
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                for await text in RealmStorageSurface.shared.observeBannerText() {
                    guard self.run == mine else { return }
                    self.text = text
                }
            }
            group.addTask { @MainActor in
                for await isError in RealmStorageSurface.shared.observeIsError() {
                    guard self.run == mine else { return }
                    self.isError = isError.boolValue
                }
            }
        }
    }
}
