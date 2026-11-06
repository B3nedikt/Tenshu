package app.tenshu.timeline.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_detail_loading_a11y
import app.tenshu.timeline.shared.resources.timeline_detail_retry
import app.tenshu.timeline.shared.resources.timeline_detail_unavailable
import app.tenshu.timeline.shared.resources.timeline_detail_unavailable_hint
import app.tenshu.ui.feedback.AppLoadingIndicator
import app.tenshu.ui.feedback.AppLoadingSize
import org.jetbrains.compose.resources.stringResource

/**
 * The non-content states of a detail destination — loading, not-found, unreachable.
 *
 * One component for all three of Timeline's detail screens, because all three had **the same two
 * bugs** and fixing them once per screen is how the second one stayed broken: place detail was
 * repaired on 2026-08-03 while moment and trip kept rendering a bare `Center("…")`, and the user's
 * report came back unchanged because a feed row opens *moment* detail.
 *
 * Three things every state needs and none of them had:
 *
 *  1. **The detail chrome**, so back always works. A bare centred string is a dead end on a screen
 *     the user reached by tapping a row.
 *  2. **A spinner rather than an ellipsis.** `…` is indistinguishable from broken; it is also
 *     untranslatable and unreadable to a screen reader, which is why the indicator carries a
 *     `liveRegion` description instead.
 *  3. **A distinction between "not there" and "could not be read".** They are different facts and
 *     only one of them is worth a retry — see [DetailUnavailable].
 */
@Composable
private fun DetailStatus(
    onBack: () -> Unit,
    modifier: Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    // Title is blank deliberately: in every one of these states the subject's name is precisely
    // what is not known yet, and inventing a placeholder is worse than an empty bar.
    TimelineDetailScaffold(title = "", onBack = onBack, modifier = modifier) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
        }
    }
}

/** The read is in flight. */
@Composable
fun DetailLoading(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val a11y = stringResource(Res.string.timeline_detail_loading_a11y)
    DetailStatus(onBack, modifier) {
        AppLoadingIndicator(
            size = AppLoadingSize.Large,
            modifier = Modifier.semantics {
                contentDescription = a11y
                liveRegion = LiveRegionMode.Polite
            },
        )
    }
}

/** The read **succeeded** and the subject genuinely does not exist. No retry — nothing would change. */
@Composable
fun DetailNotFound(message: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    DetailStatus(onBack, modifier) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The read could not be completed — in practice Control died or was unreachable mid-load.
 *
 * ⚠ Deliberately NOT [DetailNotFound]: conflating them tells the user their moment/trip/place is
 * gone when it is merely unreadable from this device right now, which reads as data loss. This one
 * *is* actionable — the read is one tap from succeeding once Control is back, and nothing else
 * retries a one-shot detail load.
 */
@Composable
fun DetailUnavailable(onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    DetailStatus(onBack, modifier) {
        Text(
            stringResource(Res.string.timeline_detail_unavailable),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.timeline_detail_unavailable_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(stringResource(Res.string.timeline_detail_retry)) }
    }
}
