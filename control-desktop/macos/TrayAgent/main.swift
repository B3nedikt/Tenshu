// Tenshu Control tray — macOS menu-bar agent.
//
// docs/proposals/desktop-macos-integration.md §4. A persistent
// NSStatusItem with an NSPopover that renders sync state, recent notifications,
// Open Control, and an app launcher — all over the JSON native bridge
// (BridgeClient.swift). The daemon stays alive when Control's window closes;
// this agent re-opens it via the `openControlWindow` bridge method.
//
// Build:  swiftc -O -o tray-agent main.swift BridgeClient.swift
// Run:    ./tray-agent      (daemon must be running: ./gradlew :control-desktop:run)
//
// Runs as an .accessory app (no Dock icon) — the runtime equivalent of
// LSUIElement; the packaged .app sets LSUIElement in Info.plist.

import AppKit
import SwiftUI

// MARK: - Display model

final class TrayModel: ObservableObject {
    /// Dismiss the tray panel. Set by `AppDelegate`.
    ///
    /// ⚠⚠ **Invoking an action from the panel has to close the panel explicitly.** The panel is
    /// dismissed by only two things — toggling the status item, and a *global* mouse-down monitor
    /// that, by design, does not fire for our own events ("clicking inside the panel doesn't
    /// dismiss it"). Without this every button in here leaves the panel on screen after doing its
    /// job, which no macOS menu does.
    ///
    /// ⚠ Do not rely on `NSPanel.hidesOnDeactivate` for it: the panel then vanishes only when
    /// *Control activating* deactivates this agent — a side effect of activation succeeding, not
    /// something the panel does for itself, so the dismissal is intermittent.
    var onDismissRequested: (() -> Void)?

    @Published var daemonReachable = true
    @Published var syncStateLabel = "…"
    /// True when the sync state warrants attention (divergence / offline) so the
    /// popover tints the label — the ambient surface for `divergenceWarning`
    /// (bgproc §5.3).
    @Published var syncStateWarning = false
    /// One line of *why*, for the states where the label alone cannot say anything true —
    /// only divergence, which is about one named peer at one moment, not about whether
    /// sync works. Present whenever a warning stands, including after it has aged out of
    /// [syncStateLabel]: demoting it must not delete it (`sync-hlc-tip-device-identity.md` TC-1).
    @Published var syncStateDetail: String? = nil
    /// Whether the user paused sync — drives the Pause/Resume button label (§5.3).
    @Published var syncPaused = false
    /// Whether a realm is set up (sync enabled) — gates the Pause/Resume button;
    /// pausing is meaningless with no realm.
    @Published var syncEnabled = false
    @Published var notifications: [NotificationRow] = []
    @Published var apps: [AppRow] = []
    /// appId of the most recent launch that failed, so the menu can say so rather than
    /// appearing inert. Cleared on the next successful refresh.
    @Published var launchFailure: String? = nil
    /// Inbound actionable requests — pinned above ambient notifications
    /// in the popover; also drives the OS notification via [onPendingRequests].
    @Published var pendingRequests: [PendingRequestRow] = []

    struct NotificationRow: Identifiable {
        let id: String
        let text: String
    }
    struct AppRow: Identifiable {
        let id: String      // appId
        let name: String
        let verified: Bool
        /// Registered on *this* Mac — it has connected to Control here at least once
        /// (`control-app-catalogue.md` D5). ⚠ Not the same as being on disk: a registration
        /// outlives its bundle, and an app that was installed but never opened has none.
        /// [isLaunchable] is what decides the menu.
        let installedHere: Bool
        /// Has a registration anywhere in the realm. False for the daemon's never-connected
        /// candidates, whose `verified` says nothing because nothing was ever attested.
        let registered: Bool
    }

    /// Fires after each successful `pendingRequests` poll so the AppDelegate can
    /// drive the [PendingRequestNotifier] diff. Not called when the daemon is
    /// unreachable (we don't know the feed, so we leave delivered alerts intact).
    var onPendingRequests: (([PendingRequestRow]) -> Void)?

    /// Fires after each successful `pairingApproval` poll with the opaque joiner
    /// id (or nil when no device is asking) so the AppDelegate can drive the
    /// [PairingApprovalNotifier] diff (control-pairing-ux.md §3.6). Not called on
    /// a failed/unreachable poll, so a live alert isn't spuriously cancelled.
    var onPairingApproval: ((String?) -> Void)?

    /// Fires with the schema pull fence verdict after each poll that could read it, so
    /// the AppDelegate can drive the [SchemaFenceNotifier] diff. Not called when the daemon is
    /// unreachable or has not evaluated the fence yet — neither is "the fence cleared".
    var onSchemaFence: ((Bool) -> Void)?

    private let bridge = BridgeClient()
    private var pollTimer: Timer?
    /// Calendar reminders: set by the app delegate, driven by the same poll.
    var onCalendarReminders: (([ReminderRow]) -> Void)?

    /// How often to poll the daemon for actionable requests in the background
    /// (independent of the popover, which only refreshes on open). The daemon is
    /// the resident process, so this is a cheap local UDS round-trip.
    private static let pollInterval: TimeInterval = 10

    /// CFBundleIdentifier of the packaged Control.app — shared with its iOS
    /// App Store record and `nativeDistributions.macOS.bundleID`. Used to locate
    /// the bundle when the daemon isn't running and we need a cold launch.
    static let controlBundleId = "app.tenshu.control.ios"

