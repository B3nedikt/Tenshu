package app.tenshu.calendar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import app.tenshu.calendar.data.external.MirrorRefreshOutcome
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.calendar.data.ExternalCopyResult
import app.tenshu.calendar.data.external.ExternalCalendar
import app.tenshu.calendar.ui.ExternalCopyProblem
import app.tenshu.calendar.ui.ExternalCopyStep
import app.tenshu.calendar.ui.ExternalCopyViewModel
import app.tenshu.calendar.ui.MirroredSource
import app.tenshu.calendar.ui.external.DeviceCalendarAccess

/**
 * *Copy from this phone* (`calendar-external-sources.md` CX-1): the calendars the OS already
 * has, by account, and what copying one did.
 *
 * ⚠ It draws an answer it did not compute — every decision is in [ExternalCopyViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalCopySheet(viewModel: ExternalCopyViewModel, access: DeviceCalendarAccess) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.isOpen) return

    ModalBottomSheet(
        onDismissRequest = viewModel::dismiss,
        // ⚠ Straight to full height: the chooser is a list, and a half-open sheet hides the
        // calendar the user came for below the fold.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Copy from this ${access.deviceName}", style = MaterialTheme.typography.titleLarge)

            when (val step = state.step) {
                ExternalCopyStep.Closed -> Unit
                ExternalCopyStep.Loading -> Working("Reading this ${access.deviceName}'s calendars…")
                is ExternalCopyStep.Choosing -> Chooser(step, viewModel, access)
                is ExternalCopyStep.Deciding -> Decision(step, viewModel, access)
                is ExternalCopyStep.Copying -> Working("Copying “${step.calendar.name}”…")
                is ExternalCopyStep.Done -> Finished(step.result.message(), viewModel)
                is ExternalCopyStep.Mirrored -> Finished(mirroredMessage(step, access.deviceName), viewModel)
                is ExternalCopyStep.Failed -> Finished(step.problem.message(access), viewModel)
            }
        }
    }
}

@Composable
private fun Working(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Chooser(
    step: ExternalCopyStep.Choosing,
    viewModel: ExternalCopyViewModel,
    access: DeviceCalendarAccess,
) {
    val accounts = step.accounts
    // ⚠⚠ Honest about what each choice does — a copy is never linked back, so a user who
    // expected a live calendar must not find out a week later.
    Text(
        if (viewModel.canKeepInSync) {
            "Pick a calendar to bring into Tenshu. Next you choose whether to copy it once or " +
                "keep it in sync."
        } else {
            "Pick a calendar to copy into Tenshu. It is copied once — changes made later in the " +
                "other app are not."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (accounts.isEmpty()) {
        Text(
            "There are no calendars on this ${access.deviceName} yet. Add your Google or work account, let it " +
                "sync, then come back here.",
            style = MaterialTheme.typography.bodyLarge,
        )
    } else {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            accounts.forEach { account ->
                item(key = "account:${account.accountType}|${account.accountName}") {
                    Text(
                        account.accountName.ifBlank { "On this ${access.deviceName}" },
                        Modifier.padding(top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(account.calendars, key = { "calendar:${it.sourceKey}" }) { calendar ->
                    CalendarRow(
                        calendar,
                        copiedInto = step.copiedInto[calendar.sourceKey],
                        mirrored = step.mirroredInto[calendar.sourceKey],
                        deviceName = access.deviceName,
                    ) { viewModel.choose(calendar) }
                }
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = {
                // ⚠ Closed first: the account syncs on its own schedule, and a list left open
                // behind the settings screen would be stale when the user comes back.
                viewModel.dismiss()
                access.addAccount()
            },
        ) { Text("Add an account") }
        TextButton(onClick = viewModel::dismiss) { Text("Cancel") }
    }
}

@Composable
private fun CalendarRow(
    calendar: ExternalCalendar,
    copiedInto: String?,
    mirrored: MirroredSource?,
    deviceName: String,
    onClick: () -> Unit,
) {
    val enabled = calendar.holdsEvents
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(16.dp).clip(CircleShape).background(
                calendar.colorArgb
                    ?.let { Color((it.toLong() and 0xFFFFFFL) or 0xFF000000L) }
                    ?: MaterialTheme.colorScheme.outline,
            ),
        )
        Column(Modifier.weight(1f)) {
            Text(
                calendar.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // ⚠ Shown, not hidden: someone looking for this calendar should learn why it
                // cannot be copied rather than conclude it is missing.
                when {
                    // ⚠ Android-only: EventKit has no visible-but-unsynced calendar.
                    !enabled -> "Not synced to this phone — turn on sync for it in the account's settings"
                    // ⚠⚠ Before the copy line: a source a mirror already brings in must not read as
                    // new, or the user copies it and gets every event twice.
                    mirrored != null -> mirroredNote(mirrored, deviceName)
                    // ⚠ *Copy once* again updates that calendar; "new" would make the user
                    // expect a duplicate.
                    copiedInto != null -> "Copied before into “$copiedInto”"
                    else -> "Bring into a new Tenshu calendar"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * *Copy once* or *Keep in sync* (CX-1b).
 *
 * ⚠⚠ Each option says what it leaves behind, because the two calendars behave so differently: an
 * editable copy that never updates, or a read-only calendar that does — and only while this phone
 * has the account.
 */
