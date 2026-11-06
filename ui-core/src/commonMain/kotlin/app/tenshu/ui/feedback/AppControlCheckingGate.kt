package app.tenshu.ui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import app.tenshu.ui.preview.PreviewBox
import app.tenshu.ui.resources.Res
import app.tenshu.ui.resources.control_checking_retry
import app.tenshu.ui.resources.control_checking_timed_out_body
import app.tenshu.ui.resources.control_checking_timed_out_title
import app.tenshu.ui.resources.control_checking_title
import app.tenshu.ui.theme.spacing
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * A non-interactive stand-in for an app's whole shell while it is not yet known whether Control's
 * device has a realm (`UnreachableReason.Checking`, `ADR 0025` §Decision 8, §8.6).
 *
 * ### When to use
 * In an app root, in place of the shell, exactly while the reachability engine reports
 * `UnreachableReason.Checking` — Control is answering, and the realm read has not answered yet.
 * The shell's own writes (an upload, a save) are user acts, and nobody knows yet whether there is a
 * realm to write into, so the shell must not be interactive. With the realm read's retry backoff it
 * normally lasts about a second. The iOS twin is `ControlProvisioning.checking`.
 *
 * Not for an outage (the transport reasons keep their banner), not for a device known to have no
 * realm (that is [AppControlUnavailableWall] with the NotSetUp words), and not for a reconnect of a
 * device that had a realm — the engine keeps the shell then and holds only unattended writes.
 *
 * ### Variants
 * The phase is driven by time, not by the caller:
 * - **Blank** for [quietMs] (default 300 ms) — a read normally answers inside it, and a spinner
 *   flashed on every launch is worse than none.
 * - **Spinner** with [appControlCheckingLabel] — "Checking Tenshu Control…".
 * - **Retry wall** once [boundMs] (default 20 s) has passed without an answer — it never spins
 *   forever. "Try again" asks the engine to probe and re-read now ([onRetry]) and re-arms the
 *   bounded wait; the engine also keeps re-reading on its own, so the gate lifts by itself the
 *   moment a read answers.
 *
 * The copy is not a parameter: it is the same in every app, so it lives once in ui-core's resource
 * catalogue (`control_checking_*`), and no app root can fall back to an untranslated default.
 *
 * ### Accessibility
 * The spinner phase announces its text through a polite live region, so a screen reader hears that
 * the app is checking rather than silence. The blank phase is an empty surface with nothing to
 * focus. The retry wall is a [AppControlUnavailableWall]: its title, body and one action button are
 * read in order and the button is keyboard-reachable.
 *
 * @param onRetry Runs when the user taps "Try again" on the retry wall, before the bounded wait
 *   re-arms. ⚠⚠ Required, and it must ask the engine again (`ControlReachability.recheck`, or the
 *   app repository's `recheckControl`): re-arming the timer alone repeats the wait without a new
 *   probe or realm read (Codex P2 on 67e1533a4).
 * @param quietMs How long the gate stays blank before showing the spinner.
 * @param boundMs How long the gate may spin before it becomes the retry wall.
 */
@Composable
fun AppControlCheckingGate(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    quietMs: Long = 300,
    boundMs: Long = 20_000,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(CheckingPhase.Blank) }
    LaunchedEffect(attempt) {
        phase = CheckingPhase.Blank
        delay(quietMs)
        phase = CheckingPhase.Spinner
        delay((boundMs - quietMs).coerceAtLeast(0))
        phase = CheckingPhase.TimedOut
    }
    AppControlCheckingContent(
        phase = phase,
        modifier = modifier,
        onRetry = {
            onRetry()
            attempt++
        },
    )
}

/** The three phases of [AppControlCheckingGate]. */
internal enum class CheckingPhase { Blank, Spinner, TimedOut }

/** Stateless rendering of one [CheckingPhase] — what [AppControlCheckingGate] drives, and what previews show. */
@Composable
internal fun AppControlCheckingContent(
    phase: CheckingPhase,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    when (phase) {
        CheckingPhase.Blank ->
            Surface(modifier.fillMaxSize().testTag(TAG_BLANK), color = MaterialTheme.colorScheme.surface) {}
        CheckingPhase.Spinner ->
            Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.l),
                    ) {
                        CircularProgressIndicator(Modifier.testTag(TAG_SPINNER))
                        Text(
                            appControlCheckingLabel(),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(horizontal = MaterialTheme.spacing.xl)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        CheckingPhase.TimedOut ->
            AppControlUnavailableWall(
                title = stringResource(Res.string.control_checking_timed_out_title),
                body = stringResource(Res.string.control_checking_timed_out_body),
                modifier = modifier,
                icon = Icons.Default.Settings,
                actionLabel = stringResource(Res.string.control_checking_retry),
                onAction = onRetry,
            )
    }
}

/**
 * The localised "Checking Tenshu Control…" — the words for `UnreachableReason.Checking`.
 *
 * ### When to use
 * In an app's Control banner, for the `Checking` branch of its reason-to-copy `when` (Fit's, Maps',
 * Photos'…). It is the same catalogue entry the gate's spinner shows, so a banner and the gate can
 * never disagree or fall back to an untranslated literal. Not a surface: for the screen itself use
 * [AppControlCheckingGate].
 *
 * ### Variants
 * None — one string, resolved for the current locale.
 *
 * ### Accessibility
 * Returns text only; the semantics belong to whatever renders it (the banner's text is read as
 * part of the banner). There is nothing of its own to preview — its rendering is the gate's spinner
 * phase, which `AppControlCheckingSpinnerPreview` shows.
 */
@Composable
fun appControlCheckingLabel(): String = stringResource(Res.string.control_checking_title)

/** Test tags for the two silent phases (the retry wall is found by its text). */
const val TAG_BLANK = "control-checking-blank"
const val TAG_SPINNER = "control-checking-spinner"

@Preview
@Composable
private fun AppControlCheckingBlankPreview() = PreviewBox {
    AppControlCheckingContent(phase = CheckingPhase.Blank)
}

@Preview
@Composable
private fun AppControlCheckingSpinnerPreview() = PreviewBox {
    AppControlCheckingContent(phase = CheckingPhase.Spinner)
}

@Preview
@Composable
private fun AppControlCheckingRetryPreview() = PreviewBox {
    AppControlCheckingContent(phase = CheckingPhase.TimedOut)
}

@Preview
@Composable
private fun AppControlCheckingSpinnerDarkPreview() = PreviewBox(useDarkTheme = true) {
    AppControlCheckingContent(phase = CheckingPhase.Spinner)
}

@Preview
@Composable
private fun AppControlCheckingRetryDarkPreview() = PreviewBox(useDarkTheme = true) {
    AppControlCheckingContent(phase = CheckingPhase.TimedOut)
}
