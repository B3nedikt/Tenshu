package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.FakeClock
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentChangedFileModification
import org.tenshu.core.file.CreatedFileModification
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.FileService
import org.tenshu.core.file.MovedFileModification
import org.tenshu.core.file.RenamedFileModification
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.DeleteFile
import org.tenshu.core.file.system.MoveFile
import org.tenshu.core.file.system.ReplaceFile
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.id
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 2 emit hooks (docs/proposals/file-service-history.md §4.1). Every File
 * write op stamps the authoring device + timestamp onto the right
 * [FileModification] subtype and saves it; the
 * `FileModificationRelationMaintainer` derives the realm-wide
 * [FileService.Relations.MODIFICATIONS] log. Phase 3 adds the tombstone
 * cascade (§4.4); Phase 5 adds the per-file retention cap (§4.6). These tests
 * exercise the single-device emit + cascade + retention paths; cross-device
 * union/convergence is the maintainer's job (proven by the same pattern's
 * `NotificationSyncSystemTest`).
 */
class FileModificationEmitTest {

    private val deviceId = "device-A"

    // Modifications are saved as plain composites (the `create(...)` factory
    // returns a Composite, not an Entity — same shape as notifications), so the
    // relation array holds composites or entities depending on materialisation.
    private fun TestProxy.modifications(): List<Data> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = tContext,
                type = TList.ID,
                relationId = FileService.Relations.MODIFICATIONS
            ),
            isEntity = false,
            elementSchemaId = FileModification.ID
        )
        return executionContext.getArrayData(ref)?.toList() ?: emptyList()
    }

    private fun Data.typeId(): String = (this as? Entity<*>)?.type?.get() ?: id

    private fun Data.field(id: String): String = when (this) {
        is Entity<*> -> getFieldValue<TString>(id).get()
        is Composite -> getField<TString>(id).get()
        else -> error("Unexpected modification shape: $this")
    }

    private suspend fun TestProxy.createFolder(path: String): Data? =
        executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to path.toTString(tContext))
        )

    private suspend fun TestProxy.upload(parent: String, name: String): Data? =
        executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to parent.toTString(tContext),
                UploadFile.Params.FILE_NAME to name.toTString(tContext),
                UploadFile.Params.DATA to byteArrayOf(1, 2, 3).toTByteArray(tContext)
            )
        )

    private suspend fun TestProxy.replace(parent: String, name: String, bytes: ByteArray = byteArrayOf(4, 5, 6, 7)): Data? =
        executionContext.executeAsync<Data>(
            operationId = ReplaceFile.ID,
            caller = null,
            params = mapOf(
                ReplaceFile.Params.PARENT_PATH to parent.toTString(tContext),
                ReplaceFile.Params.FILE_NAME to name.toTString(tContext),
                ReplaceFile.Params.DATA to bytes.toTByteArray(tContext)
            )
        )

    private suspend fun TestProxy.move(source: String, target: String): Data? =
        executionContext.executeAsync<Data>(
            operationId = MoveFile.ID,
            caller = null,
            params = mapOf(
                MoveFile.Params.SOURCE_PATH to source.toTString(tContext),
                MoveFile.Params.TARGET_PATH to target.toTString(tContext)
            )
        )

    private suspend fun TestProxy.delete(path: String): Data? =
        executionContext.executeAsync<Data>(
            operationId = DeleteFile.ID,
            caller = null,
            params = mapOf(DeleteFile.Params.PATH to path.toTString(tContext))
        )

    private fun TestProxy.fileIds(): List<String> =
        modifications().map { it.field(FileModification.Field.FILE_ID) }

    @Test
    fun createFolder_emitsCreatedModification() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)

        proxy.createFolder("/docs")

        val mods = proxy.modifications()
        assertEquals(1, mods.size, "exactly one modification")
        val mod = mods.single()
        assertEquals(CreatedFileModification.ID, mod.typeId())
        assertEquals("/docs", mod.field(FileModification.Field.FILE_ID))
        assertEquals(deviceId, mod.field(FileModification.Field.DEVICE_ID))
    }

    @Test
    fun uploadNewFile_emitsCreatedModification() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)

        proxy.upload("/", "a.txt")

        val mods = proxy.modifications()
        assertEquals(1, mods.size)
        val mod = mods.single()
        assertEquals(CreatedFileModification.ID, mod.typeId())
        assertEquals("/a.txt", mod.field(FileModification.Field.FILE_ID))
        assertEquals(deviceId, mod.field(FileModification.Field.DEVICE_ID))
    }

    @Test
    fun overwriteFile_emitsContentChangedModification() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)

        proxy.upload("/", "a.txt")   // Created
        proxy.replace("/", "a.txt")  // ContentChanged

        val contentChanged = proxy.modifications()
            .filter { it.typeId() == ContentChangedFileModification.ID }
        assertEquals(1, contentChanged.size, "overwrite emits one ContentChanged")
        val mod = contentChanged.single()
        assertEquals("/a.txt", mod.field(FileModification.Field.FILE_ID))
        assertEquals(deviceId, mod.field(FileModification.Field.DEVICE_ID))
        // manifestId is the content locator (file path) until restore-to-version
        // surfaces the real ref (§2.2).
        assertEquals("/a.txt", mod.field(ContentChangedFileModification.Field.MANIFEST_ID))
    }

    @Test
    fun overwriteWithUnchangedBytes_emitsNoModification() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)

        proxy.upload("/", "a.txt")
        proxy.replace("/", "a.txt", bytes = byteArrayOf(1, 2, 3)) // the bytes it already holds

        assertEquals(
            listOf(CreatedFileModification.ID),
            proxy.modifications().map { it.typeId() },
            "an overwrite with identical bytes is a no-op, so the history must not claim the content changed",
        )
    }

    @Test
    fun renameWithinParent_emitsRenamedModification() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)

        proxy.upload("/", "a.txt")
        proxy.move("/a.txt", "/b.txt")

        val renamed = proxy.modifications()
            .filter { it.typeId() == RenamedFileModification.ID }
        assertEquals(1, renamed.size, "same-parent move is a Rename")
        val mod = renamed.single()
        assertEquals("/b.txt", mod.field(FileModification.Field.FILE_ID))
        assertEquals("a.txt", mod.field(RenamedFileModification.Field.OLD_NAME))
        assertEquals("b.txt", mod.field(RenamedFileModification.Field.NEW_NAME))
    }

    @Test
    fun moveAcrossParents_emitsMovedModification() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)

        proxy.createFolder("/sub")
        proxy.upload("/", "a.txt")
        proxy.move("/a.txt", "/sub/a.txt")

        val moved = proxy.modifications()
            .filter { it.typeId() == MovedFileModification.ID }
        assertEquals(1, moved.size, "cross-parent move is a Move")
        val mod = moved.single()
        assertEquals("/sub/a.txt", mod.field(FileModification.Field.FILE_ID))
        assertEquals("/", mod.field(MovedFileModification.Field.OLD_PARENT_PATH))
        assertEquals("/sub", mod.field(MovedFileModification.Field.NEW_PARENT_PATH))
    }

    @Test
    fun distinctWrites_unionInAppendOnlyLog() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)

        proxy.upload("/", "a.txt")
        proxy.upload("/", "b.txt")

        val fileIds = proxy.modifications()
            .map { it.field(FileModification.Field.FILE_ID) }
            .toSet()
        assertEquals(setOf("/a.txt", "/b.txt"), fileIds, "both modifications retained (union)")
    }

    @Test
    fun noDeviceId_emitsNoModifications() = runTest {
        // Default harness (deviceId == null): the emit hooks no-op, matching the
        // nullable syncBlockEmitterHolder / tombstoneEmitter pattern.
        val proxy = createFileProxy(backgroundScope)

        proxy.createFolder("/docs")
        proxy.upload("/", "a.txt")

        assertTrue(proxy.modifications().isEmpty(), "no modifications without a deviceId")
    }

    // --- Phase 3: tombstone cascade (§4.4) ---

    @Test
    fun deleteFile_cascadeRemovesItsHistory() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)

        proxy.upload("/", "a.txt")
        proxy.upload("/", "b.txt")
        assertEquals(setOf("/a.txt", "/b.txt"), proxy.fileIds().toSet())

        proxy.delete("/a.txt")

        assertEquals(
            listOf("/b.txt"),
            proxy.fileIds(),
            "deleting /a.txt removes only its history; /b.txt's survives"
        )
    }

    @Test
    fun deleteFolder_cascadeRemovesHistoryForFolderAndChildren() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId)

        proxy.createFolder("/sub")        // Created /sub
        proxy.upload("/sub", "a.txt")     // Created /sub/a.txt
        proxy.upload("/", "keep.txt")     // Created /keep.txt
        assertEquals(setOf("/sub", "/sub/a.txt", "/keep.txt"), proxy.fileIds().toSet())

        // Recursive delete tombstones /sub and (locally) its child — both cascade.
        proxy.delete("/sub")

        assertEquals(
            listOf("/keep.txt"),
            proxy.fileIds(),
            "folder delete cascades to the folder + every descendant's history"
        )
    }

    // --- Phase 5: retention cap (§4.6) ---

    @Test
    fun retention_capsHistoryPerFile_keepingCreated() = runTest {
        // A FakeClock advanced per write gives each modification a distinct
        // timestamp -> distinct compositeKey -> distinct row (same-ms writes would
        // otherwise collapse onto one key).
        val clock = FakeClock(initial = 1_000L)
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId, clock = clock)

        clock.advance(1); proxy.upload("/", "a.txt")   // Created
        clock.advance(1); proxy.upload("/", "keep.txt") // untouched file

        val overshoot = 10
        repeat(FileModification.RETENTION_CAP + overshoot) {
            clock.advance(1)
            // Distinct bytes per write: an overwrite with the bytes the file already holds is a no-op.
            proxy.replace("/", "a.txt", bytes = byteArrayOf(4, 5, 6, 7, it.toByte())) // ContentChanged
        }

        val aHistory = proxy.modifications().filter { it.field(FileModification.Field.FILE_ID) == "/a.txt" }
        assertEquals(
            FileModification.RETENTION_CAP,
            aHistory.size,
            "history for /a.txt is capped at RETENTION_CAP"
        )
        assertEquals(
            1,
            aHistory.count { it.typeId() == CreatedFileModification.ID },
            "the Created floor record is never trimmed"
        )

        // Per-file cap: an untouched file's single record is unaffected.
        assertEquals(
            listOf("/keep.txt"),
            proxy.modifications()
                .map { it.field(FileModification.Field.FILE_ID) }
                .filter { it == "/keep.txt" },
            "retention is per-file; /keep.txt keeps its lone record"
        )
    }
}
