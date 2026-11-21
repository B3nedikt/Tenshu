package app.tenshu.contacts.ui.components

import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.TrustState
import app.tenshu.ui.chrome.AppListItem

/**
 * One person, wherever they are listed — the main list and the expanded search surface both
 * render this, so a search result behaves and reads exactly like a list row (Drive's rule for
 * its own search results).
 *
 * [selected] is the list-detail layout's highlight: the row whose contact the detail pane is
 * showing. It is a quiet surface tint, deliberately not `secondaryContainer` — that is the
 * pending-confirmation row's colour, the one row on the list that asks for action.
 *
 * ⚠ `null`, not `false`, wherever no row *can* be selected (the narrow list, search results):
 * Android reports `Selected` on a non-tab node as a checked state, so a `false` there would have
 * TalkBack read "not selected" on every row of a list that has no selection at all.
 */
@Composable
fun ContactRow(contact: Contact, onClick: () -> Unit, selected: Boolean? = null) {
    AppListItem(
        modifier = if (selected != null) Modifier.semantics { this.selected = selected } else Modifier,
        colors = if (selected == true) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        } else {
            ListItemDefaults.colors()
        },
        headline = contact.displayName,
        // Supporting text only where it carries information the badge cannot. A calm list is
        // what makes the one actionable state — a changed key — visible at a glance;
        // repeating "not verified" under every row would bury it.
        supportingText = when (contact.trust) {
            TrustState.NeedsReverification -> trustDetail(contact.trust)
            else -> null
        },
        onClick = onClick,
        leadingContent = {
            ContactAvatar(
                displayName = contact.displayName,
                personId = contact.personId,
                photo = contact.avatar,
            )
        },
        // The badge carries its own contentDescription (the short trust label), so a screen
        // reader reads the row as name-then-state without extra semantics here.
        trailingContent = { TrustBadge(contact.trust) },
    )
}
