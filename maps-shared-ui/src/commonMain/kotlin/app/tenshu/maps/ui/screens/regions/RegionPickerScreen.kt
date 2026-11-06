package app.tenshu.maps.ui.screens.regions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.tenshu.maps.model.RegionItem
import app.tenshu.maps.shared.resources.Res
import app.tenshu.maps.shared.resources.maps_back_cd
import app.tenshu.maps.shared.resources.maps_region_delete_cd
import app.tenshu.maps.shared.resources.maps_region_download_soon
import app.tenshu.maps.shared.resources.maps_region_downloaded
import app.tenshu.maps.shared.resources.maps_region_get
import app.tenshu.maps.shared.resources.maps_region_size_mb
import app.tenshu.maps.shared.resources.maps_regions_blurb
import app.tenshu.maps.shared.resources.maps_regions_title
import app.tenshu.maps.ui.components.MapsCard
import app.tenshu.ui.legal.AppPrivacyPolicyListItem
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource
import app.tenshu.ui.legal.OpenSourceLicencesListItem

/**
 * The offline region picker (`docs/proposals/maps-region-delivery.md` D5 — Maps owns the picker).
 * Lists the catalog with sizes shown honestly + install state; installed regions can be deleted,
 * available ones downloaded (the download seam is coming-soon until the download op lands, so a
 * "Get" tap surfaces a snackbar rather than a broken flow). Pure UI over [RegionPickerViewModel].
 */
@Composable
fun RegionPickerScreen(
    viewModel: RegionPickerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the open-source notices (`docs/oss-licences.md`) — Maps' only settings surface is this. */
    onOpenLicences: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val downloadSoon = stringResource(Res.string.maps_region_download_soon)

    LaunchedEffect(state.message) {
        if (state.message == RegionPickerMessage.DownloadUnavailable) {
            snackbarHostState.showSnackbar(downloadSoon)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { RegionPickerTopBar(onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = MaterialTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
        ) {
            item {
                Text(
                    text = stringResource(Res.string.maps_regions_blurb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MaterialTheme.spacing.s),
                )
            }
            items(state.regions, key = { it.regionId }) { region ->
                RegionRow(
                    region = region,
                    onDownload = { viewModel.download(region.regionId) },
                    onDelete = { viewModel.delete(region.regionId) },
                )
            }
            // In-app privacy policy (App Store 5.1.1(i), Play User Data policy). Maps has no
            // settings screen of its own; this is the one its settings gear opens.
            item(key = "privacy-policy") { AppPrivacyPolicyListItem() }
            // Then the open-source notices, including the map data's own credits.
            item(key = "licences") { OpenSourceLicencesListItem(onClick = onOpenLicences) }
        }
    }
}

@Composable
private fun RegionRow(
    region: RegionItem,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    MapsCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = region.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.maps_region_size_mb, (region.byteSize / 1_000_000L).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (region.installed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(Res.string.maps_region_downloaded),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = MaterialTheme.spacing.xs),
                    )
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(Res.string.maps_region_delete_cd),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Button(onClick = onDownload) {
                    Text(stringResource(Res.string.maps_region_get))
                }
            }
        }
    }
}

@Composable
private fun RegionPickerTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            start = MaterialTheme.spacing.xs,
            end = MaterialTheme.spacing.l,
            top = MaterialTheme.spacing.s,
            bottom = MaterialTheme.spacing.s,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(Res.string.maps_back_cd),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = stringResource(Res.string.maps_regions_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = MaterialTheme.spacing.xs),
        )
    }
}
