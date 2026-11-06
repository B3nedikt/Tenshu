package app.tenshu.calendar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.data.IcsImportResult
import app.tenshu.calendar.ui.CalendarInteropUiState
import app.tenshu.calendar.ui.CalendarInteropViewModel
import app.tenshu.calendar.ui.InteropProblem
import app.tenshu.calendar.ui.interop.CalendarFileAccess
import app.tenshu.calendar.ui.interop.FilePickOutcome
import app.tenshu.calendar.ui.interop.FileProblem
import app.tenshu.calendar.ui.interop.FileSaveOutcome
import app.tenshu.calendar.ui.interop.rememberCalendarFileAccess

/**
 * Import and export for one calendar (CA-6b).
 *
 * ⚠ Reached from the calendars screen rather than from a settings screen, which is
 * where a stock app puts it — Calendar has no settings screen, and more to the
 * point *which calendar* is the question both actions need answered. Opening from
 * a row answers it before the sheet appears.
 *
 * ⚠⚠ **Except when the file comes from outside the app.** A macOS document open
 * (CA-6c) arrives with no calendar attached, so the same sheet asks the question
 * *last* instead, from `CalendarInteropUiState.importTargets`.
 *
 * ⚠ Hosted by `App`, above the routes, rather than by the calendars screen (CA-6e):
 * a file opened from the Finder now raises this sheet over whatever the user was
 * looking at, instead of navigating them to a screen they did not ask for. That is
 * also why the chooser's list comes from the view model rather than a parameter —
 * the host no longer has one to pass.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarInteropSheet(viewModel: CalendarInteropViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.visible) return

    val files = rememberCalendarFileAccess()

    ModalBottomSheet(onDismissRequest = viewModel::dismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                // ⚠ The file's name, until a calendar is picked: it is the only thing
                // the user can use to tell *which* file they just double-clicked, and
                // a blank title over a list of calendars explains nothing.
                if (state.choosingCalendar) {
                    state.pendingImportOrigin.ifBlank { "Import calendar" }
                } else {
                    state.calendarName
                },
                style = MaterialTheme.typography.titleLarge,
            )

            when {
                state.isBusy -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text("Working…", style = MaterialTheme.typography.bodyMedium)
                }

                state.choosingCalendar -> CalendarChooser(state.importTargets, viewModel)

                state.showingChoices -> InteropChoices(state, viewModel, files)

                else -> InteropResult(state, viewModel)
            }
        }
    }
}

/**
 * Which calendar a handed-over file goes into (CA-6c).
 *
 * ⚠ No "create a new calendar" row, even though `X-WR-CALNAME` is parsed and would
 * name one. Creating a calendar is a synced write to every device; making it a
 * side effect of opening a file is not something a user asked for, and the row
 * would be the easiest one to hit by accident.
 */
@Composable
private fun CalendarChooser(
    calendars: List<CalendarSummary>,
    viewModel: CalendarInteropViewModel,
) {
    Text(
        "Which calendar should these events go into?",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (calendars.isEmpty()) {
        // ⚠ Two ways to get here, which is why the copy names neither: the list may
        // not have been read yet (the file can arrive on a launch where Control is
        // not even running), or every calendar may be subscribed and therefore not
        // something to import into — see the caller.
        Text("There is no calendar here to import into.", style = MaterialTheme.typography.bodyLarge)
    } else {
        calendars.forEach { calendar ->
            InteropRow(
                title = calendar.name.ifBlank { "Untitled calendar" },
                subtitle = "Add the file's events to this calendar.",
                icon = {
                    Box(
                        Modifier.size(16.dp).clip(CircleShape).background(Color(calendar.colorArgb)),
                    )
                },
            ) { viewModel.chooseCalendar(calendar.calendarId, calendar.name) }
        }
    }

    TextButton(onClick = viewModel::dismiss) { Text("Cancel") }
}

@Composable
private fun InteropChoices(
    state: CalendarInteropUiState,
    viewModel: CalendarInteropViewModel,
    files: CalendarFileAccess,
) {
    // ⚠ Not for a subscribed or mirrored calendar: its next refresh would delete the import.
    if (state.canImport) InteropRow(
        title = "Import from a file",
        subtitle = "Add the events in an .ics file to this calendar.",
    ) {
        viewModel.pickStarted()
        files.openIcs { outcome ->
            when (outcome) {
                is FilePickOutcome.Text -> viewModel.importText(outcome.text)
                FilePickOutcome.Cancelled -> viewModel.pickCancelled()
                is FilePickOutcome.Failed -> viewModel.pickFailed(outcome.problem.toInterop())
            }
        }
    }

    InteropRow(
        title = "Export to a file",
        subtitle = "Save this calendar as an .ics file you can open anywhere.",
        icon = { Icon(Icons.Default.Share, contentDescription = null) },
    ) {
        // ⚠⚠ The text is produced *first*, then the save dialog opens with it —
        // so a failure to read the calendar is an error message rather than a
        // picker that appears and writes nothing.
        viewModel.exportText { text ->
            files.saveIcs(state.suggestedFileName, text) { outcome ->
                when (outcome) {
                    FileSaveOutcome.Saved -> viewModel.exportSaved()
                    FileSaveOutcome.Cancelled -> viewModel.pickCancelled()
                    is FileSaveOutcome.Failed -> viewModel.exportFailed()
                }
            }
        }
    }
}

@Composable
private fun InteropRow(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit = { Icon(Icons.Default.Add, contentDescription = null) },
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InteropResult(state: CalendarInteropUiState, viewModel: CalendarInteropViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.resultMessage(), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = viewModel::acknowledgeResult) { Text("Do something else") }
            TextButton(onClick = viewModel::dismiss) { Text("Done") }
        }
    }
}

/**
 * ⚠⚠ **Skipped events are named, not swallowed.** An import that says "42 events"
 * when the file held 44 is how a user loses two appointments and never finds out —
 * which is why `IcsImportResult` carries the count at all.
 */
private fun CalendarInteropUiState.resultMessage(): String {
    // ⚠ Locals, not smart casts: these are public properties of a different
    // module, which Kotlin will not narrow.
    val failure = problem
    val imported = lastImport
    return when {
        failure != null -> failure.message()
        exported -> "Exported."
        imported != null -> imported.message()
        else -> ""
    }
}

private fun IcsImportResult.message(): String = buildString {
    append("Imported $imported event")
    if (imported != 1) append("s")
    if (overrides > 0) append(" and $overrides changed occurrence${if (overrides == 1) "" else "s"}")
    append(".")
    if (skipped > 0) {
        append(" $skipped entr${if (skipped == 1) "y" else "ies"} could not be read and ")
        append(if (skipped == 1) "was" else "were")
        append(" left out.")
    }
}

private fun InteropProblem.message(): String = when (this) {
    InteropProblem.EmptyFile -> "That file is empty."
    InteropProblem.NothingImported -> "Nothing in that file could be read as an event."
    InteropProblem.ExportFailed -> "Could not write the file."
    InteropProblem.Unreadable -> "Could not read that file."
}

private fun FileProblem.toInterop(): InteropProblem = when (this) {
    FileProblem.Empty -> InteropProblem.EmptyFile
    FileProblem.Unreadable -> InteropProblem.Unreadable
}
