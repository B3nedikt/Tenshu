import SwiftUI
import TenshuSdk

// Devices — the first **read** surface over the real Control proxy (Control-on-iOS C4-RT / RT-4,
// docs/proposals/ios-control-handover.md). Binds `SyncDashboardViewModel`: its `refresh()` issues
// the live device-roster relation + the real `getSyncStatus()` op through the in-process proxy (RT-3),
// so a populated list confirms a live proxy read round-trip — the check before device-pairing (C6)
// layers on. Pre-genesis the list is empty (no realm yet); once set up, the genesised device shows
// with a "This device" marker.
//
// The Kotlin `Device` composites bridge poorly to Swift, so `ControlSurface.syncDevices(state:)`
// flattens them Kotlin-side; here they map once more into a Swift-native, `Identifiable` row so the
// SwiftUI list never references the SKIE-bridged type directly.
private struct DeviceRow: Identifiable {
    let id: String
    let name: String
    let isCurrent: Bool
    let isAuthorized: Bool
    /// Pre-formatted Kotlin-side so this roster and the Compose one bucket quietness identically.
    /// Empty for the current device.
    let lastSeen: String
}

/// A device the one being removed admitted (§7j.4).
private struct AdmitteeRow: Identifiable {
    let id: String
    let name: String
}

/// What the removal sheet is asking about: the device, plus the devices it admitted.
private struct PendingRemoval: Identifiable {
    let device: DeviceRow
    let admittees: [AdmitteeRow]
    let unlistedCount: Int
    var id: String { device.id }
}

@MainActor
final class SyncDashboardModel: ObservableObject {
    @Published fileprivate var devices: [DeviceRow] = []
    @Published var isSyncEnabled = false
    @Published var isLoading = false
    /// Surfaced by the VM when a revocation is refused — otherwise the list would simply
    /// re-render unchanged and a refusal would look like a slow success.
    @Published var error: String?

    /// A non-failure outcome that still has to be said out loud: `Restore access` acts on the
    /// OTHER device, on its next sync tick, so there is nothing in this list to read the result
    /// off — a silent success and a dead button look identical.
    @Published var notice: String?

    /// For the Settings Account row — the list itself stays file-private.
    var deviceCount: Int { devices.count }

    private let viewModel = ControlSurface.shared.syncDashboardViewModel()

    init() {
        apply(viewModel.uiState.value)
    }

    func observe() async {
        for await state in viewModel.uiState {
            apply(state)
        }
    }

    func refresh() {
        viewModel.refresh()
    }

    /// Remove a device from the realm. The VM re-reads the roster itself, so the row
    /// disappears on the next emitted state — there is nothing to reconcile here.
    fileprivate func revoke(_ device: DeviceRow) {
        viewModel.revokeDevice(deviceId: device.id)
    }

    /// Remove `device` and, in the same action, the admittees the user chose to stop trusting
    /// (`admission-revocation-names-the-capability.md` §7j.4).
    fileprivate func revoke(_ device: DeviceRow, alsoRemove: [String]) {
        viewModel.revokeDeviceAndAdmittees(deviceId: device.id, alsoRemove: alsoRemove)
    }

    /// The devices `device` admitted (§7j.4), from the same shared function the Compose dialog uses.
    fileprivate func admittees(of device: DeviceRow) -> [AdmitteeRow] {
        ControlSurface.shared.removalAdmitteeRows(state: viewModel.uiState.value, deviceId: device.id).map {
            AdmitteeRow(id: $0.id, name: $0.name)
        }
    }

    /// Roster rows that record no admitter — the sheet says so rather than imply it is complete.
    fileprivate func unlistedCount(of device: DeviceRow) -> Int {
        Int(ControlSurface.shared.removalUnlistedCount(state: viewModel.uiState.value, deviceId: device.id))
    }

    /// Restore a peer's relay registration (`ReadmitDevice`). The stranded device needs to do
    /// nothing itself — it keeps its keypair and simply starts being accepted on its next tick.
    fileprivate func readmit(_ device: DeviceRow) {
        viewModel.readmitDevice(deviceId: device.id)
    }

    func clearError() {
        viewModel.clearError()
    }

    func clearNotice() {
        viewModel.clearNotice()
    }

    private func apply(_ state: SyncDashboardUiState) {
        devices = ControlSurface.shared.syncDevices(state: state).map {
            DeviceRow(
                id: $0.id,
                name: $0.name,
                isCurrent: $0.isCurrent,
                isAuthorized: $0.isAuthorized,
                lastSeen: $0.lastSeen
            )
        }
        isSyncEnabled = state.isSyncEnabled
        isLoading = state.isLoading
        error = state.error
        notice = state.notice
    }
}

