package org.tenshu.contact.common

/**
 * The editable view of a [org.tenshu.contact.ContactSource] — Q6's set: name, phones,
 * emails, addresses, organisation, note, with type labels.
 *
 * ⚠ This is a **projection, not the record.** The record is the raw vCard; this is the part
 * of it the editor understands. Anything absent from this class is not absent from the
 * contact — it is simply not editable, and [mergeIntoVCard] is what guarantees it survives.
 */
data class ContactCard(
    val formattedName: String = "",
    val phones: List<LabeledValue> = emptyList(),
    val emails: List<LabeledValue> = emptyList(),
    val addresses: List<LabeledValue> = emptyList(),
    val organisation: String = "",
    val note: String = "",
) {
    val isEmpty: Boolean
        get() = formattedName.isEmpty() && phones.isEmpty() && emails.isEmpty() &&
            addresses.isEmpty() && organisation.isEmpty() && note.isEmpty()
}

/** A value with its vCard `TYPE` label — `mobile`, `home`, `work`, or a custom one. */
data class LabeledValue(
    val label: String,
    val value: String,
)

/** vCard property names this codec understands well enough to edit. */
private const val FN = "FN"
private const val TEL = "TEL"
private const val EMAIL = "EMAIL"
private const val ADR = "ADR"
private const val ORG = "ORG"
private const val NOTE = "NOTE"

/** The properties [mergeIntoVCard] is allowed to rewrite. Everything else is untouchable. */
private val EDITABLE = setOf(FN, TEL, EMAIL, ADR, ORG, NOTE)

/** Read the editable projection out of a raw record. */
fun readContactCard(rawVcard: String): ContactCard {
    val properties = parseVCard(rawVcard)
    fun labelled(name: String) = properties
        .filter { it.name.equals(name, ignoreCase = true) }
        .map { LabeledValue(label = primaryLabel(it), value = decodeText(it)) }

    return ContactCard(
        formattedName = properties.firstOrNull { it.name.equals(FN, true) }
            ?.let(::decodeText)
            .orEmpty(),
        phones = labelled(TEL),
        emails = labelled(EMAIL),
        // ADR is structured (`;`-separated components); join the non-empty ones so the
        // editor shows one line. Editing an address therefore rewrites it as a single
        // free-text component — see mergeIntoVCard's note.
        addresses = properties.filter { it.name.equals(ADR, true) }.map { property ->
            LabeledValue(
                label = primaryLabel(property),
                value = splitComponents(property.value)
                    .map { component -> decodeText(property.copy(value = component)) }
                    .filter { it.isNotBlank() }
                    .joinToString(", "),
            )
        },
        organisation = properties.firstOrNull { it.name.equals(ORG, true) }
            ?.let { splitComponents(it.value).joinToString(", ") { c -> decodeText(it.copy(value = c)) } }
            ?.trim(' ', ',')
            .orEmpty(),
        note = properties.firstOrNull { it.name.equals(NOTE, true) }
            ?.let(::decodeText)
            .orEmpty(),
    )
}

/**
 * ⚠⚠ **Merge the edited projection into the original record — never regenerate it.**
 *
 * This function is Q6's constraint made executable. A save that rebuilt the card from the
 * editor's fields would silently drop every `X-` extension, `CATEGORIES`, `UID`, `PHOTO` and
 * vendor property the import preserved: deletion by omission, invisible until somebody
 * notices their contact lost half of itself on a round trip.
 *
 * The rules:
 *
 *  - properties **outside** [EDITABLE] pass through untouched, in their original order and
 *    with their original escaping — byte-exact;
 *  - an editable property that still has a counterpart is rewritten **in place**, so its
 *    position, group (`item1.TEL`) and non-`TYPE` params survive;
 *  - a removed one is dropped; an added one is appended after the last of its kind (or at
 *    the end if there were none);
 *  - `TYPE` params are replaced from the edited label, other params are kept.
 *
 * ⚠ Known lossy corner: editing an `ADR` collapses its structured components into one
 * free-text line, because [ContactCard] shows an address as one field. An *untouched* `ADR`
 * keeps its structure exactly. This is the only edit-time loss in the merge; splitting the
 * editor into the seven ADR components would remove it.
 */
