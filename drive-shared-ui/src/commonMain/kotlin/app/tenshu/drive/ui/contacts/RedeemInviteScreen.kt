package app.tenshu.drive.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.ui.error.label
import app.tenshu.drive.data.InviteAckModel
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.contact_prompt_unnamed
import app.tenshu.drive.shared.resources.redeem_invite_accepted_body
import app.tenshu.drive.shared.resources.redeem_invite_accepted_intent
import app.tenshu.drive.shared.resources.redeem_invite_accepted_title
import app.tenshu.drive.shared.resources.redeem_invite_close
import app.tenshu.drive.shared.resources.redeem_invite_confirm
import app.tenshu.drive.shared.resources.redeem_invite_error
import app.tenshu.drive.shared.resources.redeem_invite_gone
import app.tenshu.drive.shared.resources.redeem_invite_invalid
import app.tenshu.drive.shared.resources.redeem_invite_inviter_fingerprint
import app.tenshu.drive.shared.resources.redeem_invite_name_label
import app.tenshu.drive.shared.resources.redeem_invite_name_note
import app.tenshu.drive.shared.resources.redeem_invite_paste_label
import app.tenshu.drive.shared.resources.redeem_invite_paste_malformed
import app.tenshu.drive.shared.resources.redeem_invite_paste_unsupported
import app.tenshu.drive.shared.resources.redeem_invite_rejected
import app.tenshu.drive.shared.resources.redeem_invite_scan_qr
import app.tenshu.drive.shared.resources.redeem_invite_scan_stop
import app.tenshu.drive.shared.resources.redeem_invite_title
import app.tenshu.drive.shared.resources.redeem_invite_waiting
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.components.QrScanner
import org.jetbrains.compose.resources.stringResource
import org.tenshu.contact.ContactInvite

/**
 * The invitee's redeem screen (`docs/proposals/drive-manage-access-ux.md` §3):
 * paste/parse → "your name as they'll see it" → waiting → terminal states.
 * Deep links and the Shared screen's paste entry both land here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedeemInviteScreen(
    viewModel: RedeemInviteViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            AppTopAppBar(
                title = stringResource(Res.string.redeem_invite_title),
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val stage = uiState.stage) {
                RedeemStage.EnterLink -> EnterLinkContent(uiState, viewModel)
                RedeemStage.ConfirmName -> ConfirmNameContent(uiState, viewModel)
                RedeemStage.Waiting -> WaitingContent(uiState, viewModel)
                is RedeemStage.Accepted -> AcceptedContent(stage.ack, onBack)
                RedeemStage.Rejected ->
                    TerminalContent(stringResource(Res.string.redeem_invite_rejected), onBack)
                RedeemStage.Gone ->
                    TerminalContent(stringResource(Res.string.redeem_invite_gone), onBack)
                RedeemStage.Invalid ->
                    TerminalContent(stringResource(Res.string.redeem_invite_invalid), onBack)
            }
        }
    }
}

@Composable
private fun EnterLinkContent(
    uiState: RedeemInviteUiState,
    viewModel: RedeemInviteViewModel,
) {
    OutlinedTextField(
        value = uiState.pastedLink,
        onValueChange = viewModel::onPastedLinkChanged,
        label = { Text(stringResource(Res.string.redeem_invite_paste_label)) },
        isError = uiState.pasteError != null,
        supportingText = {
            uiState.pasteError?.let { error ->
                Text(
                    stringResource(
                        when (error) {
                            PasteError.MALFORMED -> Res.string.redeem_invite_paste_malformed
                            PasteError.UNSUPPORTED_VERSION ->
                                Res.string.redeem_invite_paste_unsupported
                        }
                    )
                )
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { viewModel.onLinkEntered() },
        enabled = uiState.pastedLink.isNotBlank(),
    ) {
        Text(stringResource(Res.string.redeem_invite_confirm))
    }

    // §5.3 Scan QR — `:ui-qr-scanner` (camera on Android and macOS; elsewhere
    // it says "not available", so paste stays the way in). A successful scan
    // feeds the same parse path as paste.
    var scanning by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { scanning = !scanning }) {
        Text(
            stringResource(
                if (scanning) Res.string.redeem_invite_scan_stop
                else Res.string.redeem_invite_scan_qr
            )
        )
    }
    if (scanning) {
        QrScanner(
            onResult = { scanned -> viewModel.onLinkEntered(scanned) },
            modifier = Modifier.fillMaxWidth().height(320.dp),
        )
    }
}

@Composable
private fun ConfirmNameContent(
    uiState: RedeemInviteUiState,
    viewModel: RedeemInviteViewModel,
) {
    Text(
        text = stringResource(
            Res.string.redeem_invite_inviter_fingerprint,
            uiState.inviterFingerprint,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = uiState.selfName,
        onValueChange = viewModel::onSelfNameChanged,
        label = { Text(stringResource(Res.string.redeem_invite_name_label)) },
        supportingText = { Text(stringResource(Res.string.redeem_invite_name_note)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = viewModel::onConfirmClicked,
        enabled = uiState.selfName.isNotBlank() && !uiState.isSubmitting,
    ) {
        Text(stringResource(Res.string.redeem_invite_confirm))
    }
    uiState.errorCopyKey?.let { key ->
        Text(
            // ⚠⚠ The catalogue's sentence, not the proxy's. `redeem_invite_error` frames it
            // ("Couldn't redeem the invite: …"); what fills the slot used to be whatever English
            // `RedeemContactInviteImplementation` happened to write (§5.0).
            text = stringResource(Res.string.redeem_invite_error, key.label()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun WaitingContent(
    uiState: RedeemInviteUiState,
    viewModel: RedeemInviteViewModel,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        Text(
            text = stringResource(Res.string.redeem_invite_waiting),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    // §5.3: both sides can compare the code while the inviter confirms —
    // checking it makes the ACCEPTED close mint VERIFIED/QR_SAS.
    if (uiState.sas.isNotEmpty()) {
        SasConfirmPanel(
            sas = uiState.sas,
            peerFingerprint = uiState.inviterFingerprint,
            confirmed = uiState.sasConfirmed,
            onConfirmedChange = viewModel::onSasConfirmedChanged,
        )
    }
}

@Composable
private fun AcceptedContent(ack: InviteAckModel, onBack: () -> Unit) {
    val peerName = ack.peerName.ifEmpty { stringResource(Res.string.contact_prompt_unnamed) }
    Text(
        text = stringResource(Res.string.redeem_invite_accepted_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = stringResource(
            Res.string.redeem_invite_accepted_body,
            peerName,
            displayFingerprint(ack.peerFingerprint),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (ack.intentKind == ContactInvite.IntentKind.SHARE_FOLDER) {
        // The grant is parked (§1) — say so honestly instead of pretending.
        Text(
            text = stringResource(
                Res.string.redeem_invite_accepted_intent,
                peerName,
                ack.intentLabel,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = onBack) {
        Text(stringResource(Res.string.redeem_invite_close))
    }
}

@Composable
private fun TerminalContent(message: String, onBack: () -> Unit) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onClick = onBack) {
        Text(stringResource(Res.string.redeem_invite_close))
    }
}
