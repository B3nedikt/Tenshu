package org.tenshu.commerce

import org.tenshu.commerce.store.CommerceStore
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * **Store-review sign-in** (`commerce-accounts-and-sessions.md` D13, threat model T-CB-12): each
 * review account — an **unpublished** address such as `google-review-<suffix>@tenshu.app`, one per
 * store, configured only in `deploy.env` (`REVIEW_SIGNIN_ACCOUNTS`) — accepts **its own** fixed
 * 6-digit code in place of a mailed one, for sign-in and for the deletion confirm. Always on while
 * configured: Google re-reviews live apps and expects the App access credentials to keep working.
 *
 * The rules this class is the single home of:
 * - ⚠⚠ **Exact membership** of the already-normalised address ([covers]). No wildcard, no domain or
 *   suffix match; a `+tag`, a trailing dot or a longer domain is a different string.
 * - ⚠⚠ **Per-account codes.** Each address is checked against its own digest only, so one store's
 *   code never opens the other store's account.
 * - ⚠⚠ **Keyed digests only.** The box holds hex HMAC-SHA256 of each code under
 *   `REVIEW_SIGNIN_HMAC_KEY`, never a code. A plain SHA-256 of a 6-digit code is reversed by trying
 *   all 10⁶ — instantly — so a leaked digest alone would be a leaked code; under a key it is not.
 *   Compared constant-time ([MessageDigest.isEqual]).
 * - ⚠⚠ **A persisted per-address lockout** ([PersistedFailureLimit]): [MAX_FAILURES] wrong codes in a
 *   rolling 24 hours refuse every attempt on that address, the right code included, until the oldest
 *   failure ages out. With 10⁶ codes and 10 guesses a day per account that is ≈ 274 years to try
 *   them all and ≈ 0.37 % per account per year — before counting the unpublished address, which an
 *   attacker must find first.
 * - ⚠ **Rotation ends sessions** ([endSessionsOnRotation]): a fingerprint of each account's digest is
 *   kept in the store; at boot, an account whose digest changed — or that is no longer configured —
 *   loses every session, so a rotated or withdrawn code does not stay usable through a session it
 *   minted.
 *
 * The callers own the other half: a listed address never gets a mailed code and never accepts one;
 * every other address never reaches [check].
 *
 * ⚠ Nothing here — a code, the key, a digest, a candidate, an address — may reach a log line.
 */
