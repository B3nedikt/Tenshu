import SwiftUI
import TenshuSdk

// The chat surface (home-and-mia-apps.md §4): greeting empty state, bubbles, the citations
// cell (mia-query-layer.md MQ-D1), text-first input (the mic arrives with the voice pipeline,
// D4). Bound to `ChatViewModel` via `MiaSurface`.
//
// ⚠⚠ There is no confirmation cell, and that is not an omission to fill in from Swift. On this
// platform the proxy never offers an action (`MiaActionOffer.WITHHELD`), so no
// `MessageBodyConfirmation` arrives; adding a button here would be a button over nothing.
struct ChatView: View {
    @StateObject private var model = ChatModel()

    var body: some View {
        VStack(spacing: 0) {
            if (model.state?.messages ?? []).isEmpty {
                greeting
            } else {
                thread
            }
            inputBar
        }
        .navigationTitle("Mia")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
    }

    private var greeting: some View {
        VStack(spacing: 8) {
            Spacer()
            Text("Hi, I'm Mia").font(.title2.weight(.semibold))
            // ⚠ Names only what this build answers. "…or to control your home" stood here and is
            // the one thing Mia on iPhone will not do.
            Text("Ask me about your photos, files, places, workouts and calendar — I'll show you what I found.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    private var thread: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 8) {
                    ForEach(model.state?.messages ?? [], id: \.id) { message in
                        MessageCell(message: message)
                    }
                    if model.state?.replying == true {
                        Text("Mia is thinking…").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .padding(16)
            }
            .onChange(of: model.state?.messages.count ?? 0) { _ in
                if let last = model.state?.messages.last {
                    withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
        }
    }

    private var inputBar: some View {
        HStack(spacing: 8) {
            TextField("Ask Mia…", text: $model.input)
                .textFieldStyle(.roundedBorder)
                .onSubmit { model.send() }
            Button(action: model.send) {
                Image(systemName: "paperplane.fill")
            }
            .disabled(!(model.state?.canSend ?? false))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }
}

// One message: user bubbles trail, Mia's lead; rich bodies get their own cells.
struct MessageCell: View {
    let message: MiaMessage

    var body: some View {
        HStack {
            if message.fromUser { Spacer(minLength: 48) }
            content
            if !message.fromUser { Spacer(minLength: 48) }
        }
    }

    @ViewBuilder
    private var content: some View {
        if let text = message.body as? MessageBodyText {
            Text(text.text)
                .padding(.horizontal, 14).padding(.vertical, 9)
                .background(
                    message.fromUser ? Color.accentColor.opacity(0.18) : Color(.secondarySystemBackground),
                    in: RoundedRectangle(cornerRadius: 16),
                )
        } else if let citations = message.body as? MessageBodyCitations {
            CitationsCell(citations: citations)
        } else if let grid = message.body as? MessageBodyPhotoGrid {
            VStack(alignment: .leading, spacing: 8) {
                Text(grid.caption).font(.subheadline)
                FlowGrid(labels: grid.photoLabels)
            }
            .padding(12)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
        } else if let place = message.body as? MessageBodyPlaceCard {
            HStack(spacing: 10) {
                Image(systemName: "mappin.circle.fill").foregroundStyle(.tint).font(.title3)
                VStack(alignment: .leading, spacing: 2) {
                    Text(place.name).font(.body.weight(.medium))
                    Text(place.detail).font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding(12)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
        } else if let ack = message.body as? MessageBodyDeviceAck {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                Text(ack.text)
            }
            .padding(.horizontal, 14).padding(.vertical, 9)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
        }
    }
}

/// Mia's citations (mia-query-layer.md MQ-D1): real rows, not prose — the cell every answer from
/// the real repository arrives in. The SwiftUI twin of the Compose `CitationsCell`.
///
/// ⚠ The caption is the planner's *explanation* — what was actually searched — so the user can
/// check the question Mia answered against the one they asked. Dropping it would leave an answer
/// with no way to tell a right result from a right-looking one.
///
/// ⚠ Rows are **not tappable** in this slice: `MiaSurface` passes no opener, so there is nothing a
/// tap could do, and a row that looks tappable and is not is worse than one that does not.
private struct CitationsCell: View {
    let citations: MessageBodyCitations

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(citations.caption)
                .font(.caption)
                .foregroundStyle(.secondary)
            ForEach(Array(citations.hits.enumerated()), id: \.offset) { _, hit in
                HStack(spacing: 10) {
                    Image(systemName: Self.symbol(for: hit.typeId))
                        .foregroundStyle(.tint)
                        .frame(width: 24)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(hit.title).font(.body)
                        if !hit.subtitle.isEmpty {
                            Text(hit.subtitle).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .padding(12)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    /// The glyph for a cited row, by the owning schema's type id — the same six outcomes as the
    /// Compose `glyphFor`. ⚠ Literals for the reason recorded there: presentation lives in each
    /// consumer, and a stale spelling costs a document icon on an otherwise correct row.
    private static func symbol(for typeId: String) -> String {
        switch typeId {
        case "tenshu/place/place": return "mappin.circle.fill"
        case "tenshu/timeline/moment": return "clock"
        case "tenshu/health/workout": return "figure.run"
        case "tenshu/home/device/homeDevice": return "lightbulb"
        case "tenshu/calendar/eventOccurrence": return "calendar"
        default: return "doc"
        }
    }
}

// Mock photo tiles until real Drive thumbnails ride the grid.
private struct FlowGrid: View {
    let labels: [String]
    private let columns = [GridItem(.adaptive(minimum: 84), spacing: 8)]

    var body: some View {
        LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
            ForEach(labels, id: \.self) { label in
                VStack(spacing: 4) {
                    Image(systemName: "photo").font(.title3)
                    Text(label).font(.caption2).lineLimit(1)
                }
                .frame(maxWidth: .infinity)
                .padding(10)
                .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
            }
        }
    }
}

@MainActor
final class ChatModel: ObservableObject {
    @Published var state: ChatUiState?
    @Published var input: String = "" {
        didSet { vm.setInput(text: input) }
    }
    private let vm = MiaSurface.shared.chatViewModel()
    /// The text handed to the VM by the last `send()`, until its clear comes back.
    private var sending: String?

    func observe() async {
        for await v in vm.uiState {
            state = v
            // ⚠⚠ Only the VM's clear on send comes back into the field — never its echo of what was
            // typed. Copying every differing emission raced the keyboard: the echo of one keystroke
            // landed after the next and erased it, so fast typing dropped letters (the store walk
            // sent "Sho me my tax fles", 2026-10-30). On iOS nothing else writes the VM's input:
            // there is no voice composer here.
            if v.input.isEmpty, let sent = sending, input == sent {
                input = ""
                sending = nil
            }
        }
    }

    func send() {
        sending = input
        vm.send()
    }
}
