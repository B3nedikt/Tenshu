package app.tenshu.contacts.data

import app.tenshu.error.ErrorCopyKey

import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.ContactDetails
import app.tenshu.contacts.model.ContactIdentity
import app.tenshu.contacts.model.InviteConfirmation
import app.tenshu.contacts.model.OutstandingInvite
import app.tenshu.contacts.model.OwnProfile
import app.tenshu.contacts.model.Verification
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The in-memory stand-in (`docs/proposals/contacts-app.md` D8) for previews and tests: enough
 * shape to build the badged list, the detail screen and the own profile card without the
 * real `:contact-service` read surface.
 *
 * The fixture is chosen to cover **all four** [app.tenshu.contacts.model.TrustState] cases,
 * including the mixed keyring the Q2b rule exists for — a screen that has never rendered
 * `NeedsReverification` has not really been built.
 */
class MockContactsRepository(
    initial: List<Contact> = ContactsMockData.contacts,
    initialProfile: OwnProfile = ContactsMockData.ownProfile,
    private val initialConfirmations: List<InviteConfirmation> = emptyList(),
) : ContactsRepository {

    private val _contacts = MutableStateFlow(initial.sortedBy { it.displayName.lowercase() })
    private val _profile = MutableStateFlow(initialProfile)

    val contactsState: StateFlow<List<Contact>> get() = _contacts

    override fun contacts(): Flow<List<Contact>> = _contacts

    override fun contact(personId: String): Flow<Contact?> =
        _contacts.map { list -> list.firstOrNull { it.personId == personId } }

    override fun ownProfile(): Flow<OwnProfile> = _profile

    override suspend fun setOwnDisplayName(name: String) {
        _profile.update { it.copy(displayName = name) }
    }

    // ── contact CRUD ─────────────────────────────────────────────────────────

    private var nextPerson = 0

    override suspend fun createContact(displayName: String): RepositoryResult<String> {
        val personId = "person-new-${nextPerson++}"
        _contacts.update { list ->
            (list + Contact(personId = personId, displayName = displayName))
                .sortedBy { it.displayName.lowercase() }
        }
        return RepositoryResult.Success(personId)
    }

    override suspend fun renameContact(
        personId: String,
        displayName: String,
    ): RepositoryResult<Unit> {
        if (_contacts.value.none { it.personId == personId }) {
            return RepositoryResult.Failure(ErrorCopyKey.UNEXPECTED)
        }
        _contacts.update { list ->
            list.map { if (it.personId == personId) it.copy(displayName = displayName) else it }
                .sortedBy { it.displayName.lowercase() }
        }
        return RepositoryResult.Success(Unit)
    }

    /** Mirrors the proxy: the keyring goes with the contact (Q7a). */
    override suspend fun deleteContact(personId: String): RepositoryResult<Unit> {
        _contacts.update { list -> list.filterNot { it.personId == personId } }
        return RepositoryResult.Success(Unit)
    }

    override suspend fun unlinkIdentity(fingerprint: String): RepositoryResult<Unit> {
        _contacts.update { list ->
            list.map { contact ->
                contact.copy(identities = contact.identities.filterNot { it.fingerprint == fingerprint })
            }
        }
        return RepositoryResult.Success(Unit)
    }

    override suspend fun mergeContacts(
        sourcePersonId: String,
        targetPersonId: String,
    ): RepositoryResult<Unit> {
        if (sourcePersonId == targetPersonId || sourcePersonId.isEmpty() || targetPersonId.isEmpty()) {
            return RepositoryResult.Failure(ErrorCopyKey.UNEXPECTED)
        }
        val source = _contacts.value.firstOrNull { it.personId == sourcePersonId }
            ?: return RepositoryResult.Failure(ErrorCopyKey.UNEXPECTED)
        _contacts.update { list ->
            list.mapNotNull { contact ->
                when (contact.personId) {
                    sourcePersonId -> null
                    targetPersonId -> contact.copy(
                        // Mirrors the service exactly: keyrings union, the target keeps its
                        // petname, and only a contact with no photo inherits one.
                        identities = contact.identities + source.identities,
                        avatar = contact.avatar ?: source.avatar,
                    )
                    else -> contact
                }
            }
        }
        return RepositoryResult.Success(Unit)
    }

    override suspend fun relinkIdentity(
        fingerprint: String,
        personId: String,
    ): RepositoryResult<Unit> {
        val moving = _contacts.value
            .flatMap { it.identities }
            .firstOrNull { it.fingerprint == fingerprint }
            ?: return RepositoryResult.Failure(ErrorCopyKey.UNEXPECTED)
        _contacts.update { list ->
            list.map { contact ->
                when (contact.personId) {
                    personId -> contact.copy(identities = contact.identities + moving)
                    else -> contact.copy(
                        identities = contact.identities.filterNot { it.fingerprint == fingerprint }
                    )
                }
            }
        }
        return RepositoryResult.Success(Unit)
    }

    override suspend fun clearAvatar(personId: String): RepositoryResult<Unit> {
        _contacts.update { list ->
            list.map { if (it.personId == personId) it.copy(avatar = null) else it }
        }
        return RepositoryResult.Success(Unit)
    }

    // ── contact detail / vCard ───────────────────────────────────────────────

    private var nextImport = 0

    override suspend fun saveContactDetails(
        personId: String,
        details: ContactDetails,
    ): RepositoryResult<Unit> {
        if (_contacts.value.none { it.personId == personId }) {
            return RepositoryResult.Failure(ErrorCopyKey.UNEXPECTED)
        }
        _contacts.update { list ->
            list.map { if (it.personId == personId) it.copy(details = details) else it }
        }
        return RepositoryResult.Success(Unit)
    }

    /**
     * Counts `BEGIN:VCARD` markers and creates that many contacts. The mock cannot parse a
     * vCard — the codec lives in `:contact-service-common`, which this module deliberately
     * does not depend on — so it models the *shape* of the outcome (n cards in, n contacts
     * out, each its own row per D7's no-auto-merge rule) and leaves the parsing to the real
     * implementation's tests.
     */
    override suspend fun importVcards(text: String): RepositoryResult<Int> {
        val count = text.lineSequence().count { it.trim().uppercase().startsWith("BEGIN:VCARD") }
        // Text holding no cards fails rather than succeeding with zero — matching the real
        // implementation, because the import UI branches on the difference between "that file
        // was not an address book" and "none of its cards could be stored".
        if (count == 0) return RepositoryResult.Failure(ErrorCopyKey.UNEXPECTED)
        repeat(count) {
            createContact("Imported ${nextImport++}")
        }
        return RepositoryResult.Success(count)
    }

    override suspend fun exportVcard(personId: String): RepositoryResult<String> {
        val contact = _contacts.value.firstOrNull { it.personId == personId }
            ?: return RepositoryResult.Failure(ErrorCopyKey.UNEXPECTED)
        return RepositoryResult.Success(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:${contact.displayName}\r\nEND:VCARD\r\n"
        )
    }

    // ── re-verification ──────────────────────────────────────────────────────

    override suspend fun contactSas(fingerprint: String): RepositoryResult<String> {
        val known = _contacts.value.any { c -> c.identities.any { it.fingerprint == fingerprint } }
        // Mirrors the proxy: an unknown key yields no code rather than an error.
        return RepositoryResult.Success(if (known) MOCK_SAS else "")
    }

    override suspend fun setVerified(
        fingerprint: String,
        verified: Boolean,
    ): RepositoryResult<Unit> {
        _contacts.update { list ->
            list.map { contact ->
                contact.copy(
                    identities = contact.identities.map { identity ->
                        if (identity.fingerprint != fingerprint) return@map identity
                        // Mirrors the proxy's rung handling: ATTESTED is Rei-carried and a
                        // local verdict must not move it in either direction.
                        if (identity.verification == Verification.ATTESTED) return@map identity
                        identity.copy(
                            verification = if (verified) {
                                Verification.VERIFIED
                            } else {
                                Verification.UNVERIFIED
                            },
                            verifiedAtMillis = if (verified) MOCK_VERIFIED_AT else 0,
                            source = if (verified) "QR_SAS" else identity.source,
                        )
                    }
                )
            }
        }
        return RepositoryResult.Success(Unit)
    }

    // ── invitations ──────────────────────────────────────────────────────────

    private val _outstanding = MutableStateFlow(emptyList<OutstandingInvite>())
    private val _pending = MutableStateFlow(initialConfirmations)
    private var nextInvite = 0

    override fun outstandingInvites(): Flow<List<OutstandingInvite>> = _outstanding

    override fun pendingConfirmations(): Flow<List<InviteConfirmation>> = _pending

    override suspend fun createBareInvite(): RepositoryResult<String> {
        val id = "invite-${nextInvite++}"
        _outstanding.update {
            it + OutstandingInvite(
                inviteId = id,
                createdAtMillis = 0,
                expiresAtMillis = 0,
            )
        }
        return RepositoryResult.Success("tenshu-invite:$id#mock-key")
    }

    override suspend fun revokeInvite(inviteId: String): RepositoryResult<Unit> {
        _outstanding.update { list -> list.filterNot { it.inviteId == inviteId } }
        _pending.update { list -> list.filterNot { it.inviteId == inviteId } }
        return RepositoryResult.Success(Unit)
    }

    override suspend fun completeHandshake(
        inviteId: String,
        accept: Boolean,
        shownFingerprint: String,
        personId: String,
        newPersonName: String,
        selfAssertedName: String,
        sasConfirmed: Boolean,
    ): RepositoryResult<Unit> {
        val confirmation = _pending.value.firstOrNull { it.inviteId == inviteId }
        _pending.update { list -> list.filterNot { it.inviteId == inviteId } }
        _outstanding.update { list -> list.filterNot { it.inviteId == inviteId } }
        if (!accept || confirmation == null) return RepositoryResult.Success(Unit)

        val identity = ContactIdentity(
            fingerprint = confirmation.claimedFingerprint,
            selfAssertedName = confirmation.claimedName,
            // Mirrors the proxy: the key is elevated only on an explicit SAS confirmation.
            verification = if (sasConfirmed) Verification.VERIFIED else Verification.UNVERIFIED,
            source = if (sasConfirmed) "QR_SAS" else "INVITE_LINK",
        )
        _contacts.update { list ->
            val existing = list.firstOrNull { it.personId == personId }
            val updated = if (existing != null) {
                // Re-point at an existing contact: the key joins that keyring. Note it does
                // NOT inherit the contact's trust — verification lives on the key.
                list.map { c ->
                    if (c.personId == personId) c.copy(identities = c.identities + identity) else c
                }
            } else {
                list + Contact(
                    personId = "person-$inviteId",
                    displayName = newPersonName.ifBlank { confirmation.claimedName },
                    identities = listOf(identity),
                )
            }
            updated.sortedBy { it.displayName.lowercase() }
        }
        return RepositoryResult.Success(Unit)
    }
}

