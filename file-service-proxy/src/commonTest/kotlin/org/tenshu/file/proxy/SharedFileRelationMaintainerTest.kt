package org.tenshu.file.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `SharedFileRelationMaintainer` derives a shared realm's folder **tree** from its
 * [SharedFile] rows (`docs/proposals/drive-shared-realm-file-model.md` §6 Phase 3,
 * additive model). On every save it maintains the parent's children array (keyed on
 * the *parent's* DataId — an [DataId.EntityPropertyDataId] under
 * `SharedFile.CHILDREN_RELATION_ID`), the structure a shared-realm `FileSystem.list`
 * walks (the read path lands in Phase 3c).
 *
 * Rows are saved directly via `executionContext.saveData` (the `ShareFolderIntoRealm`
 * op that produces them is covered by `ShareFolderIntoRealmOperationTest`). Mirrors
 * `FileHistoryRelationTest`'s maintainer-direct shape.
 */
class SharedFileRelationMaintainerTest {

    private val realm = "realm-owner-1"

    /** The children array of folder [parentFileId] in [realm], read from the store. */
    private fun children(proxy: TestProxy, parentFileId: String): List<Data> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.EntityPropertyDataId.create(
                context = proxy.tContext,
                type = TList.ID,
                parentEntity = DataId.StringDataId.create(
                    proxy.tContext,
                    SharedFile.ID,
                    SharedFile.compositeKey(realm, parentFileId),
                ),
                propertyId = SharedFile.CHILDREN_RELATION_ID,
            ),
            isEntity = false,
            elementSchemaId = SharedFile.ID,
        )
        return proxy.executionContext.getArrayData(ref)?.toList() ?: emptyList()
    }

    private fun place(
        proxy: TestProxy,
        fileId: String,
        parentFileId: String = SharedFile.ROOT_PARENT,
        name: String,
    ) {
        proxy.executionContext.saveData(
            Entity.create<SharedFile>(
                context = proxy.tContext,
                composite = SharedFile.create(
                    context = proxy.tContext,
                    realmId = realm,
                    fileId = fileId,
                    parentFileId = parentFileId,
                    name = name,
                    isFolder = false,
                ),
            )
        )
    }

    private fun Data.placementFileId(): String = when (this) {
        is Entity<*> -> getFieldValue<TString>(SharedFile.Field.FILE_ID).get()
        is Composite -> getField<TString>(SharedFile.Field.FILE_ID).get()
        else -> error("unexpected SharedFile shape: $this")
    }

    private fun List<Data>.fileIds(): List<String> = map { it.placementFileId() }

    @Test
    fun savesAreMirroredToTheParentChildrenArray() {
        val proxy = createFileProxy()

        place(proxy, fileId = "docs", name = "Documents")          // realm root
        place(proxy, fileId = "report", parentFileId = "docs", name = "report.pdf")
        place(proxy, fileId = "notes", parentFileId = "docs", name = "notes.md")

        assertEquals(
            listOf("docs"),
            children(proxy, SharedFile.ROOT_PARENT).fileIds(),
            "the root level holds the top folder",
        )
        assertEquals(
            setOf("report", "notes"),
            children(proxy, "docs").fileIds().toSet(),
            "both children are placed under their parent folder",
        )
    }

    @Test
    fun perParentArraysAreIsolated() {
        val proxy = createFileProxy()

        place(proxy, fileId = "a", parentFileId = "folder-1", name = "a.txt")
        place(proxy, fileId = "b", parentFileId = "folder-2", name = "b.txt")

        assertEquals(listOf("a"), children(proxy, "folder-1").fileIds())
        assertEquals(listOf("b"), children(proxy, "folder-2").fileIds())
    }

    @Test
    fun reSaveConvergesToASingleEntry() {
        val proxy = createFileProxy()

        place(proxy, fileId = "report", parentFileId = "docs", name = "report.pdf")
        place(proxy, fileId = "report", parentFileId = "docs", name = "report.pdf")

        assertEquals(
            1,
            children(proxy, "docs").size,
            "the idempotent remove-then-add converges re-saves to exactly one entry",
        )
    }

    @Test
    fun rowsAreScopedByRealmNamespaceInTheKey() {
        val proxy = createFileProxy()

        place(proxy, fileId = "x", parentFileId = "docs", name = "x.txt")

        val otherRef = ArrayDatabaseReference(
            arrayId = DataId.EntityPropertyDataId.create(
                context = proxy.tContext,
                type = TList.ID,
                parentEntity = DataId.StringDataId.create(
                    proxy.tContext,
                    SharedFile.ID,
                    "other-realm::docs",
                ),
                propertyId = SharedFile.CHILDREN_RELATION_ID,
            ),
            isEntity = false,
            elementSchemaId = SharedFile.ID,
        )

        assertEquals(listOf("x"), children(proxy, "docs").fileIds())
        assertTrue(
            proxy.executionContext.getArrayData(otherRef)?.toList().isNullOrEmpty(),
            "a same-named parent in another realm has its own (empty) children array",
        )
    }
}
