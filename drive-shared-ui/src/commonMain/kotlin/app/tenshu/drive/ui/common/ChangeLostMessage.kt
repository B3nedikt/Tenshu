package app.tenshu.drive.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.drive.data.ChangeLostModel
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.change_lost_dismiss
import app.tenshu.drive.shared.resources.change_lost_text
import org.jetbrains.compose.resources.stringResource

/**
 * CR-2 B4 P3 (`content-plane-cr2-b4-spec.md` §2.4): a change to [notice]'s file was lost — another
 * device deleted it while the change waited, and no device held its bytes. Shown once, dismissable;
 * Control's feed keeps the synced row.
 */
@Composable
fun ChangeLostMessage(notice: ChangeLostModel, onDismiss: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(Res.string.change_lost_text, notice.fileName),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = { onDismiss(notice.id) }) {
                Text(stringResource(Res.string.change_lost_dismiss))
            }
        }
    }
}
