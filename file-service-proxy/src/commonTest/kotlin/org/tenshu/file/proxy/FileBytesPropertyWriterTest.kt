package org.tenshu.file.proxy

import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3b.2b of `docs/proposals/drive-shared-realm-file-model.md` (additive model):
 * the joiner-side `FileBytesPropertyWriter` now also handles a **shared-realm** file's
 * content slot ([SharedFile.DATA_PROPERTY_ID]). A realm member has no personal path
 * for the file, so the writer reassembles bytes under a deterministic realm
 * byte-path keyed by `(realmId, fileId)` (`/.shared/$realmId/$fileId`) — the path the
 * `FileContentManifestApplier` promotes the verified file onto.
 *
 * (Personal `File` resolution is unchanged — it still returns the file's own path.)
 */
class FileBytesPropertyWriterTest {

    @Test
    fun supportsBothPersonalAndSharedFileDataSlots() {
        val proxy = createFileProxy()
        val writer = FileBytesPropertyWriter(proxy.fileDatabase)

        assertTrue(writer.supports(File.Data.id), "personal File.Data slot")
        assertTrue(writer.supports(SharedFile.DATA_PROPERTY_ID), "shared-realm SharedFile data slot")
        assertTrue(!writer.supports("tenshu/some/other/property"), "unrelated property not supported")
    }

    @Test
    fun resolvesSharedFileToARealmBytePath() {
        val proxy = createFileProxy()
        val writer = FileBytesPropertyWriter(proxy.fileDatabase)

        proxy.executionContext.saveData(
            Entity.create<SharedFile>(
                context = proxy.tContext,
                composite = SharedFile.create(
                    context = proxy.tContext,
                    realmId = "realm-owner-1",
                    fileId = "file-abc",
                    name = "cat.jpg",
                    isFolder = false,
                ),
            )
        )
        val sharedFileDataId = DataId.StringDataId.create(
            context = proxy.tContext,
            type = SharedFile.ID,
            keyValue = SharedFile.compositeKey("realm-owner-1", "file-abc"),
        )

        val path = writer.resolveExternalPath(
            executionContext = proxy.executionContext,
            targetEntityId = sharedFileDataId,
            propertyId = SharedFile.DATA_PROPERTY_ID,
        )

        // Deterministic, realm-scoped, decoupled from the human name (which is on the
        // SharedFile's `name`); the reserved prefix never collides with a personal path.
        assertEquals("/.shared/realm-owner-1/file-abc", path)
    }

    @Test
    fun resolvesPersonalFileToItsOwnPath() {
        val proxy = createFileProxy()
        val writer = FileBytesPropertyWriter(proxy.fileDatabase)

        proxy.executionContext.saveData(
            Entity.create<File>(
                context = proxy.tContext,
                composite = File.create(proxy.tContext, "/photos/a.jpg"),
            )
        )
        val fileDataId = DataId.StringDataId.create(
            context = proxy.tContext,
            type = File.ID,
            keyValue = "/photos/a.jpg",
        )

        val path = writer.resolveExternalPath(
            executionContext = proxy.executionContext,
            targetEntityId = fileDataId,
            propertyId = File.Data.id,
        )
        assertEquals("/photos/a.jpg", path)
    }

    @Test
    fun resolveContentId_forSharedFile_returnsItsContentIdWhenSet_elseNull() {
        val proxy = createFileProxy()
        val writer = FileBytesPropertyWriter(proxy.fileDatabase)

        // A content-addressed SharedFile (3-2a): resolveContentId returns its contentId,
        // routing the manifest through the content plane.
        proxy.executionContext.saveData(
            Entity.create<SharedFile>(
                context = proxy.tContext,
                composite = SharedFile.create(
                    context = proxy.tContext,
                    realmId = "realm-owner-1",
                    fileId = "file-addressed",
                    name = "cat.jpg",
                    isFolder = false,
                    contentId = "content-xyz",
                ),
            )
        )
        assertEquals(
            "content-xyz",
            writer.resolveContentId(
                proxy.executionContext,
                DataId.StringDataId.create(
                    proxy.tContext, SharedFile.ID,
                    SharedFile.compositeKey("realm-owner-1", "file-addressed"),
                ),
                SharedFile.DATA_PROPERTY_ID,
            ),
            "a SharedFile with a contentId routes through the content plane",
        )

        // A legacy / folder SharedFile (empty contentId): falls back to the mailbox path.
        proxy.executionContext.saveData(
            Entity.create<SharedFile>(
                context = proxy.tContext,
                composite = SharedFile.create(
                    context = proxy.tContext,
                    realmId = "realm-owner-1",
                    fileId = "file-legacy",
                    name = "old.jpg",
                    isFolder = false,
                ),
            )
        )
        assertNull(
            writer.resolveContentId(
                proxy.executionContext,
                DataId.StringDataId.create(
                    proxy.tContext, SharedFile.ID,
                    SharedFile.compositeKey("realm-owner-1", "file-legacy"),
                ),
                SharedFile.DATA_PROPERTY_ID,
            ),
            "an empty contentId falls back to the legacy mailbox path",
        )
    }

    @Test
    fun resolvesNullForUnknownEntity() {
        val proxy = createFileProxy()
        val writer = FileBytesPropertyWriter(proxy.fileDatabase)
        val missing = DataId.StringDataId.create(
            context = proxy.tContext,
            type = SharedFile.ID,
            keyValue = SharedFile.compositeKey("realm-owner-1", "never-saved"),
        )
        assertNull(
            writer.resolveExternalPath(proxy.executionContext, missing, SharedFile.DATA_PROPERTY_ID),
            "an unsynced entity resolves to null (entity-before-bytes tolerance)",
        )
    }
}
