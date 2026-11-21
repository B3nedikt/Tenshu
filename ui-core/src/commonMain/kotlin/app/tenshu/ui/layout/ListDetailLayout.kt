package app.tenshu.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.ui.preview.PreviewBox
import app.tenshu.ui.theme.spacing

/**
 * The width of the list pane in [AppListDetailLayout]: wide enough for an avatar, a name, a
 * trailing badge and a search bar, narrow enough that the detail pane keeps the majority of a
 * 1280 dp window. Material 3's list-detail canonical layout puts its fixed list pane at
 * 360–412 dp; this is the low end, because the rail and the window margins already take
 * ~150 dp before either pane starts.
 */
val AppListPaneWidth: Dp = 360.dp

/**
 * Whether a window of this class shows a list and the selected item side by side.
 *
 * Expanded **and above** (`>=`, see the note on [WindowWidthSizeClass]): below 840 dp a fixed
 * [AppListPaneWidth] list would leave the detail pane narrower than the list itself, so
 * Compact and Medium keep the one-screen-at-a-time navigation.
 */
val WindowWidthSizeClass.showsListDetail: Boolean
    get() = this >= WindowWidthSizeClass.Expanded

/**
 * A fixed-width list pane against the leading edge, a divider, and the selected item filling
 * the rest of the window — the layout desktop contact, mail and notes apps share.
 *
 * ### When to use
 *
 * A list whose rows open a detail screen, at a width where [showsListDetail] is true. The
 * layout is not adaptive by itself: it always draws both panes, and the **caller** decides
 * when to use it (read [LocalWindowWidthSizeClass]) and what the detail pane shows with
 * nothing selected. Below the breakpoint, render the list alone and navigate to the detail
 * screen as before.
 *
 * Do not wrap either pane in `Modifier.appContentWidth()` from outside: the list's width is
 * fixed here, and a detail screen that caps its own body keeps doing so inside its pane.
 *
 * ### Variants
 *
 * - Default — [AppListPaneWidth] list, the detail filling the remainder.
 * - [listWidth] — a wider list for rows that carry more text.
 *
 * ### Accessibility
 *
 * Focus and reading order run list first, then detail, matching the visual order. The
 * divider is decorative and has no semantics. The caller should mark the selected row as
 * selected (`Modifier.semantics { selected = true }`) so a screen reader can tell which item
 * the detail pane belongs to.
 *
 * @param list      the list pane; it is given exactly [listWidth] and the full height.
 * @param detail    the detail pane; it is given the remaining width and the full height.
 * @param listWidth the list pane's fixed width.
 */
@Composable
fun AppListDetailLayout(
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    listWidth: Dp = AppListPaneWidth,
) {
    Row(modifier.fillMaxSize()) {
        Box(Modifier.width(listWidth).fillMaxHeight()) { list() }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
    }
}

@Composable
private fun ListDetailDemo() {
    AppListDetailLayout(
        modifier = Modifier.height(320.dp),
        list = {
            Column(Modifier.fillMaxSize()) {
                listOf("Ada", "Grace", "Linus").forEachIndexed { index, name ->
                    Text(
                        text = name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (index == 0) {
                                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                } else {
                                    Modifier
                                }
                            )
                            .padding(MaterialTheme.spacing.m),
                    )
                }
            }
        },
        detail = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Ada", style = MaterialTheme.typography.headlineSmall)
            }
        },
    )
}

@Preview
@Composable
private fun AppListDetailLayoutPreview() = PreviewBox(
    windowWidthSizeClass = WindowWidthSizeClass.Expanded,
) {
    ListDetailDemo()
}

@Preview
@Composable
private fun AppListDetailLayoutDarkPreview() = PreviewBox(
    useDarkTheme = true,
    windowWidthSizeClass = WindowWidthSizeClass.Expanded,
) {
    ListDetailDemo()
}
