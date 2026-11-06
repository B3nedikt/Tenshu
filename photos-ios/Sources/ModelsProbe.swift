#if DEV_HARNESS
import SwiftUI
import TenshuSdk

/// `-photosModels <state>` (with `-photosFixture`, DEV_HARNESS only): the Models screens over
/// `PhotosModelsFixture` in the state named, with no model and no network
/// (ios-on-device-inference.md §14.18.15). ⚠ Opt-in: without the argument nothing is installed,
/// and the build's own flag decides whether there is a Settings button at all.
///
/// `-photosModelsOpen models|feature` opens that screen at launch — `simctl` cannot tap.
/// `-photosModelsBare` leaves the overlay's two buttons out, for a screenshot.
///
/// ⚠⚠ The fake moves only when `models-probe-advance` is pressed: a download or a cancel the screen
/// asked for is recorded (`models-probe-calls`) and changes no row, as a journal would not have yet.
@MainActor
enum ModelsProbe {
    private static func argument(_ name: String) -> String? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
        return args[i + 1]
    }

    /// The fixture's controls, installed on first use. `nil`: no state was named, or not a known one.
    /// ⚠ Only with `-photosFixture` as well, as this file's header says: the fixture's screens are
    /// never installed over a real library.
    static let probe: PhotosModelsProbe? = ProcessInfo.processInfo.arguments.contains("-photosFixture")
        ? argument("-photosModels").flatMap { PhotosSurface.shared.installModelsFixture(state: $0) }
        : nil

    static var opens: String? { probe == nil ? nil : argument("-photosModelsOpen") }

    final class Calls: ObservableObject {
        @Published var value = ""
        private var observation: Task<Void, Never>?

        init(_ probe: PhotosModelsProbe) {
            observation = Task { @MainActor [weak self] in
                for await calls in probe.calls { self?.value = calls }
            }
        }

        deinit { observation?.cancel() }
    }

    struct Readout: View {
        var body: some View {
            if let probe = ModelsProbe.probe { Controls(probe: probe) }
        }
    }

    private struct Controls: View {
        let probe: PhotosModelsProbe
        @StateObject private var calls: Calls

        init(probe: PhotosModelsProbe) {
            self.probe = probe
            _calls = StateObject(wrappedValue: Calls(probe))
        }

        var body: some View {
            HStack(spacing: 14) {
                Color.clear.frame(width: 1, height: 1)
                    .accessibilityElement()
                    .accessibilityIdentifier("models-probe-calls")
                    .accessibilityValue(calls.value)
                if !ProcessInfo.processInfo.arguments.contains("-photosModelsBare") {
                    Button("step") { probe.advance() }
                        .accessibilityIdentifier("models-probe-advance")
                    // A re-provision under the open screen: the runtime it was built over is replaced.
                    Button("swap") { _ = PhotosSurface.shared.installModelsFixture(state: "ready") }
                        .accessibilityIdentifier("models-probe-reprovision")
                }
            }
            .font(.caption2)
            .padding(.horizontal, 10)
            .padding(.bottom, 58)
        }
    }
}
#endif
