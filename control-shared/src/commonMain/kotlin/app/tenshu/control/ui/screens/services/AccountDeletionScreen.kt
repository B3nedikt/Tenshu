package app.tenshu.control.ui.screens.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.account_deletion_acknowledge
import app.tenshu.control.shared.resources.account_deletion_apple
import app.tenshu.control.shared.resources.account_deletion_busy
import app.tenshu.control.shared.resources.account_deletion_cancel
import app.tenshu.control.shared.resources.account_deletion_code_label
import app.tenshu.control.shared.resources.account_deletion_code_resent
import app.tenshu.control.shared.resources.account_deletion_code_sent
import app.tenshu.control.shared.resources.account_deletion_code_sent_generic
import app.tenshu.control.shared.resources.account_deletion_confirm
import app.tenshu.control.shared.resources.account_deletion_deleted_now
import app.tenshu.control.shared.resources.account_deletion_devices
import app.tenshu.control.shared.resources.account_deletion_done_body
import app.tenshu.control.shared.resources.account_deletion_done_close
import app.tenshu.control.shared.resources.account_deletion_done_heading
import app.tenshu.control.shared.resources.account_deletion_error_code
import app.tenshu.control.shared.resources.account_deletion_error_sign_in
import app.tenshu.control.shared.resources.account_deletion_error_signed_out
import app.tenshu.control.shared.resources.account_deletion_error_unavailable
import app.tenshu.control.shared.resources.account_deletion_heading
import app.tenshu.control.shared.resources.account_deletion_kept
import app.tenshu.control.shared.resources.account_deletion_permanent
import app.tenshu.control.shared.resources.account_deletion_resend
import app.tenshu.control.shared.resources.account_deletion_send_code
import app.tenshu.control.shared.resources.account_deletion_sending
import app.tenshu.control.shared.resources.account_deletion_subscriptions
import app.tenshu.control.shared.resources.account_deletion_synced_data
import app.tenshu.control.shared.resources.account_deletion_title
import app.tenshu.control.shared.resources.account_deletion_web_hint
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource

@Composable
fun AccountDeletionScreen(
    viewModel: AccountDeletionViewModel,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AccountDeletionScreenContent(
        state = state,
        onSendCode = viewModel::onSendCode,
        onCodeChange = viewModel::onCodeChange,
        onAcknowledgedChange = viewModel::onAcknowledgedChange,
        onConfirm = viewModel::onConfirm,
        onCancel = onCancel,
        onDone = onDone,
    )
}

/**
 * ⚠ The explanation is shown on **both** steps before the destructive button, not only on the first:
 * what is kept and what cannot be undone must be on screen at the moment of confirming (policy §4.5's
 * counsel note — irreversibility disclosed *before* the action).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDeletionScreenContent(
    state: AccountDeletionUiState,
    onSendCode: () -> Unit,
    onCodeChange: (String) -> Unit,
    onAcknowledgedChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(topBar = { AppTopAppBar(title = stringResource(Res.string.account_deletion_title)) }) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .appContentWidth()
                    .widthIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.step == AccountDeletionStep.Deleted) {
                    Text(
                        stringResource(Res.string.account_deletion_done_heading),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(stringResource(Res.string.account_deletion_done_body), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(Res.string.account_deletion_apple), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.account_deletion_done_close))
                    }
                    return@Column
                }

                Text(stringResource(Res.string.account_deletion_heading), style = MaterialTheme.typography.headlineSmall)
                Explanation()

                if (state.step == AccountDeletionStep.Explain) {
                    Button(
                        onClick = onSendCode,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (state.busy) Res.string.account_deletion_sending else Res.string.account_deletion_send_code,
                            ),
                        )
                    }
                } else {
                    val email = state.email
                    Text(
                        text = when {
                            email == null -> stringResource(Res.string.account_deletion_code_sent_generic)
                            state.resent -> stringResource(Res.string.account_deletion_code_resent, email)
                            else -> stringResource(Res.string.account_deletion_code_sent, email)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = onCodeChange,
                        label = { Text(stringResource(Res.string.account_deletion_code_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.acknowledged,
                            onCheckedChange = onAcknowledgedChange,
                            enabled = !state.busy,
                        )
                        Text(
                            stringResource(Res.string.account_deletion_acknowledge),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = state.canConfirm,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(
                            stringResource(
                                if (state.busy) Res.string.account_deletion_busy else Res.string.account_deletion_confirm,
                            ),
                        )
                    }
                    TextButton(onClick = onSendCode, enabled = !state.busy) {
                        Text(stringResource(Res.string.account_deletion_resend))
                    }
                }

                state.error?.let { error ->
                    Text(
                        text = when (error) {
                            AccountDeletionError.InvalidCode -> stringResource(Res.string.account_deletion_error_code)
                            AccountDeletionError.SignInRequired -> stringResource(Res.string.account_deletion_error_sign_in)
                            AccountDeletionError.SignedOut -> stringResource(Res.string.account_deletion_error_signed_out)
                            AccountDeletionError.Unavailable -> stringResource(Res.string.account_deletion_error_unavailable)
                            is AccountDeletionError.Message -> error.text
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                OutlinedButton(onClick = onCancel, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.account_deletion_cancel))
                }
                Text(
                    stringResource(Res.string.account_deletion_web_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Explanation() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (line in listOf(
            Res.string.account_deletion_deleted_now,
            Res.string.account_deletion_subscriptions,
            Res.string.account_deletion_apple,
            Res.string.account_deletion_synced_data,
            Res.string.account_deletion_kept,
            Res.string.account_deletion_devices,
        )) {
            Text(stringResource(line), style = MaterialTheme.typography.bodyMedium)
        }
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
            Text(
                text = stringResource(Res.string.account_deletion_permanent),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
            )
        }
    }
}
