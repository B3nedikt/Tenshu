package org.tenshu.sdk.ios

import app.tenshu.drive.data.ContactsRepository
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.MembershipRepository
import app.tenshu.drive.ui.access.InviteLinkUiState
import app.tenshu.drive.ui.access.InviteLinkViewModel
import app.tenshu.drive.ui.access.ManageAccessViewModel
import app.tenshu.drive.ui.contacts.ContactPromptUiState
import app.tenshu.drive.ui.contacts.ContactPromptViewModel
import app.tenshu.drive.ui.contacts.RedeemInviteViewModel
import app.tenshu.drive.ui.contacts.RedeemInviteUiState
import app.tenshu.drive.ui.screens.shared.SharedScreenViewModel
import app.tenshu.drive.ui.share.CreateShareLinkViewModel

/**
 * Wires the file-**sharing** view models for the SwiftUI client (full-parity step B, sharing cluster):
 * the create-share-link sheet and the Manage Access screen, over the injected [FilesRepository] (plus
 * the contacts + membership repos for access management).
 *
 * The parameterless prod entry points bind whatever [DriveReadSurface] has installed — fakes by
 * default (so previews/tests/no-proxy bring-up work), or the real `:contact-service` + GR shared-realm
 * repos once [DriveReadSurface.installRealClient] swaps in the live client over the EPC channel. The
 * explicit-deps overloads stay for tests, which install their own configured fakes. The bound Manage
 * Access screen reads the access list + revokes/removes/grants and produces an *invite someone*
 * link/QR ([inviteLink]); the SAS-confirm + completion-prompt + invitee-side redemption handshake
 * steps are a deferred follow-up.
 */
object DriveShareSurface {

    /** Create-share-link sheet for [filePath]. */
    fun createShareLink(filePath: String): CreateShareLinkViewModel =
        createShareLink(DriveReadSurface.installedRepository(), filePath)

    fun createShareLink(repository: FilesRepository, filePath: String): CreateShareLinkViewModel =
        CreateShareLinkViewModel(repository, filePath)

    /** Manage Access screen for the folder at [folderPath]. */
    fun manageAccess(folderPath: String): ManageAccessViewModel =
        manageAccess(
            DriveReadSurface.installedRepository(),
            DriveReadSurface.installedContactsRepository(),
            DriveReadSurface.installedMembershipRepository(),
            folderPath,
        )

    fun manageAccess(
        filesRepository: FilesRepository,
        contactsRepository: ContactsRepository,
        membershipRepository: MembershipRepository,
        folderPath: String,
    ): ManageAccessViewModel =
        ManageAccessViewModel(filesRepository, contactsRepository, membershipRepository, folderPath)

    /**
     * *Invite someone* sheet — generate a one-session contact-invite link/QR for [folderName] (the
     * advisory intent label, `""` outside a folder). Mirrors [createShareLink]: generate → hand out.
     */
    fun inviteLink(folderName: String): InviteLinkViewModel =
        inviteLink(DriveReadSurface.installedContactsRepository(), folderName)

    fun inviteLink(contactsRepository: ContactsRepository, folderName: String): InviteLinkViewModel =
        InviteLinkViewModel(contactsRepository, folderName)

    /** The Shared destination — *My shares* + *Shared with me* + inbound pending grants. */
    fun sharedWithMe(): SharedScreenViewModel =
        sharedWithMe(
            DriveReadSurface.installedRepository(),
            DriveReadSurface.installedContactsRepository(),
            DriveReadSurface.installedMembershipRepository(),
        )

    fun sharedWithMe(
        filesRepository: FilesRepository,
        contactsRepository: ContactsRepository,
        membershipRepository: MembershipRepository,
    ): SharedScreenViewModel =
        SharedScreenViewModel(filesRepository, contactsRepository, membershipRepository)

    /**
     * The inviter-side *completion prompt* for the REDEEMED invite [inviteId] (handshake §2.3): confirm
     * the SAS, name the contact, accept/decline. Opened from the *Shared* REDEEMED banner / a Manage
     * Access REDEEMED row.
     *
     * ⚠ The grant offer for a `SHARE_FOLDER` invite no longer rides on this — it is a deferred card
     * derived from the COMPLETED row (`contacts-app.md` §9.1), which is what dropped this surface's
     * `FilesRepository` + `MembershipRepository` arguments.
     */
    fun contactPrompt(inviteId: String): ContactPromptViewModel =
        contactPrompt(DriveReadSurface.installedContactsRepository(), inviteId)

    fun contactPrompt(
        contactsRepository: ContactsRepository,
        inviteId: String,
    ): ContactPromptViewModel =
        ContactPromptViewModel(contactsRepository, inviteId)

    /**
     * The invitee-side *redeem an invite* flow (handshake §3): paste/parse a link → confirm your name →
     * redeem → wait for the inviter's ack (resumable). [initialLink] pre-fills a scanned/deep-linked
     * value (`null` = manual paste entry).
     */
    /**
     * The redeem failure as copy. ⚠ `RedeemInviteUiState.errorMessage` became
     * `errorCopyKey: ErrorCopyKey?` when the error-copy catalogue landed, and `RedeemInviteSheet`
     * kept reading the old field — which is why `drive-ios` stopped compiling. The key names an
     * entry in the shared catalogue, so the copy is resolved here rather than restated in Swift
     * (unlike `pasteError`, whose copy is Swift-side by design).
     */
    fun redeemInviteErrorMessage(state: RedeemInviteUiState): String? =
        state.errorCopyKey?.let { ErrorCopySurface.labelForKey(it) }

    /**
     * The completion prompt's failure as copy — the same resolution, for the same reason. ⚠⚠ The
     * Swift sheet used to read `ContactPromptUiState.errorMessage`, which held the repository
     * error's `toString()`; threat model T-CI-10's refusals carry their remedy in the catalogue.
     */
    fun contactPromptErrorMessage(state: ContactPromptUiState): String? =
        state.errorCopyKey?.let { ErrorCopySurface.labelForKey(it) }

    /** The invite-link sheet's failure as copy, likewise resolved from its key. */
    fun inviteLinkErrorMessage(state: InviteLinkUiState): String? =
        state.errorCopyKey?.let { ErrorCopySurface.labelForKey(it) }

    fun redeemInvite(initialLink: String?): RedeemInviteViewModel =
        redeemInvite(DriveReadSurface.installedContactsRepository(), initialLink)

    fun redeemInvite(contactsRepository: ContactsRepository, initialLink: String?): RedeemInviteViewModel =
        RedeemInviteViewModel(contactsRepository, initialLink)
}
