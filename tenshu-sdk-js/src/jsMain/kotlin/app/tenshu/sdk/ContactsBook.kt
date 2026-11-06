@file:OptIn(ExperimentalJsExport::class, ExperimentalEncodingApi::class)

package app.tenshu.sdk

import app.tenshu.error.ErrorCopyKey
import app.tenshu.error.errorCopyKey
import app.tenshu.sdk.internal.failureResult
import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.tenshu.contact.ContactInvite
import org.tenshu.contact.ContactService
import org.tenshu.contact.ContactSource
import org.tenshu.contact.Person
import org.tenshu.contact.PersonAvatar
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.claimedFingerprint
import org.tenshu.contact.claimedName
import org.tenshu.contact.claimedSas
import org.tenshu.contact.common.ContactCard
import org.tenshu.contact.common.LabeledValue
import org.tenshu.contact.common.readContactCard
import org.tenshu.contact.createdAt
import org.tenshu.contact.displayName
import org.tenshu.contact.expiresAt
import org.tenshu.contact.fingerprint
import org.tenshu.contact.imageData
import org.tenshu.contact.intentKind
import org.tenshu.contact.inviteId
import org.tenshu.contact.personId
import org.tenshu.contact.rawVcard
import org.tenshu.contact.sourceKey
import org.tenshu.contact.status
import org.tenshu.contact.verification
import org.tenshu.core.Data
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.entity.Entity
import org.tenshu.core.fold
import org.tenshu.core.time.Instant
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/*
 * The Contacts **address book** on web (`web-app-suite.md` Wave 1, run A): the read side and the
 * row shapes behind `Contacts`' non-handshake ops. It mirrors `TenshuContactsRepository`
 * (`:contacts-shared-data`) call for call, without compiling that module to JS — the seam is
 * Compose-free but its join, ladder mapping and detail union are small enough that a second,
 * JS-shaped copy here is cheaper than a JS target on two app modules.
 *
 * ⚠⚠ That makes this the second reader of the same relations. The rules that matter are copied
 * with their reasons, and each has a contract test in `ContactsAddressBookTest`:
 *  - `personId == ""` is an *unlinked candidate*, never a person — neither keys nor sources with
 *    it attach to anyone;
 *  - verification **fails closed**: an unknown rung reads `UNVERIFIED`;
 *  - invites partition on `intentKind == ""` — Drive owns `SHARE_FOLDER` end to end.
 */

@JsExport
class ContactJs internal constructor(
    val personId: String,
    /** The petname. `""` is a legitimate value — the UI substitutes its own "unnamed". */
    val displayName: String,
    /** `notConnected` | `unverified` | `verified` | `needsReverification` (the Q2b rule). */
    val trust: String,
    val identities: Array<ContactIdentityJs>,
    /** The union of every source record's editable projection (Ph6). */
    val details: ContactDetailsJs,
    /** `none` | `present` | `tooLarge` | `unknownFormat` — why [avatarDataUrl] is or is not set. */
    val avatar: String,
    /** `data:image/…;base64,…`, only when [avatar] is `present`. */
    val avatarDataUrl: String?,
)

@JsExport
class ContactIdentityJs internal constructor(
    val fingerprint: String,
    /** What the key claims to be called — render as a *claim*, never as the petname. */
    val selfAssertedName: String,
    /** `UNVERIFIED` | `VERIFIED` | `ATTESTED`. An unknown stored rung arrives as `UNVERIFIED`. */
    val verification: String,
    val verifiedAtMillis: Double,
    val source: String,
)

@JsExport
class ContactDetailsJs internal constructor(
    val phones: Array<ContactFieldJs>,
    val emails: Array<ContactFieldJs>,
    val addresses: Array<ContactFieldJs>,
    val organisation: String,
    val note: String,
)

@JsExport
class ContactFieldJs internal constructor(
    val label: String,
    val value: String,
)

@JsExport
class OwnProfileJs internal constructor(
    val displayName: String,
    /** Always `""` today: there is no client-readable own-identity op (same as native). */
    val fingerprint: String,
)

@JsExport
class OutstandingInviteJs internal constructor(
    val inviteId: String,
    val createdAtMillis: Double,
    val expiresAtMillis: Double,
)

@JsExport
class InviteConfirmationJs internal constructor(
    val inviteId: String,
    /** The responder's self-asserted name — a *claim*, quoted. */
    val claimedName: String,
    /** The RAW fingerprint; pass it back unchanged as `completeHandshake`'s `shownFingerprint`. */
    val claimedFingerprint: String,
    val claimedSas: String,
)

