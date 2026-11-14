package app.tenshu.drive.domain

import app.tenshu.drive.data.SchemaFenceModel
import app.tenshu.drive.data.syncHealthModelOf
import app.tenshu.ingest.backup.StorageStatusModel
import app.tenshu.reachability.SuiteSyncProblem
import app.tenshu.reachability.UnreachableReason
import app.tenshu.reachability.evaluateSuiteSyncProblem
import org.tenshu.storage.common.RealmStorageState
import org.tenshu.sync.SyncHealthStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ⚠⚠ Drive's banner and the strip every other app shows (`suite-sync-status.md` SS-1) must name the
 * same account-wide problem for the same inputs, so a rank changed in one cannot silently disagree
 * with the other. Drive layers its own transfer states (uploading, waiting for Wi-Fi, backup) below
 * these; those are not compared, since the other apps have nothing to transfer.
 *
 * Drive's side goes through [syncHealthModelOf], the mapping its repository uses, so the test
 * compares the two decisions over the same wire value rather than over hand-built models.
 */
class SuiteSyncOrderMatchesDriveTest {

    private val connectivities = listOf(
        SyncHealthStatus.CONNECTIVITY_ONLINE,
        SyncHealthStatus.CONNECTIVITY_OFFLINE,
        SyncHealthStatus.CONNECTIVITY_LOCKED,
        SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED,
        SyncHealthStatus.CONNECTIVITY_NEEDS_ATTENTION,
        SyncHealthStatus.CONNECTIVITY_STORAGE_FULL,
        SyncHealthStatus.CONNECTIVITY_DEFERRED,
        SyncHealthStatus.CONNECTIVITY_RELAY_RETRYING,
    )

    /** (has a plan, used, limit) — fine, no plan, full. */
    private val storages = listOf(
        Triple(true, 1L, 100L),
        Triple(false, 0L, 0L),
        Triple(true, 100L, 100L),
    )

    @Test
    fun everyAccountWideProblem_isNamedTheSameWay() {
        for (connectivity in connectivities) {
            for ((hasPlan, used, limit) in storages) {
                for (fenced in listOf(false, true)) {
                    val drive = SyncBanner.evaluate(
                        storage = StorageStatusModel(
                            hasStorage = hasPlan,
                            usedBytes = used,
                            limitBytes = limit,
                            relayRejected = connectivity == SyncHealthStatus.CONNECTIVITY_STORAGE_FULL,
                        ),
                        health = syncHealthModelOf(connectivity),
                        fence = SchemaFenceModel(fenced = fenced),
                    )
                    val shared = evaluateSuiteSyncProblem(
                        reason = if (fenced) UnreachableReason.Fenced(update = null) else null,
                        connectivity = connectivity,
                        storage = realmStorage(hasPlan, used, limit),
                    )
                    assertEquals(
                        kindOf(drive),
                        kindOf(shared),
                        "connectivity=$connectivity plan=$hasPlan used=$used/$limit fenced=$fenced",
                    )
                }
            }
        }
    }

    private fun realmStorage(hasPlan: Boolean, used: Long, limit: Long): RealmStorageState = when {
        !hasPlan -> RealmStorageState.NoPlan
        used >= limit -> RealmStorageState.Full(used, limit)
        else -> RealmStorageState.Ok
    }

    private fun kindOf(state: SyncBannerState?): String = when (state) {
        SyncBannerState.Locked -> "locked"
        SyncBannerState.DeviceUnrecognized -> "deviceUnrecognized"
        is SyncBannerState.NeedsUpdate -> "fenced"
        SyncBannerState.LocalOnly -> "noPlan"
        is SyncBannerState.StorageFull -> "storageFull"
        SyncBannerState.NeedsAttention -> "needsAttention"
        SyncBannerState.Offline -> "offline"
        // Drive's own transfer states, and nothing at all.
        else -> "none"
    }

    private fun kindOf(problem: SuiteSyncProblem): String = when (problem) {
        SuiteSyncProblem.Locked -> "locked"
        SuiteSyncProblem.DeviceUnrecognized -> "deviceUnrecognized"
        is SuiteSyncProblem.Control ->
            if (problem.reason is UnreachableReason.Fenced) "fenced" else "control"
        SuiteSyncProblem.NoPlan -> "noPlan"
        is SuiteSyncProblem.StorageFull -> "storageFull"
        SuiteSyncProblem.NeedsAttention -> "needsAttention"
        SuiteSyncProblem.Offline -> "offline"
        SuiteSyncProblem.None -> "none"
    }
}
