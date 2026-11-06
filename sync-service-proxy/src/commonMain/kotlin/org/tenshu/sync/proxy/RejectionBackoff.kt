package org.tenshu.sync.proxy

import kotlin.math.min

/**
 * Exponential backoff keyed on a relay rejection the sync loop cannot fix by retrying faster.
 *
 * Two instances exist, one per rejection class the relay can hold a device in indefinitely:
 *  - **`402` entitlement** enforcement ([isEntitlementRejection]) — the device is entitled-but-
 *    credential-less. Track A P0 of `docs/proposals/enforcement-followup-tracks.md`.
 *  - **`401` unauthorized** ([isRelayUnauthorized]) — the relay does not accept this device's
 *    identity, e.g. it no longer has a registration for it. Paired with the re-registration
 *    repair in [RealmRegistrar.reregisterThisDevice].
 *
 * Both had the same observed failure mode: a doomed request retried at tick cadence forever.
 * The 402 case (`second-device-entitlement-enforcement.md` §1) storm was **~6×/sec with no
 * backoff** (~1401 rejects in 4 min); the 401 case, measured against the live relay on
 * 2026-08-17, was **20,970 rejected content `PUT`s + 19,646 rejected `POST /sync/log` in five
 * hours** from devices whose registration a relay wipe had dropped — ~8k requests/hour/device,
 * indefinitely, surfaced nowhere in any UI.
 *
 * This state machine bounds both: on each rejection the retry window grows geometrically
 * ([baseDelayMs] · 2ⁿ, capped at [maxDelayMs]); [isBackingOff] tells the scheduler to skip the
 * doomed work until the window elapses.
 *
 * Recovery is prompt (proposal §6a — no restart required): the window is reset by [onCleared]
 * when the request succeeds / the block clears, or by [reset] on an external signal (reconnect,
 * a credential becoming available, a successful re-registration). After a reset the very next
 * tick proceeds. Pull is never gated by the entitlement instance — the scheduler keeps pulling
 * while backing off, so a synced `Entitlement` (or a reconnect) can be observed and clear it.
 *
 * Not thread-safe by itself; the scheduler only ever touches these under its `tickMutex`.
 */
class RejectionBackoff(
    private val nowMs: () -> Long,
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
) {
    private var consecutiveRejections = 0
    private var nextAttemptAtMs = 0L
    private var blocked = false

    /**
     * Whether the client is currently in the blocked state (a rejection was seen and not yet
     * cleared). Stays `true` through the whole backoff window — drives the "needs attention on
     * this device" health signals — whereas [isBackingOff] additionally goes `false` at each
     * window boundary so the scheduler makes its next probing attempt.
     */
    val isBlocked: Boolean get() = blocked

    /** The current backoff delay in ms (0 when not blocked). Exposed for tests / diagnostics. */
    val currentDelayMs: Long
        get() = if (consecutiveRejections == 0) 0L else delayFor(consecutiveRejections)

    /** True while inside a backoff window — the scheduler skips the doomed work until it elapses. */
    fun isBackingOff(): Boolean = blocked && nowMs() < nextAttemptAtMs

    /** Record a rejection: mark blocked and grow the retry window geometrically. */
    fun onRejected() {
        consecutiveRejections++
        blocked = true
        nextAttemptAtMs = nowMs() + delayFor(consecutiveRejections)
    }

    /**
     * The request succeeded / the block cleared. Resets the window so steady-state (unblocked)
     * behaviour resumes with no residual delay.
     */
    fun onCleared() = reset()

    /**
     * External "the situation may differ now" signal — reconnect, a credential becoming available,
     * enforcement relaxed, a repair having just run. Drops the block so the next tick proceeds
     * immediately instead of waiting out a (possibly minutes-long) window. Idempotent and cheap
     * when already cleared.
     */
    fun reset() {
        consecutiveRejections = 0
        nextAttemptAtMs = 0L
        blocked = false
    }

    /**
     * Open the current window without forgetting how many rejections preceded it: the next attempt
     * may go out immediately, but if it is rejected again the window resumes growing from where it
     * was rather than restarting at [baseDelayMs].
     *
     * For "we just did something that might have fixed this" — e.g. a re-registration that the relay
     * accepted. A full [reset] there would be wrong in the pathological case: a relay that accepts
     * the repair yet keeps rejecting the traffic would be repaired-and-retried every [baseDelayMs]
     * forever, which is the storm this class exists to prevent, only slower. Clearing the block
     * outright stays the job of [onCleared] — i.e. of a call the relay actually accepted.
     */
    fun allowImmediateRetry() {
        nextAttemptAtMs = 0L
    }

    private fun delayFor(rejections: Int): Long {
        // baseDelay · 2^(n-1), capped. Shift is bounded so it never overflows before the min().
        val shift = min(rejections - 1, 30)
        val scaled = baseDelayMs shl shift
        return if (scaled <= 0L) maxDelayMs else min(scaled, maxDelayMs)
    }

    companion object {
        const val DEFAULT_BASE_DELAY_MS: Long = 2_000L

        /**
         * Cap the window at 2 min: a 6/sec storm collapses to at most one probe every 2 min
         * (≈360× fewer requests), while auto-recovery after the cause clears stays within 2 min
         * even if no external [reset] trigger (reconnect / credential / repair) fires first.
         */
        const val DEFAULT_MAX_DELAY_MS: Long = 120_000L
    }
}
