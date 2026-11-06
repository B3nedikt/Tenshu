package app.tenshu.fit.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import app.tenshu.fit.model.Units
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_edit_birth_date_title
import app.tenshu.fit.shared.resources.fit_edit_cancel
import app.tenshu.fit.shared.resources.fit_edit_clear
import app.tenshu.fit.shared.resources.fit_edit_decrease
import app.tenshu.fit.shared.resources.fit_edit_goals_title
import app.tenshu.fit.shared.resources.fit_edit_height_cm
import app.tenshu.fit.shared.resources.fit_edit_height_ft
import app.tenshu.fit.shared.resources.fit_edit_height_in
import app.tenshu.fit.shared.resources.fit_edit_height_title
import app.tenshu.fit.shared.resources.fit_edit_increase
import app.tenshu.fit.shared.resources.fit_edit_measurement_hint
import app.tenshu.fit.shared.resources.fit_edit_name_hint
import app.tenshu.fit.shared.resources.fit_edit_name_title
import app.tenshu.fit.shared.resources.fit_edit_save
import app.tenshu.fit.shared.resources.fit_edit_sex_hint
import app.tenshu.fit.shared.resources.fit_edit_sex_title
import app.tenshu.fit.shared.resources.fit_edit_weight_kg
import app.tenshu.fit.shared.resources.fit_edit_weight_lb
import app.tenshu.fit.shared.resources.fit_edit_view_history
import app.tenshu.fit.shared.resources.fit_edit_weight_title
import app.tenshu.fit.shared.resources.fit_profile_goal_active_minutes
import app.tenshu.fit.shared.resources.fit_profile_goal_steps
import app.tenshu.fit.shared.resources.fit_profile_not_set
import app.tenshu.fit.shared.resources.fit_sex_female
import app.tenshu.fit.shared.resources.fit_sex_male
import app.tenshu.fit.shared.resources.fit_sex_other
import app.tenshu.fit.ui.components.FitIcons
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import org.tenshu.preference.BiologicalSex
import kotlin.math.roundToInt

/**
 * The Profile row editors (`docs/proposals/fit-app.md` §18.6).
 *
 * **Dialogs, not destinations.** Five fields, each one value — a screen per field would be more
 * navigation than the content earns, and a dialog keeps the row it edits on screen behind it.
 *
 * Each editor holds its draft in local state and commits only on Save, so dismissing never writes.
 * Height and weight commit a **`HealthSample`**, not a setting: the same row Health Connect writes
 * (§18.2), which is why their copy says "saved as a new reading" rather than implying an edit.
 */

/** Which editor is open. `null` = none; the screen owns exactly one of these at a time. */
internal enum class ProfileEditor { Name, Goals, BirthDate, Sex, Height, Weight }

@Composable
internal fun NameDialog(current: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by remember { mutableStateOf(current.orEmpty()) }
    EditDialog(
        title = stringResource(Res.string.fit_edit_name_title),
        onDismiss = onDismiss,
        onSave = { onSave(draft) },
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Hint(stringResource(Res.string.fit_edit_name_hint))
    }
}

/**
 * Both daily targets in one dialog — they are set together in practice, and iOS has always shown
 * them as one pair of steppers (whose bounds and increments this mirrors exactly).
 */
@Composable
internal fun GoalsDialog(
    activeMinutes: Int,
    steps: Int,
    onDismiss: () -> Unit,
    onSave: (Int, Int) -> Unit,
) {
    var minutes by remember { mutableStateOf(activeMinutes) }
    var stepGoal by remember { mutableStateOf(steps) }
    EditDialog(
        title = stringResource(Res.string.fit_edit_goals_title),
        onDismiss = onDismiss,
        onSave = { onSave(minutes, stepGoal) },
    ) {
        Stepper(
            label = stringResource(Res.string.fit_profile_goal_active_minutes),
            value = minutes,
            onChange = { minutes = it.coerceIn(5, 240) },
            step = 5,
        )
        Stepper(
            label = stringResource(Res.string.fit_profile_goal_steps),
            value = stepGoal,
            onChange = { stepGoal = it.coerceIn(1_000, 30_000) },
            step = 500,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BirthDateDialog(
    current: LocalDate?,
    onDismiss: () -> Unit,
    onSave: (LocalDate?) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = current?.let { it.toEpochDays() * MILLIS_PER_DAY },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSave(state.selectedDateMillis?.let { LocalDate.fromEpochDays((it / MILLIS_PER_DAY).toInt()) })
            }) { Text(stringResource(Res.string.fit_edit_save)) }
        },
        dismissButton = {
            // Clearing must stay reachable: "not set" is a real answer, and a mis-tap on a date
            // picker would otherwise be permanent.
            Row {
                TextButton(onClick = { onSave(null) }) { Text(stringResource(Res.string.fit_edit_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.fit_edit_cancel)) }
            }
        },
    ) {
        Text(
            stringResource(Res.string.fit_edit_birth_date_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, top = 16.dp),
        )
        DatePicker(state = state, title = null)
    }
}

