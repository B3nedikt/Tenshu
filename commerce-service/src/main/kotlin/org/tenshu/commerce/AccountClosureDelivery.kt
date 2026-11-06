package org.tenshu.commerce

import kotlinx.coroutines.delay

/**
 * Hands freshly-minted closure records to the relay (`retention-quota-model.md` Face A / D4).
 *
 * ⚠⚠ **Delivery is a repeating sweep, not an event.** The obvious design — post once when the
 * account closes — cannot work here: the record is deliberately short-lived (see
 * [AccountClosureMinter]) and the relay stores none of it, so a single delivery would expire long
 * before the grace window it starts. Re-posting *is* the mechanism: while an account stays closed
 * commerce keeps handing over a fresh record, and the moment it reopens the sweep stops finding it
 * and the last record simply expires. Nothing has to be un-said.
 *
 * ⚠ **A failed delivery is not an error worth remembering.** There is no queue, no retry schedule
 * and no dead-letter path: the next sweep re-mints from the store, which is the durable truth. A
 * relay that was down for an hour is caught up by the following tick, and the only cost of a lost
 * post is that reclamation happens one sweep later than it could have.
 *
 * ⚠ The transport is injected as a plain suspending function so this class holds no HTTP client and
 * a test can drive it without a server.
 */
class AccountClosureDelivery(
    private val minter: AccountClosureMinter,
    /**
     * Post one record; return the relay's HTTP status, or `null` if no response came back (network
     * fault). Must not throw for a network fault — a throw is counted as `null` regardless.
     */
    private val post: suspend (token: String) -> Int?,
) {

    /**
     * What one sweep did. **Counts only** — a record is a delete capability and is never logged, and
     * neither is the owner it names.
     *
     * [byStatus] keys each outcome by the relay's HTTP status (`null` = no response), so a sweep that
     * fails can say *how*: a run of `401`s is a signature / key / clock problem that will repeat every
     * sweep, which an `n/m delivered` line would hide as an ordinary outage.
     */
    data class Report(val attempted: Int, val byStatus: Map<Int?, Int> = emptyMap()) {
        /** Records the relay accepted (any 2xx): `202` = within grace, `200` = reclaimed. */
        val delivered: Int get() = byStatus.entries.sumOf { (status, n) -> if (status.isSuccess()) n else 0 }

        /** e.g. `2 delivered (1×202 within grace, 1×200 reclaimed), 1 failed (1×401)`. */
        fun summary(): String {
            val ok = byStatus.filterKeys { it.isSuccess() }
            val failed = byStatus.filterKeys { !it.isSuccess() }
            return buildString {
                append("$delivered delivered")
                if (ok.isNotEmpty()) append(" (${ok.render()})")
                val failedCount = attempted - delivered
                if (failedCount > 0) append(", $failedCount failed (${failed.render()})")
            }
        }

        private fun Map<Int?, Int>.render(): String =
            entries.sortedWith(compareBy(nullsLast()) { it.key }).joinToString(", ") { (status, n) ->
                "$n×" + when (status) {
                    null -> "no response"
                    202 -> "202 within grace"
                    200 -> "200 reclaimed"
                    else -> "$status"
                }
            }

        private companion object {
            fun Int?.isSuccess(): Boolean = this != null && this in 200..299
        }
    }

    /**
     * Mint and deliver every currently-closed account's record once.
     *
     * ⚠ One failure does not abandon the rest of the sweep: the accounts are independent, and an
     * unreachable relay would otherwise starve whichever owner happened to sort first.
     */
    suspend fun deliverOnce(nowMs: Long): Report {
        val records = minter.mintAll(nowMs)
        val byStatus = mutableMapOf<Int?, Int>()
        for (record in records) {
            val status = runCatching { post(record.token) }.getOrNull()
            byStatus[status] = (byStatus[status] ?: 0) + 1
        }
        return Report(attempted = records.size, byStatus = byStatus)
    }

    /**
     * Sweep forever, [intervalMs] apart. Cancellation-cooperative via [delay]; intended to run on a
     * background scope for the lifetime of the process.
     *
     * ⚠ The sweep interval is unrelated to the grace window. It only bounds *how late* a due
     * reclamation starts — the relay measures grace from the recorded closure time, so a slow sweep
     * can never shorten retention, only delay the deletion.
     */
    suspend fun runForever(
        intervalMs: Long,
        now: () -> Long = System::currentTimeMillis,
        onSweep: (Report) -> Unit = {},
    ): Nothing {
        while (true) {
            onSweep(deliverOnce(now()))
            delay(intervalMs)
        }
    }
}
