import HealthKit
import SwiftUI
import TenshuSdk

// Profile (fit-app.md §2 screen 11): identity header, editable goals, a units toggle, and the
// connected import sources. Bound to `ProfileViewModel` via `FitSurface`.
struct ProfileView: View {
    @StateObject private var model = ProfileModel()
    /// The presented field editor; exactly one at a time (FP-4).
    @State private var editing: ProfileEditorField?

    var body: some View {
        Form {
            if let profile = model.state?.profile {
                Section {
                    // The header is the name's editor — a "Name" row under Personal data would be
                    // a second place to change what the top of the screen already shows (§18.11).
                    Button { editing = .name } label: {
                    HStack(spacing: 14) {
                        ZStack {
                            Circle().fill(Color.accentColor.opacity(0.18)).frame(width: 52, height: 52)
                            Image(systemName: "person.fill").foregroundStyle(Color.accentColor)
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text(displayName(profile)).font(.headline)
                            // Sex and height are independently unknown; a subtitle of
                            // "Not set · Not set" says nothing, so the missing halves — and the
                            // subtitle with them — simply drop out (Compose `Identity` does the same).
                            let subtitle = [sexText(profile.sex), profile.heightCm.map { _ in heightText(profile) }]
                                .compactMap { $0 }
                                .joined(separator: " · ")
                            if !subtitle.isEmpty {
                                Text(subtitle).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.primary)
                }

                // Personal data as rows, not a squeezed subtitle (Compose ProfileScreen "Personal
                // data"). Birth date was simply absent on iOS — and it is the one value here the
                // app cannot re-derive from a workout, so it never surfaced anywhere else.
                //
                // FP-4: every row is now an editor, so each earns its chevron (§18.11). Height and
                // weight write a `HealthSample`, not a setting — a typed reading joins the series
                // Health Connect writes instead of shadowing it (§18.2).
                Section("Personal data") {
                    EditableRow(label: "Height", value: heightText(profile)) { editing = .height }
                    EditableRow(label: "Weight", value: weightText(profile)) { editing = .weight }
                    EditableRow(label: "Birth date", value: profile.birthDate?.display ?? notSet) { editing = .birthDate }
                    EditableRow(label: "Sex", value: sexText(profile.sex) ?? notSet) { editing = .sex }
                }
            }

            Section("Daily goals") {
                Stepper("Active minutes: \(activeMinutes)", value: $activeMinutes, in: 5...240, step: 5)
                Stepper("Steps: \(steps)", value: $steps, in: 1000...30000, step: 500)
                Button("Save goals") { model.saveGoals(active: activeMinutes, steps: steps) }
                    .disabled(model.state == nil)
            }

            Section("Units") {
                Picker("Units", selection: unitsBinding) {
                    Text("Metric").tag(Units.metric)
                    Text("Imperial").tag(Units.imperial)
                }
                .pickerStyle(.segmented)
            }

            // DS-5 (`fit-data-sources-restructure.md` §4). Every row here IS a source and
            // opens its own screen; the actions that used to sit beside them — the Takeout
            // import, the deep-history grant — now live inside the thing they belong to.
            //
            // ⚠ An empty list means "nothing to show yet", NOT "you have no sources", so it
            // renders nothing rather than an empty state claiming the user's realm is bare.
            Section("Data sources") {
                ForEach(Array((model.state?.dataSources ?? []).enumerated()), id: \.offset) { _, row in
                    NavigationLink(value: FitRoute.dataSource(kind: row.kind, deviceId: row.deviceId)) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Label(dataSourceName(row.kind), systemImage: dataSourceSymbol(row.kind))
                                // Another device's row is named, so "89 items" cannot be mistaken
                                // for something this phone did.
                                Text(row.deviceLabel.map { "On \($0)" } ?? dataSourceHint(row.kind))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(dataSourceStatus(row.state))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                // The explicit first connection (App Review 5.1.3): no Apple Health row exists for
                // this device until an import has registered one, and the unattended refresh never
                // asks for the HealthKit grant — so this tap is where the consent sheet comes from.
                // Absent where the device has no health store (an iPad on iPadOS 16).
                if showsImportFromAppleHealth {
                    Button(action: model.importFromHealthSource) {
                        Label("Import from Apple Health", systemImage: "heart.text.square")
                    }
                }
            }

            // A separate section, because a file import is an ACT rather than a standing
            // connection (D11) — no source row, no auto-import switch, nothing to disconnect.
            if let batch = model.state?.batchImport {
                Section("Batch import") {
                    NavigationLink(value: FitRoute.batchImport) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Label("Google Takeout", systemImage: "square.and.arrow.down")
                                Text("Bring your Google Fit history in from an export file")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(batch.lastRunAt.map { "Last import: \(formatRunDate(Int64(truncating: $0)))" }
                                 ?? "Never imported")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            MapRendererSection()

            // In-app privacy policy (App Store guideline 5.1.1(i); ios-release/Sources), then the
            // open-source notices (docs/oss-licences.md), with the basemap's credits.
            Section("About") {
                PrivacyPolicyLink()
                OpenSourceLicencesLink(data: OssDataCredit.basemap)
            }

            Section {
                FitTrustCue().listRowBackground(Color.clear)
            }
        }
        .navigationTitle("Profile")
        .sheet(item: $editing) { field in
            // Guarded on a present profile: the rows that open these only render inside the
            // `if let profile` above, so the else branch is unreachable in practice.
            if let profile = model.state?.profile {
                ProfileEditorSheet(field: field, values: profile.editorValues) { model.apply($0) }
            }
        }
        .task { await model.observe() }
        .onChange(of: model.goalsToken) { _ in
            activeMinutes = Int(model.state?.goals.activeMinutes ?? 30)
            steps = Int(model.state?.goals.steps ?? 8000)
        }
    }

    @State private var activeMinutes = 30
    @State private var steps = 8000

    /// HealthKit exists here and this device has no Apple Health source row yet.
    private var showsImportFromAppleHealth: Bool {
        guard model.state != nil, HKHealthStore.isHealthDataAvailable() else { return false }
        return !(model.state?.dataSources ?? []).contains { $0.kind == .appleHealth && $0.isThisDevice }
    }

    private var unitsBinding: Binding<Units> {
        Binding(get: { model.state?.profile?.units ?? .metric }, set: { model.setUnits($0) })
    }

    // A profile value the user has never given us. Health Connect cannot supply sex or birth
    // date at all, so "unknown" is the true state until they say otherwise (fit-app.md §18.5).
    private var notSet: String { "Not set" }

    /// The header still needs a name, so an unset one falls back to the generic "You" — a
    /// placeholder in a greeting is fine where one in a data row would be a claim.
    private func displayName(_ profile: FitProfile) -> String {
        guard let name = profile.name, !name.isEmpty else { return "You" }
        return name
    }

    private func sexText(_ sex: BiologicalSex?) -> String? {
        switch sex {
        case .female: return "Female"
        case .male: return "Male"
        case .other: return "Other"
        default: return nil
        }
    }

    // Units-aware personal values, matching Compose `heightText` / `weightText`.
    private func heightText(_ profile: FitProfile) -> String {
        guard let heightCm = profile.heightCm else { return notSet }
        guard profile.units == .imperial else { return "\(Int(truncating: heightCm)) cm" }
        let totalInches = Int((heightCm.doubleValue / 2.54).rounded())
        return "\(totalInches / 12)′\(totalInches % 12)″"
    }

    private func weightText(_ profile: FitProfile) -> String {
        // Unlike the rest of Personal data this is a *measurement* read from the latest
        // `HealthSample`, so "Not set" means the store holds no weight reading (fit-app.md §18.2).
        guard let weightKg = profile.weightKg else { return notSet }
        return profile.units == .imperial
            ? String(format: "%.1f lb", weightKg.doubleValue * lbPerKg)
            : String(format: "%.1f kg", weightKg.doubleValue)
    }

}

@MainActor
final class ProfileModel: ObservableObject {
    @Published var state: ProfileUiState?
    // Bumped on each emission so the view reseeds its editable goal fields.
    @Published var goalsToken = 0
    private let vm = FitSurface.shared.profileViewModel()

    func observe() async {
        for await v in vm.uiState { state = v; goalsToken += 1 }
    }

    func saveGoals(active: Int, steps: Int) { vm.saveGoals(activeMinutes: Int32(active), steps: Int32(steps)) }

    // FP-4 profile edits (fit-app.md §18.11). The declared fields are preferences; height and
    // weight are measurements written as `HealthSample` rows — same sheet, different destination.

    /// One entry point for every field editor — the sheet hands back what it committed.
    func apply(_ edit: ProfileEdit) {
        switch edit {
        case .name(let v): vm.saveName(name: v)
        case .birthDate(let v): vm.saveBirthDate(date: v)
        case .sex(let v): vm.saveSex(sex: v)
        case .height(let v): vm.saveHeight(centimetres: Int32(v))
        case .weight(let v): vm.saveWeight(kilograms: v)
        }
    }
    func setUnits(_ units: Units) { vm.setUnits(units: units) }

    func importFromHealthSource() { vm.importFromHealthSource() }

    // ⚠ `importFullHistory()` moved to `DataSourceDetailView` with its row (DS-5): the deep
    // history grant is Health Connect's business, and Profile no longer renders a row for it.

}
