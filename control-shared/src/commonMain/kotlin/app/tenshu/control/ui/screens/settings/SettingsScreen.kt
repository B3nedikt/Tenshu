package app.tenshu.control.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.settings_advanced_headline
import app.tenshu.control.shared.resources.settings_advanced_support
import app.tenshu.control.shared.resources.settings_background_ingest_headline
import app.tenshu.control.shared.resources.settings_background_ingest_support
import app.tenshu.control.shared.resources.settings_background_sync_headline
import app.tenshu.control.shared.resources.settings_background_sync_support
import app.tenshu.control.shared.resources.forget_device_headline
import app.tenshu.control.shared.resources.forget_device_support
import app.tenshu.control.shared.resources.settings_recovery_headline
import app.tenshu.control.shared.resources.settings_recovery_support
import app.tenshu.control.shared.resources.settings_sync_headline
import app.tenshu.control.shared.resources.settings_sync_support
import app.tenshu.control.shared.resources.settings_title
import app.tenshu.control.shared.resources.settings_unlock_headline
import app.tenshu.control.shared.resources.settings_unlock_support
import app.tenshu.ui.legal.OpenSourceLicencesListItem
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import app.tenshu.ui.legal.AppPrivacyPolicyListItem
import app.tenshu.ui.legal.AppTermsOfUseListItem
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun SettingsScreen(
    onSyncClick: () -> Unit,
    onBackgroundSyncClick: () -> Unit,
    onBackgroundIngestClick: () -> Unit = {},
    onRecoverySetupClick: () -> Unit,
    onForgetDeviceClick: () -> Unit,
    onUnlockClick: () -> Unit,
    onErrorsClick: () -> Unit = {},
    onOfflineMapsClick: () -> Unit = {},
    onModelsClick: () -> Unit = {},
    onAdvancedClick: () -> Unit = {},
    onLicencesClick: () -> Unit = {},
) {
    SettingsScreenContent(
        onSyncClick = onSyncClick,
        onBackgroundSyncClick = onBackgroundSyncClick,
        onBackgroundIngestClick = onBackgroundIngestClick,
        onRecoverySetupClick = onRecoverySetupClick,
        onForgetDeviceClick = onForgetDeviceClick,
        onUnlockClick = onUnlockClick,
        onErrorsClick = onErrorsClick,
        onOfflineMapsClick = onOfflineMapsClick,
        onModelsClick = onModelsClick,
        onAdvancedClick = onAdvancedClick,
        onLicencesClick = onLicencesClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    onSyncClick: () -> Unit,
    onBackgroundSyncClick: () -> Unit,
    onBackgroundIngestClick: () -> Unit = {},
    onRecoverySetupClick: () -> Unit,
    onForgetDeviceClick: () -> Unit,
    onUnlockClick: () -> Unit,
    onErrorsClick: () -> Unit = {},
    onOfflineMapsClick: () -> Unit = {},
    onModelsClick: () -> Unit = {},
    onAdvancedClick: () -> Unit = {},
    onLicencesClick: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            AppTopAppBar(title = stringResource(Res.string.settings_title))
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
                AppListItem(
                    headline = stringResource(Res.string.settings_sync_headline),
                    supportingText = stringResource(Res.string.settings_sync_support),
                    leadingContent = { Icon(Icons.Default.Sync, contentDescription = null) },
                    onClick = onSyncClick
                )
                AppListItem(
                    headline = stringResource(Res.string.settings_background_sync_headline),
                    supportingText = stringResource(Res.string.settings_background_sync_support),
                    leadingContent = { Icon(Icons.Default.Wifi, contentDescription = null) },
                    onClick = onBackgroundSyncClick
                )
                AppListItem(
                    headline = stringResource(Res.string.settings_background_ingest_headline),
                    supportingText = stringResource(Res.string.settings_background_ingest_support),
                    leadingContent = { Icon(Icons.Default.Cloud, contentDescription = null) },
                    onClick = onBackgroundIngestClick
                )
                AppListItem(
                    headline = stringResource(Res.string.settings_unlock_headline),
                    supportingText = stringResource(Res.string.settings_unlock_support),
                    leadingContent = {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                    },
                    onClick = onUnlockClick
                )
                // Direct shortcut into the §7.4 phrase-capture flow per the
                // onboarding-low-friction redesign. The full sync dashboard
                // also surfaces the phrase under "Synchronization & Devices",
                // but the deferral pattern in §4 means many users will reach
                // for "Set up account recovery" by name.
                AppListItem(
                    headline = stringResource(Res.string.settings_recovery_headline),
                    supportingText = stringResource(Res.string.settings_recovery_support),
                    leadingContent = { Icon(Icons.Default.Key, contentDescription = null) },
                    onClick = onRecoverySetupClick,
                )

                // ⚠ Placed directly AFTER "Set up account recovery", never before it: the entry
                // that saves the phrase must be the one the eye reaches first. This entry destroys
                // it (§5b.12.2) and its dialog routes through the phrase for exactly that reason.
                AppListItem(
                    headline = stringResource(Res.string.forget_device_headline),
                    supportingText = stringResource(Res.string.forget_device_support),
                    leadingContent = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = onForgetDeviceClick,
                )
                // Phase 5 of `docs/proposals/core-error-channel.md`:
                // visibility-only diagnostic log (per v0.2 review notes).
                // Phase 6 adds the "Send error report" CTA on the detail
                // screen; the Settings entry stays the same.
                AppListItem(
                    headline = "Errors",
                    supportingText = "Recent errors captured on this device",
                    leadingContent = { Icon(Icons.Default.ErrorOutline, contentDescription = null) },
                    onClick = onErrorsClick,
                )
                // Settings → Offline maps (maps-region-delivery.md §7 phase 6 / D5-amended):
                // canonical management of downloaded basemap regions (shared across Maps/Timeline/
                // Fit → owned by Control, the store owner).
                AppListItem(
                    headline = "Offline maps",
                    supportingText = "Downloaded map regions used across your apps",
                    leadingContent = { Icon(Icons.Default.Map, contentDescription = null) },
                    onClick = onOfflineMapsClick,
                )
                // Settings → Models (inference-service.md IN-2c / IN-D10): on-device inference
                // models, owned by Control because Control owns the store — the same reason
                // Offline maps lives here. ⚠ The only route by which a model reaches a device.
                AppListItem(
                    headline = "Models",
                    supportingText = "On-device models used for search and labelling",
                    leadingContent = { Icon(Icons.Default.Memory, contentDescription = null) },
                    onClick = onModelsClick,
                )
                // Settings → Advanced (control-keep-device-unlocked.md §4.4): expert
                // settings hub — the soft app-lock lives under Advanced → Security.
                AppListItem(
                    headline = stringResource(Res.string.settings_advanced_headline),
                    supportingText = stringResource(Res.string.settings_advanced_support),
                    leadingContent = { Icon(Icons.Default.Tune, contentDescription = null) },
                    onClick = onAdvancedClick,
                )
                // Per-platform entries: the menu-bar agent on desktop (slice 1E), "Let other apps
                // open your Drive files" on Android (cross-app-file-access.md D-D).
                PlatformSettingsSection()
                // The legal entries, last: the in-app privacy policy (App Store 5.1.1(i), Play User
                // Data policy), then the open-source notices this binary ships (docs/oss-licences.md).
                AppPrivacyPolicyListItem()
                AppTermsOfUseListItem()
                OpenSourceLicencesListItem(onClick = onLicencesClick)
            }
        }
    }
}

@Preview
@Composable
fun SettingsScreenPreview() {
    Surface {
        SettingsScreenContent(
            onSyncClick = {},
            onBackgroundSyncClick = {},
            onBackgroundIngestClick = {},
            onRecoverySetupClick = {},
            onForgetDeviceClick = {},
            onUnlockClick = {},
            onErrorsClick = {},
            onAdvancedClick = {},
        )
    }
}
