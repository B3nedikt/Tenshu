package app.tenshu.fit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_storage_action
import app.tenshu.fit.shared.resources.fit_storage_setup_action
import app.tenshu.fit.shared.resources.fit_storage_full_text
import app.tenshu.fit.shared.resources.fit_storage_no_plan_text
import app.tenshu.ui.storage.RealmStorageBanner
import org.tenshu.storage.common.RealmStorageState
import org.tenshu.storage.common.evaluateRealmStorage
import org.tenshu.storage.common.formatStorageBytes
import app.tenshu.ui.storage.rememberControlServicesLauncher
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.tenshu.core.common.TenshuClient
import org.tenshu.storage.client.status
import org.tenshu.storage.client.storageService

/**
 * Fit's binding of the shared realm-storage banner (`drive-synced-folders.md` §8b.5c).
 *
 * ⚠⚠ **This is not a Drive concern that Fit borrowed.** `StorageSyncGate` gates the personal
 * realm's push at the *scheduler*, so an unfunded or full realm stops Fit's workouts leaving the
 * device exactly as it stops Drive's photos — and Fit is the worse place for it to be silent,
 * because a user expects a file app to talk about storage and does not expect a fitness app to
 * quietly stop syncing.
 *
 * ⚠ Reads the client directly rather than growing a `FitRepository` method: this is shell-level
 * realm state that no Fit screen consumes, and threading it through the repository would put a
 * storage concern into every fake and test that implements it.
 *
 * ⚠ The action is `null` when there is no route to Control (not installed; desktop). The banner
 * then states the situation without offering a dead button.
 */
/**
 * The realm's storage state, hoisted so the **shell** can see it.
 *
 * ⚠⚠ Separate from [RealmStorageBannerHost] for one measured reason. Fit's shell applies
 * `windowInsetsPadding(statusBars)` *only while a banner is showing* — the banner is the one thing
 * above the screens, so it owns the inset instead of the top bar. When the first device build
 * rendered this banner while `controlReachable` was true, the shell did not know a banner was up
 * and the strip drew **underneath the status bar**, with the clock sitting on top of the text.
 * Whether a banner will render has to be answerable *before* laying out, which a composable that
 * decides internally and returns early cannot do.
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
            is RealmStorageState.Full -> stringResource(
                Res.string.fit_storage_full_text,
                formatStorageBytes(state.usedBytes),
                formatStorageBytes(state.limitBytes),
            )
            else -> stringResource(Res.string.fit_storage_no_plan_text)
        },
        modifier = modifier,
        actionLabel = manage?.let {
            // ⚠ The button must say what the sentence says. "Manage storage" over "Set up storage
            // to sync across your devices" reads as two different instructions, and there is
            // nothing to *manage* on a realm that has no plan yet.
            if (state is RealmStorageState.Full) stringResource(Res.string.fit_storage_action)
            else stringResource(Res.string.fit_storage_setup_action)
        },
        onManageStorage = manage,
    )
}
