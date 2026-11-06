package app.tenshu.drive.data

import app.tenshu.error.ErrorCopyKey
import org.tenshu.core.InvalidParameter
import app.tenshu.error.errorCopyKey

import app.tenshu.drive.preferences.DrivePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.tenshu.contact.AwaitInviteAck
import org.tenshu.contact.ContactInvite
import org.tenshu.contact.InviteAckResult
import org.tenshu.contact.Person
import org.tenshu.contact.RedeemContactInvite
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.fingerprint
import org.tenshu.contact.grantSeed
import org.tenshu.contact.awaitInviteAck
import org.tenshu.contact.claimedFingerprint
import org.tenshu.contact.claimedName
import org.tenshu.contact.claimedSas
import org.tenshu.contact.client.contactService
import org.tenshu.contact.completeContactHandshake
import org.tenshu.contact.createContactInvite
import org.tenshu.contact.createdAt
import org.tenshu.contact.displayName
import org.tenshu.contact.expiresAt
import org.tenshu.contact.intentKind
import org.tenshu.contact.intentLabel
import org.tenshu.contact.inviteId
import org.tenshu.contact.listPendingRedemptions
import org.tenshu.contact.personId
import org.tenshu.contact.redeemContactInvite
import org.tenshu.contact.resultFingerprint
import org.tenshu.contact.revokeContactInvite
import org.tenshu.contact.status
import org.tenshu.core.GenericError
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.fold
import org.tenshu.core.getOrNull
import org.tenshu.preference.OwnerPreferences
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.read
import org.tenshu.preference.client.set

/**
 * Drive's surface over `:contact-service` for the *Manage access* / contact
 * handshake flows (`docs/proposals/drive-manage-access-ux.md` §4.1). Mirrors
 * [FilesRepository]'s shape: reactive streams over the contact relations
 * (maintained proxy-side by `ContactRelationMaintainer`) + thin suspend
 * wrappers over the typed client extensions.
 */
interface ContactsRepository {

    /** All [ContactInvite] rows, re-emitting on every change (synced, Eager). */
    fun contactInvites(): Flow<List<ContactInviteModel>>

    /**
     * True while any **share-folder** invite sits at `REDEEMED` — the completion-prompt
     * badge.
     *
     * ⚠ Narrowed to `SHARE_FOLDER` since the Contacts app landed (`contacts-app.md` §9.1).
     * The two apps partition the invite space so no invitation is prompted in two places:
     * Drive owns the ones it mints, whose completion chains into the GR-4 grant offer and so
     * needs Drive's file domain; Contacts owns bare invites, which have nothing to grant.
     */
    fun hasRedeemedInvites(): Flow<Boolean>

    /** The contact list (thin `Person` aggregates) for link-to-existing. */
    fun persons(): Flow<List<PersonModel>>

    /**
     * Connected contacts (a `Person` with ≥1 linked `TenshuIdentity`) for the
     * GR-4 grant / accept pickers — a one-shot read, like the shared-realm
     * read surface. [ContactModel.grantable] is true when a linked identity
     * carries a `grantSeed`: only contacts handshaked after GR-3 are grantable,
     * because both the grant and the accept address derivation need that seed.
     */
    suspend fun connectedContacts(): RepositoryResult<List<ContactModel>>

    /** Issue an invite; returns the one-session messaging link (never persisted). */
    suspend fun createInvite(intentKind: String, intentLabel: String): RepositoryResult<String>

    suspend fun revokeInvite(inviteId: String): RepositoryResult<Unit>

    /** Redeem a parsed link; typed outcomes carry the T-CI-01 loud-copy cases. */
    suspend fun redeemInvite(link: String, selfAssertedName: String): RedeemOutcome

    /**
     * One slow-poll budget of the ack wait; safe to re-invoke on `PENDING`.
     * [sasConfirmed] = the user confirmed the §5.3 code — an ACCEPTED close
     * then mints the inviter identity `VERIFIED`/`QR_SAS`.
     */
    suspend fun awaitInviteAck(
        inviteId: String,
        sasConfirmed: Boolean = false,
    ): RepositoryResult<InviteAckModel>

