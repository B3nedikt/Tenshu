package app.tenshu.home.ui.screens.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tenshu.home.model.ActivityEvent
import app.tenshu.home.model.ActivityKind
import app.tenshu.home.model.formatTimeOfDay
import app.tenshu.home.model.localDayOf
import app.tenshu.home.shared.resources.Res
import app.tenshu.home.shared.resources.home_activity_empty
import app.tenshu.home.shared.resources.home_activity_empty_devices
import app.tenshu.home.shared.resources.home_activity_empty_voice
import app.tenshu.home.shared.resources.home_activity_filter_automations
import app.tenshu.home.shared.resources.home_activity_filter_devices
import app.tenshu.home.shared.resources.home_activity_filter_voice
import app.tenshu.home.shared.resources.home_activity_today
import app.tenshu.home.shared.resources.home_activity_yesterday
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/**
 * The Activity tab (docs/proposals/home-and-mia-apps.md §3, M2): one merged chronological
 * feed — device events, automation runs, voice interactions — sliced by kind filter chips
 * and grouped under day headers.
 */
@Composable
fun ActivityScreen(viewModel: ActivityViewModel) {
    val state by viewModel.uiState.collectAsState()
    val timeZone = remember { TimeZone.currentSystemDefault() }
    val today = remember { Clock.System.now().toLocalDateTime(timeZone).date }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KindChip(state.filter, ActivityKind.Device, stringResource(Res.string.home_activity_filter_devices), viewModel::toggleFilter)
            KindChip(state.filter, ActivityKind.Automation, stringResource(Res.string.home_activity_filter_automations), viewModel::toggleFilter)
            KindChip(state.filter, ActivityKind.Voice, stringResource(Res.string.home_activity_filter_voice), viewModel::toggleFilter)
        }

        val events = state.filteredEvents
        if (state.loaded && events.isEmpty()) {
            Text(
                // ⚠⚠ Kind-aware, because two of the three chips can never match anything: nothing
                // stores device events (`home-device-service` holds current state, not a history)
                // and the voice pipeline does not exist. One generic "nothing here yet" over a
                // Devices filter reads as "your home was quiet", which is a different claim — and
                // the copy this replaced went further still, promising both would "show up".
                stringResource(
                    when (state.filter) {
                        ActivityKind.Device -> Res.string.home_activity_empty_devices
                        ActivityKind.Voice -> Res.string.home_activity_empty_voice
                        ActivityKind.Automation, null -> Res.string.home_activity_empty
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            var lastDay: LocalDate? = null
            events.forEach { event ->
                val day = localDayOf(event.at, timeZone)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day-$day") {
                        Text(
                            dayLabel(day, today),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                }
                item(key = event.id) {
                    EventRow(event, timeZone)
                }
            }
        }
    }
}

@Composable
private fun KindChip(
    current: ActivityKind?,
    kind: ActivityKind,
    label: String,
    onToggle: (ActivityKind) -> Unit,
) {
    FilterChip(
        selected = current == kind,
        onClick = { onToggle(kind) },
        label = { Text(label) },
    )
}

@Composable
private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> stringResource(Res.string.home_activity_today)
    today.minus(DatePeriod(days = 1)) -> stringResource(Res.string.home_activity_yesterday)
    else -> day.toString()
}

@Composable
private fun EventRow(event: ActivityEvent, timeZone: TimeZone) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            kindIcon(event.kind),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(event.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                event.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            formatTimeOfDay(event.at, timeZone),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun kindIcon(kind: ActivityKind): ImageVector = when (kind) {
    ActivityKind.Device -> Icons.Default.Sensors
    ActivityKind.Automation -> Icons.Default.AutoAwesome
    ActivityKind.Voice -> Icons.Outlined.ChatBubbleOutline
}