@Composable
private fun Decision(step: ExternalCopyStep.Deciding, viewModel: ExternalCopyViewModel, access: DeviceCalendarAccess) {
    val calendar = step.calendar
    val mirrored = step.mirrored
    // ⚠ A rebind keeps the mirror's own switch: starting from "off" would turn it off.
    var remind by remember(calendar) { mutableStateOf(mirrored?.remindersEnabled ?: false) }

    Text("\u201C${calendar.name}\u201D", style = MaterialTheme.typography.titleMedium)

    when {
        // ⚠⚠ No *Copy once* for a mirrored source — the view model refuses it too. A copy beside the
        // mirror would hold every event a second time.
        mirrored != null && mirrored.boundHere -> {
            Text(
                "Already kept in sync from this ${access.deviceName}, in \u201C${mirrored.calendarName}\u201D.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OptionRow(
                title = "Update now",
                detail = "Reads it again from this ${access.deviceName}.",
                onClick = { viewModel.keepInSync(calendar, remindersEnabled = remind) },
            )
        }
        mirrored != null -> {
            Text(
                "Already kept in sync from another device, in \u201C${mirrored.calendarName}\u201D. " +
                    "Its events are in Tenshu already.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OptionRow(
                title = "Keep in sync from this ${access.deviceName} instead",
                detail = "The same calendar, updated ${access.syncCadence} instead of from the other " +
                    "device. Nothing is copied twice.",
                onClick = { viewModel.keepInSync(calendar, remindersEnabled = remind) },
            )
        }
        else -> {
            OptionRow(
                title = "Copy once",
                detail = step.copiedInto?.let { "Updates your earlier copy, \u201C$it\u201D. Changes made later in the other app are not copied." }
                    ?: "An editable copy in Tenshu. Changes made later in the other app are not copied.",
                onClick = { viewModel.copy(calendar) },
            )
            OptionRow(
                title = "Keep in sync",
                detail = "Read-only in Tenshu, and updated ${access.syncCadence}. It " +
                    "stays up to date only while this ${access.deviceName} has the account." +
                    // ⚠ The copy is never turned into the mirror (its first refresh would sweep what
                    // the user added), so say that both will exist.
                    (step.copiedInto?.let { " It is a new calendar: your copy \u201C$it\u201D stays, and you can delete it afterwards." } ?: ""),
                onClick = { viewModel.keepInSync(calendar, remindersEnabled = remind) },
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable { remind = !remind }.padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = remind, onCheckedChange = { remind = it })
        Column(Modifier.weight(1f)) {
            Text("Also remind me from Tenshu", style = MaterialTheme.typography.bodyMedium)
            // ⚠ Off by default, and the copy says why (§5.7): reminders on would arrive twice.
            Text(
                "For a kept-in-sync calendar. Your other calendar app already reminds you, so " +
                    "this is usually off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    TextButton(onClick = viewModel::backToList) { Text("Back") }
}

/** "Kept in sync from another device, in “Arbeit”" — the chooser row's line for a mirrored source. */
private fun mirroredNote(mirrored: MirroredSource, deviceName: String): String {
    val from = if (mirrored.boundHere) "this $deviceName" else "another device"
    return "Kept in sync from $from, in \u201C${mirrored.calendarName}\u201D"
}

@Composable
private fun OptionRow(title: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun mirroredMessage(step: ExternalCopyStep.Mirrored, device: String): String {
    val name = "\u201C${step.calendarName}\u201D"
    return when (val outcome = step.outcome) {
        is MirrorRefreshOutcome.Synced -> {
            val total = outcome.result.written + outcome.result.unchanged
            val skipped = outcome.result.skipped
            "$name is now kept in sync from this $device — ${count(total)} so far." +
                if (skipped > 0) " ${count(skipped)} could not be read and were left out." else ""
        }
        is MirrorRefreshOutcome.NotApplied ->
            "$name is now kept in sync from this $device. It has no events to show yet."
        else ->
            "$name is now kept in sync from this $device. Its events will arrive with the next update."
    }
}

@Composable
private fun Finished(message: String, viewModel: ExternalCopyViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = viewModel::dismiss) { Text("Done") }
    }
}

/**
 * ⚠⚠ Says what actually happened. A second copy of an unchanged calendar adds nothing, and
 * claiming "copied 412 events" again would make a user wonder whether they now have two of each;
 * skipped events are named, never swallowed (the CA-6 rule).
 */
private fun ExternalCopyResult.message(): String {
    val name = "“$calendarName”"
    val main = when {
        copied == 0 -> "$name has no events to copy yet. It was added, empty."
        added == 0 -> "$name is up to date — ${count(copied)} checked, none new."
        added < copied -> "Copied ${count(added)} into $name (${copied - added} were already there)."
        else -> "Copied ${count(copied)} into $name."
    }
    return if (skipped > 0) "$main ${count(skipped)} could not be read and were left out." else main
}

private fun count(n: Int) = if (n == 1) "1 event" else "$n events"

private fun ExternalCopyProblem.message(access: DeviceCalendarAccess): String = when (this) {
    ExternalCopyProblem.PermissionDenied ->
        "Calendar is not allowed to read this ${access.deviceName}'s calendars. You can allow it in " +
            "${access.permissionHint}."
    ExternalCopyProblem.Unreadable -> "This ${access.deviceName}'s calendars could not be read."
    ExternalCopyProblem.NothingToCopy ->
        "None of this calendar's events could be read, so nothing was copied."
    ExternalCopyProblem.CopyFailed ->
        "The events could not be saved. Check that Tenshu Control is set up and running, then " +
            "try again."
    ExternalCopyProblem.SyncFailed ->
        "Keeping this calendar in sync could not start. Check that Tenshu Control is set up and " +
            "running, then try again."
}
