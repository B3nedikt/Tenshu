import SwiftUI
import TenshuSdk

// History (fit-app.md §2 screen 5): a week roll-up card, then workouts grouped by day (newest
// first). Bound to `HistoryViewModel` via `FitSurface`.
struct HistoryView: View {
    @StateObject private var model = HistoryModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                weekCard
                ForEach(Array((model.state?.days ?? []).enumerated()), id: \.offset) { _, group in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(dayLabel(group)).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                        ForEach(Array(group.workouts.enumerated()), id: \.offset) { _, workout in
                            NavigationLink(value: FitRoute.workout(id: workout.workoutId)) {
                                WorkoutRow(workout: workout)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                if (model.state?.days ?? []).isEmpty {
                    FitEmptyState(title: "No workouts yet", systemImage: "figure.run").frame(height: 200)
                }
            }
            .padding(20)
        }
        .navigationTitle("History")
        .task { await model.observe() }
    }

    private var weekCard: some View {
        HStack {
            StatBlock(value: "\(Int(model.state?.weekWorkoutCount ?? 0))", label: "workouts")
            Divider().frame(height: 32)
            StatBlock(value: FitUnitFormat.distance(Int(model.state?.weekDistanceM ?? 0)), label: "distance")
            Divider().frame(height: 32)
            StatBlock(value: FitUnitFormat.duration(Int(model.state?.weekDurationSec ?? 0)), label: "time")
        }
        .padding(.vertical, 14)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    private func dayLabel(_ group: HistoryDayGroup) -> String {
        switch Int(group.daysAgo) {
        case 0: return "Today"
        case 1: return "Yesterday"
        default: return "\(Int(group.daysAgo)) days ago"
        }
    }
}

// Reusable workout row (History + Home). Icon tinted by activity type, title + stat line.
struct WorkoutRow: View {
    let workout: Workout

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(workout.activityType.tint.opacity(0.18)).frame(width: 40, height: 40)
                Image(systemName: workout.activityType.sfSymbol).foregroundStyle(workout.activityType.tint)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(workout.title).font(.body.weight(.medium))
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            Text(workout.start.hhmm).font(.caption).foregroundStyle(.tertiary)
        }
        .padding(.vertical, 6)
    }

    private var subtitle: String {
        var parts = [FitUnitFormat.duration(Int(workout.durationSec))]
        if let d = workout.distanceM { parts.append(FitUnitFormat.distance(d.intValue)) }
        if let hr = workout.avgHr { parts.append("\(hr.intValue) bpm") }
        return parts.joined(separator: " · ")
    }
}

@MainActor
final class HistoryModel: ObservableObject {
    @Published var state: HistoryUiState?
    private let vm = FitSurface.shared.historyViewModel()
    func observe() async { for await v in vm.uiState { state = v } }
}
