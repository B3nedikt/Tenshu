package app.tenshu.contacts.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ImportContacts
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tenshu.contacts.shared.resources.Res
import app.tenshu.contacts.shared.resources.contacts_add_actions_description
import app.tenshu.contacts.shared.resources.contacts_add_supporting
import app.tenshu.contacts.shared.resources.contacts_add_title
import app.tenshu.contacts.shared.resources.contacts_import_intro_short
import app.tenshu.contacts.shared.resources.contacts_import_title
import app.tenshu.ui.chrome.AppBottomSheet
import app.tenshu.ui.chrome.AppFab
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource

/**
 * The list screen's primary action, in the shape Drive uses
 * (`AddFileActionsFloatingActionButton`): a FAB bottom-end over the content, opening a sheet
 * of the ways to acquire something.
 *
 * Contacts has exactly two that are not a handshake — type someone in, or import them — and
 * they belong together. Before this they were two icon buttons in a top app bar, which put
 * the app's primary action in the corner furthest from the thumb and gave the rarely-used
 * import equal billing with it.
 *
 * ⚠ Inviting is deliberately **not** here. It lives on the *You* card (D6), because an
 * invitation is something you send *about yourself* — it carries your identity and your name,
 * and it is the one acquisition path that needs the other person present.
 */
@Composable
fun AddContactFab(
    sheetVisible: Boolean,
    onClicked: () -> Unit,
    onDismissed: () -> Unit,
    onAddByHand: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppBottomSheet(visible = sheetVisible, onDismissRequest = onDismissed) {
        Column(Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.s)) {
            AppListItem(
                headline = stringResource(Res.string.contacts_add_title),
                // D5: says plainly what a keyless contact is, so the missing badge on the
                // row it creates is not a mystery later.
                supportingText = stringResource(Res.string.contacts_add_supporting),
                onClick = onAddByHand,
                leadingContent = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
            )
            AppListItem(
                headline = stringResource(Res.string.contacts_import_title),
                supportingText = stringResource(Res.string.contacts_import_intro_short),
                onClick = onImport,
                leadingContent = { Icon(Icons.Default.ImportContacts, contentDescription = null) },
            )
        }
    }

    AppFab(
        modifier = modifier,
        onClick = onClicked,
        icon = Icons.Default.Add,
        contentDescription = stringResource(Res.string.contacts_add_actions_description),
    )
}
