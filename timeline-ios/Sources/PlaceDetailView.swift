import SwiftUI
import TenshuSdk

// Place detail (§1.4) — identity + a visit-history timeline, the user-authored rename
// (place-geocoding.md G4/G5; the enricher never overwrites a non-blank name), the Home/Work role
// answer (place-roles.md PR-3) and the manual merge/unmerge
// (timeline-recognition-and-place-naming.md §9.4). Retains the `PlaceDetailViewModel` — not just
// its state flow — so every action reaches the same instance the screen observes.
@MainActor
private final class PlaceDetailModel: ObservableObject {
    @Published var state: PlaceDetailUiState?
    private let vm: PlaceDetailViewModel

    init(placeId: String) {
        vm = TimelineSurface.shared.placeViewModel(placeId: placeId)
    }

    func observe() async {
        do { for try await value in vm.uiState { state = value } } catch {}
    }

    func rename(_ label: String) {
        vm.rename(label: label)
    }

    /// Nearby name candidates for the picker; an unreachable/failed lookup degrades to none.
    func suggestions() async -> [String] {
        ((try? await vm.suggestions()) ?? []) as [String]
    }

    /// Answer the role prompt — or dismiss it, which is the same write with `none` (PR-3).
    func setRole(_ role: String) {
        vm.setRole(role: role)
    }

    /// Fold this place into `otherId`. The survivor is chosen deterministically by the store, so
    /// the screen deliberately does not navigate — it reloads, exactly like the Compose surface.
    func merge(into otherId: String) {
        vm.merge(otherId: otherId, onMerged: { _ in })
    }

    func unmerge() {
        vm.unmerge()
    }
}

struct PlaceDetailView: View {
    @StateObject private var model: PlaceDetailModel
    @Environment(\.colorScheme) private var colorScheme
    @State private var renaming = false
    @State private var merging = false

    init(placeId: String) {
        _model = StateObject(wrappedValue: PlaceDetailModel(placeId: placeId))
    }

    var body: some View {
        Group {
            switch model.state.map({ onEnum(of: $0) }) {
            case .some(.loaded(let loaded)):
                loadedBody(loaded)
            case .some(.notFound):
                ContentUnavailableCompat(title: "Place not found", systemImage: "questionmark.circle")
            default:
                ProgressView()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if case .some(.loaded) = model.state.map({ onEnum(of: $0) }) {
                ToolbarItem(placement: .primaryAction) {
                    Button { renaming = true } label: {
                        Image(systemName: "pencil")
                    }
                    .accessibilityLabel("Rename this place")
                }
            }
        }
        .sheet(isPresented: $renaming) {
            ChoosePlaceSheet(
                current: loadedPlace?.name ?? "",
                loadSuggestions: { await model.suggestions() },
                onConfirm: { model.rename($0) }
            )
        }
        .sheet(isPresented: $merging) {
            MergePlaceSheet(
                candidates: loadedState?.mergeCandidates ?? [],
                onPick: { model.merge(into: $0) }
            )
        }
        .task { await model.observe() }
    }

    private var loadedState: PlaceDetailUiStateLoaded? {
        guard case .some(.loaded(let loaded)) = model.state.map({ onEnum(of: $0) }) else { return nil }
        return loaded
    }

    private var loadedPlace: Place? { loadedState?.place }

    @ViewBuilder private func loadedBody(_ loaded: PlaceDetailUiStateLoaded) -> some View {
        let place = loaded.place
        let personalisation = TimelinePersonalisation.resolve(
            dominantHex: place.cover.dominantColor,
            strength: loaded.personalisationStrength,
            scheme: colorScheme
        )
        let accent = personalisation.accent
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // A real map of the place instead of the flat colour rectangle (P3) — a **point**
                // hero: centred on the place with a single pin, not a route forced through the route
                // style. The flat rectangle stays for a place with no coordinate yet (imported
                // without one, or never geocoded), which is the same gate `PlaceDetailScreen` uses:
                // `(0, 0)` is a real location in the Atlantic, so it must never reach the map.
                if place.hasCoordinate {
                    TimelinePlaceMapCard(
                        latE7: place.latE7,
                        lngE7: place.lngE7,
                        accent: accent,
                        soft: personalisation.soft,
                        regionBundlePath: loaded.regionBundlePath
                    )
                } else {
                    RoundedRectangle(cornerRadius: 20)
                        .fill(personalisation.soft)
                        .frame(height: 160)
                }

                VStack(alignment: .leading, spacing: 4) {
                    // Label-less until geocoded/renamed — honest fallback, not blank text.
                    Text(place.name.isEmpty ? "Unnamed place" : place.name)
                        .font(.timelineSerif(30, weight: .semibold))
                    // City-level is all geocoding stores (G4); hide the line when unknown.
                    let locality = [place.neighborhood, place.city]
                        .filter { !$0.isEmpty }.joined(separator: ", ")
                    if !locality.isEmpty {
                        Text(locality).font(.headline).foregroundStyle(.secondary)
                    }
                }

                // place-roles.md PR-3: the prompt sits directly under the identity that justifies
                // the guess. A confirmed role renders as a chip in the same slot — same position,
                // question becomes statement.
                PlaceRoleSection(role: place.role, accent: accent, onSetRole: { model.setRole($0) })

                HStack(spacing: 20) {
                    stat("\(place.visitsCount)", "visits")
                    stat("\(place.photosCount)", "photos")
                    if let avg = place.avgDuration { stat(avg, "avg") }
                }

                if !place.tags.isEmpty {
                    HStack(spacing: 8) {
                        ForEach(place.tags, id: \.self) { tag in
                            Text(tag).font(.caption).padding(.horizontal, 10).padding(.vertical, 4)
                                .background(Color(.secondarySystemFill), in: Capsule())
                        }
                    }
                }

                if !place.history.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Eyebrow("Visit history")
                        ForEach(Array(place.history.enumerated()), id: \.offset) { _, visit in
                            visitRow(visit, strength: loaded.personalisationStrength)
                        }
                    }
                }

