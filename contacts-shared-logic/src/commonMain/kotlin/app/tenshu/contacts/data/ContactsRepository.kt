package app.tenshu.contacts.data

import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.ContactDetails
import app.tenshu.contacts.model.InviteConfirmation
import app.tenshu.contacts.model.OutstandingInvite
import app.tenshu.contacts.model.OwnProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The Contacts app's data seam (`docs/proposals/contacts-app.md` D8). The mock in this
 * module implements it for previews and tests; `:contacts-shared-data`'s
 * `TenshuContactsRepository` reads the real `:contact-service` surface, with no view-model or
 * UI change.
 *
 * **Every method here has a real implementation**, which is the rule that keeps the mock
 * honest — the mock must never be able to do something the service cannot.
 *
 * The service operations behind these methods carry their contracts; a method here restates
 * only what its caller has to do.
 *
 * ⚠⚠ **[kotlin.native.ObjCName] is load-bearing** (`ios-fit-timeline-maps-parity.md`
 * D12). `app.tenshu.drive.data.ContactsRepository` is on the **same** `TenshuSdk` umbrella, and
 * Kotlin/Native resolves an Obj-C name clash by appending `_` to whichever fully-qualified name
 * sorts *later* — `app.tenshu.contacts` sorts before `app.tenshu.drive`, so exporting this
 * module would silently rename **Drive's** type to `ContactsRepository_` and break the shipped
 * `drive-ios`. D12's rule: the newcomer carries the annotation.
 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("ContactsAppRepository")
interface ContactsRepository {

    /**
     * Every contact, re-emitting on change: the `Person` rows joined to their keyrings.
     * Sorted by petname so the list order is the repository's answer, not each caller's.
     */
    fun contacts(): Flow<List<Contact>>

    /** One contact by `personId`; emits `null` once it is known not to exist. */
    fun contact(personId: String): Flow<Contact?>

    /** The user's own card (D6). */
    fun ownProfile(): Flow<OwnProfile>

    suspend fun setOwnDisplayName(name: String)

    // ── contact CRUD ─────────────────────────────────────────────────────────

    /** Create a contact by hand — a petname and no key (D5). Returns its `personId`. */
    suspend fun createContact(displayName: String): RepositoryResult<String>

    /** Change a contact's petname. Never touches the names its keys claim. */
    suspend fun renameContact(personId: String, displayName: String): RepositoryResult<Unit>

    /**
     * Delete a contact and **every key linked to it** (Q7a).
     *
     * ⚠⚠ **This revokes nothing** — a deleted contact keeps whatever access they were granted,
     * so the caller must say so before invoking this (Q7b, `DeletePerson`).
     */
    suspend fun deleteContact(personId: String): RepositoryResult<Unit>

    /** Drop one key from a contact's keyring; the contact itself stays. */
    suspend fun unlinkIdentity(fingerprint: String): RepositoryResult<Unit>

    // ── merge (D7) ───────────────────────────────────────────────────────────

    /**
     * Fold [sourcePersonId] into [targetPersonId] — keys, source records and photo move, and
     * the source contact is deleted.
     *
     * ⚠⚠ **Only ever on an explicit user confirmation** (D7, `MergePersons`). Candidate
     * *suggestion* is a pure function over the contact list (`findMergeCandidates`).
     *
     * ⚠ **There is no un-merge**, so the confirmation has to show what is about to be combined.
     */
    suspend fun mergeContacts(
        sourcePersonId: String,
        targetPersonId: String,
    ): RepositoryResult<Unit>

    /**
     * Re-point one key at a different contact — the remedy for a mis-link made at the
     * handshake completion prompt.
     *
     * ⚠ Never moves verification with it (`LinkTenshuIdentity`).
     */
    suspend fun relinkIdentity(fingerprint: String, personId: String): RepositoryResult<Unit>

    // ── photo ────────────────────────────────────────────────────────────────

    /**
     * Remove a contact's photo.
     *
     * ⚠ There is deliberately no `setAvatar` counterpart. Of D1's two sources, a card's
     * embedded `PHOTO` is promoted at import, in the service, and *pick from Drive* has no
     * picker on this seam (§5) — a setter with no caller is what the seam's rule is against.
     *
     * The card's own `PHOTO` is untouched (`ClearPersonAvatar`), so re-importing the card
     * brings it back.
     */
    suspend fun clearAvatar(personId: String): RepositoryResult<Unit>

    // ── contact detail / vCard ───────────────────────────────────────────────

