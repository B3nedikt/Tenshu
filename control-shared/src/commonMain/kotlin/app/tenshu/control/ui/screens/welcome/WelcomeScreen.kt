package app.tenshu.control.ui.screens.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.welcome_body
import app.tenshu.control.shared.resources.welcome_existing_device
import app.tenshu.control.shared.resources.welcome_recover_phrase
import app.tenshu.control.shared.resources.welcome_start_fresh
import app.tenshu.control.shared.resources.welcome_title
import app.tenshu.ui.components.marks.MarkOutline
import app.tenshu.ui.feedback.ErrorBanner
import org.jetbrains.compose.resources.stringResource

/**
 * §4.0 Welcome chooser. The first-time-on-this-device branch point — asks
 * the user whether they are starting fresh on this device (route to §4.1
 * unlock setup) or have Tenshu on another device (route to §4a pairing).
 *
 * Per `docs/proposals/onboarding-low-friction.md` §4.0: both CTAs are
 * equally weighted. Neither is styled as "primary" — a returning-user path
 * and a new-user path are equally valid first-run choices. The Tenshu
 * logomark sits centred above the title.
 *
 * ## [notice] — the outcome of the forget that landed the user here
 *
 * `ForgetThisDevice` is deliberately **partially-failable**: it never blocks the local wipe on a
 * retirement it could not complete, because people forget a device precisely when something is
 * broken. Its own KDoc calls the result *"an announced outcome with a named remedy — never a silent
 * orphan"* — and the remedy is always *"from another device"*, which is exactly why this screen is
 * its home: the user has just been dropped here with nothing else to look at, and it needs no
 * raising to be seen. A modal was tried first and was invisible on macOS three fixes running
 * (`control-keep-device-unlocked.md` §6.1).
 *
 * ⚠ `errorContainer` tone rather than informational, and that is a judgement worth stating: a
 * retirement that failed means the admission strip did not reach the relay, so a device that should
 * have lost access may still have it. That is a loose end, not a status update.
 *
 * ⚠⚠ It is **transient** — in-memory, gone on the next launch or as soon as the user leaves this
 * screen. Good enough for the moment it matters and no more; nothing durably records that a realm
 * has an orphaned row, which is a real gap rather than a shortcut.
 */
@Composable
fun WelcomeScreen(
    onExistingDevice: () -> Unit,
    onNewUser: () -> Unit,
    onRecover: () -> Unit,
    notice: String? = null,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            notice?.let {
                ErrorBanner(text = it, icon = Icons.Default.Warning)
                Spacer(modifier = Modifier.height(24.dp))
            }
            MarkOutline(size = 96.dp)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(Res.string.welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(Res.string.welcome_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedButton(
                onClick = onNewUser,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.welcome_start_fresh))
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onExistingDevice,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.welcome_existing_device))
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onRecover) {
                Text(stringResource(Res.string.welcome_recover_phrase))
            }
        }
    }
}
