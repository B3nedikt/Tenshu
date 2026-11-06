import SwiftUI
import TenshuSdk

// One data source, and everything that belongs to it (`fit-data-sources-restructure.md` §4) —
// the SwiftUI twin of `DataSourceDetailScreen`. Pushed from Profile's Data sources rows.
//
// ⚠ Every rule it renders was decided in `commonMain` and desktop-tested (D10): which state the
// row shows, whether this device may import, whether the switch applies here or elsewhere. This
// view chooses no behaviour of its own.
struct DataSourceDetailView: View {
    @StateObject private var model: DataSourceModel

    init(kind: DataSourceKind, deviceId: String?) {
        _model = StateObject(wrappedValue: DataSourceModel(kind: kind, deviceId: deviceId))
    }

    var body: some View {
        List {
            Section {
                if let row = model.state?.row {
                    HStack {
                        Label(dataSourceName(row.kind), systemImage: dataSourceSymbol(row.kind))
                        Spacer()
                        Text(dataSourceStatus(row.state))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    if let device = row.deviceLabel {
                        Text("On \(device)").font(.caption).foregroundStyle(.secondary)
                    }
                } else {
                    // ⚠ Not an empty state. A nil row means the registry is still being read, and
                    // saying "no such source" during the first frame after a cold start would be
                    // a claim about the user's realm we have not read yet.
                    ProgressView()
                }
            } footer: {
                Text(dataSourceHint(model.kind))
            }

            if model.kind == .tenshuHealth {
                Section {
                    Text("Workouts you record in Fit, enter by hand, or that are detected from your movement. Nothing is imported — this is where they already are.")
                        .font(.callout)
                        .foregroundStyle(.secondary)

                    // Q1 (DS-6): auto-detection is this source's own setting, so it lives here
                    // rather than in a separate section on Profile. ⚠ Read off the row's state,
                    // not a second read — `Recording` carries exactly this.
                    if let state = model.state?.row?.state,
                       case .recording(let recording) = onEnum(of: state) {
                        Toggle("Detect activities automatically", isOn: Binding(
                            get: { recording.autoDetect },
                            set: { model.setAutoImport($0) }
                        ))
                        Text("Log walks, runs and rides your phone already noticed, without pressing start.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            } else {
                Section {
                    if model.state?.canImportNow == true {
                        Button("Import now") { model.importNow() }
                    } else if let device = model.state?.row?.deviceLabel {
                        // ⚠⚠ D4: we cannot make another device read its health store, so the
                        // action is replaced by where the user would have to go — not by a
                        // disabled button that silently does nothing.
                        Text("Open Fit on \(device) to import from there")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                    }

                    if let row = model.state?.row {
                        Toggle("Import automatically", isOn: Binding(
                            get: { row.autoImport },
                            set: { model.setAutoImport($0) }
                        ))
                        // The switch reaches another device — the flag is ours and it syncs — but
                        // that device applies it when it next runs. Saying so stops the delay
                        // reading as a bug.
                        Text(row.deviceLabel.map { "Applies the next time Fit opens on \($0)" }
                             ?? "Check for new data each time Fit opens")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("Import history") {
                    if model.state?.history.isEmpty ?? true {
                        // ⚠ iOS 17's ContentUnavailableView is unavailable at our iOS-16 floor —
                        // `FitEmptyState` is the house stand-in.
                        Text("Nothing imported yet.").font(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(Array((model.state?.history ?? []).enumerated()), id: \.offset) { _, entry in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(importRunHeadline(entry)).font(.callout)
                            Text(importRunDetail(entry)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .navigationTitle(dataSourceName(model.kind))
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
    }
}

@MainActor
final class DataSourceModel: ObservableObject {
    @Published var state: DataSourceDetailUiState?

    let kind: DataSourceKind
    private let vm: DataSourceDetailViewModel

    init(kind: DataSourceKind, deviceId: String?) {
        self.kind = kind
        vm = FitSurface.shared.dataSourceViewModel(kind: kind, deviceId: deviceId)
    }

    func observe() async {
        for await v in vm.uiState { state = v }
    }

    func importNow() { vm.importNow() }

    func setAutoImport(_ enabled: Bool) { vm.setAutoImport(enabled: enabled) }
}
