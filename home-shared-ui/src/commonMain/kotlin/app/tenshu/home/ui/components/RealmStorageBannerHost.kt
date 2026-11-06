package app.tenshu.home.ui.components

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
 * Home summarises what the other apps hold, so an unfunded realm means the rows it shows are
 * not reaching this user's other devices — and a summary is exactly where that is hardest to spot.
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
 * Home's binding of the shared banner.
 *
 * ⚠ **Literal strings, not resources:** this copy is not in the app's `strings.xml`, so it is
 * not translated.
 *
 * ⚠ The shell places this inside its scaffold's content, where the status-bar inset is already
 * applied; a hand-rolled inset draws the banner under the status bar.
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
