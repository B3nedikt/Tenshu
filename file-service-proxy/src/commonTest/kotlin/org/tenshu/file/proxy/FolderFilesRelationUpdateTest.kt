package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.Size
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Folder.FILES` under R2b (`core-relation-system.md` §10.4) — the other named
 * order-observing relation, and the one that shows what R2b *fixed* rather than
 * what it changed.
 *
 * `Folder.FILES` was already probe-first before R2b (it was the sole production
 * consumer of `MembershipOrder.Stable`, because remove-then-add visibly
 * reshuffled Drive's unsorted default listing after a detail-view round trip).
 * So its **order is unchanged** by R2b — the first test pins that.
 *
 * ⚠ What *was* broken, and is the reason R2b could not simply generalise
 * `Stable`: writing nothing in the steady state also meant *telling nobody*.
 * `StreamRegistry` re-evaluates a reactive read only on an array add/remove, so
 * a file re-saved in place — a size or `lastModified` update, OnDemand byte
 * materialisation, a thumbnail resolving, this device's own block coming back
 * through sync — updated the row and left every open listing showing the old
 * one until something else invalidated it. The second test pins the fix.
 *
 * ⚠ Narrowed by `core-deterministic-invalidation.md` §8 (P1): a re-save that changed **nothing**
 * no longer notifies — there is no stale row to fix. "This device's own block coming back" is that
 * case when the echo is byte-identical, which is what P0a measured. The third test pins it.
 */
class FolderFilesRelationUpdateTest {

    @Test
    fun aReSavedFileKeepsItsSlotInTheFolder() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val ec = proxy.executionContext

        createFolder(proxy, "/W")
        createFolder(proxy, "/W/a")
        createFolder(proxy, "/W/b")
        createFolder(proxy, "/W/c")

        // Re-save "/W/a" in place: same path ⇒ same DataId ⇒ same membership.
        ec.saveData(
            ec.database.getData(fileId("/W/a"), asEntity = true, projection = null)
                ?: error("/W/a not found"),
        )

        assertEquals(
            listOf("/W/a", "/W/b", "/W/c"),
            pathsIn(proxy, "/W"),
            "unchanged by R2b — this relation was already probe-first",
        )
    }

    @Test
    fun aReSavedFileNowTellsOpenListings() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val ec = proxy.executionContext

        createFolder(proxy, "/W")
        ec.saveData(sizedFile(proxy, "/W/f.txt", size = 1))

        var notifications = 0
        ec.database.setArrayListener(
            reference = filesRef(proxy, "/W"),
            onAdded = { _, _ -> notifications++ },
            onRemoved = { _, _ -> notifications++ },
        )

        // Re-saved in place with a new size — same path ⇒ same DataId ⇒ same membership.
        ec.saveData(sizedFile(proxy, "/W/f.txt", size = 2))

        assertEquals(
            1,
            notifications,
            "⚠ BEFORE R2b this was 0: `Stable` wrote nothing on a re-save and therefore " +
                "notified nothing, so an open folder listing kept rendering the stale row",
        )
    }

    /**
     * `core-deterministic-invalidation.md` §8, P1: a re-save that changed nothing — every entry
     * already stored byte-identically, as the relay's echo of this device's own block is — leaves no
     * stale row to fix, so no listing is told. ⚠ Until P1 this notified once, like the test above.
     */
    @Test
    fun aByteIdenticalReSaveTellsNobody() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val ec = proxy.executionContext

        createFolder(proxy, "/W")
        ec.saveData(sizedFile(proxy, "/W/f.txt", size = 1))

        var notifications = 0
        ec.database.setArrayListener(
            reference = filesRef(proxy, "/W"),
            onAdded = { _, _ -> notifications++ },
            onRemoved = { _, _ -> notifications++ },
        )

        ec.saveData(sizedFile(proxy, "/W/f.txt", size = 1))

        assertEquals(0, notifications)
    }

    // --- fixtures ---------------------------------------------------------

    private suspend fun createFolder(proxy: org.tenshu.core.common.TestProxy, path: String) {
        proxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to path.toTString(proxy.tContext)),
        )
    }

    private fun sizedFile(proxy: org.tenshu.core.common.TestProxy, path: String, size: Long): Data =
        Entity.create<File>(
            context = proxy.tContext,
            composite = File.create(proxy.tContext, path),
        ) {
            set(File.Size, size)
        }

    private fun fileId(path: String): DataId.EntityDataId =
        DataId.StringDataId.create(type = File.ID, keyValue = path) as DataId.EntityDataId

    private fun filesRef(proxy: org.tenshu.core.common.TestProxy, folderPath: String) =
        ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                context = proxy.tContext,
                type = TList.ID,
                parentEntity = fileId(folderPath),
                fieldRef = Folder.Fields.FILES,
            ),
            isEntity = true,
            elementSchemaId = File.ID,
        )

    private fun pathsIn(proxy: org.tenshu.core.common.TestProxy, folderPath: String): List<String> =
        proxy.executionContext.database.getArrayData(filesRef(proxy, folderPath))
            ?.map { (it as Entity<*>).getFieldValue<TString>(File.Field.PATH).get() }
            ?.toList()
            ?: emptyList()
}