/** A stable stand-in for the proxy-derived §5.3 code. */
private const val MOCK_SAS = "418902"

/** Fixed so a verified-at stamp is assertable without a clock. */
private const val MOCK_VERIFIED_AT = 1_754_000_000_000L

/** The mock's fixture. Also feeds Compose previews and the desktop render tests. */
object ContactsMockData {

    val ownProfile = OwnProfile(
        displayName = "Kae",
        // A plausible full-hex fingerprint so `displayFingerprint` has something to shape.
        // The real card reads `""`: there is no own-identity surface — see OwnProfile.
        fingerprint = "9f2c41ab77e30d5c8b1af6042d9e7c3315bb08ae",
    )

    val contacts: List<Contact> = listOf(
        // Verified: one key, compared out of band. The checkmark case.
        Contact(
            personId = "person-mira",
            displayName = "Mira Okonkwo",
            identities = listOf(
                ContactIdentity(
                    fingerprint = "3ab19c7f2e08d641b5c0aa93e77f10d2846bc5e1",
                    selfAssertedName = "Mira",
                    verification = Verification.VERIFIED,
                    verifiedAtMillis = 1_753_000_000_000,
                    source = "QR_SAS",
                ),
            ),
            // A photo, so the render tests and previews exercise the avatar path. Not a real
            // image: `decodeAvatar` returns null for it, which is exactly the fallback the
            // list has to handle gracefully.
            avatar = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
        ),
        // Unverified: TOFU through a remote link — the population Q2a's compare-and-confirm
        // screen is built for, since they are exactly the people not in the room.
        Contact(
            personId = "person-jonas",
            displayName = "Jonas Weber",
            identities = listOf(
                ContactIdentity(
                    fingerprint = "c14d802fbb6e3971a05c4d8ee2130f7a99b64c05",
                    selfAssertedName = "jonas.w",
                    verification = Verification.UNVERIFIED,
                    source = "INVITE_LINK",
                ),
            ),
        ),
        // ⚠ The Q2b case: an old verified key beside a current unverified one. A rule that
        // trusted any verified key would render a checkmark for a key they no longer use.
        Contact(
            personId = "person-sara",
            displayName = "Sara Lindqvist",
            identities = listOf(
                ContactIdentity(
                    fingerprint = "7e5f0c39a1bb24d806fe93cc5a17d240b8e6f091",
                    selfAssertedName = "Sara L",
                    verification = Verification.VERIFIED,
                    verifiedAtMillis = 1_740_000_000_000,
                    source = "QR_SAS",
                ),
                ContactIdentity(
                    fingerprint = "20a7cc61d4e93b05f8172ba0ce3d9f4471e02b8d",
                    selfAssertedName = "Sara L",
                    verification = Verification.UNVERIFIED,
                    source = "INVITE_LINK",
                ),
            ),
        ),
        // Not connected: a person with no key at all (D5) — typed by hand or imported.
        // Not a warning state; most contacts look like this.
        Contact(
            personId = "person-dad",
            displayName = "Dad",
        ),
        Contact(
            personId = "person-ines",
            displayName = "Inés Aguilar",
        ),
    )
}
