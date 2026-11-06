package org.tenshu.file.proxy

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which deletes strand bytes on *this* device — the input to `OrphanedBytesSweep`.
 *
 * ⚠ Recording the wrong deletes is not harmless: every candidate is a path the sweep will later
 * consider deleting plaintext at, so a recorder that fires on local deletes or on non-file rows is
 * feeding the one pass that can destroy a user's bytes.
 */
class IngestedDeleteRecorderTest {

    private val context = createTestContext(createCoreTestRegistry())

    private class Recorded : OrphanedBytesRecorder {
        val paths = mutableListOf<String>()
        var explode = false
        override fun record(path: String) {
            if (explode) error("candidate list unwritable")
            paths += path
        }
    }

    private fun fileId(path: String) =
        DataId.StringDataId.create(context = context, type = File.ID, keyValue = path)

    /**
     * ⭐ The case the whole track exists for: a delete that arrived from another device removes the
     * row here and leaves the bytes.
     */
    @Test
    fun anIngestedFileDelete_isRecorded() {
        val recorded = Recorded()
        IngestedDeleteRecorder(recorded).onDataDeleted(fileId("/photo.jpg"), WriteOrigin.SYNC_INGEST)
        assertEquals(listOf("/photo.jpg"), recorded.paths)
    }

    /**
     * ⚠⚠ A local delete already dropped its own bytes, and a local move renamed them before retiring
     * the old row. Recording those would hand the sweep paths that hold nothing — noise at best, and
     * at worst a candidate that outlives a later re-creation at the same path.
     */
    @Test
    fun aLocalDelete_isNotRecorded() {
        val recorded = Recorded()
        val observer = IngestedDeleteRecorder(recorded)
        observer.onDataDeleted(fileId("/photo.jpg"), WriteOrigin.USER)
        observer.onDataDeleted(fileId("/other.jpg"), WriteOrigin.DERIVED)
        assertTrue(recorded.paths.isEmpty(), "only an ingested delete strands bytes here")
    }

    /** Non-`File` rows own no plaintext; recording them would be a path the sweep cannot interpret. */
    @Test
    fun aNonFileDelete_isNotRecorded() {
        val recorded = Recorded()
        val other = DataId.StringDataId.create(
            context = context,
            type = Folder.ID,
            keyValue = "not-a-file-row",
        )
        IngestedDeleteRecorder(recorded).onDataDeleted(other, WriteOrigin.SYNC_INGEST)
        assertTrue(recorded.paths.isEmpty())
    }

    /**
     * ⚠⚠ This runs inside sync ingest's observer fanout, where an escaping throw takes the whole
     * tick with it — the lesson `DeletedFolderSubtreeReaper` already carries. A lost note costs
     * disk; a failed ingest costs sync.
     */
    @Test
    fun aFailingRecorder_cannotBreakIngest() {
        val recorded = Recorded().apply { explode = true }
        IngestedDeleteRecorder(recorded).onDataDeleted(fileId("/photo.jpg"), WriteOrigin.SYNC_INGEST)
        // Reaching here at all is the assertion.
        assertTrue(recorded.paths.isEmpty())
    }
}
