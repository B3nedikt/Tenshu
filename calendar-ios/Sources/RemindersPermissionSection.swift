import SwiftUI
import TenshuSdk
import UIKit

/// The reminders permission row on the Calendars screen (CA-8c).
///
/// ⚠⚠ **Asking is the shell's job, and this is the moment.** `NotificationPresenter`'s KDoc says
/// so and iOS sharpens it: the system prompt is once per install and unrepeatable, so firing it at
/// proxy init would spend it on a launch the user had no reason to connect with reminders. Here it
/// sits under a heading that says what it is for.
///
/// ⚠⚠ **A refused request routes to Settings, because the second tap does nothing.** Once
/// authorization has been denied, `requestAuthorization` returns `false` immediately without
/// showing anything — so a button that only re-requests is a button that silently fails forever.
/// The `false` result is treated the same whether it came from a denial now or a denial last year,
/// which is what lets this work without asking the OS to distinguish them.
struct RemindersPermissionSection: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var canPost = CalendarLocalRuntime.shared.canPostNotifications()
    @State private var refused = false

    var body: some View {
        // ⚠⚠ The `Group` is load-bearing: the refresh hooks hang off IT, not off the row. Put them
        // on the row and they disappear exactly when reminders are working — so a revocation in
        // Settings could never be noticed and the row could never come back.
        Group {
            // ⚠ Nothing at all once reminders work. A permanent "notifications: on" row is a line
            // every user reads once and then reads past forever.
            if !canPost {
                Section {
                    if refused {
                        Button {
                            openSettings()
                        } label: {
                            Label("Open Settings to allow notifications", systemImage: "gear")
                        }
                    } else {
                        Button {
                            CalendarLocalRuntime.shared.requestNotificationAuthorization { granted in
                                // ⚠ Back to the main actor: the completion handler arrives on
                                // whichever thread the OS used, and SwiftUI state is main-only.
                                Task { @MainActor in
                                    canPost = granted.boolValue
                                    refused = !canPost
                                }
                            }
                        } label: {
                            Label("Turn on reminders", systemImage: "bell")
                        }
                    }
                } header: {
                    Text("Reminders")
                } footer: {
                    Text(
                        refused
                            ? "Notifications are turned off for Calendar, so reminders cannot be shown."
                            : "Reminders for your events are delivered by iOS, even when Calendar is closed."
                    )
                }
            }
        }
        // ⚠⚠ Re-read on every return to the foreground. Coming back from Settings — having just
        // granted or revoked — is the single most likely way this row is wrong.
        .onChange(of: scenePhase) { phase in
            guard phase == .active else { return }
            refresh()
        }
        .onAppear { refresh() }
    }

    /// ⚠ Reads the answer from the callback, not from the cache afterwards. The OS settings query
    /// is asynchronous, so a refresh-then-read would answer with the previous value — and showing
    /// "turn on reminders" to someone who just turned them on is the exact failure this row is for.
    private func refresh() {
        CalendarLocalRuntime.shared.refreshNotificationAuthorization { allowed in
            Task { @MainActor in
                let granted = allowed.boolValue
                canPost = granted
                if granted { refused = false }
            }
        }
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}
