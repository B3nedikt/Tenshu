#if DEBUG
import SwiftUI
import TenshuSdk

/// Seeds the realm with the store-screenshot fixture (`docs/how-to/store-screenshots.md`).
///
/// ⚠⚠ **Data only, never a screen anyone screenshots.** The `Screenshots` UI test launches Drive
/// once with `-seedFixture <dir>`, waits for the result line, and launches it again without the
/// argument — so every capture is the shipping `RootView` over the real in-process runtime. This
/// view exists to put rows in the realm, through the same `uploadFile` / `createFolder` ops the
/// "+ Add" menu calls; it renders nothing a user could see.
///
/// ⚠ DEBUG-only (the `Debug` configuration, not only `DebugHarness`): the screenshot scheme builds
/// the shipping app in `Debug` precisely so the harness tabs are absent, and the seed has to be
/// reachable there. A Release build does not compile this file's body.
///
/// ⚠ It needs the route-1 key material Control provisions at genesis. Without it the result line
/// says so, and the test fails on it rather than capturing an empty listing.
enum StoreFixtureSeed {
    static var requestedDirectory: String? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-seedFixture"), i + 1 < args.count else { return nil }
        return args[i + 1]
    }
}

struct StoreFixtureSeedView: View {
    let directory: String
    @State private var result = "seeding…"

    var body: some View {
        Text(result)
            .font(.system(.footnote, design: .monospaced))
            .padding()
            .accessibilityIdentifier("seed-result")
            .task {
                guard DriveLocalRuntime.shared.isProvisioned() else {
                    result = "seed: NOT PROVISIONED — run Control's genesis on this simulator first"
                    print("TENSHU-SEED \(result)")
                    return
                }
                DriveReadSurface.shared.installLocalRuntime()
                let summary = (try? await DriveLocalRuntime.shared.debugSeedFixture(rootDir: directory))
                    ?? "seed: threw"
                print("TENSHU-SEED \(summary)")
                result = summary
            }
    }
}
#endif
