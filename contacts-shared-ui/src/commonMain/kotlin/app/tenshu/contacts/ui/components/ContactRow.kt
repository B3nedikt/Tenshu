package app.tenshu.contacts.ui.components

import androidx.compose.runtime.Composable
import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.TrustState
import app.tenshu.ui.chrome.AppListItem

/**
 * One person, wherever they are listed — the main list and the expanded search surface both
 * render this, so a search result behaves and reads exactly like a list row (Drive's rule for
 * its own search results).
 */
@Composable
fun ContactRow(contact: Contact, onClick: () -> Unit) {
    AppListItem(
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
