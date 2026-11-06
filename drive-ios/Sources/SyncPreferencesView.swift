import SwiftUI
import TenshuSdk

// Sync Preferences settings (full-parity UI, step B), bound to the real `SyncPreferencesViewModel`.
// Reached from Settings. Toggles update optimistically; they persist once a Control serves device
// preferences (the on-device data path) — over the Noop seams here they just reflect locally.
struct SyncPreferencesView: View {
    @StateObject private var model = SyncPrefsModel()

    var body: some View {
        Form {
            Section {
                Toggle("Keep files offline by default", isOn: Binding(
                    get: { model.state.keepOfflineByDefault },
                    set: { model.setKeepOffline($0) }
                ))
            } header: {
                Text("Offline")
            } footer: {
                Text("When on, new files download to this device automatically.")
            }

            // The local-cache ceiling (retention-quota-model.md Face B / §4.12), the iOS twin
            // of Drive's "Cache size limit" row. ⚠ The copy says CACHE, and the footer spells
            // out that kept-offline files are outside the limit: the ceiling deliberately
            // measures only evictable content, so a label promising a total footprint would
            // promise something it does not enforce (D6 as corrected).
            Section {
                Picker("Cache size limit", selection: Binding(
                    get: { CacheCeilingChoices.shared.nearest(bytes: model.state.maxLocalBytes) },
                    set: { model.setMaxLocalBytes($0) }
                )) {
                    ForEach(cacheCeilingChoices, id: \.self) { choice in
                        Text(cacheCeilingLabel(choice)).tag(choice)
                    }
                }
            } header: {
                Text("Storage")
            } footer: {
                // ⚠ Deliberately does not promise immediate eviction: enforcement is the
                // offline keeper's periodic sweep, so a lowered limit applies on its next pass.
                Text("Files you keep offline don't count towards this. When the cache goes over the limit, Tenshu removes the files you opened longest ago — they stay in your account and download again when you open them.")
            }

            // Network policy (Wi-Fi only) moved to Control's Background Sync settings with the
            // preference service — SyncPreferencesUiState no longer carries it.

            if !model.state.offlineFiles.isEmpty {
                Section("Files kept offline") {
                    ForEach(model.state.offlineFiles, id: \.self) { path in
                        Text(path)
                            .swipeActions {
                                Button("Remove", role: .destructive) { model.remove(path) }
                            }
                    }
                }
            }
        }
        .navigationTitle("Sync Preferences")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
    }
}

@MainActor
final class SyncPrefsModel: ObservableObject {
    @Published var state: SyncPreferencesUiState

    private let vm = DriveSettingsSurface.shared.syncPreferences()

    init() { state = vm.uiState.value }

    func observe() async {
        for await s in vm.uiState { state = s }
    }

    func setKeepOffline(_ enabled: Bool) { vm.setKeepOfflineByDefault(enabled: enabled) }
    func remove(_ path: String) { vm.removeOfflineFile(path: path) }
    func setMaxLocalBytes(_ bytes: Int64) { vm.setMaxLocalBytes(bytes: bytes) }
}

// The offered ceilings come from shared Kotlin (`CacheCeilingChoices`) rather than a Swift
// copy, so the two platforms cannot drift into offering different values.
// ⚠ `values` bridges as `[KotlinLong]`; unwrap to `Int64` so the `Picker` tag type matches
// what `nearest(bytes:)` returns.
private let cacheCeilingChoices: [Int64] =
    CacheCeilingChoices.shared.values.map { $0.int64Value }

// ⚠ Formatted through the shared `formatByteSize`, never by interpolating the number into a
// `Text`: SwiftUI applies locale grouping to an interpolated integer, which is how a 2.1 GB
// region once printed as "2.100 MB" (ios-fit-timeline-maps-parity.md §3.3.1).
// ⚠ A Kotlin top-level function exports on a file *facade* class whose members are Swift
// statics — `ByteSizeFormatKt.formatByteSize(…)`, with NO `.shared`. A Kotlin `object` like
// `CacheCeilingChoices` is the opposite and does need it.
private func cacheCeilingLabel(_ bytes: Int64) -> String {
    bytes <= CacheCeilingChoices.shared.NO_LIMIT
        ? "No limit"
        : ByteSizeFormatKt.formatByteSize(sizeInBytes: bytes)
}
