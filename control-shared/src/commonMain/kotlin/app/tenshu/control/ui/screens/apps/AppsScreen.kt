package app.tenshu.control.ui.screens.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Apps
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.data.InstalledApp
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.apps_app_supporting_version
import app.tenshu.control.shared.resources.apps_connect_action
import app.tenshu.control.shared.resources.apps_empty_body
import app.tenshu.control.shared.resources.apps_empty_title
import app.tenshu.control.shared.resources.apps_title
import app.tenshu.ui.chrome.AppEmptyState
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource

/**
 * Apps section list (`control-ia.md` §4.1): one card per installed app,
 * grouped by appId across devices, newest install first. Tapping a card opens
 * its detail. Control / apex itself is not listed (it is the management surface,
 * not a registered app).
 */
@Composable
fun AppsScreen(
    viewModel: AppsViewModel,
    onAppClick: (String) -> Unit,
    onConnectApp: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AppsScreenContent(
        uiState = uiState,
        onAppClick = onAppClick,
        onConnectApp = onConnectApp,
        onPrimaryHandlerChosen = viewModel::onPrimaryHandlerChosen,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreenContent(
    uiState: AppsUiState,
    onAppClick: (String) -> Unit,
    onConnectApp: () -> Unit,
    onPrimaryHandlerChosen: (type: String, appId: String?) -> Unit = { _, _ -> },
) {
    Scaffold(topBar = { AppTopAppBar(title = stringResource(Res.string.apps_title)) }) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .appContentWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // "Connect an app on another device" (control-ia.md §4.3): the EPC-pairing
                // entry point lives at the top of Apps, on the Control acting as proxy.
                ConnectAppItem(onClick = onConnectApp)

                if (!uiState.loading && uiState.apps.isEmpty()) {
                    AppEmptyState(
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Default.Apps,
                        title = stringResource(Res.string.apps_empty_title),
                        body = stringResource(Res.string.apps_empty_body),
                    )
                } else {
                    uiState.apps.forEach { app ->
                        AppCard(app = app, onClick = { onAppClick(app.appId) })
                    }
                    // ⚠ Below the list, not above it: the app list is what this screen is for, and
                    // a setting most realms cannot even make must not push it down the page.
                    OpenHandlerSection(
                        choices = uiState.openHandlerChoices,
                        onChoose = onPrimaryHandlerChosen,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectAppItem(onClick: () -> Unit) {
    AppListItem(
        headline = stringResource(Res.string.apps_connect_action),
        onClick = onClick,
        leadingContent = { Icon(Icons.Default.AddLink, contentDescription = null) },
    )
}

@Composable
private fun AppCard(app: InstalledApp, onClick: () -> Unit) {
    AppListItem(
        headline = app.name,
        supportingText = appSupportingTextLocalised(app),
        onClick = onClick,
        leadingContent = { Icon(Icons.Default.Apps, contentDescription = null) },
        // An app we cannot describe is still an app the user has — it is never hidden, only
        // labelled (C3 / D3). `recognised` and `verified` are separate questions, so at most one
        // badge is shown and "unrecognised" wins: it explains the bare id the headline is
        // showing, which is the thing the user is actually looking at.
        trailingContent = when {
            !app.recognised -> {
                { UnrecognisedBadge() }
            }
            !app.verified -> {
                { UnverifiedBadge() }
            }
            else -> null
        },
    )
}

/** "{shortDescription} · v{version}" — the short description with the version appended. */
@Composable
private fun appSupportingTextLocalised(app: InstalledApp): String {
    val versionText = if (app.version.isNotBlank()) {
        stringResource(Res.string.apps_app_supporting_version, app.version)
    } else {
        null
    }
    return buildString {
        if (app.shortDescription.isNotBlank()) append(app.shortDescription)
        if (versionText != null) {
            if (isNotEmpty()) append(" · ")
            append(versionText)
        }
    }
}
