package app.tenshu.fit.ui.screens.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tenshu.fit.data.RecordingState
import app.tenshu.fit.data.TrimSuggestion
import app.tenshu.fit.data.systemKillExplains
import app.tenshu.fit.data.trimmed
import app.tenshu.fit.model.Workout
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_bpm
import app.tenshu.fit.shared.resources.fit_record_activity_eyebrow
import app.tenshu.fit.shared.resources.fit_record_save_failed_body
import app.tenshu.fit.shared.resources.fit_record_save_failed_ok
import app.tenshu.fit.shared.resources.fit_record_save_failed_title
import app.tenshu.fit.shared.resources.fit_record_saved_locally_body
import app.tenshu.fit.shared.resources.fit_record_saved_locally_ok
import app.tenshu.fit.shared.resources.fit_record_saved_locally_title
import app.tenshu.fit.shared.resources.fit_record_steps
import app.tenshu.fit.shared.resources.fit_record_summary_discard
import app.tenshu.fit.shared.resources.fit_record_summary_discard_body
import app.tenshu.fit.shared.resources.fit_record_summary_discard_cancel
import app.tenshu.fit.shared.resources.fit_record_summary_discard_title
import app.tenshu.fit.shared.resources.fit_record_summary_header
import app.tenshu.fit.shared.resources.fit_record_summary_recovered
import app.tenshu.fit.shared.resources.fit_record_summary_recovered_at
import app.tenshu.fit.shared.resources.fit_record_summary_recovered_killed
import app.tenshu.fit.shared.resources.fit_record_summary_save
import app.tenshu.fit.shared.resources.fit_record_summary_stop_this
import app.tenshu.fit.shared.resources.fit_record_summary_title_label
import app.tenshu.fit.shared.resources.fit_record_summary_trim_end
import app.tenshu.fit.shared.resources.fit_record_summary_trim_eyebrow
import app.tenshu.fit.shared.resources.fit_record_summary_trim_remove
import app.tenshu.fit.shared.resources.fit_record_summary_trim_start
import app.tenshu.fit.shared.resources.fit_record_summary_trim_undo
import app.tenshu.fit.shared.resources.fit_workout_stat_avg_hr
import app.tenshu.fit.shared.resources.fit_workout_stat_distance
import app.tenshu.fit.shared.resources.fit_workout_stat_duration
import app.tenshu.fit.shared.resources.fit_workout_stat_energy
import app.tenshu.fit.shared.resources.fit_workout_stat_max_hr
import app.tenshu.fit.shared.resources.fit_workout_stat_pace
import app.tenshu.fit.ui.components.Eyebrow
import app.tenshu.fit.ui.components.FitCard
import app.tenshu.fit.ui.components.FitTopBar
import app.tenshu.fit.ui.components.RoutePath
import app.tenshu.fit.ui.components.StatCell
import app.tenshu.fit.ui.components.activityName
import app.tenshu.fit.ui.components.clock
import app.tenshu.fit.ui.components.distanceValueUnit
import app.tenshu.fit.ui.components.durationClock
import app.tenshu.fit.ui.components.durationLong
import app.tenshu.fit.ui.components.energyValueUnit
import app.tenshu.fit.ui.components.groupThousands
import app.tenshu.fit.ui.components.intOrDash
import app.tenshu.fit.ui.components.paceValueUnit
import app.tenshu.fit.ui.theme.ActivityAccents
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * How stale a recovered draft has to be before the review screen dates the interruption instead of
 * calling it "the last few seconds". Below this the user pressed on within a minute of the kill and
 * the short wording is honest; above it, an unknown amount of the session is simply missing.
 */
private const val STALE_RECOVERY_SEC = 60L

