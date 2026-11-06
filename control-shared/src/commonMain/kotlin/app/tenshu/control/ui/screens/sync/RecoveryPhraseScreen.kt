package app.tenshu.control.ui.screens.sync

import kotlinx.coroutines.launch
import app.tenshu.control.shared.resources.recovery_phrase_marked_saved
import app.tenshu.control.shared.resources.recovery_phrase_mark_saved
import app.tenshu.control.data.RecoveryAffordancePreferences
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.tenshu.control.data.ReauthPromptCopy
import app.tenshu.control.data.Reauthenticator
import app.tenshu.control.data.SyncRepository
import app.tenshu.control.data.confirm
import app.tenshu.control.data.rememberBiometricPromptHost
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.recovery_phrase_absent_body
import app.tenshu.control.shared.resources.recovery_phrase_absent_title
import app.tenshu.control.shared.resources.recovery_phrase_copied
import app.tenshu.control.shared.resources.recovery_phrase_copy
import app.tenshu.control.shared.resources.recovery_phrase_hidden_placeholder
import app.tenshu.control.shared.resources.recovery_phrase_hide
import app.tenshu.control.shared.resources.recovery_phrase_show
import app.tenshu.control.shared.resources.reauth_recovery_phrase_subtitle
import app.tenshu.control.shared.resources.reauth_recovery_phrase_system_reason
import app.tenshu.control.shared.resources.reauth_title
import app.tenshu.control.shared.resources.recovery_phrase_title
import app.tenshu.control.shared.resources.recovery_phrase_warning
import app.tenshu.ui.chrome.AppTopAppBar
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import org.koin.compose.koinInject
import org.tenshu.core.getOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryPhraseScreen(
    onBack: () -> Unit
) {
    val syncRepository: SyncRepository = koinInject()
    // ⭐ Closes the loop the banner opened: `RecoveryAffordanceViewModel` reads this flag, and until
    // now nothing wrote it — so the warning was dismissible for 72h but never resolvable.
    val recoveryPreferences: RecoveryAffordancePreferences = koinInject()
    val scope = rememberCoroutineScope()
    val phraseSaved by recoveryPreferences.observePhraseSaved().collectAsState(initial = false)
    var phrase by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        phrase = syncRepository.getRecoveryPhrase().getOrNull()?.get()
    }

    var isVisible by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    // Ph-reauth (`control-keep-device-unlocked.md` §6.1): a presence check in front of the one
    // disclosure on this screen that cannot be undone.
    //
    // ⚠⚠ Deliberately NOT gated on the "Require unlock" preference. The front door is optional and
    // off for some users; A1's plaintext should not be. Before this, a user with the soft lock off
    // had no authentication at all in front of the phrase.
    //
    // ⚠⚠ It gates the transition **concealed → disclosed**, which is both the eye *and* Copy —
    // copying puts the phrase on the clipboard without ever rendering it, so gating only the reveal
    // would leave the door beside the one we just locked.
    val reauthenticator: Reauthenticator = koinInject()
    val promptHost = rememberBiometricPromptHost()
    val reauthCopy = ReauthPromptCopy(
        title = stringResource(Res.string.reauth_title),
        subtitle = stringResource(Res.string.reauth_recovery_phrase_subtitle),
        systemReason = stringResource(Res.string.reauth_recovery_phrase_system_reason),
    )

    // ⚠ Re-conceal when the app leaves the foreground, or the gate is a formality: a phrase left on
    // screen rides into the app switcher's snapshot and into whoever picks the device up next. The
    // soft app-lock's overlay covers this only for users who have it on — this screen must not
    // depend on that.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        isVisible = false
        copied = false
    }

    Scaffold(
        topBar = {
            AppTopAppBar(title = stringResource(Res.string.recovery_phrase_title), onBack = onBack)
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(Res.string.recovery_phrase_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )

                Spacer(modifier = Modifier.height(32.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // ⚠⚠ THREE states, not two. This block used to read
                        // `if (isVisible && phrase != null) … else placeholder`, which collapsed
                        // "hidden" and "there is no phrase on this device" into the same render —
                        // so on a device without one the reveal button did nothing at all, forever,
                        // with no way to tell a missing phrase from a broken button. A device added
                        // by pairing never receives the phrase, so that is not a rare state; and
                        // this is the screen whose whole job is saving the one thing that survives
                        // losing every device.
                        when {
                            phrase == null -> {
                                Text(
                                    text = stringResource(Res.string.recovery_phrase_absent_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(Res.string.recovery_phrase_absent_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }

                            isVisible -> Text(
                                text = phrase!!,
                                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(16.dp)
                            )

                            else -> Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(stringResource(Res.string.recovery_phrase_hidden_placeholder))
                            }
                        }

                        // ⚠ Both controls are hidden rather than disabled when there is nothing to
                        // act on: a greyed-out eye still invites the tap that taught the user the
                        // screen was broken.
                        if (phrase != null) {
                            Row {
                                IconButton(
                                    onClick = {
                                        if (isVisible) {
                                            isVisible = false // concealing needs no proof
                                        } else {
                                            scope.launch {
                                                if (reauthenticator.confirm(promptHost, reauthCopy)) {
                                                    isVisible = true
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = stringResource(
                                            if (isVisible) Res.string.recovery_phrase_hide
                                            else Res.string.recovery_phrase_show
                                        ),
                                    )
                                }
                                // ⚠ Was `/* TODO: Copy to clipboard */` — a second silent no-op on
                                // the same screen. Copying is how a phrase reaches a password
                                // manager, which is the realistic way anyone keeps it.
                                IconButton(
                                    onClick = {
                                        // Already on screen ⇒ already disclosed: re-prompting to
                                        // copy what the user is looking at is friction with no
                                        // security to show for it.
                                        if (isVisible) {
                                            clipboard.setText(AnnotatedString(phrase!!))
                                            copied = true
                                        } else {
                                            scope.launch {
                                                if (reauthenticator.confirm(promptHost, reauthCopy)) {
                                                    clipboard.setText(AnnotatedString(phrase!!))
                                                    copied = true
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = stringResource(Res.string.recovery_phrase_copy),
                                    )
                                }
                            }
                            if (copied) {
                                Text(
                                    text = stringResource(Res.string.recovery_phrase_copied),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }

                            // ⚠⚠ Gated on `isVisible`, not merely on holding a phrase. An
                            // acknowledgement offered before the reveal would let a user silence
                            // the warning without ever seeing what they are supposed to save —
                            // which is exactly the state that cost a realm its master identity
                            // (§5b.11). Seeing it is the point; the button only records that it
                            // happened.
                            if (isVisible) {
                                Spacer(modifier = Modifier.height(8.dp))
                                if (phraseSaved) {
                                    Text(
                                        text = stringResource(Res.string.recovery_phrase_marked_saved),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    TextButton(
                                        onClick = {
                                            scope.launch { recoveryPreferences.setPhraseSaved(true) }
                                        },
                                    ) {
                                        Text(stringResource(Res.string.recovery_phrase_mark_saved))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
