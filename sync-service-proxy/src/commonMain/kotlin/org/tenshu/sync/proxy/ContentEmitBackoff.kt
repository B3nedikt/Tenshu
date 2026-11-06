package org.tenshu.sync.proxy

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * Per-entry retry backoff for the deferred-content drain (`control-background-work-off-fgs.md`
 * §5.7): an entry whose upload failed waits before the drain tries it again — exponentially longer
 * each time, with jitter, up to [maxMs].
 *
 * ⚠⚠ **Why per entry.** Measured on the API 37 emulator with every content `PUT` answered 500: the
 * drain retried the whole journal on every tick, about 3,000 `PUT`s a minute for as long as the
 * process had a network. The push job's two-minute no-send stall and 15-minute park (§5.3) bound how
 * long that lasts; this bounds the rate while it does, and it holds on every platform, not only under
 * Android's push job.
 *
 * ⚠ **Only an entry whose content `PUT` failed for the plane's sake is backed off**
 * ([isContentPlaneOutage]: a 5xx, a 429, a transport failure). An entry someone else is emitting,
 * one kept for a local move, bytes that changed under the stream, a `507` (its own pass-wide stop
 * and scheduler backoff), a `402`/`401` (their own backoffs) and a `4xx` about the object are not
 * the plane failing.
 *
 * ⚠⚠ **Any upload that lands clears every wait** ([landed]). A success says the content plane is
 * answering again, and a healed relay must not leave a 300-file backlog waiting out up to [maxMs]
 * each. One entry that keeps failing on its own (a relay that 500s one object) is then retried once
 * per pass in which something else landed: a trickle, not a storm.
 *
 * ⚠ In memory, by decision: a restart starts every entry from zero, which costs one attempt each.
 * Persisting the waits would make a stale wait survive the very restart a user tries first.
 *
 * ⚠ Thread-safe (an immutable map behind a compare-and-set): the drain runs under the scheduler's
 * tick lock, but an inline upload landing on a client's call reports [landed] from outside it.
 */
