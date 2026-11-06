import SwiftUI
import TenshuSdk

// Moment detail (§1.3). The moment can live in a home day or any (nested) trip; the VM resolves it
// by id. `moment.kind` drives the accent + glyph. Bound to `MomentDetailViewModel` via `TimelineSurface`.
//
// Retains the view model — not just its state flow — because the screen now *writes*: the
// "Wrong place?" picker calls `rename(label:)` and sources its candidates from `suggestions()`
// (the Compose `ChoosePlaceDialog` wiring, MomentDetailScreen.kt:160).
@MainActor
private final class MomentDetailModel: ObservableObject {
    @Published var state: MomentDetailUiState?
    private let vm: MomentDetailViewModel

    init(momentId: String) {
        vm = TimelineSurface.shared.momentViewModel(momentId: momentId)
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
}

struct MomentDetailView: View {
    @StateObject private var model: MomentDetailModel
    @Environment(\.colorScheme) private var colorScheme
    @State private var choosingPlace = false

    init(momentId: String) {
        _model = StateObject(wrappedValue: MomentDetailModel(momentId: momentId))
    }

    var body: some View {
        Group {
            switch model.state.map({ onEnum(of: $0) }) {
            case .some(.loaded(let loaded)):
                loadedBody(
                    loaded.moment,
                    route: loaded.route,
                    strength: loaded.personalisationStrength,
                    regionBundlePath: loaded.regionBundlePath
                )
            case .some(.notFound):
                ContentUnavailableCompat(title: "Moment not found", systemImage: "questionmark.circle")
            default:
                ProgressView()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $choosingPlace) {
            ChoosePlaceSheet(
                current: currentPlaceName,
                loadSuggestions: { await model.suggestions() },
                onConfirm: { model.rename($0) }
            )
        }
        .task { await model.observe() }
    }

    private var currentPlaceName: String {
        guard case .some(.loaded(let loaded)) = model.state.map({ onEnum(of: $0) }) else { return "" }
        return loaded.moment.place
    }

    @ViewBuilder private func loadedBody(
        _ moment: Moment,
        route: [GeoPoint],
        strength: Float,
        regionBundlePath: String?
    ) -> some View {
        // The shared VM has always published a personalisation strength; the accent is now the
        // Amethyst-based mix it describes rather than the raw dominant colour (Personalisation.kt).
        let personalisation = TimelinePersonalisation.resolve(
            dominantHex: moment.cover?.dominantColor ?? moment.photos.first?.dominantColor,
            strength: strength,
            scheme: colorScheme
        )
        let accent = personalisation.accent
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if moment.kind == .movement && route.count >= 2 {
                    // M5's iOS sibling: the movement's hero is its route, drawn from the trace's
                    // decoded polyline the shared VM fetched — over the real offline basemap since
                    // P3, over the normalized sketch when there are no tiles to draw it on. The glyph
                    // card below stays the fallback for a dropped/undecodable path.
                    TimelineRouteMapCard(
                        route: route,
                        accent: accent,
                        regionBundlePath: regionBundlePath
                    )
                } else {
                    RoundedRectangle(cornerRadius: 20)
                        .fill(personalisation.soft)
                        .frame(height: 200)
                        .overlay(
                            Image(systemName: moment.kind.sfSymbol)
                                .font(.system(size: 44, weight: .semibold))
                                .foregroundStyle(accent)
                        )
                }

                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 8) {
                        Text(moment.time).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        if moment.isCurrent {
                            Text("● now").font(.caption2.weight(.bold)).foregroundStyle(.green)
                        }
                    }
                    Text(moment.title).font(.timelineSerif(30, weight: .semibold))
                    // A visit's title already IS its place name, so this subject line would only
                    // echo it (the Compose `moment.place != headline` guard).
                    if !moment.place.isEmpty && moment.place != moment.title {
                        Text(moment.place).font(.headline).foregroundStyle(.secondary)
                    }
                    HStack(spacing: 16) {
                        if let duration = moment.durationMin {
                            Label("\(duration.intValue) min", systemImage: "clock")
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
                        // M5-lite parity: a movement's two other facts (the Compose detail's
                        // stat cells). The mode is a raw wire string — an unknown newer mode
                        // must still render, so it is displayed capitalized as-is.
                        if let distance = moment.distanceM {
                            Label(distanceText(metres: distance.int64Value), systemImage: "arrow.triangle.swap")
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
                        if let mode = moment.movementMode {
                            Label(mode.capitalized, systemImage: moment.kind.sfSymbol)
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                }

                if moment.photos.count > 0 {
                    Eyebrow("\(moment.photos.count) photos")
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 8)], spacing: 8) {
                        ForEach(Array(moment.photos.enumerated()), id: \.offset) { _, photo in
                            RoundedRectangle(cornerRadius: 10)
                                .fill(Color(timelineHex: photo.dominantColor).opacity(0.5))
                                .aspectRatio(1, contentMode: .fill)
                                // Cross-app open (`control-cross-app-open.md` TT-2a): the photo is
                                // a backed-up Drive file, so tapping it opens Drive.
                                //
                                // ⚠ Only when the photo has a path — `uri` IS the file's path, and
                                // a placeholder drawn from a colour alone has nothing to open. An
                                // inert frame beats one that offers a tap it cannot honour.
                                .openInOwningApp(path: photo.uri)
                        }
                    }
                }

                placeSection(moment, personalisation: personalisation)
            }
            .padding(20)
        }
    }

    // MARK: - "At this place" (MomentDetailScreen.kt:232 / :160)

    /// The place context card + the "Wrong place?" correction, the two affordances the iOS moment
    /// detail was missing: it rendered the place as a plain, dead `Text`. A visit/event's place is a
    /// real destination — tappable through to `PlaceDetailView` when the moment carries a `placeId`
    /// — and a mis-named stay is fixable where it is noticed (§4).
    @ViewBuilder private func placeSection(_ moment: Moment, personalisation: TimelinePersonalisation) -> some View {
        // ⚠ Deliberately NOT gated on `place != title`: a visit's title *is* its place name, and
        // that is exactly the moment whose tap-through matters most (the Compose screen renders the
        // card unconditionally for the same reason — only the subject *line* above is deduped).
        if !moment.place.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                if let placeId = moment.placeId {
                    NavigationLink(value: TimelineRoute.place(id: placeId)) {
                        placeCard(moment.place, personalisation: personalisation, tappable: true)
                    }
                    .buttonStyle(.plain)
                } else {
                    // No place row behind it (a movement, or a stay whose place never resolved):
                    // still show where it was, but do not fake a destination.
                    placeCard(moment.place, personalisation: personalisation, tappable: false)
                }
                if moment.placeId != nil {
                    Button("Wrong place? Choose the right one") { choosingPlace = true }
                        .font(.footnote)
                        .foregroundStyle(personalisation.accent)
                }
            }
        }
    }

    private func placeCard(_ place: String, personalisation: TimelinePersonalisation, tappable: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Eyebrow("At this place")
            HStack(spacing: 6) {
                Text(place).font(.timelineSerif(20)).foregroundStyle(.primary)
                if tappable {
                    Image(systemName: "arrow.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(personalisation.accent)
                }
                Spacer(minLength: 0)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(personalisation.soft.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
        .accessibilityHint(tappable ? Text("Opens this place") : Text(""))
    }
}

/// Metres → "850 m" / "1.2 km" (the Compose `distanceText` shape: km with one decimal from 1 km).
private func distanceText(metres: Int64) -> String {
    if metres < 1000 { return "\(metres) m" }
    let km = Double(metres) / 1000.0
    return String(format: "%.1f km", km)
}
