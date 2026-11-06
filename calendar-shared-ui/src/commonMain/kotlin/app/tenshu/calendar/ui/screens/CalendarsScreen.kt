package app.tenshu.calendar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.AlertDialog as M3AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.calendar.data.CalendarSubscriptionSummary
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.TimeZone
import app.tenshu.calendar.model.CalendarDateFormat
import app.tenshu.calendar.data.CalendarMirrorSummary
import app.tenshu.calendar.data.CalendarSummary
import app.tenshu.calendar.model.CalendarPalette
import app.tenshu.calendar.ui.CalendarDraft
import app.tenshu.calendar.ui.CalendarManagementUiState
import app.tenshu.calendar.ui.SubscribeDraft
import app.tenshu.calendar.ui.CalendarInteropViewModel
import app.tenshu.calendar.ui.CalendarManagementViewModel
import app.tenshu.calendar.ui.ExternalCopyViewModel
import app.tenshu.calendar.ui.external.rememberDeviceCalendarAccess

/**
 * The calendars screen (CA-5f).
 *
 * ⚠⚠ **This screen is where two long-orphaned toggles finally live.** The
 * per-calendar `sensitive` flag has been stored, accepted by `SaveCalendar` and
 * honoured by `ReminderPlanner` since CA-5a with no way to set it, and
 * `setCalendarVisible` has been on `CalendarViewModel` since CA-2 with no UI.
 *
 * ⚠ Visibility and sensitivity look alike and are not: the first is a device-local
 * view preference, the second a synced property of the data. The row says
 * "Show on this device" and the sheet says "Hide details on the lock screen" —
 * naming the scope is the only thing that keeps them apart at a glance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarsScreen(
    viewModel: CalendarManagementViewModel,
    /** ⚠ Optional: a host with no interop surface simply does not offer the rows. */
    interopViewModel: CalendarInteropViewModel? = null,
    /**
     * *Copy from this phone* (CX-1). ⚠ Optional, and offered only where both the view model has
     * a reader and the platform has a store — Android today; a calendar copied there reaches
     * every other device by sync.
     */
    externalCopyViewModel: ExternalCopyViewModel? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceCalendars = rememberDeviceCalendarAccess()
    val copyFromPhone = externalCopyViewModel?.takeIf { it.supported && deviceCalendars.supported }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // ⚠ No insets of its own: its one host (`CalendarsPane`) is a Scaffold with the top bar,
        // whose padding already clears the status bar. Taking them again here drew a blank band
        // the height of the status bar between the top bar and the first calendar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ⚠ A small secondary button rather than a second full FAB or a
                // speed dial: subscribing is the rarer action, and a speed dial
                // would hide the common one behind a tap.
                SmallFloatingActionButton(onClick = viewModel::startSubscribing) {
                    Icon(Icons.Default.Refresh, contentDescription = "Subscribe to a calendar")
                }
                copyFromPhone?.let { copy ->
                    SmallFloatingActionButton(
                        onClick = {
                            // ⚠⚠ The permission is asked for here, at the button that needs it,
                            // and nowhere else (§4.4, the Contacts `READ_CONTACTS` precedent).
                            deviceCalendars.requestRead { granted ->
                                if (granted) copy.open() else copy.permissionDenied()
                            }
                        },
                    ) {
                        Icon(
                            if (deviceCalendars.deviceName == "phone") Icons.Default.PhoneAndroid else Icons.Default.Computer,
                            contentDescription = "Copy a calendar from this ${deviceCalendars.deviceName}",
                        )
                    }
                }
                FloatingActionButton(onClick = viewModel::startCreating) {
                    Icon(Icons.Default.Add, contentDescription = "New calendar")
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.calendars.isEmpty() -> Text(
                    state.errorMessage ?: "No calendars yet.",
                    Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> CalendarList(state, viewModel, interopViewModel, deviceName = deviceCalendars.deviceName)
            }
        }
    }

    state.draft?.let { draft ->
        ModalBottomSheet(onDismissRequest = viewModel::dismissDraft) {
            CalendarDraftSheet(draft, state.draftError, viewModel)
        }
    }

    state.confirmingDelete?.let { target ->
        DeleteCalendarDialog(target, viewModel)
    }

    // ⚠⚠ The interop sheet is **not composed here any more** (CA-6e). It is hosted by
    // `App`, above the routes, so a file the OS hands over can open it over whatever
    // the user was looking at. This screen only *starts* it, from a row.
    state.subscribeDraft?.let { draft -> SubscribeDialog(draft, viewModel) }

    copyFromPhone?.let { ExternalCopySheet(it, deviceCalendars) }
}

/**
 * The subscribe dialog (CA-7b).
 *
 * ⚠⚠ **The copy is the consent.** Adding a subscription is what makes this device
 * start fetching a third party on a schedule, so the dialog has to name what the
 * publisher learns *before* the button — the `PlaceGeocodingControl` framing, and
 * the threat model's CA-7 amendment depends on it being said here.
 */
