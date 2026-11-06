package app.tenshu.ui.chrome

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.DialogProperties
import app.tenshu.ui.preview.PreviewBox
import androidx.compose.ui.tooling.preview.Preview

/**
 * App-styled alert dialog — a thin convenience wrapper over Material 3's [AlertDialog].
 *
 * ### When to use this, and when to use `AlertDialog` directly
 *
 * `AppDialog` fits the ordinary shape: a **string** title, an optional icon, a body, and one
 * or two labelled buttons. Reach for [AlertDialog] directly when you need something it does
 * not model — a composable title, custom container colours, a non-standard button row. The
 * two are the *same component*; this is the shorthand, not a different thing, so mixing them
 * is a question of fit rather than a design-system violation.
 *
 * ### ⚠ History worth knowing, because this KDoc used to say otherwise
 *
 * This file previously claimed: *"On Desktop, this implementation uses a custom modal overlay
 * to avoid focus issues with top-level AWT windows."* That was **never true**. The
 * implementation was a hand-rolled `Dialog { Card { … } }` from its first commit, and the
 * claim was not merely unimplemented — it was *unimplementable by that code*, because
 * `androidx.compose.ui.window.Dialog` is the very primitive the comment said it avoided, and
 * is also exactly what `AlertDialog` opens internally (`DefaultBasicAlertDialogOverride`). A
 * fork of `AlertDialog` cannot dodge a bug in `AlertDialog`'s own window by opening the same
 * window.
 *
 * So the fork bought nothing and cost the M3 spec: 480 dp max width instead of 560, no min
 * width, flat elevation instead of tonal, and a body slot with no supporting-text style. It is
 * gone. What was genuinely worth keeping stayed:
 *
 *  - **[icon] as an `ImageVector` + [iconContentDescription]**, rather than a composable slot.
 *    ⚠ The description is a separate parameter on purpose — a decorative dialog icon *should*
 *    usually be `null` (the title carries the meaning), but that ought to be a decision
 *    someone makes rather than a slot they fill in without thinking.
 *  - **the string-label overload below**, which five of the six call sites use.
 */
@Composable
fun AppDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    properties: DialogProperties = DialogProperties(),
    content: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        icon = icon?.let {
            { Icon(imageVector = it, contentDescription = iconContentDescription) }
        },
        title = { Text(title) },
        text = content,
        properties = properties,
    )
}

/**
 * The common confirm-and-dismiss shape, with the buttons given as labels.
 *
 * [dismissLabel] `null` renders a single-button dialog — the acknowledge case, where there is
 * nothing to decline.
 */
@Composable
fun AppDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String? = null,
    onDismiss: () -> Unit = onDismissRequest,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    properties: DialogProperties = DialogProperties(),
    content: (@Composable () -> Unit)? = null,
) {
    AppDialog(
        title = title,
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = dismissLabel?.let {
            { TextButton(onClick = onDismiss) { Text(it) } }
        },
        icon = icon,
        iconContentDescription = iconContentDescription,
        properties = properties,
        content = content,
    )
}

@Preview
@Composable
private fun AppDialogPreview() = PreviewBox {
    AppDialog(
        title = "Delete file?",
        onDismissRequest = {},
        confirmLabel = "Delete",
        onConfirm = {},
        dismissLabel = "Cancel",
        icon = Icons.Default.Delete,
        iconContentDescription = "Destructive action",
        content = {
            Text("This will permanently remove report.pdf from your Drive.")
        }
    )
}

@Preview
@Composable
private fun AppDialogSingleButtonPreview() = PreviewBox {
    AppDialog(
        title = "Import finished",
        onDismissRequest = {},
        confirmLabel = "Done",
        onConfirm = {},
        content = { Text("42 contacts were added.") },
    )
}

@Preview
@Composable
private fun AppDialogDarkPreview() = PreviewBox(useDarkTheme = true) {
    AppDialog(
        title = "Delete file?",
        onDismissRequest = {},
        confirmLabel = "Delete",
        onConfirm = {},
        dismissLabel = "Cancel",
        content = { Text("This will permanently remove report.pdf from your Drive.") },
    )
}
