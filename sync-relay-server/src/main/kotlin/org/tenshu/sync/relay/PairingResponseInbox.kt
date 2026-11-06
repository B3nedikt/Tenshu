package org.tenshu.sync.relay

import org.tenshu.core.common.util.HlcStamp
import org.tenshu.sync.relay.mailbox.PairingOfferRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * The join flow's response mailbox — `POST /sync/pairing/response` fills it, the joiner peeks
 * (`POST /sync/pairing/response/peek`) and acks (`POST /sync/pairing/ack`) — held in memory for
 * both mailbox implementations, like [PairingRequestInbox].
 *
 * Keyed by the joiner's `pairingSecretHash` (F7, `relay-metadata-minimisation.md`): the relay is
 * never sent the pairing secret, so it cannot key anything by it.
 *
 * ⚠⚠ **Bounded in time the way the offer flow is** (`PairingOfferRegistry`):
 *  - a response is served and kept for [ttlMs] — [PAIRING_REQUEST_TTL_MS], the five-minute
 *    pairing window plus the offer flow's two-minute grace ([RelayConfig.PAIRING_OFFER_RESPONSE_TTL_MS]).
 *    Before this, a response the joiner never acked (it gave up, crashed past its budget, or
 *    never existed) held an encrypted master-key blob until the process restarted;
 *  - an **ack** removes the response and leaves a **consumed marker** for [consumedRetentionMs]
 *    ([PairingOfferRegistry.CONSUMED_RETENTION_MS], 24 h), so the same hash cannot be filled a
 *    second time. A host session mints a fresh secret per pairing, so a legitimate second fill of
 *    an acked hash does not exist.
 *
 *
 * ⚠⚠ **The joiner's relay registration rides the response and is applied on delivery.**
 * Registering the joiner's signing key when the host *posts* would let a joiner that never fetched
 * within [ttlMs] (it gave up, crashed, or the host approved a request whose device had gone)
 * leave a registered device whose key was never delivered — a permanent member of
 * `DeviceRegistry.deviceIds`, which never acks a tombstone, so the realm's tombstone GC quorum
 * (`MailboxStore.gcSweep`) could never be met again. So [post] stores a [PendingRegistration] with
 * the response, verified by the route, and [peek] hands it back with the bytes so
 * the route registers it **before** serving them: the joiner's first authenticated call (after it
 * has decrypted the key) finds its registration, and a response that expires undelivered leaves
 * nothing behind. A relay restart loses both together, which is the same consistent outcome.
 *
 * ⚠ The posting route is JWT-authenticated (a registered realm device), so the number of live
 * entries is bounded by what realm devices post; expiry, not a cap, is the bound that matters.
 * Sweeps run on the write path at most once per [PairingRequestInbox.SWEEP_INTERVAL_MS], for the
 * reason [PairingRequestInbox] gives.
 */
