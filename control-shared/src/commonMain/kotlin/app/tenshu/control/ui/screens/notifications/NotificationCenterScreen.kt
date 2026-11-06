package app.tenshu.control.ui.screens.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.control.notifications.PendingRequest
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.notifications_empty_title
import app.tenshu.control.shared.resources.notifications_title
import app.tenshu.ui.chrome.AppTopAppBar
import org.jetbrains.compose.resources.stringResource

/**
 * The notification center (`control-central-notifications.md` §4.3.2): the full list of
 * inbound actionable requests, each an actionable [PendingRequestCard]. Reached from the
 * [NotificationsBell] in Home's top bar.
 */
@Composable
fun NotificationCenterScreen(
    viewModel: NotificationsViewModel,
    onBack: () -> Unit,
) {
    val requests by viewModel.requests.collectAsState()
    val actionState by viewModel.actionState.collectAsState()
    val seenIds by viewModel.seenIds.collectAsState()
    NotificationCenterContent(
        requests = requests,
        actionState = actionState,
        actions = viewModel.asActions(),
        seenIds = seenIds,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCenterContent(
    requests: List<PendingRequest>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actionState: Map<String, RequestActionUiState> = emptyMap(),
    actions: PendingRequestActions = PendingRequestActions(),
    seenIds: Set<String> = emptySet(),
) {
    Scaffold(
        modifier = modifier,
        topBar = { AppTopAppBar(title = stringResource(Res.string.notifications_title), onBack = onBack) },
    ) { innerPadding ->
        if (requests.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = stringResource(Res.string.notifications_empty_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(requests, key = { it.id }) { request ->
                    PendingRequestCard(
                        request = request,
                        actionState = actionState[request.id] ?: RequestActionUiState(),
                        actions = actions,
                        seen = request.id in seenIds,
                    )
                }
            }
        }
    }
}

/**
 * Top-bar bell with an unread [count] badge (`control-central-notifications.md` §4.3.1). Lives
 * in Home's top bar (the `AdaptiveNavScaffold` has no global actions slot); tapping opens the
 * notification center.
 */
@Composable
fun NotificationsBell(count: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = { if (count > 0) Badge { Text(count.toString()) } },
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = stringResource(Res.string.notifications_title),
            )
        }
    }
}

/** Bind this VM's action methods into a [PendingRequestActions] for the card. */
fun NotificationsViewModel.asActions(): PendingRequestActions = PendingRequestActions(
    onAcceptGrant = ::acceptGrant,
    onDeclineGrant = ::declineGrant,
    onConfirmInvite = ::confirmInvite,
    onDeclineInvite = ::declineInvite,
    onDismiss = ::dismiss,
)
