package app.tenshu.drive.ui.access

import app.tenshu.drive.data.ContactInviteModel
import app.tenshu.drive.data.ContactModel
import app.tenshu.drive.data.InvitationModel
import app.tenshu.drive.data.MemberModel
import app.tenshu.drive.domain.StorageGateDecision

/**
 * State for [ManageAccessScreen] (`docs/proposals/drive-manage-access-ux.md`
 * §10). The screen pins "You — Owner" unconditionally; when the folder backs a
 * shared realm the list also carries cross-user [members] (GR-4) and a grant
 * action over [grantableContacts].
 */
data class ManageAccessUiState(
    val folderPath: String,
    /** Sheet header + the invite's advisory `intentLabel`. */
    val folderName: String,
    /** True when a `sharedFolders()` row matches this folder (Mode-B realm). */
    val backsSharedRealm: Boolean = false,
    /** The realm this folder backs (when I own it) — the grant/roster target. */
    val sharedRealmId: String? = null,
    /** Invite rows filtered to this folder's intent label (v1 approximation). */
    val invites: List<ContactInviteModel> = emptyList(),
    /**
     * Cross-user members of [sharedRealmId] (`getRealmMembers`, role = member;
     * the owner is the pinned "You — Owner" row, not repeated here). GR-4.
     */
    val members: List<MemberModel> = emptyList(),
    /**
     * Grants of [sharedRealmId] still waiting for the member to accept — the "invited" state
     * (`shared-realm-membership-records.md` §3.4). A row moves to [members] once the owner's poll picks up
     * the acceptance and writes the member's row (RO D7). Owner-side only.
     */
    val invited: List<InvitationModel> = emptyList(),
    /**
     * Contact fingerprint → that contact's name, so an invited row and a member row (whose own display name
     * is empty by default — pseudonymous, GR D5) both show who it is.
     */
    val contactNames: Map<String, String> = emptyMap(),
    /** Connected, grantable contacts for the grant picker (GR-4). */
    val grantableContacts: List<ContactModel> = emptyList(),
    /** Set when *Invite someone* was tapped — host renders [InviteLinkSheet]. */
    val showInviteSheet: Boolean = false,
    /** Set when *Grant access to a contact* was tapped — host renders the picker. */
    val showGrantPicker: Boolean = false,
    val grantInProgress: Boolean = false,
    val grantError: String? = null,
    /** The member currently being removed (its row shows progress, GR-6); null when idle. */
    val removingMemberId: String? = null,
    /** A failed removal's message (GR-6). */
    val removeError: String? = null,
    /** Set when an *Awaiting your confirmation* row was tapped — host renders the prompt. */
    val promptInviteId: String? = null,
    /** The folder is being shared into a fresh realm — the first add's prerequisite (§11). */
    val sharing: Boolean = false,
    /** Why sharing the folder failed, when it did. */
    val shareError: String? = null,
    /** The first add was blocked for want of storage — host renders the gate dialog. */
    val storageGateBlock: StorageGateDecision? = null,
)
