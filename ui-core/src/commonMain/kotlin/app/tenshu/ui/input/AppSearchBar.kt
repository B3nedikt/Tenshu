package app.tenshu.ui.input

import androidx.compose.foundation.layout.ColumnScope
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
 * Collapsed, the bar renders with outer padding so it looks docked
 * inside a scaffold; expanded, the bar pins edge-to-edge to host its
 * dropdown. That transition is handled here so callers don't have to
 * pick between two layouts.
 *
 * ### Keyboard
 *
 * Pass an [inputFocusRequester] to focus the input programmatically —
 * Drive uses this to wire `Ctrl+F` to the search bar. On desktop,
 * Escape closes the expanded state (handled by M3 SearchBar).
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
    placeholder: String = "Search",
    leadingIcon: ImageVector = Icons.Default.Search,
    leadingIconContentDescription: String? = "Search Icon",
    trailingIcon: (@Composable () -> Unit)? = null,
    inputFocusRequester: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
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
