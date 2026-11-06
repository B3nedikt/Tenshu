package app.tenshu.control.ui.screens.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.control.data.GrantState
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.app_grant_ask_each_time
import app.tenshu.control.shared.resources.app_grant_denied
import app.tenshu.control.shared.resources.app_grant_granted
import app.tenshu.control.shared.resources.apps_unrecognised_badge
import app.tenshu.control.shared.resources.apps_unverified_badge
import org.jetbrains.compose.resources.stringResource

/**
 * Marks an app this Control holds a registration for but has **no manifest** for
 * (`control-app-catalogue.md` C3).
 *
 * ⚠ Deliberately *not* the error colour of [UnverifiedBadge], and deliberately a separate
 * badge. "Unverified" answers *who is this* — the peer's identity was not attested, which is a
 * security signal. This answers *what do we know about it*, which usually means this Control is
 * simply older than the app. Rendering both in red would teach the user to read a routine
 * staleness as a threat.
 */
@Composable
fun UnrecognisedBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = stringResource(Res.string.apps_unrecognised_badge),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * "Unverified" pill shown on apps admitted under the dev-mode handshake
 * downgrade (`AppRegistration.verified = false`, `control-app-registration.md`
 * §3.3). Uses the error-container palette so it reads as a caution.
 */
@Composable
fun UnverifiedBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = stringResource(Res.string.apps_unverified_badge),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun GrantState.displayLabel(): String = stringResource(
    when (this) {
        GrantState.Granted -> Res.string.app_grant_granted
        GrantState.Denied -> Res.string.app_grant_denied
        GrantState.AskEachTime -> Res.string.app_grant_ask_each_time
    }
)
