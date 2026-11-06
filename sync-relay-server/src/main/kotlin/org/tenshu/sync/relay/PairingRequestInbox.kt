package org.tenshu.sync.relay

import java.util.concurrent.ConcurrentHashMap

/**
 * How long a pending pairing request is kept and served. `sync-relay-api.md` §3.3 specifies a
 * five-minute pairing window; the two minutes on top are the same grace the offer-flow stores
 * give a poll that started just inside it ([RelayConfig.PAIRING_OFFER_RESPONSE_TTL_MS]).
 */
const val PAIRING_REQUEST_TTL_MS: Long = 7L * 60L * 1_000L

/**
 * The pending requests of `POST /sync/pairing/request`, held in memory for both mailbox
 * implementations — one class, because the bound below was once written twice and wrong twice.
 *
 * ⚠ F7: an entry holds the joiner's `pairingSecretHash` and a **sealed** device name — never the
 * secret, never the name in clear (the relay is sent neither). The responses live next door in
 * [PairingResponseInbox], under the same TTL.
 *
 * ⚠⚠ **Three bounds, and each closes a different hole.** The route is credential-less by
 * necessity (an unpaired joiner holds nothing), so everything stored here was put there by
 * anyone who cared to:
 *  - **per owner** — [MAX_PENDING_PAIRING_REQUESTS_PER_OWNER], oldest evicted first;
 *  - **in time** — an entry is neither served nor kept past [ttlMs]. Before this, a request whose
 *    secret no host ever answered stayed until the process restarted;
 *  - **in owners** — an owner with nothing pending holds no map entry. The per-owner cap alone
 *    bounded each list and left the NUMBER of lists to the caller: a fresh `ownerId` per request
 *    grew the map without limit (found 2026-10-13). The route now also refuses an owner the relay
 *    has never registered a device for, which is what keeps this map no larger than the realm set.
 *
 * ⚠ Expiry runs on the write path ([add] sweeps at most once per [SWEEP_INTERVAL_MS]) rather than
 * on a timer: memory here grows only when something is added, so that is the only moment a sweep
 * is owed, and it needs no job to forget to start.
 */
class PairingRequestInbox(
    private val ttlMs: Long = PAIRING_REQUEST_TTL_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private class Pending(val request: SyncMailbox.PairingRequestData, val receivedAtMs: Long)

    private val byOwner = ConcurrentHashMap<String, MutableList<Pending>>()

    @Volatile
    private var lastSweepMs: Long = 0L

    fun add(ownerId: String, request: SyncMailbox.PairingRequestData) {
        val now = nowMs()
        // `compute`, not `computeIfAbsent` + a lock on the list: [sweep] removes an owner whose
        // list emptied, and an add racing that removal must not append to a list no longer in
        // the map — the request would be accepted and never served.
        byOwner.compute(ownerId) { _, existing ->
            val requests = existing ?: mutableListOf()
            requests.removeAll { now - it.receivedAtMs >= ttlMs }
            requests.add(Pending(request, now))
            while (requests.size > MAX_PENDING_PAIRING_REQUESTS_PER_OWNER) requests.removeAt(0)
            requests
        }
        if (now - lastSweepMs >= SWEEP_INTERVAL_MS) {
            lastSweepMs = now
            sweep(now)
        }
    }

    fun get(ownerId: String): List<SyncMailbox.PairingRequestData> {
        val now = nowMs()
        var live: List<SyncMailbox.PairingRequestData> = emptyList()
        byOwner.computeIfPresent(ownerId) { _, requests ->
            requests.removeAll { now - it.receivedAtMs >= ttlMs }
            live = requests.map { it.request }
            requests.takeIf { it.isNotEmpty() }
        }
        return live
    }

    fun remove(ownerId: String, pairingSecretHash: String) {
        byOwner.computeIfPresent(ownerId) { _, requests ->
            requests.removeAll { it.request.pairingSecretHash == pairingSecretHash }
            requests.takeIf { it.isNotEmpty() }
        }
    }

    /** Drops every expired request, and every owner left with none. */
    fun sweep(now: Long = nowMs()) {
        for (ownerId in byOwner.keys) {
            byOwner.computeIfPresent(ownerId) { _, requests ->
                requests.removeAll { now - it.receivedAtMs >= ttlMs }
                requests.takeIf { it.isNotEmpty() }
            }
        }
    }

    /** Account closure (F12): every pending request addressed to [ownerId]. Returns how many. */
    fun eraseOwner(ownerId: String): Int = byOwner.remove(ownerId)?.size ?: 0

    /** Owners with at least one map entry — for the bound's tests, not for logic. */
    fun ownerCount(): Int = byOwner.size

    companion object {
        const val SWEEP_INTERVAL_MS: Long = 60L * 1_000L
    }
}
