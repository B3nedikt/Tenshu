package org.tenshu.file.common

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.file.shared.fileId
import org.tenshu.core.file.shared.isFolder
import org.tenshu.core.file.shared.name
import org.tenshu.core.file.shared.parentFileId
import org.tenshu.core.file.shared.realmId
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.trait.Syncable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Schema test for the [SharedFile] composite — a file inside a shared realm
 * (`docs/proposals/drive-shared-realm-file-model.md`, additive model). Mirrors
 * [ShareSchemaTest]:
 *  - `SharedFile` is `Syncable` (trait factory registered in
 *    `fileServiceCommonModule`) so a realm's file set converges,
 *  - its DataId derives from the `(realmId, fileId)` `compositeKey` (via
 *    `SharedFileDataIdProvider`) — the `::` prefix is the realm namespace routing
 *    relies on,
 *  - the fields round-trip on a `create(...)` + getter call,
 *  - and it survives a serialize → parse round-trip (the proxy `BinaryParser`
 *    wire path; in-process getters alone wouldn't exercise the registration).
 */
class SharedFileSchemaTest {

    private fun proxy() = TestProxy().apply {
        schemaRegistry.registerConfig(fileServiceCommonModule)
    }

    @Test
    fun sharedFileHasSyncableTrait() {
        assertTrue(
            SharedFile.Schema.hasTrait(Syncable.ID),
            "SharedFile must be Syncable so a realm's file set converges across its members",
        )
    }

    @Test
    fun dataIdDerivesFromCompositeKey() {
        val p = proxy()
        val sharedFile = SharedFile.create(
            context = p.tContext,
            realmId = "realm-owner-1",
            fileId = "file-abc",
            parentFileId = "folder-xyz",
            name = "cat.jpg",
            isFolder = false,
        )
        val entity = Entity.create<SharedFile>(context = p.tContext, composite = sharedFile)

        val dataId = p.executionContext.getDataId(entity)

        assertTrue(dataId is DataId.StringDataId)
        assertEquals(SharedFile.ID, dataId.type.get())
        // realmId is the `::` namespace prefix — RealmRegistry.realmForDataId routes on it.
        assertEquals("realm-owner-1::file-abc", dataId.keyValue.get())
    }

    @Test
    fun rootDefaultsParentToSentinel() {
        val p = proxy()
        val sharedFile = SharedFile.create(
            context = p.tContext,
            realmId = "realm-owner-1",
            fileId = "file-root-child",
            name = "Documents",
            isFolder = true,
        )

        assertEquals(SharedFile.ROOT_PARENT, sharedFile.parentFileId.get())
        assertEquals("", sharedFile.parentFileId.get())
    }

    @Test
    fun fieldsRoundTrip() {
        val p = proxy()
        val sharedFile = SharedFile.create(
            context = p.tContext,
            realmId = "realm-owner-2",
            fileId = "file-123",
            parentFileId = "parent-456",
            name = "spec.md",
            isFolder = true,
        )

        assertEquals("realm-owner-2", sharedFile.realmId.get())
        assertEquals("file-123", sharedFile.fileId.get())
        assertEquals("parent-456", sharedFile.parentFileId.get())
        assertEquals("spec.md", sharedFile.name.get())
        assertEquals("realm-owner-2::file-123", sharedFile.compositeKey.get())
        assertEquals(true, sharedFile.isFolder.get())
    }

    @Test
    fun survivesSerializeParseRoundTrip() {
        val p = proxy()
        val serializer = BinarySerializer(p.schemaRegistry)

        val entity = Entity.create<SharedFile>(
            context = p.tContext,
            composite = SharedFile.create(
                context = p.tContext,
                realmId = "realm-owner-3",
                fileId = "file-789",
                parentFileId = SharedFile.ROOT_PARENT,
                name = "photo.png",
                isFolder = false,
            ),
        )

        val bytes = serializer.serialize(entity)

        @Suppress("UNCHECKED_CAST")
        val parsed = p.parser.parse<Entity<SharedFile>>(bytes)

        assertEquals("realm-owner-3", parsed.realmId.get())
        assertEquals("file-789", parsed.fileId.get())
        assertEquals(SharedFile.ROOT_PARENT, parsed.parentFileId.get())
        assertEquals("photo.png", parsed.name.get())
        assertEquals(false, parsed.isFolder.get())
    }
}
