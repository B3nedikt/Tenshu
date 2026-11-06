package app.tenshu.contacts.data

import app.tenshu.error.ErrorCopyKey
import app.tenshu.error.errorCopyKey

import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.ContactDetails
import app.tenshu.contacts.model.ContactField
import app.tenshu.contacts.model.ContactIdentity
import app.tenshu.contacts.model.InviteConfirmation
import app.tenshu.contacts.model.OutstandingInvite
import app.tenshu.contacts.model.OwnProfile
import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.UnreachableReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.tenshu.contact.ContactInvite
import org.tenshu.contact.ContactSource
import org.tenshu.contact.CompleteContactHandshake
import org.tenshu.contact.Person
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.claimedFingerprint
import org.tenshu.contact.claimedName
import org.tenshu.contact.claimedSas
import org.tenshu.contact.client.FullCardRead
import org.tenshu.contact.client.contactService
import org.tenshu.contact.client.fullVcard
import org.tenshu.contact.completeContactHandshake
import org.tenshu.contact.createContactInvite
import org.tenshu.contact.createPerson
import org.tenshu.contact.createdAt
import org.tenshu.contact.deletePerson
import org.tenshu.contact.displayName
import org.tenshu.contact.expiresAt
import org.tenshu.contact.getContactSas
import org.tenshu.contact.importContact
import org.tenshu.contact.rawVcard
import org.tenshu.contact.saveContactSource
import org.tenshu.contact.sourceKey
import org.tenshu.contact.fingerprint
import org.tenshu.contact.intentKind
import org.tenshu.contact.inviteId
import org.tenshu.contact.personId
import org.tenshu.contact.renamePerson
import org.tenshu.contact.revokeContactInvite
import org.tenshu.contact.status
import org.tenshu.contact.unlinkTenshuIdentity
import org.tenshu.contact.verifyTenshuIdentity
import org.tenshu.contact.verification
import org.tenshu.core.GenericError
import org.tenshu.core.TError
import org.tenshu.core.TString
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.entity.Entity
import org.tenshu.core.fold
import org.tenshu.core.getOrNull
import org.tenshu.contact.PersonAvatar
import org.tenshu.contact.clearPersonAvatar
import org.tenshu.contact.linkTenshuIdentity
import org.tenshu.contact.mergePersons
import org.tenshu.contact.imageData
import org.tenshu.contact.common.ContactCard
import org.tenshu.contact.common.LabeledValue
import org.tenshu.contact.common.mergeIntoVCard
import org.tenshu.contact.common.newVCard
import org.tenshu.contact.common.readContactCard
import org.tenshu.contact.common.splitVCards
import org.tenshu.core.time.Instant
import org.tenshu.preference.OwnerPreferences
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.read
import org.tenshu.preference.client.set

/**
 * The real binding (`docs/proposals/contacts-app.md` §6): the same [ContactsRepository] seam
 * the mock implements, backed by the real `:contact-service` surface over the `TenshuClient`
 * that reaches Control's proxy.
 *
 * Nothing here is service logic; it is the join, the ladder mapping, a preference read, and
 * one call per write op.
 *
 * [reachability] wraps the read streams so a probe-driven recovery re-subscribes them — a
 * stream that hung against a dead transport never recovers on its own.
 */
