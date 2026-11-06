package app.tenshu.fit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tenshu.fit.model.Source

/**
 * Provenance pill (fit-agent.md §0.6 — always shown). A small source glyph + label in a
 * quiet outlined pill; [subtle] draws it on transparent (the default in dense rows),
 * otherwise it fills a container wash (the workout-detail hero band).
 */
@Composable
fun SourceBadge(
    source: Source,
    modifier: Modifier = Modifier,
    subtle: Boolean = true,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val fg = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = if (subtle) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, outline),
    ) {
        Row(
            modifier = Modifier.padding(PaddingValues(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FitIcons.source(source),
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = sourceLabel(source),
                style = MaterialTheme.typography.labelSmall,
                color = fg,
            )
        }
    }
}
