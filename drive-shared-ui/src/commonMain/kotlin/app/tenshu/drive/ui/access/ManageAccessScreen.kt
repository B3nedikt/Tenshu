package app.tenshu.drive.ui.access

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.tenshu.drive.data.ContactInviteModel
import app.tenshu.drive.data.MemberModel
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.contact_prompt_unnamed
import app.tenshu.drive.shared.resources.manage_access_empty_body
import app.tenshu.drive.shared.resources.manage_access_empty_title
import app.tenshu.drive.shared.resources.manage_access_grant_action
import app.tenshu.drive.shared.resources.manage_access_grant_error
import app.tenshu.drive.shared.resources.manage_access_grant_picker_empty
import app.tenshu.drive.shared.resources.manage_access_grant_picker_title
import app.tenshu.drive.shared.resources.manage_access_invite_link_row
import app.tenshu.drive.shared.resources.manage_access_invite_someone
import app.tenshu.drive.shared.resources.manage_access_member_you
import app.tenshu.drive.shared.resources.manage_access_remove
import app.tenshu.drive.shared.resources.manage_access_remove_error
import app.tenshu.drive.shared.resources.manage_access_removing
import app.tenshu.drive.shared.resources.manage_access_role_member
import app.tenshu.drive.shared.resources.manage_access_role_owner
import app.tenshu.drive.shared.resources.manage_access_status_accepted_confirm
import app.tenshu.drive.shared.resources.manage_access_status_compromised
import app.tenshu.drive.shared.resources.manage_access_status_invited
import app.tenshu.drive.shared.resources.manage_access_revoke
import app.tenshu.drive.shared.resources.manage_access_title
import app.tenshu.drive.ui.contacts.ContactPromptDialog
import app.tenshu.drive.ui.contacts.ContactPromptViewModel
import app.tenshu.ui.chrome.AppBottomSheet
import app.tenshu.ui.chrome.AppFab
import app.tenshu.ui.chrome.AppTopAppBar
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.tenshu.contact.ContactInvite