fun mergeIntoVCard(original: String, edited: ContactCard): String {
    val properties = parseVCard(original)
    val result = mutableListOf<VCardProperty>()

    val remaining = mutableMapOf(
        FN to mutableListOf(LabeledValue("", edited.formattedName)).filter { it.value.isNotEmpty() }.toMutableList(),
        TEL to edited.phones.toMutableList(),
        EMAIL to edited.emails.toMutableList(),
        ADR to edited.addresses.toMutableList(),
        ORG to mutableListOf(LabeledValue("", edited.organisation)).filter { it.value.isNotEmpty() }.toMutableList(),
        NOTE to mutableListOf(LabeledValue("", edited.note)).filter { it.value.isNotEmpty() }.toMutableList(),
    )
    val lastIndexOfKind = mutableMapOf<String, Int>()

    properties.forEach { property ->
        val kind = EDITABLE.firstOrNull { it.equals(property.name, ignoreCase = true) }
        if (kind == null) {
            result += property
            return@forEach
        }
        val queue = remaining.getValue(kind)
        if (queue.isEmpty()) return@forEach // removed by the edit
        val replacement = queue.removeAt(0)
        result += property.copy(
            params = withLabel(property.params, replacement.label, kind),
            value = encodeText(replacement.value),
        )
        lastIndexOfKind[kind] = result.lastIndex
    }

    // Anything the edit added beyond what the record already had.
    EDITABLE.forEach { kind ->
        remaining.getValue(kind).forEach { added ->
            val property = VCardProperty(
                name = kind,
                params = withLabel(emptyList(), added.label, kind),
                value = encodeText(added.value),
            )
            val after = lastIndexOfKind[kind]
            if (after == null) {
                result += property
                lastIndexOfKind[kind] = result.lastIndex
            } else {
                result.add(after + 1, property)
                lastIndexOfKind[kind] = after + 1
            }
        }
    }

    return serializeVCard(result)
}

/** Build a fresh record — for a contact typed in by hand, which has no original to preserve. */
fun newVCard(card: ContactCard): String = mergeIntoVCard(
    original = "BEGIN:VCARD\r\nVERSION:3.0\r\nEND:VCARD\r\n",
    edited = card,
)

/**
 * The label an editor shows for a property: its first `TYPE` that names a label, lowercased.
 *
 * Skipped, because none of them is a label a person gave the value: `PREF` (an ordering hint),
 * and the vCard 3.0 *kind* types — `INTERNET` (every email address is one; Apple's and Google's
 * exports write `EMAIL;TYPE=INTERNET;TYPE=HOME`) and `VOICE`
 * (`TEL;TYPE=VOICE;TYPE=WORK`). A comma list (`TYPE=WORK,VOICE`) is read value by value.
 */
private fun primaryLabel(property: VCardProperty): String =
    property.types
        .flatMap { it.split(',') }
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && it.uppercase() !in NOT_A_LABEL }
        ?.lowercase()
        .orEmpty()

/** `TYPE` values that describe what a property is rather than label it — see [primaryLabel]. */
private val NOT_A_LABEL = setOf("PREF", "INTERNET", "VOICE", "X400")

/**
 * Replace the label `TYPE` params with the edited label, keeping every other param (`ENCODING`,
 * `CHARSET`, `X-`…) and the `TYPE`s that are not labels (`PREF`, `INTERNET`, `VOICE` —
 * [NOT_A_LABEL]) — those describe the property, not its label.
 *
 * ⚠ `ENCODING` is deliberately dropped when it says QUOTED-PRINTABLE: the value is rewritten
 * as plain 3.0 text, so keeping the param would claim an encoding the bytes no longer use.
 */
private fun withLabel(existing: List<VCardParam>, label: String, kind: String): List<VCardParam> {
    val isType = { param: VCardParam -> param.name == null || param.name.equals("TYPE", ignoreCase = true) }
    val others = existing.filterNot { param ->
        isType(param) ||
            (param.name.equals("ENCODING", ignoreCase = true) &&
                param.value.equals("QUOTED-PRINTABLE", ignoreCase = true))
    }
    // ⚠ Every TYPE is re-emitted as `TYPE=<value>`, one per value: a comma list (`TYPE=WORK,VOICE,PREF`)
    // keeps its non-label members, and a bare 2.1 param (`EMAIL;INTERNET;HOME:`) never lands bare in
    // the 3.0 card this writes (RFC 2426 wants `name=value`).
    val kinds = existing.filter(isType)
        .flatMap { it.value.removeSurrounding("\"").split(',') }
        .map { it.trim().uppercase() }
        .filter { it in NOT_A_LABEL }
        .distinct()
        .map { VCardParam(name = "TYPE", value = it) }
    // FN / ORG / NOTE are single-valued and carry no label.
    if (label.isEmpty() || kind == FN || kind == ORG || kind == NOTE) return others + kinds
    return others + kinds + VCardParam(name = "TYPE", value = label.uppercase())
}
