package app.tenshu.control.ui.screens.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.control.notifications.PendingRequest
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.notifications_pinned_title
import org.jetbrains.compose.resources.stringResource

/**
 * The pinned-request slot rendered above Home's event feed
 * (`control-central-notifications.md` §4.3.3, O2: "top 1–2 on Home, rest in the center").
 * Shows the [maxVisible] most recent unresolved requests as actionable
 * [PendingRequestCard]s so the user lands on them without navigating; the rest stay in
 * the notification center. Renders nothing when there are no requests.
 */
@Composable
fun PinnedRequests(
    requests: List<PendingRequest>,
    actionState: Map<String, RequestActionUiState>,
    actions: PendingRequestActions,
    modifier: Modifier = Modifier,
    maxVisible: Int = 2,
) {
    if (requests.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.notifications_pinned_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        requests.take(maxVisible).forEach { request ->
            PendingRequestCard(
                request = request,
                actionState = actionState[request.id] ?: RequestActionUiState(),
                actions = actions,
            )
        }
    }
}
