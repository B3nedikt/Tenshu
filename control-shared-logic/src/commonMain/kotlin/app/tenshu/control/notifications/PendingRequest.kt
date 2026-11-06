package app.tenshu.control.notifications

import org.tenshu.contact.ContactInvite

/**
 * An inbound, **actionable** realm-level request the user must resolve — the
 * "actionable" tier of Control's central notifications
 * (`docs/proposals/control-central-notifications.md`). Distinct from the ambient
 * event feed (file added, app registered), these persist until acted on or
 * dismissed (§4.4).
 *
 * v1 sources are the genuinely *cold-inbound* flows Control owns as the realm/
 * identity plane: incoming **membership grants** and **redeemed contact invites**.
 * Pairing approvals are host-initiated (the user already started pairing), so they
 * are not v1 cold-inbound; the 6e app-reconnect wake lands later as another variant.
 */
sealed interface PendingRequest {
    /** Stable identity for dedup + (later) per-request dismissal state. */
    val id: String

    /** When the request was discovered/created (Unix ms) — newest surfaces first. */
    val timestampMillis: Long

    /** A contact wants to share a realm with this device (GR-6 `PendingGrant`). */
    data class MembershipGrant(
        val grantorFingerprint: String,
        override val timestampMillis: Long,
    ) : PendingRequest {
        override val id: String get() = "grant:$grantorFingerprint"
    }

    /** A contact invite this device sent has been redeemed by the peer — the user
     *  confirms adding the contact (CI-4 completion prompt). [claimedName] is the
     *  peer's self-asserted, quarantined name; [claimedFingerprint] + [claimedSas]
     *  are the proxy-derived key fingerprint and §5.3 SAS the user compares
     *  out-of-band before confirming (drives a `VERIFIED` vs `UNVERIFIED` contact). */
    data class ContactInviteRedeemed(
        val inviteId: String,
        val claimedName: String,
        val claimedFingerprint: String,
        val claimedSas: String,
        override val timestampMillis: Long,
    ) : PendingRequest {
        override val id: String get() = "invite:$inviteId"
    }
}

/**
 * Maps a contact invite's projected fields to a [PendingRequest] — non-null only when
 * the invite is **redeemed** (the actionable state; PENDING/COMPLETED/EXPIRED are not
 * pending requests). Pure (takes the extracted primitives, not the entity) so it is
 * unit-testable without a schema registry.
 */
fun contactInviteRequestOrNull(
    status: String,
    inviteId: String,
    claimedName: String,
    claimedFingerprint: String,
    claimedSas: String,
    createdAtMillis: Long,
): PendingRequest.ContactInviteRedeemed? =
    if (status == ContactInvite.Status.REDEEMED) {
        PendingRequest.ContactInviteRedeemed(
            inviteId = inviteId,
            claimedName = claimedName,
            claimedFingerprint = claimedFingerprint,
            claimedSas = claimedSas,
            timestampMillis = createdAtMillis,
        )
    } else {
        null
    }

/** Maps a pending membership grant's fields to a [PendingRequest]. */
fun membershipGrantRequest(grantorFingerprint: String, discoveredAtMillis: Long): PendingRequest.MembershipGrant =
    PendingRequest.MembershipGrant(grantorFingerprint, discoveredAtMillis)

/** Merge the inbound sources newest-first, deduped by [PendingRequest.id]. */
fun aggregatePendingRequests(vararg sources: List<PendingRequest>): List<PendingRequest> =
    sources.asSequence()
        .flatten()
        .distinctBy { it.id }
        .sortedByDescending { it.timestampMillis }
        .toList()
