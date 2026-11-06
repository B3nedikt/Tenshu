package app.tenshu.photos.ui.components

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
 * ⚠⚠ **Photos has the strongest claim of any app to say this out loud.** It exists to put photos
 * somewhere safe, so an unfunded realm means the one thing it is *for* is silently not happening:
 * `StorageSyncGate` holds the personal realm's push at the scheduler until a plan is bound, and
 * nothing in the feed looks any different. A user scrolling a full library has no way to tell it
 * lives only on this phone.
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
 * Photos' binding of the shared banner.
 *
 * ⚠ **Literal strings, not resources.** Photos ships no `composeResources` at all — every label in
 * this app is a literal (`Text("Photos")`, `"Search"`) — so introducing a resource bundle for two
 * sentences would be a localisation decision smuggled in as a side effect of a banner. When Photos
 * is localised these move with the rest.
 *
 * ⚠ Hung off the shell's `topBar` rather than wrapped around the content: `Scaffold` then owns the
 * status-bar inset and grows the content padding by the banner's height on its own. The
 * hand-rolled alternative puts the banner under the clock.
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
