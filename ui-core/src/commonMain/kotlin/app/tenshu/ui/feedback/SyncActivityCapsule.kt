package app.tenshu.ui.feedback

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import app.tenshu.ui.resources.Res
import app.tenshu.ui.resources.sync_activity_catching_up
import app.tenshu.ui.resources.sync_activity_catching_up_a11y
import app.tenshu.ui.resources.sync_activity_uploading
import app.tenshu.ui.resources.sync_activity_uploading_count
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.tenshu.sync.common.SyncActivity
import org.tenshu.sync.common.SyncActivityTiming
import org.tenshu.sync.common.debounceSyncActivity
import org.tenshu.sync.common.evaluateSyncActivity

/**
 * What the "Syncing…" indicator should show, from whether a pull is in progress
 * (`suite-sync-status.md` SS-2) — the iOS indicator's rule ([evaluateSyncActivity]) and timing
 * ([debounceSyncActivity]: shown only after activity lasts, held long enough to read, and not
 * blinked between two ticks of one catch-up).
 *
 * ⚠ Pass a `remember`ed stream, or the timing restarts on every recomposition. For a Compose app
 * that is `client.syncService.anyCatchUpHeartbeat()` — Control's pull, through its heartbeat, so a
 * `true` left behind by a killed Control is discounted rather than shown for ever.
 */
@Composable
fun rememberSyncActivity(
    catchingUp: Flow<Boolean>,
    timing: SyncActivityTiming = SyncActivityTiming.Default,
): SyncActivity {
    val smoothed = remember(catchingUp, timing) {
        catchingUp.map { evaluateSyncActivity(catchingUp = it) }.debounceSyncActivity(timing)
    }
    val activity by smoothed.collectAsState(initial = SyncActivity.Idle)
    return activity
}

/**
 * The small "Syncing…" capsule, bottom-centre over the screen (`suite-sync-status.md` SS-2) — the
 * Compose twin of iOS's `SyncActivityCapsule` (`ios-sync-activity-indicator.md` §4).
 *
 * ⚠⚠ **An overlay, never a sibling** (mount it in `AdaptiveNavScaffold`'s `overlay`): a strip in the
 * column would shift the whole screen each time activity starts or ends. It may cover a few pixels
 * of the last row. ⚠ Not tappable: drawn with a background, not a `Surface`, which would swallow
 * taps meant for the row underneath.
 *
 * ⚠⚠ **A problem hides it** ([hidden]): a problem strip says more, and the two at once is noise. The
 * caller passes whether its own strip is showing, so the capsule can only be hidden by a strip the
 * user can actually see.
 */
@Composable
fun BoxScope.SyncActivityCapsule(
    activity: SyncActivity,
    hidden: Boolean,
    modifier: Modifier = Modifier,
) {
    // ⚠ The last active state is what draws while the capsule fades out: going idle starts the fade,
    // and drawing `Idle` itself would shrink the pill to a bare glyph for the length of it.
    var lastActive by remember { mutableStateOf<SyncActivity>(SyncActivity.CatchingUp) }
    SideEffect { if (activity != SyncActivity.Idle) lastActive = activity }
    val drawn = if (activity != SyncActivity.Idle) activity else lastActive
    val text = when (drawn) {
        SyncActivity.Idle -> null
        SyncActivity.CatchingUp -> stringResource(Res.string.sync_activity_catching_up)
        is SyncActivity.Uploading -> drawn.count?.let {
            stringResource(Res.string.sync_activity_uploading_count, it.completed, it.total)
        } ?: stringResource(Res.string.sync_activity_uploading)
    }
    val a11y = if (drawn == SyncActivity.CatchingUp) {
        stringResource(Res.string.sync_activity_catching_up_a11y)
    } else {
        text
    }
    AnimatedVisibility(
        visible = activity != SyncActivity.Idle && !hidden,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .testTag(SYNC_ACTIVITY_CAPSULE_TAG)
                .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 8.dp)
                // One element for a screen reader, announced politely, never per change.
                .clearAndSetSemantics {
                    contentDescription = a11y.orEmpty()
                    liveRegion = LiveRegionMode.Polite
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A static glyph, not a spinner: motion here would compete with the screen's own.
            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = text.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 1,
            )
        }
    }
}

/** Test handle for [SyncActivityCapsule]. */
const val SYNC_ACTIVITY_CAPSULE_TAG = "syncActivityCapsule"
