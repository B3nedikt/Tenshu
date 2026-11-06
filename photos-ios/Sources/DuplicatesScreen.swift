import SwiftUI
import TenshuSdk

// Duplicates (PIO-4 slice 3b, photos-ios.md §8.18), opened from its tile on the Collections tab.
//
// ⚠⚠ Every rule and sentence is the shared `DuplicatesViewModel` / `DuplicatesUiState` /
// `DuplicatesCopy` — the same ones Compose's `DuplicatesScreen` draws (PIO-D2). Which copy may be
// chosen, what the confirmation says and which mode it runs in, what stands in place of the list:
// none of it is decided here. This file asks, confirms, and says what came back.
//
// ⚠⚠ A tap means KEEP THIS ONE, never delete this one. Nothing is removed until the confirmation —
// which names the survivor and says what happens to the rest — has been read and confirmed, and
// destroying instead of trashing takes the switch as well (off by default).

struct DuplicatesScreen: View {
    @StateObject private var model: DuplicatesModel
    @State private var confirming: DuplicatesConfirmation?
    @Environment(\.displayScale) private var scale
    private let previews = PreviewGate()

    init(onChanged: @escaping () -> Void) {
        _model = StateObject(wrappedValue: DuplicatesModel(onChanged: onChanged))
    }

    var body: some View {
        content
            .navigationTitle(DuplicatesCopy.shared.TITLE)
            .navigationBarTitleDisplayMode(.inline)
            // ⚠⚠ No leaving while a write runs — the Back button AND the swipe (§8.17 note 6): a pop
            // and a reopen builds a second view model whose own single-flight guard lets a second
            // keep-only run beside a write still able to land. Android consumes Back for its reason.
            .navigationBarBackButtonHidden(!model.state.canLeave)
            .toolbar {
                if model.state.writing {
                    ToolbarItem(placement: .topBarTrailing) { ProgressView() }
                }
            }
            .task { await model.observe() }
            .confirmationDialog(
                confirming?.title ?? "",
                isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
                titleVisibility: .visible,
                presenting: confirming
            ) { confirmation in
                // ⚠ Destructive only when it destroys: a move to the trash can be taken back.
                Button(confirmation.confirm, role: confirmation.permanently ? .destructive : nil) {
                    model.viewModel.keepOnly(confirmation: confirmation)
                }
                Button(confirmation.cancel, role: .cancel) {}
            } message: { confirmation in
                Text(confirmation.keeping + "\n" + confirmation.consequence)
            }
            // ⚠⚠ Rendering this is not optional: every result here used to be dropped (§8.18).
            .alert(
                model.state.report?.title ?? "",
                isPresented: Binding(get: { model.state.report != nil }, set: { if !$0 { model.viewModel.dismissReport() } }),
                presenting: model.state.report
            ) { _ in
                Button("OK") {}
            } message: { report in
                Text(report.message)
            }
    }

    @ViewBuilder
    private var content: some View {
        if model.state.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let status = model.state.status {
            // ⚠⚠ "Could not look" is not "no duplicates" (photos-app.md §40): the shared status says
            // which, and only a read that failed carries a retry. ⚠ Hand-rolled — the floor is iOS 16
            // (PIO-D11), and `ContentUnavailableView` is 17+.
            VStack(spacing: 16) {
                Text(status.text)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .accessibilityIdentifier("duplicatesStatus")
                if status.canRetry {
                    Button(DuplicatesCopy.shared.RETRY_ACTION) { model.viewModel.retry() }
                        .buttonStyle(.bordered)
                }
            }
            .padding(32)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            list
        }
    }

    private var list: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                header
                Divider()
                ForEach(model.state.groups, id: \.contentHash) { group in
                    row(group)
                    Divider()
                }
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(model.state.summary)
                .font(.subheadline)
                .accessibilityIdentifier("duplicatesSummary")
            Text(DuplicatesCopy.shared.EXACT_ONLY)
                .font(.caption)
                .foregroundStyle(.secondary)
            Toggle(isOn: Binding(
                get: { model.state.deletePermanently },
                set: { model.viewModel.setDeletePermanently(enabled: $0) }
            )) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(DuplicatesCopy.shared.PERMANENT_SWITCH).font(.subheadline)
                    // ⚠ What each setting COSTS — the reason to turn this on is running out of space.
                    Text(DuplicatesCopy.shared.modeDescription(permanently: model.state.deletePermanently))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            // ⚠ Not while a write runs: the one running took the mode it was confirmed with.
            .disabled(!model.state.canSetDeletePermanently)
            .accessibilityIdentifier("deletePermanently")
            .padding(.top, 6)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func row(_ group: DuplicateGroup) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(model.state.heading(group: group)).font(.subheadline.weight(.semibold))
            Text(DuplicatesCopy.shared.caption(group: group))
                .font(.caption)
                .foregroundStyle(.secondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(group.photos, id: \.path) { photo in
                        // ⚠ The tap only ASKS: the shared confirmation is `nil` when this copy may not
                        // be chosen, and then nothing opens.
                        Button { confirming = model.state.confirmation(group: group, keep: photo) } label: {
                            PhotoTileView(photo: photo, edgePoints: 96, scale: scale, previews: previews)
                                .clipShape(RoundedRectangle(cornerRadius: 6))
                        }
                        .buttonStyle(.plain)
                        // ⚠ Nothing while a write runs, and never a copy whose own write went unanswered
                        // (`DuplicatesUiState.canKeep`). ⚠ There is no context menu on these tiles, so
                        // `.disabled` — which stops a tap but not a long-press — leaves nothing live.
                        .disabled(!model.state.canKeep(group: group, keep: photo))
                        .accessibilityIdentifier(photo.path.split(separator: "/").last.map(String.init) ?? photo.path)
                        .accessibilityHint(DuplicatesCopy.shared.KEEP_HINT)
                    }
                }
                .padding(.top, 4)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

@MainActor
final class DuplicatesModel: ObservableObject {
    let viewModel: DuplicatesViewModel
    @Published private(set) var state: DuplicatesUiState

    init(onChanged: @escaping () -> Void) {
        viewModel = PhotosSurface.shared.duplicates(onChanged: onChanged)
        state = viewModel.state.value
    }

    func observe() async {
        for await value in viewModel.state { state = value }
    }
}
