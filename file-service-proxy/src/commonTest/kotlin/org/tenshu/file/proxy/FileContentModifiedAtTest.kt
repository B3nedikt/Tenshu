package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.common.FakeClock
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.equalTo
import org.tenshu.core.file.ContentModifiedAt
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Size
import org.tenshu.core.file.contentModifiedAtOrCreated
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.file.system.replaceFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.getOrNull
import org.tenshu.core.resolve
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `File.ContentModifiedAt` (file-service-history.md §3.4) is the file's **own** modification
 * time: the source's, carried across the upload. `File.LastModifiedAt` beside it stays "last
 * touched in the realm", and the two must not be allowed to drift back into one — a library
 * backed up in a day otherwise shows that day as every photo's "Modified" date.
 *
 * Read back through the client → proxy chain's `relations(...)` projection, the path the Finder
 * bridge and the details screen use.
 */
class FileContentModifiedAtTest {

    private fun fileService(proxy: TestProxy): FileService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        )
        return env.getService(FileService.ID)
    }

    private suspend fun FileService.metaEntity(filePath: String): Entity<File>? =
        defaultFileSystem.root.files
            .search<File> { this.path equalTo filePath }
            .select { relations(File.CreatedAt, File.LastModifiedAt, File.ContentModifiedAt) }
            .resolve()
            .getOrNull()

    @Test
    fun upload_carriesTheSourcesDate_whileLastModifiedStaysTheUploadMoment() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val fileService = fileService(createFileProxy(backgroundScope, clock = clock))

        clock.advance(9) // the upload happens "now" = 1_009
        assertTrue(
            fileService.defaultFileSystem.uploadFile(
                "/", "old-photo.jpg", byteArrayOf(1, 2, 3), sourceModifiedAt = 42L,
            ) is TResult.Success,
        )

        val entity = assertNotNull(fileService.metaEntity("/old-photo.jpg"))
        assertEquals(42L, entity.get(File.ContentModifiedAt)?.get(), "the file's own date crosses the op")
        assertEquals(1_009L, entity.get(File.LastModifiedAt)?.get(), "the realm's touch time is the upload")
    }

    @Test
    fun upload_withNoSourceDate_isModifiedNow() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val fileService = fileService(createFileProxy(backgroundScope, clock = clock))

        clock.advance(7)
        assertTrue(fileService.defaultFileSystem.uploadFile("/", "b.txt", byteArrayOf(1)) is TResult.Success)

        val entity = assertNotNull(fileService.metaEntity("/b.txt"))
        assertEquals(1_007L, entity.get(File.ContentModifiedAt)?.get())
    }

    /** New bytes carry a new date; the creation date is the one that does not move. */
    @Test
    fun overwrite_takesTheNewContentsDate_andKeepsCreatedAt() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val fileService = fileService(createFileProxy(backgroundScope, clock = clock))

        assertTrue(
            fileService.defaultFileSystem.uploadFile(
                "/", "a.txt", byteArrayOf(1), sourceModifiedAt = 42L,
            ) is TResult.Success,
        )
        clock.advance(500) // now = 1_500
        assertTrue(fileService.defaultFileSystem.replaceFile("/", "a.txt", byteArrayOf(9, 9)) is TResult.Success)

        val entity = assertNotNull(fileService.metaEntity("/a.txt"))
        assertEquals(42L, entity.get(File.CreatedAt)?.get())
        assertEquals(1_500L, entity.get(File.ContentModifiedAt)?.get())
    }

    /**
     * The difference from `LastModifiedAt`, which a move bumps. A rename that moved this date
     * would make "Modified" mean "last reorganised" — every file dragged into a new folder
     * reading as changed today.
     */
    @Test
    fun move_carriesTheContentDateUnchanged_whileLastModifiedIsBumped() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val fileService = fileService(createFileProxy(backgroundScope, clock = clock))

        assertTrue(
            fileService.defaultFileSystem.uploadFile(
                "/", "a.txt", byteArrayOf(1), sourceModifiedAt = 42L,
            ) is TResult.Success,
        )
        clock.advance(500)
        assertTrue(fileService.defaultFileSystem.moveFile("/a.txt", "/renamed.txt") is TResult.Success)

        val entity = assertNotNull(fileService.metaEntity("/renamed.txt"))
        assertEquals(42L, entity.get(File.ContentModifiedAt)?.get(), "a move is not a content change")
        assertEquals(1_500L, entity.get(File.LastModifiedAt)?.get(), "but it is a touch")
    }

    /** A folder has no content, so it has no content date — absent, not zero. */
    @Test
    fun folder_carriesNoContentDate() = runTest {
        val fileService = fileService(createFileProxy(backgroundScope, clock = FakeClock(initial = 1_000L)))

        assertTrue(fileService.defaultFileSystem.createFolder("/docs") is TResult.Success)

        val entity = assertNotNull(fileService.metaEntity("/docs"))
        assertNull(entity.get(File.ContentModifiedAt))
    }

    /**
     * A row written before v17 has no slot. It must read as absent — not throw, not the epoch —
     * and the one fallback every reader shares answers with the creation date, which the upload
     * seeded from the same source timestamp.
     */
    @Test
    fun rowFromBeforeTheProperty_readsAbsent_andFallsBackToCreatedAt() = runTest {
        val proxy = createFileProxy(backgroundScope, clock = FakeClock(initial = 1_000L))
        val fileService = fileService(proxy)
        proxy.executionContext.saveData(
            Entity.create<File>(
                context = proxy.tContext,
                composite = File.create(proxy.tContext, "/legacy.jpg"),
            ) {
                set(File.Size, 3L)
                set(File.CreatedAt, 42L)
                set(File.LastModifiedAt, 900L)
            }
        )

        val entity = assertNotNull(fileService.metaEntity("/legacy.jpg"))
        assertNull(entity.get(File.ContentModifiedAt), "absent means the row predates the property")
        assertEquals(
            42L,
            contentModifiedAtOrCreated(entity.get(File.ContentModifiedAt)?.get(), entity.get(File.CreatedAt)?.get()),
        )
    }
}
