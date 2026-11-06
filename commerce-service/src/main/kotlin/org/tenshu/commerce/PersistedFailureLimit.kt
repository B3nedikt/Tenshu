package org.tenshu.commerce

import org.tenshu.commerce.store.CommerceStore

/**
 * A per-address cap on **failed code checks** inside a rolling window, kept in the store so a
 * restart does not reset it (`commerce-accounts-and-sessions.md` D13 / D15, threat model T-CB-12 /
 * T-CB-13).
 *
 * Two instances exist: the review sign-in's (10 per 24 h, [ReviewSignIn]) and the ordinary one-time
 * code's (`OTP_FAILED_PER_EMAIL_PER_DAY`, default 20 per 24 h). Over the cap every attempt is
 * refused — **the right code included** — until the oldest counted failure leaves the window. A
 * correct code does not clear the count: it proves nothing about who made the wrong guesses, and
 * clearing would hand an attacker who learns the code mid-attack a fresh budget. Stamps age out.
 *
 * **Storage.** One `email_verification` row per address under `<keyPrefix><address>` (or its SHA-256), whose
 * `code_hash` column holds the last [maxFailures] failure timestamps, comma-separated, and whose
 * `expires_at` is when the newest leaves the window. ⚠⚠ The prefix ends in `:`, which no normalised
 * address can contain (`normaliseEmail`), so no request can read or overwrite another key space —
 * the same construction as `account-deletion:`. No schema change, no migration. A row whose stamps
 * have all aged out is deleted on the next read, so a quiet address leaves nothing behind; there is
 * no background sweep.
 *
 * ⚠ The read-modify-write is serialised in-process only. Commerce runs as one process; the worst a
 * race can do is lose one stamp.
 *
 * [maxFailures] `<= 0` disables the cap (the convention of the other budgets).
 */
class PersistedFailureLimit(
    private val store: CommerceStore,
    private val keyPrefix: String,
    val maxFailures: Int,
    val windowMs: Long = DAY_MS,
    /**
     * Key the row by SHA-256 of the address rather than the address. ⚠ On for the ordinary cap: it
     * counts every refused check — including for addresses no account uses, so that its 429 is not an
     * oracle — and those strangers' addresses must not sit in the table in the clear (the posture of
     * the deletion route's decoy key). A pseudonym, not anonymisation: a guessed address can be
     * hashed and looked up. Off for the review cap, whose addresses are our own.
     */
    private val hashAddress: Boolean = false,
) {
    init {
        require(keyPrefix.endsWith(":")) { "the key prefix must end in ':' so no address can reach it" }
    }

    fun key(normalisedEmail: String): String = keyPrefix + if (hashAddress) sha256Hex(normalisedEmail) else normalisedEmail

    /** Whether [normalisedEmail] is over the cap at [nowMs]. */
    fun isLocked(normalisedEmail: String, nowMs: Long): Boolean {
        if (maxFailures <= 0) return false
        synchronized(this) { return recent(normalisedEmail, nowMs).size >= maxFailures }
    }

    /** Count one failed check against [normalisedEmail]. */
    fun recordFailure(normalisedEmail: String, nowMs: Long) {
        if (maxFailures <= 0) return
        synchronized(this) {
            val updated = (recent(normalisedEmail, nowMs) + nowMs).takeLast(maxFailures)
            store.putEmailVerification(key(normalisedEmail), updated.joinToString(","), expiresAt = updated.last() + windowMs)
        }
    }

    private fun recent(email: String, nowMs: Long): List<Long> {
        val row = store.getEmailVerification(key(email)) ?: return emptyList()
        val stamps = row.codeHash.split(',').mapNotNull { it.toLongOrNull() }.filter { nowMs - it < windowMs }
        if (stamps.isEmpty()) store.deleteEmailVerification(key(email))
        return stamps
    }

    companion object {
        const val DAY_MS: Long = 24L * 60L * 60L * 1000L

        /** The ordinary one-time code's key space (D15), keyed `otp-failures:<sha256(address)>`. */
        const val OTP_FAILURES_PREFIX = "otp-failures:"

        /** The review sign-in's key space (D13). */
        const val REVIEW_FAILURES_PREFIX = "review-signin-failures:"
    }
}
