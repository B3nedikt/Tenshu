package org.tenshu.sync.relay.mailbox

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Outstanding recovery challenges. The recovery flow is a two-step challenge-response
 * (sync-relay-api.md §3.4) to prevent signature replay:
 *
 *   1. Recovering device calls `GET /sync/recovery-challenge` → relay returns fresh random
 *      bytes and remembers them with a TTL.
 *   2. Device signs the challenge with the master identity (derived from the recovery
 *      code) and POSTs `/sync/recovery-claim`. The relay verifies the signature and
 *      **consumes** the challenge so it can't be re-used.
 *
 * ⚠⚠ **Not keyed by owner** (`relay-metadata-minimisation.md` F8). It used to be issued per
 * `ownerId`, and only for a realm that existed — an existence oracle for anyone holding an
 * `ownerId`, which every pairing QR carries. A challenge is 32 random single-use bytes whose
 * only job is replay prevention; the claim's master signature binds it to the new device key,
 * and the realm is chosen by the master public key the claim carries. So a challenge belongs
 * to nobody, is issued to anyone, and says nothing about any realm.
 *
 * Because issue is now unconditional, the outstanding set is **bounded** ([maxOutstanding]):
 * expired entries are pruned first, and a full store refuses ([issue] returns null → 503)
 * rather than growing without limit.
 *
 * In-memory in every deployment: a restart drops outstanding challenges, which costs a
 * recovering device one retry.
 */
class RecoveryChallengeStore(
    private val challengeBytes: Int = 32,
    private val ttlMillis: Long = 5 * 60 * 1000L, // 5 minutes per spec
    private val maxOutstanding: Int = DEFAULT_MAX_OUTSTANDING,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() }
) {

    private val random = SecureRandom()
    private val store = ConcurrentHashMap<List<Byte>, Long>() // challenge → expiresAt epoch ms

    /**
     * Issue a fresh challenge. Returns the raw bytes, remembered with [ttlMillis] TTL, or
     * null when [maxOutstanding] unexpired challenges are already outstanding.
     */
    @Synchronized
    fun issue(): ByteArray? {
        if (store.size >= maxOutstanding) {
            val now = nowEpochMillis()
            store.entries.removeIf { it.value < now }
            if (store.size >= maxOutstanding) return null
        }
        val challenge = ByteArray(challengeBytes)
        random.nextBytes(challenge)
        store[challenge.toList()] = nowEpochMillis() + ttlMillis
        return challenge
    }

    /**
     * Consume a challenge if and only if it's outstanding and not yet expired. Returns
     * true on consumption (challenge was valid, now removed); false if unknown or expired.
     * Constant-time matching isn't strictly required here — the challenge is per-request
     * random and the attacker doesn't gain anything from learning matching took longer.
     */
    fun consume(challenge: ByteArray): Boolean {
        val expiresAt = store.remove(challenge.toList()) ?: return false
        return nowEpochMillis() <= expiresAt
    }

    /** Test/maintenance helper. */
    fun clear() {
        store.clear()
    }

    fun size(): Int = store.size

    companion object {
        /** ~1.3 MB of map at 32-byte challenges; far above any honest recovery rate in a 5-minute TTL. */
        const val DEFAULT_MAX_OUTSTANDING: Int = 10_000
    }
}
