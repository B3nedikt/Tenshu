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
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import app.tenshu.control.shared.resources.welcome_forget_data_not_cleared
import app.tenshu.control.shared.resources.welcome_forget_key_not_saved
import app.tenshu.control.shared.resources.welcome_forget_not_finished
import app.tenshu.control.shared.resources.welcome_forget_still_listed
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
 * ## [notices] — what the forget that landed the user here left to do
 *
 * `ForgetThisDevice` is deliberately **partially-failable**: it never blocks the local wipe on a
 * retirement it could not complete, because people forget a device precisely when something is
 * broken. What it left behind is shown here, because the user has just been dropped on this screen
 * with nothing else to look at (a modal was tried first and was invisible on macOS three fixes
 * running, `control-keep-device-unlocked.md` §6.1).
 *
 * ⚠⚠ **Plain words, never the op's outcome string.** That string is a log line ("forgotten — ⚠ …;
 * 177 queued item(s) dropped; local store dropped"), English-only, and shown verbatim in a red
 * banner it read as a failure on the one screen a user reaches expecting everything to be fine.
 * [forgetNoticesFor] maps it to [ForgetNotice]s; the string itself goes to the log.
 *
 * ⚠ Tone follows what the user can do. [ForgetNotice.MayStillBeListed] is housekeeping: this
 * device's keys are already gone, so the leftover row admits nobody — it only clutters the device
 * list (and holds back storage reclamation until removed). It gets an informational card. A forget
 * that did not finish, or data that was not cleared, is a broken state and keeps the error tone.
 *
 * ⚠⚠ It is **transient** — in-memory, gone on the next launch or as soon as the user leaves this
 * screen. Nothing durably records that a realm has an orphaned row, which is a real gap rather
 * than a shortcut.
 */
@Composable
fun WelcomeScreen(
    onExistingDevice: () -> Unit,
    onNewUser: () -> Unit,
    onRecover: () -> Unit,
    notices: List<ForgetNotice> = emptyList(),
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
            notices.forEach {
                ForgetNoticeBanner(it)
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (notices.isNotEmpty()) Spacer(modifier = Modifier.height(12.dp))
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

/** What a `ForgetThisDevice` run left for the user to do — see [WelcomeScreen]'s `notices`. */
enum class ForgetNotice {
    /** The device row may still be in the realm; remove it from another device. Housekeeping. */
    MayStillBeListed,

    /** The local store's wipe did not finish, so custody was kept; forget again. */
    NotFinished,

    /** The forget completed but the local store could not be wiped. */
    DataNotCleared,

    /**
     * The old identity is gone but the new device key could not be saved, so pairing is refused
     * until Control restarts (threat model A4, *lifecycle*). ⚠ Not [NotFinished]: custody is already
     * gone, so the other notices are still the only report of the row and the store.
     */
    KeyNotSaved,
}

/**
 * Map a `ForgetThisDevice` outcome string to what the Welcome screen should say — broken states
 * first — or nothing for a clean removal.
 *
 * ⚠⚠ **The `⚠` marker decides whether there is a notice at all**, and setting it is the producer's
 * job: `ForgetThisDeviceImplementation` prefixes exactly its non-clean parts with it. That is
 * pinned from the producing side by `ForgetThisDeviceOutcomeMarkerTest`, together with the two
 * phrases matched below, so a reworded outcome fails a test instead of silently mis-classifying.
 * Both ends live in this repo and change together — not a status-text match on a remote contract.
 *
 * ⚠⚠ **A list, because one outcome can carry two problems.** A failed retirement and a store that
 * would not wipe arrive together (`forgotten — ⚠ …still listed…; …; ⚠ local store NOT dropped`),
 * and that pair never comes back: custody is already gone, so no second forget will report the row.
 * Showing only one would hide the leftover row for good.
 *
 * ⚠ A forget that did not finish reports [ForgetNotice.NotFinished] alone: custody is kept, so the
 * repeated forget runs the retirement again and reports the row then.
 *
 * ⚠ Anything marked but unrecognised counts as [ForgetNotice.MayStillBeListed]: its remedy (check
 * the device list from another device) is safe for every leftover, and an unknown outcome is never
 * treated as success. A missing outcome (`null`) is unknown too.
 */
fun forgetNoticesFor(outcome: String?): List<ForgetNotice> {
    if (outcome == null) return listOf(ForgetNotice.MayStillBeListed)
    if (!outcome.contains('⚠')) return emptyList()
    if (outcome.contains(FORGET_OUTCOME_NOT_FINISHED)) return listOf(ForgetNotice.NotFinished)
    val storeNotDropped = outcome.contains(FORGET_OUTCOME_STORE_NOT_DROPPED)
    val keyNotSaved = outcome.contains(FORGET_OUTCOME_KEY_NOT_SAVED)
    // Whatever is still marked once the store's and the key's own marked phrases are taken out is
    // about the row.
    val rest = outcome
        .replace("⚠ $FORGET_OUTCOME_STORE_NOT_DROPPED", "")
        .replace("⚠ $FORGET_OUTCOME_KEY_NOT_SAVED", "")
    return buildList {
        if (keyNotSaved) add(ForgetNotice.KeyNotSaved)
        if (storeNotDropped) add(ForgetNotice.DataNotCleared)
        if (rest.contains('⚠') || (!storeNotDropped && !keyNotSaved)) add(ForgetNotice.MayStillBeListed)
    }
}

/** Phrases of `ForgetThisDeviceImplementation`'s outcome, pinned there by `ForgetThisDeviceOutcomeMarkerTest`. */
internal const val FORGET_OUTCOME_NOT_FINISHED = "not finished"
internal const val FORGET_OUTCOME_STORE_NOT_DROPPED = "local store NOT dropped"
internal const val FORGET_OUTCOME_KEY_NOT_SAVED = "new device key NOT saved"

@Composable
private fun ForgetNoticeBanner(notice: ForgetNotice) {
    when (notice) {
        ForgetNotice.MayStillBeListed -> Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(imageVector = Icons.Default.Info, contentDescription = null)
                Text(
                    text = stringResource(Res.string.welcome_forget_still_listed),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ForgetNotice.NotFinished -> ErrorBanner(
            text = stringResource(Res.string.welcome_forget_not_finished),
            icon = Icons.Default.Warning,
        )
        ForgetNotice.DataNotCleared -> ErrorBanner(
            text = stringResource(Res.string.welcome_forget_data_not_cleared),
            icon = Icons.Default.Warning,
        )
        ForgetNotice.KeyNotSaved -> ErrorBanner(
            text = stringResource(Res.string.welcome_forget_key_not_saved),
            icon = Icons.Default.Warning,
        )
    }
}
