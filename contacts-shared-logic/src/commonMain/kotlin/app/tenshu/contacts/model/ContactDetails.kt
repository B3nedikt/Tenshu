package app.tenshu.contacts.model

/**
 * The human-facing half of a contact — Q6's editable set, read through from the
 * `ContactSource` records behind the person.
 *
 * ⚠ **A mirror of `:contact-service-common`'s `ContactCard`, deliberately not that type.**
 * `:contacts-shared-logic` has no service dependencies, which is what lets the mock and
 * the view models exist without a proxy; `:contacts-shared-data` maps the codec's model onto
 * this one. The duplication is four data classes and buys the seam its independence.
 *
 * ⚠ This is a **projection**. Absent from it does not mean absent from the contact: the
 * record keeps every property the codec does not model, and the edit path merges rather than
 * regenerates so they survive (Q6).
 */
data class ContactDetails(
    val phones: List<ContactField> = emptyList(),
    val emails: List<ContactField> = emptyList(),
    val addresses: List<ContactField> = emptyList(),
    val organisation: String = "",
    val note: String = "",
) {
    val isEmpty: Boolean
        get() = phones.isEmpty() && emails.isEmpty() && addresses.isEmpty() &&
            organisation.isEmpty() && note.isEmpty()
}

/** One value with its type label — `mobile`, `home`, `work`, or whatever the record said. */
data class ContactField(
    val label: String,
    val value: String,
)

/**
 * The **read-through union** across a person's source records: one contact may have been
 * imported from a `.vcf` *and* typed into, and the detail screen shows the whole person rather
 * than one record at a time.
 *
 * Multi-values concatenate in source order, de-duplicated **by value** — the same number
 * arriving from two imports is one number, and keeping the first occurrence keeps the label
 * the earlier source gave it. Single-valued fields take the first non-empty one, because
 * there is no sensible way to show two organisations in one row and no basis for preferring
 * the later.
 *
 * ⚠ This is a display-time union only. It is never written back: an edit targets one record
 * (see `ContactsRepository.saveContactDetails`), so unioning cannot silently collapse two
 * sources into one.
 */
fun unionDetails(perSource: List<ContactDetails>): ContactDetails {
    if (perSource.isEmpty()) return ContactDetails()
    fun merge(select: (ContactDetails) -> List<ContactField>): List<ContactField> =
        perSource.flatMap(select).distinctBy { it.value.trim().lowercase() }

    return ContactDetails(
        phones = merge { it.phones },
        emails = merge { it.emails },
        addresses = merge { it.addresses },
        organisation = perSource.firstOrNull { it.organisation.isNotEmpty() }?.organisation.orEmpty(),
        note = perSource.firstOrNull { it.note.isNotEmpty() }?.note.orEmpty(),
    )
}
