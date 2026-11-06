package org.tenshu.file.proxy

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.FakeClock
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.Entity
import org.tenshu.core.equalTo
import org.tenshu.core.file.CreatedFileModification
import org.tenshu.core.file.Device
import org.tenshu.core.file.File
import org.tenshu.core.file.FileHistory
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.FileService
import org.tenshu.core.file.MovedFileModification
import org.tenshu.core.file.RenamedFileModification
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.replaceFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.getOrNull
import org.tenshu.core.id
import org.tenshu.core.ifEmptyError
import org.tenshu.core.relation.relation
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the **`Entity<T>.relation(handle)`** chain accessor — the list-FIELD
 * navigation `root.files` analogue for a list-valued **property-relation**
 * (here `File.FileHistory`, the `entityRelation` primitive). Produces a
 * `TList<FileModification>` chain rooted at the file's per-entity array
 * (`EntityPropertyDataId`), ready for the usual chain ops (`.select`,
 * `.flow()`, …) — and observes via the same `setArrayListener` infra the
 * realm-wide `fileService.modifications` rides, so `.flow()` is reactive.
 */
class FileHistoryChainTest {

    private val deviceId = "device-A"

    private fun env(proxy: TestProxy): FileService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val tenshu = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        )
        return tenshu.getService(FileService.ID)
    }

    private fun Data.fileId(): String = when (this) {
        is Entity<*> -> getFieldValue<TString>(FileModification.Field.FILE_ID).get()
        is Composite -> getField<TString>(FileModification.Field.FILE_ID).get()
        else -> error("Unexpected modification shape: $this")
    }

    private fun Data.typeId(): String = (this as? Entity<*>)?.type?.get() ?: id

    /** The File-rooted history chain: file lookup → `.relation(File.FileHistory)`. */
    private fun FileService.history(path: String) = defaultFileSystem.root.files
        .search<File> { this.path equalTo path }
        .ifEmptyError()
        .relation(File.FileHistory)

    /**
     * Reproduces the exact chain `TenshuFilesRepository.fileActivity` ships: the
     * File-rooted history relation **with the `.select { fields(...); relations(
     * FileModification.Device) }` projection**. The other tests stop at
     * `.relation(...).flow()` and never exercise the selector + nested
     * reference-property over the entity-relation list — the one combination the
     * empty-Activity report points at.
     */
    private fun FileService.historyProjected(path: String) = defaultFileSystem.root.files
        .search<File> { this.path equalTo path }
        .ifEmptyError()
        .relation(File.FileHistory)
        .select {
            fields(
                FileModification.Field.COMPOSITE_KEY,
                FileModification.Field.TIMESTAMP,
                FileModification.Field.DEVICE_ID,
                RenamedFileModification.Field.OLD_NAME,
                RenamedFileModification.Field.NEW_NAME,
                MovedFileModification.Field.NEW_PARENT_PATH,
            )
            relations(FileModification.Device)
        }

    @Test
    fun fileRootedRelationChain_withSelectProjection_emitsRows() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val fileService = env(createFileProxy(backgroundScope, deviceId = deviceId, clock = clock))

        clock.advance(1); assertTrue(fileService.defaultFileSystem.uploadFile("/", "a.txt", byteArrayOf(1)) is TResult.Success)
        clock.advance(1); assertTrue(fileService.defaultFileSystem.replaceFile("/", "a.txt", byteArrayOf(2)) is TResult.Success)

        val rows = fileService.historyProjected("/a.txt").flow().first().get().toList()
        assertEquals(2, rows.size, "the projected chain still returns this file's two modifications")
        assertEquals(1, rows.count { (it as Data).typeId() == CreatedFileModification.ID }, "one Created row survives the projection")
    }

    @Test
    fun fileRootedRelationChain_emitsThisFilesHistory() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val fileService = env(createFileProxy(backgroundScope, deviceId = deviceId, clock = clock))

        clock.advance(1); assertTrue(fileService.defaultFileSystem.uploadFile("/", "a.txt", byteArrayOf(1)) is TResult.Success)
        clock.advance(1); assertTrue(fileService.defaultFileSystem.replaceFile("/", "a.txt", byteArrayOf(2)) is TResult.Success)
        clock.advance(1); assertTrue(fileService.defaultFileSystem.uploadFile("/", "b.txt", byteArrayOf(3)) is TResult.Success)

        val rows = fileService.history("/a.txt").flow().first().get().toList()
        assertEquals(2, rows.size, "the chain returns this file's two modifications")
        assertEquals(setOf("/a.txt"), rows.map { (it as Data).fileId() }.toSet(), "File-rooted: no other file's mods")
        assertEquals(1, rows.count { (it as Data).typeId() == CreatedFileModification.ID }, "one Created row")
    }

    @Test
    fun fileRootedRelationChain_isolatedAcrossFiles() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val fileService = env(createFileProxy(backgroundScope, deviceId = deviceId, clock = clock))

        clock.advance(1); assertTrue(fileService.defaultFileSystem.uploadFile("/", "a.txt", byteArrayOf(1)) is TResult.Success)
        clock.advance(1); assertTrue(fileService.defaultFileSystem.uploadFile("/", "b.txt", byteArrayOf(2)) is TResult.Success)

        val a = fileService.history("/a.txt").flow().first().get().toList()
        val b = fileService.history("/b.txt").flow().first().get().toList()
        assertEquals(listOf("/a.txt"), a.map { (it as Data).fileId() })
        assertEquals(listOf("/b.txt"), b.map { (it as Data).fileId() })
    }

    @Test
    fun fileRootedRelationChain_reactiveOnPerFileArrayChange() = runTest {
        // Reactivity ride: any addToArray/removeFromArray on the per-file
        // `EntityPropertyDataId` array fires the same `setArrayListener` infra
        // `modifications.flow()` rides → the chain re-emits. Collect two emissions,
        // mutate between, assert the second carries the new row.
        val clock = FakeClock(initial = 1_000L)
        val fileService = env(createFileProxy(backgroundScope, deviceId = deviceId, clock = clock))

        clock.advance(1); assertTrue(fileService.defaultFileSystem.uploadFile("/", "a.txt", byteArrayOf(1)) is TResult.Success)

        val emissions = mutableListOf<List<Data>>()
        val job = launch {
            fileService.history("/a.txt").flow().take(2).collect { result ->
                emissions += result.getOrNull()?.toList().orEmpty().map { it as Data }
            }
        }
        // Let the collector subscribe and receive the initial emission BEFORE we
        // mutate — without this, `runTest` queues the launch body so the mutation
        // below would land before `setArrayListener` registers, and the take(2)
        // would never see a second emission (no event to fire against).
        testScheduler.advanceUntilIdle()
        // After the initial emission lands, push a second modification to trigger re-emission.
        clock.advance(1); assertTrue(fileService.defaultFileSystem.replaceFile("/", "a.txt", byteArrayOf(2)) is TResult.Success)
        job.join()

        assertEquals(2, emissions.size, "two emissions: initial + reaction to the new mod")
        assertEquals(1, emissions[0].size, "initial: one mod")
        assertEquals(2, emissions[1].size, "after re-emit: two mods")
        assertEquals(
            setOf("/a.txt"),
            emissions[1].map { it.fileId() }.toSet(),
            "still File-rooted",
        )
    }

    @Test
    fun fileRootedRelationChain_deleteEmitsEmpty() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val fileService = env(createFileProxy(backgroundScope, deviceId = deviceId, clock = clock))

        clock.advance(1); assertTrue(fileService.defaultFileSystem.uploadFile("/", "a.txt", byteArrayOf(1)) is TResult.Success)

        val emissions = mutableListOf<List<Data>>()
        val job = launch {
            fileService.history("/a.txt").flow().take(2).collect { result ->
                emissions += result.getOrNull()?.toList().orEmpty().map { it as Data }
            }
        }
        // Let the collector subscribe and receive the initial emission BEFORE we
        // mutate — without this, `runTest` queues the launch body so the mutation
        // below would land before `setArrayListener` registers, and the take(2)
        // would never see a second emission (no event to fire against).
        testScheduler.advanceUntilIdle()
        clock.advance(1); assertTrue(fileService.defaultFileSystem.deleteFile("/a.txt") is TResult.Success)
        job.join()

        assertEquals(1, emissions[0].size, "initial: one mod")
        assertTrue(emissions[1].isEmpty(), "delete cascades to the per-file array; the chain re-emits empty")
    }
}
