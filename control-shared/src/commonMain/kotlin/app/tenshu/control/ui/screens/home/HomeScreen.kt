package app.tenshu.control.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.tenshu.control.data.Event
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.home_event_added_file_prefix
import app.tenshu.control.shared.resources.home_event_added_folder_prefix
import app.tenshu.control.shared.resources.home_event_added_with_name
import app.tenshu.control.shared.resources.home_event_app_registered
import app.tenshu.control.shared.resources.home_event_app_removed
import app.tenshu.control.shared.resources.home_event_deleted_file
import app.tenshu.control.shared.resources.home_event_deleted_folder
import app.tenshu.control.shared.resources.home_event_change_lost
import app.tenshu.control.shared.resources.home_event_multiple_added_plural
import app.tenshu.control.shared.resources.home_event_multiple_added_singular
import app.tenshu.control.shared.resources.home_event_title_app_registered
import app.tenshu.control.shared.resources.home_event_title_app_removed
import app.tenshu.control.shared.resources.home_event_title_file_added
import app.tenshu.control.shared.resources.home_event_title_file_deleted
import app.tenshu.control.shared.resources.home_event_title_folder_added
import app.tenshu.control.shared.resources.home_event_title_folder_deleted
import app.tenshu.control.shared.resources.home_event_title_change_lost
import app.tenshu.control.shared.resources.home_event_title_multiple_files_added
import app.tenshu.control.shared.resources.home_recent_events_empty_body
import app.tenshu.control.shared.resources.home_recent_events_empty_title
import app.tenshu.control.shared.resources.home_recent_events_title
import app.tenshu.control.shared.resources.home_recent_events_unknown_error
import app.tenshu.ui.chrome.AppEmptyState
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.feedback.AppLoadingIndicator
import app.tenshu.ui.feedback.AppLoadingSize
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import org.tenshu.core.common.util.TimestampUtil

