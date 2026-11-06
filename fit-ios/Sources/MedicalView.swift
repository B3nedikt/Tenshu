import SwiftUI
import TenshuSdk

// Medical (fit-app.md §2 / fit-agent.md §0.7): import-only, read-only records grouped into a fixed
// section order (Medications, Immunizations, Allergies, Conditions). Bound to `MedicalViewModel`.
struct MedicalView: View {
    @StateObject private var model = MedicalModel()

    var body: some View {
        List {
            ForEach(Array((model.state?.sections ?? []).enumerated()), id: \.offset) { _, section in
                Section(sectionTitle(section.kind)) {
                    ForEach(Array(section.records.enumerated()), id: \.offset) { _, record in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(record.title).font(.body.weight(.medium))
                            Text(record.detail).font(.caption).foregroundStyle(.secondary)
                            HStack(spacing: 6) {
                                Text(record.effectiveDate.display)
                                Text("· \(record.issuer)")
                            }
                            .font(.caption2).foregroundStyle(.tertiary)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            if (model.state?.sections ?? []).isEmpty {
                FitEmptyState(title: "No medical records", systemImage: "cross.case").listRowSeparator(.hidden)
            }
        }
        .navigationTitle("Medical")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
    }

    private func sectionTitle(_ kind: MedicalRecordKind) -> String {
        switch kind {
        case .medication: return "Medications"
        case .immunization: return "Immunizations"
        case .allergy: return "Allergies"
        case .condition: return "Conditions"
        @unknown default: return "Records"
        }
    }
}

@MainActor
final class MedicalModel: ObservableObject {
    @Published var state: MedicalUiState?
    private let vm = FitSurface.shared.medicalViewModel()
    func observe() async { for await v in vm.uiState { state = v } }
}
