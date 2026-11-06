import SwiftUI
import TenshuSdk

// Onboarding (fit-app.md D5): Welcome (pick an import source) → Permissions (per-category consent)
// → Importing (live progress). Detection is data, not expect/actual — the mock offers file-import +
// start-fresh alongside any detected platform source. Bound to `OnboardingViewModel`.
struct OnboardingView: View {
    // About-you drafts, held here so a saved value shows on the row behind the sheet. The shared
    // profile flow would also carry them, but it round-trips through the store — this keeps the
    // row responsive without waiting for that.
    @State private var aboutYouEditing: ProfileEditorField?
    @State private var aboutYouBirthDate: Kotlinx_datetimeLocalDate?
    @State private var aboutYouSex: BiologicalSex?
    @State private var aboutYouHeight: Int?
    @State private var aboutYouWeight: Double?

    let onDone: () -> Void
    @StateObject private var model = OnboardingModel()

    var body: some View {
        VStack {
            switch model.state?.step {
            case .some(.welcome): welcome
            case .some(.permissions): permissions
            case .some(.importing): importing
            // Android-only today: the shared VM reaches this step only when the importer reports
            // `HistoryAccess.Grantable`, and HealthKit has no deep-history grant to ask for (its
            // authorisation already covers all history), so `HealthKitImporter` reports
            // `Unsupported`. Handled explicitly all the same — the `default: ProgressView()` below
            // would render this state as a spinner that never resolves, and the next person to give
            // iOS a real `historyAccess` should not have to discover that on a device.
            case .some(.deepHistory): importing
            case .some(.aboutYou): aboutYou
            default: ProgressView()
            }
        }
        .padding(24)
        .task { await model.observe() }
    }

    // MARK: Welcome

