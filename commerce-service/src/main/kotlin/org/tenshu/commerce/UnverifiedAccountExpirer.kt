package org.tenshu.commerce

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.tenshu.commerce.store.CommerceStore

private val log = LoggerFactory.getLogger("UnverifiedAccountExpirer")

/**
 * Deletes accounts that are only an email-code request — never verified, created and last
 * requested more than [maxAgeMs] ago, nothing else attached — with their request records and codes.
 * The guards live in `CommerceStore.expireUnverifiedAccounts`; the D10 exception it relies on is
 * `commerce-accounts-and-sessions.md` §9.10. An account goes at the first sweep after it qualifies,
 * so up to one interval late.
 */
class UnverifiedAccountExpirer(
    private val store: CommerceStore,
    private val maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun sweepOnce(): Int {
        val cutoff = now() - maxAgeMs
        val count = store.expireUnverifiedAccounts(cutoff)
        if (count > 0) {
            log.info("Expired and deleted {} never-verified account(s)", count)
        }
        return count
    }

    suspend fun runForever(
        intervalMs: Long,
        onSweep: (Int) -> Unit = {},
    ): Nothing {
        while (true) {
            val expired = runCatching { sweepOnce() }
                .onFailure { log.error("Unverified account expiration sweep threw", it) }
                .getOrDefault(0)
            onSweep(expired)
            delay(intervalMs)
        }
    }
}
