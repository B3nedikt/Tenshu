package app.tenshu.drive.ui.screens.shared

import app.tenshu.liststate.ListLoadPhase
import app.tenshu.drive.data.ContactModel
import app.tenshu.drive.data.InboundSharedRealm
import app.tenshu.drive.data.SharedFolderModel

/**
 * Two-tab layout for the Shared destination
 * (`docs/file-sharing.md`).
 *
 * The *My shares* tab carries two outbound groups: Mode-A link shares
 * ([myShares]) and Mode-B shared folders ([sharedFolders] — realms the user has
 * shared a folder into, `drive-shared-realm-file-model.md` §6 Phase 3d). The
 * [sharedWithMe] (inbound) tab lists the realms shared *with* this member
 * (`role = member`, Phase 3d.2b); tapping one browses it via the shared-realm
 * read ops.
 */
data class SharedScreenUiState(
    val activeTab: Tab = Tab.MY_SHARES,
    val myShares: List<MyShareRow> = emptyList(),
    /**
     * Which of loading / loaded / failed [myShares] is in. ⚠ A failed read used to render as
     * "You haven't shared anything yet" — a claim about what the user has done, made when the
     * read that would know had failed. [sharedFolders] is derived locally and needs no twin.
     */
    val mySharesLoad: ListLoadPhase = ListLoadPhase.LOADING,
    val sharedFolders: List<SharedFolderModel> = emptyList(),
    val sharedWithMe: List<InboundSharedRealm> = emptyList(),
    /** Any `ContactInvite` at `REDEEMED` → banner + nav badge (ux doc §2.3). */
    val hasRedeemedInvites: Boolean = false,
    /** Set when the banner was tapped — host renders the completion prompt. */
    val promptInviteId: String? = null,
    /** Invitee-side redemptions resumed on init (ux doc §3.2.4). */
    val resumedRedemptions: List<ResumedRedemption> = emptyList(),
    /** GR-4: connected, grantable contacts for the accept-from-contact picker. */
    val grantableContacts: List<ContactModel> = emptyList(),
    /** Set when *Accept a shared folder* was tapped — host renders the picker. */
    val showAcceptPicker: Boolean = false,
    val acceptInProgress: Boolean = false,
    /** A friendly result of the last accept attempt (e.g. "nothing shared yet"). */
    val acceptMessage: String? = null,
    /**
     * GR-6: quarantined pending grants the inbound poll discovered — one card each,
     * pending the user's explicit accept/decline. Rendered above the inbound list.
     */
    val pendingGrants: List<PendingGrantCard> = emptyList(),
    /**
     * **Deferred** grant offers: contacts who finished a `SHARE_FOLDER` handshake but were
     * never actually granted the realm behind it (`contacts-app.md` §9.1 alternative).
     *
     * This is the decoupled replacement for the offer that used to be chained onto the
     * completion prompt. Deriving it from synced rows instead of from a modal moment has two
     * consequences worth knowing: the offer survives the user dismissing the prompt, closing
     * the app, or completing the handshake on a different device — and it is what lets the
     * completion prompt go back to being pure UI over synced rows + one op.
     */
    val grantOffers: List<GrantOfferCard> = emptyList(),
)

/**
 * One deferred grant offer — *"{name} is connected. Add them to '{label}'?"*.
 *
 * Derived, never remembered: it exists while a `COMPLETED` `SHARE_FOLDER` invite names a
 * grantable contact and an owned shared realm that the contact is **not yet a member of**.
 * Granting therefore makes the card disappear structurally, on every device, with no
 * bookkeeping — which is the property the chained version got for free by being modal.
 *
 * ⚠ *Not now* is the one thing that does **not** converge: see
 * [SharedScreenViewModel.onGrantOfferDismissed].
 */
data class GrantOfferCard(
    val inviteId: String,
    val personId: String,
    val realmId: String,
    /** The folder the invite named — the offer's subject. */
    val label: String,
    /** The contact's petname, or their claimed name if the contact list has not loaded. */
    val name: String,
)

/**
 * One discovered-but-not-accepted grant (GR-6): the grantor's [fingerprint] (the
 * accept/decline key) plus the resolved [grantorName] for display (empty until the
 * contacts load — the card then falls back to a generic label).
 */
data class PendingGrantCard(
    val fingerprint: String,
    val grantorName: String,
)

/**
 * One resumed invitee-side redemption: a waiting row until [ack] lands (then
 * the row renders the terminal copy — success with the inviter's claimed
 * name, declined, or gone).
 */
data class ResumedRedemption(
    val inviteId: String,
    val peerFingerprint: String,
    val ack: app.tenshu.drive.data.InviteAckModel? = null,
)

enum class Tab { SHARED_WITH_ME, MY_SHARES }

/** One row in *My shares*. P1b fills the fields by projecting `Share`. */
data class MyShareRow(
    val shareId: String,
    val filePath: String,
    val fileName: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long? = null,
    val maxOpens: Int? = null,
    val opensSoFar: Int = 0,
    val revoked: Boolean = false,
)
