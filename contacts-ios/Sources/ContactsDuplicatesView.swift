import SwiftUI
import TenshuSdk

/// *Possible duplicates* (`contacts-app.md` D7, Ph9), over the shared `MergeViewModel`.
///
/// ⚠⚠ Every row here is a **question**, not a finding. Nothing merges until the user opens a pair,
/// reads what will be combined, and confirms — D7's *never auto-merge* rule, which exists because
/// everything a handshake delivers is self-asserted: a system that merged on a name match would let
/// an attacker's key inherit a known person's contact card.
///
/// The heuristic itself is `findMergeCandidates`, in `:contacts-shared-logic`. Nothing about it is
/// reimplemented here, which is also why this screen cannot drift from the Compose one.
struct ContactsDuplicatesView: View {
    @StateObject private var model = MergeModel()

    var body: some View {
        let state = model.state
        List {
            if state?.hasCandidates == true {
                ForEach(Array((state?.candidates ?? []).enumerated()), id: \.offset) { _, candidate in
                    Button { model.viewModel.onCandidateClicked(candidate: candidate) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\"\(candidate.first.displayName)\" and \"\(candidate.second.displayName)\"")
                            // The reason is the whole content of the row: "same name" and "same
                            // phone number" deserve very different amounts of trust, and hiding
                            // which one fired would make the weakest look like the strongest.
                            Text(reason(candidate))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        // ⚠ The whole row takes the tap, not only its text (`.buttonStyle(.plain)`).
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            } else if state?.loaded == true {
                ContentUnavailableCompat(
                    title: "No duplicates found",
                    body: "Nobody in your contacts looks like anyone else. Imported contacts are never combined for you — this is where they would show up if they were."
                )
            }
        }
        .navigationTitle("Possible duplicates")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
        .confirmationDialog(
            "Which one should be kept?",
            isPresented: reviewingBinding,
            titleVisibility: .visible
        ) {
            if let review = model.state?.reviewing {
                // The one question worth asking, because it is the only choice that changes the
                // outcome: the target's petname is what remains.
                ForEach([review.candidate.first, review.candidate.second], id: \.personId) { contact in
                    Button("Keep \"\(contact.displayName)\"") {
                        model.viewModel.onTargetChanged(personId: contact.personId)
                        model.viewModel.onMergeConfirmed()
                    }
                }
            }
            Button("Cancel", role: .cancel) { model.viewModel.onReviewDismissed() }
        } message: {
            // ⚠ Said before the merge, not discovered after it. The rows that move carry no
            // memory of where they came from, so there is genuinely nothing to undo with.
            Text("The other is combined into it. Their keys, cards and photo all move across. This cannot be undone.")
        }
    }

    private var reviewingBinding: Binding<Bool> {
        Binding(
            get: { model.state?.reviewing != nil },
            set: { if !$0 { model.viewModel.onReviewDismissed() } }
        )
    }

    private func reason(_ candidate: MergeCandidate) -> String {
        // ⚠ SKIE bridges a Kotlin enum to a real Swift enum, so the cases are camelCased
        // (`.sharedPhone`) rather than the Obj-C export's flattened `sharedphone` — and the switch
        // is exhaustive, so a fourth reason in Kotlin fails the build instead of quietly reading as
        // "Same name".
        let headline: String
        switch candidate.headlineReason {
        case .sharedPhone: headline = "Same phone number"
        case .sharedEmail: headline = "Same email address"
        case .sameName: headline = "Same name"
        }
        // Two people genuinely can share a name, so a name-only match is a question rather than a
        // finding, and the copy says so.
        return candidate.isNameOnly ? "\(headline) — which two people can share." : headline
    }
}

@MainActor
final class MergeModel: ObservableObject {
    let viewModel = ContactsSurface.shared.mergeViewModel()
    @Published var state: MergeUiState?

    func observe() async {
        do { for try await value in viewModel.uiState { state = value } } catch {}
    }
}
