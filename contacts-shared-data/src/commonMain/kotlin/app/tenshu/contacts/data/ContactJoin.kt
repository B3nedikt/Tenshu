package app.tenshu.contacts.data

import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.ContactDetails
import app.tenshu.contacts.model.ContactIdentity
import app.tenshu.contacts.model.unionDetails
import app.tenshu.contacts.model.Verification
import org.tenshu.contact.TenshuIdentity

/**
 * The `Person` × `TenshuIdentity` join, kept pure so the interesting behaviour is testable
 * without standing up a proxy. `:contact-service` stores the two as separate relations
 * linked by a `personId` back-link ("link/unlink is a field write, never array surgery"),
 * so assembling the contact list is this function's whole job.
 *
 * @param persons keyring-less [Contact]s — `personId` + petname straight off the `Person`
 *   rows.
 * @param identities each keyring entry paired with the `personId` it back-links to.
 * @param sources each source record's projection paired with its `personId`; unioned per
 *   person into the read-through detail (Ph6).
 * @param avatars photo bytes by `personId` (Ph8) — at most one row per person, so a plain
 *   map rather than the grouped shape the other two need.
 */
internal fun joinContacts(
    persons: List<Contact>,
    identities: List<Pair<String, ContactIdentity>>,
    sources: List<Pair<String, ContactDetails>> = emptyList(),
    avatars: Map<String, ByteArray> = emptyMap(),
): List<Contact> {
    // ⚠ `personId == ""` is an *unlinked candidate*, not a person. Grouping it like any
    // other back-link would either attach every unlinked key to one phantom contact or,
    // worse, to a real `Person` whose id happened to be empty.
    val keyrings = identities
        .filter { (personId, _) -> personId.isNotEmpty() }
        .groupBy({ it.first }, { it.second })

    // Same rule for sources: an unlinked record belongs to nobody until it is linked.
    val detailsByPerson = sources
        .filter { (personId, _) -> personId.isNotEmpty() }
        .groupBy({ it.first }, { it.second })

    return persons
        .map { person ->
            person.copy(
                identities = keyrings[person.personId].orEmpty(),
                details = unionDetails(detailsByPerson[person.personId].orEmpty()),
                avatar = avatars[person.personId],
            )
        }
        // The seam promises a sorted list so the order is the repository's answer rather
        // than each caller's.
        .sortedBy { it.displayName.lowercase() }
}

/**
 * The stored verification string → the ladder.
 *
 * **Fail closed.** An unrecognised value reads as [Verification.UNVERIFIED], never as
 * verified: a client that does not understand a rung must under-claim trust, not over-claim
 * it. The cost of being wrong here is a user re-comparing six digits; the cost of the other
 * direction is a checkmark on a key nobody checked.
 */
internal fun verificationOf(stored: String): Verification = when (stored) {
    TenshuIdentity.Verification.VERIFIED -> Verification.VERIFIED
    TenshuIdentity.Verification.ATTESTED -> Verification.ATTESTED
    else -> Verification.UNVERIFIED
}
