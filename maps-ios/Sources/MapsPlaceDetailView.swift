import SwiftUI
import TenshuSdk

// Place detail (`maps-app.md` §6 / handoff §1.2) — the SwiftUI twin of `PlaceDetailScreen` +
// `PlaceDetailContent`, in the same order: map hero, the offline honesty line, the contextual
// "keep this area offline" row, identity, action pills, visit history, the Directions hand-off
// note, photos, and the "Trips through here → Timeline" cross-link (routes belong to Timeline, D1).
//
// Retains the `PlaceDetailViewModel` — not just its state flow — so every action (`setSaved`,
// `keepOffline`) reaches the same instance the screen observes.
@MainActor
private final class MapsPlaceDetailModel: ObservableObject {
    @Published var state: MapsPlaceDetailUiState?
    private let vm: MapsPlaceDetailViewModel

    init(placeId: String) {
        vm = MapsSurface.shared.placeViewModel(placeId: placeId)
    }

    func observe() async {
        do { for try await value in vm.uiState { state = value } } catch {}
    }

    func setSaved(_ saved: Bool) { vm.setSaved(saved: saved) }

    func keepOffline() { vm.keepOffline() }
}

struct MapsPlaceDetailView: View {
    @StateObject private var model: MapsPlaceDetailModel
    private let today = MapsSurface.shared.today()

    init(placeId: String) {
        _model = StateObject(wrappedValue: MapsPlaceDetailModel(placeId: placeId))
    }

    var body: some View {
        Group {
            switch model.state.map({ onEnum(of: $0) }) {
            case .some(.loaded(let loaded)):
                loadedBody(loaded)
            case .some(.notFound):
                ContentUnavailableCompat(title: "Place not found", systemImage: "mappin.slash")
            default:
                ProgressView()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
    }

    @ViewBuilder private func loadedBody(_ loaded: MapsPlaceDetailUiStateLoaded) -> some View {
        let place = loaded.place
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                hero(place: place, loaded: loaded)
                identity(place)
                actions(place)
                history(place)
                MapsNote(
                    systemImage: "location.north.line",
                    text: "Directions hand off to your navigation app. Tenshu doesn't route — and doesn't watch you travel."
                )
                if !place.photos.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        MapsEyebrow(text: "Photos")
                        HStack(spacing: 2) {
                            ForEach(Array(place.photos.enumerated()), id: \.offset) { _, photo in
                                Rectangle().fill(Color(hex: photo.tone) ?? Color.accentColor.opacity(0.3))
                            }
                        }
                        .frame(height: 100)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                }
                timelineTripsLink(place)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .navigationTitle(place.name.isEmpty ? "Place" : place.name)
    }

    // MARK: - Hero

    @ViewBuilder private func hero(place: MapsPlace, loaded: MapsPlaceDetailUiStateLoaded) -> some View {
        VStack(spacing: 8) {
            // ⚠ Gated on a real coordinate, not on nullability: `MapsPlaceProjection` fills
            // `location` unconditionally and `(0, 0)` is the Atlantic (D11). See
            // `MapsPlace.hasCoordinate`.
            if place.hasCoordinate, let location = place.location {
                MapsPlaceMapCard(
                    latitude: location.latitude,
                    longitude: location.longitude,
                    regionBundlePath: loaded.regionBundlePath
                )
            } else {
                NoCoordinateHero()
            }
            HStack(spacing: 5) {
                Image(systemName: "lock").font(.system(size: 10))
                Text("This map is drawn on your device from map data you hold. Nothing about this place leaves it.")
                    .font(.caption2)
            }
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)

            // The contextual "keep this area offline" trigger, directly under the map it is about.
            // Shown only when a region actually covers this place.
            if let offer = loaded.offlineOffer {
                OfflineOfferRow(
                    offer: offer,
                    downloading: loaded.offlineDownloading,
                    progress: loaded.offlineProgress?.floatValue,
                    failed: loaded.offlineFailed,
                    onKeepOffline: { model.keepOffline() }
                )
            }
        }
    }

    // MARK: - Identity, actions, history

