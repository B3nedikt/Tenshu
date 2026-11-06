// The tray's self-report, written beside the daemon's bridge socket
// (docs/proposals/desktop-tray-liveness.md §4.1).
//
// ⚠⚠ WHY THIS EXISTS. Control used to decide whether to start a tray by asking `pgrep`
// whether one was running. On 2026-08-31 a tray agent four days older than the Control
// "running" it held the menu bar with no icon on it, and Control — seeing a live pid —
// declined to spawn the one that would have been visible. A process table cannot see a
// menu bar. Only the agent itself can answer both halves of the real question, so it
// writes them down: that its run loop is turning (the timestamp) and that AppKit actually
// placed its status item (the verdict). `TrayLiveness` on the Kotlin side reads this.

import Foundation

enum TrayHeartbeat {
    /// Mirrored in `TrayLiveness.HEARTBEAT_FILE`.
    static let fileName = "tray-agent.state"

    /// Mirrored in `TrayLiveness.HEARTBEAT_INTERVAL_SECONDS`; Control treats three missed
    /// intervals as death, so a single slow tick is never fatal.
    static let interval: TimeInterval = 10

    static func path() -> String {
        "\(BridgeClient.defaultSocketDir())/\(fileName)"
    }

    /// Publishes `<epochMillis> <visible|hidden>`.
    ///
    /// Atomic, so the reader can never catch a half-written line — the Kotlin parser treats
    /// a malformed file as "no heartbeat", which would replace a perfectly healthy agent.
    /// Best-effort otherwise: a tray that cannot show its state must still show its icon.
    static func publish(visible: Bool, at date: Date = Date()) {
        let millis = Int64(date.timeIntervalSince1970 * 1000)
        let line = "\(millis) \(visible ? "visible" : "hidden")\n"
        let url = URL(fileURLWithPath: path())
        try? FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try? line.write(to: url, atomically: true, encoding: .utf8)
    }

    /// The placement journal: one line per *change* of verdict, including the first one.
    ///
    /// ⚠⚠ WHY A SECOND FILE. `publish` writes a **level**, rewritten every tick, so it answers
    /// "is the icon there now" and destroys its own history. That is precisely the wrong shape
    /// for the question this whole change exists to settle: if an icon fails to place at login
    /// and TL-2 recovers it ten seconds later, the level reads `visible` by the time anyone
    /// looks and the login race erases itself. The journal is append-only and records the
    /// **transition**, so a recovered failure stays legible after the fact:
    ///
    /// ```
    /// 1788352944806 hidden  unplacedTicks=1
    /// 1788352964806 visible unplacedTicks=0
    /// ```
    ///
    /// Tray-owned and single-writer — deliberately NOT `tray-agent.log`, which Control
    /// read-modify-writes at startup; two writers there would race.
    static let journalFileName = "tray-agent.placement.log"
    static let journalMaxLines = 100

    static func journalPath() -> String {
        "\(BridgeClient.defaultSocketDir())/\(journalFileName)"
    }

    static func recordTransition(visible: Bool, unplacedTicks: Int, at date: Date = Date()) {
        let millis = Int64(date.timeIntervalSince1970 * 1000)
        let line = "\(millis) \(visible ? "visible" : "hidden") unplacedTicks=\(unplacedTicks)"
        let url = URL(fileURLWithPath: journalPath())
        try? FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        var lines = (try? String(contentsOf: url, encoding: .utf8))?
            .split(separator: "\n", omittingEmptySubsequences: true)
            .map(String.init) ?? []
        lines.append(line)
        let kept = lines.suffix(journalMaxLines).joined(separator: "\n") + "\n"
        try? kept.write(to: url, atomically: true, encoding: .utf8)
    }
}
