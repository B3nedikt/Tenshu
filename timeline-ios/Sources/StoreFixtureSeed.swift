#if DEBUG
import SwiftUI
import TenshuSdk

/// Seeds the store-screenshot fixture (`docs/how-to/store-screenshots.md` §3f), the twin of
/// Drive's, Fit's and Contacts' `StoreFixtureSeed`.
///
/// ⚠⚠ **Data only, never a screen anyone screenshots.** The `Screenshots` UI test launches Timeline
/// once with `-seedFixture <dir>`, waits for the result line, and launches it again without the
/// argument — so every capture is the shipping `TimelineShippingRoot` over the real in-process
/// runtime and the realm Control created.
///
/// The fixture is a Google Timeline export (`store/fixtures/timeline/generate_timeline.py` →
/// `Semantic Location History/*.json`), handed to the shared `FileTimelineImporter` — the call
/// Timeline's *Import from Google Takeout* makes on Android and desktop. The iOS app has no import
/// of its own (its places and trips come from its own capture, or through the realm from the
/// user's other devices), so this is the one way in that is also a user's: the places, visits and
/// movement traces it writes are the rows such an import syncs onto this phone. Capture cannot be
/// driven instead — the simulator delivers no `CLVisit`, and two weeks cannot be lived in a run.
///
/// ⚠ DEBUG-only (the `Debug` configuration the screenshot scheme builds), and the Kotlin entry point
/// refuses a release binary too. It needs the route-1 key material Control provisions at genesis;
/// without it the result line says so and the test fails on it.
enum StoreFixtureSeed {
    /// `<dir>/Semantic Location History` for `-seedFixture <dir>`.
    static var requestedFolder: URL? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-seedFixture"), i + 1 < args.count else { return nil }
        return URL(fileURLWithPath: args[i + 1]).appendingPathComponent("Semantic Location History")
    }
}

struct StoreFixtureSeedView: View {
    let folder: URL
    @State private var result = "seeding…"

    var body: some View {
        Text(result)
            .font(.system(.footnote, design: .monospaced))
            .padding()
            .accessibilityIdentifier("seed-result")
            .task { await seed() }
    }

    private func seed() async {
        guard TimelineLocalRuntime.shared.isProvisioned() else {
            finish("seed: NOT PROVISIONED — run Control's genesis on this simulator first")
            return
        }
        TimelineLocalRuntime.shared.install()
        do {
            let files = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
                .filter { $0.pathExtension == "json" }
                .sorted { $0.lastPathComponent < $1.lastPathComponent }
            guard !files.isEmpty else {
                finish("seed: FAILED — no export files in \(folder.path)")
                return
            }
            let texts = try files.map { try String(contentsOf: $0, encoding: .utf8) }
            let written = try await TimelineLocalRuntime.shared.importGoogleTimelineForStoreFixture(
                names: files.map(\.lastPathComponent),
                texts: texts
            )
            if written.intValue > 0 {
                finish("seed: OK — \(written) records from \(files.count) file(s)")
            } else {
                finish("seed: FAILED — the import wrote \(written) records")
            }
        } catch {
            finish("seed: FAILED — \(error)")
        }
    }

    private func finish(_ line: String) {
        print("TENSHU-SEED \(line)")
        result = line
    }
}
#endif