    @ViewBuilder private func identity(_ place: MapsPlace) -> some View {
        HStack(alignment: .top, spacing: 12) {
            CategoryAvatar(category: place.category, size: 52)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(place.name.isEmpty ? "Unnamed place" : place.name).font(.title2.weight(.semibold))
                    if place.section == .yours, let label = place.label { MapsLabelChip(text: label) }
                }
                Text(place.address.isEmpty
                     ? MapsGlyph.categoryName(place.category)
                     : "\(MapsGlyph.categoryName(place.category)) · \(place.address)")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if place.section != .yours {
                SavedToggle(saved: place.saved) { model.setSaved($0) }
            }
        }
    }

    @ViewBuilder private func actions(_ place: MapsPlace) -> some View {
        HStack(spacing: 8) {
            if place.section == .yours {
                ActionPill(systemImage: "pencil", label: "Edit") {}
            } else {
                ActionPill(
                    systemImage: place.saved ? "bookmark.fill" : "bookmark",
                    label: place.saved ? "Saved" : "Save",
                    filled: true
                ) { model.setSaved(!place.saved) }
            }
            ActionPill(systemImage: "location.north.line", label: "Directions") {}
            ActionPill(systemImage: "square.and.arrow.up", label: "Share") {}
        }
    }

    @ViewBuilder private func history(_ place: MapsPlace) -> some View {
        MapsCard {
            VStack(alignment: .leading, spacing: 12) {
                MapsEyebrow(text: "History")
                if place.visitCount == 0 && place.recentVisits.isEmpty {
                    // ⚠ Honest empty: `:place-service` carries no visit history yet
                    // (`MapsPlaceProjection`), so this is the real store's normal state.
                    Text("No visits recorded here yet.")
                        .font(.subheadline).foregroundStyle(.secondary)
                } else {
                    if let last = place.lastVisitText(today: today) {
                        Text("Last visit \(last.lowercased())").font(.subheadline)
                    }
                    if let visits = place.visitCountText {
                        Text(visits).font(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(Array(place.recentVisits.enumerated()), id: \.offset) { _, date in
                        HStack(spacing: 8) {
                            Circle().fill(Color.accentColor.opacity(0.5)).frame(width: 6, height: 6)
                            Text(date.display).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                if let first = place.firstVisit {
                    Text("First seen \(first.display)").font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding(16)
        }
    }

    @ViewBuilder private func timelineTripsLink(_ place: MapsPlace) -> some View {
        MapsCard {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill(Color.accentColor.opacity(0.16))
                    Image(systemName: "point.topleft.down.curvedto.point.bottomright.up")
                        .foregroundStyle(Color.accentColor)
                }
                .frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Trips through here").font(.subheadline.weight(.semibold))
                    Text(place.visitCount > 0
                         ? "\(place.visitCount) arrivals recorded in Timeline"
                         : "Routes and journeys live in Timeline")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Image(systemName: "arrow.up.right").font(.caption).foregroundStyle(.tertiary)
            }
            .padding(12)
        }
    }
}

/// A detail action pill (`ActionPill`).
private struct ActionPill: View {
    let systemImage: String
    let label: String
    var filled: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: systemImage).font(.system(size: 18))
                Text(label).font(.caption)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
        }
        .buttonStyle(.plain)
        .foregroundStyle(filled ? Color.white : Color.primary)
        .background(
            filled ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(Color(.tertiarySystemFill)),
            in: RoundedRectangle(cornerRadius: 12)
        )
    }
}

/// "Keep this area offline" (`maps-region-delivery.md` §7.1 track 3d) — the `OfflineOfferRow` twin.
///
/// ⚠⚠ On iOS the map above does **not** stream (B-4): without a region it is the bundled demo or a
/// schematic sketch, so the copy never says "the map keeps streaming". What the download buys is a
/// real map of the area that works with **no network at all**, and the row names the price up front
/// because a region is hundreds of megabytes. The store is durable since P4-1 (`IosFileDatabase`),
/// so the old "this session only" caveat is gone.
private struct OfflineOfferRow: View {
    let offer: OfflineRegionOffer
    let downloading: Bool
    let progress: Float?
    let failed: Bool
    let onKeepOffline: () -> Void

    private var actionable: Bool { !offer.installed && !downloading }

    var body: some View {
        HStack(spacing: 8) {
            leading
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(titleColor)
                if let hint {
                    Text(hint).font(.caption2).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture { if actionable { onKeepOffline() } }
    }

    @ViewBuilder private var leading: some View {
        if downloading, let progress {
            // Determinate: the ring is the actual bytes on disk (the proxy's staging file), so a
            // stalled download visibly stalls instead of spinning a lie.
            ProgressView(value: Double(progress)).progressViewStyle(.circular).controlSize(.mini)
        } else if downloading {
            ProgressView().controlSize(.mini)
        } else {
            Image(systemName: offer.installed ? "lock" : "arrow.down.circle")
                .font(.caption)
                .foregroundStyle(failed ? Color(.systemRed) : (offer.installed ? Color.secondary : Color.accentColor))
        }
    }

    private var title: String {
        if downloading { return "Keeping \(offer.name) offline — \(Int((progress ?? 0) * 100))%" }
        if offer.installed { return "\(offer.name) is kept offline" }
        if failed { return "Couldn't keep \(offer.name) offline" }
        return "Keep \(offer.name) offline"
    }

    private var titleColor: Color {
        if failed { return Color(.systemRed) }
        if offer.installed || downloading { return .secondary }
        return .accentColor
    }

    private var hint: String? {
        if failed { return "Nothing else is affected. Tap to try again." }
        if actionable {
            return "\(formatRegionSize(offer.byteSize)) · works with no network at all"
        }
        return nil
    }
}

/// The hero for a place with no coordinate — a flat, honest placeholder rather than a map of
/// `(0, 0)`. The Compose twin is `MapSnippet`'s schematic fallback.
private struct NoCoordinateHero: View {
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 20).fill(Color.accentColor.opacity(0.12))
            VStack(spacing: 6) {
                Image(systemName: "mappin.slash").font(.title2).foregroundStyle(.tertiary)
                Text("No location recorded for this place yet")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(height: 208)
    }
}

/// `ContentUnavailableView` is iOS 17+; the deployment target is 16.0.
struct ContentUnavailableCompat: View {
    let title: String
    let systemImage: String

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: systemImage).font(.largeTitle).foregroundStyle(.tertiary)
            Text(title).font(.headline).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