    /// Resolve the host app from this embedded helper's own path. This avoids
    /// choosing a different copy registered under the same Universal Purchase
    /// bundle ID. The LaunchServices fallback is only for unbundled dev runs.
    static func containingHostAppURL() -> URL? {
        let executable = Bundle.main.executableURL
            ?? URL(fileURLWithPath: CommandLine.arguments[0])
        var candidate = executable
        for _ in 0 ..< 8 {
            candidate = candidate.deletingLastPathComponent()
            if candidate.pathExtension == "app" {
                return candidate
            }
        }
        return nil
    }

    /// Refresh everything off the main thread, publish back on main.
    func refresh() {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            let reachable = self.bridge.isDaemonReachable()
            var stateLabel = "Control not running"
            var stateWarning = false
            var stateDetail: String? = nil
            var statePaused = false
            var stateEnabled = false
            var notifs: [NotificationRow] = []
            var apps: [AppRow] = []
            var requests: [PendingRequestRow]?
            var pairing: (ok: Bool, id: String?) = (false, nil)
            var fenced: Bool? = nil

            if reachable {
                if let s = try? self.bridge.call("getSyncState") {
                    let display = Self.syncDisplay(s)
                    stateLabel = display.label
                    stateWarning = display.warning
                    stateDetail = Self.divergenceDetail(s)
                    statePaused = (s["paused"] as? Bool) ?? false
                    fenced = s["schemaFenced"] as? Bool
                    stateEnabled = (s["enabled"] as? Bool) ?? false
                }
                if let r = try? self.bridge.call("recentNotifications", params: ["limit": "4"]),
                   let rows = r["notifications"] as? [[String: Any]] {
                    notifs = rows.compactMap(Self.notificationRow)
                }
                if let r = try? self.bridge.call("listApps"),
                   let rows = r["apps"] as? [[String: Any]] {
                    apps = rows.compactMap(Self.appRow).filter(Self.isLaunchable)
                }
                requests = self.fetchPendingRequests()
                pairing = self.fetchPairingApproval()
            }

            DispatchQueue.main.async {
                self.daemonReachable = reachable
                self.syncStateLabel = stateLabel
                self.syncStateWarning = stateWarning
                self.syncStateDetail = stateDetail
                self.syncPaused = statePaused
                self.syncEnabled = stateEnabled
                self.notifications = notifs
                self.apps = apps
                if self.launchFailure != nil && !apps.contains(where: { $0.id == self.launchFailure }) {
                    self.launchFailure = nil
                }
                if let requests { self.publishPendingRequests(requests) }
                if pairing.ok { self.onPairingApproval?(pairing.id) }
                if let fenced { self.onSchemaFence?(fenced) }
            }
        }
    }

    /// Start the background poll for actionable requests, so the OS notification
    /// fires while the popover is closed. Idempotent.
    func startPolling() {
        guard pollTimer == nil else { return }
        pollPendingRequests()
        let timer = Timer.scheduledTimer(withTimeInterval: Self.pollInterval, repeats: true) { [weak self] _ in
            self?.pollPendingRequests()
        }
        // Keep firing while a tracking run loop (e.g. an open menu) is active.
        RunLoop.main.add(timer, forMode: .common)
        pollTimer = timer
    }

    /// One background poll: fetch the feed off-main, publish + drive the notifier
    /// on main. Skips entirely when the daemon is unreachable — leaving the known
    /// set + any delivered alerts intact rather than spuriously cancelling them.
    private func pollPendingRequests() {
        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let self else { return }
            let requests = self.fetchPendingRequests()
            // Pairing approval rides the same poll (control-pairing-ux.md §3.6) so a
            // join request alerts while the popover/window is closed.
            let pairing = self.fetchPairingApproval()
            // Calendar reminders ride the same poll. ⚠ The interval is a
            // limit on how fast a *change* reaches the OS, not on when a reminder
            // fires — once macOS holds a trigger it is unaffected by this timer,
            // and by the daemon being quit.
            let reminders = self.fetchCalendarReminders()
            // The fence alert rides the same poll, so it fires with the popover closed —
            // the only way anyone hears of it while Control's window is hidden.
            let fenced = self.fetchSchemaFence()
            DispatchQueue.main.async {
                if let fenced { self.onSchemaFence?(fenced) }
                if let requests { self.publishPendingRequests(requests) }
                if pairing.ok { self.onPairingApproval?(pairing.id) }
                // ⚠ `nil` means the daemon was unreachable — distinct from a
                // successfully empty calendar. Reconciling `[]` on an unreachable
                // daemon would cancel every armed reminder every time Control was
                // restarted, which is the one thing this design exists to avoid.
                if let reminders { self.onCalendarReminders?(reminders) }
            }
        }
    }

    /// `pendingRequests` bridge call → parsed rows, or `nil` when the daemon is
    /// unreachable / the call fails (distinct from a successful empty feed).
    private func fetchPendingRequests() -> [PendingRequestRow]? {
        guard let r = try? bridge.call("pendingRequests"),
              let rows = r["requests"] as? [[String: Any]] else { return nil }
        return rows.compactMap(Self.pendingRequestRow)
    }

    /// `getSyncState.schemaFenced`, or `nil` when the daemon is unreachable or has not
    /// evaluated the fence yet (the bridge sends JSON null for that, which is not a Bool).
    private func fetchSchemaFence() -> Bool? {
        guard let s = try? bridge.call("getSyncState") else { return nil }
        return s["schemaFenced"] as? Bool
    }

    /// `calendarReminders` bridge call → the rows macOS should be holding, or
    /// `nil` when the daemon is unreachable / the call fails.
    private func fetchCalendarReminders() -> [ReminderRow]? {
        guard let r = try? bridge.call("calendarReminders"),
              let rows = r["reminders"] as? [[String: Any]] else { return nil }
        return rows.compactMap(Self.reminderRow)
    }

    private static func reminderRow(_ raw: [String: Any]) -> ReminderRow? {
        guard let id = raw["id"] as? String,
              let title = raw["title"] as? String,
              let millis = raw["deliverAtEpochMs"] as? NSNumber else { return nil }
        return ReminderRow(
            id: id,
            title: title,
            body: (raw["body"] as? String) ?? "",
            deliverAt: Date(timeIntervalSince1970: millis.doubleValue / 1000),
            tapPayload: (raw["tapPayload"] as? String) ?? ""
        )
    }

    /// `pairingApproval` bridge call → `(ok, id)`. `ok` is false when the daemon
    /// is unreachable / the call failed (so the caller leaves any live alert
    /// intact); when `ok`, `id` is the opaque joiner id of a pending request, or
    /// nil when no device is asking. The payload carries no SAS / device name.
    private func fetchPairingApproval() -> (ok: Bool, id: String?) {
        guard let r = try? bridge.call("pairingApproval") else { return (false, nil) }
        let pending = (r["pending"] as? Bool) ?? false
        let id = r["id"] as? String
        return (true, pending ? id : nil)
    }

    /// Publish to the popover + hand the diff to the notifier. Main-thread.
    private func publishPendingRequests(_ requests: [PendingRequestRow]) {
        pendingRequests = requests
        onPendingRequests?(requests)
    }

    /// Show Control's window. If the daemon is already running this is a single
    /// `openControlWindow` bridge call. If it isn't, we cold-launch the packaged
    /// Control.app via LaunchServices, then raise the window once its bridge is
    /// listening. App bundles are single-instance, so the launch is idempotent —
    /// it just activates an instance that races back up.
    func openControl() {
        onDismissRequested?()
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            if self.bridge.isDaemonReachable() {
                _ = try? self.bridge.call("openControlWindow")
                self.activateControl()
            } else {
                self.launchControlDaemon()
            }
        }
    }

    /// Bring the running Control app to the front. Control is a menu-bar-only
    /// (`LSUIElement`) app, so the JVM-side `window.toFront()` alone may surface
    /// the window *behind* the frontmost app — activating the process raises it.
    /// No-op when Control isn't running (the cold-launch path activates instead,
    /// via `NSWorkspace.open` `activates = true`).
    private func activateControl() {
        if let host = Self.containingHostAppURL(),
           let running = NSWorkspace.shared.runningApplications.first(where: {
               $0.bundleURL?.standardizedFileURL == host.standardizedFileURL
           }) {
            running.activate()
            return
        }
        // Development tray runs have no containing .app; keep the LaunchServices
        // fallback for that unbundled path only.
        NSRunningApplication
            .runningApplications(withBundleIdentifier: Self.controlBundleId)
            .first?
            .activate()
    }

    /// Cold start: ask the OS to launch Control.app, then raise its window once
    /// the daemon's bridge comes up. Deliberately does NOT pass
    /// `TENSHU_CONTROL_START_HIDDEN`, so a fresh boot comes up window-visible; the
    /// follow-up `openControlWindow` is belt-and-suspenders against a
    /// hidden-by-default boot. No-ops with a log line in dev, where there's no
    /// installed bundle for LaunchServices to find (see the tray README).
    private func launchControlDaemon() {
        let url = Self.containingHostAppURL()
            ?? NSWorkspace.shared.urlForApplication(withBundleIdentifier: Self.controlBundleId)
        guard let url else {
            NSLog("Tenshu tray: Control.app not found; cannot launch")
            return
        }
        let cfg = NSWorkspace.OpenConfiguration()
        cfg.activates = true
        NSWorkspace.shared.openApplication(at: url, configuration: cfg) { [weak self] _, error in
            if let error {
                NSLog("Tenshu tray: failed to launch Control.app: \(error.localizedDescription)")
                return
            }
            self?.raiseWindowWhenReady()
        }
    }

    /// Poll the bridge until the freshly-launched daemon is listening, then ask
    /// it to show its window and refresh the popover. Bounded (~10s) so a failed
    /// boot can't spin forever.
    private func raiseWindowWhenReady(attempts: Int = 40) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            for _ in 0 ..< attempts {
                if self.bridge.isDaemonReachable() {
                    _ = try? self.bridge.call("openControlWindow")
                    self.activateControl()
                    self.refresh()
                    return
                }
                Thread.sleep(forTimeInterval: 0.25)
            }
            NSLog("Tenshu tray: Control daemon did not become reachable after launch")
        }
    }

    func quit() {
        fireAndForget("quitApp")
        NSApp.terminate(nil)
    }

    /// Launch an app's bundle, and **surface a failure instead of swallowing it**.
    ///
    /// ⚠ Not fire-and-forget: the daemon answers `not_found` for a missing bundle
    /// (`ControlDataBridge.launchApp`), and a discarded reply turns a click on an unlaunchable
    /// row into nothing at all. Filtering with [isLaunchable] removes the
    /// common cause; a bundle removed since the last poll can still reach here, and a
    /// launcher that silently does nothing is the worst way to fail.
    func launch(appId: String) {
        onDismissRequested?()
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            let ok = (try? self.bridge.call("launchApp", params: ["appId": appId])) != nil
            if !ok {
                DispatchQueue.main.async { self.launchFailure = appId }
            }
            // A failure usually means the row is stale, so re-poll: the next refresh drops it.
            self.refresh()
        }
    }

    /// Toggle the daemon's device-local pause switch (§5.3), then refresh so the
    /// button + state label reflect it immediately (rather than at the next poll).
    func setPaused(_ paused: Bool) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            _ = try? self.bridge.call("setSyncPaused", params: ["paused": paused ? "true" : "false"])
            self.refresh()
        }
    }

    private func fireAndForget(_ method: String, _ params: [String: Any] = [:]) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            _ = try? self?.bridge.call(method, params: params)
        }
    }

    // MARK: bridge → display mapping (this surface owns its own strings, ADR 0016)

    /// Label + attention flag for the tray's sync-state row. The bgproc §5.3
    /// state *code* is authoritative on the Kotlin side (`ControlBridge`); this
    /// surface owns only its localised strings (ADR 0016). `warning` tints the
    /// label for the states the user should act on (divergence / offline).
    private static func syncDisplay(_ s: [String: Any]) -> (label: String, warning: Bool) {
        switch s["state"] as? String {
        case "SYNCED": return ("Synced", false)
        case "SYNCING": return ("Syncing…", false)
        case "OFFLINE": return ("Offline", true)
        // ⚠ NOT "Sync issue detected". That read as "your sync is broken", which is precisely
        // what this state does not mean: every field below it can be green, on a device that is
        // online, idle and fully caught up. What it means is that one
        // *peer's* signed clock did not line up — a consistency warning about a named device.
        case "DIVERGENCE": return ("Consistency warning", true)
        // This Mac stopped pulling until Control is updated. Names the app, never the
        // service (core-schema-skew-surface.md §4); pushes still go out, which is why this
        // outranks Offline/Syncing/Synced on the Kotlin side.
        case "NEEDS_UPDATE": return ("Update Control to keep syncing", true)
        case "PAUSED": return ("Paused", false)
        case "LOCKED": return ("Locked", false)
        case "DISABLED": return ("Sync off", false)
        default: return ("Unknown", false)
        }
    }

    /// The divergence detail line, or nil when no warning stands.
    ///
    /// Keyed off `divergenceWarning` (the raw signal), **not** `divergenceFresh`: an aged-out
    /// warning is demoted from the headline but still shown here, so the user can still find out
    /// what happened and when. The daemon supplies the peer id, its name where it can resolve one,
    /// and the first-seen timestamp; the strings are ours (ADR 0016).
    private static func divergenceDetail(_ s: [String: Any]) -> String? {
        guard (s["divergenceWarning"] as? Bool) == true else { return nil }
        // Fall back through name → short id → a generic noun. Naming the wrong device would be
        // worse than naming none, so the fallbacks never guess: they get vaguer, not braver.
        let name = (s["divergencePeerName"] as? String)
            ?? Self.shortDeviceId(s["divergencePeerDeviceId"] as? String)
            ?? "Another device"
        let when = Self.timeOfDay(s["divergenceObservedAtMillis"]).map { " at \($0)" } ?? ""
        switch s["divergenceKind"] as? String {
        case "SIGNATURE_MISMATCH":
            return "Couldn't verify \(name)'s sync signature\(when)."
        default:
            return "\(name) reported an out-of-order sync clock\(when)."
        }
    }

    /// `e4a23211-…` — enough to tell two devices apart in a support conversation without
    /// putting a full UUID in a menu.
    private static func shortDeviceId(_ id: String?) -> String? {
        guard let id, !id.isEmpty else { return nil }
        return id.count > 8 ? "\(id.prefix(8))…" : id
    }

    /// Epoch millis → a local wall-clock time. Time only, no date: the warning that matters is
    /// a recent one, and a bare time is what makes "and it is still saying this" legible.
    private static func timeOfDay(_ raw: Any?) -> String? {
        let millis: Double
        if let d = raw as? Double { millis = d }
        else if let i = raw as? Int { millis = Double(i) }
        else if let n = raw as? NSNumber { millis = n.doubleValue }
        else { return nil }
        guard millis > 0 else { return nil }
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        formatter.dateStyle = .none
        return formatter.string(from: Date(timeIntervalSince1970: millis / 1000))
    }

    private static func notificationRow(_ row: [String: Any]) -> NotificationRow? {
        guard let id = row["id"] as? String, let kind = row["kind"] as? String else { return nil }
        let name = row["name"] as? String ?? ""
        let other = (row["otherCount"] as? Int) ?? (row["otherCount"] as? NSNumber)?.intValue ?? 0
        let text: String
        switch kind {
        case "file_added": text = "Added \(name)"
        case "folder_added": text = "Added folder \(name)"
        case "files_added": text = "Added \(name) + \(other) more"
        case "file_deleted": text = "Deleted \(name)"
        case "folder_deleted": text = "Deleted folder \(name)"
        case "change_lost": text = "\(name) was deleted on another device while your change was waiting"
        case "app_registered": text = "\(name) connected"
        case "app_removed": text = "\(name) removed"
        default: text = name
        }
        return NotificationRow(id: id, text: text)
    }

    private static func appRow(_ row: [String: Any]) -> AppRow? {
        guard let id = row["appId"] as? String, let name = row["name"] as? String else { return nil }
        // Absent ⇒ treat as local. An older daemon does not send the field, and hiding every
        // app from the tray would be a far worse failure than showing one that cannot launch.
        return AppRow(
            id: id,
            name: name,
            verified: (row["verified"] as? Bool) ?? false,
            installedHere: (row["installedOnThisDevice"] as? Bool) ?? true,
            registered: (row["registered"] as? Bool) ?? true
        )
    }

    /// Whether the tray should offer this app: LaunchServices is asked, on every refresh, where
    /// the bundle lives — so a new install appears the next time the menu opens, with no
    /// notification to miss, and an uninstalled app drops out even though its registration
    /// stays (`control-app-catalogue.md` D5).
    ///
    /// Two ways in, deliberately unequal:
    ///  - a copy in an Applications folder counts on its own. That is what makes an app
    ///    installed-but-never-opened launchable;
    ///  - a copy anywhere else counts only if the app has registered here. ⚠ LaunchServices
    ///    also knows every stray bundle it has ever seen (a `build/` output, a mounted disk
    ///    image), and those must not read as an install — but a copy the user has really run
    ///    against this Control is one they evidently mean.
    ///
    /// ⚠ This decides what the menu *offers*, nothing more. Presence on disk is never reported
    /// back as a registration: a bundle id is self-asserted.
    private static func isLaunchable(_ app: AppRow) -> Bool {
        // Internal app ids are platform-neutral. Resolve the Apple bundle id before asking
        // LaunchServices; Drive shares its iOS/macOS id for Universal Purchase.
        let paths = NSWorkspace.shared.urlsForApplications(
            withBundleIdentifier: macOSBundleId(for: app.id)
        )
            .map { $0.standardizedFileURL.path }
            // LaunchServices keeps resolving a bundle that has been dragged to the Trash.
            .filter { !$0.contains("/.Trash/") }
        if paths.contains(where: isInApplicationsFolder) { return true }
        return app.installedHere && !paths.isEmpty
    }

    /// Every first-party macOS bundle id is the app's iOS bundle id (D12, C4 in
    /// desktop-macos-distribution.md); Kotlin's `macOsBundleIdFor` reads the same from the catalogue.
    private static let firstPartyAppIds: Set<String> = [
        "app.tenshu.control", "app.tenshu.drive", "app.tenshu.maps", "app.tenshu.home",
        "app.tenshu.calendar", "app.tenshu.timeline", "app.tenshu.mia", "app.tenshu.photos",
        "app.tenshu.fit", "app.tenshu.contacts",
    ]

    private static func macOSBundleId(for appId: String) -> String {
        firstPartyAppIds.contains(appId) ? appId + ".ios" : appId
    }

    /// `/Applications` and `~/Applications`, subfolders included.
    private static func isInApplicationsFolder(_ path: String) -> Bool {
        FileManager.default
            .urls(for: .applicationDirectory, in: [.localDomainMask, .userDomainMask])
            .contains { path.hasPrefix($0.standardizedFileURL.path + "/") }
    }

    /// `{ id, kind, timestampMillis }` → a [PendingRequestRow]. The bridge ships
    /// the opaque id + generic kind only (no PII); the row derives its display
    /// title from `kind` (PendingRequestNotifier owns those strings, ADR 0016).
    private static func pendingRequestRow(_ row: [String: Any]) -> PendingRequestRow? {
        guard let id = row["id"] as? String, let kind = row["kind"] as? String else { return nil }
        return PendingRequestRow(id: id, kind: kind)
    }
}

