import SwiftUI
import TenshuSdk

// Data — the generic realm-data card list (`core-realm-data.md` Phase 4). Binds
// `DataViewModel`, observing `uiState.typeIds` (one entry per declared `RealmData`
// type). The Compose `RealmDataPresentation` (icon/label) stays on Android/Desktop;
// here a simple type-id-derived name renders the row (icons are a C2c polish pass).
@MainActor
final class DataModel: ObservableObject {
    @Published var typeIds: [String] = []
    // Until the first presence check lands, an empty list means "not known yet", not "no data".
    @Published var loading: Bool = true

    private let viewModel = ControlSurface.shared.dataViewModel()

    init() {
        typeIds = viewModel.uiState.value.typeIds
        loading = viewModel.uiState.value.loading
    }

    // The presence check is one-shot, so re-run it on every entry and foreground return —
    // the Compose screen's `LifecycleResumeEffect` twin. Data another app stored meanwhile
    // gets its card then.
    func refresh() {
        viewModel.refresh()
    }

    func observe() async {
        for await state in viewModel.uiState {
            typeIds = state.typeIds
            loading = state.loading
        }
    }
}

struct DataView: View {
    @StateObject private var model = DataModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            List {
                if model.loading {
                    ProgressView()
                } else if model.typeIds.isEmpty {
                    Text("No realm data yet.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(model.typeIds, id: \.self) { typeId in
                        Label(displayName(for: typeId), systemImage: "tray.full")
                    }
                }
            }
            .navigationTitle("Data")
        }
        .task { await model.observe() }
        .onAppear { model.refresh() }
        .onChange(of: scenePhase) { phase in
            if phase == .active { model.refresh() }
        }
    }

    private func displayName(for typeId: String) -> String {
        let leaf = typeId.split(separator: "/").last.map(String.init) ?? typeId
        return leaf.prefix(1).uppercased() + leaf.dropFirst()
    }
}