/**
 * Record — review & save (RC-2, docs/proposals/fit-app.md §13.2): the surface between Stop and
 * Save. Renders the engine's Reviewing draft — route sketch + stat grid — and lets the user
 * title the workout and correct the activity type before **Save** commits it (the §9.13
 * save-outcome dialogs live here now) or **Discard** drops it behind a confirm. The shell
 * surfaces this screen off the `Reviewing` state flip, so it covers both the in-app Stop and
 * the notification's Stop action (RC-1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordSummaryScreen(
    viewModel: RecordViewModel,
    onFinished: (String) -> Unit,
    onClosed: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val draft = (state.recording as? RecordingState.Reviewing)?.draft

    // Read once per draft: "interrupted 12 minutes ago" must not tick upward while the user reads
    // the screen, and a recomposing clock read would make it do exactly that.
    val nowMs = remember(draft) { Clock.System.now().toEpochMilliseconds() }
    // The OS's record of how our last process died — read once per draft, same reasoning, and it
    // is a binder call rather than a field read.
    val deathReporter = rememberProcessDeathReporter()
    val oemSettings = rememberOemBackgroundSettings()
    val killAtWallMs = remember(draft, deathReporter) { deathReporter.lastSystemKillWallMs() }

    LaunchedEffect(state.finishedWorkoutId) {
        state.finishedWorkoutId?.let {
            viewModel.consumeFinished()
            onFinished(it)
        }
    }

    // Save-outcome notices (§9.13). Saved-locally: the draft ended safely (journalled on this
    // device, no readable detail yet) — acknowledge → leave. Save-failed: the engine kept the
    // draft, dismiss → Save retries.
    if (state.savedLocally) {
        val acknowledge = {
            viewModel.consumeSavedLocally()
            onClosed()
        }
        AlertDialog(
            onDismissRequest = acknowledge,
            title = { Text(stringResource(Res.string.fit_record_saved_locally_title)) },
            text = { Text(stringResource(Res.string.fit_record_saved_locally_body)) },
            confirmButton = {
                TextButton(onClick = acknowledge) {
                    Text(stringResource(Res.string.fit_record_saved_locally_ok))
                }
            },
        )
    }
    if (state.saveFailed) {
        AlertDialog(
            onDismissRequest = viewModel::dismissSaveFailed,
            title = { Text(stringResource(Res.string.fit_record_save_failed_title)) },
            text = { Text(stringResource(Res.string.fit_record_save_failed_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissSaveFailed) {
                    Text(stringResource(Res.string.fit_record_save_failed_ok))
                }
            },
        )
    }

    var confirmDiscard by remember { mutableStateOf(false) }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(Res.string.fit_record_summary_discard_title)) },
            text = { Text(stringResource(Res.string.fit_record_summary_discard_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        viewModel.discard()
                        onClosed()
                    },
                ) {
                    Text(
                        text = stringResource(Res.string.fit_record_summary_discard),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(Res.string.fit_record_summary_discard_cancel))
                }
            },
        )
    }

    // Transient after Save/Discard — the navigation effects above take over.
    if (draft == null) return

    val selectedActivity = state.draftActivity ?: draft.workout.activityType

    // RC-3: the sketch + stats preview the *applied* trim live — Save persists exactly this
    // window (the same trimmed() fold rides DraftEdits into commit).
    val trimStart = state.appliedTrimStartSec
    val trimEnd = state.appliedTrimEndSec
    val preview = remember(draft, trimStart, trimEnd) { draft.trimmed(trimStart, trimEnd) }

    Column(Modifier.fillMaxSize()) {
        // ⚠ No back affordance, deliberately — unchanged from the bare headline this replaces.
        // Review is a decide-now surface: leaving it is Save or Discard, and Discard confirms
        // first. A chevron would offer a third exit that silently parks the draft.
        FitTopBar(title = stringResource(Res.string.fit_record_summary_header))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            if (draft.recovered) {
                // RC-5: this draft was rebuilt from the in-flight journal after a process death.
                // How much is missing is *not* one flush window — it is everything between the
                // kill and the user coming back, which on an OEM that kills the recorder mid-run
                // is the rest of the run (2026-08-15: a 21-minute capture, killed at 13:54 by
                // ColorOS, saved at 14:06 as if it were the whole loop). So date the interruption
                // and say how long ago it was; only a prompt return gets the "last few seconds"
                // wording, which is the one case where it is true.
                val interruptedAgoSec = draft.interruptedAtWallMs?.let {
                    ((nowMs - it) / 1000).coerceAtLeast(0L)
                }
                // …and where the OS kept a record of killing us around that moment, name it. "The
                // recording was interrupted" invites the reader to suspect the app; "your phone
                // stopped the recording" is what actually happened, and is the only version they
                // can act on. Absent that evidence — a crash, a force-stop, an API below 30 — the
                // neutral wording stands, because blaming the phone for our own crash would be
                // worse than saying nothing.
                val phoneStoppedIt = remember(draft, killAtWallMs) {
                    systemKillExplains(killAtWallMs, draft.interruptedAtWallMs)
                }
                Text(
                    text = if (interruptedAgoSec == null || interruptedAgoSec < STALE_RECOVERY_SEC) {
                        stringResource(Res.string.fit_record_summary_recovered)
                    } else {
                        stringResource(
                            if (phoneStoppedIt) {
                                Res.string.fit_record_summary_recovered_killed
                            } else {
                                Res.string.fit_record_summary_recovered_at
                            },
                            clock(
                                Instant.fromEpochMilliseconds(draft.interruptedAtWallMs!!)
                                    .toLocalDateTime(TimeZone.currentSystemDefault()),
                            ),
                            durationLong(interruptedAgoSec.toInt()),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = if (phoneStoppedIt) 4.dp else 12.dp),
                )
                // The one place the offer is unarguably earned: we have just told them their phone
                // ended the recording, and this is the screen where they feel it. Shown only when
                // a vendor screen actually resolves (§13.5d) — an offer that leads nowhere is
                // worse than no offer.
                if (phoneStoppedIt && oemSettings.canOpen()) {
                    Text(
                        text = stringResource(Res.string.fit_record_summary_stop_this),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { oemSettings.openBackgroundSettings() }
                            .padding(bottom = 12.dp),
                    )
                }
            }
            val sketch = preview.routeSketch
            if (sketch.size >= 2) {
                RoutePath(route = sketch, color = ActivityAccents.stroke(selectedActivity))
                Spacer(Modifier.height(16.dp))
            }
            SummaryStatGrid(preview.workout)
            state.trimSuggestion?.let { suggestion ->
                Spacer(Modifier.height(16.dp))
                TrimSuggestionCard(
                    suggestion = suggestion,
                    durationSec = draft.workout.durationSec,
                    startApplied = state.trimStartApplied,
                    endApplied = state.trimEndApplied,
                    onSetStartApplied = viewModel::setTrimStartApplied,
                    onSetEndApplied = viewModel::setTrimEndApplied,
                )
            }
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = state.draftTitle,
                onValueChange = viewModel::setDraftTitle,
                label = { Text(stringResource(Res.string.fit_record_summary_title_label)) },
                // Blank keeps the fallback naming — the localized activity name.
                placeholder = { Text(activityName(selectedActivity)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            Eyebrow(stringResource(Res.string.fit_record_activity_eyebrow), Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                RECORD_ACTIVITIES.forEach { (type, labelRes) ->
                    ActivityChip(
                        type = type,
                        labelRes = labelRes,
                        selected = selectedActivity == type,
                        onClick = { viewModel.setDraftActivity(type) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape,
            ) {
                Text(
                    text = stringResource(Res.string.fit_record_summary_save),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(Res.string.fit_record_summary_discard),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The RC-3 trim-suggestion card (§13.3): one row per trimmable end, each with its own
 * Remove/Undo toggle — a suggestion the user applies, never an automatic cut. The labels
 * quote the *idle* span being removed; the stat grid above previews the result live.
 */
