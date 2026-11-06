package app.tenshu.control.ui.screens.settings.unlock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.data.BiometricPromptCopy
import app.tenshu.control.data.BiometricPromptHost
import app.tenshu.control.data.rememberBiometricPromptHost
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.biometric_prompt_enable_title
import app.tenshu.control.shared.resources.biometric_prompt_setup_negative
import app.tenshu.control.shared.resources.biometric_prompt_setup_subtitle
import app.tenshu.control.shared.resources.unlock_settings_available
import app.tenshu.control.shared.resources.unlock_settings_dismiss
import app.tenshu.control.shared.resources.unlock_settings_enabled
import app.tenshu.control.shared.resources.unlock_settings_error_biometric_enroll_failed
import app.tenshu.control.shared.resources.unlock_settings_error_biometric_unavailable
import app.tenshu.control.shared.resources.unlock_settings_error_enable_biometric_failed
import app.tenshu.control.shared.resources.unlock_settings_error_passphrase_required
import app.tenshu.control.shared.resources.unlock_settings_headline
import app.tenshu.control.shared.resources.unlock_settings_title
import app.tenshu.control.shared.resources.unlock_settings_unavailable
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun UnlockSettingsScreen(
    viewModel: UnlockSettingsViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val promptHost = rememberBiometricPromptHost()
    val promptCopy = BiometricPromptCopy(
        title = stringResource(Res.string.biometric_prompt_enable_title),
        subtitle = stringResource(Res.string.biometric_prompt_setup_subtitle),
        negativeButton = stringResource(Res.string.biometric_prompt_setup_negative),
    )
    UnlockSettingsScreenContent(
        uiState = uiState,
        promptHost = promptHost,
        onToggle = { enabled, host -> viewModel.onToggle(enabled, host, promptCopy) },
        onDismissError = viewModel::onDismissError,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockSettingsScreenContent(
    uiState: UnlockSettingsUiState,
    promptHost: BiometricPromptHost?,
    onToggle: (Boolean, BiometricPromptHost?) -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            AppTopAppBar(title = stringResource(Res.string.unlock_settings_title), onBack = onBack)
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .appContentWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                val supporting = when {
                    !uiState.biometricAvailable -> stringResource(Res.string.unlock_settings_unavailable)
                    uiState.biometricEnabled -> stringResource(Res.string.unlock_settings_enabled)
                    else -> stringResource(Res.string.unlock_settings_available)
                }
                AppListItem(
                    headline = stringResource(Res.string.unlock_settings_headline),
                    supportingText = supporting,
                    leadingContent = {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                    },
                    trailingContent = {
                        Switch(
                            checked = uiState.biometricEnabled,
                            enabled = uiState.biometricAvailable && !uiState.busy,
                            onCheckedChange = { enabled -> onToggle(enabled, promptHost) },
                        )
                    },
                    onClick = {
                        if (uiState.biometricAvailable && !uiState.busy) {
                            onToggle(!uiState.biometricEnabled, promptHost)
                        }
                    },
                )

                uiState.error?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error.label(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                    TextButton(
                        onClick = onDismissError,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(stringResource(Res.string.unlock_settings_dismiss))
                    }
                }
            }
        }
    }
}

@Composable
private fun UnlockSettingsError.label(): String = when (this) {
    UnlockSettingsError.BiometricUnavailable ->
        stringResource(Res.string.unlock_settings_error_biometric_unavailable)

    UnlockSettingsError.EnableBiometricFailed ->
        stringResource(Res.string.unlock_settings_error_enable_biometric_failed)

    is UnlockSettingsError.BiometricEnrollFailed ->
        detail ?: stringResource(Res.string.unlock_settings_error_biometric_enroll_failed)

    UnlockSettingsError.PassphraseRequired ->
        stringResource(Res.string.unlock_settings_error_passphrase_required)
}

@Preview
@Composable
fun UnlockSettingsScreenAvailableAndEnabledPreview() {
    Surface {
        UnlockSettingsScreenContent(
            uiState = UnlockSettingsUiState(biometricAvailable = true, biometricEnabled = true),
            promptHost = null,
            onToggle = { _, _ -> },
            onDismissError = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
fun UnlockSettingsScreenUnavailablePreview() {
    Surface {
        UnlockSettingsScreenContent(
            uiState = UnlockSettingsUiState(biometricAvailable = false),
            promptHost = null,
            onToggle = { _, _ -> },
            onDismissError = {},
            onBack = {},
        )
    }
}
