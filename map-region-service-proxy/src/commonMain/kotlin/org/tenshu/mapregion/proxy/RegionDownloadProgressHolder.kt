package org.tenshu.mapregion.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A device-local progress snapshot for one in-flight region download. */
data class RegionDownloadProgressSnapshot(
    val regionId: String,
    val writtenBytes: Long,
    val totalBytes: Long,
)

/**
 * Device-local, in-memory progress for in-flight region downloads (`maps-region-delivery.md` §7.1).
 * [DefaultRegionDownloader] ticks it per streamed chunk; the `downloadProgress` stream executor observes
 * it and pushes each change to the subscribed app over IPC.
 *
 * Deliberately **not** the synced realm / preference-service (like [DefaultRegionStore]'s index): region
 * install progress is a coarse-location signal, and writing it to the synced store would push a download
 * bar to the user's other devices (D5). An entry exists only while its download is in flight — [start]
 * adds it, [update] advances it, [finish] removes it, and that present→absent transition is the stream's
 * natural completion signal.
 */
class RegionDownloadProgressHolder {

    private val _progress = MutableStateFlow<Map<String, RegionDownloadProgressSnapshot>>(emptyMap())

    /** Per-region download progress; keyed by regionId, an entry present only while downloading. */
    val progress: StateFlow<Map<String, RegionDownloadProgressSnapshot>> = _progress.asStateFlow()

    /**
     * Begin tracking [regionId] at [writtenBytes] of [totalBytes] (the verified catalog's advertised
     * size).
     *
     * ⚠⚠ [writtenBytes] is not decoration: on a **resumed** download it must be the already-staged
     * byte count, because this is the FIRST snapshot a subscriber sees and a `0` here is a bar that
     * visibly snaps to empty and re-climbs ground the user already paid for. Publishing 0 and
     * correcting it a step later is not equivalent — the 0 goes out on the wire.
     */
    fun start(regionId: String, totalBytes: Long, writtenBytes: Long = 0L) {
        _progress.update {
            it + (regionId to RegionDownloadProgressSnapshot(regionId, writtenBytes, totalBytes))
        }
    }

    /** Advance [regionId]'s written-byte count. No-op if it isn't being tracked (already finished). */
    fun update(regionId: String, writtenBytes: Long) {
        _progress.update { current ->
            val snapshot = current[regionId] ?: return@update current
            current + (regionId to snapshot.copy(writtenBytes = writtenBytes))
        }
    }

    /** Stop tracking [regionId] — the download finished (success or failure). Ends its stream. */
    fun finish(regionId: String) {
        _progress.update { it - regionId }
    }
}
