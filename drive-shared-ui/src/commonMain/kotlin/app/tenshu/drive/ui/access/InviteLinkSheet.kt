package app.tenshu.drive.ui.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.invite_link_cancel
import app.tenshu.drive.shared.resources.invite_link_copy
import app.tenshu.drive.shared.resources.invite_link_disclosure
import app.tenshu.drive.shared.resources.invite_link_done
import app.tenshu.drive.shared.resources.invite_link_error
import app.tenshu.drive.shared.resources.invite_link_generate
import app.tenshu.drive.shared.resources.invite_link_generating
import app.tenshu.drive.shared.resources.invite_link_grant_parked
import app.tenshu.drive.shared.resources.invite_link_one_session
import app.tenshu.drive.shared.resources.invite_link_qr_description
import app.tenshu.drive.shared.resources.invite_link_share_subject
import app.tenshu.drive.shared.resources.invite_link_show_link
import app.tenshu.drive.shared.resources.invite_link_show_qr
import app.tenshu.drive.shared.resources.invite_link_title
import app.tenshu.drive.ui.common.rememberSensitiveClipboard
import app.tenshu.drive.ui.common.rememberShareTextLauncher
import app.tenshu.drive.ui.common.shareActionLabel
import app.tenshu.ui.components.QrCode
import app.tenshu.ui.error.label
import org.jetbrains.compose.resources.stringResource
import org.tenshu.core.common.util.InviteLinkPayload

/**
 * Invite-link sheet (`docs/proposals/drive-manage-access-ux.md` §2.2) — the
 * `CreateShareLinkSheet` shape: disclosure copy → *Create invite link* →
 * result with **Share** (the platform hand-off — see `ui/common/ShareText.kt`;
 * relabelled *Send by email* on desktop, and folded into *Copy* where there is
 * no hand-off at all) + secondary **Copy**. The grant-parked honesty line shows
 * when invoked from Manage access (a folder name is present).
 */
@Composable
fun InviteLinkSheet(
    viewModel: InviteLinkViewModel,
    onDismiss: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboard = rememberSensitiveClipboard()
    val shareLauncher = rememberShareTextLauncher()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(Res.string.invite_link_title),
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = stringResource(Res.string.invite_link_disclosure),
            style = MaterialTheme.typography.bodyMedium
        )

        if (uiState.folderName.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.invite_link_grant_parked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val link = uiState.link
        if (link != null) {
            // §5.2 QR toggle: same payload, the QR-URI encoding (denser, scanner-friendly).
            var showQr by remember { mutableStateOf(false) }
            if (showQr) {
                val qrUri = remember(link) { InviteLinkPayload.parse(link).toQrUri() }
                QrCode(
                    data = qrUri,
                    modifier = Modifier.size(220.dp).align(Alignment.CenterHorizontally),
                    contentDescription = stringResource(Res.string.invite_link_qr_description),
                )
            } else {
                Text(
                    text = truncateMiddle(link),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            TextButton(onClick = { showQr = !showQr }) {
                Text(
                    stringResource(
                        if (showQr) Res.string.invite_link_show_link
                        else Res.string.invite_link_show_qr
                    )
                )
            }
            Text(
                text = stringResource(Res.string.invite_link_one_session),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val subject = stringResource(Res.string.invite_link_share_subject)
                val canShare = shareLauncher.canShareNatively
                Button(
                    onClick = {
                        if (canShare) shareLauncher.share(link, subject) else clipboard.copy(link)
                    }
                ) {
                    Text(
                        if (canShare) shareActionLabel(shareLauncher.target)
                        else stringResource(Res.string.invite_link_copy)
                    )
                }
                if (shareLauncher.canShareNatively) {
                    OutlinedButton(onClick = { clipboard.copy(link) }) {
                        Text(stringResource(Res.string.invite_link_copy))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.invite_link_done))
                }
            }
        } else if (uiState.isCreating) {
            Text(
                text = stringResource(Res.string.invite_link_generating),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = viewModel::onGenerateClicked) {
                    Text(stringResource(Res.string.invite_link_generate))
                }
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.invite_link_cancel))
                }
            }
        }

        uiState.errorCopyKey?.let { key ->
            Text(
                text = stringResource(Res.string.invite_link_error, key.label()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** `https://tenshu.app/i#v1.abcd…wxyz` — keeps both ends recognisable. */
internal fun truncateMiddle(text: String, maxLength: Int = 48): String =
    if (text.length <= maxLength) text
    else text.take(maxLength / 2 - 1) + "…" + text.takeLast(maxLength / 2 - 1)
