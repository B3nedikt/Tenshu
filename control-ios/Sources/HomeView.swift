import SwiftUI
import TenshuSdk

// Home — the realm activity feed. Binds `HomeScreenViewModel` (`:control-shared-logic`)
// through `ControlSurface.homeBound()`, observing its paged event flow via the
// `TenshuPagingPresenter` snapshot bridge (the §9.3 "share view models, native UI" pattern,
// exactly as drive-ios's FilesView observes its presenter).
//
// The presenter pages `ControlEventRow`, a flattening of the shared two-level sealed `Event`
// hierarchy done Kotlin-side (see that type). This screen therefore switches a plain Swift
// enum instead of nesting `onEnum(of:)`, and never touches a `KotlinByteArray`.
@MainActor
final class HomeModel: ObservableObject {
    @Published var events: [ControlEventRow] = []
    @Published var settled = false

    private let bound = ControlSurface.shared.homeBound()

    func observe() async {
        for await rows in bound.presenter.snapshot {
            events = rows
        }
    }

    // Whether the initial refresh has landed — distinguishes "loading" from "genuinely no
    // activity yet". Without it an empty realm shows a spinner forever.
    func observeSettled() async {
        for await value in bound.presenter.refreshSettled {
            settled = value.boolValue
        }
    }

    /// ⚠⚠ Paging's load-next trigger — call from every row's `onAppear`.
    ///
    /// `presenter.snapshot` is a passive mirror; Paging appends only when an access is
    /// *recorded* through `get(index:)`. Compose gets this free from
    /// `collectAsLazyPagingItems()`; a hand-driven presenter does not. See the same method on
    /// drive-ios's `FilesModel`, whose absence capped a 786-file folder at 20 rows.
    func onRowAppear(_ index: Int) {
        _ = bound.presenter.get(index: Int32(index))
    }
}

struct HomeView: View {
    @StateObject private var model = HomeModel()
    /// NT-1: the actionable tier. Held here (not inside the pinned section) so the toolbar
    /// badge and the pinned cards read one observation rather than two that could disagree.
    @StateObject private var notifications = NotificationsModel()

    var body: some View {
        NavigationStack {
            List {
                // Unresolved requests sit *above* the ambient feed, so a user lands on them
                // without navigating — the Compose `PinnedRequests` slot. Renders nothing
                // when there is nothing to act on.
                PinnedRequestsSection(model: notifications)
                // Enumerated so each row can record its access — see `HomeModel.onRowAppear`.
                // Keyed on `id`, the same field Compose keys on (`itemKey { it.id }`).
                ForEach(Array(model.events.enumerated()), id: \.element.id) { index, event in
                    EventRow(event: event)
                        .onAppear { model.onRowAppear(index) }
                }
            }
            .listStyle(.plain)
            .overlay {
                // Empty is ambiguous on its own: `settled` is what separates "nothing has
                // happened yet" from "the first page has not landed".
                if model.events.isEmpty {
                    if model.settled {
                        emptyState
                    } else {
                        ProgressView("Loading…")
                    }
                }
            }
            .navigationTitle("Recent Events")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink {
                        NotificationCenterView()
                    } label: {
                        Image(systemName: "bell")
                            // The badge counts *unread* — dismissed-but-unresolved requests
                            // stay in the center and leave the badge (Phase 4 seen-vs-resolved).
                            .overlay(alignment: .topTrailing) {
                                if notifications.unreadCount > 0 {
                                    Text("\(notifications.unreadCount)")
                                        .font(.caption2.bold())
                                        .foregroundStyle(.white)
                                        .padding(4)
                                        .background(Color.red, in: Circle())
                                        .offset(x: 8, y: -8)
                                }
                            }
                    }
                    .accessibilityLabel(
                        notifications.unreadCount > 0
                            ? "Notifications, \(notifications.unreadCount) unread"
                            : "Notifications"
                    )
                }
            }
        }
        .task { await model.observe() }
        .task { await model.observeSettled() }
        .task { await notifications.observe() }
    }

    // Mirrors the Compose `AppEmptyState` on this screen: the same History glyph and the same
    // two lines, so the two platforms say the same thing to a user with a quiet realm.
    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "clock.arrow.circlepath")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
            Text("No recent events")
                .font(.headline)
            Text("Sync activity from your paired devices will show up here.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
    }
}

// One event. Layout mirrors the Compose card: title and timestamp on one line, the sentence
// beneath. No per-row icon and no tap target, both matching Compose — the feed is a record,
// not a navigation surface, and there is no event-detail destination on either platform.
private struct EventRow: View {
    let event: ControlEventRow

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(title)
                    .font(.headline)
                Spacer(minLength: 8)
                // Absolute, localized, and formatted Kotlin-side by the shared `TimestampUtil`
                // the Compose feed uses — not re-derived here, so the two cannot drift.
                Text(event.timestampLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(detail)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }

    // Copy is authored here rather than shared (ADR 0016): the Compose strings live in
    // `:control-shared`, which is deliberately not exported to the iOS framework. These mirror
    // `home_event_title_*` verbatim. `LocalizedStringKey`, not `String`, so `Text` looks each one
    // up in `Localizable.xcstrings` — a `String` is rendered verbatim and never translated.
    //
    // ⚠ Exhaustive over `ControlEventKind` with no `default:` — SKIE bridges the Kotlin enum as
    // a real Swift enum, so a new kind breaks this build instead of rendering a blank row.
    private var title: LocalizedStringKey {
        switch event.kind {
        case .fileAdded: return "File Added"
        case .folderAdded: return "Folder Added"
        case .multipleFilesAdded: return "Multiple Files Added"
        case .fileDeleted: return "File Deleted"
        case .folderDeleted: return "Folder Deleted"
        case .changeLost: return "Change Lost"
        case .appRegistered: return "App Registered"
        case .appRemoved: return "App Removed"
        }
    }

    // Mirrors `home_event_added_with_name` / `_multiple_added_*` / `_deleted_file` /
    // `_app_registered` / `_app_removed`.
    //
    // ⚠ `event.name` is the full path for file events, matching what the Compose feed renders
    // — the shared `NotificationMapper` fills it from `notification.path.get()`. Shortening it
    // belongs in that mapper, for both platforms at once.
    private var detail: LocalizedStringKey {
        switch event.kind {
        case .fileAdded:
            return "Added file: \(event.name)"
        case .folderAdded:
            return "Added folder: \(event.name)"
        case .multipleFilesAdded:
            // Singular vs plural, as the two separate Compose strings do.
            return event.otherFilesCount == 1
                ? "\(event.name) and 1 other file added"
                : "\(event.name) and \(Int(event.otherFilesCount)) other files added"
        case .fileDeleted:
            return "Deleted file: \(event.name)"
        case .folderDeleted:
            return "Deleted folder: \(event.name)"
        case .changeLost:
            // Mirrors `home_event_change_lost`.
            return "\(event.name) was deleted on another device while your change was waiting"
        case .appRegistered:
            return "Registered new app: \(event.name)"
        case .appRemoved:
            return "Removed app: \(event.name)"
        }
    }
}
