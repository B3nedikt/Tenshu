package app.tenshu.ui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * A full-width, `errorContainer`-toned inline banner for a **broken** app state — the shared
 * form behind each app's "Can't reach Tenshu Control" strip (fit-app.md §9.14; Fit, Timeline
 * and Maps all render one above their nav host). Deliberately stronger than an informational
 * `secondaryContainer` banner (Drive's Offline = "changes queued, app usable"): this tone means
 * "nothing can load or save". The caller supplies the localised [text]; keep the banner
 * action-free when recovery is automatic.
 *
 * ⚠⚠ **[onAction] is nullable and the button is omitted when it is `null`** — the same contract
 * [app.tenshu.ui.storage.RealmStorageBanner] draws, for the same reason: a route to Control does
 * not exist on every platform, and a button that does nothing is worse than no button (ADR 0025).
 * ⚠ Offer it only where *acting* is what recovers the state. A transport outage that clears itself
 * wants no button; "Control is installed but asleep" wants one; a schema skew wants neither, since
 * opening Control does not update it.
 */
@Composable
fun ErrorBanner(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.CloudOff,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
