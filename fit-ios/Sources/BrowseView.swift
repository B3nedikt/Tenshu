import SwiftUI
import TenshuSdk

// Browse (fit-app.md §2 screen 9): up to seven category cards (Cycle and Medical only with data) in a 2-column grid. Medical opens the
// read-only records screen; every other category opens the shared activity-trends detail (App.kt).
struct BrowseView: View {
    @StateObject private var model = BrowseModel()
    var onImport: () -> Void = {}

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    // A store-backed category (anything but Nutrition/Cycle, which have no store) still empty → the
    // user has data to connect (§12 B2b). ⚠ Not Medical on iOS: no source here can fill it (FHIR
    // needs the restricted health-records entitlement, HealthKitImporter), so counting it kept
    // "Connect a data source" up for good, beside a connected Apple Health.
    private var hasImportableGap: Bool {
        (model.state?.categories ?? []).contains {
            $0.isEmpty && $0.id != .nutrition && $0.id != .cycle && $0.id != .medical
        }
    }

    var body: some View {
        ScrollView {
            if hasImportableGap {
                Button(action: onImport) {
                    HStack(spacing: 12) {
                        Image(systemName: "square.and.arrow.down")
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Connect a data source").font(.subheadline.weight(.semibold))
                            Text("Import your activity, sleep and body data.").font(.caption)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption2)
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity)
                    .background(Color.accentColor.opacity(0.15), in: RoundedRectangle(cornerRadius: 16))
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 16)
                .padding(.top, 16)
            }
            LazyVGrid(columns: columns, spacing: 12) {
                ForEach(Array((model.state?.categories ?? []).enumerated()), id: \.offset) { _, summary in
                    NavigationLink(value: summary.id == .medical ? FitRoute.medical : FitRoute.category(id: summary.id)) {
                        CategoryCard(summary: summary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(16)
        }
        .navigationTitle("Browse")
        .task { await model.observe() }
    }
}

private struct CategoryCard: View {
    let summary: CategorySummary

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: summary.id.sfSymbol).foregroundStyle(summary.id.tint)
                Spacer()
                Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.tertiary)
            }
            Text(summary.id.title).font(.headline)
            Text(teaserText).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            Text(updatedText).font(.caption2).foregroundStyle(.tertiary)
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 110, alignment: .topLeading)
        .background(summary.id.tint.opacity(summary.isEmpty ? 0.05 : 0.12),
                    in: RoundedRectangle(cornerRadius: 16))
        .opacity(summary.isEmpty ? 0.6 : 1)
    }

    private var teaserText: String {
        switch onEnum(of: summary.teaser) {
        case .activeMinutesToday(let t): return "\(Int(t.minutes)) active min today"
        case .weight(let t): return String(format: "%.1f kg", t.kg)
        case .restingHeartRate(let t): return "\(Int(t.bpm)) bpm resting"
        case .bloodOxygen(let t): return "\(Int(t.percent))% blood oxygen"
        case .respiratoryRate(let t): return String(format: "%.1f breaths/min", t.perMinute)
        case .sleepDuration(let t): return FitUnitFormat.duration(Int(t.minutes) * 60)
        case .energy(let t): return "\(FitUnitFormat.energy(Int(t.kj))) today"
        case .cycleDay(let t): return "Cycle day \(Int(t.day))"
        case .recordCount(let t): return "\(Int(t.count)) records"
        case .noEntries: return "No entries yet"
        case .notSetUp: return "Not set up"
        @unknown default: return ""
        }
    }

    private var updatedText: String {
        switch onEnum(of: summary.updated) {
        case .today: return "Updated today"
        case .daysAgo(let u): return u.days == 1 ? "1 day ago" : "\(Int(u.days)) days ago"
        case .imported: return "Imported"
        case .never: return ""
        @unknown default: return ""
        }
    }
}

@MainActor
final class BrowseModel: ObservableObject {
    @Published var state: BrowseUiState?
    private let vm = FitSurface.shared.browseViewModel()
    func observe() async { for await v in vm.uiState { state = v } }
}
