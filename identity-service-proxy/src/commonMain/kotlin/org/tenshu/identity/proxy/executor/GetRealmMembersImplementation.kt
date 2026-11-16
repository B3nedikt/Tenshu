package org.tenshu.identity.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.identity.GetRealmMembers
import org.tenshu.identity.IdentityService
import org.tenshu.identity.Member
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.identity.MembershipRecord
import org.tenshu.identity.OwnedRealmMaster
import org.tenshu.sync.common.Ed25519SignatureVerifier
import org.tenshu.sync.common.MembershipRecordVerifier
import org.tenshu.sync.common.MemberRowAttestation

/**
 * GR-3 `getRealmMembers(realmId)`: returns the in-realm [Member] roster for one shared
 * realm — the [IdentityService.Relations.MEMBERS] array (derived by
 * `MemberRelationMaintainer`) filtered to the requested `realmId`. Mirrors
 * `GetSharedRealmMembershipsImplementation`.
 */
internal class GetRealmMembersImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val realmId = (params?.get(GetRealmMembers.Params.REALM_ID) as? TString)?.get()
            ?: error("GetRealmMembers requires realmId")
        // Derived from the relation's declared spec — reader and writer can no
        // longer disagree about `isEntity` / `elementSchemaId` (§9.2 / D4).
        val membersRef = executionContext.relationReference(IdentityService.Relations.MEMBERS)
        val members = executionContext.getArrayData(membersRef)?.toList().orEmpty()
            .filter { (it as? Member)?.realmId?.get() == realmId }
        val vouched = vouchedMemberIds(executionContext, realmId)
        // MR-3: and the row itself must be the owner's — signed by the realm master (`MemberRowAttestation`),
        // so a member can neither rename another nor plant a row for someone granted.
        val verify = Ed25519SignatureVerifier(executionContext.context.syncCryptoManager()::verifyEd25519)
        return SimpleTList(
            members.filter { (it as Member).memberId.get() in vouched && MemberRowAttestation.isValid(it, verify) },
        ) as T
    }

    /**
     * MR-2 (`shared-realm-membership-records.md` §3.4): the member ids the realm's **signed** records vouch
     * for — every granted, unrevoked member, plus the owner. A `Member` row is an unsigned block anyone with
     * the realm key can write, so a row outside this set (never granted, or revoked) is not listed.
     *
     * ⚠ Filtered here, at read time, rather than dropped at ingest: a row can sync before its member's
     * capability record does, and dropping it then would lose it for good. Revoked rows are also dropped at
     * ingest (`SyncProcessor`), which is order-safe because a revocation is permanent.
     *
     * The owner is proven by any valid record's signer (records are master-signed), or — before this realm
     * has any record — by this device holding the realm master itself.
     */
    private fun vouchedMemberIds(executionContext: ExecutionContext, realmId: String): Set<String> {
        val verify = Ed25519SignatureVerifier(executionContext.context.syncCryptoManager()::verifyEd25519)
        val records = executionContext
            .getArrayData(executionContext.relationReference(IdentityService.Relations.MEMBERSHIP_RECORDS))
            ?.toList().orEmpty()
            .mapNotNull { it as? MembershipRecord }
            .filter { it.realmId.get() == realmId }
            .mapNotNull { MembershipRecordVerifier.fromRow(it) }
        val owner = MembershipRecordVerifier.ownerMemberId(realmId, records, verify)
            ?: heldMasterMemberId(executionContext, realmId)
        return MembershipRecordVerifier.members(realmId, records, verify).keys + listOfNotNull(owner)
    }

    private fun heldMasterMemberId(executionContext: ExecutionContext, realmId: String): String? {
        val owned = executionContext.database.getData(
            DataId.StringDataId.create(executionContext.context, OwnedRealmMaster.ID, realmId),
        ) as? OwnedRealmMaster ?: return null
        val masterPub = owned.masterPublicKey.get()
        // Only a master that really is this realm's — the row is synced, so check the binding.
        if (ownerIdForMasterPublicKey(masterPub) != realmId) return null
        return MembershipRecordVerifier.memberIdOf(masterPub)
    }
}
