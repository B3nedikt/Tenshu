import SwiftUI
import TenshuSdk

// Category detail (fit-app.md §2 screen 10; §12 B1): driven by the tapped CategoryId. Activity and
// Sleep render a D/W/M/Y trend; Body and Vitals render a single latest reading (their store is
// latest-per-metric until §12 B2). Bound to `CategoryDetailViewModel` via `FitSurface`.
struct CategoryDetailView: View {
    @StateObject private var model: CategoryModel

    /// [metric] is one of Activity's series when Home opens it (fit-app.md §18.18); nil = the
    /// category's default (Browse).
    init(categoryId: CategoryId, metric: CategoryMetric? = nil) {
        _model = StateObject(wrappedValue: CategoryModel(categoryId: categoryId, metric: metric))
    }

    private let ranges: [ChartRange] = [.day, .week, .month, .year]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // Vitals' reading picker — above the mode switch, so an Empty metric keeps it.
                if let choices = model.state?.metricChoices, !choices.isEmpty {
                    Picker("Reading", selection: metricBinding) {
                        ForEach(choices, id: \.self) { m in Text(switcherLabel(m)).tag(m) }
                    }
                    .pickerStyle(.segmented)
                }
                switch model.state?.mode {
                case .series: seriesContent
                case .latest: latestContent
                case .cycle: cycleContent
                default: emptyContent
                }
                dataSourcesRow
            }
            .padding(20)
        }
        // The grouped page colour, so the cards stand off it (white on white in light mode before).
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
    }

    @ViewBuilder private var seriesContent: some View {
        Picker("Range", selection: rangeBinding) {
            ForEach(ranges, id: \.self) { r in Text(label(r)).tag(r) }
        }
        .pickerStyle(.segmented)

        HStack {
            StatBlock(value: formatValue(model.state?.dailyAverage ?? 0), label: "daily avg")
            if (model.state?.goal ?? 0) > 0 {
                Divider().frame(height: 32)
                StatBlock(value: "\(Int(model.state?.goal ?? 0))", label: "goal")
            }
        }
        .padding(.vertical, 12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))

        TrendBars(values: (model.state?.values ?? []).map { $0.intValue },
                  goal: Int(model.state?.goal ?? 0), tint: .orange,
                  // `entries` are the same days as `values`, newest first.
                  dates: (model.state?.entries ?? []).reversed().map { $0.date },
                  label: { calloutValue(Int32($0)) })
            .frame(height: 140)
            .padding(.top, 40) // room for the bar callout, which floats above the plot

        if let entries = model.state?.entries, !entries.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text("Entries").font(.headline)
                ForEach(Array(entries.enumerated()), id: \.offset) { _, entry in
                    HStack {
                        Text(dayLabel(Int(entry.daysAgo))).font(.subheadline)
                        Spacer()
                        Text(formatValue(entry.value)).font(.body.weight(.medium))
                        Text(entry.source.label).font(.caption2).foregroundStyle(.tertiary)
                    }
                    .padding(.vertical, 6)
                    Divider()
                }
            }
        }
    }

    @ViewBuilder private var latestContent: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("LATEST READING").font(.caption).foregroundStyle(.secondary)
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(formatLatest(model.state?.latestValue ?? 0))
                    .font(.system(size: 40, weight: .medium))
                Text(model.state?.latestUnit ?? "").font(.body).foregroundStyle(.secondary)
            }
            HStack {
                Text(updatedLabel).font(.caption).foregroundStyle(.secondary)
                Spacer()
                if let source = model.state?.latestSource {
                    Text(source.label).font(.caption2).foregroundStyle(.tertiary)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))

        // The trend line over the retained readings (§12 B2) — a value-ranged axis, not zero-anchored bars.
        if let trend = model.state?.trend, trend.count > 1 {
            SampleTrend(points: trend, tint: .orange)
                .frame(height: 96)
                .padding(16)
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
        }

        // The retained readings (§12 B2) — worth a list once there's more than the current value.
        if let history = model.state?.history, history.count > 1 {
            VStack(alignment: .leading, spacing: 4) {
                Text("History").font(.headline)
                ForEach(Array(history.enumerated()), id: \.offset) { _, reading in
                    HStack {
                        Text(dayLabel(Int(reading.daysAgo))).font(.subheadline)
                        Spacer()
                        Text("\(formatLatest(reading.value)) \(model.state?.latestUnit ?? "")")
                            .font(.body.weight(.medium))
                        Text(reading.source.label).font(.caption2).foregroundStyle(.tertiary)
                    }
                    .padding(.vertical, 6)
                    Divider()
                }
            }
        }
    }

    // Cycle (§12 B3b): a "Cycle day N" header + a log of recent bleeding days. The card is
    // data-driven (shown only when there are logged days), so this branch always has content.
    @ViewBuilder private var cycleContent: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("CURRENT").font(.caption).foregroundStyle(.secondary)
            Text("Cycle day \(Int(model.state?.cycleDay ?? 0))")
                .font(.system(size: 40, weight: .medium))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))

        if let log = model.state?.cycleLog, !log.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text("Recent days").font(.headline)
                ForEach(Array(log.enumerated()), id: \.offset) { _, row in
                    HStack {
                        Text(dayLabel(Int(row.daysAgo))).font(.subheadline)
                        Spacer()
                        if row.periodStart {
                            Text("Period start").font(.caption2).foregroundStyle(Color.accentColor)
                        }
                        Text(flowLabel(row.flow)).font(.body.weight(.medium))
                    }
                    .padding(.vertical, 6)
                    Divider()
                }
            }
        }
    }

    // The provenance footer of the screen — the twin of Compose `DataSourcesRow`. Rendered under
    // every mode (including Empty), because "where did these numbers come from?" is exactly the
    // question a screen with *no* numbers raises too. Informational in v1: the Compose row carries
    // no click handler either, since the source list lives on Profile.
    private var dataSourcesRow: some View {
        HStack(spacing: 12) {
            Image(systemName: (model.state?.categoryId ?? .activity).sfSymbol)
                .foregroundStyle(.secondary)
                .frame(width: 20)
            VStack(alignment: .leading, spacing: 2) {
                Text("Data sources").font(.subheadline.weight(.semibold))
                Text("Your connected data sources").font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
        }
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func flowLabel(_ flow: FlowLevel) -> String {
        switch flow {
        case .light: return "Light"
        case .medium: return "Medium"
        case .heavy: return "Heavy"
        default: return "Logged"
        }
    }

    @ViewBuilder private var emptyContent: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("No data yet").font(.headline)
            Text("Import or record data to see it here.").font(.subheadline).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    private var title: String {
        switch model.state?.metric {
        case .energy: return "Energy"
        // ⚠⚠ Energy BURNED, not Nutrition's energy eaten (`.energy`) — never merge the two.
        case .activeEnergy: return "Energy burned"
        case .activeMinutes: return "Active minutes"
        case .distance: return "Distance"
        case .cycle: return "Cycle"
        case .sleepDuration: return "Sleep"
        case .weight: return "Weight"
        case .restingHeartRate: return "Resting heart rate"
        case .bloodOxygen: return "Blood oxygen"
        case .respiratoryRate: return "Respiratory rate"
        default: return "Steps"
        }
    }

    // Series value: sleep minutes → duration; energy kJ → kcal; distance metres → km/m; everything
    // else a plain count.
    private func formatValue(_ value: Int32) -> String {
        switch model.state?.metric {
        case .sleepDuration: return FitUnitFormat.duration(Int(value) * 60)
        case .energy, .activeEnergy: return FitUnitFormat.energy(Int(value))
        case .distance: return FitUnitFormat.distance(Int(value))
        default: return "\(Int(value))"
        }
    }

    // The bar callout's value, with a unit where the metric has one ("10,423 steps", "42 min").
    private func calloutValue(_ value: Int32) -> String {
        switch model.state?.metric {
        case .steps: return "\(Int(value).formatted()) steps"
        case .activeMinutes: return "\(Int(value)) min"
        default: return formatValue(value)
        }
    }

    // Weight and respiratory rate one-decimal; SpO₂ rounded (97.6 → 98); resting HR whole.
    private func formatLatest(_ value: Double) -> String {
        switch model.state?.metric {
        case .weight, .respiratoryRate: return String(format: "%.1f", value)
        case .bloodOxygen: return "\(Int(value.rounded()))"
        default: return "\(Int(value))"
        }
    }

    // Short labels: three must fit one segmented control at phone width.
    private func switcherLabel(_ metric: CategoryMetric) -> String {
        switch metric {
        case .restingHeartRate: return "Resting HR"
        case .bloodOxygen: return "Blood oxygen"
        case .respiratoryRate: return "Breathing"
        default: return ""
        }
    }

    private var metricBinding: Binding<CategoryMetric> {
        Binding(get: { model.state?.metric ?? .restingHeartRate }, set: { model.selectMetric($0) })
    }

    private var updatedLabel: String {
        guard let updated = model.state?.latestUpdated else { return "" }
        switch onEnum(of: updated) {
        case .today: return "Updated today"
        case .daysAgo(let u): return u.days == 1 ? "Updated 1 day ago" : "Updated \(Int(u.days)) days ago"
        case .imported: return "Imported"
        case .never: return ""
        @unknown default: return ""
        }
    }

    private var rangeBinding: Binding<ChartRange> {
        Binding(get: { model.state?.range ?? .week }, set: { model.selectRange($0) })
    }

    private func label(_ r: ChartRange) -> String {
        switch r {
        case .day: return "Day"
        case .week: return "Week"
        case .month: return "Month"
        case .year: return "Year"
        @unknown default: return ""
        }
    }

    private func dayLabel(_ daysAgo: Int) -> String {
        switch daysAgo {
        case 0: return "Today"
        case 1: return "Yesterday"
        default: return "\(daysAgo) days ago"
        }
    }
}

@MainActor
final class CategoryModel: ObservableObject {
    @Published var state: CategoryDetailUiState?
    private let vm: CategoryDetailViewModel
    init(categoryId: CategoryId, metric: CategoryMetric?) {
        vm = metric.map { FitSurface.shared.categoryViewModel(categoryId: categoryId, metric: $0) }
            ?? FitSurface.shared.categoryViewModel(categoryId: categoryId)
    }
    func observe() async { for await v in vm.uiState { state = v } }
    func selectRange(_ r: ChartRange) { vm.selectRange(newRange: r) }
    func selectMetric(_ m: CategoryMetric) { vm.selectMetric(newMetric: m) }
}
