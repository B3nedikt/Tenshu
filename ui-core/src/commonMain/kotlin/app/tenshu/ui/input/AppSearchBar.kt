package app.tenshu.ui.input

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import app.tenshu.ui.resources.Res
import app.tenshu.ui.resources.search_icon_description
import app.tenshu.ui.resources.search_placeholder
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.tenshu.ui.layout.LocalWindowWidthSizeClass
import app.tenshu.ui.layout.WindowWidthSizeClass
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import app.tenshu.ui.preview.PreviewBox
import app.tenshu.ui.theme.spacing
import androidx.compose.ui.tooling.preview.Preview

/**
 * App-styled search bar wrapping Material 3 [SearchBar] +
 * [SearchBarDefaults.InputField].
 *
 * ### When to use
 *
 * For the primary search surface of a screen or module. If a feature
 * wants a richer search experience (filters, scoped suggestions,
 * recents) it composes them inside [content], which is rendered in the
 * expanded dropdown.
 *
 * ### Layout behaviour
 *
 * On a phone ([WindowWidthSizeClass.Compact]) the bar renders with outer
 * padding while collapsed and expands to Material's full-screen search
 * view. At Medium and wider it stays docked — same position and padding
 * either way — and [content] opens in a panel under the field, over the
 * screen (see `DockedAppSearchBar`). That choice is made here so callers
 * don't have to pick between two layouts.
 *
 * ### Keyboard
 *
 * Pass an [inputFocusRequester] to focus the input programmatically —
 * Drive uses this to wire `Ctrl+F` to the search bar. Escape closes the
 * expanded state.
 *
 * ### Accessibility
 *
 * The [placeholder] is used as the field's accessible name when [query]
 * is empty; otherwise screen readers read the current query. The
 * [leadingIcon] is announced with [leadingIconContentDescription] —
 * leave it descriptive ("Search") so assistive tech explains the
 * surface.
 *
 * @param query                         current query string.
 * @param onQueryChange                 callback for every edit.
 * @param active                        whether the bar is expanded.
 * @param onActiveChange                callback when expansion flips.
 * @param onSearch                      fires when the user commits the
 *                                      query (keyboard Enter). The
 *                                      wrapper first collapses, then
 *                                      invokes this.
 * @param enabled                       when false, the bar renders
 *                                      as disabled.
 * @param placeholder                   placeholder copy.
 * @param leadingIcon                   leading icon; defaults to a
 *                                      magnifying glass.
 * @param leadingIconContentDescription a11y description for the leading
 *                                      icon; `null` only for purely
 *                                      decorative icons.
 * @param trailingIcon                  trailing slot — typical use is
 *                                      a clear button while active or
 *                                      a settings icon while collapsed.
 * @param inputFocusRequester           exposes focus to callers.
 * @param content                       dropdown body (suggestions, recents,
 *                                      results) rendered while expanded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = stringResource(Res.string.search_placeholder),
    leadingIcon: ImageVector = Icons.Default.Search,
    leadingIconContentDescription: String? = stringResource(Res.string.search_icon_description),
    trailingIcon: (@Composable () -> Unit)? = null,
    inputFocusRequester: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val docked = LocalWindowWidthSizeClass.current >= WindowWidthSizeClass.Medium
    if (docked) {
        DockedAppSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            active = active,
            onActiveChange = onActiveChange,
            onSearch = onSearch,
            modifier = modifier,
            enabled = enabled,
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            leadingIconContentDescription = leadingIconContentDescription,
            trailingIcon = trailingIcon,
            inputFocusRequester = inputFocusRequester,
            content = content,
        )
        return
    }
    SearchBar(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (!active) Modifier.padding(MaterialTheme.spacing.l) else Modifier
            ),
        inputField = {
            SearchBarDefaults.InputField(
                modifier = if (inputFocusRequester != null) {
                    Modifier.focusRequester(inputFocusRequester)
                } else {
                    Modifier
                },
                query = query,
                onQueryChange = onQueryChange,
                onSearch = {
                    onActiveChange(false)
                    onSearch(query)
                },
                expanded = active,
                onExpandedChange = onActiveChange,
                enabled = enabled,
                placeholder = { Text(text = placeholder) },
                leadingIcon = {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = leadingIconContentDescription
                    )
                },
                trailingIcon = trailingIcon
            )
        },
        expanded = active,
        onExpandedChange = onActiveChange,
        content = content
    )
}

/**
 * The [AppSearchBar] at [WindowWidthSizeClass.Medium] and wider: the field stays where it is, with
 * the same padding collapsed and expanded, and [content] opens in a panel just under it, over the
 * screen rather than instead of it — Material's docked search, which it recommends for large
 * screens; the full-screen search view is the phone pattern.
 *
 * ⚠ The panel is a [Popup], not a sibling in the layout: a docked bar that grew in place would push
 * the host's listing down by the height of its results on every keystroke. Being a popup is also
 * what lets a click anywhere else close it, as a desktop dropdown does.
 *
 * ⚠ The popup is **not focusable**, so the field keeps the keyboard while results are open. That is
 * why Escape is handled on the field itself, and why typing into a collapsed (but still focused)
 * field opens the panel again.
 *
 * ⚠ Enter does **not** collapse here, unlike the phone bar: the results are the point of pressing
 * it, and a host may clear its query on collapse (Drive does).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DockedAppSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    placeholder: String,
    leadingIcon: ImageVector,
    leadingIconContentDescription: String?,
    trailingIcon: (@Composable () -> Unit)?,
    inputFocusRequester: FocusRequester?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    // ⚠ The popup holds only the results, so a click into the FIELD — placing the caret, selecting
    // text — is "outside" to it, and dismissing on it would close the search mid-edit (Drive clears
    // its query on collapse). Field and panel are one region: a press on the field marks itself
    // for a couple of frames, and a dismissal waits a frame to see whether it was that press. The
    // wait covers either delivery order between the popup's outside-click and the field.
    var fieldPressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val gap = MaterialTheme.spacing.xs
    Box(
        modifier = modifier
            .fillMaxWidth()
            // ⚠⚠ The status-bar / cutout inset the phone bar's M3 `SearchBar` consumes: screens put
            // this bar first and rely on it to own the top inset (`ui-agent.md` "Screen chrome owns
            // the top inset"). A tablet drawn edge-to-edge is Medium+ and lands here.
            .windowInsetsPadding(SearchBarDefaults.windowInsets)
            .padding(MaterialTheme.spacing.l)
    ) {
        SearchBarDefaults.InputField(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { fieldSize = it }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) {
                                fieldPressed = true
                                scope.launch {
                                    withFrameNanos { }
                                    withFrameNanos { }
                                    fieldPressed = false
                                }
                            }
                        }
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (active && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onActiveChange(false)
                        true
                    } else {
                        false
                    }
                }
                .then(
                    if (inputFocusRequester != null) {
                        Modifier.focusRequester(inputFocusRequester)
                    } else {
                        Modifier
                    }
                ),
            query = query,
            onQueryChange = {
                onQueryChange(it)
                if (!active) onActiveChange(true)
            },
            onSearch = { onSearch(query) },
            expanded = active,
            onExpandedChange = onActiveChange,
            enabled = enabled,
            placeholder = { Text(text = placeholder) },
            leadingIcon = {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = leadingIconContentDescription
                )
            },
            trailingIcon = trailingIcon,
        )

        // ⚠ System Back must close the open results, as it does the phone bar. The popup is not
        // focusable, so it never sees Back itself, and Escape on the field is desktop-only.
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = active,
            onBackCompleted = { onActiveChange(false) },
        )

        if (active && fieldSize != IntSize.Zero) {
            val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
            Popup(
                offset = IntOffset(0, fieldSize.height + with(density) { gap.roundToPx() }),
                onDismissRequest = {
                    scope.launch {
                        withFrameNanos { }
                        if (!fieldPressed) onActiveChange(false)
                    }
                },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
            ) {
                Surface(
                    modifier = Modifier
                        .width(with(density) { fieldSize.width.toDp() })
                        .heightIn(max = windowHeight * DockedPanelMaxHeightRatio),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = SearchBarDefaults.ShadowElevation + 3.dp,
                ) {
                    Column(Modifier.padding(vertical = MaterialTheme.spacing.xs), content = content)
                }
            }
        }
    }
}

/** Material's bound for a docked search's results: two thirds of the window. */
private const val DockedPanelMaxHeightRatio = 2f / 3f

@Preview
@Composable
private fun AppSearchBarCollapsedPreview() = PreviewBox {
    AppSearchBar(
        query = "",
        onQueryChange = {},
        active = false,
        onActiveChange = {},
        onSearch = {},
        placeholder = "Search files",
        trailingIcon = {
            IconButton(onClick = {}) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        },
    )
}

@Preview
@Composable
private fun AppSearchBarWithQueryPreview() = PreviewBox {
    AppSearchBar(
        query = "design system",
        onQueryChange = {},
        active = false,
        onActiveChange = {},
        onSearch = {},
        placeholder = "Search files",
        trailingIcon = {
            Icon(Icons.Default.Close, contentDescription = "Clear")
        },
    )
}

@Preview
@Composable
private fun AppSearchBarDarkPreview() = PreviewBox(useDarkTheme = true) {
    AppSearchBar(
        query = "",
        onQueryChange = {},
        active = false,
        onActiveChange = {},
        onSearch = {},
        placeholder = "Search files",
    )
}
