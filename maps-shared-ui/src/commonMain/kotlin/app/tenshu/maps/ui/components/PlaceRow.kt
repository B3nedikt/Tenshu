package app.tenshu.maps.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tenshu.maps.model.Place
import app.tenshu.maps.ui.text.relativeVisitText
import app.tenshu.maps.ui.text.visitCountText
import app.tenshu.ui.theme.spacing
import kotlinx.datetime.LocalDate

/** What a [PlaceRow] shows at its trailing edge (handoff §1.1). */
enum class PlaceRowTrailing { Chevron, Saved }

/**
 * A place card row (Ph4, handoff §1.1 `PlaceRow`): category avatar · name + a meta line ·
 * a trailing chevron (navigates) or bookmark toggle. The meta reads "N visits · last-visit"
 * when visited, else the address. [today] resolves the relative last-visit.
 */
@Composable
fun PlaceRow(
    place: Place,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentByCategory: Boolean = false,
    trailing: PlaceRowTrailing = PlaceRowTrailing.Chevron,
    onToggleSaved: (Boolean) -> Unit = {},
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = MaterialTheme.spacing.xs, vertical = MaterialTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m),
    ) {
        CategoryAvatar(category = place.category, accentByCategory = accentByCategory)

        Column(Modifier.weight(1f)) {
            Text(
                text = place.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (place.visitCount > 0) {
                    "${visitCountText(place.visitCount)} · ${relativeVisitText(today, place.lastVisit)}"
                } else {
                    place.address
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when (trailing) {
            PlaceRowTrailing.Chevron -> Icon(
                imageVector = MapsIcons.chevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            PlaceRowTrailing.Saved -> SavedToggle(saved = place.saved, onToggle = onToggleSaved)
        }
    }
}