/** The catalogue keys this surface mints itself; every other failure carries its derived key. */
internal object ContactsWebErrorKeys {
    /** Same value as `ContactsErrorKeys.NO_CONTACTS_IN_FILE`, so the native catalogue line applies. */
    val NO_CONTACTS_IN_FILE = ErrorCopyKey("error_contacts_none_in_file")
    val NOTHING_TO_EXPORT = ErrorCopyKey("error_contacts_nothing_to_export")
    /** Same value as `ContactsErrorKeys.PHOTO_UNAVAILABLE`: the export refuses to drop a photo. */
    val PHOTO_UNAVAILABLE = ErrorCopyKey("error_contacts_photo_unavailable")
    /** A `details` argument that is not the published shape — a caller bug, said loudly. */
    val INVALID_DETAILS = ErrorCopyKey("error_contacts_invalid_details")
    /** `relinkIdentity` on a fingerprint with no key row — the service says `false`, not an error. */
    val UNKNOWN_KEY = ErrorCopyKey("error_contacts_unknown_key")
}

/**
 * `{ ok: false, error: { kind, message } }` for a typed failure: `kind` is the catalogue key the UI
 * branches and localises on, `message` the diagnostic. Never the English as the thing to match.
 */
internal fun contactFailure(error: TError): dynamic =
    failureResult(error.errorCopyKey().value, error.toString())

internal fun contactFailure(key: ErrorCopyKey): dynamic = failureResult(key.value, null)

// ── the join ─────────────────────────────────────────────────────────────────

internal class PersonRow(val personId: String, val displayName: String)

internal class IdentityRow(val personId: String, val identity: ContactIdentityJs)

internal class SourceRow(val personId: String, val card: ContactCard)

/** The `Person` × keyring × sources × photo join, sorted by petname (the native seam's promise). */
internal fun joinContactRows(
    persons: List<PersonRow>,
    identities: List<IdentityRow>,
    sources: List<SourceRow>,
    avatars: Map<String, ByteArray>,
): List<ContactJs> {
    // ⚠ `personId == ""` is an unlinked candidate, not a person — see the file header.
    val keyrings = identities.filter { it.personId.isNotEmpty() }.groupBy({ it.personId }, { it.identity })
    val cards = sources.filter { it.personId.isNotEmpty() }.groupBy({ it.personId }, { it.card })
    return persons
        .map { person ->
            val keyring = keyrings[person.personId].orEmpty()
            val bytes = avatars[person.personId]
            val avatarState = avatarStateOf(bytes)
            ContactJs(
                personId = person.personId,
                displayName = person.displayName,
                trust = trustOf(keyring),
                identities = keyring.toTypedArray(),
                details = unionDetails(cards[person.personId].orEmpty()),
                avatar = avatarState,
                avatarDataUrl = if (avatarState == AVATAR_PRESENT) avatarDataUrl(bytes!!) else null,
            )
        }
        .sortedBy { it.displayName.lowercase() }
}

/** `contacts-app.md` Q2b — the same four answers `trustOf` gives natively. */
internal fun trustOf(keyring: List<ContactIdentityJs>): String = when {
    keyring.isEmpty() -> "notConnected"
    keyring.all { it.verification == UNVERIFIED } -> "unverified"
    keyring.any { it.verification == UNVERIFIED } -> "needsReverification"
    else -> "verified"
}

/**
 * `ContactItem.verified` for the handshake's `connectedContacts()`: true iff [trustOf] says
 * `verified` over the stored rungs. ⚠ Never `any { VERIFIED }` — that read a mixed keyring as
 * verified and an ATTESTED-only one as not (the run-A review finding).
 */
internal fun connectedVerified(storedRungs: List<String>): Boolean =
    trustOf(storedRungs.map { ContactIdentityJs("", "", verificationOf(it), 0.0, "") }) == "verified"

/** Fail closed: a rung this client does not know reads as `UNVERIFIED`, never as trusted. */
internal fun verificationOf(stored: String): String = when (stored) {
    TenshuIdentity.Verification.VERIFIED -> "VERIFIED"
    TenshuIdentity.Verification.ATTESTED -> "ATTESTED"
    else -> UNVERIFIED
}

private const val UNVERIFIED = "UNVERIFIED"

