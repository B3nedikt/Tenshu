package app.tenshu.control.notifications

/**
 * Which inbound request this is, as a closed set a UI switches on.
 *
 * Lives in `commonMain` rather than beside the iOS bridge row it was written for, because
 * the *rules* below are domain logic and the bridge is not. On a Linux or CI host the Apple
 * targets do not compile at all (the Kotlin/Native tasks are skipped, not run), so anything
 * left in `iosMain` is unverified until someone opens a Mac. Splitting the decisions out
 * means the part that can be quietly wrong — which leaf shows which subject, what the badge
 * counts — is pinned by an ordinary desktop test, and only the SKIE-facing shape is not.
 */
enum class PendingRequestKind {
    MEMBERSHIP_GRANT,
    CONTACT_INVITE_REDEEMED,
}

/**
 * The facts one request contributes to a card, independent of platform: which variant it is,
 * who it is from, and the SAS to compare (empty when the variant has none).
 *
 * Copy is *not* here (ADR 0016) — no sentences, no labels. A consumer turns these into a
 * card; two consumers turn them into the same card.
 */
data class PendingRequestFacts(
    val id: String,
    val kind: PendingRequestKind,
    val subject: String,
    val verificationCode: String,
    val timestampMillis: Long,
    /**
     * The RAW claimed fingerprint this card was rendered from — `""` for a grant. ⚠⚠ A surface
     * hands it back on accept as `shownFingerprint` (threat model T-CI-10): the claim is a synced
     * value that can change between render and tap, so what was ON SCREEN must be what is sent,
     * never the claim re-read at tap time. See [app.tenshu.control.ui.screens.notifications
     * .NotificationsViewModel.acceptById].
     */
    val shownFingerprint: String = "",
)

/**
 * Project one request to its presentation facts.
 *
 * Total over the sealed hierarchy by construction: a third variant (the 6e app-reconnect
 * wake, `control-central-notifications.md` Phase 5) fails this compile rather than reaching
 * a surface as a card with no subject.
 */
fun pendingRequestFacts(request: PendingRequest): PendingRequestFacts = when (request) {
    is PendingRequest.MembershipGrant -> PendingRequestFacts(
        id = request.id,
        kind = PendingRequestKind.MEMBERSHIP_GRANT,
        // The grantor's fingerprint is all a grant carries — Control has no petname for
        // someone who is not yet a contact, and inventing one would overstate what is known.
        subject = request.grantorFingerprint,
        // A grant has no SAS step: its trust comes from the signed capability, not from a
        // code the user reads aloud. Empty rather than a placeholder, so a card that renders
        // a code section can simply branch on emptiness.
        verificationCode = "",
        timestampMillis = request.timestampMillis,
    )

    is PendingRequest.ContactInviteRedeemed -> PendingRequestFacts(
        id = request.id,
        kind = PendingRequestKind.CONTACT_INVITE_REDEEMED,
        // ⚠ The peer's **claimed** name — self-asserted and quarantined until the user
        // confirms. Surfacing it is intended (it is what the user recognises); treating it
        // as verified identity is not, which is why the SAS sits beside it.
        subject = request.claimedName,
        verificationCode = request.claimedSas,
        timestampMillis = request.timestampMillis,
        shownFingerprint = request.claimedFingerprint,
    )
}

/**
 * What the bell badge shows: requests **not** dismissed on any device.
 *
 * ⚠ Deliberately not `requests.size`. The center lists dismissed-but-unresolved requests —
 * that is the Phase-4 distinction between *seen* and *resolved* — so counting the list would
 * leave a badge the user cannot clear without resolving something they meant to defer.
 */
fun unreadRequestCount(requests: List<PendingRequest>, seenIds: Set<String>): Int =
    requests.count { it.id !in seenIds }
