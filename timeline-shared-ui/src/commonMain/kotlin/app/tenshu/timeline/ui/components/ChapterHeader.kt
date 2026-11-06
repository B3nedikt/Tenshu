package app.tenshu.timeline.ui.components

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_moment_count
import app.tenshu.timeline.ui.theme.TimelineType
import org.jetbrains.compose.resources.stringResource

/**
 * A day-chapter header (Ph4, docs/proposals/timeline-app.md §1.1): a small-caps eyebrow in
 * the accent (with an optional trailing zoom-chip slot), an italic-serif date, and the
 * [MomentRoute] summary of the day's moments.
 */
@Composable
fun ChapterHeader(
    eyebrow: String,
    dateLong: String,
    momentKinds: List<MomentKind>,
    momentCount: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    zoomChip: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
                color = accent,
            )
            zoomChip?.invoke()
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = dateLong,
            style = TimelineType.date,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        MomentRoute(momentKinds, accent, momentCount)
    }
}

/** The longest icon chain we draw before eliding — keeps the route glanceable and one line. */
private const val MAX_ROUTE_ICONS = 6

/**
 * A line of the day's moment icons joined by dotted connectors, then the moment count
 * (§1.1). The signature "route" summary — a glanceable shape of the day.
 *
 * The count is measured first (non-weighted) so it always renders on a single line; the icon
 * route takes only the remaining width ([Modifier.weight] with `fill = false`) and is capped at
 * [MAX_ROUTE_ICONS] with a trailing "…" marker. Without both guards a busy day (many icons)
 * squeezes the count into one-letter-per-line vertical text.
 */
@Composable
fun MomentRoute(
    momentKinds: List<MomentKind>,
    accent: Color,
    momentCount: Int,
    modifier: Modifier = Modifier,
) {
    val shownKinds = momentKinds.take(MAX_ROUTE_ICONS)
    val truncated = momentKinds.size > MAX_ROUTE_ICONS
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            shownKinds.forEachIndexed { index, kind ->
                Icon(
                    imageVector = TimelineIcons.forMoment(kind),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                if (truncated || index != shownKinds.lastIndex) DottedConnector(accent)
            }
            if (truncated) {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
            }
        }
        if (momentKinds.isNotEmpty()) Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(Res.string.timeline_moment_count, momentCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun DottedConnector(accent: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) {
            Box(Modifier.size(2.dp).clip(CircleShape).background(accent.copy(alpha = 0.5f)))
        }
    }
}
