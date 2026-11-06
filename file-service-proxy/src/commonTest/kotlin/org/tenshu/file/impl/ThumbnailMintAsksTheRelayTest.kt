package org.tenshu.file.impl

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.entity.DataId
import org.tenshu.file.test.createFileProxy
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A rung minted on VIEW (or by backfill) asks the relay first; only an UPLOAD's fresh rung emits
 * blind.
 *
 * ⚠⚠ Mint-on-view renders a rung missing LOCALLY — which says nothing about the relay, where another
 * device (or this one before a restart) may already have published it. A blind emit re-uploads it under
 * a fresh CEK and leaves the losing chunk set on the relay undescribed for ever.
 */
class ThumbnailMintAsksTheRelayTest {

    private class Recording : FileContentSyncEmitter {
        val emits = mutableListOf<String>()
        val ensures = mutableListOf<Pair<String, String>>()
        override suspend fun emitFileContent(targetEntityId: DataId, propertyId: String, filePath: String): Boolean {
            emits += filePath; return true
        }
        override suspend fun shareContentToRealm(contentId: String, targetRealmOwnerId: String, targetPropertyId: String) = true
        override suspend fun ensureContentOnRelay(targetEntityId: DataId, propertyId: String, filePath: String, contentId: String): Boolean {
            ensures += filePath to contentId; return true
        }
    }

    @Test
    fun aViewMintAsksTheRelay_anUploadMintEmits() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val emitter = Recording()
        val minter = ThumbnailClassMinter(
            fileDatabase = InMemoryFileDatabase(),
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = emitter },
        )
        val row = DataId.StringDataId.create(proxy.tContext, org.tenshu.core.file.File.ID, "/a.jpg")
        val cls = "large"

        minter.store(proxy.executionContext, row, "cid-1", "p", cls, byteArrayOf(1, 2, 3))
        assertEquals(0, emitter.emits.size, "a view/backfill mint never emits blind")
        assertEquals(
            listOf(org.tenshu.file.proxy.FileBytesPropertyWriter.derivedThumbnailContentId("cid-1", cls)),
            emitter.ensures.map { it.second },
            "it asks the relay about the RUNG's own content id",
        )

        minter.store(proxy.executionContext, row, "cid-2", "p", cls, byteArrayOf(4, 5, 6), freshContent = true)
        assertEquals(1, emitter.emits.size, "an upload's rung is fresh by construction, so it emits directly")
    }
}
