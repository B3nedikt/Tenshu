package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.FakeClock
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.equalTo
import org.tenshu.core.file.CreatedFileModification
import org.tenshu.core.file.File
import org.tenshu.core.file.FileHistory
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.FileService
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.replaceFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.core.file.Device
import org.tenshu.core.getOrNull
import org.tenshu.core.id
import org.tenshu.file.test.createFileProxy
import org.tenshu.identity.Device as IdentityDevice
import org.tenshu.identity.common.identityServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3 of `docs/proposals/core-nested-projection.md`: [File.FileHistory] — the
 * File-rooted view of a file's [FileModification] log, a per-file array keyed by the
 * file's own `DataId` ([DataId.EntityPropertyDataId]) and maintained by
 * `FileHistoryRelationMaintainer`.
 *
 * The maintainer-direct tests assert the per-file array is derived on save, isolated
 * per file, pruned on delete, and bounded by the realm-wide retention cap's delete
 * fan-out. The chain test reads it back through the real client → proxy `select` (the
 * entity-relation resolver + property-relation wire — the path the FileDetailsScreen
 * File-rooted query uses).
 */
class FileHistoryRelationTest {

    private val deviceId = "device-A"

    private fun env(proxy: TestProxy): TestEnv {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val tenshu = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        )
        return TestEnv(proxy, tenshu.getService(FileService.ID))
    }

    private inner class TestEnv(val proxy: TestProxy, val fileService: FileService) {

        suspend fun upload(parent: String, name: String) =
            assertTrue(fileService.defaultFileSystem.uploadFile(parent, name, byteArrayOf(1, 2, 3)) is TResult.Success)

        suspend fun replace(parent: String, name: String, bytes: ByteArray = byteArrayOf(4, 5, 6, 7)) =
            assertTrue(fileService.defaultFileSystem.replaceFile(parent, name, bytes) is TResult.Success)

        suspend fun delete(path: String) =
            assertTrue(fileService.defaultFileSystem.deleteFile(path) is TResult.Success)

        /** The per-file `File.FileHistory` array, read directly from the store. */
        fun history(filePath: String): List<Data> {
            val ref = ArrayDatabaseReference(
                arrayId = DataId.EntityPropertyDataId.create(
                    context = proxy.tContext,
                    type = TList.ID,
                    parentEntity = DataId.StringDataId.create(proxy.tContext, File.ID, filePath),
                    propertyId = File.FileHistory.id,
                ),
                isEntity = false,
                elementSchemaId = FileModification.ID,
            )
            return proxy.executionContext.getArrayData(ref)?.toList() ?: emptyList()
        }

        /** The history read back through the real client → proxy `select` chain. */
        suspend fun historyViaSelect(filePath: String): List<Data> {
            val entity = fileService.defaultFileSystem.root.files
                .search<File> { this.path equalTo filePath }
                .select { relations(File.FileHistory) }
                .resolve()
                .getOrNull() ?: return emptyList()
            return entity.get(File.FileHistory)?.toList().orEmpty().map { it as Data }
        }
    }

    private fun Data.typeId(): String = (this as? Entity<*>)?.type?.get() ?: id

    private fun Data.fileId(): String = when (this) {
        is Entity<*> -> getFieldValue<TString>(FileModification.Field.FILE_ID).get()
        is Composite -> getField<TString>(FileModification.Field.FILE_ID).get()
        else -> error("Unexpected modification shape: $this")
    }

    private fun List<Data>.fileIds(): List<String> = map { it.fileId() }

    // --- Maintainer: the per-file array is derived on save ---

    @Test
    fun savesAreMirroredToThePerFileArray() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val env = env(createFileProxy(backgroundScope, deviceId = deviceId, clock = clock))

        clock.advance(1); env.upload("/", "a.txt")   // Created
        clock.advance(1); env.replace("/", "a.txt")  // ContentChanged

        val history = env.history("/a.txt")
        assertEquals(2, history.size, "the file's per-file history holds both modifications")
        assertEquals(listOf("/a.txt", "/a.txt"), history.fileIds())
        assertEquals(1, history.count { it.typeId() == CreatedFileModification.ID }, "exactly one Created row")
    }

    @Test
    fun perFileArraysAreIsolated() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val env = env(createFileProxy(backgroundScope, deviceId = deviceId, clock = clock))

        clock.advance(1); env.upload("/", "a.txt")
        clock.advance(1); env.upload("/", "b.txt")

        assertEquals(listOf("/a.txt"), env.history("/a.txt").fileIds())
        assertEquals(listOf("/b.txt"), env.history("/b.txt").fileIds())
    }

    @Test
    fun deleteCascadesToThePerFileArray() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val env = env(createFileProxy(backgroundScope, deviceId = deviceId, clock = clock))

        clock.advance(1); env.upload("/", "a.txt")
        clock.advance(1); env.upload("/", "b.txt")
        assertEquals(1, env.history("/a.txt").size)

        clock.advance(1); env.delete("/a.txt")

        assertTrue(env.history("/a.txt").isEmpty(), "deleting /a.txt clears its per-file history")
        assertEquals(listOf("/b.txt"), env.history("/b.txt").fileIds(), "/b.txt's survives")
    }

    @Test
    fun retentionFanOutBoundsThePerFileArray() = runTest {
        // The realm-wide maintainer trims over-cap rows via `deleteData`, which fans out
        // to this observer's `onDataDeleted` — so the per-file array is bounded for free.
        val clock = FakeClock(initial = 1_000L)
        val env = env(createFileProxy(backgroundScope, deviceId = deviceId, clock = clock))

        clock.advance(1); env.upload("/", "a.txt") // Created
        repeat(FileModification.RETENTION_CAP + 10) {
            clock.advance(1)
            // Distinct bytes per write: an overwrite with the bytes the file already holds is a no-op.
            env.replace("/", "a.txt", bytes = byteArrayOf(4, 5, 6, 7, it.toByte()))
        }

        val history = env.history("/a.txt")
        assertEquals(
            FileModification.RETENTION_CAP,
            history.size,
            "the per-file array is capped via the realm-wide trim's delete fan-out",
        )
        assertEquals(1, history.count { it.typeId() == CreatedFileModification.ID }, "Created is never trimmed")
    }

    // --- Chain: File-rooted select reads the history back over the wire ---

    @Test
    fun fileRootedSelect_resolvesTheHistory() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val env = env(createFileProxy(backgroundScope, deviceId = deviceId, clock = clock))

        clock.advance(1); env.upload("/", "a.txt")
        clock.advance(1); env.replace("/", "a.txt")
        clock.advance(1); env.upload("/", "b.txt")

        val rows = env.historyViaSelect("/a.txt")
        assertEquals(2, rows.size, "the File-rooted select returns this file's history")
        assertEquals(
            setOf("/a.txt"),
            rows.fileIds().toSet(),
            "and only this file's modifications (File-rooted, not the realm-wide log)",
        )
    }

    /**
     * The headline consumer query (`core-nested-projection.md` §3.4): a single File-rooted
     * read walks down to its history and *each row's authoring `Device`* — composing the
     * entity relation (`File.FileHistory`) with the nested projection and the reference
     * value property (`FileModification.Device`). This is the FileDetailsScreen activity
     * read, all O(1) lookups bounded by the file's history.
     */
    @Test
    fun fileRootedSelect_withDeviceLeaf_resolvesDeviceName() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val proxy = createFileProxy(backgroundScope, deviceId = deviceId, clock = clock).apply {
            schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        proxy.database.saveData(
            IdentityDevice.create(
                context = proxy.tContext,
                deviceId = deviceId,
                name = "MacBook",
                publicKey = byteArrayOf(),
            )
        )
        val env = env(proxy)

        clock.advance(1); env.upload("/", "a.txt")
        clock.advance(1); env.replace("/", "a.txt")

        val entity = env.fileService.defaultFileSystem.root.files
            .search<File> { this.path equalTo "/a.txt" }
            .select { relations(File.FileHistory) { relations(FileModification.Device) } }
            .resolve()
            .getOrNull()
        assertTrue(entity != null, "the file resolves")

        // Nested-projected rows come back as `Entity<FileModification>` (each row was run
        // through `selectItem`), so read them as `Entity` rather than via the handle's
        // `TList<FileModification>` element type (core-nested-projection.md §8.6, fork A).
        val rows = (entity.get(File.FileHistory) as? TList<*>)?.toList().orEmpty()
            .map { it as Entity<*> }
        assertEquals(2, rows.size, "both modifications come back")
        val names = rows.map { it.get(FileModification.Device)?.name?.get() }
        assertEquals(
            listOf("MacBook", "MacBook"),
            names,
            "each history row's authoring Device resolves to its name (the FileDetailsScreen leaf)",
        )
    }
}
