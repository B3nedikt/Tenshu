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
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.replaceFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.core.getOrNull
import org.tenshu.file.impl.UploadFileImplementation
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `File.CreatedAt` (file-service-history.md §3.3) is op-written at creation and
 * **immutable** thereafter: a `now` stamp on a brand-new file/folder, preserved
 * across an in-place overwrite (unlike `lastModifiedAt`, which is bumped).
 *
 * Read back through the client → proxy chain's `relations(File.CreatedAt)`
 * projection — the path the file-details metadata reads through, and the one
 * that exercises the property-relation wire composites.
 */
class FileCreatedAtTest {

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
            .select { relations(File.CreatedAt, File.LastModifiedAt) }
            .resolve()
            .getOrNull()

    @Test
    fun uploadNewFile_stampsCreatedAtEqualToLastModified() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val fileService = fileService(createFileProxy(backgroundScope, clock = clock))

        clock.advance(5)
        assertTrue(fileService.defaultFileSystem.uploadFile("/", "a.txt", byteArrayOf(1, 2)) is TResult.Success)

        val entity = assertNotNull(fileService.metaEntity("/a.txt"), "uploaded file should resolve")
        val createdAt = assertNotNull(entity.get(File.CreatedAt)?.get(), "new file must carry createdAt")
        assertEquals(
            entity.get(File.LastModifiedAt)?.get(), createdAt,
            "on creation, createdAt == lastModifiedAt"
        )
    }

    @Test
    fun uploadNewFile_seedsCreatedAtFromSourceModified_butLastModifiedStaysImportTime() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val proxy = createFileProxy(backgroundScope, clock = clock)
        val fileService = fileService(proxy)

        clock.advance(9) // the import happens "now" = 1_009
        val sourceModified = 42L // the picked file's own last-modified, far in the past

        val result = UploadFileImplementation(proxy.fileDatabase).uploadFile(
            proxy.executionContext,
            "/",
            "old-photo.jpg",
            byteArrayOf(1, 2, 3),
            sourceModifiedAt = sourceModified,
        )
        assertTrue(result is TResult.Success, "seeded upload should succeed")

        val entity = assertNotNull(fileService.metaEntity("/old-photo.jpg"), "uploaded file should resolve")
        assertEquals(
            sourceModified, entity.get(File.CreatedAt)?.get(),
            "createdAt must be seeded from the source file's last-modified time, not the import moment",
        )
        assertEquals(
            1_009L, entity.get(File.LastModifiedAt)?.get(),
            "lastModifiedAt stays the import moment (the honest 'date added'), not the source time",
        )
    }

    /**
     * End-to-end over the op wire (RO → param map → `execute`'s param read), not
     * the direct `uploadFile(...)` call above: a green direct-call test proves
     * nothing about whether `sourceModifiedAt` actually crosses the operation.
     */
    @Test
    fun uploadFileOp_carriesSourceModifiedAt_seedingCreatedAt() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val fileService = fileService(createFileProxy(backgroundScope, clock = clock))

        clock.advance(9) // import now = 1_009
        val sourceModified = 42L

        assertTrue(
            fileService.defaultFileSystem.uploadFile(
                "/", "wired.jpg", byteArrayOf(1, 2, 3), sourceModifiedAt = sourceModified,
            ) is TResult.Success,
        )

        val entity = assertNotNull(fileService.metaEntity("/wired.jpg"))
        assertEquals(
            sourceModified, entity.get(File.CreatedAt)?.get(),
            "the op must carry sourceModifiedAt through to seed createdAt",
        )
        assertEquals(
            1_009L, entity.get(File.LastModifiedAt)?.get(),
            "lastModifiedAt stays the import moment",
        )
    }

    @Test
    fun uploadNewFile_withoutSourceModified_fallsBackToImportTime() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val proxy = createFileProxy(backgroundScope, clock = clock)
        val fileService = fileService(proxy)

        clock.advance(7) // now = 1_007

        val result = UploadFileImplementation(proxy.fileDatabase).uploadFile(
            proxy.executionContext,
            "/",
            "b.txt",
            byteArrayOf(1),
            sourceModifiedAt = null,
        )
        assertTrue(result is TResult.Success)

        val entity = assertNotNull(fileService.metaEntity("/b.txt"))
        assertEquals(
            1_007L, entity.get(File.CreatedAt)?.get(),
            "with no source timestamp createdAt falls back to import time",
        )
    }

    @Test
    fun createFolder_stampsCreatedAt() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val fileService = fileService(createFileProxy(backgroundScope, clock = clock))

        clock.advance(3)
        assertTrue(fileService.defaultFileSystem.createFolder("/docs") is TResult.Success)

        val entity = assertNotNull(fileService.metaEntity("/docs"), "created folder should resolve")
        assertNotNull(entity.get(File.CreatedAt)?.get(), "new folder must carry createdAt")
    }

    @Test
    fun overwrite_preservesCreatedAt_butBumpsLastModified() = runTest {
        val clock = FakeClock(initial = 1_000L)
        val fileService = fileService(createFileProxy(backgroundScope, clock = clock))

        clock.advance(1)
        assertTrue(fileService.defaultFileSystem.uploadFile("/", "a.txt", byteArrayOf(1)) is TResult.Success)
        val original = assertNotNull(fileService.metaEntity("/a.txt"))
        val originalCreatedAt = assertNotNull(original.get(File.CreatedAt)?.get())
        val originalLastModified = assertNotNull(original.get(File.LastModifiedAt)?.get())

        clock.advance(1_000)
        assertTrue(fileService.defaultFileSystem.replaceFile("/", "a.txt", byteArrayOf(9, 9, 9)) is TResult.Success)

        val after = assertNotNull(fileService.metaEntity("/a.txt"))
        assertEquals(
            originalCreatedAt, after.get(File.CreatedAt)?.get(),
            "an in-place overwrite must NOT move the creation time"
        )
        assertTrue(
            after.get(File.LastModifiedAt)!!.get() > originalLastModified,
            "an overwrite bumps lastModifiedAt"
        )
    }
}