package app.tenshu.contacts.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import app.tenshu.contacts.model.ContactDetails
import app.tenshu.contacts.shared.resources.Res
import app.tenshu.contacts.shared.resources.contacts_dialog_cancel
import app.tenshu.contacts.shared.resources.contacts_edit_add_email
import app.tenshu.contacts.shared.resources.contacts_edit_add_phone
import app.tenshu.contacts.shared.resources.contacts_edit_details_save
import app.tenshu.contacts.shared.resources.contacts_edit_details_title
import app.tenshu.contacts.shared.resources.contacts_edit_note
import app.tenshu.contacts.shared.resources.contacts_edit_organisation
import app.tenshu.contacts.shared.resources.contacts_edit_preserves_note
import app.tenshu.contacts.ui.screens.detail.DetailFieldKind
import app.tenshu.ui.input.AppTextField
import app.tenshu.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource

/**
 * The Q6 field editor: phones, emails, addresses, organisation, note.
 *
 * ⚠ Deliberately **not** a full contact editor. It shows what the codec parses, and the save
 * path merges those fields back into the stored record rather than rebuilding it — so a card
 * with `X-` extensions, categories or a photo keeps all of them even though nothing here can
 * show them. The copy at the bottom says so, because a user who sees five fields and knows
 * their card had more would reasonably assume the rest was gone.
 *
 * Clearing a field removes it; adding an empty row and leaving it blank does nothing.
 * Addresses are single-line here — see `mergeIntoVCard`'s note on the structured-`ADR`
 * tradeoff.
 */
@Composable
fun ContactDetailsEditor(
    details: ContactDetails,
    isSaving: Boolean,
    onFieldChanged: (DetailFieldKind, Int, String) -> Unit,
    onFieldAdded: (DetailFieldKind) -> Unit,
    onOrganisationChanged: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.contacts_edit_details_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
            ) {
                details.phones.forEachIndexed { index, field ->
                    AppTextField(
                        value = field.value,
                        onValueChange = { onFieldChanged(DetailFieldKind.PHONE, index, it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = field.label.ifEmpty { stringResource(Res.string.contacts_edit_add_phone) },
                        keyboardType = KeyboardType.Phone,
                    )
                }
                TextButton(onClick = { onFieldAdded(DetailFieldKind.PHONE) }) {
                    Text(stringResource(Res.string.contacts_edit_add_phone))
                }

                details.emails.forEachIndexed { index, field ->
                    AppTextField(
                        value = field.value,
                        onValueChange = { onFieldChanged(DetailFieldKind.EMAIL, index, it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = field.label.ifEmpty { stringResource(Res.string.contacts_edit_add_email) },
                        keyboardType = KeyboardType.Email,
                    )
                }
                TextButton(onClick = { onFieldAdded(DetailFieldKind.EMAIL) }) {
                    Text(stringResource(Res.string.contacts_edit_add_email))
                }

                details.addresses.forEachIndexed { index, field ->
                    AppTextField(
                        value = field.value,
                        onValueChange = { onFieldChanged(DetailFieldKind.ADDRESS, index, it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = field.label,
                        singleLine = false,
                    )
                }

                AppTextField(
                    value = details.organisation,
                    onValueChange = onOrganisationChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(Res.string.contacts_edit_organisation),
                )
                AppTextField(
                    value = details.note,
                    onValueChange = onNoteChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(Res.string.contacts_edit_note),
                    singleLine = false,
                )

                Text(
                    text = stringResource(Res.string.contacts_edit_preserves_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !isSaving) {
                Text(stringResource(Res.string.contacts_edit_details_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.contacts_dialog_cancel))
            }
        },
    )
}
