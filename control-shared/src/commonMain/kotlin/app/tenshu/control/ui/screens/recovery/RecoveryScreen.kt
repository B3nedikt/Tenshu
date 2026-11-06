package app.tenshu.control.ui.screens.recovery

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.recovery_entry_body
import app.tenshu.control.shared.resources.recovery_entry_recover
import app.tenshu.control.shared.resources.recovery_entry_slow_note
import app.tenshu.control.shared.resources.recovery_entry_troubleshooting
import app.tenshu.control.shared.resources.recovery_entry_warning
import app.tenshu.control.shared.resources.recovery_entry_word_count
import app.tenshu.control.shared.resources.recovery_error_checksum_mismatch
import app.tenshu.control.shared.resources.recovery_error_invalid_length
import app.tenshu.control.shared.resources.recovery_error_phrase_rejected
import app.tenshu.control.shared.resources.recovery_error_unknown_word
import app.tenshu.control.shared.resources.recovery_processing_body
import app.tenshu.control.shared.resources.recovery_processing_heading
import app.tenshu.control.shared.resources.recovery_success_body
import app.tenshu.control.shared.resources.recovery_success_continue
import app.tenshu.control.shared.resources.recovery_success_heading
import app.tenshu.control.shared.resources.recovery_success_sync_note
import app.tenshu.control.shared.resources.recovery_title_entry
import app.tenshu.control.shared.resources.recovery_title_processing
import app.tenshu.control.shared.resources.recovery_title_success
import app.tenshu.ui.error.label
import app.tenshu.ui.chrome.AppTopAppBar
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * §7 recovery flow per `docs/proposals/control-onboarding-ux.md`. Wraps
 * the Bip39 decode → RecoveryClaim sequence; surfaces typed
 * `Bip39InvalidMnemonicException` errors before the relay round-trip.
 *
 * Out of scope for this slice: the spec's 4×6 grid of 24 individual word
 * inputs with per-word BIP-39 autocomplete (the wordlist isn't exposed
 * yet — autocomplete needs that exposure first; falls back to a single
 * multiline TextField + word counter); typed non-cryptographic errors
 * (no-backup-blob / network / KDF-out-of-date) — surfaces the underlying
 * TError message until `RecoveryClaim` returns structured failure
 * variants. Spec §7.3 → §4a.6 (local-unlock setup) collapses to "land
 * in Home" pending Track C #11.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit,
) {
    val viewModel = koinViewModel<RecoveryViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.step) {
        if (state.step == RecoveryStep.Done) onComplete()
    }

    Scaffold(
        topBar = {
            AppTopAppBar(
                title = recoveryTitleText(state.step),
                onBack = {
                    when (state.step) {
                        RecoveryStep.Entry -> onBack()
                        // Processing/Success/Done are non-cancellable from
                        // the back button; user has to wait or continue.
                        RecoveryStep.Processing,
                        RecoveryStep.Success,
                        RecoveryStep.Done -> Unit
                    }
                },
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (state.step) {
                    RecoveryStep.Entry -> EntryStep(
                        words = state.words,
                        wordCount = state.wordCount,
                        error = state.error,
                        failureCount = state.failureCount,
                        onWordChange = viewModel::onWordChange,
                        onRecover = viewModel::onRecover,
                    )
                    RecoveryStep.Processing -> ProcessingStep()
                    RecoveryStep.Success -> SuccessStep(
                        onContinue = viewModel::onSuccessContinue,
                    )
                    RecoveryStep.Done -> CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun recoveryTitleText(step: RecoveryStep): String = when (step) {
    RecoveryStep.Entry -> stringResource(Res.string.recovery_title_entry)
    RecoveryStep.Processing -> stringResource(Res.string.recovery_title_processing)
    RecoveryStep.Success -> stringResource(Res.string.recovery_title_success)
    RecoveryStep.Done -> ""
}

@Composable
private fun EntryStep(
    words: List<String>,
    wordCount: Int,
    error: RecoveryError?,
    failureCount: Int,
    onWordChange: (Int, String) -> Unit,
    onRecover: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.recovery_title_entry),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(Res.string.recovery_entry_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(Res.string.recovery_entry_slow_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = stringResource(Res.string.recovery_entry_word_count, wordCount),
        style = MaterialTheme.typography.bodySmall,
        color = if (wordCount == 24) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    MnemonicGrid(
        words = words,
        onWordChange = onWordChange,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = stringResource(Res.string.recovery_entry_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
    Spacer(modifier = Modifier.height(20.dp))
    Button(
        onClick = onRecover,
        enabled = wordCount == 24,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.recovery_entry_recover))
    }
    error?.let {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = errorCopy(it),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
    if (failureCount >= 5) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(Res.string.recovery_entry_troubleshooting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun errorCopy(error: RecoveryError): String = when (error) {
    is RecoveryError.UnknownWord ->
        stringResource(Res.string.recovery_error_unknown_word, error.word)
    is RecoveryError.InvalidLength ->
        stringResource(Res.string.recovery_error_invalid_length, error.wordCount)
    RecoveryError.ChecksumMismatch -> stringResource(Res.string.recovery_error_checksum_mismatch)
    RecoveryError.PhraseRejected -> stringResource(Res.string.recovery_error_phrase_rejected)
    // ⚠⚠ From the shared catalogue by key, never from a message — `core-error-copy-catalogue.md`
    // §5.0. This is the branch that used to render whatever sentence the proxy happened to write.
    is RecoveryError.Unexpected -> error.copyKey.label()
}

@Composable
private fun ProcessingStep() {
    Text(
        text = stringResource(Res.string.recovery_processing_heading),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(Res.string.recovery_processing_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(32.dp))
    CircularProgressIndicator()
}

@Composable
private fun SuccessStep(onContinue: () -> Unit) {
    Text(
        text = stringResource(Res.string.recovery_success_heading),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(Res.string.recovery_success_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(Res.string.recovery_success_sync_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(32.dp))
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.recovery_success_continue))
    }
}
