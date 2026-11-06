import SwiftUI
import TenshuSdk

// The Places surface (`maps-app.md` §6 / handoff §1.1) — the SwiftUI twin of `PlacesScreen`.
//
// Places is the app's **single** top-level destination: Your places / Saved / For you / Contribute
// are tabs *inside* it (D1), which is why one `PlacesViewModel` owns all four. The IA is mirrored
// from the Compose screen rather than re-derived — a small-caps title bar, the four secondary tabs,
// list-first content per tab, and the trust cue at the foot.

@MainActor
private final class PlacesModel: ObservableObject {
    @Published var state: PlacesUiState?
    private let vm: PlacesViewModel

    init() {
        vm = MapsSurface.shared.placesViewModel()
    }

    func observe() async {
        // SKIE renders a `StateFlow` as a throwing `AsyncSequence`; it never actually throws here
        // (the warning about an unreachable `catch` is a known SKIE artefact, §6 of the parity doc).
        do { for try await value in vm.uiState { state = value } } catch {}
    }

    func select(_ tab: MapsTab) { vm.selectTab(tab: tab) }

    func setSaved(_ id: String, _ saved: Bool) { vm.setSaved(id: id, saved: saved) }
}

struct PlacesView: View {
    let onOpenPlace: (String) -> Void
    let onOpenRegions: () -> Void

    @StateObject private var model = PlacesModel()
    private let today = MapsSurface.shared.today()

