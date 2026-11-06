#if DEV_HARNESS
import SwiftUI
import TenshuSdk

/// `-photosReadsReadout` (with `-photosFixture`, DEV_HARNESS only): the fixture repository's calls
/// that were made ON THE MAIN THREAD since launch, by name — `none` when there were none
/// (photos-ios.md §8.22.2). `ReadsOffMainUITests` walks every screen and reads it.
///
/// ⚠⚠ A count, not a time: a repository call on the main thread is the defect whatever it costs on
/// this machine — on a phone a walk of the library there is ~2 s of a frozen app — and one is enough
/// to fail. The fixture answers from memory, so no wall clock or CPU figure here could say so.
/// ⚠ With `-photosHeldReads <ms>` each call also burns that much CPU where it runs, which is what
/// makes the same defect FELT in a probe; the count does not need it.
enum ReadsOnMainProbe {
    static var enabled: Bool { ProcessInfo.processInfo.arguments.contains("-photosReadsReadout") }

    struct Readout: View {
        var body: some View {
            if ReadsOnMainProbe.enabled { VStack(alignment: .trailing, spacing: 0) {
                // Polled: the list only grows, and the test reads it after the screens have settled.
                TimelineView(.periodic(from: .now, by: 0.5)) { _ in
                    let calls = PhotosSurface.shared.fixtureCallsOnMain()
                    Color.clear.frame(width: 1, height: 1)
                        .accessibilityElement()
                        .accessibilityIdentifier("reads-on-main")
                        .accessibilityValue(calls.isEmpty ? "none" : calls)
                    // How many walks of the library the fixture has served (§8.22.3).
                    Color.clear.frame(width: 1, height: 1)
                        .accessibilityElement()
                        .accessibilityIdentifier("library-walks")
                        .accessibilityValue("\(PhotosSurface.shared.fixtureWalks())")
                }
                // ⚠ What a UI test cannot do for itself: make photographs ARRIVE, as a sync does —
                // one, or a burst of twelve 80 ms apart (the `-photosArrivals` demo's rhythm).
                // ⚠ Real, hittable buttons: at 6 pt and 5 % opacity XCUITest "tapped" them and nothing ran.
                VStack(alignment: .leading, spacing: 4) {
                    Button("arrive-one") { PhotosSurface.shared.fixtureArrive(count: 1) }
                        .accessibilityIdentifier("arrive-one")
                    Button("arrive-burst") {
                        Task { @MainActor in
                            for _ in 0..<12 {
                                PhotosSurface.shared.fixtureArrive(count: 1)
                                try? await Task.sleep(nanoseconds: 80_000_000)
                            }
                        }
                    }
                    .accessibilityIdentifier("arrive-burst")
                }
                .font(.caption2)
                .buttonStyle(.borderedProminent)
                .opacity(0.6)
            } }
        }
    }
}
#endif
