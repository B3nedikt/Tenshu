package app.tenshu.contacts.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.shared.resources.Res
import app.tenshu.contacts.shared.resources.contacts_search_clear
import app.tenshu.contacts.shared.resources.contacts_search_empty_title
import app.tenshu.contacts.shared.resources.contacts_search_placeholder
import app.tenshu.ui.chrome.AppEmptyState
import app.tenshu.ui.input.AppSearchBar
import org.jetbrains.compose.resources.stringResource

/**
 * Contacts' top strip — `:ui-core`'s [AppSearchBar], the same component and the same shape
 * Drive's `FilesSearchBar` uses.
 *
 * It replaces what Ph1 hand-rolled: an `AppTopAppBar` with a separate `AppTextField` beneath
 * it, filtering the list in place. Two things were wrong with that. It re-implemented a
 * component the design system already ships — the point of `:ui-core` is that a search field
 * in Contacts and a search field in Drive are the *same* surface — and it spent a title bar
 * on a word the navigation item already says.
 *
 * ⚠ The behavioural consequence is deliberate: results live in the expanded surface and the
 * list behind stays whole, so searching is a distinct gesture with somewhere to go back to,
 * rather than a filter that silently shrinks the address book.
 */
@Composable
fun ContactSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    results: List<Contact>,
    isEmptySearch: Boolean,
    onResultClicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    inputFocusRequester: FocusRequester? = null,
) {
    AppSearchBar(
        modifier = modifier,
        query = query,
        onQueryChange = onQueryChange,
        active = active,
        onActiveChange = onActiveChange,
        // Filtering is local and instant, so committing a query has nothing left to do.
        onSearch = {},
        placeholder = stringResource(Res.string.contacts_search_placeholder),
        inputFocusRequester = inputFocusRequester,
        trailingIcon = if (!active && query.isEmpty()) {
            null
        } else {
            {
                Icon(
                    // One control, two jobs, in the order the user wants them: clear a query
                    // that exists, otherwise leave search. Drive's search bar behaves the same.
                    modifier = Modifier.clickable {
                        if (query.isNotEmpty()) onQueryChange("") else onActiveChange(false)
                    },
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.contacts_search_clear),
                )
            }
        },
    ) {
        if (isEmptySearch) {
            // An unmatched query and an empty address book are different situations and get
            // different copy — collapsing them is how "no results" ends up telling someone
            // with 200 contacts that they have none.
            AppEmptyState(
                title = stringResource(Res.string.contacts_search_empty_title),
                icon = Icons.Default.Search,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(results, key = { it.personId }) { contact ->
                    ContactRow(contact = contact, onClick = { onResultClicked(contact.personId) })
                }
            }
        }
    }
}
