import SwiftUI
import TenshuSdk

// History (home-and-mia-apps.md §4): past conversations newest-first; a row opens the
// read-only thread. Bound to `MiaHistoryViewModel` via `MiaSurface`.
struct HistoryView: View {
    @StateObject private var model = HistoryModel()

    var body: some View {
        List {
            ForEach(model.state?.conversations ?? [], id: \.id) { conversation in
                NavigationLink(value: conversation.id) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(conversation.title).font(.body.weight(.medium))
                        Text(dayLabel(conversation)).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            if (model.state?.conversations ?? []).isEmpty, model.state?.loaded == true {
                Text("No conversations yet.").foregroundStyle(.secondary)
            }
        }
        .navigationTitle("History")
        .navigationDestination(for: String.self) { conversationId in
            ConversationDetailView(conversation: model.conversation(id: conversationId))
        }
        .task { await model.observe() }
    }

    private func dayLabel(_ conversation: Conversation) -> String {
        let date = Date(timeIntervalSince1970: Double(conversation.startedAt.toEpochMilliseconds()) / 1000)
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}

// The read-only past thread — the chat renderer without an input row.
struct ConversationDetailView: View {
    let conversation: Conversation?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 8) {
                ForEach(conversation?.messages ?? [], id: \.id) { message in
                    MessageCell(message: message)
                }
            }
            .padding(16)
        }
        .navigationTitle(conversation?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
    }
}

@MainActor
final class HistoryModel: ObservableObject {
    @Published var state: MiaHistoryUiState?
    private let vm = MiaSurface.shared.historyViewModel()

    func observe() async { for await v in vm.uiState { state = v } }

    func conversation(id: String) -> Conversation? { vm.conversation(conversationId: id) }
}
