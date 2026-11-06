package app.tenshu.drive.ui.share

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.create_share_link_cancel
import app.tenshu.drive.shared.resources.create_share_link_copied
import app.tenshu.drive.shared.resources.create_share_link_copy
import app.tenshu.drive.shared.resources.create_share_link_disclosure
import app.tenshu.drive.shared.resources.create_share_link_done
import app.tenshu.drive.shared.resources.create_share_link_error
import app.tenshu.drive.shared.resources.create_share_link_generate
import app.tenshu.drive.shared.resources.create_share_link_generating
import app.tenshu.drive.shared.resources.create_share_link_share_subject
import app.tenshu.drive.shared.resources.create_share_link_title
import app.tenshu.drive.ui.common.rememberSensitiveClipboard
import app.tenshu.drive.ui.common.rememberShareTextLauncher
import app.tenshu.drive.ui.common.shareActionLabel
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Compose sheet for creating a Mode-A share link
 * (`docs/file-sharing.md`). Renders three stages:
 *
 *  1. *Idle* — file name + disclosure copy + *Generate link* button.
 *  2. *Creating* — progress affordance while the op is in flight.
 *  3. *Done* — the generated URL + *Share* / *Copy link* + *Done* dismiss.
 *
 * *Share* and *Copy link* are both offered (where the platform has a share-out
 * at all): handing the link straight to a messenger and putting it on the
 * clipboard for somewhere else are different intents, so neither is a fallback
 * for the other. Both go through `ShareText.kt` — see there for why the Android
 * chooser is the whole of the WhatsApp/Signal/Messages story, and why the copy
 * is marked sensitive.
 *
 * The disclosure copy ("Anyone with this link can view this file") is T-S-01
 * defence per the threat-model amendment (§5.2) — the only technical signal
 * we can give the user about capability-as-URL semantics. The sheet stays
 * open after generation so the user can re-copy / adjust options.
 *
 * Advanced (expiry / max opens) is intentionally minimal in v1: the values
 * are wired through the VM but the sheet only shows the disclosure + the
 * generated URL + Done for now. A future iteration adds the picker widgets
 * (§3.6 / §6.4 collapsible block).
 */
@Composable
fun CreateShareLinkSheet(
    viewModel: CreateShareLinkViewModel,
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
            text = stringResource(Res.string.create_share_link_title, uiState.fileName),
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = stringResource(Res.string.create_share_link_disclosure),
            style = MaterialTheme.typography.bodyMedium
        )

        val link = uiState.link
        if (link != null) {
            val url = link.toCopyableString()
            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium
            )
            // The clipboard write is silent on every platform, so say it landed.
            var copied by remember(url) { mutableStateOf(false) }
            LaunchedEffect(copied) {
                if (copied) {
                    delay(COPIED_FEEDBACK_MS)
                    copied = false
                }
            }
            val subject = stringResource(
                Res.string.create_share_link_share_subject,
                uiState.fileName,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (shareLauncher.canShareNatively) {
                    Button(onClick = { shareLauncher.share(url, subject) }) {
                        Text(shareActionLabel(shareLauncher.target))
                    }
                    OutlinedButton(
                        onClick = {
                            clipboard.copy(url)
                            copied = true
                        }
                    ) {
                        Text(copyLabel(copied))
                    }
                } else {
                    Button(
                        onClick = {
                            clipboard.copy(url)
                            copied = true
                        }
                    ) {
                        Text(copyLabel(copied))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.create_share_link_done))
                }
            }
        } else if (uiState.isCreating) {
            Text(
                text = stringResource(Res.string.create_share_link_generating),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = viewModel::onGenerateLinkClicked) {
                    Text(stringResource(Res.string.create_share_link_generate))
                }
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.create_share_link_cancel))
                }
            }
        }

        uiState.errorMessage?.let { message ->
            Text(
                text = stringResource(Res.string.create_share_link_error, message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun copyLabel(copied: Boolean): String = stringResource(
    if (copied) Res.string.create_share_link_copied else Res.string.create_share_link_copy
)

private const val COPIED_FEEDBACK_MS = 2_000L