class PairingResponseInbox(
    private val ttlMs: Long = PAIRING_REQUEST_TTL_MS,
    private val consumedRetentionMs: Long = PairingOfferRegistry.CONSUMED_RETENTION_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    /** Outcome of [post]. */
    enum class PostResult { STORED, CONSUMED }

    /**
     * The `DeviceRegistry.register` call a verified `POST /sync/pairing/response` would have made,
     * deferred until the joiner fetches the response (class KDoc). [admittedAt] is opaque here.
     */
    class PendingRegistration(
        val ownerId: String,
        val deviceId: String,
        publicKey: ByteArray,
        val admittedAt: HlcStamp?,
        /**
         * The encoded A33 admission chain this admission was verified through (null when A1 signed
         * it directly). ⚠⚠ The whole chain is **re-verified at delivery**, against the removal
         * ledger AND the key-scoped revocation list: it was verified at post time, up to one TTL
         * earlier, and a revoker strips by key at once while the removal tombstone lands later (and
         * a Q3 subtree member is revoked without ever being removed). Device ids alone would miss
         * the revocation half (S1).
         */
        admissionCertChain: ByteArray? = null,
    ) {
        val publicKey: ByteArray = publicKey.copyOf()
        val admissionCertChain: ByteArray? = admissionCertChain?.copyOf()
    }

    /** What [peek] serves: the sealed response and the registration it carries. */
    class Delivery(val encryptedKey: ByteArray, val registration: PendingRegistration?)

    private class Live(
        val encryptedKey: ByteArray,
        val registration: PendingRegistration?,
        val storedAtMs: Long,
    )

    private val live = ConcurrentHashMap<String, Live>()
    private val consumedAtMs = ConcurrentHashMap<String, Long>()

    /** Serialises [post]'s consumed-check + insert with [ack]'s remove + retire. */
    private val transition = Any()

    @Volatile
    private var lastSweepMs: Long = 0L

    /**
     * Store (or replace — the host's self-heal retry re-posts the same response) the response
     * for [pairingSecretHash]. Refused once the hash has been acked.
     */
    fun post(
        pairingSecretHash: String,
        encryptedKey: ByteArray,
        registration: PendingRegistration? = null,
    ): PostResult {
        val now = nowMs()
        maybeSweep(now)
        // ⚠ Check-and-insert is one transition with [ack]'s remove-and-retire: otherwise a retry
        // that read "not consumed" could insert AFTER an overlapping ack retired the hash, and the
        // mailbox would report consumed while serving the refill for another TTL.
        synchronized(transition) {
            if (isConsumed(pairingSecretHash)) return PostResult.CONSUMED
            live[pairingSecretHash] = Live(encryptedKey, registration, now)
        }
        return PostResult.STORED
    }

    /**
     * Peek — never destructive; `null` when absent or past [ttlMs]. The caller applies
     * [Delivery.registration] before serving [Delivery.encryptedKey] (class KDoc).
     */
    fun peek(pairingSecretHash: String): Delivery? {
        val now = nowMs()
        val entry = live[pairingSecretHash] ?: return null
        if (now - entry.storedAtMs >= ttlMs) {
            live.remove(pairingSecretHash, entry)
            return null
        }
        return Delivery(entry.encryptedKey, entry.registration)
    }

    /** [peek]'s bytes alone — for tests of the TTL and consumed marker. */
    fun get(pairingSecretHash: String): ByteArray? = peek(pairingSecretHash)?.encryptedKey

    /**
     * The joiner consumed the response: drop it and retire the hash. Marks the hash consumed only
     * when a response was actually live, so an ack under a guessed hash cannot pre-empt a future
     * response (hashes are 256-bit, but the route is unauthenticated).
     */
    fun ack(pairingSecretHash: String) {
        val now = nowMs()
        synchronized(transition) {
            if (live.remove(pairingSecretHash) != null) consumedAtMs[pairingSecretHash] = now
        }
        maybeSweep(now)
    }

    /** `true` while [pairingSecretHash] carries a consumed marker inside its retention. */
    fun isConsumed(pairingSecretHash: String): Boolean {
        val consumed = consumedAtMs[pairingSecretHash] ?: return false
        return nowMs() - consumed < consumedRetentionMs
    }

    /** Drops expired responses and consumed markers past their retention. */
    fun sweep(now: Long = nowMs()) {
        live.entries.removeIf { now - it.value.storedAtMs >= ttlMs }
        consumedAtMs.entries.removeIf { now - it.value >= consumedRetentionMs }
    }

    /**
     * Account closure (F12): drop every live response whose queued registration names [ownerId],
     * and return how many. ⚠ Without this a peek inside the TTL after erasure would re-register
     * the joiner into the erased realm — the registration outlives `DeviceRegistry.eraseOwner`
     * because it is not in the registry yet. Consumed markers carry no owner and stay.
     */
    fun eraseOwner(ownerId: String): Int {
        var removed = 0
        synchronized(transition) {
            val it = live.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.registration?.ownerId == ownerId) {
                    it.remove()
                    removed++
                }
            }
        }
        return removed
    }

    /**
     * Newest `admittedAt` among live (unexpired) responses queuing a registration of [deviceId] into
     * [ownerId] — `DeviceRevocation` orders a `Device` tombstone against it (see there).
     */
    fun pendingAdmittedAt(ownerId: String, deviceId: String): HlcStamp? {
        val now = nowMs()
        return live.values
            .filter { now - it.storedAtMs < ttlMs }
            .mapNotNull { it.registration }
            .filter { it.ownerId == ownerId && it.deviceId == deviceId }
            .mapNotNull { it.admittedAt }
            .maxOrNull()
    }

    /** Live response count — for the bound's tests, not for logic. */
    fun liveCount(): Int = live.size

    /** Consumed-marker count — for the bound's tests, not for logic. */
    fun consumedCount(): Int = consumedAtMs.size

    private fun maybeSweep(now: Long) {
        if (now - lastSweepMs >= PairingRequestInbox.SWEEP_INTERVAL_MS) {
            lastSweepMs = now
            sweep(now)
        }
    }
}
