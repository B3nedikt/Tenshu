package app.tenshu.control.ui.screens.epc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.epc.EpcPairingStatus
import app.tenshu.control.epc.EpcPairingUiState
import app.tenshu.control.epc.EpcPairingViewModel
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.epc_pairing_done
import app.tenshu.control.shared.resources.epc_pairing_error_title
import app.tenshu.control.shared.resources.epc_pairing_instruction
import app.tenshu.control.shared.resources.epc_pairing_manual_label
import app.tenshu.control.shared.resources.epc_pairing_retry
import app.tenshu.control.shared.resources.epc_pairing_sas_instruction
import app.tenshu.control.shared.resources.epc_pairing_sas_title
import app.tenshu.control.shared.resources.epc_pairing_title
import app.tenshu.control.shared.resources.epc_pairing_waiting
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.components.QrCode
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource

/**
 * Proxy-side EPC pairing screen (ios-proxy-architecture.md §10.6; control-ia.md §4.3). This
 * Control acts as the proxy: it provisions a relay rendezvous session and shows the QR (plus a
 * copyable link for the camera-less path). The app scans it, completes the EPC handshake, and the
 * screen surfaces the SAS for the user to confirm against the app — the load-bearing defence
 * against a relay-level MITM (§10.5).
 *
 * [start] runs on first composition; [stop] (which tears the relay session down) runs on dispose
 * — so navigating away ends the pairing cleanly and a re-entry starts a fresh one.
 */
@Composable
fun EpcPairingScreen(
    viewModel: EpcPairingViewModel,
    onClose: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.start() }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    EpcPairingScreenContent(
        uiState = uiState,
        onClose = onClose,
        onRetry = {
            viewModel.stop()
            viewModel.start()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpcPairingScreenContent(
    uiState: EpcPairingUiState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            AppTopAppBar(title = stringResource(Res.string.epc_pairing_title), onBack = onClose)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .appContentWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            when (uiState.status) {
                EpcPairingStatus.Connected -> ConnectedBody(sas = uiState.sas, onClose = onClose)
                EpcPairingStatus.Error -> ErrorBody(error = uiState.error, onRetry = onRetry)
                else -> WaitingBody(pairingUri = uiState.pairingUri)
            }
        }
    }
}

@Composable
private fun WaitingBody(pairingUri: String?) {
    if (pairingUri == null) {
        CircularProgressIndicator()
        return
    }
    QrCode(data = pairingUri, modifier = Modifier.size(240.dp))
    Text(
        text = stringResource(Res.string.epc_pairing_instruction),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(Res.string.epc_pairing_waiting),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(Res.string.epc_pairing_manual_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SelectionContainer {
        Text(
            text = pairingUri,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ConnectedBody(sas: String?, onClose: () -> Unit) {
    Text(
        text = stringResource(Res.string.epc_pairing_sas_title),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    // Render the SAS as "XXX YYY" (3-3 split, monospace) — same convention as the
    // device-pairing wizard so the two confirmation surfaces read alike.
    val display = sas?.let { if (it.length >= 6) "${it.substring(0, 3)} ${it.substring(3)}" else it }.orEmpty()
    Text(
        text = display,
        style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
    )
    Text(
        text = stringResource(Res.string.epc_pairing_sas_instruction),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Button(onClick = onClose) { Text(stringResource(Res.string.epc_pairing_done)) }
}

@Composable
private fun ErrorBody(error: String?, onRetry: () -> Unit) {
    Text(
        text = stringResource(Res.string.epc_pairing_error_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    if (!error.isNullOrBlank()) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
    Button(onClick = onRetry) { Text(stringResource(Res.string.epc_pairing_retry)) }
}
