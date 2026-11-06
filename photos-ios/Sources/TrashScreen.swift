import SwiftUI
import TenshuSdk

// The Trash (photos-ios.md §8.17), opened from Collections › Utilities.
//
// ⚠⚠ Every rule and sentence is the shared `TrashViewModel` / `TrashUiState` / `TrashCopy` — the same
// ones Compose's `TrashScreen` draws. This file asks, confirms, and says what came back.
//
// ⚠ Tap restores — the safe action, and the one a user in the trash most often wants (Compose's rule).
// Destroying takes the context menu AND a confirmation that names what is lost.

struct TrashScreen: View {
    @StateObject private var model: TrashModel
    @State private var confirming: TrashedItem?
    @State private var confirmingEmpty = false
    @Environment(\.displayScale) private var scale
    private let previews = PreviewGate()

    init(onChanged: @escaping () -> Void) {
        _model = StateObject(wrappedValue: TrashModel(onChanged: onChanged))
    }

    var body: some View {
        content
            .navigationTitle(TrashCopy.shared.TITLE)
            .navigationBarTitleDisplayMode(.inline)
            // ⚠⚠ No leaving while a write runs — the Back button AND the swipe: a pop
            // and a reopen builds a second view model whose own single-flight guard lets a restore
            // race a delete still able to land. Android consumes Back for the same reason.
            .navigationBarBackButtonHidden(model.state.writing)
            .toolbar {
                if !model.state.items.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(TrashCopy.shared.EMPTY_ACTION, role: .destructive) { confirmingEmpty = true }
                            .disabled(!model.state.canEmpty)
                    }
                }
            }
            .task { await model.observe() }
            .confirmationDialog(
                TrashCopy.shared.DELETE_ONE_TITLE,
                isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
                titleVisibility: .visible,
                presenting: confirming
            ) { item in
                Button(TrashCopy.shared.DELETE_ONE_CONFIRM, role: .destructive) {
                    model.viewModel.deleteForever(contentId: item.contentId)
                }
                Button(TrashCopy.shared.DELETE_ONE_CANCEL, role: .cancel) {}
            } message: { _ in
                Text(TrashCopy.shared.DELETE_ONE_MESSAGE)
            }
            .confirmationDialog(TrashCopy.shared.EMPTY_TITLE, isPresented: $confirmingEmpty, titleVisibility: .visible) {
                Button(TrashCopy.shared.EMPTY_CONFIRM, role: .destructive) { model.viewModel.emptyTrash() }
                Button(TrashCopy.shared.EMPTY_CANCEL, role: .cancel) {}
            } message: {
                // ⚠ What Empty will actually delete — locked items are skipped (`emptyCount`).
                Text(TrashCopy.shared.emptyMessage(count: model.state.emptyCount))
            }
            // ⚠⚠ Rendering this is not optional: no result here may be dropped unsaid
            // (photos-app.md §22).
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
        if model.state.loading && model.state.items.isEmpty {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let status = model.state.statusText {
            // The shared sentence — the error, else the empty copy (`TrashUiState.statusText`).
            Text(status)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(32)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            grid
        }
    }

    private var grid: some View {
        GeometryReader { geometry in
            let columns = Int(GridWidthScaleKt.collectionGridColumns(widthDp: Float(geometry.size.width)))
            let spacing: CGFloat = 2
            let edge = floor((geometry.size.width - spacing * CGFloat(columns - 1)) / CGFloat(columns))
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.fixed(edge), spacing: spacing), count: columns), spacing: spacing) {
                    ForEach(model.state.items, id: \.contentId) { item in
                        Button { model.viewModel.restore(contentId: item.contentId) } label: {
                            PhotoTileView(photo: item.photo, edgePoints: edge, scale: scale, previews: previews)
                        }
                        .buttonStyle(.plain)
                        // ⚠ Nothing while a write runs: the writes are single-flight, and a tap the view
                        // model then ignores is a restore that silently never happened.
                        // ⚠ Nor an item whose write was abandoned unanswered (`TrashUiState.uncertain`).
                        .disabled(!model.state.canAct(contentId: item.contentId))
                        .accessibilityIdentifier(item.photo.path.split(separator: "/").last.map(String.init) ?? item.photo.path)
                        .accessibilityHint(TrashCopy.shared.RESTORE_ACTION)
                        .contextMenu {
                            // ⚠ `.disabled` above stops the tap, not the long-press: the
                            // menu's buttons are their own, and offered actions `canAct` then drops — a
                            // Restore that did nothing, a delete confirmation whose delete did nothing.
                            // No content, no menu.
                            if model.state.canAct(contentId: item.contentId) {
                                Button { model.viewModel.restore(contentId: item.contentId) } label: {
                                    Label(TrashCopy.shared.RESTORE_ACTION, systemImage: "arrow.uturn.backward")
                                }
                                Button(role: .destructive) { confirming = item } label: {
                                    Label(TrashCopy.shared.DELETE_ACTION, systemImage: "trash")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@MainActor
final class TrashModel: ObservableObject {
    let viewModel: TrashViewModel
    @Published private(set) var state: TrashUiState

    init(onChanged: @escaping () -> Void) {
        viewModel = PhotosSurface.shared.trash(onChanged: onChanged)
        state = viewModel.state.value
    }

    func observe() async {
        for await value in viewModel.state { state = value }
    }
}
