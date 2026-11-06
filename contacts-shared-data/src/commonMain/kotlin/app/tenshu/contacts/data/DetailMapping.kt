package app.tenshu.contacts.data

import app.tenshu.contacts.model.ContactDetails
import app.tenshu.contacts.model.ContactField
import org.tenshu.contact.common.ContactCard
import org.tenshu.contact.common.LabeledValue

/**
 * The two-line bridge between the codec's model (`:contact-service-common`) and the app's
 * (`:contacts-shared-logic`).
 *
 * They are separate types on purpose: the seam module has no service dependencies, which is
 * what lets the mock and the view models exist without a proxy. This is the only place the
 * two meet.
 */
internal fun ContactCard.toDetails(): ContactDetails = ContactDetails(
    phones = phones.map { ContactField(it.label, it.value) },
    emails = emails.map { ContactField(it.label, it.value) },
    addresses = addresses.map { ContactField(it.label, it.value) },
    organisation = organisation,
    note = note,
)

/**
 * [formattedName] is carried in from the record being edited rather than from the app model:
 * the petname lives on `Person` and this editor has no business rewriting the record's `FN`.
 */
internal fun ContactDetails.toCard(formattedName: String): ContactCard = ContactCard(
    formattedName = formattedName,
    phones = phones.map { LabeledValue(it.label, it.value) },
    emails = emails.map { LabeledValue(it.label, it.value) },
    addresses = addresses.map { LabeledValue(it.label, it.value) },
    organisation = organisation,
    note = note,
)