@OptIn(ExperimentalAtomicApi::class)
class ContentEmitBackoff(
    private val nowMs: () -> Long,
    private val baseMs: Long = BASE_MS,
    private val maxMs: Long = MAX_MS,
    private val random: Random = Random.Default,
) {
    private class Wait(val failures: Int, val untilMs: Long)

    /**
     * Every entry's wait, plus the plane's: [inARow] refused writes since the last landing, and how
     * often the plane has tripped ([trips]) and until when it waits ([planeUntilMs]).
     */
    private data class State(
        val waits: Map<String, Wait> = emptyMap(),
        val inARow: Int = 0,
        val trips: Int = 0,
        val planeUntilMs: Long = 0,
    )

    private val state = AtomicReference(State())

    private inline fun update(change: (State) -> State) {
        while (true) {
            val current = state.load()
            if (state.compareAndSet(current, change(current))) return
        }
    }

    /** Whether [emitKey] is still waiting out its last failure. */
    fun isWaiting(emitKey: String): Boolean {
        val wait = state.load().waits[emitKey] ?: return false
        return nowMs() < wait.untilMs
    }

    /**
     * ⚠⚠ Whether the whole content plane is waiting: [PLANE_TRIP] refused writes in a row, on any
     * path, with nothing landing between. Per-entry waits alone bound only the REPEATS: measured on
     * the emulator, a 1,095-entry backlog's first attempts were ~9,000 failing `PUT`s in 3.5 min.
     * The plane waits on the same schedule as an entry, counted in trips; [landed] ends it.
     */
    fun planeWaiting(): Boolean = nowMs() < state.load().planeUntilMs

    /** How long the plane still waits, in ms; 0 when it does not. */
    fun planeWaitRemainingMs(): Long = (state.load().planeUntilMs - nowMs()).coerceAtLeast(0)

    /**
     * ⚠⚠ Let the next write through while the plane waits — **half-open**, not reset. Called on what
     * says the world may have changed: the user starting an upload, the network coming back, the app
     * coming to the front, *Sync now*. Without it a long outage would hold every upload for the rest of
     * a wait of up to [maxMs] after the relay had healed, because only [landed] ends it and nothing can
     * land while the plane waits.
     *
     * Half-open: the wait ends, but the trip count stays and one more refusal trips it again, at the
     * NEXT, longer wait. A recovery signal that turns out wrong costs one attempt, not a storm.
     * Per-entry waits stay too: they end when something lands.
     */
    fun halfOpen() {
        update { current ->
            if (current.planeUntilMs == 0L && current.inARow < PLANE_TRIP - 1) {
                current
            } else {
                current.copy(planeUntilMs = 0, inARow = PLANE_TRIP - 1)
            }
        }
    }

    /**
     * [emitKey]'s upload was attempted and failed: wait `base × 2^(n-1)`, capped at [maxMs], then
     * jittered down to between half and all of it ("equal jitter"), so a backlog that failed together
     * does not retry together.
     */
    fun failed(emitKey: String, reason: String? = null) {
        // ⚠⚠ Only the plane failing counts: a 402, 401, 413 or 400 is about this device, this realm
        // or this object, and one breaker serves every realm — otherwise a lapsed shared realm's 402s
        // would hold the personal realm's uploads.
        if (!isContentPlaneOutage(reason)) return
        val jitter = random.nextDouble()
        val planeJitter = random.nextDouble()
        val now = nowMs()
        update { current ->
            val failures = (current.waits[emitKey]?.failures ?: 0) + 1
            val waits = current.waits + (emitKey to Wait(failures, now + delayFor(failures, jitter)))
            val inARow = current.inARow + 1
            if (inARow < PLANE_TRIP) {
                current.copy(waits = waits, inARow = inARow)
            } else {
                val trips = current.trips + 1
                State(waits, inARow = 0, trips = trips, planeUntilMs = now + delayFor(trips, planeJitter))
            }
        }
    }

    /** The wait after the [failures]th failure in a row; [jitter] in [0, 1). */
    internal fun delayFor(failures: Int, jitter: Double): Long {
        val exponent = (failures - 1).coerceIn(0, MAX_EXPONENT)
        val ceiling = (baseMs shl exponent).coerceAtMost(maxMs)
        return ceiling / 2 + (ceiling / 2 * jitter).toLong()
    }

    /** [emitKey] left the journal (uploaded, or retired): forget it. */
    fun cleared(emitKey: String) {
        update { it.copy(waits = it.waits - emitKey) }
    }

    /** An upload landed: the plane is answering, so nothing waits any more — no entry, nor the plane. */
    fun landed() {
        if (state.load() != State()) update { State() }
    }

    /** Entries currently waiting — for tests. */
    val waitingCount: Int get() = state.load().waits.values.count { nowMs() < it.untilMs }

    companion object {
        /** The first wait: long enough that one failed pass is not repeated on the next tick. */
        const val BASE_MS: Long = 30_000

        /** The cap — the push job's own stall retry (`SyncPushScheduler.STALL_RETRY_DELAY_MS`). */
        const val MAX_MS: Long = 15L * 60 * 1000

        /** Refused writes in a row, with nothing landing, after which the whole plane waits. */
        const val PLANE_TRIP: Int = 5

        /** `30 s × 2^5` already exceeds the cap; a larger shift could overflow. */
        private const val MAX_EXPONENT = 10
    }
}

/** A monotonic millisecond clock starting at 0 — what [ContentEmitBackoff] measures its waits on. */
internal fun monotonicMs(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}

/**
 * Whether a refused content `PUT` says the **plane** is failing: a `5xx`, a `429`, or a transport
 * failure (no status at all). [reason] is the store's failure message
 * (`RestContentBlobStore.putChunk`: [CONTENT_PUT_ERROR_PREFIX] + status, a marker, or the transport
 * exception's message); `null` is a failure that said nothing, read as transport.
 */
internal fun isContentPlaneOutage(reason: String?): Boolean {
    if (reason == null) return true
    if (reason.contains(STORAGE_REJECTION_MARKER) || reason.contains(ENTITLEMENT_REJECTION_MARKER)) return false
    val at = reason.indexOf(CONTENT_PUT_ERROR_PREFIX)
    if (at < 0) return true
    val status = reason.substring(at + CONTENT_PUT_ERROR_PREFIX.length).trim().take(3).toIntOrNull() ?: return true
    return status >= 500 || status == 429
}

/** What `RestContentBlobStore.putChunk` prefixes an unclassified HTTP refusal with, before the status. */
internal const val CONTENT_PUT_ERROR_PREFIX = "Content PUT error:"