/**
 * The *Manage access* screen (`docs/proposals/drive-manage-access-ux.md` §10 —
 * supersedes the §2.1 sheet): one unified people list whose **row treatment
 * encodes invite status** (§10.2 emphasis ramp: compromised >
 * needs-your-confirmation > connected > ghosted pending), with "You — Owner"
 * pinned first and a + FAB into the invite-link sheet. The §2.3 completion
 * prompt rides on top as a dialog; the invite-link sheet stays a bottom sheet
 * (a transient action — state lives on screens, actions on sheets).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageAccessScreen(
    viewModel: ManageAccessViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            AppTopAppBar(
                title = stringResource(Res.string.manage_access_title, uiState.folderName),
                onBack = onBack,
            )
        },
        floatingActionButton = {
            AppFab(
                onClick = viewModel::onInviteSomeoneClicked,
                icon = Icons.Default.PersonAdd,
                contentDescription = stringResource(Res.string.manage_access_invite_someone),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "owner") { OwnerRow() }

            // GR-4: cross-user members (accepted grants) — real access, listed
            // in the same unified people list as connected rows. GR-6 adds *Remove*.
            items(uiState.members, key = { "member:${it.memberId}" }) { member ->
                MemberRow(
                    member = member,
                    removing = uiState.removingMemberId == member.memberId,
                    onRemove = { viewModel.onRemoveMember(member.memberId) },
                )
            }

            uiState.removeError?.let { message ->
                item(key = "remove-error") {
                    Text(
                        text = stringResource(Res.string.manage_access_remove_error, message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }

            items(uiState.invites, key = { it.inviteId }) { invite ->
                InviteRow(
                    invite = invite,
                    onClick = { viewModel.onInviteRowClicked(invite.inviteId) },
                    onRevoke = { viewModel.onRevokeInviteClicked(invite.inviteId) },
                )
            }

            // GR-4: grant an existing contact access to this folder's realm.
            if (uiState.backsSharedRealm) {
                item(key = "grant-action") {
                    GrantAccessRow(onClick = viewModel::onGrantAccessClicked)
                }
            }

            if (uiState.invites.isEmpty() && uiState.members.isEmpty() && !uiState.backsSharedRealm) {
                item(key = "empty") { EmptyState() }
            }
        }
    }

    if (uiState.showGrantPicker) {
        AppBottomSheet(
            visible = true,
            onDismissRequest = viewModel::onGrantPickerDismissed,
        ) {
            GrantPickerSheet(
                contacts = uiState.grantableContacts,
                inProgress = uiState.grantInProgress,
                errorMessage = uiState.grantError,
                onGrant = viewModel::onGrantToContact,
            )
        }
    }

    if (uiState.showInviteSheet) {
        AppBottomSheet(
            visible = true,
            onDismissRequest = viewModel::onInviteSheetDismissed
        ) {
            val inviteLinkViewModel = koinViewModel<InviteLinkViewModel>(
                key = "invite_link_${uiState.folderPath}",
                parameters = { parametersOf(uiState.folderName) }
            )
            InviteLinkSheet(
                viewModel = inviteLinkViewModel,
                onDismiss = viewModel::onInviteSheetDismissed,
            )
        }
    }

    uiState.promptInviteId?.let { inviteId ->
        val promptViewModel = koinViewModel<ContactPromptViewModel>(
            key = "contact_prompt_$inviteId",
            parameters = { parametersOf(inviteId) }
        )
        ContactPromptDialog(
            viewModel = promptViewModel,
            onDismiss = viewModel::onPromptDismissed,
        )
    }
}

/** "You — Owner", pinned first (§10.1): the one always-knowable member. */
@Composable
private fun OwnerRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(background = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = stringResource(Res.string.manage_access_member_you),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(Res.string.manage_access_role_owner),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A cross-user member (accepted grant, GR-4): clean row + "Member" subtitle, with a
 * *Remove* affordance (GR-6). While removing, the row shows a transient label instead
 * of the button.
 */
@Composable
private fun MemberRow(
    member: MemberModel,
    removing: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(background = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                text = member.displayName.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.displayName.ifEmpty { stringResource(Res.string.contact_prompt_unnamed) },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(Res.string.manage_access_role_member),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (removing) {
            Text(
                text = stringResource(Res.string.manage_access_removing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TextButton(onClick = onRemove) {
                Text(
                    text = stringResource(Res.string.manage_access_remove),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** GR-4: tappable row that opens the grant-a-contact picker. */
@Composable
private fun GrantAccessRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(background = MaterialTheme.colorScheme.surfaceVariant) {
            Icon(
                imageVector = Icons.Default.PersonAdd,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(Res.string.manage_access_grant_action),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** GR-4: pick a connected contact to grant realm access to. */
@Composable
private fun GrantPickerSheet(
    contacts: List<app.tenshu.drive.data.ContactModel>,
    inProgress: Boolean,
    errorMessage: String?,
    onGrant: (personId: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.manage_access_grant_picker_title),
            style = MaterialTheme.typography.titleMedium,
        )
        if (contacts.isEmpty()) {
            Text(
                text = stringResource(Res.string.manage_access_grant_picker_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        contacts.forEach { contact ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(enabled = !inProgress) { onGrant(contact.personId) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(background = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        text = contact.displayName.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(
                    text = contact.displayName.ifEmpty {
                        stringResource(Res.string.contact_prompt_unnamed)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        errorMessage?.let { message ->
            Text(
                text = stringResource(Res.string.manage_access_grant_error, message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * One invite row, treatment by status (§10.2): `PENDING` ghosted with a
 * dashed placeholder (there is no person yet — it's a link), `REDEEMED`
 * accented and tappable into the completion prompt (the only row demanding
 * user action), `COMPLETED` clean with no status markers, `COMPROMISED` loud
 * in error colours. Icon + text together, never icon-only.
 */
@Composable
private fun InviteRow(
    invite: ContactInviteModel,
    onClick: () -> Unit,
    onRevoke: () -> Unit,
) {
    when (invite.status) {
        ContactInvite.Status.PENDING -> PendingRow(onRevoke)
        ContactInvite.Status.REDEEMED -> RedeemedRow(invite, onClick)
        ContactInvite.Status.COMPROMISED -> CompromisedRow()
        else -> ConnectedRow(invite)
    }
}

@Composable
private fun PendingRow(onRevoke: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0.55f)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(
            background = Color.Transparent,
            modifier = Modifier.drawBehind {
                drawCircle(
                    color = outline,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                    ),
                )
            },
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.manage_access_invite_link_row),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Res.string.manage_access_status_invited),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onRevoke) {
            Text(stringResource(Res.string.manage_access_revoke))
        }
    }
}

@Composable
private fun RedeemedRow(invite: ContactInviteModel, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(background = MaterialTheme.colorScheme.primary) {
            Text(
                text = invite.initial(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = invite.quotedClaim(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(Res.string.manage_access_status_accepted_confirm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** Steady state: no status line, no markers — indicators mark exceptions. */
@Composable
private fun ConnectedRow(invite: ContactInviteModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(background = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                text = invite.initial(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(
            text = invite.quotedClaim(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CompromisedRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(background = MaterialTheme.colorScheme.errorContainer) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.manage_access_invite_link_row),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(Res.string.manage_access_status_compromised),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.PersonAdd,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.manage_access_empty_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(Res.string.manage_access_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Avatar(
    background: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** The responder's name is a *claim* — quoted while it's theirs, not yours. */
@Composable
private fun ContactInviteModel.quotedClaim(): String = when {
    claimedName.isNotEmpty() -> "“${claimedName}”"
    else -> stringResource(Res.string.contact_prompt_unnamed)
}

private fun ContactInviteModel.initial(): String =
    claimedName.firstOrNull()?.uppercase() ?: "?"
