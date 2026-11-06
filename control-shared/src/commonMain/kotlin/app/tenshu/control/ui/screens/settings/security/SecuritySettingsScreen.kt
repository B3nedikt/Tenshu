package app.tenshu.control.ui.screens.settings.security

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.data.ReauthPromptCopy
import app.tenshu.control.data.rememberBiometricPromptHost
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.reauth_disable_encryption_subtitle
import app.tenshu.control.shared.resources.reauth_disable_encryption_system_reason
import app.tenshu.control.shared.resources.reauth_title
import app.tenshu.control.shared.resources.security_encrypt_on_unlock_headline
import app.tenshu.control.shared.resources.security_encrypt_on_unlock_support
import app.tenshu.control.shared.resources.security_require_unlock_headline
import app.tenshu.control.shared.resources.security_require_unlock_support
import app.tenshu.control.shared.resources.security_require_unlock_unavailable
import app.tenshu.control.shared.resources.security_title
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * Settings → Advanced → Security
 * (`docs/proposals/control-keep-device-unlocked.md` §4.2).
 *
 *  - **Require unlock** — the soft app-lock (Ph1). Live device-local toggle,
 *    default on, disabled when no secure lock screen exists. Gates viewing
 *    Control's UI only — never keys, so background sync is unaffected.
 *  - **Encrypt on unlock only** — the hard key gate (Ph-hard). A child of
 *    "Require unlock" (only tappable when it's on): turning it on runs the
 *    passphrase-floor setup (the reused SetupUnlock flow, via
 *    [onEnableEncryptOnUnlock]); turning it off relaxes back to headless.
 */
@Composable
fun SecuritySettingsScreen(
    viewModel: SecuritySettingsViewModel,
    onEnableEncryptOnUnlock: () -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Re-read hard-mode state whenever the screen resumes — e.g. after returning
    // from the SetupUnlock passphrase flow that enables it.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshEncryptOnUnlock()
        onPauseOrDispose { }
    }

    // Ph-reauth (§6.1, RA-4) — the host has to come from the Compose tree; the VM owns the
    // sequencing so the gate is testable without a UI harness.
    val promptHost = rememberBiometricPromptHost()
    val reauthCopy = ReauthPromptCopy(
        title = stringResource(Res.string.reauth_title),
        subtitle = stringResource(Res.string.reauth_disable_encryption_subtitle),
        systemReason = stringResource(Res.string.reauth_disable_encryption_system_reason),
    )

    SecuritySettingsScreenContent(
        uiState = uiState,
        onRequireUnlockChange = viewModel::onRequireUnlockChanged,
        onEnableEncryptOnUnlock = onEnableEncryptOnUnlock,
        onDisableEncryptOnUnlock = { viewModel.onDisableEncryptOnUnlock(promptHost, reauthCopy) },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreenContent(
    uiState: SecuritySettingsUiState,
    onRequireUnlockChange: (Boolean) -> Unit,
    onEnableEncryptOnUnlock: () -> Unit,
    onDisableEncryptOnUnlock: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            AppTopAppBar(title = stringResource(Res.string.security_title), onBack = onBack)
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
                // (1) Require unlock — soft app-lock. Only interactive when the
                // device can actually present a prompt.
                AppListItem(
                    headline = stringResource(Res.string.security_require_unlock_headline),
                    supportingText = stringResource(
                        if (uiState.available) Res.string.security_require_unlock_support
                        else Res.string.security_require_unlock_unavailable
                    ),
                    leadingContent = { Icon(Icons.Default.Fingerprint, contentDescription = null) },
                    trailingContent = {
                        Switch(
                            checked = uiState.available && uiState.requireUnlock,
                            enabled = uiState.available,
                            onCheckedChange = onRequireUnlockChange,
                        )
                    },
                    onClick = if (uiState.available) {
                        { onRequireUnlockChange(!uiState.requireUnlock) }
                    } else null,
                )

                // (2) Encrypt on unlock only — hard key gate. Child of "Require
                // unlock": only tappable when it's on (and the device can prompt).
                // On ⇒ run passphrase-floor setup; off ⇒ relax to headless.
                val hardEnabled = uiState.requireUnlock && uiState.available && !uiState.busy
                AppListItem(
                    headline = stringResource(Res.string.security_encrypt_on_unlock_headline),
                    supportingText = stringResource(Res.string.security_encrypt_on_unlock_support),
                    leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingContent = {
                        Switch(
                            checked = uiState.encryptOnUnlock,
                            enabled = hardEnabled,
                            onCheckedChange = { on ->
                                if (on) onEnableEncryptOnUnlock() else onDisableEncryptOnUnlock()
                            },
                        )
                    },
                    onClick = if (hardEnabled) {
                        {
                            if (uiState.encryptOnUnlock) onDisableEncryptOnUnlock()
                            else onEnableEncryptOnUnlock()
                        }
                    } else null,
                )
            }
        }
    }
}

@Preview
@Composable
fun SecuritySettingsScreenPreview() {
    Surface {
        SecuritySettingsScreenContent(
            uiState = SecuritySettingsUiState(requireUnlock = true, available = true),
            onRequireUnlockChange = {},
            onEnableEncryptOnUnlock = {},
            onDisableEncryptOnUnlock = {},
            onBack = {},
        )
    }
}
