import SwiftUI
import TenshuSdk

// The Activity tab (home-and-mia-apps.md §3): the chronological feed with a kind filter and day
// grouping. Filtering is view-local (the same events stream the shared VM exposes); rows format
// their time via Foundation from the message's epoch instant.
//
// ⚠ The feed is automation run history (`RoutineRun` receipts) only — device events have no store
// and voice has no pipeline (`SyncedHomeActivityRepository`), and the empty state says so per
// filter rather than implying a quiet home.
struct ActivityView: View {
    @StateObject private var model = ActivityModel()
    @State private var filter: ActivityKind? = nil

    var body: some View {
        Group {
            if model.state?.loaded != true {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                let groups = groupedByDay()
                if groups.isEmpty {
                    HomeEmptyState(symbol: "clock.arrow.circlepath", title: "No activity", message: emptyMessage)
                } else {
                    List {
                        ForEach(groups, id: \.day) { group in
                            Section(group.label) {
                                ForEach(group.events, id: \.id) { event in
                                    EventRow(event: event)
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Activity")
        .toolbar {
            Picker("Filter", selection: $filter) {
                Text("All").tag(ActivityKind?.none)
                Text("Devices").tag(ActivityKind?.some(.device))
                Text("Automations").tag(ActivityKind?.some(.automation))
                Text("Voice").tag(ActivityKind?.some(.voice))
            }
            .pickerStyle(.menu)
        }
        .task { await model.observe() }
    }

    /// The same three sentences the Compose screen uses.
    private var emptyMessage: String {
        if filter == .device {
            return "Device events aren't recorded yet, so nothing from your devices appears here."
        }
        if filter == .voice {
            return "Voice isn't available yet, so nothing from Mia appears here."
        }
        return "Nothing here yet — automation runs will show up in this feed."
    }

    private struct DayGroup {
        let day: String
        let label: String
        let events: [ActivityEvent]
    }

    private func groupedByDay() -> [DayGroup] {
        let events = (model.state?.events ?? []).filter { filter == nil || $0.kind == filter }
        let calendar = Calendar.current
        var groups: [DayGroup] = []
        for event in events {
            let date = event.date
            let day = calendar.startOfDay(for: date)
            let key = ISO8601DateFormatter().string(from: day)
            let label: String = calendar.isDateInToday(date) ? "Today"
                : calendar.isDateInYesterday(date) ? "Yesterday"
                : date.formatted(date: .abbreviated, time: .omitted)
            if groups.last?.day == key {
                groups[groups.count - 1] = DayGroup(day: key, label: label, events: groups.last!.events + [event])
            } else {
                groups.append(DayGroup(day: key, label: label, events: [event]))
            }
        }
        return groups
    }
}

private struct EventRow: View {
    let event: ActivityEvent

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol).foregroundStyle(.tint).frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(event.title).font(.body.weight(.medium))
                Text(event.detail).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            Text(event.date.formatted(date: .omitted, time: .shortened))
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 2)
    }

    private var symbol: String {
        switch event.kind {
        case .device: return "sensor"
        case .automation: return "sparkles"
        case .voice: return "bubble.left"
        default: return "circle"
        }
    }
}

// Avoids naming the ObjC-bridged type of `kotlin.time.Instant` (SKIE/K2 rename hazard):
// the epoch accessor is called on the property, so only `ActivityEvent` is spelled out.
extension ActivityEvent {
    var date: Date { Date(timeIntervalSince1970: Double(at.toEpochMilliseconds()) / 1000) }
}

@MainActor
final class ActivityModel: ObservableObject {
    @Published var state: ActivityUiState?
    private let vm = HomeSurface.shared.activityViewModel()

    func observe() async { for await v in vm.uiState { state = v } }
}
