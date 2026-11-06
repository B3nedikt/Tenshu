package app.tenshu.contacts.ui.error

import androidx.compose.runtime.Composable
import app.tenshu.contacts.data.ContactsErrorKeys
import app.tenshu.contacts.shared.resources.Res
import app.tenshu.contacts.shared.resources.error_contacts_none_in_file
import app.tenshu.contacts.shared.resources.error_contacts_nothing_to_export
import app.tenshu.contacts.shared.resources.error_contacts_photo_unavailable
import app.tenshu.ui.error.label
import org.jetbrains.compose.resources.stringResource
import app.tenshu.error.ErrorCopyKey

/**
 * Contacts' own error copy, chained in front of the shared catalogue.
 *
 * ⭐ `core-error-copy-catalogue.md` §5.1: `:ui-core` owns the core `TError` taxonomy and the
 * fallback; an app contributes keys for failures that are not `TError`s at all. Screens call this
 * rather than `label()` directly, so an app key and a core key look the same at the call site.
 */
@Composable
fun ErrorCopyKey.contactsLabel(): String = when (this) {
    ContactsErrorKeys.NO_CONTACTS_IN_FILE -> stringResource(Res.string.error_contacts_none_in_file)
    ContactsErrorKeys.NOTHING_TO_EXPORT -> stringResource(Res.string.error_contacts_nothing_to_export)
    ContactsErrorKeys.PHOTO_UNAVAILABLE -> stringResource(Res.string.error_contacts_photo_unavailable)
    else -> label()
}