    var body: some View {
        VStack(spacing: 0) {
            tabBar
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    switch model.state?.tab ?? .yours {
                    case .yours: yoursTab
                    case .saved: savedTab
                    case .forYou: forYouTab
                    case .contribute: ContributeTab()
                    }
                    MapsTrustCue().padding(.top, 8)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
        }
        .navigationTitle("MAPS")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                // The gear opens the offline-region picker — `maps-region-delivery.md` D5 puts the
                // picker in Maps, and this is the entry point the Compose top bar uses too.
                Button { onOpenRegions() } label: { Image(systemName: "gearshape") }
                    .accessibilityLabel("Offline maps")
            }
        }
        .task { await model.observe() }
    }

    // The four secondary tabs. A segmented picker is the native shape of Compose's
    // `SecondaryScrollableTabRow`; the *order and membership* are what matter and they match.
    private var tabBar: some View {
        Picker("", selection: Binding(
            get: { model.state?.tab ?? .yours },
            set: { model.select($0) }
        )) {
            Text("Yours").tag(MapsTab.yours)
            Text("Saved").tag(MapsTab.saved)
            Text("For you").tag(MapsTab.forYou)
            Text("Contribute").tag(MapsTab.contribute)
        }
        .pickerStyle(.segmented)
        .padding(.horizontal, 20)
        .padding(.bottom, 8)
    }

    @ViewBuilder private var yoursTab: some View {
        Blurb("The places you actually go. Derived from your own timeline, on your device.")
        let places = model.state?.yours ?? []
        if places.isEmpty {
            EmptyTabNote(
                systemImage: "mappin.slash",
                text: MapsSurface.shared.controlReachable()
                    ? "No places yet. They appear as Timeline records the places you visit."
                    : "Nothing to show until Tenshu Control is unlocked."
            )
        }
        ForEach(places, id: \.id) { place in
            Button { onOpenPlace(place.id) } label: {
                MapsCard { PlaceRow(place: place, today: today) }
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder private var savedTab: some View {
        let lists = model.state?.savedLists ?? []
        let saved = model.state?.saved ?? []
        if lists.isEmpty && saved.isEmpty {
            // ⚠ Honest, and permanent for now: `:place-service` carries no `saved` field, so
            // `PlaceProjectionRepository.setSaved` is a documented no-op and `savedLists()` is
            // empty. This tab is empty against the real store by construction, not by accident —
            // the thin-screens tradeoff `MapsPlaceProjection` records.
            EmptyTabNote(
                systemImage: "bookmark",
                text: "Saved lists arrive when places can carry a bookmark — the store has no field for one yet."
            )
        }
        ForEach(lists, id: \.id) { list in
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    MapsEyebrow(text: list.name)
                    Spacer()
                    Text(list.count == 1 ? "1 place" : "\(list.count) places")
                        .font(.caption2).foregroundStyle(.secondary)
                }
                MapsCard {
                    VStack(spacing: 0) {
                        ForEach(saved.filter { $0.savedList == list.id }, id: \.id) { place in
                            Button { onOpenPlace(place.id) } label: {
                                PlaceRow(
                                    place: place,
                                    today: today,
                                    onToggleSaved: { model.setSaved(place.id, $0) }
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder private var forYouTab: some View {
        Blurb("Suggestions built on this device from what you already have — never from a profile held elsewhere.")
        let recommended = model.state?.recommended ?? []
        if recommended.isEmpty {
            EmptyTabNote(
                systemImage: "sparkles",
                text: "Nothing suggested yet."
            )
        }
        ForEach(recommended, id: \.id) { place in
            RecommendedCard(place: place) { model.setSaved(place.id, true) }
        }
    }
}

/// One place row — avatar, name (+ label chip), address · visits, and a trailing accessory. The
/// `PlaceRow` + `PlaceRowTrailing` twin.
private struct PlaceRow: View {
    let place: MapsPlace
    let today: Kotlinx_datetimeLocalDate
    /// `nil` = the chevron (a row that opens the detail); non-nil = the Saved-tab bookmark toggle.
    var onToggleSaved: ((Bool) -> Void)? = nil

    var body: some View {
        HStack(spacing: 12) {
            CategoryAvatar(category: place.category)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(place.name.isEmpty ? "Unnamed place" : place.name)
                        .font(.headline)
                        .lineLimit(1)
                    if let label = place.label { MapsLabelChip(text: label) }
                }
                if let sub = subtitle {
                    Text(sub).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if let onToggleSaved {
                SavedToggle(saved: place.saved, onToggle: onToggleSaved)
            } else {
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
            }
        }
        .padding(12)
        .contentShape(Rectangle())
    }

    /// "address · 12 visits · 3 days ago", dropping whatever the store cannot answer.
    ///
    /// ⚠ Against the real `:place-service` this is usually **empty** — address, visit counts and
    /// visit history have no home in the store yet (`MapsPlaceProjection`). Composing the parts
    /// and dropping the blanks is what keeps that honest instead of rendering " · 0 visits".
    private var subtitle: String? {
        var parts: [String] = []
        if !place.address.isEmpty { parts.append(place.address) }
        if let visits = place.visitCountText { parts.append(visits) }
        if let last = place.lastVisitText(today: today) { parts.append(last) }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

/// The "For you" card — photo strip, provenance chip, name, Save / Dismiss.
private struct RecommendedCard: View {
    let place: MapsPlace
    let onSave: () -> Void

    var body: some View {
        MapsCard {
            VStack(alignment: .leading, spacing: 0) {
                PhotoStrip(photos: place.photos, height: 92)
                VStack(alignment: .leading, spacing: 6) {
                    if let reason = place.reason {
                        HStack(spacing: 5) {
                            Image(systemName: MapsGlyph.reason(reason.kind)).font(.caption2)
                            Text(reason.text).font(.caption2)
                        }
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Color.accentColor.opacity(0.14), in: Capsule())
                        .foregroundStyle(Color.accentColor)
                    }
                    Text(place.name).font(.headline)
                    Text("\(MapsGlyph.categoryName(place.category)) · \(place.address)")
                        .font(.caption).foregroundStyle(.secondary)
                    HStack(spacing: 8) {
                        Button(action: onSave) {
                            Label(place.saved ? "Saved" : "Save", systemImage: place.saved ? "bookmark.fill" : "bookmark")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        Button("Dismiss") {}
                            .buttonStyle(.bordered)
                    }
                    .padding(.top, 4)
                }
                .padding(12)
            }
        }
    }
}

/// The dominant-tone photo placeholders (`PhotoStrip`). Never photoreal — a `PlacePhoto` carries
/// only a tone, which is the Timeline `TtPhoto` precedent.
private struct PhotoStrip: View {
    let photos: [PlacePhoto]
    let height: CGFloat

    var body: some View {
        if photos.isEmpty {
            EmptyView()
        } else {
            HStack(spacing: 2) {
                ForEach(Array(photos.enumerated()), id: \.offset) { _, photo in
                    Rectangle().fill(Color(hex: photo.tone) ?? Color.accentColor.opacity(0.3))
                }
            }
            .frame(height: height)
        }
    }
}

/// The bookmark toggle on a Saved row / the detail header (`SavedToggle`).
struct SavedToggle: View {
    let saved: Bool
    let onToggle: (Bool) -> Void

    var body: some View {
        Button { onToggle(!saved) } label: {
            Image(systemName: saved ? "bookmark.fill" : "bookmark")
                .foregroundStyle(saved ? Color.accentColor : Color.secondary)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(saved ? "Remove bookmark" : "Save this place")
    }
}

/// Contribute — a **static, honest stub** (D3): the actions exist, the queue does not moderate yet,
/// and the copy says so rather than implying a live pipeline.
private struct ContributeTab: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Blurb("Add what's missing. Contributions are queued on your device — nothing is published yet.")
            ContributeAction(
                systemImage: "mappin.and.ellipse",
                title: "Add a place",
                detail: "Drop a pin for somewhere that isn't on the map."
            )
            ContributeAction(
                systemImage: "flag",
                title: "Report a problem",
                detail: "Wrong name, moved, closed down."
            )
            MapsNote(
                systemImage: "lock",
                text: "Queued locally. There is no moderation pipeline yet, so nothing leaves this device."
            )
            MapsEyebrow(text: "Your contributions")
            MapsCard {
                Text("Nothing pending")
                    .font(.subheadline).foregroundStyle(.secondary)
                    .padding(12)
            }
        }
    }
}

private struct ContributeAction: View {
    let systemImage: String
    let title: String
    /// Named `detail`, not `body` — `body` is `View`'s own requirement.
    let detail: String

    var body: some View {
        MapsCard {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill(Color.accentColor.opacity(0.16))
                    Image(systemName: systemImage).foregroundStyle(Color.accentColor)
                }
                .frame(width: 46, height: 46)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline)
                    Text(detail).font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
            }
            .padding(12)
        }
    }
}

private struct Blurb: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.subheadline).foregroundStyle(.secondary)
    }
}

/// What a tab shows when it has nothing — an honest line, never invented rows (D4).
private struct EmptyTabNote: View {
    let systemImage: String
    let text: String

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: systemImage).font(.title2).foregroundStyle(.tertiary)
            Text(text)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
    }
}

extension Color {
    /// A `#RRGGBB` tone from the shared model (`PlacePhoto.tone`) — the reverse of
    /// `Color.basemapHex`, which the map layer uses for the route line.
    init?(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        self.init(
            red: Double((v >> 16) & 0xFF) / 255.0,
            green: Double((v >> 8) & 0xFF) / 255.0,
            blue: Double(v & 0xFF) / 255.0
        )
    }
}
