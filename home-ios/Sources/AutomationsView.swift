import SwiftUI
import TenshuSdk

// The Automations tab (home-and-mia-apps.md §5): the rule list with a human-readable summary line
// (trigger · first action), device ids resolved to household names. Bound to
// `AutomationsViewModel` via `HomeSurface`.
//
// ⚠⚠ READ-ONLY, by decision (2026-10-02). The enable switch and swipe-to-delete that stood here
// are GONE, not disabled: toggle, remove, run-now and the editor belong to the later "Home on iOS
// — control" slice (home-and-mia-apps.md §8). Do not restore `vm.setEnabled` /
// `vm.removeAutomation` call sites before that slice swaps the runtime's composition — the
// repository behind this view model throws on every write.
//
// ⚠ Run-now never arrives on iPhone at all: routines do not execute here (`RoutineExecution.NEVER`).
struct AutomationsView: View {
    @StateObject private var model = AutomationsModel()

    var body: some View {
        Group {
            if let state = model.state, state.loaded {
                if state.automations.isEmpty {
                    HomeEmptyState(
                        symbol: "sparkles",
                        title: "No automations yet",
                        message: "Automations you create in Home on Android or desktop appear here. Editing from iPhone isn't available yet."
                    )
                } else {
                    List {
                        ForEach(state.automations, id: \.id) { automation in
                            AutomationRow(automation: automation, summary: model.summary(automation))
                        }
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle("Automations")
        .task { await model.observe() }
    }
}

private struct AutomationRow: View {
    let automation: Automation
    let summary: String

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(automation.name).font(.body.weight(.medium))
                Text(summary).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            // ⚠ A label, not a switch: it reports the synced `enabled` field and cannot change it.
            Text(automation.enabled ? "On" : "Off")
                .font(.caption.weight(.medium))
                .foregroundStyle(automation.enabled ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

@MainActor
final class AutomationsModel: ObservableObject {
    @Published var state: AutomationsUiState?
    // ⚠ `private`: the view model's `setEnabled` / `removeAutomation` are not this slice's to call.
    private let vm = HomeSurface.shared.automationsViewModel()

    func observe() async { for await v in vm.uiState { state = v } }

    /// "When you arrive home · Turn on Sofa lamp" — the Swift twin of the shared
    /// Compose summary layer, over the same VM device-name resolution.
    func summary(_ automation: Automation) -> String {
        var parts = [triggerText(automation.trigger)]
        if let action = automation.actions.first {
            parts.append(actionText(action))
        }
        return parts.joined(separator: " · ")
    }

    private func deviceName(_ id: String) -> String {
        vm.deviceName(deviceId: id) ?? "Unknown device"
    }

    private func triggerText(_ trigger: Trigger) -> String {
        switch trigger {
        case let schedule as TriggerSchedule:
            return "At \(AutomationModelKt.formatMinutesOfDay(minutesOfDay: schedule.minutesOfDay))"
        case let sun as TriggerSun:
            let event = sun.event == .sunrise ? "sunrise" : "sunset"
            if sun.offsetMinutes < 0 { return "\(-sun.offsetMinutes) min before \(event)" }
            if sun.offsetMinutes > 0 { return "\(sun.offsetMinutes) min after \(event)" }
            return "At \(event)"
        case let geofence as TriggerGeofence:
            return geofence.direction == .arrive ? "When you arrive home" : "When you leave home"
        case let deviceEvent as TriggerDeviceEvent:
            return "\(deviceName(deviceEvent.deviceId)): \(eventText(deviceEvent.event))"
        case let voice as TriggerVoicePhrase:
            return "When you say \u{201C}\(voice.phrase)\u{201D}"
        default:
            return ""
        }
    }

    private func eventText(_ event: DeviceEventKind) -> String {
        switch event {
        case .motionDetected: return "Motion detected"
        case .personDetected: return "Person detected"
        case .opened: return "Opened"
        case .closed: return "Closed"
        default: return "Event"
        }
    }

    private func actionText(_ action: Action) -> String {
        switch action {
        case let command as ActionDeviceCommand:
            return "\(command.turnOn ? "Turn on" : "Turn off") \(deviceName(command.deviceId))"
        case let notify as ActionNotify:
            return "Notify: \(notify.message)"
        case is ActionRunAutomation:
            return "Run automation"
        default:
            return ""
        }
    }
}