    /** Resumable invitee-side redemptions for the §3.2.4 restart path. */
    suspend fun pendingRedemptions(): RepositoryResult<List<PendingRedemptionModel>>

    suspend fun completeHandshake(
        inviteId: String,
        accept: Boolean,
        shownFingerprint: String,
        personId: String = "",
        newPersonName: String = "",
        selfAssertedName: String = "",
        sasConfirmed: Boolean = false,
    ): RepositoryResult<Unit>

    /**
     * The user's own display name sent inside handshake payloads — prompted
     * inline on first use (Drive has no profile surface).
     *
     * Backed by the platform-level `OwnerPreferences.Name` since FP-1: it is the
     * same name Fit's Profile shows, so a second app-private row would just be a
     * second answer to one question. (The old KDoc deferred to a Control
     * `ownerMetadata.displayName` seam — that seam was never built.)
     */
    fun ownDisplayName(): Flow<String>

    suspend fun setOwnDisplayName(name: String)
}

data class ContactInviteModel(
    val inviteId: String,
    val status: String,
    val intentKind: String,
    val intentLabel: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    /** The responder's self-asserted name — render as a *claim*, quoted. */
    val claimedName: String,
    val claimedFingerprint: String,
    /** Proxy-derived §5.3 SAS code; `""` before `REDEEMED`. */
    val claimedSas: String,
    /**
     * The key the completed handshake produced; `""` until `COMPLETED`.
     *
     * This is the join back from a finished invite to the contact it created, and it is
     * what lets the **grant offer be derived from synced state** instead of chained onto
     * the completion prompt (`contacts-app.md` §9.1 alternative).
     */
    val resultFingerprint: String = "",
)

data class PersonModel(
    val personId: String,
    val displayName: String,
)

/**
 * A connected contact for the GR-4 grant / accept pickers — a [Person] joined
 * to its primary [TenshuIdentity]. [fingerprint] is the contact's A13 (the
 * accept-grant address key); [grantable] gates the grant/accept actions on a
 * `grantSeed` being present.
 */
data class ContactModel(
    val personId: String,
    val displayName: String,
    val fingerprint: String,
    val grantable: Boolean,
)

data class InviteAckModel(
    /** One of [AwaitInviteAck.Result]. */
    val status: String,
    val peerName: String,
    val peerFingerprint: String,
    val intentKind: String,
    val intentLabel: String,
    /** Proxy-derived §5.3 SAS code; `""` unless ACCEPTED. */
    val sas: String,
)

data class PendingRedemptionModel(
    val inviteId: String,
    val redeemedAtMillis: Long,
    val peerFingerprint: String,
    /** Proxy-derived §5.3 SAS code; `""` while the realm is locked. */
    val sas: String,
)

/**
 * Typed redeem outcomes: the relay's single response slot being taken
 * ([AlreadyUsed]) is the T-CI-01 signal the UI must surface loudly, and
 * expired/revoked ([Gone]) has its own copy — neither may collapse into a
 * generic failure.
 */
sealed interface RedeemOutcome {
    data class Redeemed(val inviteId: String) : RedeemOutcome
    data object AlreadyUsed : RedeemOutcome
    data object Gone : RedeemOutcome
    /** ⚠ A catalogue key; the UI renders it. Never a proxy sentence (§5.0). */
    data class Failed(val copyKey: ErrorCopyKey) : RedeemOutcome
}

