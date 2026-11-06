import Foundation
import TenshuSdk

/// Carries a decoded cross-app open from the app root to the Files stack that can show it
/// (`control-cross-app-open.md` §5i).
///
/// ⚠⚠ **A holder rather than a direct call, because the two ends are not both present.** The URL
/// arrives on the app (a scene callback, possibly at launch), while the navigation path lives
/// inside `PairedAppView` — which does not exist until a Control session is found. A cross-app open
/// to an unprovisioned Drive must wait rather than be dropped, and this is where it waits.
///
/// ⚠ Not durable, and must not become so: it lives for the process, exactly like the request it
/// carries (CO-D8). One slot, last-wins — a second open supersedes an unclaimed first.
@MainActor
final class OpenTargetRouter: ObservableObject {

    static let shared = OpenTargetRouter()

    /// The file a cross-app open named, waiting for a stack to show it in.
    ///
    /// ⚠ A `String?` rather than the `OpenedTarget`: SwiftUI's `onChange` wants `Equatable`, and a
    /// bridged Kotlin type is a poor thing to depend on for that. The type check happens once, on
    /// arrival, so nothing downstream has to re-decide what is routable.
    @Published private(set) var pendingFilePath: String?

    /// ⚠⚠ Drive shows **files**, and a target of any other type is dropped rather than guessed at.
    /// Control routes by type (CO-D2), so receiving something else means the handler table and this
    /// app disagree — and opening the file preview on a `placeId` would render that disagreement as
    /// a plausible-looking wrong screen instead of a visible nothing.
    ///
    /// ⚠ The literal mirrors `File.ID`; iOS cannot reach `drive-shared-ui`'s Kotlin copy of this
    /// rule (that module is Compose, and iOS builds against `drive-shared-logic`), so the same
    /// duplication `HandlerTable` documents applies here.
    func offer(_ target: OpenedTarget) {
        guard target.type == "tenshu/file", !target.key.isEmpty else { return }
        pendingFilePath = target.key
    }

    /// Hands over the waiting path exactly once.
    func take() -> String? {
        defer { pendingFilePath = nil }
        return pendingFilePath
    }
}
