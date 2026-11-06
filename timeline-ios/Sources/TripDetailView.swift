import SwiftUI
import TenshuSdk

// Trip detail (§1.2). Recursive: a `TripEntry.NestedTrip` pushes another `TripDetailView` for the
// nested trip's id. Bound to `TripDetailViewModel` via `TimelineSurface`.
struct TripDetailView: View {
    @StateObject private var model: DetailModel<TripDetailUiState>
    @Environment(\.colorScheme) private var colorScheme

    init(tripId: String) {
        _model = StateObject(wrappedValue: DetailModel {
            TimelineSurface.shared.tripViewModel(tripId: tripId).uiState
        })
    }

    var body: some View {
        Group {
            switch model.state.map({ onEnum(of: $0) }) {
            case .some(.loaded(let loaded)):
                loadedBody(
                    loaded.trip,
                    strength: loaded.personalisationStrength,
                    regionBundlePath: loaded.regionBundlePath
                )
            case .some(.notFound):
                ContentUnavailableCompat(title: "Trip not found", systemImage: "questionmark.circle")
            default:
                ProgressView()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
    }

    @ViewBuilder private func loadedBody(
        _ trip: Trip,
        strength: Float,
        regionBundlePath: String?
    ) -> some View {
        // The VM's personalisation strength, honoured instead of painting the raw dominant colour.
        let personalisation = TimelinePersonalisation.resolve(
            dominantHex: trip.cover.dominantColor,
            strength: strength,
            scheme: colorScheme
        )
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                RoundedRectangle(cornerRadius: 20)
                    .fill(personalisation.soft)
                    .frame(height: 180)
                    .overlay(alignment: .bottomLeading) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(trip.title).font(.timelineSerif(30, weight: .semibold))
                            Text(trip.dateRange).font(.subheadline).foregroundStyle(.secondary)
                        }
                        .padding(16)
                    }

                // The trip's route over the offline basemap (P3) — iOS had NO route hero here at
                // all, while Compose has shown one since `maps-app.md` §2 step 3. Placed under the
                // cover hero rather than replacing it, exactly as `TripDetailScreen` composes them:
                // the cover carries the trip's identity, the map carries its shape.
                //
                // ⚠ Gate at two points, the same `route.size < 2` early return `TripRouteMap` makes —
                // a trip whose members had no coordinates yet has an empty route, and a map of
                // nothing is worse than no map.
                if trip.route.count >= 2 {
                    TimelineRouteMapCard(
                        route: trip.route,
                        accent: personalisation.accent,
                        regionBundlePath: regionBundlePath
                    )
                }

                HStack(spacing: 20) {
                    stat("\(trip.daysSpan)", "days")
                    stat("\(trip.placesCount)", "places")
                    stat("\(trip.photosCount)", "photos")
                }
                .padding(.horizontal, 4)

                if !trip.tags.isEmpty {
                    HStack(spacing: 8) {
                        ForEach(trip.tags, id: \.self) { tag in
                            Text(tag).font(.caption).padding(.horizontal, 10).padding(.vertical, 4)
                                .background(Color(.secondarySystemFill), in: Capsule())
                        }
                    }
                }

                ForEach(Array(trip.contents.enumerated()), id: \.offset) { _, entry in
                    tripEntry(entry, strength: strength)
                }
            }
            .padding(20)
        }
    }

    @ViewBuilder private func tripEntry(_ entry: TripEntry, strength: Float) -> some View {
        switch onEnum(of: entry) {
        case .dayEntry(let day):
            VStack(alignment: .leading, spacing: 8) {
                Eyebrow("\(day.dayLabel) · \(day.dateLong)")
                ForEach(Array(day.moments.enumerated()), id: \.offset) { _, moment in
                    NavigationLink(value: TimelineRoute.moment(id: moment.id)) {
                        entryMomentRow(moment, strength: strength)
                    }
                    .buttonStyle(.plain)
                }
            }
        case .momentItem(let item):
            NavigationLink(value: TimelineRoute.moment(id: item.moment.id)) {
                entryMomentRow(item.moment, strength: strength)
            }
            .buttonStyle(.plain)
        case .nestedTrip(let nested):
            NavigationLink(value: TimelineRoute.trip(id: nested.trip.id)) {
                HStack(spacing: 12) {
                    RoundedRectangle(cornerRadius: 10)
                        .fill(TimelinePersonalisation.resolve(
                            dominantHex: nested.trip.cover.dominantColor,
                            strength: strength,
                            scheme: colorScheme
                        ).soft)
                        .frame(width: 44, height: 44)
                    VStack(alignment: .leading, spacing: 2) {
                        Eyebrow(nested.dayLabel)
                        Text(nested.trip.title).font(.timelineSerif(18, weight: .semibold))
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
                }
                .padding(12)
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(.plain)
        case .collapsed(let collapsed):
            Text(collapsed.label).font(.footnote).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .center).padding(.vertical, 4)
        @unknown default:
            EmptyView()
        }
    }

    private func entryMomentRow(_ moment: Moment, strength: Float) -> some View {
        HStack(spacing: 12) {
            Image(systemName: moment.kind.sfSymbol)
                .foregroundStyle(TimelinePersonalisation.resolve(
                    dominantHex: moment.photos.first?.dominantColor,
                    strength: strength,
                    scheme: colorScheme
                ).accent)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(moment.title).font(.timelineSerif(17))
                Text("\(moment.time) · \(moment.place)").font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.timelineSerif(22, weight: .semibold))
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }
}
