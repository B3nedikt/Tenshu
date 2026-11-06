package app.tenshu.drive.ui

import app.tenshu.error.ErrorCopyKey

import app.tenshu.drive.data.ContactInviteModel
import app.tenshu.drive.data.ContactModel
import app.tenshu.drive.data.ContactsRepository
import app.tenshu.drive.data.InviteAckModel
import app.tenshu.drive.data.PendingRedemptionModel
import app.tenshu.drive.data.PersonModel
import app.tenshu.drive.data.RedeemOutcome
import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.tenshu.contact.ContactInvite

/**
 * Scriptable [ContactsRepository] double for the CI-4a ViewModel tests
 * (`drive-manage-access-ux.md` §8). Streams are backed by [MutableStateFlow]s
 * a test mutates directly; op calls are recorded and answer from scripted
 * results.
 */
internal class FakeContactsRepository(
    var createInviteResult: RepositoryResult<String> = RepositoryResult.Success("link"),
    var redeemResult: RedeemOutcome = RedeemOutcome.Redeemed("invite-1"),
    var completeResult: RepositoryResult<Unit> = RepositoryResult.Success(Unit),
    var pendingRedemptionsResult: RepositoryResult<List<PendingRedemptionModel>> =
        RepositoryResult.Success(emptyList()),
    var connectedContactsResult: RepositoryResult<List<ContactModel>> =
        RepositoryResult.Success(emptyList()),
) : ContactsRepository {

    val invites = MutableStateFlow<List<ContactInviteModel>>(emptyList())
    val personsFlow = MutableStateFlow<List<PersonModel>>(emptyList())
    val ownName = MutableStateFlow("")

    /** Queue of awaitInviteAck answers, popped per call; empty → error result. */
    val ackResults = ArrayDeque<InviteAckModel>()

    val createInviteCalls = mutableListOf<Pair<String, String>>()
    val revokeCalls = mutableListOf<String>()
    val redeemCalls = mutableListOf<Pair<String, String>>()
    /** Each awaitInviteAck call as (inviteId, sasConfirmed). */
    val awaitAckCalls = mutableListOf<Pair<String, Boolean>>()
    data class CompleteCall(
        val inviteId: String,
        val accept: Boolean,
        val personId: String,
        val newPersonName: String,
        val selfAssertedName: String,
        val sasConfirmed: Boolean,
        /** The raw claimed fingerprint the prompt passed as displayed (contact-service v2). */
        val shownFingerprint: String = "",
    )
    val completeCalls = mutableListOf<CompleteCall>()
    val savedOwnNames = mutableListOf<String>()

    override fun contactInvites() = invites

    override fun hasRedeemedInvites() =
        invites.map { rows -> rows.any { it.status == ContactInvite.Status.REDEEMED } }

    override fun persons() = personsFlow

    override suspend fun connectedContacts(): RepositoryResult<List<ContactModel>> =
        connectedContactsResult

    override suspend fun createInvite(
        intentKind: String,
        intentLabel: String,
    ): RepositoryResult<String> {
        createInviteCalls += intentKind to intentLabel
        return createInviteResult
    }

    override suspend fun revokeInvite(inviteId: String): RepositoryResult<Unit> {
        revokeCalls += inviteId
        return RepositoryResult.Success(Unit)
    }

    override suspend fun redeemInvite(link: String, selfAssertedName: String): RedeemOutcome {
        redeemCalls += link to selfAssertedName
        return redeemResult
    }

    override suspend fun awaitInviteAck(
        inviteId: String,
        sasConfirmed: Boolean,
    ): RepositoryResult<InviteAckModel> {
        awaitAckCalls += inviteId to sasConfirmed
        val next = ackResults.removeFirstOrNull()
            ?: return RepositoryResult.Error(RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED))
        return RepositoryResult.Success(next)
    }

    override suspend fun pendingRedemptions(): RepositoryResult<List<PendingRedemptionModel>> =
        pendingRedemptionsResult

    override suspend fun completeHandshake(
        inviteId: String,
        accept: Boolean,
        shownFingerprint: String,
        personId: String,
        newPersonName: String,
        selfAssertedName: String,
        sasConfirmed: Boolean,
    ): RepositoryResult<Unit> {
        completeCalls += CompleteCall(
            inviteId, accept, personId, newPersonName, selfAssertedName, sasConfirmed, shownFingerprint,
        )
        return completeResult
    }

    override fun ownDisplayName() = ownName

    override suspend fun setOwnDisplayName(name: String) {
        savedOwnNames += name
        ownName.value = name
    }
}
