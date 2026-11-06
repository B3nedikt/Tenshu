package app.tenshu.diagnostics

/**
 * Where diagnostic items are persisted — the platform seam. The sink stamps the timestamp (it owns a
 * clock) and enforces the [DiagnosticRing] cap, so callers pass only level/tag/message/code.
 *
 * [append] must be **synchronous and crash-safe**: the crash handler calls it while the process is
 * dying, so the write has to hit durable storage before control returns (no async flush would run).
 */
interface DiagnosticSink {

    /** Persist one item now. Must not throw — a logging failure must never worsen a crash. */
    fun append(level: LogLevel, tag: String, message: String, code: String?)

    /** Most recent [limit] items, newest last — for the in-app Diagnostics screen and upload. */
    fun recent(limit: Int): List<LogItem>

    /** Drop everything (a user "clear" action). */
    fun clear()

    /** A no-op sink for platforms/tests that don't persist diagnostics. */
    object NoOp : DiagnosticSink {
        override fun append(level: LogLevel, tag: String, message: String, code: String?) = Unit
        override fun recent(limit: Int): List<LogItem> = emptyList()
        override fun clear() = Unit
    }
}