@Composable
internal fun SexDialog(
    current: BiologicalSex?,
    onDismiss: () -> Unit,
    onSave: (BiologicalSex?) -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    EditDialog(
        title = stringResource(Res.string.fit_edit_sex_title),
        onDismiss = onDismiss,
        onSave = { onSave(draft) },
    ) {
        // "Not set" is offered as a first-class option rather than hidden behind a Clear button:
        // it is the honest answer for anyone who would rather not say, and the estimate falls back
        // to a sex-neutral formula (§18.4).
        Choice(stringResource(Res.string.fit_profile_not_set), draft == null) { draft = null }
        BiologicalSex.entries.forEach { option ->
            Choice(sexLabel(option), draft == option) { draft = option }
        }
        Hint(stringResource(Res.string.fit_edit_sex_hint))
    }
}

@Composable
internal fun HeightDialog(
    currentCm: Int?,
    units: Units,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    // Entered in the unit the row displays — asking an imperial user for centimetres would be a
    // conversion they have to do in their head.
    if (units == Units.Imperial) {
        val totalInches = currentCm?.let { (it / 2.54).roundToInt() }
        var feet by remember { mutableStateOf(totalInches?.let { it / 12 }?.toString().orEmpty()) }
        var inches by remember { mutableStateOf(totalInches?.let { it % 12 }?.toString().orEmpty()) }
        EditDialog(
            title = stringResource(Res.string.fit_edit_height_title),
            onDismiss = onDismiss,
            onSave = {
                val total = (feet.toIntOrNull() ?: 0) * 12 + (inches.toIntOrNull() ?: 0)
                if (total > 0) onSave((total * 2.54).roundToInt())
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(feet, { feet = it }, stringResource(Res.string.fit_edit_height_ft), Modifier.weight(1f))
                NumberField(inches, { inches = it }, stringResource(Res.string.fit_edit_height_in), Modifier.weight(1f))
            }
            Hint(stringResource(Res.string.fit_edit_measurement_hint))
        }
    } else {
        var draft by remember { mutableStateOf(currentCm?.toString().orEmpty()) }
        EditDialog(
            title = stringResource(Res.string.fit_edit_height_title),
            onDismiss = onDismiss,
            onSave = { draft.toIntOrNull()?.let(onSave) },
        ) {
            NumberField(draft, { draft = it }, stringResource(Res.string.fit_edit_height_cm), Modifier.fillMaxWidth())
            Hint(stringResource(Res.string.fit_edit_measurement_hint))
        }
    }
}

@Composable
internal fun WeightDialog(
    currentKg: Double?,
    units: Units,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
    /** Opens Browse → Body. Omitted where there is no history screen to open. */
    onViewHistory: (() -> Unit)? = null,
) {
    val imperial = units == Units.Imperial
    val shown = currentKg?.let { if (imperial) it * LB_PER_KG else it }
    var draft by remember { mutableStateOf(shown?.let { ((it * 10).roundToInt() / 10.0).toString() }.orEmpty()) }
    EditDialog(
        title = stringResource(Res.string.fit_edit_weight_title),
        onDismiss = onDismiss,
        onSave = {
            draft.replace(',', '.').toDoubleOrNull()?.let { onSave(if (imperial) it / LB_PER_KG else it) }
        },
    ) {
        NumberField(
            value = draft,
            onValueChange = { draft = it },
            label = stringResource(if (imperial) Res.string.fit_edit_weight_lb else Res.string.fit_edit_weight_kg),
            modifier = Modifier.fillMaxWidth(),
            decimal = true,
        )
        Hint(stringResource(Res.string.fit_edit_measurement_hint))
        if (onViewHistory != null) {
            TextButton(onClick = onViewHistory, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(Res.string.fit_edit_view_history))
            }
        }
    }
}

@Composable
internal fun sexLabel(sex: BiologicalSex): String = when (sex) {
    BiologicalSex.FEMALE -> stringResource(Res.string.fit_sex_female)
    BiologicalSex.MALE -> stringResource(Res.string.fit_sex_male)
    BiologicalSex.OTHER -> stringResource(Res.string.fit_sex_other)
}

// ── shared dialog scaffolding ─────────────────────────────────────────────────────────

@Composable
private fun EditDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } },
        confirmButton = {
            TextButton(onClick = { onSave(); onDismiss() }) {
                Text(stringResource(Res.string.fit_edit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.fit_edit_cancel)) }
        },
    )
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        // Filter at the source rather than validating on save: a field that cannot hold a letter
        // never has to explain that it rejected one.
        onValueChange = { raw -> onValueChange(raw.filter { it.isDigit() || (decimal && (it == '.' || it == ',')) }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        ),
        modifier = modifier,
    )
}

/** The Compose twin of iOS's `Stepper` — Material 3 has no such control. */
@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit, step: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange(value - step) }) {
            Icon(FitIcons.Minus, contentDescription = stringResource(Res.string.fit_edit_decrease))
        }
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp),
        )
        IconButton(onClick = { onChange(value + step) }) {
            Icon(FitIcons.Plus, contentDescription = stringResource(Res.string.fit_edit_increase))
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val MILLIS_PER_DAY = 86_400_000L
private const val LB_PER_KG = 2.2046
