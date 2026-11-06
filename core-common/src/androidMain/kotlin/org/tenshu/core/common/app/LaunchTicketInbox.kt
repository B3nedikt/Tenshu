package org.tenshu.core.common.app

import android.app.PendingIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a proxy-pushed launch ticket waits for the caller that asked for it
 * (`control-cross-app-open.md` §5c, Option B).
 *
 * ⚠⚠ **It exists because the ticket and the outcome race.** `openTarget` is one round trip, but
 * the [PendingIntent] cannot ride its reply — a parcelable does not fit the serialised `byte[]`
 * every other answer travels in — so Control pushes it over the *other* direction of the same
 * bound connection and the reply names it by id. Either can land first, which is what makes this
 * a rendezvous rather than a lookup.
 *
 * ⚠ **Process-wide and deliberately tiny.** Its whole life is the few milliseconds between the
 * push and the tap being honoured: a ticket is taken exactly once and dropped if nobody comes for
 * it. Nothing here is durable, and it must not become so — CO-D8 rejects a pending target that
 * outlives the gesture that asked for it, and a map that quietly kept unclaimed tickets would be
 * that, one layer down.
 */
object LaunchTicketInbox {

    private val lock = Any()
    private val waiting = mutableMapOf<String, CompletableDeferred<PendingIntent>>()

    /** Called on the Binder thread by the client's `receiveLaunchTicket` stub. */
    fun deliver(ticketId: String, ticket: PendingIntent) {
        synchronized(lock) { slotFor(ticketId) }.complete(ticket)
    }

    /**
     * The ticket named by [ticketId], or `null` if it never arrived.
     *
     * ⚠ The timeout is a real outcome, not a formality: a caller that waits forever for a push
     * that a dead proxy will never send leaves the user tapping a row that does nothing — the
     * silent failure CO-D7 exists to prevent.
     */
    suspend fun await(ticketId: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): PendingIntent? {
        val slot = synchronized(lock) { slotFor(ticketId) }
        return try {
            withTimeoutOrNull(timeoutMs) { slot.await() }
        } finally {
            synchronized(lock) { waiting.remove(ticketId) }
        }
    }

    /** ⚠ Must be called holding [lock] — both callers race for the same slot. */
    private fun slotFor(ticketId: String): CompletableDeferred<PendingIntent> =
        waiting.getOrPut(ticketId) { CompletableDeferred() }

    internal fun clearForTest() {
        synchronized(lock) { waiting.clear() }
    }

    /**
     * ⚠ Generous on purpose. The push is a local Binder transaction, so the only thing this
     * really bounds is a proxy that died between resolving and pushing.
     */
    const val DEFAULT_TIMEOUT_MS: Long = 5_000
}
