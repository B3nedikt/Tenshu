@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import org.tenshu.core.InvalidParameter
import app.tenshu.error.ErrorCopyKey
import app.tenshu.error.errorCopyKey

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.failureResult
import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.tenshu.contact.ContactService
import org.tenshu.contact.ContactSource
import org.tenshu.contact.clearPersonAvatar
import org.tenshu.contact.client.FullCardRead
import org.tenshu.contact.client.fullVcard
import org.tenshu.contact.common.mergeIntoVCard
import org.tenshu.contact.common.newVCard
import org.tenshu.contact.common.readContactCard
import org.tenshu.contact.common.splitVCards
import org.tenshu.contact.createPerson
import org.tenshu.contact.deletePerson
import org.tenshu.contact.getContactSas
import org.tenshu.contact.importContact
import org.tenshu.contact.linkTenshuIdentity
import org.tenshu.contact.mergePersons
import org.tenshu.contact.renamePerson
import org.tenshu.contact.saveContactSource
import org.tenshu.contact.unlinkTenshuIdentity
import org.tenshu.contact.verifyTenshuIdentity
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.preference.OwnerPreferences
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.read
import org.tenshu.preference.client.readOnce
import org.tenshu.preference.client.set
import org.tenshu.contact.CompleteContactHandshake
import org.tenshu.contact.ContactInvite
import org.tenshu.contact.Person
import org.tenshu.contact.RedeemContactInvite
import org.tenshu.contact.TenshuIdentity
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
import org.tenshu.contact.fingerprint
import org.tenshu.contact.grantSeed
import org.tenshu.contact.intentKind
import org.tenshu.contact.intentLabel
import org.tenshu.contact.inviteId
import org.tenshu.contact.listPendingRedemptions
import org.tenshu.contact.personId
import org.tenshu.contact.redeemContactInvite
import org.tenshu.contact.revokeContactInvite
import org.tenshu.contact.status
import org.tenshu.contact.verification
import org.tenshu.core.GenericError
import org.tenshu.core.TError
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.fold
import org.tenshu.core.getOrNull
import kotlin.js.Promise

/**
 * Web rendering of the link-only contact-invitation handshake
 * (`drive-web-mode-b-sharing.md` MB-1). The thin `@JsExport` *operation*
 * surface (House Rule #5): each call dispatches a `:contact-service` op through
 * the in-process [JsRuntime] proxy — exactly the same op code natives run. All
 * orchestration (own display name, link parsing surfaces, the inviter completion
 * prompt, the invitee await loop) lives in the React/TS layer, which owns state.
 *
 * Form factor (doc §1): web v1 is a **deep-link invite → `UNVERIFIED` (TOFU)
 * contact → explicit grant**. No camera / QR scan / SAS here — the link's
 * `K_link` rides the URL fragment and never reaches the relay. The verify
 * upgrade (camera-QR + SAS) is MB-5, additive.
 */
@JsExport
object Contacts {

