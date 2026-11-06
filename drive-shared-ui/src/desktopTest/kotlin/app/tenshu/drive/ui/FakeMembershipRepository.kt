package app.tenshu.drive.ui

import app.tenshu.error.ErrorCopyKey

import app.tenshu.drive.data.MemberModel
import app.tenshu.drive.data.MembershipRepository
import app.tenshu.drive.data.PendingGrantModel
import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult

/**
 * Scriptable [MembershipRepository] double for the GR-4/GR-6 ViewModel tests.
 * Grant / accept / remove / poll / decline results are settable; calls are
 * recorded. [membersResult] backs the *Manage access* Members section per realm id.
 */
internal class FakeMembershipRepository(
    var grantResult: RepositoryResult<Unit> = RepositoryResult.Success(Unit),
    var acceptResult: RepositoryResult<String> = RepositoryResult.Success("realm-joined"),
    var membersResult: RepositoryResult<List<MemberModel>> = RepositoryResult.Success(emptyList()),
    var removeResult: RepositoryResult<Unit> = RepositoryResult.Success(Unit),
    var pollResult: RepositoryResult<List<PendingGrantModel>> = RepositoryResult.Success(emptyList()),
    var declineResult: RepositoryResult<Unit> = RepositoryResult.Success(Unit),
) : MembershipRepository {

    data class GrantCall(val realmId: String, val personId: String, val label: String)
    data class RemoveCall(val realmId: String, val memberId: String)

    val grantCalls = mutableListOf<GrantCall>()
    val acceptCalls = mutableListOf<String>()
    val membersCalls = mutableListOf<String>()
    val removeCalls = mutableListOf<RemoveCall>()
    var pollCount = 0
    val declineCalls = mutableListOf<String>()

    override suspend fun grant(
        realmId: String,
        personId: String,
        label: String,
    ): RepositoryResult<Unit> {
        grantCalls += GrantCall(realmId, personId, label)
        return grantResult
    }

    override suspend fun accept(grantorFingerprint: String): RepositoryResult<String> {
        acceptCalls += grantorFingerprint
        return acceptResult
    }

    override suspend fun members(realmId: String): RepositoryResult<List<MemberModel>> {
        membersCalls += realmId
        return membersResult
    }

    override suspend fun remove(realmId: String, memberId: String): RepositoryResult<Unit> {
        removeCalls += RemoveCall(realmId, memberId)
        return removeResult
    }

    override suspend fun pollPendingGrants(): RepositoryResult<List<PendingGrantModel>> {
        pollCount++
        return pollResult
    }

    override suspend fun declinePendingGrant(grantorFingerprint: String): RepositoryResult<Unit> {
        declineCalls += grantorFingerprint
        return declineResult
    }

    companion object {
        fun error(key: ErrorCopyKey = ErrorCopyKey.UNEXPECTED) = RepositoryResult.Error(RepositoryError.Unknown(key))
    }
}