// MARK: - Popover content

struct PopoverView: View {
    @ObservedObject var model: TrayModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Tenshu Control").font(.headline)
                Spacer()
                Text(model.syncStateLabel)
                    .font(.caption)
                    .foregroundColor(
                        !model.daemonReachable ? .red
                            : (model.syncStateWarning ? .orange : .secondary)
                    )
            }

            if let detail = model.syncStateDetail, model.daemonReachable {
                Text(detail)
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if !model.daemonReachable {
                Text("The Control daemon isn't running.")
                    .font(.caption).foregroundColor(.secondary)
            } else {
                if !model.pendingRequests.isEmpty {
                    Divider()
                    Text("Needs action").font(.caption).foregroundColor(.secondary)
                    ForEach(model.pendingRequests) { request in
                        Button { model.openControl() } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "exclamationmark.circle.fill")
                                    .foregroundColor(.orange).font(.caption)
                                Text(request.title).font(.callout).lineLimit(1)
                            }
                        }.buttonStyle(.plain)
                    }
                }

                if !model.notifications.isEmpty {
                    Divider()
                    Text("Recent").font(.caption).foregroundColor(.secondary)
                    ForEach(model.notifications) { n in
                        Text(n.text).font(.callout).lineLimit(1)
                    }
                }

                if !model.apps.isEmpty {
                    Divider()
                    Text("Apps").font(.caption).foregroundColor(.secondary)
                    ForEach(model.apps) { app in
                        Button { model.launch(appId: app.id) } label: {
                            HStack {
                                Text(app.name)
                                if app.registered && !app.verified {
                                    Image(systemName: "exclamationmark.triangle.fill")
                                        .foregroundColor(.orange).font(.caption2)
                                }
                                if model.launchFailure == app.id {
                                    Text("couldn't open").font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                            }
                        }.buttonStyle(.plain)
                    }
                }
            }

            Divider()
            if model.daemonReachable && model.syncEnabled {
                Button(model.syncPaused ? "Resume sync" : "Pause sync") {
                    model.setPaused(!model.syncPaused)
                }.buttonStyle(.plain)
            }
            HStack {
                Button("Open Control") { model.openControl() }
                Spacer()
                Button("Quit") { model.quit() }
            }
        }
        .padding(14)
        .frame(width: 280)
        .onAppear { model.refresh() }
    }
}

