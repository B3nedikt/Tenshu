package app.tenshu.ui.chrome

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import app.tenshu.ui.preview.PreviewBox
import androidx.compose.ui.tooling.preview.Preview

/**
 * App-level top bar. Thin wrapper over Material 3 [TopAppBar] that enforces
 * a consistent title + optional back/nav + optional actions pattern across
 * Drive and Control.
 *
 * ### When to use
 *
 * At the top of every screen that has chrome. For a screen that is part of a
 * root-level destination, leave [onBack] and [navigationIcon] `null` — the
 * bar renders with only the title. For a pushed screen, pass [onBack] and a
 * chevron will be rendered for you.
 *
 * ### Variants
 *
 * - **Title only** — omit [onBack], [navigationIcon], and [actions].
 * - **Back + title** — pass [onBack].
 * - **Custom nav icon** — pass [navigationIcon] (takes precedence over [onBack]).
 * - **Actions** — pass [actions], an M3 `RowScope` you can fill with `IconButton`s.
 *
 * ### Accessibility
 *
 * The auto-generated back button has `contentDescription = "Back"`. If you
 * supply a custom [navigationIcon] or [actions], make sure each icon has a
 * `contentDescription` — `null` is only appropriate for icons that are
 * purely decorative next to visible text.
 *
 * @param title             visible title text, rendered with the M3
 *                          title style from the active [TopAppBarColors].
 * @param onBack            tap handler for the auto-generated back chevron.
 *                          When null and [navigationIcon] is null, no
 *                          navigation icon is drawn.
 * @param navigationIcon    custom navigation slot. Overrides [onBack].
 * @param actions           trailing action row, typically one or more
 *                          `IconButton`s.
 * @param colors            override palette; defaults to
 *                          [TopAppBarDefaults.topAppBarColors].
 * @param scrollBehavior    opt-in scroll response. See below.
 *
 * ### Scroll behaviour
 *
 * M3's mechanism for separating a bar from content scrolled beneath it is the
 * **container colour**, not a divider: `AppBarTokens` defines
 * `ContainerColor = Surface` and `OnScrollContainerColor = SurfaceContainer`.
 * That shift only ever fires if a [TopAppBarScrollBehavior] is wired up — with
 * [scrollBehavior] left null the bar is inert and the boundary is never drawn.
 *
 * Pass [TopAppBarDefaults.pinnedScrollBehavior] and hoist the connection onto
 * the hosting scaffold:
 *
 * ```
 * val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
 * Scaffold(
 *     modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
 *     topBar = { AppTopAppBar(title = "Files", scrollBehavior = scrollBehavior) },
 * ) { ... }
 * ```
 *
 * ⚠ **Pinned, not `enterAlways`.** `enterAlwaysScrollBehavior` slides the bar
 * off on scroll-down, which takes the title with it; on a tab destination that
 * also carries bottom nav it is a lot of chrome in motion. Pinned is a no-op at
 * rest — the container is `Surface`, exactly as it renders today — so adopting
 * it changes nothing until the user actually scrolls.
 *
 * ⚠ `exitUntilCollapsedScrollBehavior` **does** work on this small bar: there is
 * no headline to collapse, so it slides the whole 64.dp row away with the content
 * and brings it back only once the content is at the top again — a page that
 * scrolls as one. Fit's Home uses it (fit-app.md §18.16); pass
 * `snapAnimationSpec = null` so a fling never finishes the collapse on its own.
 *
 * ⚠⚠ **Every caller now needs `@OptIn(ExperimentalMaterial3Api::class)`, even one
 * that passes no [scrollBehavior].** [TopAppBarScrollBehavior] is experimental, and
 * naming it in this signature is enough to propagate the requirement — the opt-in
 * on this declaration covers the body, not the callers. That is a real ergonomic
 * cost this wrapper used to absorb ([TopAppBarColors] is not experimental, so
 * before this parameter existed nothing leaked). It was accepted rather than
 * worked around: the alternative — a second scroll-aware composable beside this
 * one — splits the "one bar for the whole platform" invariant that is the only
 * reason this file exists, to dodge one annotation. Most call sites already carry
 * the opt-in because they host a `Scaffold`; the six that did not were annotated
 * when this parameter landed. Revisit when the M3 bar APIs go stable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopAppBar(
    title: String,
    onBack: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = { Text(text = title) },
        navigationIcon = {
            when {
                navigationIcon != null -> navigationIcon()
                onBack != null -> IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        },
        actions = { actions?.invoke(this) },
        colors = colors,
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun AppTopAppBarTitleOnlyPreview() = PreviewBox {
    AppTopAppBar(title = "Files")
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun AppTopAppBarWithBackPreview() = PreviewBox {
    AppTopAppBar(title = "Settings", onBack = {})
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun AppTopAppBarWithActionsPreview() = PreviewBox {
    AppTopAppBar(
        title = "Photos",
        onBack = {},
        actions = {
            IconButton(onClick = {}) {
                Icon(Icons.Default.Search, contentDescription = "Search")
            }
            IconButton(onClick = {}) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun AppTopAppBarDarkPreview() = PreviewBox(useDarkTheme = true) {
    AppTopAppBar(title = "Files", onBack = {})
}
