package app.tenshu.ui.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.tenshu.ui.preview.PreviewBox
import app.tenshu.ui.theme.spacing
import androidx.compose.ui.tooling.preview.Preview

/**
 * App-styled text input wrapping Material 3 [OutlinedTextField].
 *
 * ### When to use
 *
 * For every single-line or multi-line text input in Drive and Control,
 * including dialog inputs and form screens. Exotic inputs (date pickers,
 * file chooser, QR scanner) bring their own dedicated composables.
 *
 * ### Variants / common props
 *
 * - **Labelled input** — pass [label] (renders as the floating caption).
 * - **Placeholder** — pass [placeholder] (shown while the field is empty).
 * - **Supporting text** — helper copy under the field; superseded by
 *   [errorText] when present.
 * - **Error state** — set [errorText] to a non-null string. The field
 *   flips to [isError] and the error replaces [supportingText].
 * - **Password** — set [isPassword]. Applies [PasswordVisualTransformation]
 *   and a password [KeyboardType] unless an explicit [keyboardType] is
 *   supplied.
 * - **Leading / trailing icons** — `leadingIcon` / `trailingIcon` slots.
 *
 * ### Accessibility
 *
 * The [label] acts as the field's accessible name. Supply either [label]
 * or a meaningful [placeholder] — a field with neither cannot be announced
 * usefully. Error text is read when focus enters the field.
 *
 * @param value             current text content.
 * @param onValueChange     callback for every edit. Hoist state in the
 *                          caller; this wrapper does not remember anything.
 * @param modifier          passed to the underlying `OutlinedTextField`.
 * @param label             floating caption.
 * @param placeholder       rendered while [value] is empty.
 * @param supportingText    helper copy under the field.
 * @param errorText         error message; non-null flips the field into
 *                          the error state and replaces [supportingText].
 * @param keyboardType      explicit keyboard type; defaults based on
 *                          [isPassword].
 * @param isPassword        applies password masking and keyboard type.
 * @param singleLine        single-line by default; set `false` for
 *                          multiline inputs (notes, messages).
 * @param enabled           disables input and greys out the field.
 * @param readOnly          read-only but focusable (so screen readers
 *                          announce it).
 * @param leadingIcon       leading slot.
 * @param trailingIcon      trailing slot (visibility toggle, clear, etc.).
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    errorText: String? = null,
    keyboardType: KeyboardType? = null,
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val resolvedKeyboardType = keyboardType
        ?: if (isPassword) KeyboardType.Password else KeyboardType.Unspecified
    val visualTransformation: VisualTransformation =
        if (isPassword) PasswordVisualTransformation() else VisualTransformation.None
    val isError = errorText != null

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        isError = isError,
        label = label?.let { { Text(text = it) } },
        placeholder = placeholder?.let { { Text(text = it) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = resolvedKeyboardType),
        supportingText = when {
            errorText != null -> {
                {
                    Text(
                        text = errorText,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            supportingText != null -> {
                { Text(text = supportingText) }
            }

            else -> null
        }
    )
}

@Preview
@Composable
private fun AppTextFieldPreview() = PreviewBox {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m),
    ) {
        AppTextField(
            value = "",
            onValueChange = {},
            label = "Device name",
            placeholder = "e.g. Work laptop",
            modifier = Modifier.fillMaxWidth(),
        )
        AppTextField(
            value = "benedikt@example.com",
            onValueChange = {},
            label = "Email",
            keyboardType = KeyboardType.Email,
            leadingIcon = {
                androidx.compose.material3.Icon(
                    Icons.Default.Email,
                    contentDescription = null,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview
@Composable
private fun AppTextFieldPasswordPreview() = PreviewBox {
    AppTextField(
        value = "hunter2hunter2",
        onValueChange = {},
        label = "Password",
        isPassword = true,
        supportingText = "At least 12 characters",
        modifier = Modifier.fillMaxWidth(),
    )
}

@Preview
@Composable
private fun AppTextFieldErrorPreview() = PreviewBox {
    AppTextField(
        value = "nope",
        onValueChange = {},
        label = "Folder name",
        errorText = "A folder with this name already exists.",
        modifier = Modifier.fillMaxWidth(),
    )
}

@Preview
@Composable
private fun AppTextFieldDisabledPreview() = PreviewBox {
    AppTextField(
        value = "Read-only value",
        onValueChange = {},
        label = "Device ID",
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Preview
@Composable
private fun AppTextFieldDarkPreview() = PreviewBox(useDarkTheme = true) {
    AppTextField(
        value = "",
        onValueChange = {},
        label = "Device name",
        placeholder = "e.g. Work laptop",
        modifier = Modifier.fillMaxWidth(),
    )
}