// MARK: - Menu-bar icon (the Tenshu Control cat, as a template image)

/// The brand cat as a black-on-transparent vector PDF (a macOS *template* image,
/// so the menu bar tints it for light/dark). Derived from the themed control
/// silhouette — regeneration recipe in README.md "Menu-bar icon". Embedded as
/// base64 so the standalone `swiftc` binary has no external-file dependency.
private let catTemplatePdfBase64 = """
JVBERi0xLjcKJbXtrvsKNCAwIG9iago8PCAvTGVuZ3RoIDUgMCBSCiAgIC9GaWx0ZXIgL0ZsYXRlRGVjb2RlCj4+CnN0cmVhbQp4nIVUQY4UMQy85xX+ACaOEyd5wUpIHBaOiANqNIsQc1g48H0qaSernQVWo+5WeeJKVWxHKOL3RvCS1Om4hscQZ+znA739EunhV9DK1guJZI610ZU8UI1za2SJDanZOIpRV9amCx1gzSxZPBBIcgfLynW0qA/69nK3S2jgLIU0s1pFpMSRpBzN6AdlYSmJCvKkOtZKhtWxUxFOHYs755gCTaq0qMZ+t+SXIDFxj0aqnOoIDS0tZ0qJY4foZCyW8Z3/iwIOL9zroJRcWGKhUIxLGopmJAkZCAXWsFc3IW1T4c12Q9MLBRcvyoc7gk6VVOh3EHqH53v49JlwIPQ1ZHpPjyRz5flGPaECmmtjHNaVMoiVKkhQHfhoFbI6lCs1blBtZVbzoI90H/5B1mCnTrbIPaWFk3IzXahw7Rv8l64LKi5bXEenqCxxEoUzDutUJ7H6Kf+VDy3MydJ2K1mnUfcrpbPWsgxLrZzza5YX5zY9qqkpr4Cg/7XVDVvkXOorxhfptr6UuvmldLlfUp/7RxuNPnjBDvuYD3D3MpqnoW0bqBvnUWZ0a8TOnUvp1CMLpqaX2ZkH9cSjuTDFMWugjuFVdTjEjVHeiyUiu9jikojVNa2NJLY5F6eO4Jr35QJVGS1edU7vdWPjquhXzGu38bEMVOY4ODqo4siTwzDKCVWe6OBkPRziXUZbzDQzNoh2TkeH7xhWwOV45nOx89q4kY9rQ3CnDekrtAOnMhGd94G7kIguPJ2epiTauJvC0wJFzbexDZeGFXCJnr70O314Mrj2Xwtc3Up/rn7eQreGLuE+/AHuzDVOCmVuZHN0cmVhbQplbmRvYmoKNSAwIG9iagogICA2MTMKZW5kb2JqCjMgMCBvYmoKPDwKICAgL0V4dEdTdGF0ZSA8PAogICAgICAvYTAgPDwgL0NBIDEgL2NhIDEgPj4KICAgPj4KPj4KZW5kb2JqCjcgMCBvYmoKPDwgL1R5cGUgL09ialN0bQogICAvTGVuZ3RoIDggMCBSCiAgIC9OIDEKICAgL0ZpcnN0IDQKICAgL0ZpbHRlciAvRmxhdGVEZWNvZGUKPj4Kc3RyZWFtCnicM1Mw4IrmiuUCAAY4AV0KZW5kc3RyZWFtCmVuZG9iago4IDAgb2JqCiAgIDE2CmVuZG9iago5IDAgb2JqCjw8IC9UeXBlIC9PYmpTdG0KICAgL0xlbmd0aCAxMiAwIFIKICAgL04gNAogICAvRmlyc3QgMjMKICAgL0ZpbHRlciAvRmxhdGVEZWNvZGUKPj4Kc3RyZWFtCnicVZHBasMwEETv+oq5lDoUbK1jp0kwOSSBUEohOL2VHoQsHEGxjCSX5u8rOXFK0WX3MauZZQmcEUfJWQ4qiRFh/lywqkL2fukVsqNolWMAslfdOHwgB0eNzxHtzNB5ENtsxomjNc0glUUihbYGlNIyLZCcve/dOstG2lrRn7V0qbHtbHb9xirhten2wisk+3XO8wVf8IIoVOUTzx85n00mf7HwEKzj/FFYFXPEZCN4U40WW/MT4vLwaLkC5at76M4HuUNx1x+sGXpUVWxif/UY6YROgVrRuT56ycuEX+DtoKZuF1R79a2lqg/bCEPmyGvlzGClcpjfPU9hUPprdBeu8G+9nfDiy7S37cIFbssF0S8UA27LCmVuZHN0cmVhbQplbmRvYmoKMTIgMCBvYmoKICAgMjc4CmVuZG9iagoxMyAwIG9iago8PCAvVHlwZSAvWFJlZgogICAvTGVuZ3RoIDU5CiAgIC9GaWx0ZXIgL0ZsYXRlRGVjb2RlCiAgIC9TaXplIDE0CiAgIC9XIFsxIDIgMl0KICAgL1Jvb3QgMTEgMCBSCiAgIC9JbmZvIDEwIDAgUgo+PgpzdHJlYW0KeJxjYGD4/5+JgZOBAUQwMTJdZ2BgZOAHEkwHQWLsQBazPIhYCCK2QdQxgghmRlYHoBhrOAMDAAkkBg0KZW5kc3RyZWFtCmVuZG9iagpzdGFydHhyZWYKMTM2NwolJUVPRgo=
"""

