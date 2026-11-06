package app.tenshu.fit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.ui.theme.ActivityAccents

/**
 * The soft-tinted activity glyph circle used in workout rows and the detail hero
 * (fit-agent.md §1.3). Colours come from the [ActivityAccents] seam — pure Amethyst in v1
 * (D9); the `type` will steer a per-sport hue if that setting is ever enabled.
 */
@Composable
fun ActivityGlyph(
    type: ActivityType,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 23.dp,
) {
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = ActivityAccents.container(type),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                FitIcons.activity(type),
                contentDescription = null,
                tint = ActivityAccents.onContainer(type),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
