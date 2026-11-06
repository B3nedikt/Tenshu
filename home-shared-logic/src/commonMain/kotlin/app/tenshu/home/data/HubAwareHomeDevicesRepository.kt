package app.tenshu.home.data

import app.tenshu.home.model.Device
import app.tenshu.home.model.DeviceKind
import app.tenshu.home.model.DeviceState
import app.tenshu.home.model.Room
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlin.time.Clock

/**
 * The Devices repository with the storage tile driven by REAL hub data
 * (docs/proposals/home-and-mia-apps.md §3 M1 tile; hub proposal §13 H4).
 *
 * Decorates the existing repository (the mock in this phase): rooms and
 * add/remove/reorder pass straight through, while [devices] overlays the
 * freshest hub-authored `HubStatus` snapshot onto the household:
 *
 *  - the first [DeviceKind.Storage] row gets the hub's real disk figures
 *    (used = total − free) plus a [DeviceState.LiveHubStorage] block (replica
 *    figures, last backup pass, "as of" + staleness);
 *  - the first [DeviceKind.Hub] row's connectivity reflects the same
 *    staleness signal (online = the row is fresh).
 *
 * **Fallback semantics** ("mock stays the honest default; never render fake
 * numbers as real"): no source emission yet, no `HubStatus` row at all, or a
 * row without usable disk figures (`diskTotalBytes <= 0`, e.g. a pre-v12 hub)
 * → the delegate's devices pass through untouched, exactly as today.
 *
 * **Staleness**: the hub refreshes its row at least once per heartbeat
 * (1 h — `HubManagementAgent.DEFAULT_HEARTBEAT_MS`); a snapshot older than
 * [staleAfterMs] (double that, by default) marks the hub offline/stale. A
 * [recheckIntervalMs] ticker re-derives the flag so it flips even while no
 * new row arrives; emissions are de-duplicated, so an unchanged derivation
 * costs no downstream update.
 */
class HubAwareHomeDevicesRepository(
    private val delegate: HomeDevicesRepository,
    private val source: HubStatusSource,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
    private val recheckIntervalMs: Long = DEFAULT_RECHECK_INTERVAL_MS,
) : HomeDevicesRepository {

    override fun rooms(): Flow<List<Room>> = delegate.rooms()

    /**
     * ⚠⚠ **Synthesised, not overlaid, as of 2026-08-26.** This used to find the mock household's
     * Hub and Storage rows *by kind* and replace their state with live figures — so the tiles
     * existed because a seeded row existed, and a hub that was genuinely reporting still borrowed
     * its identity from fake data. Retiring the mock therefore had to build them instead of
     * decorating them, and the status row already carries everything they need.
     *
     * ⚠ No status ⇒ **no tiles**. There is no hub to describe, and a Hub tile rendered from
     * nothing is the defect this whole change is about.
     */
    override fun devices(): Flow<List<Device>> =
        combine(delegate.devices(), liveStatus()) { devices, status ->
            // ⚠⚠ This class **owns** the Hub and Storage tiles now, so any the delegate carries are
            // dropped rather than merged. Production's delegate is empty, but a fixture that seeds
            // its own hub row would otherwise render two — and the duplicate would look like two
            // hubs rather than a bug.
            val rest = devices.filterNot { it.kind == DeviceKind.Hub || it.kind == DeviceKind.Storage }
            if (status == null) rest else hubRows(status) + rest
        }.distinctUntilChanged()

    /**
     * The Hub and Storage tiles for the freshest reporting hub.
     *
     * ⚠ Ids are derived from the hub's own name rather than the mock's `device-hub` constants, so
     * two hubs cannot collide on one row — the mock could only ever describe one household.
     */
    private fun hubRows(stamped: StampedStatus): List<Device> {
        val status = stamped.status
        val hubId = "hub:${status.name}"
        return listOf(
            Device(
                id = hubId,
                name = status.name,
                roomId = null,
                kind = DeviceKind.Hub,
                state = DeviceState.Connectivity(online = !stamped.stale),
                sortIndex = 0,
            ),
            Device(
                id = "$hubId:storage",
                name = "Hub storage",
                roomId = null,
                kind = DeviceKind.Storage,
                state = DeviceState.Storage(
                    usedBytes = (status.diskTotalBytes - status.diskFreeBytes).coerceAtLeast(0),
                    totalBytes = status.diskTotalBytes,
                    live = DeviceState.LiveHubStorage(
                        hubName = status.name,
                        replicaFileCount = status.replicaFileCount,
                        replicaByteCount = status.replicaByteCount,
                        lastBackupPassAtEpochMs = status.lastBackupPassAtEpochMs,
                        freshAsOfEpochMs = status.freshAsOfEpochMs,
                        stale = stamped.stale,
                    ),
                ),
                sortIndex = 1,
            ),
        )
    }

    /**
     * The freshest usable snapshot with a live staleness derivation, `null`
     * while there is none (the fall-back-to-mock signal). Starts at `null` so
     * the UI renders (mock) immediately rather than waiting on the source.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun liveStatus(): Flow<StampedStatus?> =
        source.statuses()
            .map { statuses ->
                statuses.filter { it.hasDiskFigures }.maxByOrNull { it.freshAsOfEpochMs }
            }
            .onStart { emit(null) }
            .flatMapLatest { status ->
                if (status == null) {
                    flow { emit(null) }
                } else {
                    flow {
                        while (true) {
                            emit(StampedStatus(status, stale = isStale(status)))
                            delay(recheckIntervalMs)
                        }
                    }
                }
            }
            .distinctUntilChanged()

    private fun isStale(status: HubStatusInfo): Boolean =
        nowMs() - status.freshAsOfEpochMs > staleAfterMs

    private data class StampedStatus(val status: HubStatusInfo, val stale: Boolean)

    companion object {
        /**
         * Stale after twice the hub's publish heartbeat (1 h) — one missed
         * heartbeat is tolerated (sync latency, a restart), two means the hub
         * is genuinely not reporting.
         */
        const val DEFAULT_STALE_AFTER_MS = 2 * 60L * 60 * 1000

        /** How often the staleness flag is re-derived without new rows (1 min). */
        const val DEFAULT_RECHECK_INTERVAL_MS = 60L * 1000
    }
}