private func trayIcon() -> NSImage {
    if let data = Data(base64Encoded: catTemplatePdfBase64.replacingOccurrences(of: "\n", with: "")),
       let image = NSImage(data: data) {
        // Fill the menu-bar height; width follows the cat's own aspect ratio
        // (~1.47, ears+whiskers are wider than tall) so it isn't distorted.
        let height: CGFloat = 19
        let aspect = image.size.height > 0 ? image.size.width / image.size.height : 1
        image.size = NSSize(width: height * aspect, height: height)
        image.isTemplate = true // menu bar tints the black silhouette for light/dark
        return image
    }
    // Fallback if the embedded asset ever fails to decode: a filled-cat SF Symbol
    // (macOS 14+), else a paw print.
    let symbol = NSImage(systemSymbolName: "cat.fill", accessibilityDescription: "Tenshu Control")
        ?? NSImage(systemSymbolName: "pawprint.fill", accessibilityDescription: "Tenshu Control")!
    symbol.isTemplate = true
    return symbol
}

// MARK: - Flush menu-bar panel
//
// A borderless NSPanel anchored flush under the menu bar (no NSPopover arrow, no
// gap) — the native "menu-bar dropdown" look (cf. the input-source / Wi-Fi menus).
// `canBecomeKey` must be true so the SwiftUI buttons inside receive clicks.

