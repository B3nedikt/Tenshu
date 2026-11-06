package app.tenshu.drive.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.contact_sas_match
import app.tenshu.drive.shared.resources.contact_sas_title
import org.jetbrains.compose.resources.stringResource

/**
 * The §5 SAS comparison panel (`drive-manage-access-ux.md` §5.2/§5.3), shared
 * by the completion prompt and the redeem flow's waiting state. Presentation
 * follows the pairing wizard's conventions: the 6 digits space-grouped
 * (`482 913`) plus the peer fingerprint. The checkbox is the *only* way
 * `sasConfirmed` flows — never implicit.
 */
@Composable
fun SasConfirmPanel(
    sas: String,
    peerFingerprint: String,
    confirmed: Boolean,
    onConfirmedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(Res.string.contact_sas_title),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatSas(sas),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = peerFingerprint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = confirmed, onCheckedChange = onConfirmedChange)
            Text(
                text = stringResource(Res.string.contact_sas_match),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** `"482913"` → `"482 913"` (§5.4 display format). */
internal fun formatSas(sas: String): String =
    sas.chunked(3).joinToString(" ")
