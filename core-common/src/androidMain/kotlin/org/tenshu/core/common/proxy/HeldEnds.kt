package org.tenshu.core.common.proxy

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Stream ends that did not go out although the binding to the app is alive, by stream id
 * (`core-client-resubscription.md` §4.8).
 *
 * ⚠⚠ An end is the one message whose loss cannot be shrugged off: the app's subscription is waiting
 * on it. Lost, the subscription hangs with no signal — or, if the stream's later elements still
 * went out, the app misses a `Removed` and keeps a ghost row. So a failed end is held here and
 * retried, and [AndroidClientConnection] drops the stream's own elements while it is [isHeld].
 *
 * The rules that keep a hold from doing harm of its own:
 *  - **Stream ids are reused.** An id is the hash of the query's callstack, so the same query
 *    subscribed again — a `flow()` retry, a screen re-entered — is the same id. A hold outliving
 *    its subscription would end the new one with the old error, or drop its snapshot. So
 *    [forget] is called whenever Control registers or unregisters the id (`StreamRegistry` through
 *    [ClientConnection.forgetStream]), and [clear] on a fresh connection.
 *  - **Bound to its binder.** Each hold remembers the binder it failed against and is only ever
 *    delivered to that one ([Result.GONE] when it is no longer the connection's), and a hold whose
 *    delivery finds the binder dead or gone is dropped, never put back. An app process that died
 *    took its subscriptions with it; its successor may hold a new one under the same id, which the
 *    old end would kill (the same rule as never queueing an end for a dead binder).
 *  - **Single-flight.** [retry] takes a hold out before sending it and puts it back only if it
 *    failed and nothing replaced or forgot it meanwhile, so an end is in flight at most once.
 *  - **Retried without a sender.** A quiet app would otherwise wait for a send that never comes:
 *    a timer retries with a backoff (1 s, 2 s … 30 s), [MAX_TIMED_ATTEMPTS] times — about two
 *    minutes. After that a hold waits for the next send or reconnect, and a binder that refuses a
 *    small transaction for that long is one the next send will find dead.
 */
internal class HeldEnds(
    /** Deliver [bytes] to [target] — the binder the hold was made against, and no other. */
    private val deliver: (target: Any, bytes: ByteArray) -> Result,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
) {
    enum class Result {
        DELIVERED,

        /** The target is dead or no longer the connection's binder: drop the hold. */
        GONE,

        /** Alive and refused (buffer full …): hold again. */
        FAILED,
    }

    private class Hold(val bytes: ByteArray, val target: Any) {
        var attempts = 0
    }

    private val waiting = LinkedHashMap<Long, Hold>()
    private val inFlight = HashMap<Long, Hold>()
    private var timerArmed = false
    private var closed = false

    fun hold(streamId: Long, bytes: ByteArray, target: Any) {
        synchronized(this) {
            if (closed) return
            inFlight.remove(streamId)
            waiting[streamId] = Hold(bytes, target)
        }
        armTimer()
    }

    fun isHeld(streamId: Long): Boolean = synchronized(this) { streamId in waiting || streamId in inFlight }

    /** The id was registered afresh or unregistered: whatever is held for it belongs to nobody. */
    fun forget(streamId: Long) {
        synchronized(this) {
            waiting.remove(streamId)
            inFlight.remove(streamId)
        }
    }

    fun clear() {
        synchronized(this) {
            waiting.clear()
            inFlight.clear()
        }
    }

    /** The connection is gone for good: drop everything, and a pending timer does nothing. */
    fun close() {
        synchronized(this) {
            closed = true
            waiting.clear()
            inFlight.clear()
        }
    }

    /** Send every waiting hold once; the failures wait again. */
    fun retry() {
        val taken = synchronized(this) {
            if (waiting.isEmpty()) return
            waiting.entries.map { it.key to it.value }.also { list ->
                waiting.clear()
                list.forEach { (id, hold) -> inFlight[id] = hold }
            }
        }
        for ((id, hold) in taken) {
            val result = deliver(hold.target, hold.bytes)
            synchronized(this) {
                if (inFlight[id] === hold) {
                    inFlight.remove(id)
                    if (result == Result.FAILED && id !in waiting && !closed) {
                        hold.attempts++
                        waiting[id] = hold
                    }
                }
            }
        }
        armTimer()
    }

    private fun armTimer() {
        val delayMs = synchronized(this) {
            if (closed || timerArmed || waiting.isEmpty()) return
            val attempts = waiting.values.minOf { it.attempts }
            if (attempts >= MAX_TIMED_ATTEMPTS) return
            timerArmed = true
            (1_000L shl attempts.coerceAtMost(5)).coerceAtMost(30_000L)
        }
        schedule(delayMs) {
            synchronized(this) { timerArmed = false }
            retry()
        }
    }

    companion object {
        const val MAX_TIMED_ATTEMPTS = 8

        /** One daemon thread for every connection's timed retries; a retry is one small transaction. */
        private val timer: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "tenshu-held-ends").apply { isDaemon = true }
            }
        }

        val defaultSchedule: (Long, () -> Unit) -> Unit = { delayMs, task ->
            timer.schedule({ task() }, delayMs, TimeUnit.MILLISECONDS)
        }
    }
}
