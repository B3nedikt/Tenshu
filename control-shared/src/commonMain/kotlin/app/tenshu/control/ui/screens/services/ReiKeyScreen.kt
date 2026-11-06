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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.rei_key_body
import app.tenshu.control.shared.resources.rei_key_cancel
import app.tenshu.control.shared.resources.rei_key_checking
import app.tenshu.control.shared.resources.rei_key_continue
import app.tenshu.control.shared.resources.rei_key_heading
import app.tenshu.control.shared.resources.rei_key_label
import app.tenshu.control.shared.resources.rei_key_top_title
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource

@Composable
fun ReiKeyScreen(
    viewModel: ReiKeyViewModel,
    onCancel: () -> Unit,
    onRedeemed: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.linked) {
        if (state.linked) onRedeemed()
    }

    ReiKeyScreenContent(
        key = state.key,
        busy = state.busy,
        error = state.error,
        onKeyChange = viewModel::onKeyChange,
        onContinue = viewModel::onContinue,
        onCancel = onCancel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReiKeyScreenContent(
    key: String,
    busy: Boolean,
    error: String?,
    onKeyChange: (String) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(topBar = { AppTopAppBar(title = stringResource(Res.string.rei_key_top_title)) }) { padding ->
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
                    text = stringResource(Res.string.rei_key_heading),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(Res.string.rei_key_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = onKeyChange,
                    label = { Text(stringResource(Res.string.rei_key_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    enabled = !busy,
                )
                Button(
                    onClick = onContinue,
                    enabled = !busy && key.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (busy) stringResource(Res.string.rei_key_checking)
                        else stringResource(Res.string.rei_key_continue)
                    )
                }
                TextButton(onClick = onCancel, enabled = !busy) {
                    Text(stringResource(Res.string.rei_key_cancel))
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
