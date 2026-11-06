package org.tenshu.sync.relay.mailbox

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [GrantRegistry] — the default + test backend
 * (`shared-realm-membership-grant.md` §4). Mutations go through
 * `ConcurrentHashMap.compute`, so single-pickup-delete is atomic per address.
 * Durable sibling: `org.tenshu.sync.relay.storage.SqliteGrantStore`.
 */
class GrantStore : GrantRegistry {

    /** One grant mailbox slot. Immutable — a re-grant replaces the record. */
    data class GrantRecord(
        val address: String,
        val ownerId: String,
        val sealedGrant: ByteArray,
        val createdAtMs: Long,
        val expiresAtMs: Long,
    )

    private val records = ConcurrentHashMap<String, GrantRecord>()

    override fun put(
        address: String,
        ownerId: String,
        sealedGrant: ByteArray,
        expiresAtMs: Long,
        nowMs: Long,
        maxOpenPerOwner: Int,
    ): GrantRegistry.PutResult {
        // Cap is administrative anti-griefing, not an invariant — the relaxed
        // count-then-put matches the SQLite backend under its single-writer
        // lock. A re-grant to an existing address never consumes a new slot.
        if (!records.containsKey(address)) {
            val openCount = records.values.count { it.ownerId == ownerId && nowMs < it.expiresAtMs }
            if (openCount >= maxOpenPerOwner) return GrantRegistry.PutResult.CAP_EXCEEDED
        }
        records[address] = GrantRecord(
            address = address,
            ownerId = ownerId,
            sealedGrant = sealedGrant,
            createdAtMs = nowMs,
            expiresAtMs = expiresAtMs,
        )
        return GrantRegistry.PutResult.STORED
    }

    override fun pickup(address: String, nowMs: Long): GrantRegistry.Pickup {
        var result: GrantRegistry.Pickup = GrantRegistry.Pickup.Empty
        records.compute(address) { _, current ->
            when {
                current == null -> null
                nowMs >= current.expiresAtMs -> null // expired — drop, report empty
                else -> {
                    result = GrantRegistry.Pickup.Found(current.sealedGrant)
                    null // single-pickup: delete on read
                }
            }
        }
        return result
    }

    override fun gcSweep(nowMs: Long): Int {
        var dropped = 0
        for (record in records.values) {
            if (nowMs >= record.expiresAtMs && records.remove(record.address, record)) {
                dropped++
            }
        }
        return dropped
    }

    override fun recordCount(): Int = records.size

    /** Account closure (F12): unpicked grants this owner dropped. */
    override fun eraseOwner(ownerId: String): Int {
        requireErasableOwner(ownerId)
        return records.values.filter { it.ownerId == ownerId }.count { records.remove(it.address, it) }
    }

    /** Read a record without consuming it (test/diagnostics hook). */
    fun peek(address: String): GrantRecord? = records[address]
}
