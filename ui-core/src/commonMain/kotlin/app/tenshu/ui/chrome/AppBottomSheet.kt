package app.tenshu.ui.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tenshu.ui.preview.PreviewBox
import app.tenshu.ui.theme.spacing
import androidx.compose.ui.tooling.preview.Preview

/**
 * App-styled modal bottom sheet that skips the partially-expanded state and
 * pads around the system navigation bars. Thin wrapper over Material 3
 * [ModalBottomSheet].
 *
 * ### When to use
 *
 * For contextual actions, filters, or short forms triggered by a single
 * action in the host screen. For anything that needs navigation or multi-step
 * flows, prefer a full-screen destination instead.
 *
 * ### State model
 *
 * [visible] gates the entire sheet, including [content]. While `visible` is
 * true, `content` is composed inside the sheet and may read caller state
 * that is non-null only in that branch. When the caller flips `visible` to
 * false the sheet leaves composition immediately (no programmatic-dismiss
 * animation); for user dismissal (scrim tap, drag-to-dismiss, system back)
 * Material 3 plays its slide-out animation internally before invoking
 * [onDismissRequest], at which point the caller flips `visible` to false.
 *
 * ### Accessibility
 *
 * On phones, the drag handle and scrim provide discoverable dismiss
 * affordances. On desktop (Compose Multiplatform), the Escape key dismisses
 * the sheet — callers don't have to wire this up.
 *
 * @param visible           whether the sheet should be shown. Toggle this
 *                          from caller state.
 * @param onDismissRequest  called when the sheet wants to dismiss (scrim
 *                          tap, drag-to-dismiss, system back).
 * @param content           sheet body, rendered inside a `ColumnScope`
 *                          provided by [ModalBottomSheet]. Only composed
 *                          while [visible] is true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        modifier = Modifier.navigationBarsPadding(),
        sheetState = sheetState,
        onDismissRequest = onDismissRequest
    ) {
        content()
    }
}

@Preview
@Composable
private fun AppBottomSheetPreview() = PreviewBox {
    // Previews can't render a ModalBottomSheet's floating presentation
    // directly; this is a representative body as it would be composed
    // into the sheet's ColumnScope.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MaterialTheme.spacing.l),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
    ) {
        Text("Share file", style = MaterialTheme.typography.titleMedium)
        Text(
            "Anyone with the link can view this file.",
            style = MaterialTheme.typography.bodyMedium
        )
        TextButton(onClick = {}) { Text("Copy link") }
        TextButton(onClick = {}) { Text("Remove share") }
    }
}
