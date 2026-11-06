package app.tenshu.drive.data

import app.tenshu.error.ErrorCopyKey
import app.tenshu.error.errorCopyKey

import org.tenshu.core.GenericError
import org.tenshu.core.TError
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.entity.get
import org.tenshu.core.fold
import org.tenshu.identity.Member
import org.tenshu.identity.client.identityService
import org.tenshu.identity.contactFingerprint
import org.tenshu.identity.displayName
import org.tenshu.identity.getRealmMembers
import org.tenshu.identity.memberId
import org.tenshu.identity.role
import org.tenshu.sync.acceptSharedRealmGrant
import org.tenshu.sync.client.syncService
import org.tenshu.sync.declinePendingGrant
import org.tenshu.sync.grantSharedRealmMembership
import org.tenshu.sync.pollPendingGrants
import org.tenshu.sync.removeSharedRealmMember

/**
 * Drive's surface over the GR-3 shared-realm membership ops
 * (`docs/proposals/shared-realm-membership-grant.md` §11). The cross-user
 * *grant / accept / roster* actions sit at the seam between
 * [ContactsRepository] (who the contact is) and [FilesRepository] (which folder
 * backs the realm), so they get their own thin repository rather than bloating
 * either. Wraps `SyncService` (grant/accept) + `IdentityService.getRealmMembers`.
 *
 * GR-6 made owner grant/remove durable across sessions (the realm master key is
 * persisted, no longer same-session in-memory only) and adds *remove* + the inbound
 * *poll / decline* of quarantined pending grants.
 */
interface MembershipRepository {

    /**
     * Grant connected contact [personId] membership of shared realm [realmId]
     * (`grantSharedRealmMembership`). [label] is advisory (the folder name).
     * The op returns the minted A26 public key (needed for removal); GR-4 has no
     * removal flow, so it is discarded here — GR-5 stashes it durably.
     */
    suspend fun grant(realmId: String, personId: String, label: String): RepositoryResult<Unit>

    /**
     * Pick up + accept a grant waiting from [grantorFingerprint]
     * (`acceptSharedRealmGrant`, the GR-3 single-op pickup+accept). Returns the
     * joined realm id on success; a failure carries the relay/derivation error
     * (e.g. no grant blob at the derived address — "nothing shared yet").
     */
    suspend fun accept(grantorFingerprint: String): RepositoryResult<String>

    /**
     * The in-realm member roster of [realmId] (`getRealmMembers`, the Q25 Member
     * records — GR-3 §9.2). Backs the *Manage access* Members section.
     */
    suspend fun members(realmId: String): RepositoryResult<List<MemberModel>>

    /**
     * Remove member [memberId] from shared realm [realmId] (`removeSharedRealmMember`,
     * GR-6): revokes the member at the relay (purges their devices) and tombstones
     * their roster rows. Owner-only. Kr rotation is GR-7.
     */
    suspend fun remove(realmId: String, memberId: String): RepositoryResult<Unit>

    /**
     * Sweep grantable contacts' grant addresses for waiting grants and return the
     * full quarantined set (`pollPendingGrants`, GR-6). Backs the *Shared with me*
     * pending-grant cards.
     */
    suspend fun pollPendingGrants(): RepositoryResult<List<PendingGrantModel>>

    /**
     * Decline (discard) the quarantined grant from [grantorFingerprint]
     * (`declinePendingGrant`, GR-6).
     */
    suspend fun declinePendingGrant(grantorFingerprint: String): RepositoryResult<Unit>
}

/**
 * Data-layer projection of a [org.tenshu.identity.PendingGrant] row (GR-6) — a grant
 * the inbound poll discovered but the user hasn't accepted. [grantorFingerprint] is
 * the only identity the card needs (the grantor's petname is resolved from contacts);
 * the sealed blob stays in the proxy.
 */
data class PendingGrantModel(
    val grantorFingerprint: String,
    val discoveredAt: Long,
)

/**
 * Data-layer projection of a `Member` row (`identity-service` GR-1). [memberId]
 * is `hash(memberAuthorityPub)`; [contactFingerprint] is the member's A13 (empty
 * when not shared as a contact) and is how a member row dedupes against a
 * completed invite (D7).
 */
data class MemberModel(
    val memberId: String,
    val displayName: String,
    val contactFingerprint: String,
    val role: String,
) {
    val isOwner: Boolean get() = role == Member.Role.OWNER
}

class TenshuMembershipRepository(
    private val client: TenshuClient,
) : MembershipRepository {

    override suspend fun grant(
        realmId: String,
        personId: String,
        label: String,
    ): RepositoryResult<Unit> =
        client.syncService.grantSharedRealmMembership(realmId, personId, label).fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error -> RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey())) },
        )

    override suspend fun accept(grantorFingerprint: String): RepositoryResult<String> =
        client.syncService.acceptSharedRealmGrant(grantorFingerprint).fold(
            onSuccess = { realmId -> RepositoryResult.Success(realmId.get()) },
            onFailure = { error -> RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey())) },
        )

    override suspend fun members(realmId: String): RepositoryResult<List<MemberModel>> =
        client.identityService.getRealmMembers(realmId).fold(
            onSuccess = { members ->
                RepositoryResult.Success(
                    (members?.toList() ?: emptyList()).map { entity ->
                        MemberModel(
                            memberId = entity.memberId.get(),
                            displayName = entity.displayName.get(),
                            contactFingerprint = entity.contactFingerprint.get(),
                            role = entity.role.get(),
                        )
                    }
                )
            },
            onFailure = { error -> RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey())) },
        )

    override suspend fun remove(realmId: String, memberId: String): RepositoryResult<Unit> =
        client.syncService.removeSharedRealmMember(realmId, memberId).fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error -> RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey())) },
        )

    override suspend fun pollPendingGrants(): RepositoryResult<List<PendingGrantModel>> =
        client.syncService.pollPendingGrants().fold(
            onSuccess = { pending ->
                RepositoryResult.Success(
                    (pending?.toList() ?: emptyList()).map { entity ->
                        PendingGrantModel(
                            grantorFingerprint = entity.grantorFingerprint.get(),
                            discoveredAt = entity.discoveredAt.toEpochMilliseconds(),
                        )
                    }
                )
            },
            onFailure = { error -> RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey())) },
        )

    override suspend fun declinePendingGrant(grantorFingerprint: String): RepositoryResult<Unit> =
        client.syncService.declinePendingGrant(grantorFingerprint).fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error -> RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey())) },
        )

}
