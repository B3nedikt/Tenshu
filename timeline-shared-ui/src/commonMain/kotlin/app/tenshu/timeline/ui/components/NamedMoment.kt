package app.tenshu.timeline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tenshu.timeline.model.Moment
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_now
import app.tenshu.timeline.ui.theme.Personalisation
import app.tenshu.timeline.ui.theme.TimelineType
import org.jetbrains.compose.resources.stringResource

private val RAIL_WIDTH = 52.dp

/**
 * A named moment (Ph4, docs/proposals/timeline-app.md §1.1) — the core feed entry, reused
 * on Home / Trip / Place. Left rail: time (tabular) + a 32dp icon circle (soft-tinted when
 * the moment has photos). Right: italic-serif title, an optional live "● now" tag, the
 * [PhotoCluster], then `place · meta`. The connector line is drawn behind the row (down the
 * rail gutter) — [drawBehind], not an intrinsic-height child, so it composes over the
 * `SubcomposeLayout`-based [PhotoCluster] without forcing intrinsic measurement.
 *
 * @param personalisation the (photo-derived) accent/soft tint for this moment.
 * @param showConnector draw the rail's trailing connector line (false for the last entry).
 */
@Composable
fun NamedMoment(
    moment: Moment,
    personalisation: Personalisation,
    modifier: Modifier = Modifier,
    showConnector: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val hasPhotos = moment.photos.isNotEmpty()
    val connectorColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .fillMaxWidth()
            .drawBehind {
                if (!showConnector) return@drawBehind
                val x = RAIL_WIDTH.toPx() / 2f
                val top = 62.dp.toPx() // below time + icon circle
                drawLine(
                    color = connectorColor,
                    start = Offset(x, top),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        // ── Left rail: time + icon circle ──
        Column(
            modifier = Modifier.width(RAIL_WIDTH),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = moment.time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (hasPhotos) personalisation.soft else Color.Transparent)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    // A movement gets its per-mode glyph (walk / run / bike / car / transit);
                    // every other kind keeps the one-per-kind icon.
                    imageVector = if (moment.kind == MomentKind.Movement) {
                        TimelineIcons.forMovement(moment.movementMode)
                    } else {
                        TimelineIcons.forMoment(moment.kind)
                    },
                    contentDescription = null,
                    tint = personalisation.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // ── Right: title + now tag + photos + meta ──
        Column(modifier = Modifier.weight(1f).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = momentTitleText(moment),
                    style = TimelineType.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (moment.isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    NowTag(personalisation.accent)
                }
            }
            if (hasPhotos) {
                Spacer(Modifier.height(8.dp))
                PhotoCluster(
                    photos = moment.photos,
                    accent = personalisation.accent,
                    // ⚠⚠ The row's own click, never the cross-app opener. A feed card is ONE
                    // target: the picture is a thumbnail *of* the moment, so tapping it must land
                    // where tapping the title lands. It used to leave Timeline for whichever app
                    // owns the file — the same card opening two different apps depending on which
                    // pixel was pressed. The photo is the subject, and the cross-app open the right
                    // answer, only on the detail screens (see `PhotoCluster.tapAction`).
                    //
                    // ⚠ Falls back to the opener when the row itself does not navigate: a row with
                    // no destination has nothing better to offer, and an inert photo would be a
                    // loss rather than a fix.
                    onPhotoTap = onClick?.let { open -> { _ -> open() } },
                )
            }
            Spacer(Modifier.height(6.dp))
            // A Visit and a Movement now carry their own subject in the title — the place name and
            // the route respectively ([momentTitleText]) — so this line must not repeat it. That
            // repetition is what made the feed read "Visit / Blue Bottle · …" down the whole column,
            // and a movement render its mode and distance twice over ("Ride · 12.4 km" above
            // "Ride · 12.4 km · 38 min"). Every other kind still leads with its subject here.
            val place = when (moment.kind) {
                MomentKind.Visit, MomentKind.Movement -> ""
                else -> moment.place
            }
            // A subject-less Event still has an empty lead; drop the separator rather than orphan it.
            val meta = momentMetaText(moment)
            Text(
                text = if (place.isBlank()) meta else "$place · $meta",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NowTag(accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(Res.string.timeline_now),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
        )
    }
}
