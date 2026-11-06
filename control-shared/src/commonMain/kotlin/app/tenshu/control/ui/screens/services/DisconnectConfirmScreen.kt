package app.tenshu.control.ui.screens.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.disconnect_busy
import app.tenshu.control.shared.resources.disconnect_cancel
import app.tenshu.control.shared.resources.disconnect_confirm
import app.tenshu.control.shared.resources.disconnect_heading
import app.tenshu.control.shared.resources.disconnect_passphrase_label
import app.tenshu.control.shared.resources.disconnect_title
import app.tenshu.control.shared.resources.disconnect_warning
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource

@Composable
fun DisconnectConfirmScreen(
    viewModel: DisconnectConfirmViewModel,
    onCancel: () -> Unit,
    onDisconnected: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.disconnected) {
        if (state.disconnected) onDisconnected()
    }

    DisconnectConfirmScreenContent(
        passphrase = state.passphrase,
        requiresPassphrase = state.requiresPassphrase,
        busy = state.busy || state.resolving,
        error = state.error,
        onPassphraseChange = viewModel::onPassphraseChange,
        onConfirm = viewModel::onConfirm,
        onCancel = onCancel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisconnectConfirmScreenContent(
    passphrase: String,
    /**
     * ⚠ When false there is NO local-unlock passphrase on this device, so the field is omitted
     * entirely rather than rendered-and-unsatisfiable. Showing it anyway is the defect this
     * parameter exists to prevent: `unlockLocal` returns false with no `UNLOCK_WRAPPER`, so every
     * input read as "Passphrase doesn't match" and disconnect could not be completed at all.
     */
    requiresPassphrase: Boolean,
    busy: Boolean,
    error: String?,
    onPassphraseChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(topBar = { AppTopAppBar(title = stringResource(Res.string.disconnect_title)) }) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .appContentWidth()
                    .widthIn(max = 480.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(Res.string.disconnect_heading),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = stringResource(Res.string.disconnect_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (requiresPassphrase) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = onPassphraseChange,
                        label = { Text(stringResource(Res.string.disconnect_passphrase_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                }
                Button(
                    onClick = onCancel,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.disconnect_cancel))
                }
                Button(
                    onClick = onConfirm,
                    // ⚠ The non-blank requirement applies ONLY when a passphrase is actually being
                    // asked for. Gating on it unconditionally is the other half of the dead end:
                    // with no passphrase configured there is no field, so `passphrase` is
                    // permanently blank and the confirm button could never enable.
                    enabled = !busy && (!requiresPassphrase || passphrase.isNotBlank()),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(
                        if (busy) stringResource(Res.string.disconnect_busy)
                        else stringResource(Res.string.disconnect_confirm)
                    )
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
