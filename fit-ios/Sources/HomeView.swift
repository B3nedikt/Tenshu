import HealthKit
import SwiftUI
import TenshuSdk

// Home (fit-app.md §2 screen 1): today's goal ring + stat strip, the four week cards, and the
// latest workout. Bound to `HomeViewModel` via `FitSurface`.
//
// ⚠ Home scrolls as ONE page (fit-app.md §18.16): the navigation bar is hidden and the "Home" title
// plus the TODAY/date block are the first rows of the scroll content, so they leave with it and come
// back only at the top. A large title would not do it — it collapses into an inline bar that stays
// pinned. ⚠ Home only: History and the other tabs keep their bar on purpose (a list reads better with
// its header in place). The bar is hidden in all four states, each of which draws the title, so
// moving between states never makes the header jump.

struct HomeView: View {
    @StateObject private var model = HomeModel()
    /// Jump to the Record tab — the empty state's only call to action.
    var onStartRecording: () -> Void = {}
    /// Push a route onto Home's stack — the week cards' chart taps need it (see [WeekTrendCard]).
    var open: (FitRoute) -> Void = { _ in }

    var body: some View {
        content
            // Still set: it is the back button's label on every screen Home pushes.
            .navigationTitle("Home")
            .toolbar(.hidden, for: .navigationBar)
            // The grouped page colour, so the cards (secondarySystemGroupedBackground) stand off
            // it — on the plain systemBackground a light-mode card was white on white.
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            // With no bar, scrolled content would run under the clock: a strip of page colour
            // covers the status-bar area.
            .overlay(alignment: .top) {
                Color(.systemGroupedBackground)
                    .ignoresSafeArea(edges: .top)
                    .frame(height: 0)
            }
            .task { await model.observe() }
    }

    /// The page header: "Home", then the TODAY eyebrow and the date once a day has loaded — the twin
    /// of Compose's bar title + date block, but inside the scroll.
    private func header(date: Kotlinx_datetimeLocalDate?) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Home").font(.largeTitle.bold())
                .accessibilityAddTraits(.isHeader)
            if let date {
                Text("TODAY")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(.top, 8)
                Text(Self.longDate(date))
                    .font(.title3.weight(.semibold))
                    .padding(.top, 2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// "Sunday, November 1" in the user's locale.
    static func longDate(_ d: Kotlinx_datetimeLocalDate) -> String {
        var c = DateComponents()
        c.year = Int(d.year); c.month = Int(d.monthNumber); c.day = Int(d.dayOfMonth)
        guard let date = isoCalendar.date(from: c) else { return "" }
        return longDateFormatter.string(from: date)
    }

    private static let longDateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.setLocalizedDateFormatFromTemplate("EEEEMMMMd")
        return f
    }()

    // Four states, not two (mirrors Compose `HomeScreen`): a day with data, a read that FAILED, a
    // *loaded* store that simply holds nothing, and the first frame before the repository has
    // emitted at all. Collapsing the last two — which is what a bare `today == nil` check did —
    // left an empty store spinning forever, the one state where the user most needs to be told why
    // the screen is blank.
    //
    // ⚠ `.failed` is checked BEFORE `.loaded`, exactly as Compose orders them: both arrive with no
    // aggregates, and the empty state is a claim ("you have no activity — record one or connect a
    // source") that a failed read has no standing to make. On iOS this is the *common* path, not an
    // edge case: `NotConnectedFitRepository` reports `.failed` whenever no live Control client is
    // installed.
    @ViewBuilder private var content: some View {
        if let today = model.state?.today {
            loaded(today)
        } else if model.state?.load == .failed {
            unavailableState
        } else if model.state?.load == .loaded {
            emptyState
        } else {
            titled { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) }
        }
    }

    /// A state with no scroll of its own still wears the page title, pinned above it.
    private func titled<Inner: View>(@ViewBuilder _ inner: () -> Inner) -> some View {
        VStack(spacing: 0) {
            header(date: nil).padding(.horizontal, 20).padding(.top, 8)
            inner()
        }
    }

    /// No retry button: the reads are reactive subscriptions that retry themselves, and the shell
    /// already carries the "Can't reach Tenshu Control" banner when that is the cause.
    private var unavailableState: some View {
        titled {
            Text("Your activity couldn't be loaded. Still trying…")
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(32)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func loaded(_ today: DailyAggregate) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                header(date: today.date).padding(.top, 8)
                // Above the ring: data that is stale, partial or fake shouldn't be discovered
                // *below* the numbers it undermines. Renders nothing when the import is healthy.
                if let status = model.state?.importStatus {
                    ImportStatusBanner(status: status, onRetry: model.retryImport)
                }
                ringCard(today: today, goals: model.state?.goals)
                statStrip(today: today)
                weekCharts
                if let workout = model.state?.latestWorkout {
                    latestWorkoutCard(workout)
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 20)
        }
    }

