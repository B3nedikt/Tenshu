package app.tenshu.ui.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.ui.layout.WindowWidthSizeClass
import app.tenshu.ui.preview.PreviewBox
import app.tenshu.ui.theme.spacing
import androidx.compose.ui.tooling.preview.Preview

/**
 * App-styled empty state for lists and screens that have no content yet.
 *
 * Shows an optional decorative [icon], a required [title], optional
 * explanatory [body] copy, and an optional call-to-action button. Designed
 * to sit centred inside whatever container it is placed in.
 *
 * ### When to use
 *
 * Whenever a screen or list has loaded successfully but has no items to
 * render — a brand-new Drive folder, an "all caught up" inbox, a search
 * with no results. Not for errors (use an error state) or loading (use
 * [app.tenshu.ui.feedback.AppLoadingIndicator]).
 *
 * ### Variants
 *
 * - **Icon + title** — minimum. Good for inline empty slots.
 * - **Icon + title + body** — adds a short explanation of why it's empty.
 * - **+ action** — adds a `TextButton`, typically an "Add" or
 *   "Try again with a broader filter" affordance.
 *
 * ### Accessibility
 *
 * The [icon] is decorative by default, so [iconContentDescription] is
 * nullable. Supply a description only when the icon communicates something
 * the [title] doesn't. The text is centre-aligned but reads as a single
 * block for screen readers since each line is its own `Text`.
 *
 * @param title                     main headline. Required.
 * @param modifier                  applied to the outer [Box]; typical
 *                                  callers pass `Modifier.appContentWidth()`
 *                                  to constrain width on desktop.
 * @param icon                      optional decorative icon.
 * @param iconContentDescription    a11y description for [icon].
 * @param body                      optional supporting sentence.
 * @param actionLabel               optional call-to-action label.
 * @param onAction                  handler for the call-to-action.
 */
@Composable
fun AppEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

@Preview
@Composable
private fun AppEmptyStateMinimalPreview() = PreviewBox {
    AppEmptyState(title = "All caught up")
}

@Preview
@Composable
private fun AppEmptyStateFullPreview() = PreviewBox {
    AppEmptyState(
        icon = Icons.Default.FolderOpen,
        iconContentDescription = null,
        title = "No files yet",
        body = "Drag files here or tap + below to add your first one.",
        actionLabel = "Add file",
        onAction = {},
    )
}

@Preview
@Composable
private fun AppEmptyStateDarkPreview() = PreviewBox(useDarkTheme = true) {
    AppEmptyState(
        icon = Icons.Default.History,
        title = "No recent events",
        body = "Sync activity from your paired devices will show up here.",
    )
}

@Preview
@Composable
private fun AppEmptyStateExpandedPreview() = PreviewBox(
    windowWidthSizeClass = WindowWidthSizeClass.Expanded,
) {
    AppEmptyState(
        icon = Icons.Default.FolderOpen,
        title = "No files yet",
        body = "Drag files here or tap + below to add your first one.",
    )
}
