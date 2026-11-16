package org.tenshu.sync.proxy

import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.IdentityService
import org.tenshu.identity.MembershipRecord
import org.tenshu.sync.common.Ed25519SignatureVerifier
import org.tenshu.sync.common.MembershipCapability
import org.tenshu.sync.common.MembershipRecordVerifier
import org.tenshu.sync.common.MembershipRevocation

/**
 * This device's view of each shared realm's signed [MembershipRecord]s (`shared-realm-membership-records.md`
 * MR-1): who is a member, by `MembershipRecordVerifier`.
 *
 * ⚠ Read on demand over the relation, like `DeviceRecords`: a realm's whole life holds a handful of
 * records per member, and reading fresh means a record ingested a moment ago counts at once.
 * ⚠ The rows of every shared realm share one relation; each read filters by `realmId`.
 */
internal class MembershipRecords(
    private val executionContext: ExecutionContext,
    private val cryptoManager: SyncCryptoManager,
) {
    private val verify = Ed25519SignatureVerifier(cryptoManager::verifyEd25519)

    /** Record a capability the owner just signed — the same bytes the grant ships to the member. */
    fun saveCapability(capability: MembershipCapability, masterPublicKey: ByteArray) {
        save(
            realmId = capability.realmId,
            kind = MembershipRecord.Kind.CAPABILITY,
            authorityPub = capability.memberAuthorityPublicKey,
            epoch = capability.epoch,
            at = capability.issuedAt,
            masterPublicKey = masterPublicKey,
            signature = capability.signature,
        )
    }

    /**
     * Record a revocation the owner just signed — the same bytes it posted to `/sync/realm-member/revoke`. ⚠⚠ Twice:
     * first as the owner's personal copy, then as the realm's record. The personal copy is saved before the rotation
     * that follows, in the same personal stream, so no owner device can hold the new key without it
     * ([MembershipRecord.personalKeyOf]).
     */
    fun saveRevocation(revocation: MembershipRevocation, masterPublicKey: ByteArray) {
        save(
            realmId = revocation.realmId,
            kind = MembershipRecord.Kind.REVOCATION,
            authorityPub = revocation.memberAuthorityPublicKey,
            epoch = revocation.epoch,
            at = revocation.revokedAt,
            masterPublicKey = masterPublicKey,
            signature = revocation.signature,
            personal = true,
        )
        save(
            realmId = revocation.realmId,
            kind = MembershipRecord.Kind.REVOCATION,
            authorityPub = revocation.memberAuthorityPublicKey,
            epoch = revocation.epoch,
            at = revocation.revokedAt,
            masterPublicKey = masterPublicKey,
            signature = revocation.signature,
        )
    }

    /** [realmId]'s current members (owner excluded), `memberId` → current capability. */
    fun members(realmId: String): Map<String, MembershipCapability> =
        MembershipRecordVerifier.members(realmId, records(realmId).map { it.second }, verify)

    /**
     * Whether an incoming record row may be stored — MR-2, the R6b rule. ⚠⚠ Anyone holding the realm key can
     * push a row at any key: an unverifiable row is never stored, and a valid row already stored is never
     * overwritten — else a same-key row with a garbage signature would replace a revocation by
     * last-writer-wins and quietly bring the member back.
     */
    fun acceptsIncoming(incoming: MembershipRecord): Boolean {
        val realmId = incoming.realmId.get()
        val record = toRecord(incoming) ?: return false
        if (!MembershipRecordVerifier.isValid(realmId, record, verify)) return false
        val id = executionContext.getDataId(incoming) ?: return false
        val stored = (executionContext.database.getData(id) as? MembershipRecord)?.let(::toRecord) ?: return true
        return !MembershipRecordVerifier.isValid(realmId, stored, verify)
    }

    fun isRevoked(realmId: String, memberId: String): Boolean = memberId in revokedMembers(realmId)

    fun revokedMembers(realmId: String): Set<String> =
        MembershipRecordVerifier.revokedMembers(realmId, records(realmId).map { it.second }, verify)

    /**
     * Re-save every **valid** record of [realmId], so each goes out again as a fresh block sealed under the
     * realm's **current** key (§3.5): relay body GC drops a block once every device pulled it, and a member
     * granted after a `Kr` rotation holds only the new key. Returns how many went out.
     *
     * ⚠ Valid rows only — re-emitting an unverifiable row would spread whatever a realm-key holder wrote.
     */
    fun reEmit(realmId: String): Int {
        var reEmitted = 0
        for ((row, record) in records(realmId)) {
            if (!MembershipRecordVerifier.isValid(realmId, record, verify)) continue
            executionContext.saveData(row)
            reEmitted++
        }
        return reEmitted
    }

    private fun save(
        realmId: String,
        kind: String,
        authorityPub: ByteArray,
        epoch: Long,
        at: Long,
        masterPublicKey: ByteArray,
        signature: ByteArray,
        personal: Boolean = false,
    ) {
        executionContext.saveData(
            MembershipRecord.create(
                context = executionContext.context,
                realmId = realmId,
                kind = kind,
                memberId = MembershipRecordVerifier.memberIdOf(authorityPub),
                memberAuthorityPublicKey = authorityPub,
                epoch = epoch,
                at = at,
                signerPublicKey = masterPublicKey,
                signature = signature,
                personal = personal,
            ),
        )
    }

    private fun records(realmId: String): List<Pair<MembershipRecord, MembershipRecordVerifier.Record>> =
        executionContext.database
            .getArrayData(executionContext.relationReference(IdentityService.Relations.MEMBERSHIP_RECORDS))
            ?.toList().orEmpty()
            .mapNotNull { it as? MembershipRecord }
            .filter { it.realmId.get() == realmId }
            .mapNotNull { row -> toRecord(row)?.let { row to it } }

    private fun toRecord(row: MembershipRecord): MembershipRecordVerifier.Record? = MembershipRecordVerifier.fromRow(row)
}