    /**
     * Save the editable field set (Q6) for a contact.
     *
     * ⚠⚠ The implementation **merges** into the contact's existing record — it must never
     * regenerate it, or every property the editor does not model (`X-` extensions,
     * `CATEGORIES`, `UID`, `PHOTO`) is destroyed by omission. A contact with no record yet
     * gets a fresh `manual:` one.
     *
     * ⚠ A person may have several source records; this writes the **first**, which is the
     * one the union shows first. There is no surface for editing a specific record among
     * several.
     */
    suspend fun saveContactDetails(
        personId: String,
        details: ContactDetails,
    ): RepositoryResult<Unit>

    /**
     * Import one or more contacts from `.vcf` text. Returns how many cards were created.
     *
     * Takes text, not a file, so any platform's picker can feed it.
     *
     * ⚠ Each card becomes a **new** contact. Recognising that an import duplicates someone
     * you already have is merge (D7), which is user-assisted and late by design — silently
     * folding imports together is exactly what D7 forbids.
     */
    suspend fun importVcards(text: String): RepositoryResult<Int>

    /** The contact's records as `.vcf` text, ready to write out or share. */
    suspend fun exportVcard(personId: String): RepositoryResult<String>

    // ── re-verification ──────────────────────────────────────────────────────

    /**
     * The six digits to read to the contact (`GetContactSas`).
     *
     * `""` means the key is unknown or the realm is locked — render the absence, never a
     * placeholder that looks like a code.
     */
    suspend fun contactSas(fingerprint: String): RepositoryResult<String>

    /**
     * Record the user's verdict after they compared the code (Q2a).
     *
     * ⚠⚠ `verified = true` may only follow an explicit human confirmation that the digits
     * matched — any other trigger fabricates trust (`VerifyTenshuIdentity`, threat-model A24).
     * `false` un-verifies.
     */
    suspend fun setVerified(fingerprint: String, verified: Boolean): RepositoryResult<Unit>

    // ── invitations ──────────────────────────────────────────────────────────
    //
    // ⚠ Scoped to **bare** invites — `intentKind == ""`, the ones with no share attached.
    // Drive owns `SHARE_FOLDER` invites end to end, because its completion prompt chains
    // into the membership-grant offer and so needs Drive's file domain. See §9.1 of the proposal
    // for why the two apps partition the invite space instead of one hosting all of it.

    /** Invitations issued from this account that nobody has answered yet (`PENDING`). */
    fun outstandingInvites(): Flow<List<OutstandingInvite>>

    /**
     * Invitations somebody accepted and that are waiting to be saved as a contact
     * (`REDEEMED`). Synced, so any of the user's devices can confirm — which is also what
     * makes this a badge source rather than a notification.
     */
    fun pendingConfirmations(): Flow<List<InviteConfirmation>>

    /**
     * Mint a bare invitation and return its one-session link.
     *
     * ⚠ The link is returned, never stored: it is the only carrier of `K_link`
     * (`CreateContactInvite`). Lose this string and the only remedy is revoke-and-reissue.
     */
    suspend fun createBareInvite(): RepositoryResult<String>

    suspend fun revokeInvite(inviteId: String): RepositoryResult<Unit>

    /**
     * Finish a handshake: save the responder as a contact, or decline.
     *
     * [personId] empty + [newPersonName] set creates a new contact; [personId] set re-points
     * the key at an existing one (never transferring that contact's trust — verification
     * lives on the key). [sasConfirmed] may only be true when the user explicitly confirmed
     * the code (`CompleteContactHandshake`); it is never inferred.
     */
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
     * Whether reads are reaching Control's proxy: `null` until the first outcome, then
     * `true`/`false`.
     *
     * The app has no store of its own — every read crosses IPC — so an unreachable Control
     * (not installed, disabled, or Control-desktop not running) folds every read to empty
     * and the contact list is silently blank. This is the signal the shell needs to say
     * *why*, instead of telling someone with 200 contacts that they have none.
     *
     * The default is `true`: an in-process implementation (the mock, previews, tests) has
     * no Control to be unreachable.
     */
    fun controlReachable(): Flow<Boolean?> = flowOf(true)

    /**
     * *Why* Control is unreachable (`core-schema-skew-surface.md` SK-1b) — `null` while it is fine
     * or not yet known. [controlReachable] decides **whether** the banner shows; this decides what
     * it says, because a proxy that is answering and refusing on a schema version needs the user
     * to update something, and a dead channel does not.
     *
     * Defaults to a permanent `null`, which renders the generic unreachable copy.
     */
    fun controlUnreachableReason(): kotlinx.coroutines.flow.StateFlow<app.tenshu.reachability.UnreachableReason?> = app.tenshu.reachability.NO_UNREACHABLE_REASON

    /**
     * The user asked to check again ("Try again" on the checking gate's retry wall): re-probe Control
     * and re-read the realm now rather than at the next poll. A no-op without an engine.
     */
    fun recheckControl() = Unit
}
