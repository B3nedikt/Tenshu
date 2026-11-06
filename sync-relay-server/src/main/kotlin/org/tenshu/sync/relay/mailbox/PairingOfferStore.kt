package org.tenshu.sync.relay.mailbox

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [PairingOfferRegistry] — the default + test backend
 * (`sync-pairing-inverted-direction.md` §3.4). Single-use is enforced by
 * `putIfAbsent` on the hash: an entry, live or lapsed, rejects re-posts until
 * the retention sweep removes it. Durable sibling:
 * `org.tenshu.sync.relay.storage.SqlitePairingOfferStore`.
 */
class PairingOfferStore : PairingOfferRegistry {

    private class Entry(
        val response: PairingOfferRegistry.OfferResponse,
        val expiresAtMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Announces live in their own map, deliberately.
     *
     * ⚠⚠ In [entries] the **presence of a row IS the consumed marker** (`putIfAbsent`), so storing
     * an announce there would consume the hash and kill the very handshake it belongs to. Keeping
     * them apart leaves single-use (T-P3) exactly as it was.
     */
    private val announces = ConcurrentHashMap<String, Entry>()

    override fun post(
        pairingSecretHash: String,
        response: PairingOfferRegistry.OfferResponse,
        expiresAtMs: Long,
        nowMs: Long,
    ): PairingOfferRegistry.PostResult {
        val existing = entries.putIfAbsent(pairingSecretHash, Entry(response, expiresAtMs))
        return if (existing == null) {
            PairingOfferRegistry.PostResult.STORED
        } else {
            PairingOfferRegistry.PostResult.CONSUMED
        }
    }

    override fun postAnnounce(
        pairingSecretHash: String,
        response: PairingOfferRegistry.OfferResponse,
        expiresAtMs: Long,
        nowMs: Long,
    ): PairingOfferRegistry.AnnounceResult {
        // A terminal response settles the hash; an announce after it is meaningless and must not
        // displace the blob the offerer is polling for.
        if (entries.containsKey(pairingSecretHash)) return PairingOfferRegistry.AnnounceResult.CONSUMED
        val existing = announces.putIfAbsent(pairingSecretHash, Entry(response, expiresAtMs))
        return if (existing == null) {
            PairingOfferRegistry.AnnounceResult.STORED
        } else {
            PairingOfferRegistry.AnnounceResult.ALREADY_ANNOUNCED
        }
    }

    override fun fetch(pairingSecretHash: String, nowMs: Long): PairingOfferRegistry.Fetch {
        // Terminal first: the announce is served only until the decision replaces it (§2.1).
        val entry = entries[pairingSecretHash] ?: announces[pairingSecretHash]
            ?: return PairingOfferRegistry.Fetch.NotFound
        if (nowMs >= entry.expiresAtMs) return PairingOfferRegistry.Fetch.NotFound
        return PairingOfferRegistry.Fetch.Found(entry.response)
    }

    override fun pendingAdmittedAt(ownerId: String, deviceId: String, nowMs: Long) =
        entries.values
            .filter { nowMs < it.expiresAtMs }
            .mapNotNull { it.response.registration }
            .filter { it.ownerId == ownerId && it.deviceId == deviceId }
            .mapNotNull { it.admittedAt }
            .maxOrNull()

    override fun gcSweep(nowMs: Long, retentionMs: Long): Int {
        var dropped = 0
        for ((hash, entry) in entries) {
            if (nowMs >= entry.expiresAtMs + retentionMs && entries.remove(hash, entry)) dropped++
        }
        // ⚠ Announces are swept too, or a handshake abandoned after the scan leaks a row forever —
        // the same unbounded-growth shape the pairing-request mailbox had.
        for ((hash, entry) in announces) {
            if (nowMs >= entry.expiresAtMs + retentionMs && announces.remove(hash, entry)) dropped++
        }
        return dropped
    }

    /** Account closure (F12): see `SqlitePairingOfferStore.eraseOwner` for why the markers may go. */
    override fun eraseOwner(ownerId: String): Int {
        requireErasableOwner(ownerId)
        var dropped = 0
        for (map in listOf(entries, announces)) {
            for ((hash, entry) in map) {
                if (entry.response.ownerId == ownerId && map.remove(hash, entry)) dropped++
            }
        }
        return dropped
    }
}
