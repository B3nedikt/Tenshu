package app.tenshu.control.ui.screens.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tenshu.control.data.OpenHandlerChoice
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.apps_open_with_body
import app.tenshu.control.shared.resources.apps_open_with_default
import app.tenshu.control.shared.resources.apps_open_with_title
import app.tenshu.control.shared.resources.apps_open_with_type_place
import org.jetbrains.compose.resources.stringResource
import org.tenshu.core.common.app.HandlerTable

/**
 * *"Opens by default"* — the user's primary-handler swap (`control-cross-app-open.md` TT-4b).
 *
 * ⚠⚠ **Rendered only when there is a choice to make.** [choices] is already filtered to types with
 * two-or-more candidates installed on *this* device, so an empty list means the section is absent
 * rather than empty-stated: explaining a setting the user cannot make is noise on a screen whose
 * job is the app list.
 *
 * ⚠ The default option names the app it would pick, rather than saying "Automatic". A user
 * choosing between *"Recommended"* and *"Timeline"* cannot tell whether those are the same thing;
 * *"Recommended (Timeline)"* answers it without a second screen.
 */
@Composable
fun OpenHandlerSection(
    choices: List<OpenHandlerChoice>,
    onChoose: (type: String, appId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (choices.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(Res.string.apps_open_with_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(Res.string.apps_open_with_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        choices.forEach { choice ->
            OpenHandlerRow(choice = choice, onChoose = onChoose)
        }
    }
}

@Composable
private fun OpenHandlerRow(
    choice: OpenHandlerChoice,
    onChoose: (type: String, appId: String?) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = openHandlerTypeLabel(choice.type),
            style = MaterialTheme.typography.bodyMedium,
        )
        // ⚠⚠ "Recommended" comes FIRST and is selected when nothing is chosen, so the list reads
        // as "the platform decides unless you say otherwise" rather than as a required decision.
        // `candidates` is in effective order, so the app it names is the one that would open.
        HandlerOption(
            label = stringResource(
                Res.string.apps_open_with_default,
                choice.candidates.first().name,
            ),
            selected = choice.chosenAppId == null,
            onSelect = { onChoose(choice.type, null) },
        )
        choice.candidates.forEach { app ->
            HandlerOption(
                label = app.name,
                selected = choice.chosenAppId == app.appId,
                onSelect = { onChoose(choice.type, app.appId) },
            )
        }
    }
}

/**
 * ⚠ The whole row is the target, not the button: `selectable` carries the click and the
 * `RadioButton` role, so the label is tappable and the row reads as one selectable thing to
 * accessibility services (and to `onNodeWithText`) instead of as a button beside some text.
 */
@Composable
private fun HandlerOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A human name for a routed data type.
 *
 * ⚠⚠ Falls back to the raw type rather than hiding the row: a choosable type with no label is a
 * missing string, and a user who can still make the choice is better served by an ugly label than
 * by a setting that silently disappeared. `OpenHandlerSectionTest` fails on the missing label so it
 * never reaches a build — the fallback is the safety net, not the plan.
 */
@Composable
internal fun openHandlerTypeLabel(type: String): String = when (type) {
    HandlerTable.PLACE_TYPE -> stringResource(Res.string.apps_open_with_type_place)
    else -> type
}