@Composable
private fun TrimSuggestionCard(
    suggestion: TrimSuggestion,
    durationSec: Int,
    startApplied: Boolean,
    endApplied: Boolean,
    onSetStartApplied: (Boolean) -> Unit,
    onSetEndApplied: (Boolean) -> Unit,
) {
    FitCard(Modifier.fillMaxWidth()) {
        Column {
            Eyebrow(stringResource(Res.string.fit_record_summary_trim_eyebrow), Modifier.padding(bottom = 4.dp))
            suggestion.trimStartSec?.let { windowStart ->
                TrimRow(
                    label = stringResource(Res.string.fit_record_summary_trim_start, durationClock(windowStart)),
                    applied = startApplied,
                    onToggle = onSetStartApplied,
                )
            }
            suggestion.trimEndSec?.let { windowEnd ->
                TrimRow(
                    label = stringResource(Res.string.fit_record_summary_trim_end, durationClock(durationSec - windowEnd)),
                    applied = endApplied,
                    onToggle = onSetEndApplied,
                )
            }
        }
    }
}

@Composable
private fun TrimRow(label: String, applied: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onToggle(!applied) }) {
            Text(
                text = stringResource(
                    if (applied) Res.string.fit_record_summary_trim_undo
                    else Res.string.fit_record_summary_trim_remove,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** The draft's stat grid — the detail screen's treatment over the not-yet-saved workout. */
@Composable
private fun SummaryStatGrid(workout: Workout) {
    val distance = workout.distanceM?.let { distanceValueUnit(it) }
    val pace = paceValueUnit(workout.effectivePaceSecPerKm)
    val energy = energyValueUnit(workout.activeEnergyKj)
    val bpm = stringResource(Res.string.fit_bpm)
    val cells = listOfNotNull(
        SummaryCell(durationClock(workout.durationSec), null, stringResource(Res.string.fit_workout_stat_duration)),
        SummaryCell(distance?.value ?: "—", distance?.unit, stringResource(Res.string.fit_workout_stat_distance)),
        SummaryCell(pace.value, pace.unit, stringResource(Res.string.fit_workout_stat_pace)),
        SummaryCell(energy.value, energy.unit, stringResource(Res.string.fit_workout_stat_energy)),
        SummaryCell(intOrDash(workout.avgHr), bpm, stringResource(Res.string.fit_workout_stat_avg_hr)),
        SummaryCell(intOrDash(workout.maxHr), bpm, stringResource(Res.string.fit_workout_stat_max_hr)),
        workout.steps?.let { SummaryCell(groupThousands(it), null, stringResource(Res.string.fit_record_steps)) },
    )
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            cells.chunked(3).forEach { rowCells ->
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    rowCells.forEach { cell ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                                StatCell(value = cell.value, unit = cell.unit, label = cell.label)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class SummaryCell(
    val value: String,
    val unit: String?,
    val label: String,
)
