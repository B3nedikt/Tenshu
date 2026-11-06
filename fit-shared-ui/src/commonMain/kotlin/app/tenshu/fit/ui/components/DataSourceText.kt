package app.tenshu.fit.ui.components

import androidx.compose.runtime.Composable
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.model.DataSourceState
import app.tenshu.fit.model.ImportRunEntry
import app.tenshu.fit.model.ImportRunStatus
import app.tenshu.fit.model.ImportRunWindow
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_batch_import_title
import app.tenshu.fit.shared.resources.fit_import_running
import app.tenshu.fit.shared.resources.fit_import_running_count
import app.tenshu.fit.shared.resources.fit_run_failed
import app.tenshu.fit.shared.resources.fit_run_full_history
import app.tenshu.fit.shared.resources.fit_run_item_one
import app.tenshu.fit.shared.resources.fit_run_workout_one
import app.tenshu.fit.shared.resources.fit_source_status_imported_at_one
import app.tenshu.fit.shared.resources.fit_run_items
import app.tenshu.fit.shared.resources.fit_run_nothing
import app.tenshu.fit.shared.resources.fit_run_permission
import app.tenshu.fit.shared.resources.fit_run_workouts
import app.tenshu.fit.shared.resources.fit_source_apple_health
import app.tenshu.fit.shared.resources.fit_source_health_connect
import app.tenshu.fit.shared.resources.fit_source_health_connect_hint
import app.tenshu.fit.shared.resources.fit_source_status_failed_at
import app.tenshu.fit.shared.resources.fit_source_status_imported_at
import app.tenshu.fit.shared.resources.fit_source_status_never_run
import app.tenshu.fit.shared.resources.fit_source_status_no_data_at
import app.tenshu.fit.shared.resources.fit_source_status_permission
import app.tenshu.fit.shared.resources.fit_source_status_detecting
import app.tenshu.fit.shared.resources.fit_source_status_detection_off
import app.tenshu.fit.shared.resources.fit_source_status_sample
import app.tenshu.fit.shared.resources.fit_source_status_unavailable
import app.tenshu.fit.shared.resources.fit_source_tenshu_health
import app.tenshu.fit.shared.resources.fit_source_tenshu_health_hint
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/**
 * Copy for the Data sources surface (`fit-data-sources-restructure.md` DS-4).
 *
 * ⚠⚠ **Localisation only.** Every *decision* — which state a row shows, whether it needs
 * attention, what "last import" means when the newest run failed — was already made by
 * `DataSourceProjection` and is desktop-tested there (D10). Nothing here may re-derive one; a
 * `when` over a sealed state is a translation table, not a rule.
 */

@Composable
fun dataSourceName(kind: DataSourceKind): String = stringResource(
    when (kind) {
        DataSourceKind.TenshuHealth -> Res.string.fit_source_tenshu_health
        DataSourceKind.HealthConnect -> Res.string.fit_source_health_connect
        DataSourceKind.AppleHealth -> Res.string.fit_source_apple_health
    }
)

@Composable
fun dataSourceHint(kind: DataSourceKind): String = stringResource(
    when (kind) {
        DataSourceKind.TenshuHealth -> Res.string.fit_source_tenshu_health_hint
        // Apple Health reads the same categories, so the one hint serves both platform stores.
        DataSourceKind.HealthConnect, DataSourceKind.AppleHealth ->
            Res.string.fit_source_health_connect_hint
    }
)

/** A source row's right-hand text. Never null: every state has something honest to say. */
@Composable
fun dataSourceStatus(state: DataSourceState): String = when (state) {
    DataSourceState.NeverRun -> stringResource(Res.string.fit_source_status_never_run)
    is DataSourceState.Running ->
        if (state.imported > 0) stringResource(Res.string.fit_import_running_count, state.imported)
        else stringResource(Res.string.fit_import_running)
    is DataSourceState.Imported ->
        if (state.itemCount == 1L) {
            stringResource(Res.string.fit_source_status_imported_at_one, formatRunDate(state.at))
        } else {
            stringResource(Res.string.fit_source_status_imported_at, state.itemCount, formatRunDate(state.at))
        }
    is DataSourceState.NoData ->
        stringResource(Res.string.fit_source_status_no_data_at, formatRunDate(state.at))
    is DataSourceState.Failed ->
        stringResource(Res.string.fit_source_status_failed_at, formatRunDate(state.at))
    DataSourceState.PermissionNeeded -> stringResource(Res.string.fit_source_status_permission)
    DataSourceState.Unavailable -> stringResource(Res.string.fit_source_status_unavailable)
    DataSourceState.Sample -> stringResource(Res.string.fit_source_status_sample)
    is DataSourceState.Recording -> stringResource(
        if (state.autoDetect) Res.string.fit_source_status_detecting
        else Res.string.fit_source_status_detection_off
    )
}

/** One history row's headline — what the run did. */
@Composable
fun importRunHeadline(entry: ImportRunEntry): String = when (entry.status) {
    ImportRunStatus.Succeeded ->
        if (entry.itemCount == 1L) stringResource(Res.string.fit_run_item_one)
        else stringResource(Res.string.fit_run_items, entry.itemCount)
    ImportRunStatus.NoData -> stringResource(Res.string.fit_run_nothing)
    ImportRunStatus.PermissionsMissing -> stringResource(Res.string.fit_run_permission)
    ImportRunStatus.Failed -> stringResource(Res.string.fit_run_failed)
}

/**
 * One history row's supporting line: date, then whatever else this run can honestly say.
 *
 * ⚠ Megabytes appear only for a file import — a platform health store hands us objects, not
 * bytes, so `byteCount` is null there and inventing one would be a fabricated figure (D6).
 */
@Composable
fun importRunDetail(entry: ImportRunEntry): String {
    val parts = mutableListOf(formatRunDate(entry.startedAt))
    if (entry.window == ImportRunWindow.FullHistory) {
        parts += stringResource(Res.string.fit_run_full_history)
    }
    if (entry.workoutCount == 1L) {
        parts += stringResource(Res.string.fit_run_workout_one)
    } else if (entry.workoutCount > 0) {
        parts += stringResource(Res.string.fit_run_workouts, entry.workoutCount)
    }
    entry.byteCount?.let { parts += formatBytes(it) }
    entry.fileName?.let { parts += it }
    entry.deviceLabel?.let { parts += it }
    return parts.joinToString(" · ")
}

/** The batch importer's own label, reused by its section row and its screen title. */
@Composable
fun batchImportName(): String = stringResource(Res.string.fit_batch_import_title)

/**
 * `dd.MM.yyyy` — the shape the trigger asked for ("Last import: 01.08.2026").
 *
 * ⚠ Hand-formatted because `kotlinx-datetime` has no locale-aware formatter on every target Fit
 * ships to. It is a deliberate, uniform format rather than a locale one; if that becomes a
 * problem it is a `ui-core` question, not a Fit one.
 */
fun formatRunDate(epochMillis: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.dayOfMonth.pad()}.${local.monthNumber.pad()}.${local.year}"
}

private fun Int.pad(): String = if (this < 10) "0$this" else toString()

/**
 * A size a person can read. ⚠ Decimal MB (10^6), matching what a file manager shows for the same
 * archive — a figure the user can cross-check is worth more than a binary-exact one.
 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "${bytes / 100_000_000L / 10.0} GB"
    bytes >= 1_000_000L -> "${bytes / 1_000_000L} MB"
    bytes >= 1_000L -> "${bytes / 1_000L} kB"
    else -> "$bytes B"
}