/** Phones / emails / addresses de-duplicated by value; organisation and note from the first record that has one. */
internal fun unionDetails(cards: List<ContactCard>): ContactDetailsJs {
    fun merge(select: (ContactCard) -> List<LabeledValue>): Array<ContactFieldJs> =
        cards.flatMap(select)
            .distinctBy { it.value.trim().lowercase() }
            .map { ContactFieldJs(it.label, it.value) }
            .toTypedArray()
    return ContactDetailsJs(
        phones = merge { it.phones },
        emails = merge { it.emails },
        addresses = merge { it.addresses },
        organisation = cards.firstOrNull { it.organisation.isNotEmpty() }?.organisation.orEmpty(),
        note = cards.firstOrNull { it.note.isNotEmpty() }?.note.orEmpty(),
    )
}

// ── avatar ───────────────────────────────────────────────────────────────────

internal const val AVATAR_PRESENT = "present"

/**
 * ⚠ The cap is the service's own write cap ([PersonAvatar.MAX_IMAGE_BYTES], 256 KiB): a row above
 * it did not come through `SetPersonAvatar`, and inlining it would put an unbounded string on the
 * bridge for every list emission. Over it, or in a format an `<img>` cannot be told the type of,
 * the photo is reported *as* withheld rather than as absent.
 */
internal fun avatarStateOf(bytes: ByteArray?): String = when {
    bytes == null || bytes.isEmpty() -> "none"
    bytes.size > PersonAvatar.MAX_IMAGE_BYTES -> "tooLarge"
    imageMimeOf(bytes) == null -> "unknownFormat"
    else -> AVATAR_PRESENT
}

internal fun avatarDataUrl(bytes: ByteArray): String =
    "data:${imageMimeOf(bytes)};base64,${Base64.encode(bytes)}"

/** Magic-byte sniff — the row stores bytes "in whatever format they arrived as" and no MIME. */
internal fun imageMimeOf(bytes: ByteArray): String? {
    fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
    return when {
        at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
        at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
        at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
        at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "image/webp"
        else -> null
    }
}

// ── reads ────────────────────────────────────────────────────────────────────

/**
 * A read's outcome on the Kotlin side. `TResult` is a schema composite and cannot carry a plain
 * Kotlin list, so the join works in this and converts to the JS envelope at the edge.
 */
internal sealed interface Read<out T> {
    class Ok<T>(val value: T) : Read<T>
    class Failed(val error: TError) : Read<Nothing>
}

private fun <E : Data, R> TResult<TList<E>>.rows(row: (E) -> R): Read<List<R>> =
    fold(onSuccess = { Read.Ok(it.toList().map(row)) }, onFailure = { Read.Failed(it) })

/**
 * Every contact, re-emitting on change. A failed relation read is a **failure**, not an empty book:
 * the native repository folds it to empty because its reachability banner explains the blank list,
 * and the web has no such banner yet.
 */
internal fun ContactService.contactsFlow(): Flow<Read<List<ContactJs>>> =
    combine(personsFlow(), identitiesFlow(), sourcesFlow(), avatarsFlow()) { p, i, s, a ->
        val failed = listOf(p, i, s, a).filterIsInstance<Read.Failed>().firstOrNull()
        failed ?: Read.Ok(
            joinContactRows(
                persons = (p as Read.Ok).value,
                identities = (i as Read.Ok).value,
                sources = (s as Read.Ok).value,
                avatars = (a as Read.Ok).value.toMap(),
            ),
        )
    }

private fun ContactService.personsFlow(): Flow<Read<List<PersonRow>>> =
    persons
        .select { fields(Person.Field.PERSON_ID, Person.Field.DISPLAY_NAME) }
        .flow()
        .map { r -> r.rows { PersonRow(it.personId.get(), it.displayName.get()) } }

private fun ContactService.identitiesFlow(): Flow<Read<List<IdentityRow>>> =
    tenshuIdentities
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
        .map { r -> r.rows { IdentityRow(it.personId.get(), it.toIdentityJs()) } }

private fun ContactService.sourcesFlow(): Flow<Read<List<SourceRow>>> =
    sources
        .select {
            fields(ContactSource.Field.SOURCE_KEY, ContactSource.Field.PERSON_ID, ContactSource.Field.RAW_VCARD)
        }
        .flow()
        .map { r -> r.rows { SourceRow(it.personId.get(), readContactCard(it.rawVcard.get())) } }

/** Photo bytes by `personId`, as pairs; an unlinked row (`personId == ""`) belongs to nobody. */
private fun ContactService.avatarsFlow(): Flow<Read<List<Pair<String, ByteArray>>>> =
    avatars
        .select { fields(PersonAvatar.Field.PERSON_ID, PersonAvatar.Field.IMAGE_DATA) }
        .flow()
        .map { r ->
            when (val read = r.rows { it.personId.get() to it.imageData.get() }) {
                is Read.Ok -> Read.Ok(read.value.filter { it.first.isNotEmpty() })
                is Read.Failed -> read
            }
        }

