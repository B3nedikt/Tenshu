package org.tenshu.file.common

import org.tenshu.core.common.TestProxy
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentChangedFileModification
import org.tenshu.core.file.CreatedFileModification
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.MovedFileModification
import org.tenshu.core.file.RenamedFileModification
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.trait.Syncable
import org.tenshu.core.trait.isAppendOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase-1 schema test for the `FileModification` hierarchy
 * (docs/proposals/file-service-history.md §2-§3): the four v1 subtypes are
 * `FileModification`s, the parent's traits (AppendOnly / Syncable /
 * DataIdProvider) are inherited, the DataId derives from the composite key, and
 * each subtype's fields round-trip.
 */
class FileModificationSchemaTest {

    private fun proxy() = TestProxy().apply {
        schemaRegistry.registerConfig(fileServiceCommonModule)
    }

    @Test
    fun subtypesAreFileModifications() {
        assertTrue(CreatedFileModification.Schema.isSubtypeOf(FileModification.ID))
        assertTrue(ContentChangedFileModification.Schema.isSubtypeOf(FileModification.ID))
        assertTrue(RenamedFileModification.Schema.isSubtypeOf(FileModification.ID))
        assertTrue(MovedFileModification.Schema.isSubtypeOf(FileModification.ID))
    }

    @Test
    fun parentTraitsAreInheritedBySubtypes() {
        // AppendOnly + Syncable declared on the parent, visible on every subtype
        // via the parent-walk in `hasTrait` (the File -> Folder pattern).
        assertTrue(CreatedFileModification.Schema.isAppendOnly())
        assertTrue(CreatedFileModification.Schema.hasTrait(Syncable.ID))
        assertTrue(MovedFileModification.Schema.isAppendOnly())
        assertTrue(MovedFileModification.Schema.hasTrait(Syncable.ID))
    }

    @Test
    fun dataIdDerivesFromCompositeKey() {
        val p = proxy()
        val created = CreatedFileModification.create(
            context = p.tContext,
            fileId = "/docs/a.txt",
            timestamp = 1000L,
            deviceId = "device-1",
        )
        val entity = Entity.create<CreatedFileModification>(
            context = p.tContext,
            composite = created,
        )

        val dataId = p.executionContext.getDataId(entity)

        assertTrue(dataId is DataId.StringDataId)
        // The provider is registered on the parent, so the DataId carries the
        // parent type — modifications form one logical collection keyed by
        // compositeKey. The concrete subtype is preserved in the stored entry.
        assertEquals(FileModification.ID, dataId.type.get())
        assertEquals(
            FileModification.compositeKey("/docs/a.txt", "device-1", 1000L),
            dataId.keyValue.get(),
        )
    }

    @Test
    fun subtypeFieldsRoundTrip() {
        val p = proxy()

        val renamed = RenamedFileModification.create(
            context = p.tContext,
            fileId = "/docs/a.txt",
            timestamp = 2000L,
            deviceId = "device-1",
            oldName = "a.txt",
            newName = "b.txt",
        )
        assertEquals("a.txt", renamed.oldName.get())
        assertEquals("b.txt", renamed.newName.get())
        assertEquals("device-1", renamed.deviceId.get())
        assertEquals(2000L, renamed.timestamp.get())

        val content = ContentChangedFileModification.create(
            context = p.tContext,
            fileId = "/docs/a.txt",
            timestamp = 3000L,
            deviceId = "device-1",
            manifestId = "manifest-xyz",
        )
        assertEquals("manifest-xyz", content.manifestId.get())

        val moved = MovedFileModification.create(
            context = p.tContext,
            fileId = "/docs/a.txt",
            timestamp = 4000L,
            deviceId = "device-1",
            oldParentPath = "/docs",
            newParentPath = "/archive",
        )
        assertEquals("/docs", moved.oldParentPath.get())
        assertEquals("/archive", moved.newParentPath.get())
    }
}
