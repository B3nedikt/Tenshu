package app.tenshu.contacts.model

/**
 * A contact-list row: the thin `Person` aggregate joined to its keyring
 * (`docs/proposals/contacts-app.md` D3/D5; the service shapes are `Person` +
 * `TenshuIdentity` in `:contact-service`).
 *
 * **One list, not two** (D3). A contact with linked identities (acquired through the
 * invitation handshake) and a contact with none (typed by hand, imported from the platform
 * store, parsed from a `.vcf` — D5) are the same kind of thing: a person. What differs is
 * the [trust] badge on the row, not which list it lives in.
 *
 * [displayName] is the **petname** — user-owned, defaulted from the handshake's
 * self-asserted name but never overwritten by it. Self-asserted names stay quarantined on
 * the [identities], where they are rendered as claims.
 */
data class Contact(
    val personId: String,
    val displayName: String,
    /**
     * This person's keyring, one entry per key. A **list**, not a key: rotation and
     * re-handshaking add rows rather than mutating one (`TenshuIdentity` is keyed by
     * fingerprint), which is exactly what [trust] has to reckon with.
     */
    val identities: List<ContactIdentity> = emptyList(),
    /**
     * The read-through union of this person's source records — numbers, emails,
     * addresses, organisation, note. Empty for a contact whose only origin is a handshake,
     * which carries no card.
     */
    val details: ContactDetails = ContactDetails(),
    /**
     * This person's photo, or `null` for the great majority who have none (D1).
     *
     * ⚠ Bytes, not a decoded image: `:contacts-shared-logic` is Compose-free so that
     * the view models stay platform-neutral and iOS-capable. Decoding is the UI's job and
     * happens once per row in the composable.
     */
    val avatar: ByteArray? = null,
) {
    // ⚠ `avatar` is a ByteArray, whose equals is reference identity — so the generated
    // data-class equality would call two identical contacts different whenever the bytes
    // were re-read. Compared by content here, which is what every caller means.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Contact) return false
        return personId == other.personId &&
            displayName == other.displayName &&
            identities == other.identities &&
            details == other.details &&
            avatar.contentEquals(other.avatar)
    }

    override fun hashCode(): Int {
        var result = personId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + identities.hashCode()
        result = 31 * result + details.hashCode()
        result = 31 * result + (avatar?.contentHashCode() ?: 0)
        return result
    }

    /** The D3 badge state, derived from the keyring. See [trustOf]. */
    val trust: TrustState get() = trustOf(identities)

    /** True once a handshake has yielded at least one key — D5's dividing line. */
    val isConnected: Boolean get() = identities.isNotEmpty()

    /**
     * Local search (D2): the petname plus the names the keys claim, so searching for how
     * someone introduced themselves finds them even if the user renamed the card.
     *
     * This is search over *your own contacts* only. Directory search — a server mapping a
     * handle to a public key — is permanently rejected (D2 anti-parity), so there is
     * nothing here that reaches off-device.
     */
    fun matches(query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return displayName.contains(needle, ignoreCase = true) ||
            identities.any { it.selfAssertedName.contains(needle, ignoreCase = true) } ||
            // Searching for a number or an address is how people find contacts they never
            // named carefully — the fields are already in memory, so this costs nothing.
            details.phones.any { it.value.contains(needle, ignoreCase = true) } ||
            details.emails.any { it.value.contains(needle, ignoreCase = true) } ||
            details.organisation.contains(needle, ignoreCase = true)
    }
}

/**
 * One keyring entry — a peer's realm-identity public key and its verification state
 * (the `TenshuIdentity` row).
 *
 * [selfAssertedName] is what *they* claimed at handshake time. It is never promoted into
 * the petname automatically and must be rendered as a claim.
 */
data class ContactIdentity(
    val fingerprint: String,
    val selfAssertedName: String,
    val verification: Verification,
    /** Epoch millis; `0` while [verification] is [Verification.UNVERIFIED]. */
    val verifiedAtMillis: Long = 0,
    /** One of `TenshuIdentity.Source` — `INVITE_LINK`, `QR_SAS`, `REI`. */
    val source: String = "",
)

/** The verification ladder, low to high. Never auto-elevated (threat-model A24). */
enum class Verification {
    /** TOFU — acquired through the remote-link handshake and not yet compared. */
    UNVERIFIED,

    /** The six-digit contact SAS was compared out of band and confirmed. */
    VERIFIED,

    /** Carried by a Tenshu Rei attestation chain. */
    ATTESTED,
}

/**
 * What the row badge says about a person. Derived from the keyring — trust lives on the
 * key, never on the person (`contact-service-identity-model.md` §4.2).
 */
sealed interface TrustState {

    /** No linked key: an import-only or manually-typed contact (D5). Not a warning. */
    data object NotConnected : TrustState

    /** Connected, but every key is still TOFU. */
    data object Unverified : TrustState

    /** Connected and every key is at `VERIFIED` or above — the checkmark. */
    data object Verified : TrustState

    /**
     * ⚠ **Q2b.** Some keys are verified and some are not, which means the keyring changed
     * after a verification: `TenshuIdentity` is keyed by fingerprint, so a rotated or
     * re-handshaked key arrives as a **new row** beside the old one rather than mutating
     * it.
     *
     * A *"≥1 linked identity at `VERIFIED` or above"* rule would render a checkmark here,
     * describing a key the contact no longer uses. That is precisely the case verification
     * exists to catch, so the badge demotes and the copy names the key change.
     *
     * Note what is deliberately *not* claimed: nothing on the row says which key is
     * current. A `TenshuIdentity` carries no acquisition timestamp (`verifiedAt` is `0`
     * until verified), so "which one is newest" is not derivable — the honest signal is
     * that the keyring is mixed.
     */
    data object NeedsReverification : TrustState
}

/**
 * The D3 badge derivation, extracted so it is testable on its own and so the Q2b rule
 * lives in exactly one place.
 */
fun trustOf(identities: List<ContactIdentity>): TrustState = when {
    identities.isEmpty() -> TrustState.NotConnected
    identities.all { it.verification == Verification.UNVERIFIED } -> TrustState.Unverified
    identities.any { it.verification == Verification.UNVERIFIED } -> TrustState.NeedsReverification
    else -> TrustState.Verified
}

/**
 * The user's own card (D6). Contacts is the home for this — Drive punted it (*"Drive has no
 * profile surface"*), and the name is platform-level (`OwnerPreferences.Name`, the same row
 * Fit's Profile shows), so this introduces no second answer to "what is my name".
 *
 * [fingerprint] is `""` when this device cannot read it, and no client-readable own-identity
 * op exists (`contacts-app.md` §9). The surface renders the absence honestly rather than
 * inventing a value.
 */
data class OwnProfile(
    val displayName: String = "",
    val fingerprint: String = "",
)

/**
 * Display form of a full-hex identity fingerprint: the leading 15 hex chars as
 * `XXXXX-YYYYY-ZZZZZ` (uppercased) — the convention pinned in
 * `contact-invitation-handshake.md` §5.3 and `drive-manage-access-ux.md` §2.3. The stored
 * key stays full-length; this is presentation only.
 *
 * Duplicated from Drive's `displayFingerprint` rather than shared: a five-line presentation
 * helper is not worth a cross-app module dependency, and the two apps' surfaces have no
 * other reason to couple.
 */
fun displayFingerprint(fingerprintHex: String): String =
    fingerprintHex
        .take(15)
        .uppercase()
        .chunked(5)
        .joinToString("-")
