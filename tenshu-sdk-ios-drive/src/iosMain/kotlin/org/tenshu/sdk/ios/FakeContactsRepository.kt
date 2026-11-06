package org.tenshu.sdk.ios

import app.tenshu.drive.data.ContactInviteModel
import app.tenshu.drive.data.ContactModel
import app.tenshu.drive.data.ContactsRepository
import app.tenshu.drive.data.InviteAckModel
import app.tenshu.drive.data.PendingRedemptionModel
import app.tenshu.drive.data.PersonModel
import app.tenshu.drive.data.RedeemOutcome
import app.tenshu.drive.data.RepositoryResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.tenshu.contact.AwaitInviteAck

/**
 * In-memory [ContactsRepository] for driving the full Drive sharing + contact-handshake UI without a
 * proxy. The real impl wraps `:contact-service` over a `TenshuClient`. Every screen surface is now
 * bound: read invites/persons/connected contacts, revoke invites, create an invite link/QR
 * ([createInvite]), complete the inviter-side handshake ([completeHandshake]), and run the invitee-side
 * redemption ([redeemInvite]/[awaitInviteAck]).
 */
class FakeContactsRepository(
    invites: List<ContactInviteModel> = emptyList(),
    private val contacts: List<ContactModel> = emptyList(),
    private val people: List<PersonModel> = emptyList(),
    private val ownName: String = "",
    private val inviteLinkResult: RepositoryResult<String> =
        RepositoryResult.Success("https://tenshu.app/i/demo-invite#k=demo-key"),
    private val completeHandshakeResult: RepositoryResult<Unit> = RepositoryResult.Success(Unit),
    private val redeemOutcome: RedeemOutcome = RedeemOutcome.Redeemed("redeemed-invite-id"),
    private val inviteAck: RepositoryResult<InviteAckModel> = RepositoryResult.Success(
        InviteAckModel(
            status = AwaitInviteAck.Result.ACCEPTED,
            peerName = "Alice",
            peerFingerprint = "FP-INVITER",
            intentKind = "",
            intentLabel = "",
            sas = "",
        ),
    ),
    private val pendingRedemptions: List<PendingRedemptionModel> = emptyList(),
) : ContactsRepository {

    val invitesFlow: MutableStateFlow<List<ContactInviteModel>> = MutableStateFlow(invites)
    val revoked: MutableList<String> = mutableListOf()

    /** Recorded `(intentKind, intentLabel)` of each created invite — assert against this in tests. */
    val createdInvites: MutableList<Pair<String, String>> = mutableListOf()

    /** Recorded completion-handshake calls — assert the accept/SAS/petname wiring in tests. */
    val completedHandshakes: MutableList<CompletedHandshake> = mutableListOf()

    /** Names committed via [setOwnDisplayName] (the prompt's "your name" field). */
    val ownNames: MutableList<String> = mutableListOf()

    /** Recorded `(link, selfAssertedName)` of each redeem attempt (invitee side). */
    val redeemedLinks: MutableList<Pair<String, String>> = mutableListOf()

    /** Recorded `(inviteId, sasConfirmed)` of each ack wait (invitee side). */
    val ackWaits: MutableList<Pair<String, Boolean>> = mutableListOf()

    data class CompletedHandshake(
        val inviteId: String,
        val accept: Boolean,
        val personId: String,
        val newPersonName: String,
        val selfAssertedName: String,
        val sasConfirmed: Boolean,
        val shownFingerprint: String = "",
    )

    override fun contactInvites(): Flow<List<ContactInviteModel>> = invitesFlow
    override fun hasRedeemedInvites(): Flow<Boolean> = flowOf(false)
    override fun persons(): Flow<List<PersonModel>> = flowOf(people)

    override suspend fun connectedContacts(): RepositoryResult<List<ContactModel>> =
        RepositoryResult.Success(contacts)

    override suspend fun revokeInvite(inviteId: String): RepositoryResult<Unit> {
        revoked += inviteId
        invitesFlow.value = invitesFlow.value.filterNot { it.inviteId == inviteId }
        return RepositoryResult.Success(Unit)
    }

    override suspend fun pendingRedemptions(): RepositoryResult<List<PendingRedemptionModel>> =
        RepositoryResult.Success(pendingRedemptions)

    override fun ownDisplayName(): Flow<String> = flowOf(ownName)
    override suspend fun setOwnDisplayName(name: String) {
        ownNames += name
    }

    /** Records the intent + returns the configurable [inviteLinkResult] (the *invite someone* sheet). */
    override suspend fun createInvite(intentKind: String, intentLabel: String): RepositoryResult<String> {
        createdInvites += intentKind to intentLabel
        return inviteLinkResult
    }

    /** Records the inviter-side completion + returns the configurable [completeHandshakeResult]. */
    override suspend fun completeHandshake(
        inviteId: String,
        accept: Boolean,
        shownFingerprint: String,
        personId: String,
        newPersonName: String,
        selfAssertedName: String,
        sasConfirmed: Boolean,
    ): RepositoryResult<Unit> {
        completedHandshakes += CompletedHandshake(
            inviteId, accept, personId, newPersonName, selfAssertedName, sasConfirmed, shownFingerprint,
        )
        return completeHandshakeResult
    }

    /** Records the redeem + returns the configurable [redeemOutcome] (invitee paste/confirm). */
    override suspend fun redeemInvite(link: String, selfAssertedName: String): RedeemOutcome {
        redeemedLinks += link to selfAssertedName
        return redeemOutcome
    }

    /** Records the wait + returns the configurable [inviteAck] (terminal by default — ACCEPTED). */
    override suspend fun awaitInviteAck(inviteId: String, sasConfirmed: Boolean): RepositoryResult<InviteAckModel> {
        ackWaits += inviteId to sasConfirmed
        return inviteAck
    }
}
