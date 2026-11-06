package app.tenshu.contacts.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tenshu.contacts.model.TrustState
import app.tenshu.contacts.shared.resources.Res
import app.tenshu.contacts.shared.resources.contacts_trust_needs_reverification
import app.tenshu.contacts.shared.resources.contacts_trust_needs_reverification_detail
import app.tenshu.contacts.shared.resources.contacts_trust_not_connected
import app.tenshu.contacts.shared.resources.contacts_trust_not_connected_detail
import app.tenshu.contacts.shared.resources.contacts_trust_unverified
import app.tenshu.contacts.shared.resources.contacts_trust_unverified_detail
import app.tenshu.contacts.shared.resources.contacts_trust_verified
import app.tenshu.contacts.shared.resources.contacts_trust_verified_detail
import org.jetbrains.compose.resources.stringResource

/**
 * The D3 row badge. One list holds connected and import-only contacts alike; this is what
 * tells them apart.
 *
 * [TrustState.NotConnected] renders **nothing**: a contact without a key is the ordinary
 * case once import lands (Ph6/Ph7), and badging the majority state would read as a warning
 * about people the user simply typed in.
 */
@Composable
fun TrustBadge(trust: TrustState, modifier: Modifier = Modifier) {
    val icon = trustIcon(trust) ?: return
    Icon(
        imageVector = icon,
        contentDescription = trustLabel(trust),
        tint = trustTint(trust),
        modifier = modifier.size(18.dp),
    )
}

private fun trustIcon(trust: TrustState): ImageVector? = when (trust) {
    TrustState.Verified -> Icons.Default.Verified
    TrustState.NeedsReverification -> Icons.Default.ErrorOutline
    TrustState.Unverified -> Icons.Outlined.Key
    TrustState.NotConnected -> null
}

@Composable
private fun trustTint(trust: TrustState): Color = when (trust) {
    TrustState.Verified -> MaterialTheme.colorScheme.primary
    // Not `error`: a changed key is a prompt to re-check, not a failure. Reserving the
    // error colour keeps it meaningful for states that really are wrong.
    TrustState.NeedsReverification -> MaterialTheme.colorScheme.tertiary
    TrustState.Unverified -> MaterialTheme.colorScheme.onSurfaceVariant
    TrustState.NotConnected -> Color.Unspecified
}

/** The short badge label — also the icon's content description. */
@Composable
fun trustLabel(trust: TrustState): String = stringResource(
    when (trust) {
        TrustState.Verified -> Res.string.contacts_trust_verified
        TrustState.NeedsReverification -> Res.string.contacts_trust_needs_reverification
        TrustState.Unverified -> Res.string.contacts_trust_unverified
        TrustState.NotConnected -> Res.string.contacts_trust_not_connected
    }
)

/**
 * The sentence under the name on the detail screen. `NeedsReverification` is the one that
 * has to earn its words: it must name the *key change* (Q2b), because "not verified" alone
 * would read as though the user had simply never got round to it.
 */
@Composable
fun trustDetail(trust: TrustState): String = stringResource(
    when (trust) {
        TrustState.Verified -> Res.string.contacts_trust_verified_detail
        TrustState.NeedsReverification -> Res.string.contacts_trust_needs_reverification_detail
        TrustState.Unverified -> Res.string.contacts_trust_unverified_detail
        TrustState.NotConnected -> Res.string.contacts_trust_not_connected_detail
    }
)
