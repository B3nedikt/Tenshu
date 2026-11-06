package app.tenshu.contacts.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tenshu.contacts.shared.resources.Res
import app.tenshu.contacts.shared.resources.contacts_dialog_cancel
import app.tenshu.contacts.shared.resources.contacts_name_label
import app.tenshu.ui.chrome.AppDialog
import app.tenshu.ui.input.AppTextField
import org.jetbrains.compose.resources.stringResource

/**
 * One text field and two buttons — shared by *Add a contact* and *Rename*, which are the
 * same gesture over the same field.
 *
 * The petname is the only thing either dialog collects, and that is the model rather than a
 * simplification: `Person` carries a `personId` and a display name, nothing else. vCard
 * fields arrive with `ContactSource` in Ph6 and belong to a fuller editor.
 *
 * Built on `:ui-core`'s [AppDialog] rather than `AlertDialog` directly — the shape fits its
 * shorthand exactly (string title, a body, two buttons), and a second local wrapper doing the
 * design system's job is the smell that opened the `AppDialog` question in the first place.
 * ⚠ The **slot** overload, not the label one, because `confirmLabel`'s button is disabled
 * until the name is non-blank and the label overload has no way to say so.
 */
@Composable
fun NameDialog(
    title: String,
    value: String,
    confirmLabel: String,
    canConfirm: Boolean,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    supporting: String? = null,
) {
    AppDialog(
        title = title,
        onDismissRequest = onDismiss,
        content = {
            AppTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(Res.string.contacts_name_label),
                supportingText = supporting,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.contacts_dialog_cancel))
            }
        },
    )
}
