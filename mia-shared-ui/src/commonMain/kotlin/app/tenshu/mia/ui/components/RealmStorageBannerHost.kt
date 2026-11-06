package app.tenshu.mia.ui.components

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
 * ⚠ `StorageSyncGate` holds the personal realm's push at the scheduler until a plan is bound.
 * Mia answers from data the other apps sync, so an unfunded realm quietly narrows what it can
 * ever be right about, without any answer admitting it was incomplete.
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
 * Mia's binding of the shared banner.
 *
 * ⚠ **Literal strings, not resources.** Mia keeps this copy local, so introducing
 * a resource bundle for two sentences would be a localisation decision smuggled in as a side effect
 * of a banner. It moves into resources with the rest of the app's copy.
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
