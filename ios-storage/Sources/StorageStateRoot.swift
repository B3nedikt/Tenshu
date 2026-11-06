import SwiftUI
import UIKit
import TenshuSdk

/// The shared store's state over an app's root (`ios-shared-store-sqlite.md` §6, Q39, Appendix
/// B.17 (257)): every first-party app's `WindowGroup` content goes inside one of these.
///
/// ⚠ Compiled INTO each app target via `ios-storage/storage.yml`, beside `RealmStorageBanner`.
///
/// Swift decides nothing: whether to cover, the copy, the "Try again" and the generation are
/// Kotlin's (`StorageStateSurface`). What Swift owns is the two things only a root can do:
/// - **covering**: a state that `covers` puts its surface over the root, so no screen over a store
///   that is not serving is reachable (an empty listing over the stand-in would read as an empty
///   success);
/// - **re-creating**: the root's identity is the snapshot's `generation`, so the move back to READY
///   over a rebuilt graph builds every screen, and every view model and engine a screen made
///   (Fit's recording engine included), anew over the new graph.
///
/// ⚠⚠ With the SQLite switch off (every shipped build) the snapshot is READY, generation 0, for the
/// life of the process: this view is then its content and nothing else.
struct StorageStateRoot<Content: View>: View {
    @StateObject private var model = StorageStateModel()
    private let content: () -> Content

    init(@ViewBuilder content: @escaping () -> Content) {
        self.content = content
    }

    var body: some View {
        ZStack {
            content()
                .id(model.snapshot.generation)
                // Hidden from VoiceOver while covered: nothing under the cover is reachable.
                .accessibilityHidden(model.snapshot.covers)
            if model.snapshot.covers {
                StorageStateCover(snapshot: model.snapshot)
                    .transition(.opacity)
            }
        }
        .animation(.default, value: model.snapshot.covers)
        .onAppear { model.start() }
        .onDisappear { model.stop() }
    }
}

/// The cover: one title, one paragraph, and "Try again" when Kotlin offers it. Over a damaged
/// store (Appendix B.18, Q47): "Export damaged data" in Control once its copy is complete, or
/// "Open Control" in any other app when Control can be reached.
private struct StorageStateCover: View {
    let snapshot: StorageStateSnapshot
    @State private var exporting = false
    @State private var confirmingDelete = false

    var body: some View {
        VStack(spacing: 16) {
            if snapshot.canTryAgain {
                Image(systemName: "externaldrive.badge.exclamationmark")
                    .font(.largeTitle)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            } else {
                ProgressView()
                    .opacity(isWaiting ? 1 : 0)
                    .accessibilityHidden(true)
            }
            Text(snapshot.title ?? "")
                .font(.title2.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(snapshot.message ?? "")
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            if snapshot.canTryAgain {
                Button("Try again") { _ = StorageStateSurface.shared.tryAgain() }
                    .buttonStyle(.borderedProminent)
            }
            // ⚠⚠ B.18: Kotlin sets this only in Control, only over a COMPLETE copy, with its files.
            if snapshot.canExportDamagedData && !snapshot.damagedDataFiles.isEmpty {
                Button("Export damaged data") { exporting = true }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("storage-state-export-damaged-data")
                    .sheet(isPresented: $exporting) {
                        DamagedDataShareSheet(paths: snapshot.damagedDataFiles, isPresented: $exporting)
                    }
            }
            // ⭐ Q51: "Delete the copy", Control's only, behind a confirmation.
            if snapshot.canDeleteDamagedData {
                Button("Delete the copy", role: .destructive) { confirmingDelete = true }
                    .accessibilityIdentifier("storage-state-delete-damaged-data")
                    .confirmationDialog("Delete the copy of the damaged data?", isPresented: $confirmingDelete, titleVisibility: .visible) {
                        Button("Delete the copy", role: .destructive) { _ = StorageStateSurface.shared.deleteDamagedData() }
                        Button("Cancel", role: .cancel) {}
                    } message: {
                        Text("Export it first if you may need it. The damaged data itself stays on this phone, and Control does not copy it again.")
                    }
            }
            // ⚠ No button that does nothing: offered only when Control can actually be reached
            // (`tenshu-control` in this app's LSApplicationQueriesSchemes, Control installed).
            if snapshot.offersOpenControl && RealmStorageSurface.shared.canOpenControl() {
                Button("Open Control") { StorageStateSurface.shared.openControl() }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("storage-state-open-control")
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(uiColor: .systemBackground))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("storage-state-cover-\(snapshot.state)")
    }

    /// A state the host leaves by itself shows a spinner; a final one (update, damaged, unavailable) does not.
    private var isWaiting: Bool {
        ["REFRESHING", "UPDATING", "CHECKING"].contains(snapshot.state)
    }
}

/// The system share sheet over the damaged store's complete copy (§6: to Files or AirDrop), which
/// is how the user or the maintainer gets it off the phone to inspect it with `sqlite3`. Nothing
/// in the app reads the copy again, and nothing uploads it: only the user's own choice here moves it.
///
/// ⚠ B.18.8: the sheet's completion resets `isPresented`, or a second tap after a
/// cancel or a share would present nothing. ⚠ The pasteboard is excluded: a copy there reaches the
/// user's other devices by Universal Clipboard without them choosing a destination; printing a
/// database is meaningless. Files, AirDrop, Mail and Messages stay: each is a destination the user picks.
private struct DamagedDataShareSheet: UIViewControllerRepresentable {
    let paths: [String]
    @Binding var isPresented: Bool

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: paths.map { URL(fileURLWithPath: $0) }, applicationActivities: nil)
        controller.excludedActivityTypes = [.copyToPasteboard, .print, .assignToContact, .saveToCameraRoll, .addToReadingList]
        controller.completionWithItemsHandler = { _, _, _, _ in
            DispatchQueue.main.async { isPresented = false }
        }
        return controller
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// Mirrors `StorageStateSurface` for as long as the root is on screen.
///
/// ⚠ The callback is Kotlin's, made on the main thread; it is hopped onto the main queue all the
/// same, so the published value is only ever written there whatever the bridge's isolation says.
@MainActor
final class StorageStateModel: ObservableObject {
    @Published private(set) var snapshot: StorageStateSnapshot = StorageStateSurface.shared.snapshot()
    private var observation: StorageStateObservation?

    func start() {
        guard observation == nil else { return }
        observation = StorageStateSurface.shared.observe { [weak self] next in
            DispatchQueue.main.async { self?.publish(next) }
        }
    }

    func stop() {
        observation?.cancel()
        observation = nil
    }

    private func publish(_ next: StorageStateSnapshot) {
        let newlyCovered = next.covers && !snapshot.covers
        snapshot = next
        if newlyCovered { Self.dismissPresented() }
    }

    /// ⚠⚠ B.17 (291): a sheet, a full-screen cover, an alert or a picker presented before the
    /// state left READY sits above the root's cover, and over the stand-in it would show an
    /// empty result as if it were true (a Drive share sheet's "no members"): AGENTS.md's
    /// "not-connected is a failure, never an empty success". So the moment the cover goes up,
    /// whatever any window's root presented is dismissed, and the cover is what remains.
    private static func dismissPresented() {
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows {
                // Dismisses the whole presented chain above the root view controller.
                if window.rootViewController?.presentedViewController != nil {
                    window.rootViewController?.dismiss(animated: false)
                }
            }
        }
    }
}