/**
 * MR-2: when a **revocation** record arrives (own or ingested), the revoked member's `Member` row leaves the
 * store and the `MEMBERS` relation, so every reader of the member list agrees without being taught about
 * records. ⭐ This — not a tombstone — is how a removal reaches the owner's other devices and the remaining
 * members: shared realms apply no tombstones (`shared-realm-membership-records.md` §1).
 *
 * ⚠ Order-safe because a revocation is permanent: whichever of row and record arrives first, the row ends up
 * gone (the other half is `SyncProcessor`'s ingest guard, which drops a revoked member's row).
 */
internal class MembershipRecordObserver(
    private val executionContext: ExecutionContext,
    private val records: MembershipRecords,
) : org.tenshu.core.common.proxy.DataObserver {

    override fun onDataSaved(data: org.tenshu.core.Data, origin: org.tenshu.core.common.proxy.WriteOrigin) {
        val row = data as? MembershipRecord ?: return
        if (row.kind.get() != MembershipRecord.Kind.REVOCATION) return
        val realmId = row.realmId.get()
        val memberId = MembershipRecordVerifier.memberIdOf(row.memberAuthorityPublicKey.get())
        if (!records.isRevoked(realmId, memberId)) return // unverifiable: changes nothing
        val rowId = org.tenshu.core.entity.DataId.StringDataId.create(
            executionContext.context,
            org.tenshu.identity.Member.ID,
            org.tenshu.identity.Member.compositeKey(realmId, memberId),
        )
        executionContext.database.removeFromArray(
            reference = executionContext.relationReference(IdentityService.Relations.MEMBERS),
            dataId = rowId,
        )
        // ⚠ A local delete, as `DeviceRecordObserver`'s is: this replica's view, not a new emit.
        if (executionContext.database.getData(rowId) != null) {
            executionContext.deleteData(rowId, origin = org.tenshu.core.common.proxy.WriteOrigin.SYNC_INGEST)
        }
    }

    override fun onDataDeleted(dataId: org.tenshu.core.entity.DataId, origin: org.tenshu.core.common.proxy.WriteOrigin) = Unit
}
