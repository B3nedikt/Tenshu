package org.tenshu.file.proxy

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId as FileContentIdProperty
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ⭐ CR-2 §10.12 Q5, option d: **a bare composite never clears content**, for every type whose schema
 * declares content. `WorkoutContentIdTest` pins the case that motivated it; this pins that the rule is
 * generic (a second type, no per-writer code) and that it keys on the stored row's TYPE.
 */
class BareCompositeKeepsContentTest {

    private val proxy = createFileProxy()
    private val ctx = proxy.tContext
    private val path = "/a.txt"
    private val id = DataId.StringDataId.create(context = ctx, type = File.ID, keyValue = path)

    private fun contentId(): String? =
        (proxy.executionContext.database.getData(id, asEntity = true) as? Entity<*>)
            ?.getProperty<Data>(File.FileContentIdProperty.id)?.let { it as? TString }?.get()

    private fun saveFileWithContent() {
        proxy.executionContext.saveData(
            Entity.create<File>(context = ctx, composite = File.create(ctx, path)) {
                set(File.FileContentIdProperty, "cid-1")
            }
        )
        assertEquals("cid-1", contentId(), "precondition")
    }

    @Test
    fun aBareFileSavedOverAFileKeepsItsContentId() {
        saveFileWithContent()

        proxy.executionContext.saveData(File.create(ctx, path))

        assertEquals("cid-1", contentId())
    }

    @Test
    fun aFolderReplacingAFileAtTheSameDataIdClearsIt() {
        // ⚠ File and Folder share one key space. Carrying a file's content onto the folder that
        // replaced it would keep the bytes alive under a row that can never name them.
        saveFileWithContent()

        proxy.executionContext.saveData(Folder.create(ctx, path, files = emptyList()))

        assertNull(contentId())
    }
}
