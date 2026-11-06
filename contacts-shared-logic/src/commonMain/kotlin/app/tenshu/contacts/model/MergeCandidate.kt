package app.tenshu.contacts.model

/** Why two contacts look like the same person. Ordered strongest first. */
enum class MergeReason {
    /** They share a phone number. Two people almost never do. */
    SharedPhone,

    /** They share an email address. */
    SharedEmail,

    /** Their names are the same once case and spacing are ignored. Weak on its own. */
    SameName,
}

/**
 * A suggestion that two contacts are one person (`docs/proposals/contacts-app.md` D7).
 *
 * ⚠⚠ **A suggestion, never an action** (D7's *never auto-merge*, see `MergePersons`).
 * Nothing in this file writes anything — it ranks pairs for a human to look at.
 */
data class MergeCandidate(
    val first: Contact,
    val second: Contact,
    /** Every reason that fired, strongest first. Never empty. */
    val reasons: List<MergeReason>,
) {
    /** The strongest reason — what the row leads with. */
    val headlineReason: MergeReason get() = reasons.first()

    /**
     * True when the pair is only similar by name.
     *
     * Worth distinguishing: two people genuinely can share a name, so a name-only suggestion
     * is a question rather than a finding, and the UI says so rather than presenting it with
     * the same confidence as a shared phone number.
     */
    val isNameOnly: Boolean get() = reasons == listOf(MergeReason.SameName)
}

/**
 * Find pairs that look like duplicates.
 *
 * Pure, and deliberately in the app rather than the service: the service has **no heuristic
 * anywhere**, so nothing server-side or proxy-side can ever act on a resemblance. Suggestion
 * is a presentation concern; merging is an explicit op a user has to trigger.
 *
 * ⚠ **Connected contacts are never suggested against each other on name alone.** Two people
 * who both completed a handshake are two people the user has separately confirmed exist; the
 * odds that a name collision between them is a duplicate are far lower than for two imported
 * cards, and the cost of a wrong merge is higher (an unfamiliar key silently joins a known
 * contact's keyring). A shared phone or email still surfaces them, because that is evidence
 * rather than coincidence.
 */
fun findMergeCandidates(contacts: List<Contact>): List<MergeCandidate> {
    val candidates = mutableListOf<MergeCandidate>()
    for (i in contacts.indices) {
        for (j in i + 1 until contacts.size) {
            val a = contacts[i]
            val b = contacts[j]
            val reasons = reasonsFor(a, b)
            if (reasons.isNotEmpty()) {
                candidates += MergeCandidate(first = a, second = b, reasons = reasons)
            }
        }
    }
    // Strongest evidence first, so the pairs worth acting on are not buried under name
    // coincidences — and stable within a tier, since the input list is sorted by name.
    return candidates.sortedBy { it.headlineReason.ordinal }
}

private fun reasonsFor(a: Contact, b: Contact): List<MergeReason> = buildList {
    if (sharesAny(a.details.phones.map { normalisePhone(it.value) }, b.details.phones.map { normalisePhone(it.value) })) {
        add(MergeReason.SharedPhone)
    }
    if (sharesAny(a.details.emails.map { normaliseEmail(it.value) }, b.details.emails.map { normaliseEmail(it.value) })) {
        add(MergeReason.SharedEmail)
    }
    val sameName = a.displayName.isNotBlank() &&
        normaliseName(a.displayName) == normaliseName(b.displayName)
    if (sameName && !(a.isConnected && b.isConnected)) {
        add(MergeReason.SameName)
    }
}

private fun sharesAny(a: List<String>, b: List<String>): Boolean {
    val left = a.filter { it.isNotEmpty() }.toSet()
    return left.isNotEmpty() && b.any { it.isNotEmpty() && it in left }
}

/**
 * Digits only, compared on the **last nine**.
 *
 * `+44 7700 900123`, `07700 900123` and `00447700900123` are the same number written three
 * ways, and a naive string compare finds none of them. Nine digits is long enough that a
 * collision is implausible and short enough to survive every country's trunk-prefix and
 * international-prefix variation. Numbers shorter than that (a short code, an extension)
 * compare whole.
 */
internal fun normalisePhone(value: String): String {
    val digits = value.filter { it.isDigit() }
    return if (digits.length > 9) digits.takeLast(9) else digits
}

internal fun normaliseEmail(value: String): String = value.trim().lowercase()

internal fun normaliseName(value: String): String =
    value.trim().lowercase().split(' ', '\t', '\n').filter { it.isNotBlank() }.joinToString(" ")
