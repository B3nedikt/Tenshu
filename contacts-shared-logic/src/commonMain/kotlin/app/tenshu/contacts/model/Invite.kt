package app.tenshu.contacts.model

/**
 * An invitation this device issued that nobody has answered yet — a `ContactInvite` at
 * `PENDING` (`contact-invitation-handshake.md` §3).
 *
 * ⚠ **There is no link here, and that is deliberate.** The raw link exists only in the
 * session that minted it: it carries `K_link`, which is never persisted and never reaches
 * the relay. So an outstanding invite can be *revoked*, never *shown again* — the same rule
 * Drive's *Invited* rows follow.
 */
data class OutstandingInvite(
    val inviteId: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
)

/**
 * Somebody accepted an invitation and is waiting to be saved as a contact — a
 * `ContactInvite` at `REDEEMED` (`drive-manage-access-ux.md` §2.3).
 *
 * [claimedName] and [claimedFingerprint] are **their claim**, taken from the sealed response
 * the proxy verified. The UI must render the name as a claim (quoted) and never promote it
 * to a petname on its own — the petname is the user's to choose.
 *
 * [claimedSas] is the proxy-derived six-digit code from
 * `contact-invitation-handshake.md` §5.3, over **both** identity public keys. Confirming it
 * is the only thing that may set `sasConfirmed`, and it is what turns an `UNVERIFIED`
 * TOFU key into a `VERIFIED` one (threat-model A24: never auto-elevated).
 */
data class InviteConfirmation(
    val inviteId: String,
    val claimedName: String,
    val claimedFingerprint: String,
    val claimedSas: String,
)