class TenshuContactsRepository(
    private val client: TenshuClient,
    /**
     * The app's reachability engine, **read from and never written to**.
     *
     * ⚠⚠ No `markUnreachable()` from a `fold(onFailure)` arm: a failure over a LIVE channel is a
     * schema refusal or a read error rather than an outage — and reporting one parks the "Can't
     * reach Control" banner on something reconnecting cannot fix. A dead channel reaches the
     * engine through `transportConnected`, instantly and authoritatively; the probe owns the
     * live-but-refusing case. The reads degrade to empty, which is what keeps the list
     * rendering.
     */
    // ⚠ Required, never defaulted (ADR 0025 §8.6): `null` only where no engine exists (tests), stated there.
    private val reachability: ControlReachability?,
) : ContactsRepository {

    override fun contacts(): Flow<List<Contact>> = resubscribing {
        combine(
            personRows(),
            identityRows(),
            sourceRows(),
            avatarRows(),
        ) { persons, identities, sources, avatars ->
            joinContacts(persons, identities, sources, avatars)
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Derived from [contacts] rather than a narrowed query. At address-book scale the whole
     * list is already in memory for the list screen, and re-deriving keeps one join — a
     * second read path would be a second place for the `personId == ""` rule to be missed.
     */
    override fun contact(personId: String): Flow<Contact?> =
        contacts().map { list -> list.firstOrNull { it.personId == personId } }

    override fun ownProfile(): Flow<OwnProfile> = resubscribing {
        client.preferenceService.read(OwnerPreferences.Name)
    }.map { name ->
        // `fingerprint` stays empty: there is no client-readable own-identity op. The card
        // renders the absence rather than a value.
        OwnProfile(displayName = name)
    }.flowOn(Dispatchers.Default)

    override suspend fun setOwnDisplayName(name: String) {
        client.preferenceService.set(OwnerPreferences.Name, name)
    }

    override fun controlReachable(): Flow<Boolean?> =
        reachability?.state ?: flowOf(true)

    override fun controlUnreachableReason(): kotlinx.coroutines.flow.StateFlow<UnreachableReason?> =
        reachability?.reason ?: app.tenshu.reachability.NO_UNREACHABLE_REASON

    override fun recheckControl() {
        reachability?.recheck()
    }

    // ── contact CRUD ─────────────────────────────────────────────────────────

    override suspend fun createContact(displayName: String): RepositoryResult<String> =
        client.contactService.createPerson(displayName).fold(
            onSuccess = { personId -> RepositoryResult.Success(personId.get()) },
            onFailure = { error -> RepositoryResult.Failure(error.errorCopyKey()) },
        )

    override suspend fun renameContact(
        personId: String,
        displayName: String,
    ): RepositoryResult<Unit> =
        client.contactService.renamePerson(personId, displayName).fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error -> RepositoryResult.Failure(error.errorCopyKey()) },
        )

    override suspend fun deleteContact(personId: String): RepositoryResult<Unit> =
        client.contactService.deletePerson(personId).fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error -> RepositoryResult.Failure(error.errorCopyKey()) },
        )

    override suspend fun unlinkIdentity(fingerprint: String): RepositoryResult<Unit> =
        client.contactService.unlinkTenshuIdentity(fingerprint).fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error -> RepositoryResult.Failure(error.errorCopyKey()) },
        )

    override suspend fun mergeContacts(
        sourcePersonId: String,
        targetPersonId: String,
    ): RepositoryResult<Unit> =
        client.contactService.mergePersons(sourcePersonId, targetPersonId).fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error -> RepositoryResult.Failure(error.errorCopyKey()) },
        )

    override suspend fun relinkIdentity(
        fingerprint: String,
        personId: String,
    ): RepositoryResult<Unit> =
        client.contactService.linkTenshuIdentity(fingerprint, personId).fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error -> RepositoryResult.Failure(error.errorCopyKey()) },
        )

    override suspend fun clearAvatar(personId: String): RepositoryResult<Unit> =
        client.contactService.clearPersonAvatar(personId).fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error -> RepositoryResult.Failure(error.errorCopyKey()) },
        )

    // ── contact detail / vCard ───────────────────────────────────────────────

    /**
     * ⚠⚠ **Merge, never regenerate** (Q6). The stored payload is read back, the edited
     * projection is merged into it by `mergeIntoVCard`, and the result is written whole — so
     * every property the editor does not model survives. Rebuilding the card from
     * [details] would be a silent, total loss of `X-` extensions, `CATEGORIES`, `UID` and
     * `PHOTO`, and nothing downstream could tell it had happened.
     *
     * A contact with no record yet gets a fresh `manual:` one (D5), keyed by `personId` —
     * one hand-typed record per contact, and idempotent if two devices create it at once.
     */
    override suspend fun saveContactDetails(
        personId: String,
        details: ContactDetails,
    ): RepositoryResult<Unit> {
        // ⚠ A failed read is a failure, never "no record yet": folding it to empty would write a
        // fresh `manual:` record beside the real one.
        val existing = when (val rows = sourceRowsFor(personId)) {
            is RepositoryResult.Success -> rows.value.firstOrNull()
            is RepositoryResult.Failure -> return rows
        }
        val card = details.toCard(
            // Keep the record's own FN: the petname lives on `Person` and is not this
            // editor's to rewrite.
            formattedName = existing?.let { readContactCard(it.second).formattedName }.orEmpty(),
        )
        val sourceKey = existing?.first ?: "${ContactSource.Origin.MANUAL}:$personId"
        val merged = existing?.let { mergeIntoVCard(it.second, card) } ?: newVCard(card)
        return client.contactService.saveContactSource(sourceKey, personId, merged).fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error -> RepositoryResult.Failure(error.errorCopyKey()) },
        )
    }

    override suspend fun importVcards(text: String): RepositoryResult<Int> {
        val cards = splitVCards(text)
        if (cards.isEmpty()) return RepositoryResult.Failure(ContactsErrorKeys.NO_CONTACTS_IN_FILE)
        var imported = 0
        cards.forEach { card ->
            val result = client.contactService.importContact(card, fallbackName = "")
            // Partial success is reported as a count rather than rolled back: a 200-card
            // export with one malformed entry should not lose the other 199.
            if (result.fold(onSuccess = { true }, onFailure = { false })) imported++
        }
        return RepositoryResult.Success(imported)
    }

    override suspend fun exportVcard(personId: String): RepositoryResult<String> {
        val rows = when (val read = sourceRowsFor(personId)) {
            is RepositoryResult.Success -> read.value
            is RepositoryResult.Failure -> return read // a failed read is not "nothing to export"
        }
        if (rows.isEmpty()) return RepositoryResult.Failure(ContactsErrorKeys.NOTHING_TO_EXPORT)
        // The payloads are stored verbatim but for their big photos (`contacts-app.md` §9.10), so
        // export is concatenation of the REASSEMBLED cards. ⚠⚠ A card whose photos cannot be
        // fetched fails the export — never exported without them.
        val cards = rows.map { (sourceKey, stored) ->
            when (val full = client.contactService.fullVcard(sourceKey, stored)) {
                is FullCardRead.Ok -> full.card
                FullCardRead.PhotosUnavailable -> return RepositoryResult.Failure(ContactsErrorKeys.PHOTO_UNAVAILABLE)
                is FullCardRead.Failed -> return RepositoryResult.Failure(full.error.errorCopyKey())
            }
        }
        return RepositoryResult.Success(cards.joinToString(""))
    }

    /** `(sourceKey, rawVcard)` for a person, in relation order — or the read's failure. */
    private suspend fun sourceRowsFor(personId: String): RepositoryResult<List<Pair<String, String>>> =
        client.contactService.sources
            .select {
                fields(
                    ContactSource.Field.SOURCE_KEY,
                    ContactSource.Field.PERSON_ID,
                    ContactSource.Field.RAW_VCARD,
                )
            }
            .getAll()
            .fold(
                onSuccess = { rows ->
                    RepositoryResult.Success(
                        rows.toList()
                            .filter { it.personId.get() == personId }
                            .map { it.sourceKey.get() to it.rawVcard.get() },
                    )
                },
                onFailure = { error -> RepositoryResult.Failure(error.errorCopyKey()) },
            )

    // ── re-verification ──────────────────────────────────────────────────────

    override suspend fun contactSas(fingerprint: String): RepositoryResult<String> =
        client.contactService.getContactSas(fingerprint).fold(
            onSuccess = { sas -> RepositoryResult.Success(sas.get()) },
            onFailure = { error -> RepositoryResult.Failure(error.errorCopyKey()) },
        )

    override suspend fun setVerified(
        fingerprint: String,
        verified: Boolean,
    ): RepositoryResult<Unit> =
        client.contactService.verifyTenshuIdentity(fingerprint, verified).fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error -> RepositoryResult.Failure(error.errorCopyKey()) },
        )

    // ── invitations ──────────────────────────────────────────────────────────

    override fun outstandingInvites(): Flow<List<OutstandingInvite>> =
        bareInvites(ContactInvite.Status.PENDING)
            .map { rows ->
                rows.map { row ->
                    OutstandingInvite(
                        inviteId = row.inviteId.get(),
                        createdAtMillis = row.createdAt.get(),
                        expiresAtMillis = row.expiresAt.get(),
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    override fun pendingConfirmations(): Flow<List<InviteConfirmation>> =
        bareInvites(ContactInvite.Status.REDEEMED)
            .map { rows ->
                rows.map { row ->
                    InviteConfirmation(
                        inviteId = row.inviteId.get(),
                        claimedName = row.claimedName.get(),
                        claimedFingerprint = row.claimedFingerprint.get(),
                        claimedSas = row.claimedSas.get(),
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    override suspend fun createBareInvite(): RepositoryResult<String> =
        // `intentKind = ""` is the bare invite `ContactInvite` already accepts — no share
        // rides along, so the handshake's only outcome is a contact.
        client.contactService.createContactInvite(intentKind = "", intentLabel = "").fold(
            onSuccess = { link -> RepositoryResult.Success(link.get()) },
            onFailure = { error -> RepositoryResult.Failure(error.errorCopyKey()) },
        )

    override suspend fun revokeInvite(inviteId: String): RepositoryResult<Unit> =
        client.contactService.revokeContactInvite(inviteId).fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error -> RepositoryResult.Failure(error.errorCopyKey()) },
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
                CompleteContactHandshake.Decision.ACCEPT
            } else {
                CompleteContactHandshake.Decision.REJECT
            },
            shownFingerprint = shownFingerprint,
            personId = personId,
            newPersonName = newPersonName,
            selfAssertedName = selfAssertedName,
            sasConfirmed = sasConfirmed,
        ).fold(
            onSuccess = { RepositoryResult.Success(Unit) },
            onFailure = { error -> RepositoryResult.Failure(error.completeHandshakeCopyKey()) },
        )

    /**
     * `ContactInvite` rows at [status] whose `intentKind` is bare.
     *
     * ⚠ The filter is what keeps Contacts and Drive from prompting for the same invite.
     * Drive owns `SHARE_FOLDER` end to end because its prompt chains into the membership-grant
     * offer; everything else is this app's. Filtering client-side rather than with a
     * `search` predicate keeps one read feeding both queries — the row count here is the
     * user's own invitations, not a scale concern.
     */
    private fun bareInvites(status: String): Flow<List<Entity<out ContactInvite>>> =
        resubscribing {
            client.contactService.contactInvites
                .select {
                    fields(
                        ContactInvite.Field.INVITE_ID,
                        ContactInvite.Field.STATUS,
                        ContactInvite.Field.INTENT_KIND,
                        ContactInvite.Field.CREATED_AT,
                        ContactInvite.Field.EXPIRES_AT,
                        ContactInvite.Field.CLAIMED_NAME,
                        ContactInvite.Field.CLAIMED_FINGERPRINT,
                        ContactInvite.Field.CLAIMED_SAS,
                    )
                }
                .flow()
        }.map { result ->
            result.fold(
                onSuccess = { rows ->
                    rows.toList().filter {
                        it.status.get() == status && it.intentKind.get().isEmpty()
                    }
                },
                onFailure = {
                    emptyList()
                },
            )
        }


    // ── reads ────────────────────────────────────────────────────────────────

    /** `Person` rows as keyring-less contacts; [joinContacts] attaches the keys. */
    private fun personRows(): Flow<List<Contact>> =
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
                    onSuccess = { rows ->
                        rows.toList().map { row ->
                            Contact(
                                personId = row.personId.get(),
                                displayName = row.displayName.get(),
                            )
                        }
                    },
                    onFailure = {
                        emptyList()
                    },
                )
            }

    /** Each keyring entry with the `personId` it back-links to (`""` = unlinked). */
    private fun identityRows(): Flow<List<Pair<String, ContactIdentity>>> =
        client.contactService.tenshuIdentities
            .select {
                fields(
                    TenshuIdentity.Field.FINGERPRINT,
                    TenshuIdentity.Field.PERSON_ID,
                    TenshuIdentity.Field.SELF_ASSERTED_NAME,
                    TenshuIdentity.Field.VERIFICATION,
                    TenshuIdentity.Field.VERIFIED_AT,
                    TenshuIdentity.Field.SOURCE,
                )
            }
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { rows ->
                        rows.toList().map { row -> row.personId.get() to row.toIdentity() }
                    },
                    onFailure = {
                        emptyList()
                    },
                )
            }

    private fun Entity<out TenshuIdentity>.toIdentity(): ContactIdentity = ContactIdentity(
        fingerprint = fingerprint.get(),
        selfAssertedName = getFieldValue<TString>(TenshuIdentity.Field.SELF_ASSERTED_NAME).get(),
        verification = verificationOf(verification.get()),
        verifiedAtMillis = getFieldValue<Instant>(TenshuIdentity.Field.VERIFIED_AT).get(),
        source = getFieldValue<TString>(TenshuIdentity.Field.SOURCE).get(),
    )

    /**
     * Each source record's editable projection, paired with the `personId` it belongs to.
     * [joinContacts] unions them per person for the detail screen's read-through view.
     */
    private fun sourceRows(): Flow<List<Pair<String, ContactDetails>>> =
        client.contactService.sources
            .select {
                fields(
                    ContactSource.Field.SOURCE_KEY,
                    ContactSource.Field.PERSON_ID,
                    ContactSource.Field.RAW_VCARD,
                )
            }
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { rows ->
                        rows.toList().map { row ->
                            row.personId.get() to readContactCard(row.rawVcard.get()).toDetails()
                        }
                    },
                    onFailure = {
                        emptyList()
                    },
                )
            }

    /**
     * Photo bytes by `personId`, read from the `PersonAvatar` rows directly.
     *
     * ⚠ **Not** through the deferred `Person.Avatar` property, deliberately. That property
     * exists for chain consumers projecting a photo beside a person; this repository already
     * streams the sibling relations for identities and sources, so a fourth stream is one
     * more `combine` argument rather than a second read mechanism to keep honest. It also
     * sidesteps the deferred-property rule that a one-shot read resolves to `null`.
     */
    private fun avatarRows(): Flow<Map<String, ByteArray>> =
        client.contactService.avatars
            .select {
                fields(
                    PersonAvatar.Field.PERSON_ID,
                    PersonAvatar.Field.IMAGE_DATA,
                )
            }
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { rows ->
                        rows.toList()
                            .filter { it.personId.get().isNotEmpty() }
                            .associate { it.personId.get() to it.imageData.get() }
                    },
                    onFailure = {
                        emptyMap()
                    },
                )
            }

    private fun <T> resubscribing(build: () -> Flow<T>): Flow<T> =
        reachability?.resubscribing(build) ?: build()
}

/**
 * The catalogue key for a failed `CompleteContactHandshake`.
 *
 * ⚠⚠ Not plain [errorCopyKey]: the two refusals of threat model T-CI-10 travel as
 * `InvalidParameter`, whose derived key is the generic sentence — and "the reply changed, decline
 * this invite and send a new one" is the one thing the user needs to be told. They are recognised
 * by type ([CompleteContactHandshake.Refusal]), never by reading a message.
 */
internal fun TError.completeHandshakeCopyKey(): ErrorCopyKey = when {
    CompleteContactHandshake.Refusal.isResponseChanged(this) -> ErrorCopyKey.INVITE_RESPONSE_CHANGED
    CompleteContactHandshake.Refusal.isCodeNotShown(this) -> ErrorCopyKey.INVITE_CODE_NOT_SHOWN
    else -> errorCopyKey()
}