    /**
     * Mints a contact invite — the one-session messaging link
     * (`https://tenshu.app/i#v1.<base64url(inviteId ‖ K_link ‖ a13Pub)>`) the inviter copies /
     * `navigator.share`s / renders as a QR. Never persisted.
     *
     * Resolves `{ ok: true, link }` or `{ ok: false, error }` (locked realm / no A13 yet /
     * transport).
     *
     * ⚠ Unlike the reads on this surface, `null` here only ever meant failure — success always
     * produces a link. So this is not the two-meanings collapse; it is the weaker version, a
     * failure with the reason thrown away. Worth carrying anyway: an invite that cannot be minted
     * is a dead end for the user, and "why" is the difference between a retry and a support
     * thread. Pairing is where this project's hardest bugs have lived.
     */
    fun createInvite(intentKind: String, intentLabel: String): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.contactService
                .createContactInvite(intentKind, intentLabel)
                .fold(
                    onSuccess = { link -> okResult { it.link = link.get() } },
                    onFailure = { error -> failureResult("invite", error.toString()) },
                )
        }

    /** Revoke an unredeemed invite (burns the relay slot). */
    fun revokeInvite(inviteId: String): Promise<Boolean> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.contactService.revokeContactInvite(inviteId)
                .fold(onSuccess = { true }, onFailure = { false })
        }

    /**
     * Redeem-on-open: the joiner opened an invite link. Parses + runs the
     * sealed-box redemption over the `/sync/invite` mailbox (the fragment stays local —
     * the relay never sees `K_link`). The typed [RedeemResult.outcome]
     * preserves the T-CI-01 loud-copy cases: `alreadyUsed` (the single response
     * slot was taken) and `gone` (expired / revoked) must not collapse into a
     * generic failure.
     */
    fun redeemInvite(link: String, selfAssertedName: String): Promise<RedeemResult> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.contactService.redeemContactInvite(link, selfAssertedName).fold(
                onSuccess = { RedeemResult(outcome = "redeemed", inviteId = it.get(), message = null) },
                onFailure = { error ->
                    // ⚠⚠ Typed reason, not a substring of English (§5.6). `message` stays on the
                    // JS-facing shape but now carries the **catalogue key**, so the React layer
                    // looks it up in its i18next catalogue rather than printing the proxy's
                    // sentence — the n=2 "merge, not rewrite" of ADR 0016 anchor #8.
                    val reason = (error as? InvalidParameter)
                        ?.takeIf { it.name.get() == RedeemContactInvite.Params.LINK_PAYLOAD }
                        ?.reason?.get()
                    val outcome = when (reason) {
                        InvalidParameter.Reasons.ALREADY_CONSUMED -> "alreadyUsed"
                        InvalidParameter.Reasons.GONE -> "gone"
                        else -> "failed"
                    }
                    RedeemResult(outcome = outcome, inviteId = null, message = error.errorCopyKey().value)
                },
            )
        }

    /**
     * Invitee-side: one slow-poll budget of the ack wait (≈1 h by default).
     * Safe to re-invoke while [InviteAck.status] is `PENDING`. [sasConfirmed]
     * stays false on the v1 link path (no SAS); MB-5 flips it on a confirmed
     * 6-digit code so the inviter identity is minted `VERIFIED`/`QR_SAS`.
     */
    fun awaitAck(inviteId: String, sasConfirmed: Boolean): Promise<InviteAck> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.contactService.awaitInviteAck(inviteId, sasConfirmed).await().fold(
                onSuccess = { ack ->
                    InviteAck(
                        status = ack.status.get(),
                        peerName = ack.peerName.get(),
                        peerFingerprint = ack.peerFingerprint.get(),
                        intentKind = ack.intentKind.get(),
                        intentLabel = ack.intentLabel.get(),
                        sas = ack.sas.get(),
                    )
                },
                onFailure = { InviteAck(status = "INVALID", "", "", "", "", "") },
            )
        }

    /**
     * Inviter-side: respond to a `REDEEMED` invite. On accept, links the
     * responder to a new (or existing) `Person` and posts the sealed ack that
     * closes the handshake; both sides end with an `UNVERIFIED` contact.
     *
     * ⚠⚠ Resolves a typed [CompleteResult], not a boolean. It used to be `true`/`false`, and the
     * web prompt answered every `false` with "try again" — which cannot fix threat model
     * T-CI-10's refusals: when the reply on the relay is not the one the prompt showed, the only
     * way forward is to decline and send a new invite, and retrying refuses identically. Same
     * shape as [redeemInvite]: a typed `outcome` to branch on, the catalogue key in `message`.
     */
    fun completeHandshake(
        inviteId: String,
        accept: Boolean,
        shownFingerprint: String,
        personId: String,
        newPersonName: String,
        selfAssertedName: String,
        sasConfirmed: Boolean,
    ): Promise<CompleteResult> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.contactService.completeContactHandshake(
                inviteId = inviteId,
                decision = if (accept) CompleteContactHandshake.Decision.ACCEPT
                else CompleteContactHandshake.Decision.REJECT,
                // The raw claimed fingerprint the prompt displayed (contact-service v2, T-CI-10).
                shownFingerprint = shownFingerprint,
                personId = personId,
                newPersonName = newPersonName,
                selfAssertedName = selfAssertedName,
                sasConfirmed = sasConfirmed,
            ).fold(
                onSuccess = { CompleteResult(outcome = "completed", message = null) },
                onFailure = { error -> completeResultOf(error) },
            )
        }

    /**
     * Snapshot of the inviter's [ContactInvite] rows (synced, Eager). The React
     * layer refetches on the TanStack-Query cadence to surface a fresh
     * `REDEEMED` invite — the cue to show the completion prompt.
     */
    fun listInvites(): Promise<Array<ContactInviteItem>> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.contactService.contactInvites
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
                    )
                }
                .getAll()
                .getOrNull()
                ?.toList()
                ?.map { entity ->
                    ContactInviteItem(
                        inviteId = entity.inviteId.get(),
                        status = entity.status.get(),
                        intentKind = entity.intentKind.get(),
                        intentLabel = entity.intentLabel.get(),
                        createdAtMillis = entity.createdAt.get().toDouble(),
                        expiresAtMillis = entity.expiresAt.get().toDouble(),
                        claimedName = entity.claimedName.get(),
                        claimedFingerprint = entity.claimedFingerprint.get(),
                        claimedSas = entity.claimedSas.get(),
                    )
                }
                ?.sortedByDescending { it.createdAtMillis }
                ?.toTypedArray()
                ?: emptyArray()
        }

    /**
     * Connected contacts (a `Person` with ≥1 linked `TenshuIdentity`) — the
     * read MB-2's grant picker consumes. [ContactItem.grantable] is true when a
     * linked identity carries a `grantSeed` (only post-GR-3 handshakes are
     * grantable; the grant + accept address derivations both need that seed).
     */
    fun connectedContacts(): Promise<Array<ContactItem>> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            val service = JsRuntime.tenshuClient.contactService
            val persons = service.persons
                .select { fields(Person.Field.PERSON_ID, Person.Field.DISPLAY_NAME) }
                .getAll()
                .getOrNull()
                ?.toList()
                ?: return@promise emptyArray<ContactItem>()
            val identities = service.tenshuIdentities
                .select {
                    fields(
                        TenshuIdentity.Field.PERSON_ID,
                        TenshuIdentity.Field.FINGERPRINT,
                        TenshuIdentity.Field.GRANT_SEED,
                        TenshuIdentity.Field.VERIFICATION,
                    )
                }
                .getAll()
                .getOrNull()
                ?.toList()
                .orEmpty()
            val byPerson = identities.groupBy { it.personId.get() }
            persons.mapNotNull { person ->
                val personId = person.personId.get()
                val keyring = byPerson[personId] ?: return@mapNotNull null // not yet connected
                val grantableIdentity = keyring.firstOrNull { it.grantSeed.get().isNotEmpty() }
                val primary = grantableIdentity ?: keyring.first()
                ContactItem(
                    personId = personId,
                    displayName = person.displayName.get(),
                    fingerprint = primary.fingerprint.get(),
                    grantable = grantableIdentity != null,
                    // ⚠⚠ The Q2b rule, not "any key is VERIFIED": a mixed keyring is
                    // `needsReverification` (one key nobody compared), and an ATTESTED-only
                    // keyring IS verified. Same `trustOf` the address book uses, so Drive's
                    // picker and the Contacts app can never disagree about a checkmark.
                    verified = connectedVerified(keyring.map { it.verification.get() }),
                )
            }.toTypedArray()
        }

    /**
     * Resumable invitee-side redemptions still awaiting an ack (the §3.2.4
     * restart path): a device that redeemed then reloaded can resume the await
     * loop. `sas` is `""` while the realm is locked.
     */
    fun pendingRedemptions(): Promise<Array<PendingRedemptionItem>> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.contactService.listPendingRedemptions().fold(
                onSuccess = { rows ->
                    rows.toList().map { row ->
                        PendingRedemptionItem(
                            inviteId = row.inviteId.get(),
                            redeemedAtMillis = row.redeemedAt.get().toDouble(),
                            peerFingerprint = row.peerFingerprint.get(),
                            sas = row.sas.get(),
                        )
                    }.toTypedArray()
                },
                onFailure = { emptyArray() },
            )
        }

    // ── the address book (web-app-suite.md Wave 1, run A) ────────────────────
    //
    // Mirrors `ContactsRepository` / `TenshuContactsRepository` op for op. Every write resolves
    // `{ ok: true, … }` or `{ ok: false, error: { kind, message } }`, where `kind` is the catalogue
    // key (`error_…`) the UI branches and localises on — the same key the native app renders.
    // Reads that are Flows natively get an `observe…` push form beside the one-shot.

    /** Every contact, sorted by petname: `{ ok: true, contacts }` or `{ ok: false, error }`. */
    fun contacts(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        contactsResult(JsRuntime.tenshuClient.contactService.contactsFlow().first())
    }

    /**
     * Pushes the [contacts] envelope on every change until the returned lambda is called.
     *
     * ⚠ [onError] is terminal (see [observeJob]): it fires once, with a diagnostic, when the stream
     * can no longer deliver — and nothing is pushed after it.
     */
    fun observeContacts(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        observeJob(onError) {
            JsRuntime.tenshuClient.contactService.contactsFlow().collect { callback(contactsResult(it)) }
        }

    /**
     * One contact: `{ ok: true, contact }` with `contact` **null** when no such person exists, or
     * `{ ok: false, error }` when the read failed. Derived from the whole book, as natively.
     */
    fun contact(personId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        contactResult(JsRuntime.tenshuClient.contactService.contactsFlow().first(), personId)
    }

    fun observeContact(personId: String, callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        observeJob(onError) {
            JsRuntime.tenshuClient.contactService.contactsFlow().collect {
                callback(contactResult(it, personId))
            }
        }

    /** The user's own card (D6). Cannot fail: an absent or undecodable row is the default `""`. */
    fun ownProfile(): Promise<OwnProfileJs> = GlobalScope.promise {
        JsRuntime.awaitReady()
        OwnProfileJs(JsRuntime.tenshuClient.preferenceService.readOnce(OwnerPreferences.Name), "")
    }

    /**
     * ⚠ Seeds with a one-shot read first: `PreferenceService.read` emits **nothing** on a realm that
     * has never held the row, so a bare collect would leave a fresh browser's profile card waiting
     * forever rather than showing an empty name.
     */
    fun observeOwnProfile(callback: (OwnProfileJs) -> Unit, onError: (String) -> Unit): () -> Unit =
        observeJob(onError) {
            val prefs = JsRuntime.tenshuClient.preferenceService
            callback(OwnProfileJs(prefs.readOnce(OwnerPreferences.Name), ""))
            prefs.read(OwnerPreferences.Name).collect { callback(OwnProfileJs(it, "")) }
        }

    fun setOwnDisplayName(name: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.tenshuClient.preferenceService.set(OwnerPreferences.Name, name)
            .fold(onSuccess = { okResult() }, onFailure = { contactFailure(it) })
    }

    /** A contact with a petname and no key (D5): `{ ok: true, personId }`. */
    fun createContact(displayName: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.tenshuClient.contactService.createPerson(displayName).fold(
            onSuccess = { id -> okResult { it.personId = id.get() } },
            onFailure = { contactFailure(it) },
        )
    }

    /** ⚠ An unknown `personId` is a failure (the service refuses it), never a silent no-op. */
    fun renameContact(personId: String, displayName: String): Promise<dynamic> = write {
        renamePerson(personId, displayName)
    }

    /**
     * Deletes the contact and every key, record and photo linked to it (Q7a).
     *
     * ⚠⚠ **This revokes nothing** — shared-realm access is keyed by the member's identity, not by
     * `personId`. The calling UI must say so before invoking this (Q7b).
     */
    fun deleteContact(personId: String): Promise<dynamic> = write { deletePerson(personId) }

    /** Drop one key from its contact's keyring; the contact stays. */
    fun unlinkIdentity(fingerprint: String): Promise<dynamic> = write { unlinkTenshuIdentity(fingerprint) }

    /**
     * Fold [sourcePersonId] into [targetPersonId] (D7). ⚠⚠ Only on an explicit user confirmation —
     * and there is no un-merge, so the confirmation must show what is about to be combined.
     */
    fun mergeContacts(sourcePersonId: String, targetPersonId: String): Promise<dynamic> = write {
        mergePersons(sourcePersonId, targetPersonId)
    }

    /**
     * Re-point one key at another contact. ⚠ Verification stays on the key; it never moves.
     *
     * ⚠ The service answers an unknown key with `false`, not an error — surfaced here as
     * `error_contacts_unknown_key`, because a relink that moved nothing is not a success.
     */
    fun relinkIdentity(fingerprint: String, personId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.tenshuClient.contactService.linkTenshuIdentity(fingerprint, personId).fold(
            onSuccess = { moved -> if (moved.get()) okResult { it.changed = true } else contactFailure(ContactsWebErrorKeys.UNKNOWN_KEY) },
            onFailure = { contactFailure(it) },
        )
    }

    /** "Stop showing me that picture" — the card's own `PHOTO` is untouched. */
    fun clearAvatar(personId: String): Promise<dynamic> = write { clearPersonAvatar(personId) }

    /**
     * Save the editable field set (Q6): [details] is `{ phones, emails, addresses: {label, value}[],
     * organisation, note }`, every key required.
     *
     * ⚠⚠ **Merges** into the contact's first record — never regenerates it — so every property the
     * editor does not model survives. A contact with no record gets a fresh `manual:` one.
     */
    fun saveContactDetails(personId: String, details: dynamic): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val service = JsRuntime.tenshuClient.contactService
        val existing = when (val read = service.sourceRecordsOf(personId)) {
            is Read.Ok -> read.value.firstOrNull()
            // ⚠ Not "no record yet": writing a fresh `manual:` card over a record we merely
            // failed to read would orphan it from the editor.
            is Read.Failed -> return@promise contactFailure(read.error)
        }
        // Keep the record's own FN: the petname lives on `Person`, not here.
        val card = contactCardOf(details, existing?.let { readContactCard(it.second).formattedName }.orEmpty())
            ?: return@promise contactFailure(ContactsWebErrorKeys.INVALID_DETAILS)
        val sourceKey = existing?.first ?: "${ContactSource.Origin.MANUAL}:$personId"
        val merged = existing?.let { mergeIntoVCard(it.second, card) } ?: newVCard(card)
        service.saveContactSource(sourceKey, personId, merged)
            .fold(onSuccess = { okResult() }, onFailure = { contactFailure(it) })
    }

    /**
     * Import `.vcf` text: `{ ok: true, imported, cards }`. Each card becomes a **new** contact (D7
     * forbids folding imports together). Partial success is a count, not a rollback — `imported <
     * cards` means some were refused. No card at all is `error_contacts_none_in_file`.
     */
    fun importVcards(text: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val cards = splitVCards(text)
        if (cards.isEmpty()) return@promise contactFailure(ContactsWebErrorKeys.NO_CONTACTS_IN_FILE)
        val service = JsRuntime.tenshuClient.contactService
        val imported = cards.count { card ->
            service.importContact(card, fallbackName = "").fold(onSuccess = { true }, onFailure = { false })
        }
        okResult {
            it.imported = imported
            it.cards = cards.size
        }
    }

    /**
     * The contact's records as `.vcf` text: `{ ok: true, vcard }`. Stored verbatim, so this is
     * concatenation. A person with no record (or no such person) is `error_contacts_nothing_to_export`.
     */
    fun exportVcard(personId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val service = JsRuntime.tenshuClient.contactService
        when (val read = service.sourceRecordsOf(personId)) {
            is Read.Failed -> contactFailure(read.error)
            is Read.Ok -> {
                if (read.value.isEmpty()) return@promise contactFailure(ContactsWebErrorKeys.NOTHING_TO_EXPORT)
                // ⚠⚠ Reassembled with the card's content-plane photos (`contacts-app.md` §9.10); a
                // card whose photos cannot be fetched fails the export, never ships without them.
                val cards = read.value.map { (sourceKey, stored) ->
                    when (val full = service.fullVcard(sourceKey, stored)) {
                        is FullCardRead.Ok -> full.card
                        FullCardRead.PhotosUnavailable -> return@promise contactFailure(ContactsWebErrorKeys.PHOTO_UNAVAILABLE)
                        is FullCardRead.Failed -> return@promise contactFailure(full.error)
                    }
                }
                okResult { it.vcard = cards.joinToString("") }
            }
        }
    }

    /**
     * The six digits to read to the contact: `{ ok: true, sas }`. ⚠ `sas == ""` means the key is
     * unknown or the realm is locked — render the absence, never a placeholder that looks like a code.
     */
    fun contactSas(fingerprint: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.tenshuClient.contactService.getContactSas(fingerprint).fold(
            onSuccess = { sas -> okResult { it.sas = sas.get() } },
            onFailure = { contactFailure(it) },
        )
    }

    /**
     * ⚠⚠ `verified = true` may only follow the user's explicit "the digits match" — nothing here is a
     * cryptographic check, and any other trigger fabricates trust (threat model A24).
     *
     * `{ ok: true, changed: false }` means nothing was written: the key was already at that rung, or
     * it is `ATTESTED`, which a local verdict may neither claim nor overwrite. An unknown key fails.
     */
    fun setVerified(fingerprint: String, verified: Boolean): Promise<dynamic> = write {
        verifyTenshuIdentity(fingerprint, verified)
    }

    /** Bare invites nobody has answered yet (`PENDING`): `{ ok: true, invites }`. */
    fun outstandingInvites(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        when (val read = JsRuntime.tenshuClient.contactService.bareInvites(ContactInvite.Status.PENDING) { it.toOutstandingJs() }) {
            is Read.Ok -> okResult { it.invites = read.value.toTypedArray() }
            is Read.Failed -> contactFailure(read.error)
        }
    }

    /** Bare invites someone accepted, waiting to be saved as a contact (`REDEEMED`). */
    fun pendingConfirmations(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        when (val read = JsRuntime.tenshuClient.contactService.bareInvites(ContactInvite.Status.REDEEMED) { it.toConfirmationJs() }) {
            is Read.Ok -> okResult { it.confirmations = read.value.toTypedArray() }
            is Read.Failed -> contactFailure(read.error)
        }
    }

    /**
     * Runs one observe collector until the returned lambda cancels it.
     *
     * ⚠⚠ Before this, a throw from the flow — or from [body]'s callback, e.g. the gateway's
     * `postMessage` refusing to clone a value — killed the job silently, and the subscriber's UI
     * froze on its last value with no sign anything was wrong. Now every way the stream stops
     * other than the subscriber's own cancel reaches [onError], exactly once: a throw carries its
     * message, and a flow that simply *completes* says so — a relation flow never ends on its own,
     * so an end is a failure to the subscriber, not a quiet success.
     */
    internal fun observeJob(onError: (String) -> Unit, body: suspend () -> Unit): () -> Unit {
        val job = GlobalScope.launch {
            val failure = try {
                JsRuntime.awaitReady()
                body()
                OBSERVE_ENDED
            } catch (e: CancellationException) {
                throw e // the subscriber's own unsubscribe — not a failure
            } catch (e: Throwable) {
                e.message ?: e.toString()
            }
            onError(failure)
        }
        return { job.cancel() }
    }

    /**
     * A `TBoolean`-returning service write → `{ ok: true, changed }` or the typed failure. `changed`
     * is the service's own answer, carried rather than dropped so a no-op is visible.
     */
    private fun write(op: suspend ContactService.() -> TResult<TBoolean>): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.tenshuClient.contactService.op().fold(
            onSuccess = { changed -> okResult { it.changed = changed.get() } },
            onFailure = { contactFailure(it) },
        )
    }

    private fun TError.text(): String =
        (this as? GenericError)?.message?.get() ?: toString()
}

