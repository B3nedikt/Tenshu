package app.tenshu.contacts.ui.screens.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import app.tenshu.contacts.ui.error.contactsLabel
import app.tenshu.contacts.shared.resources.Res
import app.tenshu.contacts.shared.resources.contacts_add_confirm
import app.tenshu.contacts.shared.resources.contacts_add_supporting
import app.tenshu.contacts.shared.resources.contacts_add_title
import app.tenshu.contacts.shared.resources.contacts_confirm_banner
import app.tenshu.contacts.shared.resources.contacts_confirm_banner_action
import app.tenshu.contacts.shared.resources.contacts_confirm_unnamed
import app.tenshu.contacts.shared.resources.contacts_list_empty_body
import app.tenshu.contacts.shared.resources.contacts_list_empty_title
import app.tenshu.contacts.shared.resources.contacts_merge_banner
import app.tenshu.contacts.shared.resources.contacts_merge_banner_action
import app.tenshu.contacts.ui.components.AddContactFab
import app.tenshu.contacts.ui.components.ContactRow
import app.tenshu.contacts.ui.components.ContactSearchBar
import app.tenshu.contacts.ui.components.NameDialog
import app.tenshu.ui.chrome.AppEmptyState
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.chrome.appFabInset
import app.tenshu.ui.feedback.AppLoadingIndicator
import app.tenshu.ui.feedback.ErrorBanner
import app.tenshu.ui.input.AppShortcuts
import app.tenshu.ui.input.keyboardShortcuts
import app.tenshu.ui.layout.appContentWidth
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource

/**
 * The contact list (`docs/proposals/contacts-app.md` D3): **one** list holding both
 * crypto-connected and import-only contacts, told apart by the row badge rather than split
 * into two sections — users think in terms of people, not key material.
 *
 * The chrome is Drive's, over the same `:ui-core` components (§9.6): `AppSearchBar` as the
 * top strip with its results in the expanded surface, and a FAB bottom-end holding the two
 * ways of acquiring a contact. What this replaced — a title bar with two icon-button actions
 * and a text field filtering the list underneath — worked, but it was a second idiom for
 * surfaces the design system had already settled.
 */
@Composable
fun ContactListScreen(
    viewModel: ContactListViewModel,
    onOpenContact: (String) -> Unit,
    onConfirmInvite: (String) -> Unit = {},
    onImport: () -> Unit = {},
    onReviewDuplicates: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val searchFocusRequester = remember { FocusRequester() }

    if (state.addingContact) {
        // D5: manual entry is not a third acquisition path — it is a petname and no key, so
        // the dialog asks for exactly one thing.
        NameDialog(
            title = stringResource(Res.string.contacts_add_title),
            supporting = stringResource(Res.string.contacts_add_supporting),
            value = state.draftName,
            confirmLabel = stringResource(Res.string.contacts_add_confirm),
            canConfirm = state.canSaveNew,
            onValueChange = viewModel::onDraftNameChanged,
            onConfirm = viewModel::onAddConfirmed,
            onDismiss = viewModel::onAddDismissed,
        )
    }

    Box(
        // Ctrl+F / Cmd+F, from `:ui-core`'s shortcut catalogue — the same binding Drive's
        // Files screen carries, so the gesture transfers between apps.
        modifier = Modifier.fillMaxSize().keyboardShortcuts(
            AppShortcuts.focusSearch {
                viewModel.onSearchActiveChange(true)
                runCatching { searchFocusRequester.requestFocus() }
            },
        ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.appContentWidth().fillMaxSize()) {
            ContactSearchBar(
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                active = state.searchActive,
                onActiveChange = viewModel::onSearchActiveChange,
                results = state.searchResults,
                isEmptySearch = state.isEmptySearch,
                onResultClicked = { personId ->
                    // Collapse as we hand off, so the full-screen results do not stay on top
                    // of the contact the user just opened.
                    viewModel.onSearchActiveChange(false)
                    onOpenContact(personId)
                },
                inputFocusRequester = searchFocusRequester,
            )

            state.errorCopyKey?.let { key -> ErrorBanner(key.contactsLabel()) }

            // Somebody accepted an invitation and is waiting to be saved. This sits above the
            // list — including above the empty state, since the very first contact often
            // arrives exactly this way, and a "No contacts yet" screen with a pending
            // confirmation hidden below it would be actively misleading.
            state.pendingConfirmations.forEach { confirmation ->
                AppListItem(
                    headline = stringResource(
                        Res.string.contacts_confirm_banner,
                        confirmation.claimedName.ifBlank {
                            stringResource(Res.string.contacts_confirm_unnamed)
                        },
                    ),
                    supportingText = stringResource(Res.string.contacts_confirm_banner_action),
                    onClick = { onConfirmInvite(confirmation.inviteId) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ListItemDefaults.colors(
                        // Accented, not ghosted: this is the only row demanding action, and
                        // `drive-manage-access-ux.md` §10.2's ramp says it must pop.
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    leadingContent = {
                        Icon(Icons.Default.PersonAdd, contentDescription = null)
                    },
                )
            }

            // Ph9: a quiet, dismissable-by-fixing prompt rather than a tab. It is absent for
            // almost everyone almost always, and it sits BELOW the invite confirmations —
            // somebody waiting to be saved is the more urgent of the two.
            if (state.duplicateCount > 0) {
                AppListItem(
                    headline = stringResource(
                        Res.string.contacts_merge_banner,
                        state.duplicateCount,
                    ),
                    supportingText = stringResource(Res.string.contacts_merge_banner_action),
                    onClick = onReviewDuplicates,
                    modifier = Modifier.fillMaxWidth(),
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null) },
                )
            }

            when {
                !state.loaded -> AppLoadingIndicator(Modifier.padding(MaterialTheme.spacing.xxl))

                state.isEmpty -> AppEmptyState(
                    title = stringResource(Res.string.contacts_list_empty_title),
                    body = stringResource(Res.string.contacts_list_empty_body),
                    icon = Icons.Outlined.PersonOutline,
                    // Now that manual add is real, the empty state can do something about
                    // itself rather than only describing the situation.
                    actionLabel = stringResource(Res.string.contacts_add_title),
                    onAction = viewModel::onAddClicked,
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.contacts, key = { it.personId }) { contact ->
                        ContactRow(contact = contact, onClick = { onOpenContact(contact.personId) })
                    }
                }
            }
        }

        AddContactFab(
            modifier = Modifier.align(Alignment.BottomEnd).appFabInset(),
            sheetVisible = state.actionsSheetVisible,
            onClicked = viewModel::onAddActionsClicked,
            onDismissed = viewModel::onAddActionsDismissed,
            onAddByHand = viewModel::onAddClicked,
            onImport = {
                viewModel.onAddActionsDismissed()
                onImport()
            },
        )
    }
}
