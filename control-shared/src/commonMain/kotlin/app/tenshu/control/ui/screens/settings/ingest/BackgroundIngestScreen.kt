package app.tenshu.control.ui.screens.settings.ingest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
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
import app.tenshu.control.data.IngestSourceModel
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.background_ingest_empty_headline
import app.tenshu.control.shared.resources.background_ingest_empty_support
import app.tenshu.control.shared.resources.background_ingest_status_error
import app.tenshu.control.shared.resources.background_ingest_status_idle
import app.tenshu.control.shared.resources.background_ingest_status_out_of_space
import app.tenshu.control.shared.resources.background_ingest_status_paused
import app.tenshu.control.shared.resources.background_ingest_status_running
import app.tenshu.control.shared.resources.background_ingest_status_up_to_date
import app.tenshu.control.shared.resources.background_ingest_title
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import org.tenshu.core.ingest.IngestSource

@Composable
fun BackgroundIngestScreen(
    viewModel: BackgroundIngestViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    BackgroundIngestScreenContent(
        uiState = uiState,
        onEnabledChange = viewModel::onEnabledChange,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundIngestScreenContent(
    uiState: BackgroundIngestUiState,
    onEnabledChange: (IngestSourceModel, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            AppTopAppBar(title = stringResource(Res.string.background_ingest_title), onBack = onBack)
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
                if (!uiState.loading && uiState.sources.isEmpty()) {
                    AppListItem(
                        headline = stringResource(Res.string.background_ingest_empty_headline),
                        supportingText = stringResource(Res.string.background_ingest_empty_support),
                        leadingContent = { Icon(Icons.Default.Cloud, contentDescription = null) },
                    )
                }
                uiState.sources.forEach { source ->
                    AppListItem(
                        headline = source.displayName,
                        supportingText = ingestStatusLabel(source.status),
                        leadingContent = { Icon(Icons.Default.Cloud, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = source.enabled,
                                onCheckedChange = { onEnabledChange(source, it) },
                            )
                        },
                        onClick = { onEnabledChange(source, !source.enabled) },
                    )
                }
            }
        }
    }
}

/** Localised label for an [IngestSource] status code. */
@Composable
private fun ingestStatusLabel(status: String): String = when (status) {
    IngestSource.STATUS_RUNNING -> stringResource(Res.string.background_ingest_status_running)
    IngestSource.STATUS_UP_TO_DATE -> stringResource(Res.string.background_ingest_status_up_to_date)
    IngestSource.STATUS_PAUSED_POLICY -> stringResource(Res.string.background_ingest_status_paused)
    IngestSource.STATUS_OUT_OF_SPACE -> stringResource(Res.string.background_ingest_status_out_of_space)
    IngestSource.STATUS_ERROR -> stringResource(Res.string.background_ingest_status_error)
    else -> stringResource(Res.string.background_ingest_status_idle)
}

@Preview
@Composable
fun BackgroundIngestScreenPreview() {
    Surface {
        BackgroundIngestScreenContent(
            uiState = BackgroundIngestUiState(
                loading = false,
                sources = listOf(
                    IngestSourceModel(
                        key = "drive.gallery@dev1",
                        sourceId = "drive.gallery",
                        appId = "app.tenshu.drive",
                        deviceId = "dev1",
                        displayName = "Photos & Videos",
                        sourceKind = IngestSource.KIND_GALLERY,
                        enabled = true,
                        status = IngestSource.STATUS_UP_TO_DATE,
                        pendingCount = 0,
                        watchedPaths = "",
                    ),
                ),
            ),
            onEnabledChange = { _, _ -> },
            onBack = {},
        )
    }
}