/** The terminal diagnostic for an observe stream that completed rather than failed. */
internal const val OBSERVE_ENDED = "contacts stream ended"

@JsExport
class RedeemResult internal constructor(
    /** One of: `redeemed` | `alreadyUsed` | `gone` | `failed`. */
    val outcome: String,
    val inviteId: String?,
    val message: String?,
)

/**
 * The failure half of `Contacts.completeHandshake` — split out so the mapping is testable without
 * driving a refusal end to end. Recognised by type (`CompleteContactHandshake.Refusal`), never by
 * reading a message.
 */
internal fun completeResultOf(error: TError): CompleteResult = CompleteResult(
    outcome = when {
        CompleteContactHandshake.Refusal.isResponseChanged(error) -> "responseChanged"
        CompleteContactHandshake.Refusal.isCodeNotShown(error) -> "codeNotShown"
        else -> "failed"
    },
    message = when {
        CompleteContactHandshake.Refusal.isResponseChanged(error) -> ErrorCopyKey.INVITE_RESPONSE_CHANGED
        CompleteContactHandshake.Refusal.isCodeNotShown(error) -> ErrorCopyKey.INVITE_CODE_NOT_SHOWN
        else -> error.errorCopyKey()
    }.value,
)

@JsExport
class CompleteResult internal constructor(
    /**
     * One of: `completed` | `responseChanged` | `codeNotShown` | `failed`.
     *
     * `responseChanged` (threat model T-CI-10): the reply this invite holds is not the one the
     * prompt showed, so nothing was saved — the user must decline and send a new invite; a retry
     * refuses the same way. `codeNotShown`: a "codes match" confirmation for an invite no code was
     * shown for yet.
     */
    val outcome: String,
    /** The catalogue key for the failure (`error_…`), or `null` on success. */
    val message: String?,
)

