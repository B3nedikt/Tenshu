package app.tenshu.calendar.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.tenshu.ui.storage.RealmStorageBanner
import org.tenshu.storage.common.RealmStorageState
import org.tenshu.storage.common.evaluateRealmStorage
import org.tenshu.storage.common.formatStorageBytes
import app.tenshu.ui.storage.rememberControlServicesLauncher
import org.koin.compose.koinInject
import org.tenshu.core.common.TenshuClient
import org.tenshu.storage.client.status
import org.tenshu.storage.client.storageService

/**
 * The realm's storage state (`drive-synced-folders.md` §8b.5c).
 *
 * ⚠ `StorageSyncGate` holds the personal realm's push at the scheduler until a plan is bound, so an
 * unfunded realm means events created here never reach the user's other devices — and a calendar
 * that silently does not sync is worse than most, because the whole point of an appointment is that
 * it is there when you look somewhere else.
 */
@Composable
fun rememberRealmStorageState(client: TenshuClient = koinInject()): RealmStorageState {
    val status by remember(client) { client.storageService.status() }.collectAsState(null)
    return evaluateRealmStorage(
        hasStorage = status?.hasStorage?.get(),
        usedBytes = status?.usedBytes?.get() ?: 0L,
        limitBytes = status?.limitBytes?.get() ?: 0L,
    )
}

/**
 * Calendar's binding of the shared banner.
 *
 * ⚠ **Literal strings, not resources.** Calendar ships no `composeResources` at all, so introducing
 * a resource bundle for two sentences would be a localisation decision smuggled in as a side effect
 * of a banner. When Calendar is localised these move with the rest.
 *
 * ⚠ Hung off the shell's `topBar` rather than wrapped around the content: `Scaffold` then owns the
 * status-bar inset and grows the content padding by the banner's height on its own; a
 * hand-rolled inset puts the banner under the clock.
 */
@Composable
fun RealmStorageBannerHost(
    state: RealmStorageState,
    modifier: Modifier = Modifier,
) {
    if (state is RealmStorageState.Ok) return

    val manage = rememberControlServicesLauncher()
    RealmStorageBanner(
        state = state,
        text = when (state) {
            is RealmStorageState.Full ->
                "Storage full — ${formatStorageBytes(state.usedBytes)} of " +
                    "${formatStorageBytes(state.limitBytes)} used. Your data stays on this device " +
                    "and nothing is deleted, but it can't sync until you free up space " +
                    "or get more."
            else ->
                "Not syncing — this device has no storage plan, so your data stays on this device. " +
                    "Set up storage to sync across your devices."
        },
        modifier = modifier,
        actionLabel = manage?.let {
            // ⚠ The button must say what the sentence says — there is nothing to *manage* on a
            // realm with no plan yet.
            if (state is RealmStorageState.Full) "Manage storage" else "Set up storage"
        },
        onManageStorage = manage,
    )
}
