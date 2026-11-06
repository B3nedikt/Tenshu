package app.tenshu.fit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_trust_cue
import org.jetbrains.compose.resources.stringResource

/**
 * Shared low-level composables (Ph4, fit-agent.md §0/§2). A quiet card wash, an uppercase
 * eyebrow / section header, the recurring "synced to your realm" trust cue. Tokens from
 * `:ui-core`; no hex literals.
 */

/**
 * The recurring content card — a low-emphasis surface at the M3 large-shape radius.
 *
 * [shape] is overridable so a *run* of cards can read as one: History's paged feed emits one card
 * per workout (a day straddles a page boundary, so it cannot emit one card per day any more) and
 * squares off the inner corners, which puts the seam back exactly where the day-grouped card had
 * its divider. Derive the override from `MaterialTheme.shapes.large` rather than a literal radius.
 */
@Composable
fun FitCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(Modifier.padding(padding)) { content() }
    }
}

/** Uppercase, wide-tracked section eyebrow (label-medium). */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** A detail-section header in onSurfaceVariant (uppercase title-small). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun TrustCue(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FitIcons.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(Res.string.fit_trust_cue),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
