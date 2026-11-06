package app.tenshu.diagnostics

/**
 * The pure bounded-log policy: keep the most recent [capacity] items, oldest dropped first. Extracted
 * so retention is unit-tested without a device — the platform [DiagnosticSink] persists whatever this
 * returns.
 *
 * A count cap (not a time window) because it is the simple, self-limiting rule that can't be gamed by
 * a burst: 500 items is 500 items whether they span a minute or a month. Time-based eviction is a
 * refinement, not a v1 need — and notably the shipped `ErrorEvent` screen *promises* "500 / 30 days"
 * while enforcing neither, a gap this deliberately does not repeat.
 */
class DiagnosticRing(private val capacity: Int) {
    init { require(capacity > 0) { "capacity must be positive" } }

    /** Append [item] to [existing], dropping the oldest overflow. Newest-last ordering preserved. */
    fun appended(existing: List<LogItem>, item: LogItem): List<LogItem> {
        val next = existing + item
        return if (next.size <= capacity) next else next.subList(next.size - capacity, next.size)
    }

    /** Trim an over-long list (e.g. loaded from disk after a capacity decrease) to the cap. */
    fun trimmed(items: List<LogItem>): List<LogItem> =
        if (items.size <= capacity) items else items.subList(items.size - capacity, items.size)
}
