import SwiftUI

/// "Choose the right place" (`timeline-recognition-and-place-naming.md` §4) — the SwiftUI twin of
/// the Compose `ChoosePlaceDialog`, shared by the visit detail and the place detail exactly as the
/// Compose component is.
///
/// A coarse indoor fix (±100 m) can name the clinic next door instead of the office you were in, so
/// this offers the nearest labelled candidates (proxy-mediated, behind the geocoding consent) as
/// tappable rows plus a free-text field. Whatever is confirmed becomes the **user-authored** name,
/// which the enricher's blank-only rule then never overwrites.
///
/// [loadSuggestions] runs once when the sheet opens; a failure or an empty result simply leaves the
/// free-text field alone — the picker degrades to a plain rename and never blocks on the network.
///
/// ⚠ A sheet rather than an `.alert`: an alert can host a `TextField` but not a list of candidates,
/// and the candidates are the reason this exists.
struct ChoosePlaceSheet: View {
    let current: String
    let loadSuggestions: () async -> [String]
    let onConfirm: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var value: String
    /// nil = still loading (the Compose `suggestions == null` state).
    @State private var suggestions: [String]?

    init(
        current: String,
        loadSuggestions: @escaping () async -> [String],
        onConfirm: @escaping (String) -> Void
    ) {
        self.current = current
        self.loadSuggestions = loadSuggestions
        self.onConfirm = onConfirm
        _value = State(initialValue: current)
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField("Place name", text: $value)
                        .autocorrectionDisabled()
                } header: {
                    Text("Name")
                }

                if suggestions == nil {
                    Section {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Looking for nearby places…")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                } else if let found = suggestions, !found.isEmpty {
                    Section {
                        ForEach(found, id: \.self) { candidate in
                            Button { value = candidate } label: {
                                HStack {
                                    Text(candidate).foregroundStyle(.primary)
                                    Spacer(minLength: 8)
                                    if candidate == value {
                                        Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                                    }
                                }
                            }
                        }
                    } header: {
                        Text("Nearby")
                    }
                }
            }
            .navigationTitle("Choose the right place")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onConfirm(value)
                        dismiss()
                    }
                    .disabled(value.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .task { suggestions = await loadSuggestions() }
        }
    }
}
