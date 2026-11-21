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
import app.tenshu.calendar.shared.resources.Res
import app.tenshu.calendar.shared.resources.calendar_back
import app.tenshu.calendar.shared.resources.calendar_cancel
import app.tenshu.calendar.shared.resources.calendar_copied_all
import app.tenshu.calendar.shared.resources.calendar_copied_empty
import app.tenshu.calendar.shared.resources.calendar_copied_some
import app.tenshu.calendar.shared.resources.calendar_copied_up_to_date
import app.tenshu.calendar.shared.resources.calendar_copy_add_account
import app.tenshu.calendar.shared.resources.calendar_copy_already_elsewhere
import app.tenshu.calendar.shared.resources.calendar_copy_already_here
import app.tenshu.calendar.shared.resources.calendar_copy_copied_before
import app.tenshu.calendar.shared.resources.calendar_copy_copying
import app.tenshu.calendar.shared.resources.calendar_copy_denied
import app.tenshu.calendar.shared.resources.calendar_copy_failed
import app.tenshu.calendar.shared.resources.calendar_copy_keep_in_sync
import app.tenshu.calendar.shared.resources.calendar_copy_keep_in_sync_beside_copy
import app.tenshu.calendar.shared.resources.calendar_copy_keep_in_sync_detail
import app.tenshu.calendar.shared.resources.calendar_copy_new
import app.tenshu.calendar.shared.resources.calendar_copy_none
import app.tenshu.calendar.shared.resources.calendar_copy_not_synced
import app.tenshu.calendar.shared.resources.calendar_copy_nothing
import app.tenshu.calendar.shared.resources.calendar_copy_on_device
import app.tenshu.calendar.shared.resources.calendar_copy_once
import app.tenshu.calendar.shared.resources.calendar_copy_once_new
import app.tenshu.calendar.shared.resources.calendar_copy_once_update
import app.tenshu.calendar.shared.resources.calendar_copy_pick_once
import app.tenshu.calendar.shared.resources.calendar_copy_pick_sync
import app.tenshu.calendar.shared.resources.calendar_copy_reading
import app.tenshu.calendar.shared.resources.calendar_copy_remind_too
import app.tenshu.calendar.shared.resources.calendar_copy_remind_too_detail
import app.tenshu.calendar.shared.resources.calendar_copy_take_over
import app.tenshu.calendar.shared.resources.calendar_copy_take_over_detail
import app.tenshu.calendar.shared.resources.calendar_copy_title
import app.tenshu.calendar.shared.resources.calendar_copy_unreadable
import app.tenshu.calendar.shared.resources.calendar_copy_update_now
import app.tenshu.calendar.shared.resources.calendar_copy_update_now_detail
import app.tenshu.calendar.shared.resources.calendar_done
import app.tenshu.calendar.shared.resources.calendar_events_one
import app.tenshu.calendar.shared.resources.calendar_events_other
import app.tenshu.calendar.shared.resources.calendar_events_skipped_one
import app.tenshu.calendar.shared.resources.calendar_events_skipped_other
import app.tenshu.calendar.shared.resources.calendar_mirror_note_elsewhere
import app.tenshu.calendar.shared.resources.calendar_mirror_note_here
import app.tenshu.calendar.shared.resources.calendar_mirrored_empty
import app.tenshu.calendar.shared.resources.calendar_mirrored_pending
import app.tenshu.calendar.shared.resources.calendar_mirrored_synced
import app.tenshu.calendar.shared.resources.calendar_quoted
import app.tenshu.calendar.shared.resources.calendar_sync_failed
import app.tenshu.calendar.ui.external.DeviceKind
import app.tenshu.calendar.ui.external.permissionHint
import app.tenshu.calendar.ui.external.syncCadence
import app.tenshu.calendar.ui.external.word
import app.tenshu.calendar.ui.i18n.countOf
import org.jetbrains.compose.resources.stringResource

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
            Text(stringResource(Res.string.calendar_copy_title, access.deviceKind.word()), style = MaterialTheme.typography.titleLarge)

            when (val step = state.step) {
                ExternalCopyStep.Closed -> Unit
                ExternalCopyStep.Loading -> Working(stringResource(Res.string.calendar_copy_reading, access.deviceKind.word()))
                is ExternalCopyStep.Choosing -> Chooser(step, viewModel, access)
                is ExternalCopyStep.Deciding -> Decision(step, viewModel, access)
                is ExternalCopyStep.Copying -> Working(stringResource(Res.string.calendar_copy_copying, step.calendar.name))
                is ExternalCopyStep.Done -> Finished(step.result.message(), viewModel)
                is ExternalCopyStep.Mirrored -> Finished(mirroredMessage(step, access.deviceKind), viewModel)
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
            stringResource(Res.string.calendar_copy_pick_sync)
        } else {
            stringResource(Res.string.calendar_copy_pick_once)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (accounts.isEmpty()) {
        Text(
            stringResource(Res.string.calendar_copy_none, access.deviceKind.word()),
            style = MaterialTheme.typography.bodyLarge,
        )
    } else {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            accounts.forEach { account ->
                item(key = "account:${account.accountType}|${account.accountName}") {
                    Text(
                        account.accountName.ifBlank { stringResource(Res.string.calendar_copy_on_device, access.deviceKind.word()) },
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
                        deviceKind = access.deviceKind,
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
        ) { Text(stringResource(Res.string.calendar_copy_add_account)) }
        TextButton(onClick = viewModel::dismiss) { Text(stringResource(Res.string.calendar_cancel)) }
    }
}

@Composable
private fun CalendarRow(
    calendar: ExternalCalendar,
    copiedInto: String?,
    mirrored: MirroredSource?,
    deviceKind: DeviceKind,
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
                    !enabled -> stringResource(Res.string.calendar_copy_not_synced)
                    // ⚠⚠ Before the copy line: a source a mirror already brings in must not read as
                    // new, or the user copies it and gets every event twice.
                    mirrored != null -> mirroredNote(mirrored, deviceKind)
                    // ⚠ *Copy once* again updates that calendar; "new" would make the user
                    // expect a duplicate.
                    copiedInto != null -> stringResource(Res.string.calendar_copy_copied_before, copiedInto)
                    else -> stringResource(Res.string.calendar_copy_new)
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

    Text(stringResource(Res.string.calendar_quoted, calendar.name), style = MaterialTheme.typography.titleMedium)

    when {
        // ⚠⚠ No *Copy once* for a mirrored source — the view model refuses it too. A copy beside the
        // mirror would hold every event a second time.
        mirrored != null && mirrored.boundHere -> {
            Text(
                stringResource(Res.string.calendar_copy_already_here, access.deviceKind.word(), mirrored.calendarName),
                style = MaterialTheme.typography.bodyMedium,
            )
            OptionRow(
                title = stringResource(Res.string.calendar_copy_update_now),
                detail = stringResource(Res.string.calendar_copy_update_now_detail, access.deviceKind.word()),
                onClick = { viewModel.keepInSync(calendar, remindersEnabled = remind) },
            )
        }
        mirrored != null -> {
            Text(
                stringResource(Res.string.calendar_copy_already_elsewhere, mirrored.calendarName),
                style = MaterialTheme.typography.bodyMedium,
            )
            OptionRow(
                title = stringResource(Res.string.calendar_copy_take_over, access.deviceKind.word()),
                detail = stringResource(Res.string.calendar_copy_take_over_detail, access.deviceKind.syncCadence()),
                onClick = { viewModel.keepInSync(calendar, remindersEnabled = remind) },
            )
        }
        else -> {
            OptionRow(
                title = stringResource(Res.string.calendar_copy_once),
                detail = step.copiedInto?.let { stringResource(Res.string.calendar_copy_once_update, it) }
                    ?: stringResource(Res.string.calendar_copy_once_new),
                onClick = { viewModel.copy(calendar) },
            )
            OptionRow(
                title = stringResource(Res.string.calendar_copy_keep_in_sync),
                detail = stringResource(
                    Res.string.calendar_copy_keep_in_sync_detail,
                    access.deviceKind.syncCadence(),
                    access.deviceKind.word(),
                ) +
                    // ⚠ The copy is never turned into the mirror (its first refresh would sweep what
                    // the user added), so say that both will exist.
                    (step.copiedInto?.let { " " + stringResource(Res.string.calendar_copy_keep_in_sync_beside_copy, it) } ?: ""),
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
            Text(stringResource(Res.string.calendar_copy_remind_too), style = MaterialTheme.typography.bodyMedium)
            // ⚠ Off by default, and the copy says why (§5.7): reminders on would arrive twice.
            Text(
                stringResource(Res.string.calendar_copy_remind_too_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    TextButton(onClick = viewModel::backToList) { Text(stringResource(Res.string.calendar_back)) }
}

/** "Kept in sync from another device, in “Arbeit”" — the chooser row's line for a mirrored source. */
@Composable
private fun mirroredNote(mirrored: MirroredSource, deviceKind: DeviceKind): String =
    if (mirrored.boundHere) {
        stringResource(Res.string.calendar_mirror_note_here, deviceKind.word(), mirrored.calendarName)
    } else {
        stringResource(Res.string.calendar_mirror_note_elsewhere, mirrored.calendarName)
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

@Composable
private fun mirroredMessage(step: ExternalCopyStep.Mirrored, deviceKind: DeviceKind): String {
    val name = stringResource(Res.string.calendar_quoted, step.calendarName)
    val device = deviceKind.word()
    return when (val outcome = step.outcome) {
        is MirrorRefreshOutcome.Synced -> {
            val total = outcome.result.written + outcome.result.unchanged
            val skipped = outcome.result.skipped
            stringResource(Res.string.calendar_mirrored_synced, name, device, count(total)) +
                if (skipped > 0) " " + skippedEvents(skipped) else ""
        }
        is MirrorRefreshOutcome.NotApplied ->
            stringResource(Res.string.calendar_mirrored_empty, name, device)
        else ->
            stringResource(Res.string.calendar_mirrored_pending, name, device)
    }
}

@Composable
private fun Finished(message: String, viewModel: ExternalCopyViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = viewModel::dismiss) { Text(stringResource(Res.string.calendar_done)) }
    }
}

/**
 * ⚠⚠ Says what actually happened. A second copy of an unchanged calendar adds nothing, and
 * claiming "copied 412 events" again would make a user wonder whether they now have two of each;
 * skipped events are named, never swallowed (the CA-6 rule).
 */
@Composable
private fun ExternalCopyResult.message(): String {
    val name = stringResource(Res.string.calendar_quoted, calendarName)
    val main = when {
        copied == 0 -> stringResource(Res.string.calendar_copied_empty, name)
        added == 0 -> stringResource(Res.string.calendar_copied_up_to_date, name, count(copied))
        added < copied -> stringResource(Res.string.calendar_copied_some, count(added), name, copied - added)
        else -> stringResource(Res.string.calendar_copied_all, count(copied), name)
    }
    return if (skipped > 0) "$main ${skippedEvents(skipped)}" else main
}

@Composable
private fun count(n: Int) = countOf(n, Res.string.calendar_events_one, Res.string.calendar_events_other)

@Composable
private fun skippedEvents(n: Int) =
    countOf(n, Res.string.calendar_events_skipped_one, Res.string.calendar_events_skipped_other)

@Composable
private fun ExternalCopyProblem.message(access: DeviceCalendarAccess): String = when (this) {
    ExternalCopyProblem.PermissionDenied -> stringResource(
        Res.string.calendar_copy_denied,
        access.deviceKind.word(),
        access.deviceKind.permissionHint(),
    )
    ExternalCopyProblem.Unreadable -> stringResource(Res.string.calendar_copy_unreadable, access.deviceKind.word())
    ExternalCopyProblem.NothingToCopy -> stringResource(Res.string.calendar_copy_nothing)
    ExternalCopyProblem.CopyFailed -> stringResource(Res.string.calendar_copy_failed)
    ExternalCopyProblem.SyncFailed -> stringResource(Res.string.calendar_sync_failed)
}
