package org.tenshu.sync.relay.mailbox

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-owner realm registry. Populated by `POST /sync/realm` (first device,
 * master-self-signed bootstrap) and read by JWT verification (the master public key is
 * the trust anchor for verifying master-delegated device admissions in pairing /
 * recovery).
 *
 * In-memory [RealmRegistry] backend — the default + test backend. The durable backend is
 * `org.tenshu.sync.relay.storage.SqliteRealmStore`
 * (`docs/proposals/sync-relay-persistence.md`). [RealmRecord] stays nested here so its
 * many `RealmStore.RealmRecord` call sites are untouched; both backends share it.
 */
class RealmStore : RealmRegistry {

    private val records = ConcurrentHashMap<String, RealmRecord>()
    private val erasedRealms = ConcurrentHashMap<String, Long>()

    data class RealmRecord(
        val ownerId: String,
        val masterPublicKey: ByteArray,
        val createdAt: Long
    ) {
        // ByteArray equality must use contentEquals; data-class default uses reference.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RealmRecord) return false
            return ownerId == other.ownerId &&
                    masterPublicKey.contentEquals(other.masterPublicKey) &&
                    createdAt == other.createdAt
        }

        override fun hashCode(): Int {
            var result = ownerId.hashCode()
            result = 31 * result + masterPublicKey.contentHashCode()
            result = 31 * result + createdAt.hashCode()
            return result
        }
    }

    /**
     * Returns true if a new realm record was inserted; false if [ownerId] already exists
     * (the relay returns 409 in that case). Inserts are atomic — `POST /sync/realm` from
     * two clients racing for the same `ownerId` produces exactly one winner.
     */
    override fun createIfAbsent(record: RealmRecord): Boolean {
        return records.putIfAbsent(record.ownerId, record) == null
    }

    override fun get(ownerId: String): RealmRecord? = records[ownerId]

    override fun isErased(ownerId: String): Boolean = erasedRealms.containsKey(ownerId)

    /** Account closure (F12). */
    override fun eraseOwner(ownerId: String): Int {
        requireErasableOwner(ownerId)
        erasedRealms[ownerId] = System.currentTimeMillis()
        return if (records.remove(ownerId) != null) 1 else 0
    }

    /** Test/maintenance helper. */
    fun clear() {
        records.clear()
    }

    fun size(): Int = records.size
}
