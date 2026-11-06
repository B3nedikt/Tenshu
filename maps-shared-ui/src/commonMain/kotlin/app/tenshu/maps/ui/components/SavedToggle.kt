package app.tenshu.maps.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.tenshu.maps.shared.resources.Res
import app.tenshu.maps.shared.resources.maps_save_cd
import app.tenshu.maps.shared.resources.maps_unsave_cd
import org.jetbrains.compose.resources.stringResource

/**
 * Bookmark toggle (Ph4, handoff §5) — filled when saved, outline when not. Used by the
 * Saved-tab rows, the detail header, and "For you" Save.
 */
@Composable
fun SavedToggle(
    saved: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = { onToggle(!saved) },
        modifier = modifier.clip(CircleShape),
        colors = if (saved) {
            IconButtonDefaults.filledTonalIconButtonColors()
        } else {
            IconButtonDefaults.iconButtonColors()
        },
    ) {
        Icon(
            imageVector = if (saved) MapsIcons.bookmarkFilled else MapsIcons.bookmark,
            contentDescription = stringResource(if (saved) Res.string.maps_unsave_cd else Res.string.maps_save_cd),
            tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
