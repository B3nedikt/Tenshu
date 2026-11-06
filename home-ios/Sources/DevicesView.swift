import SwiftUI
import TenshuSdk

// The Home tab (home-and-mia-apps.md §3): per-kind device state rows — connectivity, light,
// storage usage (with gauge), thermostat, sensor. Bound to `DevicesViewModel` via `HomeSurface`.
//
// ⚠⚠ READ-ONLY, by decision: a row shows the device's LAST-SYNCED state and offers
// nothing to tap. Power, level and permit-join pairing — `DevicesViewModel.setDevicePower` /
// `setDeviceLevel` / `startPairing` — belong to the "Home on iOS — control" slice
// (home-and-mia-apps.md §8). `HomeSurface` builds this view model without a control or pairing
// source, so those calls are inert here even if something wired them.
//
// ⚠ No add / remove / reorder: those were affordances of a mock household, not of real devices.
struct DevicesView: View {
    @StateObject private var model = DevicesModel()

    var body: some View {
        Group {
            if let state = model.state, state.loaded {
                if state.devices.isEmpty {
                    // ⚠ An honest empty state, not demo rows: this realm has no hub reporting and
                    // no bridged devices. It says where devices come from, because nothing on
                    // this phone can add one.
                    HomeEmptyState(
                        symbol: "house",
                        title: "No devices yet",
                        message: "Devices appear here once a Tenshu Hub in your realm reports them. Adding devices from iPhone isn't available yet."
                    )
                } else {
                    List {
                        ForEach(state.devices, id: \.id) { device in
                            DeviceRow(device: device, roomName: model.roomName(device))
                        }
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle("Home")
        .task { await model.observe() }
    }
}

struct DeviceRow: View {
    let device: Device
    let roomName: String?

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: device.kind.sfSymbol)
                .foregroundStyle(.tint)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 3) {
                Text(device.name).font(.body.weight(.medium))
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(disconnected ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary))
                if let storage = device.state as? DeviceStateStorage {
                    ProgressView(value: Double(storage.usedFraction))
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }

    private var disconnected: Bool {
        (device.state as? DeviceStateConnectivity).map { !$0.online } ?? false
    }

    private var subtitle: String {
        let room = roomName ?? "Unassigned"
        return "\(room) · \(stateLine)"
    }

    private var stateLine: String {
        switch device.state {
        case let connectivity as DeviceStateConnectivity:
            return connectivity.online ? "Connected" : "Disconnected"
        case let light as DeviceStateLight:
            return light.on ? "On · \(light.brightnessPercent)%" : "Off"
        case let storage as DeviceStateStorage:
            let free = HomeModelKt.formatByteSize(bytes: storage.freeBytes)
            let total = HomeModelKt.formatByteSize(bytes: storage.totalBytes)
            return "\(free) free of \(total)"
        case let thermostat as DeviceStateThermostat:
            let current = HomeModelKt.formatDeciC(deciC: thermostat.currentDeciC)
            let target = HomeModelKt.formatDeciC(deciC: thermostat.targetDeciC)
            return "\(current) · set to \(target)"
        case let sensor as DeviceStateSensorReading:
            return sensor.summary
        default:
            return ""
        }
    }
}

// ⚠ No add-device form: a device built from a name/kind/room form is a shape the proxy-backed
// model has no concept of, because real devices arrive by permit-join pairing. Wire
// `DevicesViewModel.startPairing` / `stopPairing` instead.

extension DeviceKind {
    var sfSymbol: String {
        switch self {
        case .hub: return "server.rack"
        case .storage: return "externaldrive"
        case .speaker: return "hifispeaker"
        case .light: return "lightbulb"
        case .camera: return "video"
        case .thermostat: return "thermometer.medium"
        case .sensor: return "sensor"
        default: return "questionmark.circle"
        }
    }

    var label: String {
        switch self {
        case .hub: return "Tenshu Hub"
        case .storage: return "Storage"
        case .speaker: return "Speaker"
        case .light: return "Light"
        case .camera: return "Camera"
        case .thermostat: return "Thermostat"
        case .sensor: return "Sensor"
        default: return "Device"
        }
    }
}

@MainActor
final class DevicesModel: ObservableObject {
    @Published var state: DevicesUiState?
    // ⚠ Built when the view first appears, which `ControlSetUpGate` guarantees is after
    // `HomeLocalRuntime.install()` — the surface swap happens at view-model construction.
    private let vm = HomeSurface.shared.devicesViewModel()

    func observe() async { for await v in vm.uiState { state = v } }

    func roomName(_ device: Device) -> String? { vm.roomName(device: device) }
}