@Composable
fun HomeScreen(
    viewModel: HomeScreenViewModel,
    affordanceContent: @Composable () -> Unit = {},
    pinnedContent: @Composable () -> Unit = {},
    topBarActions: @Composable RowScope.() -> Unit = {},
) {
    val events = viewModel.eventFlow.collectAsLazyPagingItems()

    HomeScreenContent(
        events = events,
        affordanceContent = affordanceContent,
        pinnedContent = pinnedContent,
        topBarActions = topBarActions,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenContent(
    modifier: Modifier = Modifier,
    events: LazyPagingItems<Event>,
    affordanceContent: @Composable () -> Unit = {},
    pinnedContent: @Composable () -> Unit = {},
    topBarActions: @Composable RowScope.() -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopAppBar(
                title = stringResource(Res.string.home_recent_events_title),
                actions = topBarActions,
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Pinned actionable requests (central-notifications §4.3.3) — the
            // top 1–2 unresolved grants/invites, above everything else so the
            // user lands on them. The rest stay in the notification center.
            Box(modifier = Modifier.appContentWidth()) {
                pinnedContent()
            }
            // §7.2 Level 0 recovery affordance — rendered above the event
            // feed. The slot pattern lets the screen stay agnostic of the
            // affordance VM (Koin-injected at the App.kt entry).
            // ⚠ A Column, not a Box: the slot holds several cards (the fence banner, recovery,
            // quota, offline maps), and in a Box they all sat at top-start, drawn over each other.
            Column(modifier = Modifier.appContentWidth()) {
                affordanceContent()
            }
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            val refreshState = events.loadState.refresh
            val unknownErrorMessage = stringResource(Res.string.home_recent_events_unknown_error)
            if (events.itemCount == 0 && refreshState !is LoadState.Loading) {
                AppEmptyState(
                    modifier = Modifier.appContentWidth(),
                    icon = Icons.Default.History,
                    iconContentDescription = null,
                    title = stringResource(Res.string.home_recent_events_empty_title),
                    body = stringResource(Res.string.home_recent_events_empty_body),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .appContentWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        count = events.itemCount,
                        key = events.itemKey { it.id }
                    ) { index ->
                        val event = events[index]
                        if (event != null) {
                            EventItem(event)
                        }
                    }

                    val loadState = events.loadState
                    when (val state = loadState.append) {
                        is LoadState.Error -> {
                            item { ErrorItem(state.error.message ?: unknownErrorMessage) }
                        }

                        is LoadState.Loading -> {
                            item { LoadingItem() }
                        }

                        else -> {}
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun EventItem(event: Event) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = 0.5f
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = eventTitle(event),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = TimestampUtil.formatTimestamp(event.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (event) {
                is Event.FileEvent -> FileEventContent(event)
                is Event.AppEvent -> AppEventContent(event)
            }
        }
    }
}

@Composable
private fun FileEventContent(event: Event.FileEvent) {
    when (event) {
        is Event.FileEvent.Added -> {
            val prefix = stringResource(
                if (event.isFolder) Res.string.home_event_added_folder_prefix
                else Res.string.home_event_added_file_prefix,
            )
            Text(
                text = stringResource(
                    Res.string.home_event_added_with_name,
                    prefix,
                    event.fileName,
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            val preview = event.preview
            if (preview != null) {
                Spacer(modifier = Modifier.height(8.dp))
                PreviewImage(preview)
            }
        }

        is Event.FileEvent.MultipleAdded -> {
            Text(
                text = if (event.otherFilesCount == 1) {
                    stringResource(
                        Res.string.home_event_multiple_added_singular,
                        event.firstFileName,
                    )
                } else {
                    stringResource(
                        Res.string.home_event_multiple_added_plural,
                        event.firstFileName,
                        event.otherFilesCount,
                    )
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (event.previews.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    event.previews.take(3).forEach { data ->
                        PreviewImage(data, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        is Event.FileEvent.ChangeLost -> {
            Text(
                text = stringResource(Res.string.home_event_change_lost, event.fileName),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        is Event.FileEvent.Deleted -> {
            Text(
                text = stringResource(
                    if (event.isFolder) Res.string.home_event_deleted_folder
                    else Res.string.home_event_deleted_file,
                    event.fileName,
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            val preview = event.preview
            if (preview != null) {
                Spacer(modifier = Modifier.height(8.dp))
                PreviewImage(preview, alpha = 0.5f)
            }
        }
    }
}

@Composable
private fun AppEventContent(event: Event.AppEvent) {
    val description = when (event) {
        is Event.AppEvent.Registered ->
            stringResource(Res.string.home_event_app_registered, event.appName)
        is Event.AppEvent.Removed ->
            stringResource(Res.string.home_event_app_removed, event.appName)
    }
    Text(text = description, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun PreviewImage(data: ByteArray, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val bitmap = decodeToImageBitmap(data)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
            alpha = alpha
        )
    }
}

expect fun decodeToImageBitmap(data: ByteArray): ImageBitmap?

@Composable
private fun eventTitle(event: Event): String = stringResource(
    when (event) {
        is Event.FileEvent.Added ->
            if (event.isFolder) Res.string.home_event_title_folder_added
            else Res.string.home_event_title_file_added
        is Event.FileEvent.MultipleAdded -> Res.string.home_event_title_multiple_files_added
        is Event.FileEvent.Deleted ->
            if (event.isFolder) Res.string.home_event_title_folder_deleted
            else Res.string.home_event_title_file_deleted
        is Event.FileEvent.ChangeLost -> Res.string.home_event_title_change_lost
        is Event.AppEvent.Registered -> Res.string.home_event_title_app_registered
        is Event.AppEvent.Removed -> Res.string.home_event_title_app_removed
    }
)

@Composable
private fun LoadingItem() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp), contentAlignment = Alignment.Center
    ) {
        AppLoadingIndicator(size = AppLoadingSize.Medium)
    }
}

@Composable
private fun ErrorItem(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    )
}

@Preview
@Composable
fun HomeScreenContentPreview() {
    // Note: Mocking LazyPagingItems in previews can be tricky.
    // In a real project, we might have a FakeLazyPagingItems or similar.
    // For now, we'll just show an empty state or a simplified version.
}
