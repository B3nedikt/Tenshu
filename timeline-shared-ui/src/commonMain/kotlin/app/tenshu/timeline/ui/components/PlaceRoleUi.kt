package app.tenshu.timeline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tenshu.timeline.model.PlaceRoleNames
import app.tenshu.timeline.model.PlaceRoleState
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_place_role_confirm
import app.tenshu.timeline.shared.resources.timeline_place_role_dismiss_home
import app.tenshu.timeline.shared.resources.timeline_place_role_dismiss_work
import app.tenshu.timeline.shared.resources.timeline_place_role_home
import app.tenshu.timeline.shared.resources.timeline_place_role_prompt_home
import app.tenshu.timeline.shared.resources.timeline_place_role_prompt_work
import app.tenshu.timeline.shared.resources.timeline_place_role_work
import org.jetbrains.compose.resources.stringResource

/**
 * A role's display name, localised — `null` for a role this build does not recognise.
 *
 * ⚠ Returning `null` rather than the raw token is the point: a newer peer may sync `gym`, and
 * printing the wire string at the user is worse than falling back to the place's own name. The
 * unknown role is still *stored* and still syncs; it just is not rendered here.
 */
@Composable
fun placeRoleName(role: String?): String? = when (role) {
    PlaceRoleNames.HOME -> stringResource(Res.string.timeline_place_role_home)
    PlaceRoleNames.WORK -> stringResource(Res.string.timeline_place_role_work)
    else -> null
}

/**
 * The confirm prompt for an **inferred** role (place-roles.md PR-3) — "Is this your home?" with a
 * yes and a real refusal.
 *
 * The shape follows `InterludeCard`: a soft-filled rounded block with dismiss on the left as an
 * `OutlinedButton` and the affirmative on the right as an accent `TextButton`. ⚠ Unlike the trip
 * suggestion it borrows from, **dismissal here persists** — it writes `role = none` as a
 * user-authored answer rather than hiding the card for this session, which is why the button says
 * "Not my home" rather than "Not now". The trip precedent's in-memory `resolvedSuggestions` set
 * would make this prompt reappear on every launch.
 *
 * Renders nothing when [state] is absent, already confirmed, or a role this build cannot name.
 */
@Composable
fun PlaceRolePrompt(
    state: PlaceRoleState?,
    accent: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == null || !state.isSuggestion || state.role == PlaceRoleNames.NONE) return
    val prompt = when (state.role) {
        PlaceRoleNames.HOME -> stringResource(Res.string.timeline_place_role_prompt_home)
        PlaceRoleNames.WORK -> stringResource(Res.string.timeline_place_role_prompt_work)
        // An unrecognised role has no sensible question — say nothing rather than guess at one.
        else -> return
    }
    val refuse = when (state.role) {
        PlaceRoleNames.WORK -> stringResource(Res.string.timeline_place_role_dismiss_work)
        else -> stringResource(Res.string.timeline_place_role_dismiss_home)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = prompt,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onDismiss) { Text(refuse) }
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.timeline_place_role_confirm), color = accent)
            }
        }
    }
}

/**
 * A confirmed role, as a small accent chip. Renders nothing for a suggestion (that is
 * [PlaceRolePrompt]'s job), for `none`, or for a role this build cannot name.
 */
@Composable
fun PlaceRoleChip(state: PlaceRoleState?, accent: Color, modifier: Modifier = Modifier) {
    if (state == null || !state.isConfirmed) return
    val name = placeRoleName(state.role) ?: return
    Text(
        text = name.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = accent,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
