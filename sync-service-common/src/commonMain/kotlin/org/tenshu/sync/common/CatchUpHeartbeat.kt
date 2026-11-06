package org.tenshu.sync.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.tenshu.core.common.time.SystemClock

/**
 * How often a catching-up runtime rewrites its `SyncCatchUpHeartbeat` row, and how long a reader
 * trusts one (`ios-sync-activity-indicator.md` §12.2).
 *
 * - [intervalMs] **5 s.** The cost is one small, non-`Syncable` store transaction per interval per
 *   catching-up runtime, and only while it catches up. §10.4 measured that a status-row write wakes
 *   that relation's readers and nothing else (the feed: 0 re-deliveries over 40 writes).
 * - [staleAfterMs] **15 s = 3 intervals.** Two beats can be missed — a write queued behind a
 *   sibling's held writer lock, a slow store under load — before a live pull reads as stopped. It
 *   is also how long a killed app's last beat can still light a sibling's capsule.
 *
 * Starting values, as the debounce's are; neither was measured on a device.
 */
data class CatchUpHeartbeatTiming(
    val intervalMs: Long,
    val staleAfterMs: Long,
) {
    init {
        require(intervalMs > 0) { "intervalMs must be positive" }
        require(staleAfterMs > intervalMs) { "staleAfterMs must exceed intervalMs, or a live writer flickers" }
    }

    companion object {
        val Default = CatchUpHeartbeatTiming(intervalMs = 5_000, staleAfterMs = 15_000)
    }
}

/** The device's wall clock, in epoch milliseconds: what every writer stamps and reader compares. */
val wallClockMillis: () -> Long = { SystemClock().nowEpochMillis() }

/** One row that says `catchingUp`: its writer and its stamp. */
data class CatchUpBeat(val writer: String, val atMillis: Long)

/**
 * Whether any of [initial] — the rows that say `catchingUp` — is fresh, **as time passes with no
 * new write**: `true` while one is, then `false`, and the flow completes.
 *
 * ## The timer exists only while something is fresh
 *
 * With nothing fresh it emits `false` and completes at once: an idle store costs no timer. While
 * something is fresh it waits until the youngest beat would expire or [recheckMs], whichever is
 * sooner, and then asks [reread] for the rows again — so a sibling that is still beating keeps the
 * answer `true`, and one that settled or died lets it fall. The caller re-runs this on the
 * relation's next change (`flatMapLatest`), which also cancels the wait.
 *
 * [reread] exists for a store whose listeners do not hear a sibling process's writes (the shared
 * iOS replica; `ios-sync-activity-indicator.md` §12.3). `null` = no re-read: expiry only.
 *
 * ## A heartbeat from the future
 *
 * A stamp more than [recheckMs] — one beat interval — ahead of the clock is **expired**. A live
 * writer stamps with the same clock and rewrites within one interval, so after the clock is set
 * back its next beat is current again; a stamp still that far ahead was left by a writer that
 * stopped, and must not count — not until the clock reaches it (which, after a jump of a day,
 * would be a day), and not for a fresh [staleAfterMs] at every new subscription either (the
 * surface re-subscribes at every foreground). A stamp less than one interval ahead is ordinary
 * skew and counts as just written.
 */
fun catchUpHeartbeatLiveness(
    initial: List<CatchUpBeat>,
    nowMillis: () -> Long,
    staleAfterMs: Long,
    recheckMs: Long,
    reread: (suspend () -> List<CatchUpBeat>)?,
): Flow<Boolean> = flow {
    var beats = initial
    while (true) {
        val now = nowMillis()
        var longest = 0L
        for (beat in beats) {
            val left = catchUpHeartbeatFreshForMs(beat.atMillis, now, staleAfterMs, recheckMs)
            if (left > longest) longest = left
        }
        if (longest <= 0) {
            emit(false)
            return@flow
        }
        emit(true)
        val wait = if (reread == null) longest else minOf(longest, recheckMs)
        delay(wait)
        if (reread != null) beats = reread()
    }
}

/**
 * For how many more milliseconds a beat stamped [atMillis] counts, judged at [nowMillis]; `0` or
 * less means it does not. A stamp more than [futureToleranceMs] ahead never counts (see
 * [catchUpHeartbeatLiveness]); one within it counts as stamped now.
 */
fun catchUpHeartbeatFreshForMs(
    atMillis: Long,
    nowMillis: Long,
    staleAfterMs: Long,
    futureToleranceMs: Long,
): Long {
    val age = nowMillis - atMillis
    return when {
        age < -futureToleranceMs -> 0
        age < 0 -> staleAfterMs
        else -> staleAfterMs - age
    }
}
