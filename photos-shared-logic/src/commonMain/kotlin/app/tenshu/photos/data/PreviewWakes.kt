package app.tenshu.photos.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold
import org.tenshu.core.common.TenshuClient
import org.tenshu.sync.SyncHealthStatus
import org.tenshu.sync.client.anyCatchUpHeartbeat
import org.tenshu.sync.client.syncService

/**
 * The moments a grey tile should ask for its thumbnail again **now**, rather than at its backoff's
 * next step (`suite-sync-status.md` SS-3).
 *
 * ⚠⚠ Why: a tile with no bytes backs off to one ask a minute (`PhotoTile`), which is right while
 * nothing is changing and wrong the moment something does. After the 2026-11-12 outage a phone's
 * sync came back and its grid stayed grey for up to a minute. The two moments that make a missing
 * thumbnail likely to be fetchable:
 *
 * - **Sync is reachable again**: connectivity moves from anything else to `online` (or
 *   `relay_retrying`, which Drive also counts as online — the relay answers). The first value is
 *   never a wake. ⚠ "Not known yet" (`null`) counts as not reachable, so a launch whose first row
 *   arrives after an empty one wakes once: one early retry, harmless.
 * - **A catch-up finished**: `catchingUp` falls from `true` to `false`. The rows just pulled are
 *   the ones whose thumbnails are now worth asking for.
 *
 * Pure over its two inputs so it can be tested without a client.
 */
fun previewWakes(connectivity: Flow<String?>, catchingUp: Flow<Boolean>): Flow<Unit> = merge(
    connectivity.map { it.isReachable() }.risingEdges(),
    catchingUp.map { !it }.risingEdges(),
)

/** [previewWakes] over Control's own rows — the ones a Compose app reads across IPC. */
fun controlPreviewWakes(client: TenshuClient): Flow<Unit> = previewWakes(
    connectivity = syncConnectivity(client),
    catchingUp = controlCatchingUp(client),
)

/**
 * Whether Control is pulling data in, through its catch-up heartbeat (`suite-sync-status.md`
 * SS-D8) — what the "Syncing…" indicator and the tile wakes read.
 *
 * ⚠ The heartbeat, not Control's plain `catchUp` row: a Control killed mid-pull leaves that row
 * `true` until it next starts, and a stale beat is discounted as time passes.
 */
fun controlCatchingUp(client: TenshuClient): Flow<Boolean> = client.syncService.anyCatchUpHeartbeat()

private fun String?.isReachable(): Boolean =
    this == SyncHealthStatus.CONNECTIVITY_ONLINE || this == SyncHealthStatus.CONNECTIVITY_RELAY_RETRYING

/** One `Unit` each time this goes from `false` to `true`; never for its first value. */
private fun Flow<Boolean>.risingEdges(): Flow<Unit> =
    distinctUntilChanged()
        .runningFold<Boolean, Pair<Boolean?, Boolean?>>(null to null) { (_, last), now -> last to now }
        .mapNotNull { (before, now) -> if (before == false && now == true) Unit else null }
