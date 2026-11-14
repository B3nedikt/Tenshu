package org.tenshu.sync.relay.mailbox

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [MembershipRegistry] — the default + test backend
 * (`shared-realm-membership-grant.md` §4). Durable sibling:
 * `org.tenshu.sync.relay.storage.SqliteMembershipStore`.
 *
 * Both maps key the member authority by its base64 form so a `ByteArray` works
 * as a composite key without an `equals`/`hashCode` wrapper.
 */
class MembershipStore : MembershipRegistry {

    /** `realmId → revocation list` (append-only). */
    private val revocations = ConcurrentHashMap<String, MutableList<MembershipRegistry.RevocationRecord>>()

    /** `realmId|base64(memberAuthorityPub) → admitted device ids`. */
    private val admissions = ConcurrentHashMap<String, MutableSet<String>>()

    private fun admissionKey(realmId: String, memberAuthorityPublicKey: ByteArray): String =
        realmId + "|" + Base64.getEncoder().encodeToString(memberAuthorityPublicKey)

    override fun recordAdmission(realmId: String, memberAuthorityPublicKey: ByteArray, deviceId: String) {
        admissions.compute(admissionKey(realmId, memberAuthorityPublicKey)) { _, current ->
            (current ?: mutableSetOf()).apply { add(deviceId) }
        }
    }

    override fun admittedDevices(realmId: String, memberAuthorityPublicKey: ByteArray): Set<String> =
        admissions[admissionKey(realmId, memberAuthorityPublicKey)]?.toSet() ?: emptySet()

    override fun isMemberDevice(realmId: String, deviceId: String): Boolean {
        val prefix = "$realmId|"
        return admissions.any { (key, devices) -> key.startsWith(prefix) && deviceId in devices }
    }

    override fun clearAdmissions(realmId: String, memberAuthorityPublicKey: ByteArray) {
        admissions.remove(admissionKey(realmId, memberAuthorityPublicKey))
    }

    override fun appendRevocation(record: MembershipRegistry.RevocationRecord) {
        revocations.compute(record.realmId) { _, current ->
            (current ?: mutableListOf()).apply { add(record) }
        }
    }

    override fun revocations(realmId: String): List<MembershipRegistry.RevocationRecord> =
        revocations[realmId]?.toList() ?: emptyList()

    override fun recordCount(): Int = revocations.values.sumOf { it.size }

    /**
     * Account closure (F12): admissions into the realm [ownerId] and its member [revocations], as
     * `SqliteMembershipStore.eraseOwner` does (the `erased_realm` tombstone stops the realm returning,
     * see `AccountErasure`). Counts device ids and revocation records, matching the SQLite twin's rows.
     */
    override fun eraseOwner(ownerId: String): Int {
        requireErasableOwner(ownerId)
        val prefix = "$ownerId|"
        val admitted = admissions.keys.filter { it.startsWith(prefix) }.sumOf { admissions.remove(it)?.size ?: 0 }
        return admitted + (revocations.remove(ownerId)?.size ?: 0)
    }
}
