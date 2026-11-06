import SwiftUI
import TenshuSdk

// Apps — the installed-app list. Binds `AppsViewModel`, observing
// `uiState.apps: [InstalledApp]` + `uiState.loading`. Over fakes this renders the
// `MockAppRegistry` seed (Drive + a dev app).
@MainActor
final class AppsModel: ObservableObject {
    @Published var apps: [InstalledApp] = []
    @Published var loading: Bool = true

    private let viewModel = ControlSurface.shared.appsViewModel()

    init() {
        let state = viewModel.uiState.value
        apps = state.apps
        loading = state.loading
    }

    func observe() async {
        for await state in viewModel.uiState {
            apps = state.apps
            loading = state.loading
        }
    }
}

struct AppsView: View {
    @StateObject private var model = AppsModel()

    var body: some View {
        NavigationStack {
            List {
                if model.loading && model.apps.isEmpty {
                    Text("Loading…").foregroundStyle(.secondary)
                } else if model.apps.isEmpty {
                    Text("No apps installed.").foregroundStyle(.secondary)
                } else {
                    ForEach(model.apps, id: \.appId) { app in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(app.name).font(.headline)
                            Text(app.shortDescription)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            .navigationTitle("Apps")
        }
        .task { await model.observe() }
    }
}
