package app.tenshu.contacts.ui.screens.invite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.contacts.ui.error.contactsLabel
import app.tenshu.contacts.shared.resources.Res
import app.tenshu.contacts.shared.resources.contacts_invite_copy_link
import app.tenshu.contacts.shared.resources.contacts_invite_create
import app.tenshu.contacts.shared.resources.contacts_invite_creating
import app.tenshu.contacts.shared.resources.contacts_invite_intro
import app.tenshu.contacts.shared.resources.contacts_invite_link_caption
import app.tenshu.contacts.shared.resources.contacts_invite_one_session_warning
import app.tenshu.contacts.shared.resources.contacts_invite_outstanding
import app.tenshu.contacts.shared.resources.contacts_invite_qr_description
import app.tenshu.contacts.shared.resources.contacts_invite_revoke
import app.tenshu.contacts.shared.resources.contacts_invite_title
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.components.QrCode
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource

/**
 * *Invite someone* from the own profile card (`docs/proposals/contacts-app.md` D6). Mints a
 * bare invitation and hands over the link, as a QR to scan in person and as text to send.
 *
 * ⚠ **The link is shown once.** It carries `K_link`, which is never persisted and never
 * reaches the relay, so there is no *show again* — only *revoke and reissue*. The copy says
 * so before the user closes the sheet, because the alternative is discovering it afterwards.
 */
@Composable
fun InviteSheetContent(viewModel: InviteViewModel) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m),
    ) {
        Text(
            text = stringResource(Res.string.contacts_invite_title),
            style = MaterialTheme.typography.titleLarge,
        )

        val link = state.link
        if (link == null) {
            Text(
                text = stringResource(Res.string.contacts_invite_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = viewModel::onCreateClicked, enabled = !state.isCreating) {
                Text(
                    stringResource(
                        if (state.isCreating) {
                            Res.string.contacts_invite_creating
                        } else {
                            Res.string.contacts_invite_create
                        }
                    )
                )
            }
        } else {
            QrCode(
                data = link,
                modifier = Modifier.size(220.dp),
                contentDescription = stringResource(Res.string.contacts_invite_qr_description),
            )
            Text(
                text = stringResource(Res.string.contacts_invite_link_caption),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            @Suppress("DEPRECATION")
            val clipboard = LocalClipboardManager.current
            Button(onClick = { clipboard.setText(AnnotatedString(link)) }) {
                Text(stringResource(Res.string.contacts_invite_copy_link))
            }
            Text(
                text = stringResource(Res.string.contacts_invite_one_session_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        state.errorCopyKey?.let { key ->
            Text(
                text = key.contactsLabel(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        if (state.outstanding.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.contacts_invite_outstanding),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.s),
            )
            state.outstanding.forEach { invite ->
                AppListItem(
                    // There is no name to show — nobody has answered yet, and the invite is
                    // a link, not a person. The id is the only honest handle.
                    headline = invite.inviteId,
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { viewModel.onRevokeClicked(invite.inviteId) }) {
                                Text(stringResource(Res.string.contacts_invite_revoke))
                            }
                        }
                    },
                )
            }
        }
    }
}
