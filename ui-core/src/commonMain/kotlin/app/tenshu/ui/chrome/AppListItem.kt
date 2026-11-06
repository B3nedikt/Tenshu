package app.tenshu.ui.chrome

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tenshu.ui.preview.PreviewBox
import androidx.compose.ui.tooling.preview.Preview

/**
 * App-styled list row wrapping Material 3 [ListItem] inside a [Surface] so
 * click and long-click ripples stay consistent across Drive and Control.
 *
 * ### When to use
 *
 * For rows in a `LazyColumn`, settings list, or any vertical collection
 * where each row is a selectable or informational unit. For a row whose
 * headline is a composition (icons next to text, badges, etc.), pass
 * [headlineContentOverride] instead of [headline].
 *
 * ### Variants
 *
 * - **Plain** — `headline` + optional `supportingText`.
 * - **With leading** — e.g., an avatar, file icon, or checkbox.
 * - **With trailing** — e.g., a chevron, switch, or loading indicator.
 * - **Non-interactive** — pass `onClick = null`; the row does not ripple.
 *
 * ### Accessibility
 *
 * The entire row is the tap target when [onClick] is non-null. Keep
 * trailing controls (switches, checkboxes) visually grouped, but let the
 * row handle the click so users can target the whole surface.
 *
 * @param headline                      required headline string. Ignored
 *                                      when [headlineContentOverride] is set.
 * @param supportingText                optional second line.
 * @param onClick                       tap handler. `null` means the row
 *                                      is not interactive.
 * @param onLongClick                   optional long-press handler. When
 *                                      non-null, forces interactive mode.
 * @param leadingContent                leading slot (icons, avatars).
 * @param trailingContent               trailing slot (chevrons, toggles).
 * @param headlineContentOverride       replaces [headline] when the title
 *                                      needs richer composition than a
 *                                      single string.
 * @param supportingContentOverride     replaces [supportingText] likewise.
 * @param colors                        palette override.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppListItem(
    headline: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    headlineContentOverride: (@Composable () -> Unit)? = null,
    supportingContentOverride: (@Composable () -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
) {
    val clickableModifier = when {
        onClick != null || onLongClick != null -> Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick
        )

        else -> Modifier
    }

    Surface(modifier = modifier.then(clickableModifier)) {
        ListItem(
            headlineContent = headlineContentOverride ?: { Text(text = headline) },
            supportingContent = when {
                supportingContentOverride != null -> supportingContentOverride
                supportingText != null -> {
                    { Text(text = supportingText) }
                }

                else -> null
            },
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            colors = colors
        )
    }
}

@Preview
@Composable
private fun AppListItemPlainPreview() = PreviewBox {
    AppListItem(
        headline = "Design system plan",
        supportingText = "Updated 2 hours ago",
        onClick = {},
    )
}

@Preview
@Composable
private fun AppListItemLeadingTrailingPreview() = PreviewBox {
    AppListItem(
        headline = "Projects",
        supportingText = "12 items",
        onClick = {},
        leadingContent = {
            Icon(Icons.Default.Folder, contentDescription = null)
        },
        trailingContent = {
            Icon(Icons.Default.MoreVert, contentDescription = "More")
        }
    )
}

@Preview
@Composable
private fun AppListItemNonInteractivePreview() = PreviewBox {
    AppListItem(
        headline = "Auto-sync",
        supportingText = "Sync changes across devices automatically",
        onClick = null,
        trailingContent = {
            Switch(checked = true, onCheckedChange = {})
        }
    )
}

@Preview
@Composable
private fun AppListItemDarkPreview() = PreviewBox(useDarkTheme = true) {
    AppListItem(
        headline = "Design system plan",
        supportingText = "Updated 2 hours ago",
        onClick = {},
    )
}