@Composable
private fun SubscribeDialog(draft: SubscribeDraft, viewModel: CalendarManagementViewModel) {
    M3AlertDialog(
        onDismissRequest = viewModel::dismissSubscribe,
        title = { Text("Subscribe to a calendar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.url,
                    onValueChange = viewModel::setSubscribeUrl,
                    label = { Text("Calendar address") },
                    placeholder = { Text("https://… or webcal://…") },
                    singleLine = true,
                    isError = draft.error != null,
                    supportingText = draft.error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = viewModel::setSubscribeName,
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // ⚠⚠ Three facts, and each one is here because it is not obvious:
                // that the publisher learns something; that *this device* is what
                // asks, so every paired device asks separately; and that the feed
                // owns the calendar, so nothing you add to it survives. "About every 6 hours on
                // Wi-Fi" is SUBSCRIPTION_REFRESH_PERIOD_MS + NETWORK_UNMETERED, as iOS says it.
                Text(
                    "Tenshu will fetch this address about every 6 hours on Wi-Fi. The " +
                        "publisher will see your device's IP address and that you follow this " +
                        "calendar — each of your devices checks for itself. The calendar it " +
                        "creates is read-only: an update replaces whatever is in it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::commitSubscribe, enabled = !draft.isSaving) {
                Text("Subscribe")
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissSubscribe) { Text("Cancel") }
        },
    )
}

@Composable
private fun CalendarList(
    state: CalendarManagementUiState,
    viewModel: CalendarManagementViewModel,
    interopViewModel: CalendarInteropViewModel?,
    deviceName: String = "phone",
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.calendars, key = { it.calendarId }) { calendar ->
            CalendarRow(
                calendar = calendar,
                isDefault = calendar.calendarId == state.defaultCalendarId,
                canDelete = state.canDelete,
                onToggleVisible = { viewModel.setVisible(calendar.calendarId, it) },
                onEdit = { viewModel.startEditing(calendar) },
                onDelete = { viewModel.askDelete(calendar) },
                onMakeDefault = { viewModel.setDefault(calendar.calendarId) },
                onInterop = interopViewModel?.let {
                    { it.open(calendar.calendarId, calendar.name) }
                },
                subscription = state.subscriptionFor(calendar.calendarId),
                onToggleSubscription = { id, on -> viewModel.setSubscriptionEnabled(id, on) },
                onUnsubscribe = { id -> viewModel.removeSubscription(id) },
                onRefreshFeeds = viewModel::refreshSubscriptionsNow,
                mirror = state.mirrorFor(calendar.calendarId),
                onRefreshMirror = viewModel::refreshMirrorNow,
                onMirrorReminders = viewModel::setMirrorReminders,
                onStopMirror = viewModel::stopMirror,
                deviceName = deviceName,
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun CalendarRow(
    calendar: CalendarSummary,
    isDefault: Boolean,
    canDelete: Boolean,
    onToggleVisible: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMakeDefault: () -> Unit,
    onInterop: (() -> Unit)?,
    subscription: CalendarSubscriptionSummary?,
    onToggleSubscription: (String, Boolean) -> Unit,
    onUnsubscribe: (String) -> Unit,
    onRefreshFeeds: () -> Unit,
    mirror: CalendarMirrorSummary? = null,
    onRefreshMirror: (String) -> Unit = {},
    onMirrorReminders: (String, Boolean) -> Unit = { _, _ -> },
    onStopMirror: (String) -> Unit = {},
    deviceName: String = "phone",
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ⚠ The checkbox is `Show on this device`, and it must not look like the
        // row's primary action — tapping the row *edits*, which is where the
        // synced settings are.
        Checkbox(checked = calendar.visible, onCheckedChange = onToggleVisible)
        Box(
            Modifier.padding(horizontal = 12.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(Color(calendar.colorArgb)),
        )
        Column(Modifier.weight(1f)) {
            Text(calendar.name, style = MaterialTheme.typography.bodyLarge)
            val notes = buildList {
                if (isDefault) add("Default for new events")
                // ⚠ Surfaced on the row, not only in the sheet: a calendar whose
                // reminders are redacted should say so where you can see every
                // calendar at once, or the setting is invisible until you go
                // looking for it.
                if (calendar.sensitive) add("Details hidden on lock screen")
                // ⚠ A subscribed calendar says so on the row: everything in it is
                // read-only and a refresh will overwrite it, which the user has to
                // be able to see without opening anything.
                subscription?.let {
                    add(if (it.enabled) "Subscribed" else "Subscription paused")
                }
                // ⚠ CX-1b: where it is kept in sync from, and how fresh it is — on every device,
                // because only one can refresh it and the others must be able to see it going stale.
                mirror?.let { add(mirrorNote(it, deviceName)) }
            }
            if (notes.isNotEmpty()) {
                Text(
                    notes.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ⚠ An overflow rather than a row of icons. Delete moved in here when
        // import/export arrived (CA-6b) — three actions is where a row of buttons
        // starts competing with the row's own tap target.
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More for ${calendar.name}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!isDefault && !calendar.readOnly) {
                    DropdownMenuItem(
                        text = { Text("Make default") },
                        onClick = { menuOpen = false; onMakeDefault() },
                    )
                }
                onInterop?.let { open ->
                    DropdownMenuItem(
                        text = { Text("Import or export…") },
                        onClick = { menuOpen = false; open() },
                    )
                }
                // ⚠ Absent rather than disabled for the last calendar: a greyed
                // item the user cannot explain is worse than no item.
                // `canDelete`'s KDoc carries why.
                subscription?.let { feed ->
                    DropdownMenuItem(
                        text = { Text("Refresh now") },
                        onClick = { menuOpen = false; onRefreshFeeds() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (feed.enabled) "Pause updates" else "Resume updates") },
                        onClick = {
                            menuOpen = false
                            onToggleSubscription(feed.subscriptionId, !feed.enabled)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Unsubscribe") },
                        onClick = { menuOpen = false; onUnsubscribe(feed.subscriptionId) },
                    )
                }
                mirror?.let { synced ->
                    // ⚠ Only where the source is: no other device can read it.
                    if (synced.boundHere) {
                        DropdownMenuItem(
                            text = { Text("Refresh now") },
                            onClick = { menuOpen = false; onRefreshMirror(synced.mirrorId) },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (synced.remindersEnabled) "Stop Tenshu reminders"
                                else "Remind me from Tenshu too",
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onMirrorReminders(synced.mirrorId, !synced.remindersEnabled)
                        },
                    )
                    // ⚠ Keeps the events — the calendar becomes an ordinary, editable one. *Delete*
                    // below still removes calendar and sync together.
                    DropdownMenuItem(
                        text = { Text("Stop syncing (keep events)") },
                        onClick = { menuOpen = false; onStopMirror(synced.mirrorId) },
                    )
                }
                // ⚠ A subscribed calendar has no plain *Delete*: unsubscribing is
                // what removes it, and offering both would leave a user who picked
                // the wrong one with a live subscription to a calendar that no
                // longer exists.
                if (canDelete && subscription == null) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CalendarDraftSheet(
    draft: CalendarDraft,
    error: String?,
    viewModel: CalendarManagementViewModel,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (draft.isCreating) "New calendar" else "Edit calendar",
            style = MaterialTheme.typography.titleLarge,
        )

        OutlinedTextField(
            value = draft.name,
            onValueChange = viewModel::setDraftName,
            label = { Text("Name") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )

        Column {
            Text("Colour", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CalendarPalette.COLORS.forEach { colour ->
                    val selected = CalendarPalette.sameColour(colour, draft.colorArgb)
                    Box(
                        Modifier.size(32.dp)
                            .clip(CircleShape)
                            .background(Color(colour))
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        3.dp,
                                        MaterialTheme.colorScheme.onSurface,
                                        CircleShape,
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { viewModel.setDraftColor(colour) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }

        // ⚠⚠ The one setting here that is about privacy rather than tidiness, so
        // it carries an explanation rather than a bare label. The copy says what
        // *happens*, not what the flag is called.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hide details on the lock screen", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Reminders for this calendar show only \"Reminder\" — no title, no location.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = draft.sensitive, onCheckedChange = viewModel::setDraftSensitive)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = viewModel::dismissDraft) { Text("Cancel") }
            AssistChip(onClick = viewModel::saveDraft, label = { Text("Save") })
        }
    }
}

@Composable
private fun DeleteCalendarDialog(
    target: CalendarSummary,
    viewModel: CalendarManagementViewModel,
) {
    AlertDialog(
        onDismissRequest = viewModel::dismissDelete,
        title = { Text("Delete \"${target.name}\"?") },
        // ⚠⚠ Names the consequence rather than asking "are you sure": the op
        // cascades to every event in the calendar, and this is the only action in
        // the app with no undo.
        text = {
            Text(
                "Every event in this calendar will be deleted on all your devices. " +
                    "This cannot be undone."
            )
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmDelete) {
                Text("Delete", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissDelete) { Text("Cancel") }
        },
    )
}

/**
 * "Kept in sync from this phone · updated 14 Oct, 09:41".
 *
 * ⚠ The device's own zone is read here, for display only: this is presentation of an instant,
 * not a calendar time, and nothing is computed from it.
 */
private fun mirrorNote(mirror: CalendarMirrorSummary, deviceName: String): String {
    val from = if (mirror.boundHere) "Kept in sync from this $deviceName" else "Kept in sync from another device"
    if (mirror.lastRefreshedEpochMs <= 0L) return "$from · not updated yet"
    val local = kotlin.time.Instant.fromEpochMilliseconds(mirror.lastRefreshedEpochMs)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    return "$from · updated ${CalendarDateFormat.mediumDate(local.date)}, " +
        CalendarDateFormat.shortTime(local.time)
}