class TenshuContactsRepository(
    private val client: TenshuClient,
) : ContactsRepository {

    override fun contactInvites(): Flow<List<ContactInviteModel>> =
        client.contactService.contactInvites
            .select {
                fields(
                    ContactInvite.Field.INVITE_ID,
                    ContactInvite.Field.STATUS,
                    ContactInvite.Field.INTENT_KIND,
                    ContactInvite.Field.INTENT_LABEL,
                    ContactInvite.Field.CREATED_AT,
                    ContactInvite.Field.EXPIRES_AT,
                    ContactInvite.Field.CLAIMED_NAME,
                    ContactInvite.Field.CLAIMED_FINGERPRINT,
                    ContactInvite.Field.CLAIMED_SAS,
                    ContactInvite.Field.RESULT_FINGERPRINT,
                )
            }
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { invites ->
                        invites.toList().map { entity ->
                            ContactInviteModel(
                                inviteId = entity.inviteId.get(),
                                status = entity.status.get(),
                                intentKind = entity.intentKind.get(),
                                intentLabel = entity.intentLabel.get(),
                                createdAtMillis = entity.createdAt.get(),
                                expiresAtMillis = entity.expiresAt.get(),
                                claimedName = entity.claimedName.get(),
                                claimedFingerprint = entity.claimedFingerprint.get(),
                                claimedSas = entity.claimedSas.get(),
                                resultFingerprint = entity.resultFingerprint.get(),
                            )
                        }
                    },
                    onFailure = { emptyList() },
                )
            }
            .flowOn(Dispatchers.Default)

    override fun hasRedeemedInvites(): Flow<Boolean> =
        contactInvites().map { invites ->
            invites.any {
                it.status == ContactInvite.Status.REDEEMED &&
                    it.intentKind == ContactInvite.IntentKind.SHARE_FOLDER
            }
        }

    override fun persons(): Flow<List<PersonModel>> =
        client.contactService.persons
            .select {
                fields(
                    Person.Field.PERSON_ID,
                    Person.Field.DISPLAY_NAME,
                )
            }
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { persons ->
                        persons.toList().map { entity ->
                            PersonModel(
                                personId = entity.personId.get(),
                                displayName = entity.displayName.get(),
                            )
                        }
                    },
                    onFailure = { emptyList() },
                )
            }
            .flowOn(Dispatchers.Default)

    override suspend fun connectedContacts(): RepositoryResult<List<ContactModel>> {
        val persons = client.contactService.persons
            .select { fields(Person.Field.PERSON_ID, Person.Field.DISPLAY_NAME) }
            .getAll()
            .getOrNull()
            ?.toList()
            ?: return RepositoryResult.Error(RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED))
        val identities = client.contactService.tenshuIdentities
            .select {
                fields(
                    TenshuIdentity.Field.PERSON_ID,
                    TenshuIdentity.Field.FINGERPRINT,
                    TenshuIdentity.Field.GRANT_SEED,
                )
            }
            .getAll()
            .getOrNull()
            ?.toList()
            .orEmpty()
        // Group the keyring by personId; a contact is grantable iff one of its
        // identities carries a grant seed, and the fingerprint we surface is
        // that grantable identity's (so the accept-grant address derives).
        val byPerson = identities.groupBy { it.personId.get() }
        val models = persons.mapNotNull { person ->
            val personId = person.personId.get()
            val keyring = byPerson[personId] ?: return@mapNotNull null // not yet connected
            val grantableIdentity = keyring.firstOrNull { it.grantSeed.get().isNotEmpty() }
            val primary = grantableIdentity ?: keyring.first()
            ContactModel(
                personId = personId,
                displayName = person.displayName.get(),
                fingerprint = primary.fingerprint.get(),
                grantable = grantableIdentity != null,
            )
        }
        return RepositoryResult.Success(models)
    }

    override suspend fun createInvite(
        intentKind: String,
        intentLabel: String,
    ): RepositoryResult<String> =
        client.contactService.createContactInvite(intentKind, intentLabel).fold(
            onSuccess = { link -> RepositoryResult.Success(link.get()) },
            onFailure = { error -> RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey())) },
        )

    override suspend fun revokeInvite(inviteId: String): RepositoryResult<Unit> =
        client.contactService.revokeContactInvite(inviteId).fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error -> RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey())) },
        )

    override suspend fun redeemInvite(
        link: String,
        selfAssertedName: String,
    ): RedeemOutcome =
        client.contactService.redeemContactInvite(link, selfAssertedName).fold(
            onSuccess = { inviteId -> RedeemOutcome.Redeemed(inviteId.get()) },
            onFailure = { error ->
                // ⚠⚠ Matches the typed reason, not a substring of a message. This used to be
                // `ErrorCode.ALREADY_RESPONDED in message` — a real code travelling in the wrong
                // field, so a caller could only find it by searching English
                // (`core-error-copy-catalogue.md` §5.6).
                val reason = (error as? InvalidParameter)
                    ?.takeIf { it.name.get() == RedeemContactInvite.Params.LINK_PAYLOAD }
                    ?.reason?.get()
                when (reason) {
                    InvalidParameter.Reasons.ALREADY_CONSUMED -> RedeemOutcome.AlreadyUsed
                    InvalidParameter.Reasons.GONE -> RedeemOutcome.Gone
                    else -> RedeemOutcome.Failed(error.errorCopyKey())
                }
            },
        )

    override suspend fun awaitInviteAck(
        inviteId: String,
        sasConfirmed: Boolean,
    ): RepositoryResult<InviteAckModel> =
        client.contactService.awaitInviteAck(inviteId, sasConfirmed).await().fold(
            onSuccess = { ack -> RepositoryResult.Success(ack.toModel()) },
            onFailure = { error -> RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey())) },
        )

    override suspend fun pendingRedemptions(): RepositoryResult<List<PendingRedemptionModel>> =
        client.contactService.listPendingRedemptions().fold(
            onSuccess = { redemptions ->
                RepositoryResult.Success(
                    redemptions.toList().map { row ->
                        PendingRedemptionModel(
                            inviteId = row.inviteId.get(),
                            redeemedAtMillis = row.redeemedAt.get(),
                            peerFingerprint = row.peerFingerprint.get(),
                            sas = row.sas.get(),
                        )
                    }
                )
            },
            onFailure = { error -> RepositoryResult.Error(RepositoryError.Unknown(error.errorCopyKey())) },
        )

    override suspend fun completeHandshake(
        inviteId: String,
        accept: Boolean,
        shownFingerprint: String,
        personId: String,
        newPersonName: String,
        selfAssertedName: String,
        sasConfirmed: Boolean,
    ): RepositoryResult<Unit> =
        client.contactService.completeContactHandshake(
            inviteId = inviteId,
            decision = if (accept) {
                org.tenshu.contact.CompleteContactHandshake.Decision.ACCEPT
            } else {
                org.tenshu.contact.CompleteContactHandshake.Decision.REJECT
            },
            shownFingerprint = shownFingerprint,
            personId = personId,
            newPersonName = newPersonName,
            selfAssertedName = selfAssertedName,
            sasConfirmed = sasConfirmed,
        ).fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error ->
                RepositoryResult.Error(RepositoryError.Unknown(error.completeHandshakeCopyKey()))
            },
        )

    // The user's own name is platform-level (`OwnerPreferences.Name`), not Drive's — it is the
    // same person Fit's Profile names. Drive stored it as `drive.contact.selfName` only because
    // Drive needed it first (fit-app.md §18.2). Pre-release, the old row is simply left behind
    // rather than migrated: the name is re-prompted inline on first use, so the worst case is one
    // extra prompt, and keeping both writable is exactly the two-sources-of-truth bug this fixes.
    override fun ownDisplayName(): Flow<String> =
        client.preferenceService.read(OwnerPreferences.Name)

    override suspend fun setOwnDisplayName(name: String) {
        client.preferenceService.set(OwnerPreferences.Name, name)
    }

    private fun InviteAckResult.toModel(): InviteAckModel = InviteAckModel(
        status = status.get(),
        peerName = peerName.get(),
        peerFingerprint = peerFingerprint.get(),
        intentKind = intentKind.get(),
        intentLabel = intentLabel.get(),
        sas = sas.get(),
    )

}

/**
 * The catalogue key for a failed `CompleteContactHandshake`.
 *
 * ⚠⚠ Not plain [errorCopyKey]: the two refusals of threat model T-CI-10 travel as
 * `InvalidParameter`, whose derived key is the generic sentence — and "the reply changed, decline
 * this invite and send a new one" is the one thing the user needs to be told. They are recognised
 * by type ([org.tenshu.contact.CompleteContactHandshake.Refusal]), never by reading a message.
 */
internal fun TError.completeHandshakeCopyKey(): ErrorCopyKey = when {
    org.tenshu.contact.CompleteContactHandshake.Refusal.isResponseChanged(this) -> ErrorCopyKey.INVITE_RESPONSE_CHANGED
    org.tenshu.contact.CompleteContactHandshake.Refusal.isCodeNotShown(this) -> ErrorCopyKey.INVITE_CODE_NOT_SHOWN
    else -> errorCopyKey()
}
