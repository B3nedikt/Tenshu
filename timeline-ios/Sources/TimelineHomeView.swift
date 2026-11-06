import SwiftUI
import TenshuSdk

// Home (§1.1) — the single top-level surface: a small-caps title, a segmented Home / Places /
// Activities control, then the active tab's content. Bound to the real `TimelineHomeViewModel`
// (which owns all three tabs' data) via `TimelineSurface`.
struct TimelineHomeView: View {
    @StateObject private var model = HomeModel()
    @State private var showingSettings = false
    /// Read on appear and whenever Settings closes — the switch lives there, and a location denial
    /// can turn it back off on its own, so a value captured once would go stale.
    @State private var captureAvailable = TimelineCaptureRuntime.shared.isCaptureAvailable()
    @State private var captureEnabled = TimelineCaptureRuntime.shared.isCaptureEnabled()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Picker("", selection: tabBinding) {
                    Text("Home").tag(HomeTab.home)
                    Text("Places").tag(HomeTab.places)
                    Text("Activities").tag(HomeTab.activities)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)

                switch model.state?.tab ?? .home {
                case .home: homeFeed
                case .places: placesTab
                case .activities: activitiesTab
                @unknown default: homeFeed
                }
            }
            .padding(.vertical, 12)
        }
        .navigationTitle("Timeline")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showingSettings = true } label: { Image(systemName: "gearshape") }
                    .accessibilityIdentifier("settings")
            }
        }
        .sheet(isPresented: $showingSettings, onDismiss: refreshCaptureState) {
            NavigationStack { SettingsView() }
        }
        .onAppear(perform: refreshCaptureState)
        .task { await model.observe() }
        .overlay { if model.state == nil { ProgressView() } }
    }

    private func refreshCaptureState() {
        captureAvailable = TimelineCaptureRuntime.shared.isCaptureAvailable()
        captureEnabled = TimelineCaptureRuntime.shared.isCaptureEnabled()
    }

    private var tabBinding: Binding<HomeTab> {
        Binding(get: { model.state?.tab ?? .home }, set: { model.selectTab($0) })
    }

    // MARK: Home feed — day chapters, the interlude, bound-trip covers

    @ViewBuilder private var homeFeed: some View {
        let state = model.state
        // The shared VM's personalisation knob (§5) — iOS painted raw dominant colours and ignored
        // it; every tinted mark below now goes through `TimelinePersonalisation`.
        let strength = state?.personalisationStrength ?? 0
        // ⚠⚠ `hasNothing`, never `days.isEmpty`: every list starts empty, so deciding from the list
        // alone would tell a user with years of history that they have none while the first read
        // runs (TimelineHomeEmptyStateTest pins the flag). Before this the feed was simply blank
        // until iOS delivered a first visit — minutes after leaving a place, on its own schedule.
        if state?.hasNothing == true {
            FeedEmptyState(
                captureAvailable: captureAvailable,
                captureEnabled: captureEnabled,
                onOpenSettings: { showingSettings = true }
            )
        }
        VStack(alignment: .leading, spacing: 28) {
            ForEach(Array((state?.days ?? []).enumerated()), id: \.offset) { index, day in
                DayChapter(day: day, strength: strength)
                if index == 0, let suggestion = state?.suggestion {
                    InterludeCard(suggestion: suggestion,
                                  strength: strength,
                                  onAccept: { model.acceptSuggestion() },
                                  onDismiss: { model.dismissSuggestion() })
                }
            }
            if let bound = state?.boundTrips, !bound.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Eyebrow("Bound chapters")
                    ForEach(bound, id: \.id) { trip in
                        NavigationLink(value: TimelineRoute.trip(id: trip.id)) {
                            TripCoverCard(trip: trip, strength: strength)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }

    // MARK: Places tab

    @ViewBuilder private var placesTab: some View {
        if model.state?.placesLoaded == true && model.state?.places.isEmpty == true {
            TabEmptyNote(text: "Places appear as your timeline fills in.")
        }
        VStack(spacing: 0) {
            ForEach(model.state?.places ?? [], id: \.id) { place in
                NavigationLink(value: TimelineRoute.place(id: place.id)) {
                    PlaceRow(place: place, strength: model.state?.personalisationStrength ?? 0)
                }
                .buttonStyle(.plain)
                Divider().padding(.leading, 16)
            }
        }
    }

    // MARK: Activities tab

    @ViewBuilder private var activitiesTab: some View {
        if model.state?.activitiesLoaded == true && model.state?.activities.isEmpty == true {
            TabEmptyNote(text: "Walks, runs and rides appear here once your timeline has some.")
        }
        VStack(spacing: 0) {
            ForEach(model.state?.activities ?? [], id: \.kind) { activity in
                ActivityRow(activity: activity)
                Divider().padding(.leading, 16)
            }
        }
    }
}

// MARK: - Empty states

/// The Home feed with nothing in it — the iOS twin of Compose `TimelineEmptyState`, minus its Takeout
/// import button (no import on iOS). What to say depends on the one thing the user can act on: the
/// capture switch. ⚠ Recording ON still owes a wait — iOS delivers a visit only after the user has
/// stayed somewhere and left — so that copy must not read as broken.
private struct FeedEmptyState: View {
    let captureAvailable: Bool
    let captureEnabled: Bool
    let onOpenSettings: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
                .font(.largeTitle)
                .foregroundStyle(.tertiary)
            Text("Your timeline starts here")
                .font(.timelineSerif(24, weight: .semibold))
                .multilineTextAlignment(.center)
            if captureEnabled {
                Text("Your day will appear here once Timeline has recorded a visit. This can take a little while after you start moving.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            } else if captureAvailable {
                Text("Turn on \u{201C}Record places and trips\u{201D} in Settings, and the places you visit and the trips between them will appear here.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Button("Open Settings", action: onOpenSettings)
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 4)
            } else {
                // No live runtime: the shell's Control banner owns the reason; claim nothing here.
                Text("Places and trips appear here once Timeline can record them.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 32)
        .padding(.vertical, 48)
    }
}

/// A tab with nothing in it yet — the twin of Compose `TimelineTabEmpty`.
private struct TabEmptyNote: View {
    let text: LocalizedStringKey

    var body: some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 32)
            .padding(.vertical, 48)
    }
}

// MARK: - Rows / cards

private struct DayChapter: View {
    let day: Day
    let strength: Float

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Eyebrow(day.dayLabel)
                Text(day.dateLong).font(.timelineSerif(24, weight: .semibold))
                HStack(spacing: 8) {
                    Text(day.place).font(.subheadline).foregroundStyle(.secondary)
                    if let weather = day.weather {
                        Text("· \(weather)").font(.subheadline).foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.horizontal, 16)

            ForEach(Array(day.moments.enumerated()), id: \.offset) { index, moment in
                NavigationLink(value: TimelineRoute.moment(id: moment.id)) {
                    MomentRow(
                        moment: moment,
                        strength: strength,
                        showConnector: index != day.moments.count - 1
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }
}

private struct MomentRow: View {
    let moment: Moment
    let strength: Float
    let showConnector: Bool
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let personalisation = TimelinePersonalisation.resolve(
            dominantHex: railColor,
            strength: strength,
            scheme: colorScheme
        )
        HStack(alignment: .top, spacing: 14) {
            VStack(spacing: 0) {
                ZStack {
                    Circle().fill(personalisation.soft).frame(width: 34, height: 34)
                    Image(systemName: moment.kind.sfSymbol)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(personalisation.accent)
                }
                if showConnector {
                    Rectangle().fill(Color(.separator)).frame(width: 1.5).frame(maxHeight: .infinity)
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(moment.time).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    if moment.isCurrent {
                        Text("● now").font(.caption2.weight(.bold)).foregroundStyle(.green)
                    }
                }
                Text(moment.title).font(.timelineSerif(19))
                // ⚠ Joined, so a row with no place line (every movement) does not open with a bare
                // "·" — the store screenshots showed " · 22 min" under every walk.
                Text(metaLine).font(.footnote).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, showConnector ? 4 : 0)
        // ⚠ The whole row takes the tap: under `.buttonStyle(.plain)` only drawn content is
        // hit-testable, and on an iPad the row's empty middle — where a tap at its centre lands —
        // opened nothing (the store screenshots' iPad walk, 2026-10-30; Contacts had the same).
        .contentShape(Rectangle())
    }

    private var railColor: String? {
        moment.cover?.dominantColor ?? moment.photos.first?.dominantColor
    }

    /// "Mercato Centrale · 66 min" for a visit; for a movement, its route between the bracketing
    /// places when both are named ("Piazza Santo Spirito → Galleria dell'Accademia · 22 min"), the
    /// twin of Compose's `movementRouteText`; then the duration and the photo count.
    private var metaLine: String {
        var place = moment.place
        if place.isEmpty, let from = moment.originPlace, let to = moment.destinationPlace,
           !from.isEmpty, !to.isEmpty {
            place = "\(from) → \(to)"
        }
        var parts = [place].filter { !$0.isEmpty }
        if let duration = moment.durationMin { parts.append(String(localized: "\(duration.intValue) min")) }
        if moment.photos.count > 0 { parts.append(String(localized: "\(moment.photos.count) photos")) }
        return parts.joined(separator: " · ")
    }
}

private struct InterludeCard: View {
    let suggestion: TripSuggestion
    let strength: Float
    let onAccept: () -> Void
    let onDismiss: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Eyebrow("An interlude")
            Text(suggestion.title).font(.timelineSerif(22, weight: .semibold))
            Text("\(suggestion.dateRange) · \(suggestion.daysSpan) days · \(suggestion.placesCount) places")
                .font(.footnote).foregroundStyle(.secondary)
            HStack(spacing: 12) {
                Button("Read as a trip", action: onAccept).buttonStyle(.borderedProminent)
                Button("Not a trip", action: onDismiss).buttonStyle(.bordered)
            }
            .font(.subheadline)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            TimelinePersonalisation.resolve(
                dominantHex: suggestion.cover.dominantColor,
                strength: strength,
                scheme: colorScheme
            ).soft.opacity(0.5),
            in: RoundedRectangle(cornerRadius: 16)
        )
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(.separator), lineWidth: 0.5))
        .padding(.horizontal, 16)
    }
}

private struct TripCoverCard: View {
    let trip: Trip
    let strength: Float
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(spacing: 14) {
            RoundedRectangle(cornerRadius: 12)
                .fill(TimelinePersonalisation.resolve(
                    dominantHex: trip.cover.dominantColor,
                    strength: strength,
                    scheme: colorScheme
                ).soft)
                .frame(width: 64, height: 64)
            VStack(alignment: .leading, spacing: 4) {
                Text(trip.title).font(.timelineSerif(20, weight: .semibold))
                Text("\(trip.dateRange) · \(trip.placesCount) places · \(trip.photosCount) photos")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
        }
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
    }
}

private struct PlaceRow: View {
    let place: Place
    let strength: Float
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let personalisation = TimelinePersonalisation.resolve(
            dominantHex: place.cover.dominantColor,
            strength: strength,
            scheme: colorScheme
        )
        HStack(spacing: 14) {
            RoundedRectangle(cornerRadius: 10)
                .fill(personalisation.soft)
                .frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 3) {
                Text(place.name.isEmpty ? "Unnamed place" : place.name).font(.timelineSerif(19))
                // Neighborhood → city fallback (G4: city-level is all geocoding stores).
                let area = place.neighborhood.isEmpty ? place.city : place.neighborhood
                // place-roles.md PR-3: a **confirmed** role rides the metadata line — it is a fact
                // *about* the place, not part of what it is called. A suggestion never appears here:
                // the question belongs on the detail, next to the evidence for it.
                let role = place.role.flatMap { $0.isConfirmed ? timelinePlaceRoleName($0.role) : nil }
                Text([role, area.isEmpty ? nil : area, place.visitsCount == 1 ? "1 visit" : "\(place.visitsCount) visits"]
                        .compactMap { $0 }.joined(separator: " · "))
                    .font(.footnote)
                    .foregroundStyle(role == nil ? Color.secondary : personalisation.accent)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        // ⚠ The whole row takes the tap — see `MomentRow`.
        .contentShape(Rectangle())
    }
}

private struct ActivityRow: View {
    let activity: ActivityRollup

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(Color(timelineHex: activity.dominantColor).opacity(0.18)).frame(width: 40, height: 40)
                Text(activity.icon).font(.title3)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(activity.label).font(.timelineSerif(18))
                Text("\(activity.count) \(activity.suffix) · \(activity.secondary)")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            Text(activity.lastDate).font(.caption).foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

// MARK: - Model

@MainActor
final class HomeModel: ObservableObject {
    @Published var state: TimelineHomeUiState?

    private let vm = TimelineSurface.shared.homeViewModel()

    func observe() async {
        for await value in vm.uiState {
            state = value
        }
    }

    func selectTab(_ tab: HomeTab) { vm.selectTab(tab: tab) }
    func acceptSuggestion() { vm.acceptSuggestion() }
    func dismissSuggestion() { vm.dismissSuggestion() }
}
