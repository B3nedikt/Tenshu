import SwiftUI

// Shared plumbing for the single-entity detail screens (Moment / Trip / Place). Each VM exposes a
// `StateFlow` of a sealed `*DetailUiState`; SKIE renders that flow as an `AsyncSequence` and the
// sealed state as a Swift enum (switched via `onEnum(of:)`). This observer collects the flow into a
// `@Published` value the SwiftUI screen renders.
@MainActor
final class DetailModel<State>: ObservableObject {
    @Published var state: State?

    private let subscribe: (@MainActor @escaping (State) -> Void) async -> Void

    /// Build from a factory returning the VM's `StateFlow` (a SKIE `AsyncSequence` of `State`).
    init<S: AsyncSequence>(_ flow: @escaping () -> S) where S.Element == State {
        self.subscribe = { setter in
            do { for try await value in flow() { setter(value) } } catch {}
        }
    }

    func observe() async {
        await subscribe { [weak self] value in self?.state = value }
    }
}

// `ContentUnavailableView` is iOS 17+, but the app targets iOS 16 — a minimal stand-in for the
// not-found states.
struct ContentUnavailableCompat: View {
    let title: String
    let systemImage: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage).font(.largeTitle).foregroundStyle(.tertiary)
            Text(title).font(.headline).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