    private var welcome: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "heart.text.square.fill").font(.system(size: 56)).foregroundStyle(.orange)
            Text("Welcome to Fit").font(.largeTitle.weight(.bold))
            Text("Private health & fitness, on your terms. Bring your history in — or start fresh.")
                .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Spacer()
            VStack(spacing: 10) {
                ForEach(Array((model.state?.sources ?? []).enumerated()), id: \.offset) { _, source in
                    Button { model.chooseSource(source) } label: {
                        HStack {
                            Image(systemName: sourceIcon(source))
                            Text(sourceLabel(source)).fontWeight(.medium)
                            Spacer()
                            Image(systemName: "chevron.right").font(.caption)
                        }
                        .padding(16)
                        .frame(maxWidth: .infinity)
                        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
                        .foregroundStyle(.primary)
                    }
                }
            }
        }
    }

    // MARK: Permissions

    private var permissions: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("What to import").font(.title2.weight(.semibold))
            Text("Choose which categories to bring in. You can change this later.")
                .font(.subheadline).foregroundStyle(.secondary)
            ScrollView {
                VStack(spacing: 8) {
                    ForEach(Array((model.state?.categories ?? []).enumerated()), id: \.offset) { _, category in
                        let selected = model.selection.contains(category.id)
                        Button { model.toggleCategory(category.id) } label: {
                            HStack {
                                Image(systemName: category.id.sfSymbol).foregroundStyle(category.id.tint).frame(width: 26)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(category.id.title).foregroundStyle(.primary)
                                    Text("\(Int(category.estimatedCount)) records").font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(selected ? category.id.tint : Color.secondary)
                            }
                            .padding(14)
                            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            HStack {
                Button("Back") { model.back() }.buttonStyle(.bordered)
                Button("Import") { model.importSelected() }
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
                    .disabled(model.selection.isEmpty)
            }
        }
    }

    // MARK: About you

    /// The values the import could not supply (fit-app.md §18.6) — on iOS that is whatever
    /// HealthKit was not authorised for, which is usually nothing, so this screen is normally
    /// never reached. Every field is optional and Continue is always enabled: a skipped answer
    /// costs calorie accuracy and nothing else, so nothing here blocks entry to the app.
    private var aboutYou: some View {
        let missing = model.state?.missingAboutYouFields ?? []
        return VStack(alignment: .leading, spacing: 20) {
            Text("A bit about you").font(.largeTitle.weight(.bold))
            Text("These let us estimate calories properly. Your health app could not tell us, and you can skip any of them — we will use a neutral estimate instead.")
                .font(.callout).foregroundStyle(.secondary)

            VStack(spacing: 0) {
                if missing.contains(.birthDate) {
                    aboutYouRow("Birth date", value: aboutYouBirthDate?.display) { aboutYouEditing = .birthDate }
                }
                if missing.contains(.sex) {
                    aboutYouRow("Sex", value: aboutYouSex.map(sexTitle)) { aboutYouEditing = .sex }
                }
                if missing.contains(.height) {
                    aboutYouRow("Height", value: aboutYouHeight.map { "\($0) cm" }) { aboutYouEditing = .height }
                }
                if missing.contains(.weight) {
                    aboutYouRow("Weight", value: aboutYouWeight.map { String(format: "%.1f kg", $0) }) { aboutYouEditing = .weight }
                }
            }

            Spacer()
            Button("Continue") { model.finishAboutYou() }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
        }
        .sheet(item: $aboutYouEditing) { field in
            // The same sheet Profile uses — one implementation, two callers (see ProfileEditors).
            ProfileEditorSheet(
                field: field,
                values: ProfileEditorValues(
                    units: model.state?.profile?.units ?? .metric,
                    birthDate: aboutYouBirthDate,
                    sex: aboutYouSex,
                    heightCm: aboutYouHeight,
                    weightKg: aboutYouWeight,
                ),
                commit: { edit in
                    // The draft is mirrored locally so the row updates immediately; the write
                    // itself round-trips through the store like any other profile edit.
                    switch edit {
                    case .birthDate(let v): aboutYouBirthDate = v
                    case .sex(let v): aboutYouSex = v
                    case .height(let v): aboutYouHeight = v
                    case .weight(let v): aboutYouWeight = v
                    case .name: break
                    }
                    model.apply(edit)
                },
            )
        }
    }

    private func aboutYouRow(_ label: String, value: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(label).foregroundStyle(.primary)
                Spacer()
                Text(value ?? "Add").foregroundStyle(value == nil ? Color.accentColor : .secondary)
            }
            .padding(.vertical, 12)
        }
        .buttonStyle(.plain)
    }

    private func sexTitle(_ sex: BiologicalSex) -> String {
        switch sex {
        case .female: return "Female"
        case .male: return "Male"
        case .other: return "Other"
        default: return "Not set"
        }
    }

    // MARK: Importing

    private var importing: some View {
        let complete = model.state?.importComplete ?? false
        return VStack(spacing: 24) {
            Spacer()
            GoalRing(value: Int((model.state?.overallFraction ?? 0) * 100), goal: 100, tint: .orange)
                .frame(width: 140, height: 140)
                .overlay {
                    if complete {
                        Image(systemName: "checkmark")
                            .font(.system(size: 44, weight: .bold))
                            .foregroundStyle(.orange)
                    } else {
                        Text("\(Int((model.state?.overallFraction ?? 0) * 100))%").font(.title.weight(.bold))
                    }
                }
            Text(complete ? "Import complete" : "Importing your history…").font(.headline)
            VStack(spacing: 8) {
                ForEach(Array((model.state?.progress ?? []).enumerated()), id: \.offset) { _, p in
                    HStack {
                        Image(systemName: p.id.sfSymbol).foregroundStyle(p.id.tint).frame(width: 24)
                        Text(p.id.title)
                        Spacer()
                        Text(p.done ? "Done" : "\(Int(p.imported))/\(Int(p.total))")
                            .font(.caption).foregroundStyle(p.done ? .green : .secondary)
                    }
                    .font(.subheadline)
                }
            }
            Spacer()
            Button { model.enterApp(); onDone() } label: {
                Text(complete ? "Continue" : "Start now").font(.headline).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).controlSize(.large)
        }
    }

    private func sourceIcon(_ s: ImportSource) -> String {
        switch s {
        case .healthConnect: return "heart.circle.fill"
        case .appleHealth: return "heart.fill"
        case .importFile: return "doc.fill"
        case .startFresh: return "sparkles"
        @unknown default: return "square"
        }
    }
    private func sourceLabel(_ s: ImportSource) -> String {
        switch s {
        case .healthConnect: return "Health Connect"
        case .appleHealth: return "Apple Health"
        case .importFile: return "Import a file"
        case .startFresh: return "Start fresh"
        @unknown default: return "Source"
        }
    }
}

@MainActor
final class OnboardingModel: ObservableObject {
    @Published var state: OnboardingUiState?
    @Published var selection: Set<CategoryId> = []
    private let vm = FitSurface.shared.onboardingViewModel()

    func observe() async {
        for await v in vm.uiState { state = v; selection = v.selection }
    }

    func chooseSource(_ s: ImportSource) { vm.chooseSource(source: s) }
    func toggleCategory(_ id: CategoryId) { vm.toggleCategory(id: id) }
    func importSelected() { vm.importSelected() }
    func back() { vm.back() }
    func enterApp() { vm.enterApp() }
    func finishAboutYou() { vm.finishAboutYou() }

    /// One entry point for the About-you editors, mirroring `ProfileModel.apply`.
    func apply(_ edit: ProfileEdit) {
        switch edit {
        case .birthDate(let v): vm.saveBirthDate(date: v)
        case .sex(let v): vm.saveSex(sex: v)
        case .height(let v): vm.saveHeight(centimetres: Int32(v))
        case .weight(let v): vm.saveWeight(kilograms: v)
        // Onboarding never asks for the name — it is an input to nothing (§18.6).
        case .name: break
        }
    }
}
