package app.tenshu.control.ui.screens.settings.backgroundsync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.background_sync_title
import app.tenshu.control.shared.resources.background_sync_wifi_only_headline
import app.tenshu.control.shared.resources.background_sync_wifi_only_support
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun BackgroundSyncSettingsScreen(
    viewModel: BackgroundSyncSettingsViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    BackgroundSyncSettingsScreenContent(
        uiState = uiState,
        onWifiOnlyChange = viewModel::setWifiOnly,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSyncSettingsScreenContent(
    uiState: BackgroundSyncUiState,
    onWifiOnlyChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            AppTopAppBar(title = stringResource(Res.string.background_sync_title), onBack = onBack)
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
                    headline = stringResource(Res.string.background_sync_wifi_only_headline),
                    supportingText = stringResource(Res.string.background_sync_wifi_only_support),
                    leadingContent = { Icon(Icons.Default.Wifi, contentDescription = null) },
                    trailingContent = {
                        Switch(
                            checked = uiState.wifiOnly,
                            onCheckedChange = onWifiOnlyChange
                        )
                    },
                    onClick = { onWifiOnlyChange(!uiState.wifiOnly) }
                )
            }
        }
    }
}

@Preview
@Composable
fun BackgroundSyncSettingsScreenPreview() {
    Surface {
        BackgroundSyncSettingsScreenContent(
            uiState = BackgroundSyncUiState(wifiOnly = true),
            onWifiOnlyChange = {},
            onBack = {},
        )
    }
}
