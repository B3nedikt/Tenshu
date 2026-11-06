package org.tenshu.sync.relay.mailbox

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [AdmissionRevocationRegistry] — the default + test backend
 * (`delegated-device-admission-authority.md` §5). Durable sibling:
 * `org.tenshu.sync.relay.storage.SqliteAdmissionRevocationStore`.
 */
class AdmissionRevocationStore : AdmissionRevocationRegistry, OwnerScopedStore {

    /** `ownerId → revocation list` (append-only). */
    private val revocations =
        ConcurrentHashMap<String, MutableList<AdmissionRevocationRegistry.RevocationRecord>>()

    override fun appendRevocation(record: AdmissionRevocationRegistry.RevocationRecord) {
        revocations.compute(record.ownerId) { _, current ->
            (current ?: mutableListOf()).apply { add(record) }
        }
    }

    override fun revocations(ownerId: String): List<AdmissionRevocationRegistry.RevocationRecord> =
        revocations[ownerId]?.toList() ?: emptyList()

    override fun recordCount(): Int = revocations.values.sumOf { it.size }

    override fun eraseOwner(ownerId: String): Int {
        val list = revocations.remove(ownerId)
        return list?.size ?: 0
    }
}
