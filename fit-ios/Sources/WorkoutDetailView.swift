import SwiftUI
import TenshuSdk

// Workout detail (fit-app.md §2 screen 6): the stat grid, an abstract route + HR chart, laps, and
// the provenance footer. Bound to `WorkoutDetailViewModel` via `FitSurface`.
struct WorkoutDetailView: View {
    @StateObject private var model: WorkoutModel

    init(workoutId: String) {
        _model = StateObject(wrappedValue: WorkoutModel(workoutId: workoutId))
    }

    var body: some View {
        Group {
            switch model.state.map({ onEnum(of: $0) }) {
            case .some(.loaded(let loaded)): loadedBody(loaded.workout)
            case .some(.notFound): FitEmptyState(title: "Workout not found", systemImage: "questionmark.circle")
            // ⚠⚠ `failed` MUST be spelled out. It is not `notFound` — saying a workout is missing
            // when the read merely did not finish is the lie `WorkoutRead` was introduced to stop —
            // and it must not fall to `default` either, because `default` is the spinner, so a
            // failed read would spin here forever. On this device the commonest cause is no live
            // Control (`NotConnectedFitRepository`), which is precisely a failure, not an absence.
            case .some(.failed):
                VStack(spacing: 12) {
                    FitEmptyState(title: "Couldn't load this workout", systemImage: "exclamationmark.triangle")
                    Button("Try again") { model.retry() }
                        .buttonStyle(.bordered)
                        .padding(.bottom, 24)
                }
            default: ProgressView()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
    }

    @ViewBuilder private func loadedBody(_ w: Workout) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(spacing: 12) {
                    Image(systemName: w.activityType.sfSymbol)
                        .font(.title2).foregroundStyle(w.activityType.tint)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(w.title).font(.title3.weight(.semibold))
                        Text("\(w.start.hhmm)–\(w.end.hhmm)").font(.caption).foregroundStyle(.secondary)
                    }
                }

                statGrid(w)

                // Real GPS gets the map; everything else keeps the schematic sketch. The branch is
                // the same one `:fit-shared-ui`'s Compose `WorkoutDetailScreen` takes
                // (`workout.geoRoute.size >= 2`), and it is the *only* place the sketch is replaced:
                // most workouts arrive from Health Connect / Google Fit with no per-fix track, and
                // for those there is nothing to put on a map.
                if w.geoRoute.count >= 2 {
                    WorkoutRouteMapView(route: w.geoRoute, sketchFallback: w.route)
                } else if w.hasRoute && !w.route.isEmpty {
                    RoutePathView(points: w.route).frame(height: 180)
                        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
                }

                if !w.hrSeries.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Label("Heart rate", systemImage: "heart.fill").font(.subheadline).foregroundStyle(.red)
                        HrChartView(series: w.hrSeries).frame(height: 120)
                    }
                }

                if !w.laps.isEmpty { lapsTable(w) }

                provenanceFooter(w)
            }
            .padding(20)
        }
    }

    /// The twin of Compose `ProvenanceFooter`: which device captured this, over what window, and
    /// where the data now lives. A bare device Label answered only the first of those — and read as
    /// a stray stat rather than the record's footer.
    ///
    /// ⚠ A blank `device` falls back to where the workout came from — not to "Recorded in Fit",
    /// which a file import carries no more of a device than a recording (`deviceLabel` in
    /// `FitFormatting.kt` makes the same substitution).
    private func provenanceFooter(_ w: Workout) -> some View {
        VStack(spacing: 4) {
            Text((w.device.isEmpty ? w.source.provenance : w.device).uppercased())
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
            Text("Captured \(w.start.hhmm)–\(w.end.hhmm) · Original file retained")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            FitTrustCue().padding(.top, 8)
        }
        .frame(maxWidth: .infinity)
        .multilineTextAlignment(.center)
        .padding(.top, 4)
    }

    private func statGrid(_ w: Workout) -> some View {
        let columns = [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())]
        return LazyVGrid(columns: columns, spacing: 16) {
            StatBlock(value: FitUnitFormat.duration(Int(w.durationSec)), label: "time")
            if let d = w.distanceM { StatBlock(value: FitUnitFormat.distance(d.intValue), label: "distance") }
            if let e = w.activeEnergyKj { StatBlock(value: FitUnitFormat.energy(e.intValue), label: "energy") }
            if let p = w.effectivePaceSecPerKm { StatBlock(value: FitUnitFormat.pace(p.intValue), label: "pace") }
            if let hr = w.avgHr { StatBlock(value: "\(hr.intValue)", label: "avg bpm", tint: .red) }
            if let mx = w.maxHr { StatBlock(value: "\(mx.intValue)", label: "max bpm", tint: .red) }
        }
        .padding(.vertical, 12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    private func lapsTable(_ w: Workout) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Laps").font(.headline)
            ForEach(Array(w.laps.enumerated()), id: \.offset) { _, lap in
                HStack {
                    Text("\(Int(lap.index))").frame(width: 28, alignment: .leading).foregroundStyle(.secondary)
                    Text(FitUnitFormat.distance(Int(lap.distanceM)))
                    Spacer()
                    Text(FitUnitFormat.pace(Int(lap.paceSecPerKm)))
                    Text("\(Int(lap.avgHr)) bpm").foregroundStyle(.red).frame(width: 72, alignment: .trailing)
                }
                .font(.subheadline.monospacedDigit())
                .padding(.vertical, 4)
                Divider()
            }
        }
    }
}

// MARK: - Charts

// Route polyline over normalized 0..1 points (NOT map coordinates — an abstract shape).
struct RoutePathView: View {
    let points: [RoutePoint]
    var body: some View {
        GeometryReader { geo in
            Path { path in
                for (i, pt) in points.enumerated() {
                    let p = CGPoint(x: CGFloat(pt.x) * geo.size.width, y: CGFloat(pt.y) * geo.size.height)
                    if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
                }
            }
            .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
            .padding(16)
        }
    }
}

// HR series as a line; x = time index, y = bpm normalized within [min-headroom, max+headroom].
struct HrChartView: View {
    let series: [HrPoint]
    var body: some View {
        GeometryReader { geo in
            let bpms = series.map { Int($0.bpm) }
            let lo = (bpms.min() ?? 60) - 8
            let hi = (bpms.max() ?? 160) + 8
            let span = max(hi - lo, 1)
            Path { path in
                for (i, pt) in series.enumerated() {
                    let x = series.count > 1 ? CGFloat(i) / CGFloat(series.count - 1) * geo.size.width : 0
                    let y = geo.size.height * (1 - CGFloat(Int(pt.bpm) - lo) / CGFloat(span))
                    let p = CGPoint(x: x, y: y)
                    if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
                }
            }
            .stroke(Color.red, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
        }
    }
}

@MainActor
final class WorkoutModel: ObservableObject {
    @Published var state: WorkoutDetailUiState?
    private let vm: WorkoutDetailViewModel
    init(workoutId: String) { vm = FitSurface.shared.workoutViewModel(workoutId: workoutId) }
    func observe() async { for await v in vm.uiState { state = v } }
    /// Re-runs the detail read behind the `failed` state's retry button.
    func retry() { vm.retry() }
}
