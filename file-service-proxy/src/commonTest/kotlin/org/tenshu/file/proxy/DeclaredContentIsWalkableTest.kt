package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data as TData
import org.tenshu.core.TString
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.sync.LiveContentIds
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⭐⭐ **The invariant CR-5 rests on: `content-plane-refcount.md` §9.6, *declared ⇒ walkable*.**
 *
 * §9.5's rule frees a content object only when it is **declared** and no live edge names it, with
 * the client's live walk as the **veto**. A content id the emit declares but the walk cannot name
 * therefore has no veto — so across a recreate (the tombstone drops every edge the holder had, a
 * peer brings the row back) its bytes are freed while the row is live.
 *
 * ⚠⚠ **This was violated when it was written.** A file's thumbnail classes are
 * `Syncable(OnDemand)` with their own **derived** ids
 * (`FileBytesPropertyWriter.derivedThumbnailContentId`), so the emit declares an edge for each,
 * while `ProxyLiveContentIds` walked `File.ContentId` alone. A recreated file kept its bytes and
 * lost its thumbnails.
 *
 * ⚠ Distinct from `OnDemandWriterCompletenessTest`, which pins that every `OnDemand` property has a
 * **writer**. A writer is what *declares*; this is what *vetoes*. Both halves are needed and
 * neither implies the other.
 */
class DeclaredContentIsWalkableTest {

    /**
     * Every property whose writer can resolve a content id — i.e. everything that declares. ⚠ The
     * thumbnail half is read from the rung table, so a new class is covered the day it is added.
     */
    private val declaringProperties = listOf(File.Data.id) + ThumbnailClasses.ALL.map { it.property.id }

    @Test
    fun everyContentIdTheEmitDeclaresIsNamedByTheWalk() = runTest {
        val proxy = createFileProxy()
        proxy.executionContext.executeAsync<TData>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf<String, TData>(
                UploadFile.Params.PARENT_PATH to "/".toTString(),
                UploadFile.Params.FILE_NAME to "photo.jpg".toTString(),
                UploadFile.Params.DATA to "bytes".encodeToByteArray().toTByteArray(proxy.tContext),
            ),
        )

        val writer = FileBytesPropertyWriter(fileDatabase = InMemoryFileDatabase())
        val fileId = DataId.StringDataId.create(proxy.tContext, File.ID, "/photo.jpg")

        // What the emit WOULD declare, asked of the same port the emit asks.
        val declared = declaringProperties.mapNotNull { propertyId ->
            writer.resolveContentId(proxy.executionContext, fileId, propertyId)
        }.toSet()
        assertEquals(
            declaringProperties.size,
            declared.size,
            "precondition: every declaring property must resolve a DISTINCT content id, or two " +
                "would share a manifest address; got $declared",
        )

        val walked = proxy.executionContext.liveContentIds()
        assertTrue(walked is LiveContentIds.Result.Complete, "the walk must finish: $walked")

        val unwalkable = declared - (walked as LiveContentIds.Result.Complete).contentIds
        assertTrue(
            unwalkable.isEmpty(),
            "declared but not walkable, so nothing can veto their reclamation across a recreate " +
                "(content-plane-refcount.md §9.6): $unwalkable",
        )
    }

    @Test
    fun theWalkNamesNothingOnceTheFileIsGone() = runTest {
        // ⚠ The other direction, and it must hold or the veto never lets anything be freed: the
        // derived ids are added per LIVE file, so deleting the file removes every one at once.
        val proxy = createFileProxy()
        proxy.executionContext.executeAsync<TData>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf<String, TData>(
                UploadFile.Params.PARENT_PATH to "/".toTString(),
                UploadFile.Params.FILE_NAME to "photo.jpg".toTString(),
                UploadFile.Params.DATA to "bytes".encodeToByteArray().toTByteArray(proxy.tContext),
            ),
        )
        val live = proxy.executionContext.liveContentIds()
        assertTrue((live as LiveContentIds.Result.Complete).contentIds.size >= declaringProperties.size)

        proxy.executionContext.executeAsync<TData>(
            operationId = org.tenshu.core.file.system.DeleteFile.ID,
            caller = null,
            params = mapOf<String, TData>(
                org.tenshu.core.file.system.DeleteFile.Params.PATH to "/photo.jpg".toTString(),
            ),
        )

        val after = proxy.executionContext.liveContentIds()
        assertEquals(
            emptySet(),
            (after as LiveContentIds.Result.Complete).contentIds,
            "a deleted file must take its derived thumbnail ids with it, or they are protected " +
                "for ever and the reclamation this walk gates never happens",
        )
    }

    @Test
    fun anIncompleteWalkStaysIncomplete_ratherThanGainingDerivedIds() = runTest {
        // ⚠⚠ The derived-id pass must not convert an Incomplete into a Complete. `LiveContentIds`
        // exists to make a partial walk unmistakable, and mapping over it is exactly the shape that
        // quietly launders one.
        val result = LiveContentIds.collect(root = "/") { null }
        assertTrue(result is LiveContentIds.Result.Incomplete, "precondition")
    }
}