class ReviewSignIn(
    accounts: Map<String, String>,
    hmacKey: String,
    private val store: CommerceStore,
) {
    private val key: ByteArray = hmacKey.toByteArray(Charsets.UTF_8)
    private val expectedMacs: Map<String, ByteArray> = accounts.mapValues { (_, hex) -> decodeHex(hex) }
    private val digestHex: Map<String, String> = accounts.mapValues { (_, hex) -> hex.lowercase() }
    private val failures = PersistedFailureLimit(store, PersistedFailureLimit.REVIEW_FAILURES_PREFIX, MAX_FAILURES)

    init {
        require(expectedMacs.isNotEmpty()) { "review sign-in needs at least one account" }
        require(expectedMacs.values.all { it.size == SHA256_BYTES }) { "each review digest must be 32 bytes of hex" }
        require(key.size >= MIN_KEY_LENGTH) { "review sign-in key too short" }
    }

    /** How many review accounts are configured — for the boot line; never the addresses. */
    val accountCount: Int get() = expectedMacs.size

    enum class Outcome { Accepted, Refused, LockedOut }

    /** Whether [normalisedEmail] is, exactly, one of the configured review addresses. */
    fun covers(normalisedEmail: String): Boolean = normalisedEmail in expectedMacs

    /**
     * Check [candidate] against [normalisedEmail]'s own code. An unlisted address is always
     * [Outcome.Refused] — the callers never route one here, and this is the second wall.
     */
    fun check(normalisedEmail: String, candidate: String, nowMs: Long): Outcome {
        val expected = expectedMacs[normalisedEmail] ?: return Outcome.Refused
        if (failures.isLocked(normalisedEmail, nowMs)) return Outcome.LockedOut
        if (MessageDigest.isEqual(mac(candidate.trim()), expected)) return Outcome.Accepted
        failures.recordFailure(normalisedEmail, nowMs)
        return Outcome.Refused
    }

    /**
     * Call once at boot. Compares each account's digest fingerprint with the one recorded at the
     * previous boot and deletes the sessions of every account whose code changed or that was
     * removed from the config; then records the current set. Returns how many accounts were reset.
     *
     * ⚠ The fingerprint is an HMAC under the same key, so the stored row reveals neither a digest
     * nor whether two deployments share a code. A key rotation changes every fingerprint and so
     * resets every review account — which is what a key rotation should do.
     */
    fun endSessionsOnRotation(): Int = endReviewSessionsOnRotation(store, digestHex.mapValues { (address, hex) -> fingerprint(address, hex) })

    private fun fingerprint(address: String, digestHex: String): String =
        hmacSha256Hex(String(key, Charsets.UTF_8), "review-signin-fingerprint|$address|$digestHex")

    private fun mac(value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    companion object {
        /** Wrong codes per account per rolling 24 h before it locks. */
        const val MAX_FAILURES = 10

        /** The lockout window — a rolling 24 hours. */
        const val LOCKOUT_WINDOW_MS: Long = PersistedFailureLimit.DAY_MS

        /** `REVIEW_SIGNIN_HMAC_KEY` floor, in characters. `openssl rand -hex 32` gives 64. */
        const val MIN_KEY_LENGTH = 32

        private const val SHA256_BYTES = 32

        /**
         * The single row holding last boot's fingerprints, as `address=fingerprint` entries joined by
         * `,` (no normalised address contains `,`; split on the **last** `=`, since a local part may
         * contain one). ⚠ Ends in `:`, so no address reaches it.
         */
        const val FINGERPRINTS_KEY = "review-signin-fingerprints:"

        /**
         * The boot-time comparison, also run with an empty map when review sign-in is **off**, so
         * withdrawing the config ends the sessions of the accounts it used to cover.
         */
        fun endReviewSessionsOnRotation(store: CommerceStore, current: Map<String, String>): Int {
            val previous = store.getEmailVerification(FINGERPRINTS_KEY)?.codeHash.orEmpty()
                .split(',').filter { '=' in it }
                .associate { it.substringBeforeLast('=') to it.substringAfterLast('=') }
            var reset = 0
            for ((address, oldPrint) in previous) {
                if (current[address] == oldPrint) continue
                val account = store.findAccountByEmail(address)?.takeIf { it.erasedAt == null } ?: continue
                store.deleteSessionsForAccount(account.id)
                reset++
            }
            // A newly added account has no recorded print: end any session it holds too, since none
            // of them was minted under the code configured now.
            for (address in current.keys - previous.keys) {
                val account = store.findAccountByEmail(address)?.takeIf { it.erasedAt == null } ?: continue
                store.deleteSessionsForAccount(account.id)
                reset++
            }
            if (current.isEmpty()) {
                store.deleteEmailVerification(FINGERPRINTS_KEY)
            } else {
                store.putEmailVerification(
                    FINGERPRINTS_KEY,
                    current.entries.joinToString(",") { "${it.key}=${it.value}" },
                    expiresAt = Long.MAX_VALUE,
                )
            }
            return reset
        }

        /** True for exactly 64 hex characters (either case) — the shape `openssl dgst` prints. */
        fun isSha256Hex(value: String): Boolean = value.length == SHA256_BYTES * 2 && value.all { it in HEX }

        private const val HEX = "0123456789abcdefABCDEF"

        private fun decodeHex(value: String): ByteArray {
            if (!isSha256Hex(value)) return ByteArray(0)
            return ByteArray(SHA256_BYTES) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }

        /** Hex HMAC-SHA256 of [code] under [key] — one `REVIEW_SIGNIN_ACCOUNTS` digest. For tests and tooling. */
        fun hmacHex(key: String, code: String): String = hmacSha256Hex(key, code.trim())
    }
}