                // ⚠ Structural actions live BELOW the content, together and in words — the same
                // call the Compose screen makes: a rare, corrective action must not look like part
                // of the list, and "merge" has no glyph anyone can read. Rename keeps its pencil.
                structuralActions(loaded)
            }
            .padding(20)
        }
    }

    @ViewBuilder private func structuralActions(_ loaded: PlaceDetailUiStateLoaded) -> some View {
        if !loaded.mergeCandidates.isEmpty || loaded.isMerged {
            VStack(alignment: .leading, spacing: 4) {
                if !loaded.mergeCandidates.isEmpty {
                    Button("This is the same place as…") { merging = true }
                        .font(.subheadline)
                }
                // Only when something is actually folded into this row — the folded rows are
                // invisible by design, so the question cannot be answered from this place alone.
                if loaded.isMerged {
                    Button("Undo merge") { model.unmerge() }
                        .font(.subheadline)
                }
            }
            .padding(.top, 8)
        }
    }

    private func visitRow(_ visit: PlaceVisit, strength: Float) -> some View {
        let accent = TimelinePersonalisation.resolve(
            dominantHex: visit.photos.first?.dominantColor,
            strength: strength,
            scheme: colorScheme
        ).accent
        return HStack(alignment: .top, spacing: 12) {
            Circle().fill(accent.opacity(0.6)).frame(width: 8, height: 8).padding(.top, 6)
            VStack(alignment: .leading, spacing: 2) {
                Text(visit.label).font(.timelineSerif(17))
                Text("\(visit.date) · \(visit.time) · \(visit.duration)")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if visit.photosCount > 0 {
                Text("\(visit.photosCount) 📷").font(.caption).foregroundStyle(.tertiary)
            }
        }
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.timelineSerif(22, weight: .semibold))
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }
}

// MARK: - Roles (the SwiftUI twin of `PlaceRoleUi.kt`)

/// The Home/Work confirm surface (place-roles.md PR-3): an **inferred** role is a question the UI
/// asks, a user-authored one is a fact it displays. Rendering a guess as a fact is the failure this
/// feature exists to avoid, which is why this switches on `isSuggestion` rather than on the token.
///
/// ⚠ Dismissal *persists* — it writes `role = none` as a user-authored answer rather than hiding the
/// card for this session, so "Not my home" is the honest label, not "Not now".
///
/// ⚠ An unrecognised role (a newer peer's `gym`) renders **nothing**: the raw wire token is not a
/// name, and printing it at the user is worse than staying quiet. The role still syncs regardless.
private struct PlaceRoleSection: View {
    let role: PlaceRoleState?
    let accent: Color
    let onSetRole: (String) -> Void

    var body: some View {
        if let role, let name = timelinePlaceRoleName(role.role) {
            if role.isSuggestion {
                VStack(alignment: .leading, spacing: 8) {
                    Text(prompt(for: role.role, name: name))
                        .font(.subheadline)
                    HStack(spacing: 8) {
                        Button(refusal(for: role.role, name: name)) {
                            onSetRole(PlaceRoleNames.shared.NONE)
                        }
                        .buttonStyle(.bordered)
                        Button("Yes") { onSetRole(role.role) }
                            .buttonStyle(.borderless)
                            .foregroundStyle(accent)
                    }
                    .font(.subheadline)
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.secondarySystemFill).opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
            } else if role.isConfirmed {
                Text(name.uppercased())
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(accent)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 6))
            }
        }
    }

    private func prompt(for role: String, name: String) -> String {
        "Is this your \(name.lowercased())?"
    }

    private func refusal(for role: String, name: String) -> String {
        "Not my \(name.lowercased())"
    }
}

// MARK: - Merge picker

/// "Which place is this really part of?" — the manual merge picker (§9.4). Deliberately a plain list
/// of the user's own places rather than a search: manual merge exists for the *few* pairs the
/// automatic pass will not touch (two places the user named differently), and whoever reaches for it
/// already knows which two they mean.
private struct MergePlaceSheet: View {
    let candidates: [Place]
    let onPick: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(candidates, id: \.id) { candidate in
                        Button {
                            onPick(candidate.id)
                            dismiss()
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(candidate.name.isEmpty ? "Unnamed place" : candidate.name)
                                    .foregroundStyle(.primary)
                                let area = candidate.neighborhood.isEmpty ? candidate.city : candidate.neighborhood
                                if !area.isEmpty {
                                    Text(area).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                } header: {
                    Text("Fold this place into")
                } footer: {
                    Text("Both places keep their visits. Nothing is deleted — you can undo this later.")
                }
            }
            .navigationTitle("Same place as…")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}
