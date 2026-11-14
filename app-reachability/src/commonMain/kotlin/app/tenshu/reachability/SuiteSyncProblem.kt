package app.tenshu.reachability

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import org.tenshu.storage.common.RealmStorageState
import org.tenshu.sync.SyncHealthStatus

/**
 * The one problem an app's top strip shows about sync, out of everything that can be wrong at
 * once (`suite-sync-status.md` SS-1). Stringless: each app renders it.
 *
 * ⚠⚠ **One strip, one order, in every app.** The order is Drive's (`SyncBanner.evaluate`), which
 * argues each rank in its own KDoc; `SuiteSyncOrderMatchesDriveTest` in `:drive-shared-logic` pins
 * that the two agree, so they cannot drift.
 */
sealed interface SuiteSyncProblem {

    /** Nothing to say. */
    data object None : SuiteSyncProblem

    /**
     * Control is unreachable, missing, not set up or out of step with this app — the app's own
     * Control banner says which ([reason]). Outranks everything: no other signal can be trusted
     * while Control is not answering.
     */
    data class Control(val reason: UnreachableReason) : SuiteSyncProblem

    /** Control's realm is sealed on this device; nothing syncs until it is unlocked. */
    data object Locked : SuiteSyncProblem

    /**
     * The relay no longer recognises this device (`401`). Nothing syncs, in either direction, until
     * it is paired again — and a joiner cannot repair this by itself.
     */
    data object DeviceUnrecognized : SuiteSyncProblem

    /** No storage plan, so nothing leaves the device. The app's storage banner says so. */
    data object NoPlan : SuiteSyncProblem

    /** The realm's storage is full. The app's storage banner says so. */
    data class StorageFull(val state: RealmStorageState.Full?) : SuiteSyncProblem

    /** The relay is reachable but refuses this device's writes (`402`). */
    data object NeedsAttention : SuiteSyncProblem

    /** The relay could not be reached, for long enough to say so ([withOfflineDelay]). */
    data object Offline : SuiteSyncProblem
}

/**
 * The pure decision (`suite-sync-status.md` §4).
 *
 * @param reason the app's Control verdict; `null` when Control is fine.
 * @param connectivity `SyncHealthStatus.connectivity`, or `null` while not yet known. Pass it
 *   through [withOfflineDelay] first, so a single failed tick does not flash a strip.
 * @param storage the realm's storage state.
 *
 * ⚠⚠ **Every connectivity value is decided explicitly**, never as "not online". Drive's `Offline`
 * is `!isOnline`, which would turn `deferred` — a Wi-Fi-only policy holding Drive's uploads — into
 * *Offline* in an app that uploads nothing. `deferred` and `relay_retrying` describe Drive's file
 * transfers, which Drive keeps reporting itself, so here they are no problem. An unknown value (a
 * newer value than this build knows) is no problem either: a newer Control must not make an older
 * app invent one.
 *
 * ⚠ [UnreachableReason.Fenced] ranks **below** locked and device-unrecognised, as Drive's
 * `NeedsUpdate` does, even though it arrives on the Control verdict: a fenced device still reads
 * and its fence is a slower problem than a device the relay refuses outright.
 */
fun evaluateSuiteSyncProblem(
    reason: UnreachableReason?,
    connectivity: String?,
    storage: RealmStorageState,
): SuiteSyncProblem = when {
    reason != null && reason !is UnreachableReason.Fenced -> SuiteSyncProblem.Control(reason)
    connectivity == SyncHealthStatus.CONNECTIVITY_LOCKED -> SuiteSyncProblem.Locked
    connectivity == SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED -> SuiteSyncProblem.DeviceUnrecognized
    reason is UnreachableReason.Fenced -> SuiteSyncProblem.Control(reason)
    storage is RealmStorageState.NoPlan -> SuiteSyncProblem.NoPlan
    storage is RealmStorageState.Full -> SuiteSyncProblem.StorageFull(storage)
    // The relay's own `507`, reported before the storage row has caught up.
    connectivity == SyncHealthStatus.CONNECTIVITY_STORAGE_FULL -> SuiteSyncProblem.StorageFull(null)
    connectivity == SyncHealthStatus.CONNECTIVITY_NEEDS_ATTENTION -> SuiteSyncProblem.NeedsAttention
    connectivity == SyncHealthStatus.CONNECTIVITY_OFFLINE -> SuiteSyncProblem.Offline
    else -> SuiteSyncProblem.None
}

/**
 * Holds `offline` back until it has lasted [delayMs] (`suite-sync-status.md` SS-D3).
 *
 * ⚠ `offline` is derived from the last relay call, so one failed tick on a flaky network reports
 * it. Drive shows it at once because its user is watching transfers; in every other app a strip
 * that flashes for one tick is noise people learn to ignore. Every other value passes at once, and
 * while `offline` waits the flow says `null` (not yet known), which is no problem.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<String?>.withOfflineDelay(delayMs: Long = SUITE_OFFLINE_DELAY_MS): Flow<String?> =
    // ⚠⚠ Distinct first. The health row is rewritten whenever any of its figures move (a sync tick,
    // a queued change), so the same `offline` arrives again and again; without this each repeat
    // restarted the wait, and a device that stayed offline while the scheduler kept retrying
    // never said so.
    distinctUntilChanged().transformLatest { value ->
        if (value == SyncHealthStatus.CONNECTIVITY_OFFLINE) {
            emit(null)
            delay(delayMs)
        }
        emit(value)
    }

/** 30 s of continuous `offline` before an app other than Drive says so (SS-D3). */
const val SUITE_OFFLINE_DELAY_MS: Long = 30_000L
