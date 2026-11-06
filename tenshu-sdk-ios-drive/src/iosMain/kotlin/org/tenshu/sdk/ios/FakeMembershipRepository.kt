package org.tenshu.sdk.ios

import app.tenshu.drive.data.MemberModel
import app.tenshu.drive.data.MembershipRepository
import app.tenshu.drive.data.PendingGrantModel
import app.tenshu.drive.data.RepositoryResult

/**
 * In-memory [MembershipRepository] for driving the Manage Access UI without a proxy. The real impl
 * wraps the GR shared-realm grant/accept/roster ops over a `TenshuClient` (the on-device data path);
 * here members are configurable and grant/remove are recorded.
 */
class FakeMembershipRepository(
    private val members: List<MemberModel> = emptyList(),
    private val pendingGrants: List<PendingGrantModel> = emptyList(),
) : MembershipRepository {

    val granted: MutableList<GrantRecord> = mutableListOf()
    val removed: MutableList<Pair<String, String>> = mutableListOf()
    val accepted: MutableList<String> = mutableListOf()
    val declined: MutableList<String> = mutableListOf()

    data class GrantRecord(val realmId: String, val personId: String, val label: String)

    override suspend fun grant(realmId: String, personId: String, label: String): RepositoryResult<Unit> {
        granted += GrantRecord(realmId, personId, label)
        return RepositoryResult.Success(Unit)
    }

    override suspend fun accept(grantorFingerprint: String): RepositoryResult<String> {
        accepted += grantorFingerprint
        return RepositoryResult.Success("fake-accepted-realm")
    }

    override suspend fun members(realmId: String): RepositoryResult<List<MemberModel>> =
        RepositoryResult.Success(members)

    override suspend fun remove(realmId: String, memberId: String): RepositoryResult<Unit> {
        removed += realmId to memberId
        return RepositoryResult.Success(Unit)
    }

    override suspend fun pollPendingGrants(): RepositoryResult<List<PendingGrantModel>> =
        RepositoryResult.Success(pendingGrants)

    override suspend fun declinePendingGrant(grantorFingerprint: String): RepositoryResult<Unit> {
        declined += grantorFingerprint
        return RepositoryResult.Success(Unit)
    }
}