struct SyncDashboardView: View {
    @StateObject private var model = SyncDashboardModel()
    /// The row awaiting confirmation. Revocation is irreversible (the device must be paired
    /// again from scratch), so it is never one tap.
    @State private var pendingRevoke: DeviceRow?
    /// §7j.4: a removal whose device admitted others — asked on a sheet, because a confirmation
    /// dialog cannot carry the per-device keep/remove choice.
    @State private var pendingRemoval: PendingRemoval?

    var body: some View {
        List {
            Section("Sync") {
                LabeledContent("Status", value: model.isSyncEnabled ? "Enabled" : "Local only")
            }
            Section("Devices") {
                if model.devices.isEmpty {
                    Text(model.isLoading ? "Loading…" : "No devices yet.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(model.devices) { device in
                        deviceRow(device)
                    }
                }
            }
        }
        .navigationTitle("Devices")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    model.refresh()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
            }
        }
        .confirmationDialog(
            "Remove this device?",
            isPresented: Binding(get: { pendingRevoke != nil }, set: { if !$0 { pendingRevoke = nil } }),
            titleVisibility: .visible,
            presenting: pendingRevoke
        ) { device in
            Button("Remove \(device.name.isEmpty ? "device" : device.name)", role: .destructive) {
                // Ph-reauth (`control-keep-device-unlocked.md` §6.1, RA-3): prove presence before an
                // irreversible removal. ⚠ On the confirm rather than on opening the sheet — this is
                // where the user learns the removal is final, so prompting earlier would ask them to
                // authorise something they have not yet read. `Reauth.confirm` passes through on a
                // device that cannot ask, so this never becomes a way to lose the ability to remove
                // a lost device.
                Task {
                    guard await Reauth.confirm(reason: ReauthStrings.revokeDeviceReason) else {
                        await MainActor.run { pendingRevoke = nil }
                        return
                    }
                    await MainActor.run {
                        model.revoke(device)
                        pendingRevoke = nil
                    }
                }
            }
            Button("Cancel", role: .cancel) { pendingRevoke = nil }
        } message: { device in
            // The id is the only thing separating two devices with the same platform name —
            // which is exactly the case this affordance exists for.
            // ⚠⚠ Must NOT promise the device can be added back — the Compose twin's copy was fixed
            // for this and iOS was missed. Under option F
            // (`admission-revocation-names-the-capability.md` §4.1) removal is final **for that
            // identity**: the hardware can be set up again, but it joins as a NEW device and does
            // not recover its name, its per-device settings or its place in this list. Copy that
            // implies recovery strands exactly the user who trusted it.
            Text("It loses access to your realm. This cannot be undone — the hardware can be set up again, but it joins as a new device.\n\n\(device.id)")
        }
        .sheet(item: $pendingRemoval) { removal in
            RemoveWithAdmitteesSheet(removal: removal) { alsoRemove in
                // Same presence gate as the dialog above, on the confirm.
                Task {
                    guard await Reauth.confirm(reason: ReauthStrings.revokeDeviceReason) else {
                        await MainActor.run { pendingRemoval = nil }
                        return
                    }
                    await MainActor.run {
                        model.revoke(removal.device, alsoRemove: alsoRemove)
                        pendingRemoval = nil
                    }
                }
            } onCancel: {
                pendingRemoval = nil
            }
        }
        // ⚠ ONE alert for both outcomes, not two stacked ones. The VM sets `error` XOR `notice`,
        // and a second `.alert` on the same view is the SwiftUI construct that silently wins or
        // loses by ordering — there is already a confirmation alert above this one.
        //
        // ⚠ The failure title is neutral because that channel now carries BOTH a refused removal
        // and a refused `Restore access`, and each op supplies its own explaining sentence as the
        // message. A title naming one of them would mislabel the other.
        .alert(
            model.notice != nil ? "Access restored" : "That didn't work",
            isPresented: Binding(
                get: { model.error != nil || model.notice != nil },
                set: { if !$0 { model.clearError(); model.clearNotice() } }
            ),
            presenting: model.error ?? model.notice
        ) { _ in
            Button("OK", role: .cancel) {
                model.clearError()
                model.clearNotice()
            }
        } message: { message in
            Text(message)
        }
        .task { await model.observe() }
    }

    /// Open the right confirmation: the plain dialog, or — when the device admitted others — the
    /// sheet that asks about each of them (§7j.4).
    private func requestRemoval(_ device: DeviceRow) {
        let admittees = model.admittees(of: device)
        if admittees.isEmpty {
            pendingRevoke = device
        } else {
            pendingRemoval = PendingRemoval(
                device: device,
                admittees: admittees,
                unlistedCount: model.unlistedCount(of: device)
            )
        }
    }

    @ViewBuilder private func deviceRow(_ device: DeviceRow) -> some View {
        HStack(spacing: 12) {
            Image(systemName: device.isCurrent ? "ipad.and.iphone" : "laptopcomputer")
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(device.name.isEmpty ? "Unnamed device" : device.name)
                if device.isCurrent {
                    Text("This device")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                // Mirrors the Compose roster (`DeviceItem.kt`), which has always shown the id:
                // two devices of the same model carry the same platform name, so the name alone
                // cannot tell them apart.
                Text(device.id)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                // What separates a live peer from a row whose identity is gone. An observation,
                // never a verdict — a long-offline device reads the same way, which is why nothing
                // acts on it automatically.
                if !device.lastSeen.isEmpty {
                    Text(device.lastSeen)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            if !device.isAuthorized {
                Text("Pending")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
            // Same gate as the Compose roster (`DeviceItem.kt`): an unauthorised row is
            // approved, not revoked, and this device may never revoke itself (that is
            // "Forget this device" — a different act with different consequences).
            if device.isAuthorized && !device.isCurrent {
                // `ReadmitDevice`: the exit for a peer the relay has stopped recognising, which
                // cannot repair itself unless it holds the master identity — a pairing joiner
                // never does. ⚠ Offered on every peer row rather than only ones that look
                // stranded: only the refused device can observe its own 401, and from here a
                // stranded peer and a long-offline one are the same absent last-seen. The op is
                // idempotent, so a speculative tap costs a no-op.
                //
                // ⚠ No confirmation, unlike Remove: this restores access the roster already
                // asserts and grants nothing new. Putting the same friction on a repair as on a
                // destruction teaches that the two are equally dangerous.
                Button {
                    model.readmit(device)
                } label: {
                    Image(systemName: "arrow.clockwise.circle")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Restore access for \(device.name)")

                // ⚠ Visible, not swipe-only. A swipe action would be the tidier iOS idiom, but
                // it is undiscoverable, and on an iPhone-only realm this row is the *only*
                // place a device can be removed at all — there is no second Control to fall
                // back to. `.borderless` keeps the tap on the button rather than the row.
                Button {
                    requestRemoval(device)
                } label: {
                    Image(systemName: "trash")
                        .foregroundStyle(.red)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Remove \(device.name)")
            }
        }
        .swipeActions(edge: .trailing) {
            if device.isAuthorized && !device.isCurrent {
                Button(role: .destructive) {
                    requestRemoval(device)
                } label: {
                    Label("Remove", systemImage: "trash")
                }
            }
        }
    }
}

/// The removal confirmation for a device that admitted others
/// (`admission-revocation-names-the-capability.md` §7j.4). Since §7j the relay no longer refuses a
/// device because whoever added it was removed — so this is where a device a thief added through a
/// stolen one is caught. Each admittee is **kept by default**; untick to remove it too.
private struct RemoveWithAdmitteesSheet: View {
    let removal: PendingRemoval
    let onConfirm: ([String]) -> Void
    let onCancel: () -> Void

    /// What the user explicitly turned OFF — never a kept-set snapshot, so a device that was never
    /// offered can never be removed by default (the Compose twin's roster is live; keep the shapes alike).
    @State private var deselected: Set<String> = []
    /// The same acknowledgement the Compose dialog requires: removal is final for that identity.
    @State private var acknowledged = false

    init(removal: PendingRemoval, onConfirm: @escaping ([String]) -> Void, onCancel: @escaping () -> Void) {
        self.removal = removal
        self.onConfirm = onConfirm
        self.onCancel = onCancel
    }

    private var deviceName: String { removal.device.name.isEmpty ? "This device" : removal.device.name }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("\(deviceName) loses access to your realm. This cannot be undone — the hardware can be set up again, but it joins as a new device.")
                    Text(removal.device.id)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Section {
                    ForEach(removal.admittees) { admittee in
                        Toggle(
                            "Keep \(admittee.name.isEmpty ? admittee.id : admittee.name)",
                            isOn: Binding(
                                get: { !deselected.contains(admittee.id) },
                                set: { kept in if kept { deselected.remove(admittee.id) } else { deselected.insert(admittee.id) } }
                            )
                        )
                    }
                } header: {
                    Text("These devices report that \(deviceName) added them")
                } footer: {
                    // ⚠ Never imply the list is complete: who added a device is recorded by that
                    // device itself, so one can leave it blank. It stays removable from the list.
                    if removal.unlistedCount > 0 {
                        Text("Keep trusting them? Turn one off to remove it too. Each device reports this itself, so treat the list as a guide, not a guarantee. \(removal.unlistedCount) other devices don't say who added them, or name a device that isn't here, so they aren't listed; you can remove any of them from the device list.")
                    } else {
                        Text("Keep trusting them? Turn one off to remove it too. Each device reports this itself, so treat the list as a guide, not a guarantee.")
                    }
                }
                Section {
                    Toggle("I understand this cannot be undone", isOn: $acknowledged)
                }
            }
            .navigationTitle("Remove this device?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
                ToolbarItem(placement: .destructiveAction) {
                    Button("Remove", role: .destructive) {
                        onConfirm(removal.admittees.map(\.id).filter { deselected.contains($0) })
                    }
                    .disabled(!acknowledged)
                }
            }
        }
    }
}
