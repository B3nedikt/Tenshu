#if DEV_HARNESS
import SwiftUI
import UIKit
import TenshuSdk

/// The harness surface a UI test asserts against (`control-cross-app-open.md` §5i.8).
///
/// ⚠⚠ **A file receipt cannot be read by the test process** — the app and the UI test run in
/// separate containers — so proving the decode to XCUITest needs the result to reach the
/// *accessibility tree*. This publishes it there, and nowhere else.
///
/// ⚠ `DEV_HARNESS` only. A shipping build has no reason to render what it just opened.
final class OpenTargetHarness: ObservableObject {

    static let shared = OpenTargetHarness()

    /// The identifier the UI test queries. ⚠ Stable string, referenced from `OpenTargetUITests`.
    static let accessibilityId = "openTargetReceipt"

    /// ⚠ The sentinel matters: an empty label and a missing element look the same to XCUITest, so
    /// "nothing arrived" must be a value rather than an absence.
    @Published private(set) var summary: String = "none"

    func record(_ target: OpenedTarget) {
        summary = "type=\(target.type) key=\(target.key)"
    }
}

/// A one-line, tiny, always-present readout of the last decoded target.
///
/// ⚠ Present even when nothing has arrived — see the sentinel above.
struct OpenTargetReadout: View {
    @ObservedObject private var harness = OpenTargetHarness.shared

    var body: some View {
        Text(harness.summary)
            .font(.system(size: 8))
            .foregroundStyle(.secondary)
            .accessibilityIdentifier(OpenTargetHarness.accessibilityId)
    }
}

enum OpenTargetSelfFire {

    /// Launch argument that makes the app open a real cross-app-open URL **at itself**.
    static let argument = "-fireOpenTargetSelfUrl"

    /// ⚠⚠ **The app fires at itself rather than the test driving Safari.** Typing a base64url
    /// payload into the address bar is slow and brittle, and going through a browser adds a second
    /// confirmation surface with its own wording. Self-opening reaches the same SpringBoard
    /// "Open in …?" alert — which is the thing the test exists to tap — with nothing else in the way.
    ///
    /// ⚠ The URL comes from `probeOpenTargetUrl`, i.e. the production encoder, so a broken encoder
    /// fails the test rather than being papered over by a hand-written string.
    static func fireIfRequested() {
        guard ProcessInfo.processInfo.arguments.contains(argument) else { return }
        guard let raw = OpenTargetRoutingKt.probeOpenTargetUrl(
            appId: "app.tenshu.drive",
            type: expectedType,
            key: expectedKey
        ), let url = URL(string: raw) else {
            NSLog("Tenshu harness: could not build a probe open URL")
            return
        }
        // ⚠ After a beat: firing inside `init()` races the scene coming up, and the alert then has
        // no window to present in.
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            UIApplication.shared.open(url)
        }
    }

    /// ⚠ Shared with the UI test's expectation — change both or neither.
    static let expectedType = "tenshu/file"
    static let expectedKey = "/Photos/uitest.jpg"
}
#endif
