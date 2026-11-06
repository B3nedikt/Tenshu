package org.tenshu.file.proxy

import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.Data
import org.tenshu.core.file.File

/**
 * Notes the paths an **ingested** delete strands plaintext at.
 *
 * ⭐ The path needs no lookup: a `File`'s `DataId` *is* its path
 * (`StringDataId(File.ID, "/photo.jpg")`), so this can record from the id alone — which is what
 * lets it be an observer rather than something threaded through the tombstone path.
 */
fun interface OrphanedBytesRecorder {
    /** ⚠ Best-effort. Losing a note costs disk; failing an ingest costs sync. */
    fun record(path: String)
}

/**
 * Records a candidate whenever a delete arrives **from another device**.
 *
 * ⚠⚠ `SYNC_INGEST` only, and that is the whole point. A *local* delete already drops its own bytes
 * (`DeleteFileImplementation`), and a local move renames them before retiring the old row — so on
 * this device those paths hold nothing and a candidate would be noise. What arrives over sync is the
 * case nothing handles: the row goes, the bytes stay.
 *
 * ⚠ A **move** made elsewhere reaches here identically, because `DataIdMigrator` retires the old
 * path with a real tombstone. That is not an edge case to tolerate — it is most of the traffic this
 * will ever see.
 */
internal class IngestedDeleteRecorder(
    private val recorder: OrphanedBytesRecorder,
) : DataObserver {

    override fun onDataSaved(data: Data, origin: WriteOrigin) = Unit

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        if (origin != WriteOrigin.SYNC_INGEST) return
        val fileId = dataId as? DataId.StringDataId ?: return
        if (fileId.type.get() != File.ID) return
        val path = runCatching { fileId.keyValue.get() }.getOrNull() ?: return
        if (path.isBlank()) return
        // ⚠ Runs inside sync ingest's observer fanout, where an escaping throw takes down the whole
        // tick — the lesson `DeletedFolderSubtreeReaper` already carries. A lost note is disk; a
        // failed ingest is sync.
        runCatching { recorder.record(path) }
    }
}
