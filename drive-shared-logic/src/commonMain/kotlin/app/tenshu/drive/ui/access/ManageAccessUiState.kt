package app.tenshu.drive.ui.access

import app.tenshu.drive.data.ContactInviteModel
import app.tenshu.drive.data.ContactModel
import app.tenshu.drive.data.MemberModel

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
)
