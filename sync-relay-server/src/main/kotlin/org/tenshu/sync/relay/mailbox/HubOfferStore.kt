package org.tenshu.sync.relay.mailbox

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [HubOfferRegistry] — the default (and, for the H2 prototype, only) backend.
 * Last-post-wins per hash (re-arming replaces), remove-on-fetch (single consume).
 */
class HubOfferStore : HubOfferRegistry {

    private class Entry(
        val offer: HubOfferRegistry.HubOffer,
        val expiresAtMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    override fun post(
        pairingSecretHash: String,
        offer: HubOfferRegistry.HubOffer,
        expiresAtMs: Long,
        nowMs: Long,
    ) {
        entries[pairingSecretHash] = Entry(offer, expiresAtMs)
    }

    override fun consume(pairingSecretHash: String, nowMs: Long): HubOfferRegistry.Fetch {
        val entry = entries.remove(pairingSecretHash) ?: return HubOfferRegistry.Fetch.NotFound
        if (nowMs >= entry.expiresAtMs) return HubOfferRegistry.Fetch.NotFound
        return HubOfferRegistry.Fetch.Found(entry.offer)
    }

    override fun gcSweep(nowMs: Long): Int {
        var dropped = 0
        for ((hash, entry) in entries) {
            if (nowMs >= entry.expiresAtMs && entries.remove(hash, entry)) dropped++
        }
        return dropped
    }
}
