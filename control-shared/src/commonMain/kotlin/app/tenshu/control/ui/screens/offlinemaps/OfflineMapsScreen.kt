package app.tenshu.control.ui.screens.offlinemaps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import androidx.compose.ui.tooling.preview.Preview

/**
 * Control → Settings → **Offline maps** (`maps-region-delivery.md` §7 phase 6 / D5-amended). The
 * canonical management surface for downloaded basemap regions — shared by Maps/Timeline/Fit, so it
 * lives in Control (the store owner), not in any single app. A **data-availability** surface,
 * distinct from the account/entitlement **Services** surface. Strings are inline (matching the
 * Settings "Errors" entry) — localisation of this expert surface is a later pass.
 */
@Composable
fun OfflineMapsScreen(
    viewModel: OfflineMapsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OfflineMapsScreenContent(
        state = state,
        onDownload = viewModel::download,
        onDelete = viewModel::delete,
        onQueryChange = viewModel::search,
        onConfirmMetered = viewModel::confirmMeteredDownload,
        onDismissMetered = viewModel::dismissMeteredConsent,
        onRetry = viewModel::retry,
        onMessageShown = viewModel::consumeMessage,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreenContent(
    state: OfflineMapsUiState,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onQueryChange: (String) -> Unit = {},
    onConfirmMetered: () -> Unit = {},
    onDismissMetered: () -> Unit = {},
    onRetry: () -> Unit = {},
    onMessageShown: () -> Unit = {},
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        if (state.message == OfflineMapsMessage.DownloadFailed) {
            snackbarHostState.showSnackbar("Couldn't download that region. Check your connection and try again.")
            onMessageShown()
        }
    }

    // ⚠ A dialog rather than a snackbar or an inline row: this spends the user's money, the answer is a
    // real choice, and it must be made before anything is downloaded — not reported after.
    state.meteredConsentRegion?.let { region ->
        AlertDialog(
            onDismissRequest = onDismissMetered,
            title = { Text("Use mobile data?") },
            text = {
                // Names the region AND the size, because the size is the whole reason to hesitate.
                Text(
                    "You're not on Wi-Fi. Downloading ${region.name} will use about " +
                        "${formatBytes(region.byteSize)} of mobile data.",
                )
            },
            confirmButton = { TextButton(onClick = onConfirmMetered) { Text("Download anyway") } },
            dismissButton = { TextButton(onClick = onDismissMetered) { Text("Not now") } },
        )
    }

    Scaffold(
        topBar = { AppTopAppBar(title = "Offline maps", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            // ⚠ Grouping is suppressed when the catalog could not be read: those rows come from the
            // install index, which carries no coverage box, so every one of them would file under
            // "Elsewhere" — a heading that says less than no heading at all.
            val sections = remember(state.regions, state.query, state.catalogUnavailable) {
                groupRegions(state.regions, state.query, grouped = !state.catalogUnavailable)
            }

            LazyColumn(modifier = Modifier.fillMaxSize().appContentWidth()) {
                when {
                    state.loading -> item { Hint("Loading available regions…") }

                    // Nothing to show AND no catalog: say we could not look, never "there is nothing".
                    state.catalogUnavailable && state.regions.isEmpty() -> item {
                        Hint("Couldn't load the list of map regions.")
                        RetryButton(onRetry)
                    }

                    state.regions.isEmpty() -> item { Hint("No map regions are available yet.") }

                    else -> {
                        // Resident regions still work offline, so they are listed — but the list is
                        // partial and no update check has run, and the banner says exactly that
                        // rather than letting a short list read as the whole catalog.
                        if (state.catalogUnavailable) {
                            item {
                                Hint("Couldn't check for new regions or updates. Showing what's on this device.")
                                RetryButton(onRetry)
                            }
                        }

                        item {
                            SearchField(
                                query = state.query,
                                onQueryChange = onQueryChange,
                                // The count is the honest answer to "is the filter hiding something?",
                                // which a list of headings on its own cannot give.
                                resultCount = sections.sumOf { it.regions.size },
                                total = state.regions.size,
                            )
                        }

                        // ⚠ Its own state, distinct from both "nothing available" and "couldn't look":
                        // a filter that matched nothing is a fourth thing, and the way out of it is to
                        // change the query, not to retry or to wait.
                        if (sections.isEmpty()) {
                            item { Hint("No regions match \"${state.query}\".") }
                        }

                        // ⚠ The screen's honest boundary. Every row above is a region the user chose to
                        // download and Control keeps; it is NOT everything on the device that is made of
                        // map tiles. Apps also accumulate the tiles they have actually displayed, and
                        // that cache lives in each app's own sandbox — Control cannot enumerate or delete
                        // it, so claiming to manage it here would be a lie. Saying so is what stops a
                        // short list reading as "this is all the map data you have".
                        item { CacheFootnote() }

                        sections.forEach { section ->
                            // Grouped lists render a heading; the ungrouped fallback renders none, so a
                            // single-section list is not topped by a meaningless label.
                            if (!state.catalogUnavailable) {
                                stickyHeader(key = "header-${section.group.name}") {
                                    SectionHeader(section.group.label)
                                }
                            }
                            items(section.regions, key = { it.regionId }) { region ->
                                RegionRow(
                                    region = region,
                                    busy = state.busyRegionId == region.regionId,
                                    downloadedBytes = state.downloadedBytes
                                        ?.takeIf { state.busyRegionId == region.regionId },
                                    onDownload = { onDownload(region.regionId) },
                                    onDelete = { onDelete(region.regionId) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionRow(
    region: OfflineMapRegion,
    busy: Boolean,
    /** Bytes downloaded so far — non-null only while *this* row's download is in flight. */
    downloadedBytes: Long?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    // One source for the label and the ring, so they can never disagree (a full ring beside "99 %").
    val percent = downloadedBytes?.let { downloadPercent(it, region.byteSize) }

    AppListItem(
        headline = region.name,
        supportingText = when {
            // The whole point of the slice: a multi-minute, hundreds-of-MB action says how far along
            // it is, and names the total so the remaining wait is legible.
            percent != null -> "Downloading… $percent% of ${formatBytes(region.byteSize)}"
            // A newer build of the bytes is published (track 3b) — offer the update, and say what it
            // costs, since re-downloading a region is not a small thing.
            region.updateAvailable -> "Update available · ${formatBytes(region.byteSize)}"
            region.installed -> "Available offline · ${formatBytes(region.byteSize)}"
            else -> "${formatBytes(region.byteSize)} download"
        },
        leadingContent = { Icon(Icons.Default.Map, contentDescription = null) },
        trailingContent = {
            when {
                // ⚠ Only once there are bytes. A determinate ring at 0 % draws an empty circle that
                // reads as *inert* — and that is exactly the state the row sits in for the first
                // second or two, while the proxy resolves the catalog entry and opens the staging
                // file. Found by rendering it (the CE-6 lesson: a 4.dp gauge read as an underline).
                // Unknown progress should look unknown, so it spins until the first chunk lands.
                percent != null && percent > 0 -> CircularProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.size(20.dp),
                )
                // Downloading but nothing written yet, or deleting — genuinely indeterminate. The
                // supporting text is what tells the two apart.
                busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                // Updating IS re-downloading (the downloader re-fetches + sha-gates, and the store now
                // swaps the bytes in atomically), so it reuses onDownload. Delete stays available.
                region.updateAvailable -> Row {
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Default.Refresh, contentDescription = "Update offline copy")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove offline copy")
                    }
                }
                region.installed -> IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove offline copy")
                }
                else -> IconButton(onClick = onDownload) {
                    Icon(Icons.Default.CloudDownload, contentDescription = "Make available offline")
                }
            }
        },
    )
}

/**
 * The section heading. Sticky so the continent you are looking at stays named while you scroll past a
 * long run of regions under it — with a solid background, because a transparent sticky header lets the
 * rows scroll visibly *through* the text.
 */
@Composable
internal fun SectionHeader(label: String) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

/**
 * The filter. A plain text field rather than a collapsing search bar: this is a settings screen, the
 * field is always useful once the catalog is more than a screenful, and a chrome-heavy search affordance
 * would be the most prominent thing on a page whose subject is a list.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    resultCount: Int,
    total: Int,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Search regions") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            // Only while filtering — a permanent clear button on an empty field is a dead control.
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        },
        supportingText = {
            // Says what the filter is hiding. Silent while unfiltered, since "7 of 7" is noise.
            if (query.isNotEmpty()) Text("$resultCount of $total regions")
        },
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

/**
 * Names the *other* map data on the device — the streamed-tile cache each app keeps for itself.
 *
 * Deliberately not an action: the cache is per-app-sandbox, so Control has no way to clear it. It is
 * held in each app's `cacheDir`, which means the platform's own per-app storage screen both shows its
 * size and clears it — a real button that already exists, rather than one here that could not work.
 */
@Composable
private fun CacheFootnote() {
    Text(
        text = "Downloaded regions are listed here. Apps also keep a small cache of the map tiles " +
            "they've shown, managed automatically — that one lives with each app, and your device's " +
            "storage settings can clear it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
    )
}

@Composable
private fun RetryButton(onRetry: () -> Unit) {
    TextButton(
        onClick = onRetry,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) { Text("Try again") }
}

/**
 * Whole percent of [totalBytes] written, clamped. Pure so the boundaries are testable — and guarding
 * a zero total is not theoretical: a region listed from the local install index alone (no catalog)
 * can carry a size the caller never learned.
 */
internal fun downloadPercent(writtenBytes: Long, totalBytes: Long): Int =
    if (totalBytes <= 0L) 0 else ((writtenBytes * 100) / totalBytes).toInt().coerceIn(0, 100)

/** Coarse, honest size — GiB (one decimal) above 1024 MiB, else whole MiB. */
internal fun formatBytes(bytes: Long): String {
    val mib = bytes / (1024.0 * 1024.0)
    return if (mib >= 1024.0) {
        val gib = mib / 1024.0
        "${kotlin.math.round(gib * 10) / 10.0} GB"
    } else {
        "${mib.toLong()} MB"
    }
}

@Preview
@Composable
private fun OfflineMapsScreenPreview() {
    Surface {
        OfflineMapsScreenContent(
            state = OfflineMapsUiState(
                loading = false,
                regions = listOf(
                    OfflineMapRegion("at", "Österreich", 95_000_000L, installed = true),
                    OfflineMapRegion("ch", "Schweiz", 120_000_000L, installed = true, updateAvailable = true),
                    OfflineMapRegion("de-bayern", "Bayern", 420_000_000L, installed = false),
                ),
                // The row this slice exists for — rendered in the preview so the determinate branch is
                // something a reviewer can look at rather than infer.
                busyRegionId = "de-bayern",
                downloadedBytes = 180_000_000L,
            ),
            onDownload = {},
            onDelete = {},
            onBack = {},
        )
    }
}

/** The offline case: resident regions still listed, and the banner saying the list is partial. */
@Preview
@Composable
private fun OfflineMapsCatalogUnavailablePreview() {
    Surface {
        OfflineMapsScreenContent(
            state = OfflineMapsUiState(
                loading = false,
                regions = listOf(
                    OfflineMapRegion("at", "Österreich", 95_000_000L, installed = true),
                ),
                catalogUnavailable = true,
            ),
            onDownload = {},
            onDelete = {},
            onBack = {},
        )
    }
}