final class TrayPanel: NSPanel {
    override var canBecomeKey: Bool { true }
}

// MARK: - App delegate / status item

final class AppDelegate: NSObject, NSApplicationDelegate {
    /// ⚠ Optional, not implicitly unwrapped: the placement re-check (desktop-tray-liveness.md
    /// §4.3) removes and recreates it when AppKit never gave it a slot, so there are instants
    /// where there genuinely is none.
    private var statusItem: NSStatusItem?
    /// Consecutive heartbeat ticks that found no menu-bar slot (§4.3).
    private var unplacedTicks = 0
    /// Which unplaced tick triggers the next re-placement attempt; doubles after each.
    private var nextRetryTick = 1
    /// Last verdict written to the placement journal; nil until the first tick resolves one.
    private var lastPlacementVerdict: Bool?
    private var heartbeatTimer: Timer?
    private let model = TrayModel()
    private var panel: TrayPanel!
    private var hosting: NSHostingView<PopoverView>!
    private var dismissMonitor: Any?
    // Posts macOS notifications for newly-arrived actionable requests
    // and raises Control when one is tapped (→ Home, where the pinned cards live).
    // This is the *sole* UNUserNotificationCenter delegate — it routes every tap
    // (incl. the pairing one below) to openControl, so the others needn't register.
    private lazy var notifier = PendingRequestNotifier(onActivate: { [model] in model.openControl() })
    // Pairing UX (control-pairing-ux.md §3.6): posts/cancels the
    // "a device wants to join" notification; taps route through `notifier`'s shared
    // delegate to openControl, where the in-app approval modal surfaces.
    private let pairingNotifier = PairingApprovalNotifier()
    private let reminderNotifier = ReminderNotifier()
    private let schemaFenceNotifier = SchemaFenceNotifier()