private fun Entity<out TenshuIdentity>.toIdentityJs(): ContactIdentityJs = ContactIdentityJs(
    fingerprint = fingerprint.get(),
    selfAssertedName = getFieldValue<TString>(TenshuIdentity.Field.SELF_ASSERTED_NAME).get(),
    verification = verificationOf(verification.get()),
    verifiedAtMillis = getFieldValue<Instant>(TenshuIdentity.Field.VERIFIED_AT).get().toDouble(),
    source = getFieldValue<TString>(TenshuIdentity.Field.SOURCE).get(),
)

/** `(sourceKey, rawVcard)` for a person, in relation order — a failed read is a failure here too. */
internal suspend fun ContactService.sourceRecordsOf(personId: String): Read<List<Pair<String, String>>> =
    when (
        val read = sources
            .select {
                fields(ContactSource.Field.SOURCE_KEY, ContactSource.Field.PERSON_ID, ContactSource.Field.RAW_VCARD)
            }
            .getAll()
            .rows { Triple(it.personId.get(), it.sourceKey.get(), it.rawVcard.get()) }
    ) {
        is Read.Ok -> Read.Ok(read.value.filter { it.first == personId }.map { it.second to it.third })
        is Read.Failed -> read
    }

/**
 * `ContactInvite` rows at [status] whose `intentKind` is bare. ⚠ The filter is what keeps the
 * Contacts app and Drive from prompting for the same invite (`contacts-app.md` §9.1).
 */
internal suspend fun <R> ContactService.bareInvites(
    status: String,
    row: (Entity<out ContactInvite>) -> R,
): Read<List<R>> =
    when (
        val read = contactInvites
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
            .getAll()
            .rows { it }
    ) {
        is Read.Ok -> Read.Ok(
            read.value.filter { it.status.get() == status && it.intentKind.get().isEmpty() }.map(row),
        )
        is Read.Failed -> read
    }

internal fun Entity<out ContactInvite>.toOutstandingJs() = OutstandingInviteJs(
    inviteId = inviteId.get(),
    createdAtMillis = createdAt.get().toDouble(),
    expiresAtMillis = expiresAt.get().toDouble(),
)

internal fun Entity<out ContactInvite>.toConfirmationJs() = InviteConfirmationJs(
    inviteId = inviteId.get(),
    claimedName = claimedName.get(),
    claimedFingerprint = claimedFingerprint.get(),
    claimedSas = claimedSas.get(),
)

// ── result envelopes ─────────────────────────────────────────────────────────

internal fun contactsResult(read: Read<List<ContactJs>>): dynamic = when (read) {
    is Read.Ok -> okResult { it.contacts = read.value.toTypedArray() }
    is Read.Failed -> contactFailure(read.error)
}

/** ⚠ `contact: null` is "no such person"; `ok: false` is "could not read". Never `{}`. */
internal fun contactResult(read: Read<List<ContactJs>>, personId: String): dynamic = when (read) {
    is Read.Ok -> okResult { it.contact = read.value.firstOrNull { c -> c.personId == personId } }
    is Read.Failed -> contactFailure(read.error)
}

// ── details input ────────────────────────────────────────────────────────────

/**
 * The JS `{ phones, emails, addresses, organisation, note }` → the codec's card, or `null` when a
 * field is not the published shape. ⚠ Strict on purpose: a missing `phones` read as "no phones"
 * would *delete* every number on save, since the save merges the edited projection in.
 */
internal fun contactCardOf(details: dynamic, formattedName: String): ContactCard? {
    if (details == null || jsTypeOf(details) != "object") return null
    fun fields(value: dynamic): List<LabeledValue>? {
        if (!js("Array").isArray(value).unsafeCast<Boolean>()) return null
        val array = value.unsafeCast<Array<dynamic>>()
        return array.map { item ->
            if (item == null || jsTypeOf(item.label) != "string" || jsTypeOf(item.value) != "string") return null
            LabeledValue(item.label.unsafeCast<String>(), item.value.unsafeCast<String>())
        }
    }
    val phones = fields(details.phones) ?: return null
    val emails = fields(details.emails) ?: return null
    val addresses = fields(details.addresses) ?: return null
    if (jsTypeOf(details.organisation) != "string" || jsTypeOf(details.note) != "string") return null
    return ContactCard(
        formattedName = formattedName,
        phones = phones,
        emails = emails,
        addresses = addresses,
        organisation = details.organisation.unsafeCast<String>(),
        note = details.note.unsafeCast<String>(),
    )
}
