package app.tenshu.home.data

import kotlinx.coroutines.flow.Flow

/**
 * Plain-model projection of a hub-authored `HubStatus` row (hub proposal §13
 * H4) — everything Home's storage tile needs, decoupled from the schema types
 * so the repository (and its tests) never touch a `TContext`.
 */
data class HubStatusInfo(
    val deviceId: String,
    val name: String,
    /** Free bytes on the hub data dir's filesystem; `0` with a zero total = unknown. */
    val diskFreeBytes: Long,
    /** Total bytes of that filesystem; `0` = unknown (pre-v12 hub or probe failure). */
    val diskTotalBytes: Long,
    val replicaFileCount: Long,
    val replicaByteCount: Long,
    /** Epoch ms of the hub's last completed backup pass; `0` = none. */
    val lastBackupPassAtEpochMs: Long,
    /** Epoch ms (hub clock) at which the hub authored the snapshot. */
    val freshAsOfEpochMs: Long,
) {
    /** True when the row carries usable disk figures ("never fake numbers as real"). */
    val hasDiskFigures: Boolean get() = diskTotalBytes > 0
}

/**
 * Home's read seam over the hub-management client surface. The real
 * implementation ([ClientHubStatusSource]) rides `hubStatusesFlow()` — a
 * local reactive read of the synced rows; a fake feeds repository tests.
 *
 * Contract: emits the currently-known statuses and re-emits on change. An
 * unreachable proxy, a failed read, or a realm with no hub all surface as an
 * **empty list** — never as an error — so consumers fall back to their mock
 * state exactly as if no hub existed.
 */
interface HubStatusSource {
    fun statuses(): Flow<List<HubStatusInfo>>
}
