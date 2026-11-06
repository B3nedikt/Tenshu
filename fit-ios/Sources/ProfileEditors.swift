import SwiftUI
import TenshuSdk

// The profile field editors (fit-app.md §18.6/§18.11) — the iOS twin of Compose's
// `ProfileEditDialogs`.
//
// **Sheets, not a screen per field.** Five values, each one edit; a pushed destination per field
// would be more navigation than the content earns, and a sheet keeps the row it edits behind it.
//
// **One implementation, two callers.** Profile and the About-you onboarding step edit the same
// values, so the sheet takes a plain value bag and hands back a single [ProfileEdit] rather than
// binding to either screen's model. A second copy for onboarding would have been the real cost.
//
// Each editor holds a draft and commits only on Save, so a swipe-down never writes. Height and
// weight commit a `HealthSample` rather than a setting — the same row Health Connect writes
// (§18.2) — which is why their footer says the value is saved as a *reading*.

/// Which editor is presented. `nil` = none.
enum ProfileEditorField: String, Identifiable {
    case name, birthDate, sex, height, weight
    var id: String { rawValue }
}

/// What the sheet was given to start from. Every field is optional — "not set" is a real state.
struct ProfileEditorValues {
    var units: Units = .metric
    var name: String?
    var birthDate: Kotlinx_datetimeLocalDate?
    var sex: BiologicalSex?
    var heightCm: Int?
    var weightKg: Double?
}

/// What the sheet committed. `nil` payloads clear a value back to "not set".
enum ProfileEdit {
    case name(String)
    case birthDate(Kotlinx_datetimeLocalDate?)
    case sex(BiologicalSex?)
    case height(Int)
    case weight(Double)
}

struct ProfileEditorSheet: View {
    let field: ProfileEditorField
    let values: ProfileEditorValues
    let commit: (ProfileEdit) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var birthDate = Date()
    @State private var hasBirthDate = false
    @State private var sex: BiologicalSex?
    @State private var heightText = ""
    @State private var feet = ""
    @State private var inches = ""
    @State private var weightText = ""

    var body: some View {
        NavigationStack {
            Form { editor }
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("Save") { save(); dismiss() } }
                }
                .onAppear(perform: seed)
        }
    }

    @ViewBuilder private var editor: some View {
        switch field {
        case .name:
            TextField("Your name", text: $name)
            Text("Shown on your profile and when you share with someone.")
                .font(.footnote).foregroundStyle(.secondary)

        case .birthDate:
            // "Not set" has to stay reachable — a mis-tap on a date wheel must be undoable, and
            // unset is a real answer rather than a failure state.
            Toggle("Set a birth date", isOn: $hasBirthDate)
            if hasBirthDate {
                DatePicker("Birth date", selection: $birthDate, in: ...Date(), displayedComponents: .date)
                    .datePickerStyle(.graphical)
            }

        case .sex:
            // "Not set" is a first-class option rather than a Clear button: it is the honest answer
            // for anyone who would rather not say, and the estimate falls back to a neutral
            // formula (§18.4).
            Picker("Sex", selection: $sex) {
                Text("Not set").tag(BiologicalSex?.none)
                Text("Female").tag(BiologicalSex?.some(.female))
                Text("Male").tag(BiologicalSex?.some(.male))
                Text("Other").tag(BiologicalSex?.some(.other))
            }
            .pickerStyle(.inline)
            .labelsHidden()
            Text("Used to estimate energy burned. Leave it unset and we will use a neutral estimate.")
                .font(.footnote).foregroundStyle(.secondary)

        case .height:
            // Entered in the unit the row displays — asking an imperial user for centimetres would
            // be a conversion they have to do in their head.
            if values.units == .imperial {
                HStack {
                    TextField("Feet", text: $feet).keyboardType(.numberPad)
                    TextField("Inches", text: $inches).keyboardType(.numberPad)
                }
            } else {
                TextField("Centimetres", text: $heightText).keyboardType(.numberPad)
            }
            measurementFooter

        case .weight:
            TextField(values.units == .imperial ? "Pounds" : "Kilograms", text: $weightText)
                .keyboardType(.decimalPad)
            measurementFooter
        }
    }

    private var measurementFooter: some View {
        Text("Saved as a new reading, alongside anything your health app has recorded.")
            .font(.footnote).foregroundStyle(.secondary)
    }

    private var title: String {
        switch field {
        case .name: return "Your name"
        case .birthDate: return "Birth date"
        case .sex: return "Sex"
        case .height: return "Height"
        case .weight: return "Weight"
        }
    }

    private func seed() {
        name = values.name ?? ""
        sex = values.sex
        if let d = values.birthDate {
            hasBirthDate = true
            birthDate = d.asDate ?? Date()
        }
        if let cm = values.heightCm {
            if values.units == .imperial {
                let total = Int((Double(cm) / 2.54).rounded())
                feet = "\(total / 12)"; inches = "\(total % 12)"
            } else {
                heightText = "\(cm)"
            }
        }
        if let kg = values.weightKg {
            weightText = String(format: "%.1f", values.units == .imperial ? kg * lbPerKg : kg)
        }
    }

    private func save() {
        switch field {
        case .name:
            commit(.name(name))
        case .birthDate:
            commit(.birthDate(hasBirthDate ? birthDate.asLocalDate : nil))
        case .sex:
            commit(.sex(sex))
        case .height:
            if values.units == .imperial {
                let total = (Int(feet) ?? 0) * 12 + (Int(inches) ?? 0)
                if total > 0 { commit(.height(Int((Double(total) * 2.54).rounded()))) }
            } else if let cm = Int(heightText) {
                commit(.height(cm))
            }
        case .weight:
            if let entered = Double(weightText.replacingOccurrences(of: ",", with: ".")) {
                commit(.weight(values.units == .imperial ? entered / lbPerKg : entered))
            }
        }
    }
}

/// A tappable "label … value ›" Form row — the editable successor to the old read-only
/// `LabeledRow`, whose "none of them is editable in v1" comment FP-4 retires.
struct EditableRow: View {
    let label: String
    let value: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Text(label).foregroundStyle(.primary)
                Spacer()
                Text(value).foregroundStyle(.secondary)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .buttonStyle(.plain)
    }
}

extension Date {
    /// The calendar day this instant falls on, which is all the shared model stores — read in
    /// `isoCalendar`, since a `LocalDate` is ISO whatever the system calendar is.
    var asLocalDate: Kotlinx_datetimeLocalDate? {
        let c = isoCalendar.dateComponents([.year, .month, .day], from: self)
        guard let y = c.year, let m = c.month, let d = c.day else { return nil }
        return Kotlinx_datetimeLocalDate(year: Int32(y), month: Int32(m), day: Int32(d))
    }
}

let lbPerKg = 2.2046

extension FitProfile {
    /// The editor's starting point, unwrapping the boxed Kotlin optionals once.
    var editorValues: ProfileEditorValues {
        ProfileEditorValues(
            units: units,
            name: name,
            birthDate: birthDate,
            sex: sex,
            heightCm: heightCm.map { Int(truncating: $0) },
            weightKg: weightKg?.doubleValue,
        )
    }
}
