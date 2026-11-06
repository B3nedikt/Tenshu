package org.tenshu.sync.proxy

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot conflated wake channel that lets event sources shortcut
 * [SyncScheduler]'s cold/hot poll interval:
 *
 * - a user-originated save enqueuing an outbound block ([SyncObserver]), and
 * - the idle→hot stream-subscriber edge ([SyncScheduler] itself).
 *
 * Conflated by construction: many [wake] calls between two [awaitOrTimeout]
 * waits collapse to a single pending permit, so a burst of saves triggers at
 * most one extra drain pass — a backlog of wakes is meaningless, one drain
 * clears whatever accumulated. A [wake] fired while nobody is waiting is
 * retained: the next [awaitOrTimeout] returns immediately rather than losing
 * the signal (level-on-edge — "at least one wake since the last wait").
 *
 * See `core-background-processing.md` §8 Phase 4a-bis. The user-vs-ingest
 * discrimination that makes firing this safe is carried by
 * [org.tenshu.core.common.proxy.WriteOrigin]: `SyncObserver` only reaches the
 * block-enqueue path for user-originated saves (`origin != SYNC_INGEST`), so
 * wiring [wake] there cannot start a paired-device echo — an ingested block
 * waking a push that re-sends it.
 */
class SyncWakeSignal {

    // CONFLATED: trySend never fails and only the latest single Unit is
    // buffered. At most one permit — see the class KDoc for why a backlog
    // would be meaningless.
    private val channel = Channel<Unit>(Channel.CONFLATED)

    /**
     * Signals a wake. Non-suspending and never fails — safe to call from
     * synchronous observer dispatch.
     */
    fun wake() {
        channel.trySend(Unit)
    }

    /**
     * Suspends until either [wake] is called or [timeoutMs] elapses, whichever
     * comes first; returns in both cases (the caller loops). A permit from a
     * [wake] fired before this call is observed immediately. Callers should run
     * a cooperative `yield()` before invoking this so a buffered wake doesn't
     * starve other work in tight loops — see [SyncScheduler.start].
     */
    suspend fun awaitOrTimeout(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { channel.receive() }
    }
}