    func applicationDidFinishLaunching(_ notification: Notification) {
        installStatusItem()
        buildPanel()
        startHeartbeat()

        // OS-level elevation: authorize + start the background poll that drives
        // the arrived/resolved notification diff while the popover stays closed.
        model.onPendingRequests = { [weak self] rows in self?.notifier.reconcile(rows) }
        model.onPairingApproval = { [weak self] id in self?.pairingNotifier.reconcile(pendingId: id) }
        model.onCalendarReminders = { [weak self] rows in self?.reminderNotifier.reconcile(rows) }
        model.onSchemaFence = { [weak self] fenced in self?.schemaFenceNotifier.reconcile(fenced: fenced) }
        notifier.start()
        model.startPolling()
    }

    private func installStatusItem() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = item.button {
            button.image = trayIcon()
            button.action = #selector(togglePanel(_:))
            button.target = self
        }
        statusItem = item
    }

    /// Whether AppKit actually placed the item in the menu bar.
    ///
    /// ⚠⚠ `NSStatusItem.isVisible` is the wrong question — it reports the app's *intent*,
    /// and answers `true` for an item that never got a slot (a full menu bar, or a menu bar
    /// not yet up when a `RunAtLoad` daemon spawns us early in the login session). The
    /// honest test is whether the button reached a window.
    private var statusItemIsPlaced: Bool {
        statusItem?.button?.window != nil
    }

    /// Publish liveness + placement, and re-place an icon we lost (desktop-tray-liveness.md
    /// §4.1, §4.3).
    ///
    /// One timer, one question — *am I alive, and am I on screen?* Deliberately independent
    /// of `TrayModel`'s poll, which asks a different one (is the daemon reachable) and must
    /// not be able to stall this.
    private func startHeartbeat() {
        let timer = Timer.scheduledTimer(withTimeInterval: TrayHeartbeat.interval, repeats: true) {
            [weak self] _ in self?.heartbeatTick()
        }
        // Keep firing while a tracking run loop (e.g. an open menu) is active.
        RunLoop.main.add(timer, forMode: .common)
        heartbeatTimer = timer

        // Publish once shortly after launch rather than waiting a full interval: Control's
        // grace period is three intervals, and an early first write makes a fresh start
        // legible immediately instead of 10s later.
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.heartbeatTick() }
    }

    private func heartbeatTick() {
        if statusItemIsPlaced {
            unplacedTicks = 0
            nextRetryTick = 1
        } else {
            unplacedTicks += 1
            if unplacedTicks >= nextRetryTick {
                // ⚠ Only ever reached with no window: an item that IS placed is never
                // touched, so this cannot be what makes an icon disappear.
                if let item = statusItem { NSStatusBar.system.removeStatusItem(item) }
                installStatusItem()
                nextRetryTick = min(nextRetryTick * 2, Self.maxRetryBackoffTicks)
            }
        }

        let placed = statusItemIsPlaced
        // The level — "is the icon there now" — for Control's liveness guard.
        TrayHeartbeat.publish(visible: placed)
        // ⚠ …and the transition, which the level cannot preserve. A placement failure that the
        // re-check heals is invisible in the level ten seconds later; the journal is how a login
        // race stays provable after the fact rather than only while it is happening.
        if lastPlacementVerdict != placed {
            lastPlacementVerdict = placed
            TrayHeartbeat.recordTransition(visible: placed, unplacedTicks: unplacedTicks)
        }
    }

    /// Retry backoff ceiling: one re-placement attempt per 30 ticks (5 min). Never gives up —
    /// a menu bar that is full now may free a slot later, and the cost of asking is a nil check.
    private static let maxRetryBackoffTicks = 30

    private func buildPanel() {
        hosting = NSHostingView(rootView: PopoverView(model: model))

        // Native menu chrome: rounded, behind-window blur. Clips its subviews to
        // the rounded corners (masksToBounds), so the SwiftUI content is rounded too.
        let effect = NSVisualEffectView()
        effect.material = .menu
        effect.blendingMode = .behindWindow
        effect.state = .active
        effect.wantsLayer = true
        effect.layer?.cornerRadius = 10
        effect.layer?.masksToBounds = true
        hosting.translatesAutoresizingMaskIntoConstraints = false
        effect.addSubview(hosting)
        NSLayoutConstraint.activate([
            hosting.leadingAnchor.constraint(equalTo: effect.leadingAnchor),
            hosting.trailingAnchor.constraint(equalTo: effect.trailingAnchor),
            hosting.topAnchor.constraint(equalTo: effect.topAnchor),
            hosting.bottomAnchor.constraint(equalTo: effect.bottomAnchor),
        ])

        panel = TrayPanel(
            contentRect: NSRect(x: 0, y: 0, width: 280, height: 120),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: true
        )
        panel.contentView = effect
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.level = .popUpMenu
        panel.isFloatingPanel = true
        panel.isMovable = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
    }

    @objc private func togglePanel(_ sender: AnyObject?) {
        if panel.isVisible { hidePanel() } else { showPanel() }
    }

    private func showPanel() {
        guard let button = statusItem?.button, let buttonWindow = button.window else { return }
        model.refresh()

        // Size to the SwiftUI content's intrinsic size (PopoverView fixes width).
        let size = hosting.fittingSize
        panel.setContentSize(size)

        // Flush under the menu bar, left-aligned to the status item, clamped on-screen.
        let itemRect = buttonWindow.convertToScreen(button.convert(button.bounds, to: nil))
        let visible = (buttonWindow.screen ?? NSScreen.main!).visibleFrame
        var x = itemRect.minX
        if x + size.width > visible.maxX - 8 { x = visible.maxX - 8 - size.width }
        if x < visible.minX + 8 { x = visible.minX + 8 }
        let y = itemRect.minY - size.height // panel top edge flush with the item's bottom
        panel.setFrame(NSRect(x: x, y: y, width: size.width, height: size.height), display: true)

        panel.makeKeyAndOrderFront(nil)
        statusItem?.button?.highlight(true)

        // Transient: any click outside our app dismisses. Global monitors don't fire
        // for our own events (the status item + this panel are ours), so clicking the
        // item toggles cleanly and clicking inside the panel doesn't dismiss it.
        dismissMonitor = NSEvent.addGlobalMonitorForEvents(
            matching: [.leftMouseDown, .rightMouseDown]
        ) { [weak self] _ in self?.hidePanel() }

        // ⚠ …and the panel's own buttons, which the monitor above deliberately cannot see.
        // Without this an action runs and the panel just sits there. Not set once at launch
        // because `showPanel` is where the panel is guaranteed to exist.
        model.onDismissRequested = { [weak self] in self?.hidePanel() }
    }

    private func hidePanel() {
        if let m = dismissMonitor { NSEvent.removeMonitor(m); dismissMonitor = nil }
        panel.orderOut(nil)
        statusItem?.button?.highlight(false)
    }
}

// MARK: - Bootstrap

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory) // menu-bar agent, no Dock icon
app.run()
