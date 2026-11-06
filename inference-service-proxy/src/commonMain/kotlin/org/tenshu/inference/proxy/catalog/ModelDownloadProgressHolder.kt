package org.tenshu.inference.proxy.catalog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A device-local progress snapshot for one in-flight model download. */
data class ModelDownloadProgressSnapshot(
    val modelId: String,
    val writtenBytes: Long,
    val totalBytes: Long,
)

/**
 * Device-local, in-memory progress for in-flight model downloads — [DefaultModelDownloader] ticks
 * it per streamed chunk, and IN-2c's screen is what will observe it.
 *
 * ⚠ Deliberately **not** the synced realm or the preference service, for the same reason
 * [DefaultModelStore]'s index is not: a model is device-local (IN-D4), and a download bar pushed to
 * the user's other devices would be describing work none of them is doing.
 *
 * ⚠ Built in this slice rather than with the screen that displays it because the resume subtlety
 * below lives *here*, entangled with the downloader — and retrofitting it under a UI is how the
 * region twin acquired a snap-to-zero bug whose own regression test could not see it
 * (`maps-region-delivery.md` §7.1, `tech-debt.md` row 17).
 */
class ModelDownloadProgressHolder {

    private val _progress = MutableStateFlow<Map<String, ModelDownloadProgressSnapshot>>(emptyMap())

    /** Per-model download progress; keyed by modelId, an entry present only while downloading. */
    val progress: StateFlow<Map<String, ModelDownloadProgressSnapshot>> = _progress.asStateFlow()

    /**
     * Begin tracking [modelId] at [writtenBytes] of [totalBytes] (the signed catalog's figure).
     *
     * ⚠⚠ [writtenBytes] is not decoration: on a **resumed** download it must be the already-staged
     * byte count, because this is the FIRST snapshot a subscriber sees, and a `0` here is a bar
     * that visibly snaps to empty and re-climbs ground the user already paid for. Publishing 0 and
     * correcting it one step later is not equivalent — the 0 goes out on the wire.
     */
    fun start(modelId: String, totalBytes: Long, writtenBytes: Long = 0L) {
        _progress.update {
            it + (modelId to ModelDownloadProgressSnapshot(modelId, writtenBytes, totalBytes))
        }
    }

    /** Advance [modelId]'s written-byte count. No-op if it isn't tracked (already finished). */
    fun update(modelId: String, writtenBytes: Long) {
        _progress.update { current ->
            val snapshot = current[modelId] ?: return@update current
            current + (modelId to snapshot.copy(writtenBytes = writtenBytes))
        }
    }

    /** Stop tracking [modelId] — the download finished, successfully or not. */
    fun finish(modelId: String) {
        _progress.update { it - modelId }
    }
}
