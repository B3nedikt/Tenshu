package app.tenshu.maps.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.tenshu.maps.model.Place
import app.tenshu.maps.shared.resources.Res
import app.tenshu.maps.shared.resources.maps_last_visit_label
import app.tenshu.maps.shared.resources.maps_saved_not_visited_label
import app.tenshu.maps.shared.resources.maps_visits_label
import app.tenshu.maps.ui.text.relativeVisitText
import app.tenshu.maps.ui.text.visitCountText
import app.tenshu.ui.theme.spacing
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/**
 * The visit-history strip (Ph4, handoff §5 `VisitHistory`) on the place detail: the visit
 * count, the relative last-visit, and up to five recent-visit dots (the newest accented).
 * Reads [relativeVisitText] / [visitCountText] for the localised numbers; [today] is the
 * reference the relative dates resolve against.
 */
@Composable
fun VisitHistory(
    place: Place,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.l),
    ) {
        Column {
            Text(
                text = place.visitCount.toString(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.maps_visits_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(
            Modifier.width(1.dp).height(34.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )

        Column {
            Text(
                text = relativeVisitText(today, place.lastVisit),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    if (place.lastVisit != null) Res.string.maps_last_visit_label
                    else Res.string.maps_saved_not_visited_label,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (place.recentVisits.isNotEmpty()) {
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                place.recentVisits.take(5).forEachIndexed { index, _ ->
                    Box(
                        Modifier.size(7.dp).clip(CircleShape).background(
                            if (index == 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                }
            }
        }
    }
}
