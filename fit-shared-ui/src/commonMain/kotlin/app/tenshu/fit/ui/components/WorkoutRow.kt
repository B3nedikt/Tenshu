package app.tenshu.fit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.Source
import app.tenshu.fit.ui.theme.tabular

/**
 * A workout list row (fit-agent.md §1.2/§1.3) shared by Home's latest-workout card and
 * History's day feed: activity glyph, title + provenance badge, a tabular meta line, and a
 * trailing chevron. [meta] is composed by the caller (Home shows date · time · stats;
 * History shows time · duration · distance).
 */
@Composable
fun WorkoutRow(
    activityType: ActivityType,
    title: String,
    source: Source,
    meta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivityGlyph(activityType)
        Column(Modifier.weight(1f)) {
            // The badge keeps the first line and the title breaks around it. Its weight is what
            // makes that work: an unweighted title is measured first against the full width and
            // starves the badge, which then wraps to one letter per line ("Rec / orde / d").
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Nudged onto the first line's optical centre; the row aligns on the top edge so
                // the badge stays put when the title breaks.
                SourceBadge(source, Modifier.padding(top = 3.dp))
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            FitIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
