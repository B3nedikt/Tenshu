package app.tenshu.photos.data

import app.tenshu.photos.ui.screens.collections.CurrentRealm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.get
import org.tenshu.sync.SyncHealthStatus
import org.tenshu.sync.client.syncHealth
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus

/**
 * The device's realm as Control reports it — the Collections snapshot's key (§36 step 2).
 *
 * ⚠ `ownerId` (`getSyncStatus`) is the realm key's fingerprint and the PERSISTED owner slot
 * (`SyncManager.residentOwnerId`): every identity-destroying path on Android and desktop — Start
 * fresh, *Forget this device* (sign-out), a superseded realm — clears it, and a different realm has a
 * different one. Revocation does NOT clear it: a device revoked from another of the user's devices
 * keeps its ownerId and learns of it from the relay, as `syncHealth`'s `device_unrecognized`.
 * ⚠⚠ A failed read THROWS (`get()`), never answers `null`: "Control did not answer" must not read as
 * "this device has no realm" and drop the snapshot.
 */
fun controlCurrentRealm(client: TenshuClient): CurrentRealm = healthCheckedRealm(
    ownerId = { client.syncService.getSyncStatus().get().ownerId?.get() },
    connectivity = client.syncService.syncHealth().map { it?.connectivity?.get() },
)

/**
 * A [CurrentRealm] over the owner read and the sync-health connectivity stream.
 *
 * ⚠⚠ **Revocation arrives LATE, so it is observed, not sampled**. A fresh Control
 * process first reports `null` or the proxy's optimistic `online` — `device_unrecognized` only after
 * a relay call has been refused — and the row carries no "confirmed by the relay" marker to wait
 * for. Waiting for one would make the snapshot useless offline, so [CurrentRealm.ownerId] answers
 * from what health says NOW (a revocation already known is honoured at once), and
 * [CurrentRealm.teardowns] keeps watching for the rest of the view model's life: a later
 * `device_unrecognized` purges the file and takes the cards off the screen.
 */
fun healthCheckedRealm(
    ownerId: suspend () -> String?,
    connectivity: Flow<String?>,
    healthWaitMs: Long = HEALTH_WAIT_MS,
): CurrentRealm = object : CurrentRealm {
    override suspend fun ownerId(): String? {
        val id = ownerId.invoke()
        // ⚠ Bounded: the row is `null` until the proxy has derived one, and a status we cannot get
        // promptly is no evidence of a teardown — [teardowns] still catches it later.
        val now = if (id == null) null else withTimeoutOrNull(healthWaitMs) { connectivity.first() }
        return realmFrom(id, now)
    }

    override fun teardowns(): Flow<Unit> =
        connectivity.filter { it == SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED }.map { }

    // ⚠ A LATE lock — a sealed Control restart reports null or `online` first. It
    // hides the cards (this session cannot read the realm) but keeps the file: not a teardown.
    override fun invalidations(): Flow<Unit> =
        connectivity.filter { it == SyncHealthStatus.CONNECTIVITY_LOCKED }.map { }

    // ⚠ A reset, sign-out or re-pairing moves Control's sync health; each move is a cue to ask the
    // owner again. Not the only one — the view model also asks after every refresh.
    override fun changes(): Flow<Unit> = connectivity.distinctUntilChanged().map { }
}

/**
 * The [CurrentRealm] answer from Control's two signals (§36 step 2).
 *
 * - no `ownerId` → `null`: no realm — reset or sign-out, and the snapshot is purged;
 * - `device_unrecognized` → `null`: this device was revoked (or the relay forgot it) — purged too;
 * - `locked` → THROWS (unknown): the realm is sealed and this session cannot read it, so its snapshot
 *   is not drawn — ⚠ but not purged either: locking is not an identity teardown;
 * - otherwise the `ownerId`.
 */
fun realmFrom(ownerId: String?, connectivity: String?): String? {
    if (ownerId.isNullOrBlank()) return null
    return when (connectivity) {
        SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED -> null
        SyncHealthStatus.CONNECTIVITY_LOCKED -> throw IllegalStateException("realm is locked")
        else -> ownerId
    }
}

private const val HEALTH_WAIT_MS = 2_000L
