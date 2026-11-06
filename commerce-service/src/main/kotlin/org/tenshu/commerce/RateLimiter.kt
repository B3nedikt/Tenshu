package org.tenshu.commerce

import java.util.concurrent.ConcurrentHashMap

/**
 * A fixed-window request budget keyed by an arbitrary string
 * (`commerce-end-to-end-dry-run.md` CE-2 / D4).
 *
 * **Why this module needs one at all.** `POST /commerce/account/register` is unauthenticated *by
 * design* — the verified email **is** the sybil gate, so there is nothing to authenticate before
 * it. That was free while the only `EmailSender` wrote to a log. The moment a real transport lands,
 * every unauthenticated request becomes an outbound email with a per-message price attached to our
 * sender reputation, and an attacker can also point it at a victim's inbox. So the limiter ships in
 * the same phase as the sender, not after it.
 *
 * **Fixed window, not a sliding one.** A caller can burst up to 2×[limit] across a window boundary.
 * That is a known and accepted property: the budgets here are small integers whose job is to bound
 * cost and mail-bombing, not to shape traffic precisely, and a sliding window costs per-key history
 * for no meaningful gain at this scale. (The relay's genesis limiter makes the same trade via
 * Ktor's `RateLimit` plugin; this one is hand-rolled because two of the three budgets key on the
 * request **body** — the email address — which a `requestKey` lambda cannot see.)
 *
 * ⚠⚠ **The key table is itself an attack surface, so it fails CLOSED.** An attacker rotating source
 * addresses grows the map without bound, which is a memory-exhaustion DoS in its own right. On
 * reaching [maxTrackedKeys] the limiter first drops expired windows; if it is still full, a request
 * bearing an **unseen** key is refused. Failing *open* there would be strictly worse: the table is
 * cheapest to fill exactly when an attack is underway, and the failure mode would be unbounded
 * outbound mail — precisely the cost this class exists to prevent. The cap is set high enough
 * ([DEFAULT_MAX_TRACKED_KEYS]) that reaching it means a real attack rather than a busy hour, and
 * [onTableFull] fires so that condition is observable rather than silent.
 *
 * [limit] `<= 0` disables the budget entirely (every call allowed), mirroring the relay's
 * `GENESIS_RATE_LIMIT_PER_HOUR` convention.
 *
 * Time is passed in rather than read, so the whole class is deterministically testable.
 */
class RateLimiter(
    private val limit: Int,
    private val windowMs: Long,
    private val maxTrackedKeys: Int = DEFAULT_MAX_TRACKED_KEYS,
    private val onTableFull: () -> Unit = {},
) {

    private class Window(val startedAt: Long, var count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    /** Number of keys currently tracked. Exposed for tests and for an operational gauge. */
    val trackedKeys: Int get() = windows.size

    /**
     * Whether [key] has budget left and the table could hold it — **without consuming anything**.
     * Exists so a caller guarding one action with several budgets can decide first and spend
     * after, instead of charging budgets that a later check is going to refuse anyway. See
     * `AccountRateLimits.allowSend`.
     */
    fun wouldAllow(key: String, nowMs: Long): Boolean {
        if (limit <= 0) return true
        val existing = windows[key]
        if (existing != null && nowMs - existing.startedAt < windowMs) return existing.count < limit
        return canAdmit(key, nowMs)
    }

    /**
     * Consume one unit of [key]'s budget. Returns false when the budget is spent (the caller should
     * answer `429`) or when the key table is full of live windows (see the class note).
     */
    fun tryAcquire(key: String, nowMs: Long): Boolean {
        if (limit <= 0) return true
        if (!canAdmit(key, nowMs)) {
            onTableFull()
            return false
        }

        var allowed = false
        windows.compute(key) { _, existing ->
            if (existing == null || nowMs - existing.startedAt >= windowMs) {
                // No window, or the previous one has rolled over: start a fresh one.
                allowed = true
                Window(nowMs, 1)
            } else {
                if (existing.count < limit) {
                    existing.count++
                    allowed = true
                }
                existing
            }
        }
        return allowed
    }

    /**
     * Whether the key table can hold [key] — trivially true while it is under the ceiling or the
     * key is already tracked, otherwise true only if pruning elapsed windows makes room. This is
     * the fail-closed gate described in the class note.
     */
    private fun canAdmit(key: String, nowMs: Long): Boolean {
        if (windows.size < maxTrackedKeys || windows.containsKey(key)) return true
        pruneExpired(nowMs)
        return windows.size < maxTrackedKeys || windows.containsKey(key)
    }

    /** Drop every window whose period has elapsed. Called on table pressure; safe to call anytime. */
    fun pruneExpired(nowMs: Long) {
        windows.entries.removeIf { nowMs - it.value.startedAt >= windowMs }
    }

    companion object {
        /**
         * Ceiling on distinct tracked keys. ~100k live windows is a few MB — far above any honest
         * hour on this service, and low enough that a rotation attack cannot exhaust the heap
         * before the fail-closed path engages.
         */
        const val DEFAULT_MAX_TRACKED_KEYS = 100_000
    }
}