    // Loaded with no aggregates at all — no imported or recorded activity yet. Guide the user
    // instead of rendering a blank tab: the banner says *why* it is empty, which the empty state
    // alone cannot — "no data" and "no permission" look the same from here.
    private var emptyState: some View {
        ScrollView {
            header(date: nil).padding(.horizontal, 20).padding(.top, 8)
            VStack(spacing: 0) {
                if let status = model.state?.importStatus {
                    ImportStatusBanner(status: status, onRetry: model.retryImport)
                        .padding(.bottom, 24)
                }
                ZStack {
                    Circle().fill(Color(.secondarySystemGroupedBackground)).frame(width: 72, height: 72)
                    Image(systemName: "figure.walk").font(.title).foregroundStyle(.secondary)
                }
                Text("No activity yet")
                    .font(.title3.weight(.semibold))
                    .padding(.top, 20)
                // ⚠⚠ Two honest variants, never sample data. Without a health store (an iPad on
                // iPadOS 16) the importer offers no source at all, so the copy says so instead of
                // promising a connection that cannot exist. With one, the import is offered as a
                // button — and that tap is the ONLY thing that may put the HealthKit consent sheet
                // up (App Review 5.1.3): the unattended refresh waits until it has been answered.
                Group {
                    if healthKitAvailable {
                        Text("Record a workout or import from Apple Health, and your day will show up here.")
                    } else {
                        Text("Apple Health isn't available on this device. Record a workout or import a file to get started.")
                    }
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
                Button(action: onStartRecording) {
                    Label("Record a workout", systemImage: "plus")
                        .font(.headline)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding(.top, 24)
                if healthKitAvailable {
                    Button(action: model.retryImport) {
                        Label("Import from Apple Health", systemImage: "heart.text.square")
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .padding(.top, 8)
                } else {
                    NavigationLink(value: FitRoute.batchImport) {
                        Label("Import a file", systemImage: "square.and.arrow.down")
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .padding(.top, 8)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 32)
            .padding(.vertical, 40)
        }
    }

    /// Whether this device class has a health store at all — false on iPadOS 16 and older iPads.
    private var healthKitAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    private func ringCard(today: DailyAggregate, goals: Goals?) -> some View {
        let goal = Int(goals?.activeMinutes ?? 30)
        let value = Int(today.activeMinutes)
        // The week-over-week delta lives on the Active minutes week card now (fit-app.md §18.18),
        // the one series HomeViewModel computes it for — as on Compose.
        return GoalRing(value: value, goal: goal, tint: .orange)
            .frame(width: 180, height: 180)
            .overlay {
                VStack(spacing: 2) {
                    Text("\(value)").font(.system(size: 44, weight: .bold, design: .rounded))
                    Text("of \(goal) active min").font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 8)
    }

    /// Each value opens its series' detail (fit-app.md §18.18) — steps, distance, energy burned.
    private func statStrip(today: DailyAggregate) -> some View {
        HStack {
            statLink(.steps) { StatBlock(value: "\(today.steps)", label: "steps") }
            Divider().frame(height: 32)
            statLink(.distance) { StatBlock(value: FitUnitFormat.distance(Int(today.distanceM)), label: "distance") }
            Divider().frame(height: 32)
            statLink(.activeEnergy) { StatBlock(value: FitUnitFormat.energy(Int(today.activeEnergyKj)), label: "energy") }
        }
        .padding(.vertical, 12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    private func statLink<Label: View>(_ metric: CategoryMetric, @ViewBuilder label: () -> Label) -> some View {
        NavigationLink(value: FitRoute.category(id: .activity, metric: metric)) {
            label().contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint("Details")
    }

    /// Home's week charts, one card per daily series, in Google Fit's order (fit-app.md §18.18):
    /// energy burned, steps, active minutes, distance. Each whole card opens that series' detail.
    @ViewBuilder private var weekCharts: some View {
        let week = model.state?.week ?? []
        let goals = model.state?.goals
        let dates = week.map { $0.date }
        WeekTrendCard(title: "Energy", metric: .activeEnergy, open: open,
                      values: week.map { Int($0.activeEnergyKj) }, goal: 0, dates: dates,
                      label: { FitUnitFormat.energy($0) })
        WeekTrendCard(title: "Steps", metric: .steps, open: open,
                      values: week.map { Int($0.steps) }, goal: Int(goals?.steps ?? 0), dates: dates,
                      label: { "\($0.formatted()) steps" })
        WeekTrendCard(title: "Active minutes", metric: .activeMinutes, open: open,
                      values: week.map { Int($0.activeMinutes) }, goal: Int(goals?.activeMinutes ?? 30),
                      dates: dates, label: { "\($0) min" },
                      deltaPercent: model.state?.weekDeltaPercent?.intValue)
        WeekTrendCard(title: "Distance", metric: .distance, open: open,
                      values: week.map { Int($0.distanceM) }, goal: 0, dates: dates,
                      label: { FitUnitFormat.distance($0) })
    }

    private func latestWorkoutCard(_ workout: Workout) -> some View {
        NavigationLink(value: FitRoute.workout(id: workout.workoutId)) {
            HStack(spacing: 14) {
                ZStack {
                    Circle().fill(workout.activityType.tint.opacity(0.18)).frame(width: 44, height: 44)
                    Image(systemName: workout.activityType.sfSymbol).foregroundStyle(workout.activityType.tint)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(workout.title).font(.body.weight(.medium))
                    Text(workoutSubtitle(workout)).font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
            }
            .padding(14)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
    }

    private func workoutSubtitle(_ w: Workout) -> String {
        var parts = [FitUnitFormat.duration(Int(w.durationSec))]
        if let d = w.distanceM { parts.append(FitUnitFormat.distance(d.intValue)) }
        return parts.joined(separator: " · ")
    }
}

/// One week card: title, an optional week-over-week delta, a › and the 7-day [TrendBars] — the twin
/// of Compose's `WeekTrendCard`, so the four cannot drift apart in style.
///
/// ⚠ The **whole card** opens the detail, as in Google Fit, and that does not fight the chart's
/// scrubbing — but it takes two paths. The chart is a UIKit view, so a SwiftUI button around it never
/// sees a touch that lands on the plot (checked on the simulator: a `NavigationLink` card opened from
/// its title row only). So the card is a button AND the chart reports its own tap, which
/// `ScrubRecognizer` fires only once its hold has failed; a hold-then-slide scrubs, a swipe scrolls.
/// Both push through [open], which skips a route already on top — so if a future iOS delivers the tap
/// to both, it still pushes once.
/// ⚠ Not `navigationDestination(isPresented:)` per card: with four in one stack, a tap on the Energy
/// plot opened Steps on the simulator.
struct WeekTrendCard: View {
    let title: LocalizedStringKey
    let metric: CategoryMetric
    let open: (FitRoute) -> Void
    let values: [Int]
    let goal: Int
    let dates: [Kotlinx_datetimeLocalDate]
    let label: (Int) -> String
    var deltaPercent: Int? = nil

    private var route: FitRoute { .category(id: .activity, metric: metric) }

    var body: some View {
        Button { open(route) } label: {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    Text(title).font(.headline).foregroundStyle(.primary)
                    Spacer(minLength: 0)
                    if let delta = deltaPercent {
                        Label("\(delta >= 0 ? "+" : "")\(delta)% vs last week",
                              systemImage: delta >= 0 ? "arrow.up.right" : "arrow.down.right")
                            .font(.caption).foregroundStyle(delta >= 0 ? Color.green : Color.secondary)
                    }
                    Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                }
                .padding(.bottom, 42) // room for the bar callout, which floats above the plot
                TrendBars(values: values, goal: goal, tint: .orange, dates: dates, label: label,
                          onTap: { open(route) })
                    .frame(height: 96)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
            .contentShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .accessibilityHint("Details")
    }
}

// MARK: - Charts (Canvas)

struct GoalRing: View {
    let value: Int
    let goal: Int
    var tint: Color = .orange
    /// 12, not 16: a thick full ring was the heaviest mass on Home (fit-app.md §18.17, Compose's
    /// `Ring` 18 → 12 dp). Onboarding's ring shares the default.
    var lineWidth: CGFloat = 12

    var body: some View {
        let fraction = goal > 0 ? min(Double(value) / Double(goal), 1.0) : 0
        ZStack {
            Circle().stroke(tint.opacity(0.15), lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: fraction)
                .stroke(tint, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
    }
}

/// The trend bar chart, with scrubbing — the twin of the Compose `TrendBars` in `:fit-shared-ui`.
/// Today (the last bar) is the accent and the rest are the same accent at 24 % alpha, so the chart
/// reads as one colour with today emphasised (fit-app.md §18.17); a held bar takes the accent from
/// today, so one bar is lit at a time. A dashed line marks the goal when there is one.
/// Touch and hold briefly, then slide: a pill above the plot shows the bar under the finger
/// (value via [label], and date), joined to it by a dotted guide; lifting the finger hides it.
/// The short hold is what lets a swipe that starts on the chart still scroll the page (the
/// Stocks app does the same). The touch → bar lookup is the shared `ChartMath.barIndexAt`.
/// ⚠ The hold is a UIKit recognizer ([ScrubRecognizer]), not a SwiftUI gesture: any SwiftUI drag
/// gesture here, exclusive or simultaneous, swallows a vertical swipe that starts on the chart.
struct TrendBars: View {
    let values: [Int]
    let goal: Int
    var tint: Color = .orange
    /// One date per value, oldest first; empty disables selection.
    var dates: [Kotlinx_datetimeLocalDate] = []
    var label: (Int) -> String = { "\($0)" }
    /// A plain tap on the plot (no hold) — the chart's UIKit view keeps it from any enclosing button.
    var onTap: (() -> Void)? = nil

    @State private var selected: Int?
    @State private var pillWidth: CGFloat = 0
    private let preferredGap: CGFloat = 8

    var body: some View {
        GeometryReader { geo in
            let maxV = max(values.max() ?? 1, goal, 1)
            let width = geo.size.width
            // Narrower gaps for dense ranges (Year = 365 bars), so every column keeps a width.
            let gap = CGFloat(ChartMath.shared.fittedGap(width: Float(width), count: Int32(values.count), preferred: Float(preferredGap)))
            ZStack(alignment: .topLeading) {
                HStack(alignment: .bottom, spacing: gap) {
                    ForEach(Array(values.enumerated()), id: \.offset) { i, v in
                        let h = geo.size.height * CGFloat(Double(v) / Double(maxV))
                        RoundedRectangle(cornerRadius: 4)
                            .fill(barColor(index: i))
                            .frame(height: max(h, 3))
                            .frame(maxWidth: .infinity)
                    }
                }
                .frame(width: width, height: geo.size.height, alignment: .bottom)

                if goal > 0 {
                    // Same reference as the bars (`ChartMath.goalLineFraction`).
                    let y = geo.size.height * (1 - CGFloat(Double(goal) / Double(maxV)))
                    Path { p in
                        p.move(to: CGPoint(x: 0, y: y))
                        p.addLine(to: CGPoint(x: width, y: y))
                    }
                    .stroke(Color.secondary.opacity(0.6),
                            style: StrokeStyle(lineWidth: 1.5, lineCap: .round, dash: [4, 4]))
                }

                if let sel = selected, sel < values.count, sel < dates.count {
                    let x = centerX(sel, width: width, gap: gap)
                    let barTop = geo.size.height - max(geo.size.height * CGFloat(Double(values[sel]) / Double(maxV)), 3)
                    Path { p in
                        p.move(to: CGPoint(x: x, y: -6))
                        p.addLine(to: CGPoint(x: x, y: barTop - 3))
                    }
                    .stroke(Color.secondary, style: StrokeStyle(lineWidth: 1, dash: [3, 3]))

                    callout(value: label(values[sel]), date: Self.format(dates[sel]))
                        .position(
                            x: min(max(x, pillWidth / 2), max(width - pillWidth / 2, pillWidth / 2)),
                            y: -22
                        )
                }
            }
            .contentShape(Rectangle())
            .overlay(ScrubRecognizer(onTap: onTap) { x in
                guard !dates.isEmpty else { return }
                let i = x.flatMap { index(at: $0, width: width, gap: gap) }
                // A tick per bar is useful across a week; across a month or a year it is a buzz.
                if let i, i != selected, values.count <= 31 { UISelectionFeedbackGenerator().selectionChanged() }
                selected = i
            })
        }
        // Pinned left-to-right: `ScrubRecognizer` reports physical x and the hit-test counts from
        // the left, while an RTL environment would mirror the HStack. (Fit ships en/de only, so
        // iOS lays it out LTR today; mirroring all three — as Compose's TrendBars does — is the
        // alternative if an RTL localization lands.)
        .environment(\.layoutDirection, .leftToRight)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilitySummary)
    }

    private func callout(value: String, date: String) -> some View {
        HStack(spacing: 6) {
            Text(value).font(.subheadline.weight(.semibold)).foregroundStyle(tint)
            Text(date).font(.subheadline).foregroundStyle(.secondary)
        }
        .lineLimit(1)
        .fixedSize()
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(.regularMaterial, in: Capsule())
        .shadow(color: .black.opacity(0.12), radius: 4, y: 1)
        .opacity(pillWidth > 0 ? 1 : 0) // until measured, the edge clamp has no width to use
        .background(GeometryReader { g in
            Color.clear
                .onAppear { pillWidth = g.size.width }
                .onChange(of: g.size.width) { pillWidth = $0 }
        })
    }

    private func barColor(index: Int) -> Color {
        // A translucent accent, not an opaque grey: it tints whichever card the chart sits on.
        let lit = selected.map { $0 == index } ?? (index == values.count - 1)
        return lit ? tint : tint.opacity(0.24)
    }

    private func index(at x: CGFloat, width: CGFloat, gap: CGFloat) -> Int? {
        ChartMath.shared.barIndexAt(x: Float(x), width: Float(width), count: Int32(values.count), gap: Float(gap), rtl: false)?.intValue
    }

    private func centerX(_ i: Int, width: CGFloat, gap: CGFloat) -> CGFloat {
        CGFloat(ChartMath.shared.barCenterX(index: Int32(i), width: Float(width), count: Int32(values.count), gap: Float(gap), rtl: false))
    }

    private var accessibilitySummary: String {
        // Day by day for a week or a month; a year of 365 entries is not something to listen to.
        guard !dates.isEmpty, values.count <= 31 else { return "Bar chart, \(values.count) days" }
        return zip(values, dates).map { "\(Self.format($0.1)): \(label($0.0))" }.joined(separator: ", ")
    }

    /// "Sat, Oct 31" in the user's locale.
    static func format(_ d: Kotlinx_datetimeLocalDate) -> String {
        var c = DateComponents()
        c.year = Int(d.year); c.month = Int(d.monthNumber); c.day = Int(d.dayOfMonth)
        guard let date = isoCalendar.date(from: c) else { return "" }
        return formatter.string(from: date)
    }

    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.setLocalizedDateFormatFromTemplate("EEEMMMd")
        return f
    }()
}

/// Hold-then-slide over the view it overlays: reports the finger's x while held, then nil once
/// it lifts or the touch is cancelled. A UILongPressGestureRecognizer keeps reporting `.changed`
/// as the finger moves after the hold, and fails if it moves first — so a swipe still reaches the
/// enclosing scroll view.
struct ScrubRecognizer: UIViewRepresentable {
    /// A tap that never became a hold. ⚠ It waits for the hold to fail, so a scrub never also taps.
    var onTap: (() -> Void)? = nil
    let onScrub: (CGFloat?) -> Void

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        let press = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handle(_:)))
        press.minimumPressDuration = 0.15
        view.addGestureRecognizer(press)
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        tap.require(toFail: press)
        view.addGestureRecognizer(tap)
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.onScrub = onScrub
        context.coordinator.onTap = onTap
    }

    func makeCoordinator() -> Coordinator { Coordinator(onScrub: onScrub, onTap: onTap) }

    final class Coordinator: NSObject {
        var onScrub: (CGFloat?) -> Void
        var onTap: (() -> Void)?
        init(onScrub: @escaping (CGFloat?) -> Void, onTap: (() -> Void)?) {
            self.onScrub = onScrub
            self.onTap = onTap
        }

        @objc func tapped(_ g: UITapGestureRecognizer) {
            if g.state == .ended { onTap?() }
        }

        @objc func handle(_ g: UILongPressGestureRecognizer) {
            switch g.state {
            case .began, .changed: onScrub(g.location(in: g.view).x)
            default: onScrub(nil)
            }
        }
    }
}

// MARK: - Model

@MainActor
final class HomeModel: ObservableObject {
    @Published var state: HomeUiState?
    private let vm = FitSurface.shared.homeViewModel()

    func observe() async {
        for await value in vm.uiState { state = value }
    }

    /// Re-runs the import behind the attention banner (`ImportStatusBanner`'s Try again). On a
    /// platform with a permission prompt bound the VM asks for the grant instead; iOS binds none,
    /// so this is always the plain re-import.
    func retryImport() { vm.retryImport() }
}
