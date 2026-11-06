package app.tenshu.control.ui.screens.notifications

import app.tenshu.ui.error.label
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tenshu.control.notifications.PendingRequest
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.notifications_action_accept
import app.tenshu.control.shared.resources.notifications_action_confirm
import app.tenshu.control.shared.resources.notifications_action_decline
import app.tenshu.control.shared.resources.notifications_action_dismiss
import app.tenshu.control.shared.resources.notifications_grant_title
import app.tenshu.control.shared.resources.notifications_invite_codes_match
import app.tenshu.control.shared.resources.notifications_invite_petname_label
import app.tenshu.control.shared.resources.notifications_invite_sas_hint
import app.tenshu.control.shared.resources.notifications_invite_sas_label
import app.tenshu.control.shared.resources.notifications_invite_title
import org.jetbrains.compose.resources.stringResource

/**
 * Callbacks for the actions a [PendingRequestCard] can fire. Bundled so the card —
 * used by both the notification center and the pinned Home slot — takes one parameter
 * instead of four. The host (`App.kt`) wires these to [NotificationsViewModel].
 */
class PendingRequestActions(
    val onAcceptGrant: (PendingRequest.MembershipGrant) -> Unit = {},
    val onDeclineGrant: (PendingRequest.MembershipGrant) -> Unit = {},
    val onConfirmInvite: (PendingRequest.ContactInviteRedeemed, String, Boolean) -> Unit = { _, _, _ -> },
    val onDeclineInvite: (PendingRequest.ContactInviteRedeemed) -> Unit = {},
    /** Mark seen (Phase 4) — clears it from the badge + pinned slot without resolving it. */
    val onDismiss: (PendingRequest) -> Unit = {},
)

/**
 * An actionable card for one inbound [PendingRequest]
 * (`control-central-notifications.md` §4.4) — the shared shape rendered both in the
 * notification center and as a pinned Home card. A grant offers Accept/Decline; a
 * redeemed invite shows the §5.3 verification code with a "codes match" check + a
 * petname field, then Confirm/Decline. [actionState] disables the buttons while an
 * action is in flight and surfaces any failure inline.
 */
@Composable
fun PendingRequestCard(
    request: PendingRequest,
    actionState: RequestActionUiState,
    actions: PendingRequestActions,
    modifier: Modifier = Modifier,
    seen: Boolean = false,
) {
    // The dismiss "×" marks the request seen (Phase 4). Hidden once already seen (no-op)
    // and while another action is in flight, so the corner doesn't compete for the tap.
    val onDismiss: (() -> Unit)? =
        if (seen || actionState.inProgress) null else { -> actions.onDismiss(request) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (request) {
                is PendingRequest.MembershipGrant -> GrantBody(request, actionState, actions, onDismiss)
                is PendingRequest.ContactInviteRedeemed -> InviteBody(request, actionState, actions, onDismiss)
            }
            val error = actionState.error
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    // ⚠⚠ From the shared catalogue by key. This used to be whatever sentence the
                    // proxy wrote — `core-error-copy-catalogue.md` §5.0.
                    text = error.label(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun GrantBody(
    request: PendingRequest.MembershipGrant,
    actionState: RequestActionUiState,
    actions: PendingRequestActions,
    onDismiss: (() -> Unit)?,
) {
    CardHeader(
        icon = Icons.Default.Share,
        title = stringResource(Res.string.notifications_grant_title),
        subtitle = request.grantorFingerprint.take(16),
        onDismiss = onDismiss,
    )
    Spacer(Modifier.height(12.dp))
    ActionButtons(
        inProgress = actionState.inProgress,
        onDecline = { actions.onDeclineGrant(request) },
        confirmLabel = stringResource(Res.string.notifications_action_accept),
        confirmEnabled = true,
        onConfirm = { actions.onAcceptGrant(request) },
    )
}

@Composable
private fun InviteBody(
    request: PendingRequest.ContactInviteRedeemed,
    actionState: RequestActionUiState,
    actions: PendingRequestActions,
    onDismiss: (() -> Unit)?,
) {
    var petname by rememberSaveable(request.id) { mutableStateOf(request.claimedName) }
    // ⚠⚠ Keyed on the claim too: a tick is about the code it was made against, so a claim that
    // moves under the card (threat model T-CI-10) must start unticked.
    var codesMatch by rememberSaveable(request.id, request.claimedFingerprint) { mutableStateOf(false) }

    CardHeader(
        icon = Icons.Default.Person,
        title = stringResource(Res.string.notifications_invite_title),
        subtitle = request.claimedName,
        onDismiss = onDismiss,
    )

    if (request.claimedSas.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(Res.string.notifications_invite_sas_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            text = request.claimedSas,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(Res.string.notifications_invite_sas_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.toggleable(
                value = codesMatch,
                enabled = !actionState.inProgress,
                role = Role.Checkbox,
                onValueChange = { codesMatch = it },
            ),
        ) {
            Checkbox(
                checked = codesMatch,
                onCheckedChange = null,
                enabled = !actionState.inProgress,
            )
            Text(stringResource(Res.string.notifications_invite_codes_match))
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = petname,
        onValueChange = { petname = it },
        label = { Text(stringResource(Res.string.notifications_invite_petname_label)) },
        singleLine = true,
        enabled = !actionState.inProgress,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(12.dp))
    ActionButtons(
        inProgress = actionState.inProgress,
        onDecline = { actions.onDeclineInvite(request) },
        confirmLabel = stringResource(Res.string.notifications_action_confirm),
        // No confirm before the claim has loaded: the proxy refuses an empty shown fingerprint (T-CI-10).
        confirmEnabled = petname.isNotBlank() && request.claimedFingerprint.isNotEmpty(),
        onConfirm = { actions.onConfirmInvite(request, petname.trim(), codesMatch) },
    )
}

@Composable
private fun CardHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onDismiss: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        if (onDismiss != null) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.notifications_action_dismiss),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ActionButtons(
    inProgress: Boolean,
    onDecline: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (inProgress) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        TextButton(onClick = onDecline, enabled = !inProgress) {
            Text(stringResource(Res.string.notifications_action_decline))
        }
        Spacer(Modifier.width(4.dp))
        Button(onClick = onConfirm, enabled = !inProgress && confirmEnabled) {
            Text(confirmLabel)
        }
    }
}