@JsExport
class InviteAck internal constructor(
    /** One of `AwaitInviteAck.Result`: ACCEPTED | REJECTED | PENDING | GONE | INVALID. */
    val status: String,
    val peerName: String,
    val peerFingerprint: String,
    val intentKind: String,
    val intentLabel: String,
    /** Proxy-derived §5.3 SAS code; `""` unless ACCEPTED (and only meaningful on the QR path). */
    val sas: String,
)

@JsExport
class ContactInviteItem internal constructor(
    val inviteId: String,
    /** One of `ContactInvite.Status`: PENDING | REDEEMED | COMPLETED | REJECTED | REVOKED | EXPIRED | COMPROMISED. */
    val status: String,
    val intentKind: String,
    val intentLabel: String,
    val createdAtMillis: Double,
    val expiresAtMillis: Double,
    /** The responder's self-asserted name — render as a *claim*, quoted. */
    val claimedName: String,
    val claimedFingerprint: String,
    val claimedSas: String,
)

@JsExport
class ContactItem internal constructor(
    val personId: String,
    val displayName: String,
    val fingerprint: String,
    val grantable: Boolean,
    /** True once the contact cleared the §5.3 SAS verify (MB-5); false = UNVERIFIED (TOFU). */
    val verified: Boolean,
)

@JsExport
class PendingRedemptionItem internal constructor(
    val inviteId: String,
    val redeemedAtMillis: Double,
    val peerFingerprint: String,
    val sas: String,
)
