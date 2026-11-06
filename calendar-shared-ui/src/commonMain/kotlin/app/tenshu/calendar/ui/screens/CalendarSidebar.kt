package app.tenshu.calendar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.calendar.data.CalendarSummary

/**
 * The calendars sidebar (CA-2f) — a permanent list of calendars with their
 * show-on-this-device checkboxes, for windows wide enough to keep one.
 *
 * ⚠⚠ **It deliberately does *not* duplicate `CalendarsScreen`.** Renaming,
 * recolouring, deleting, subscribing and `.ics` import stay on that screen, reached
 * from [onManage]; the sidebar owns exactly the one control worth a permanent
 * 220 dp — which calendars are being shown right now. Two places to rename a
 * calendar is two places for the wording, the validation and the error handling to
 * drift.
 *
 * ⚠ Visibility is **device-local** (it is derived from a device-scoped preference,
 * not a field on the row — see `CalendarSummary`), so this is a view control, not an
 * edit. That is why unchecking dims a name rather than warning about anything: on
 * another device the calendar is untouched.
 */
@Composable
fun CalendarSidebar(
    calendars: List<CalendarSummary>,
    onToggleVisible: (calendarId: String, visible: Boolean) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Text(
            text = "Calendars",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )

        // ⚠ Lazy and weighted: a realm with thirty subscribed calendars must scroll
        // inside the sidebar rather than push [onManage] off the bottom of it.
        LazyColumn(Modifier.weight(1f)) {
            items(calendars, key = { it.calendarId }) { calendar ->
                CalendarRow(calendar, onToggleVisible)
            }
        }

        HorizontalDivider()
        TextButton(
            onClick = onManage,
            modifier = Modifier.fillMaxWidth().padding(4.dp),
        ) {
            Text("Manage calendars")
        }
    }
}

@Composable
private fun CalendarRow(
    calendar: CalendarSummary,
    onToggleVisible: (calendarId: String, visible: Boolean) -> Unit,
) {
    Row(
        // ⚠⚠ The whole row is the checkbox, not just the box: `toggleable` with a
        // `Checkbox(onCheckedChange = null)` inside is the Material pattern that
        // yields **one** semantics node with a checked state. A clickable row beside
        // an independently clickable box announces two controls for one setting and
        // gives the pointer a 20 dp target instead of a 220 dp one.
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = calendar.visible,
                role = Role.Checkbox,
                onValueChange = { onToggleVisible(calendar.calendarId, it) },
            )
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = calendar.visible,
            onCheckedChange = null,
            // ⚠ Tinted with the calendar's own colour, which is what makes the
            // sidebar a legend for the grid beside it as well as a set of switches.
            colors = CheckboxDefaults.colors(
                checkedColor = Color(calendar.colorArgb.toLong() or 0xFF000000L),
            ),
        )
        Text(
            text = calendar.name.ifBlank { "(Untitled)" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (calendar.visible) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * ⚠ Fixed, like the day pane: a calendar name is the same size whatever the window,
 * so a proportional sidebar would only add whitespace on a wide display while taking
 * it from the grid, which is the half that uses it.
 */
val CalendarSidebarWidth: Dp = 220.dp
