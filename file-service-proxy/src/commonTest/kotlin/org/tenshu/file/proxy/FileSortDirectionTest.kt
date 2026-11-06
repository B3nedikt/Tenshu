package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.sortBy
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.thenBy
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.FakeClock
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Size
import org.tenshu.core.file.files
import org.tenshu.core.file.name
import org.tenshu.core.file.path
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.getOrNull
import org.tenshu.core.lowercase
import org.tenshu.core.relation.Property
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The **direction** half of a value-property sort, over the real client → proxy
 * chain — Drive's "Sort by → Date modified / Date created / Size" data path.
 *
 * ⚠⚠ [SortByPropertyTest] drives the same operation with a hand-built `TList` of
 * in-memory entities, which is why it stayed green while the feature was broken:
 * those rows carry their properties inline. A row read back through
 * `defaultFileSystem.root.files` does not — the array reference is
 * `isEntity = false` (`File : Composite`, not an `Entity` subtype), so the rows
 * deserialise as plain composites and the engine's key read found nothing on
 * them. Every key was `null`, every comparison tied, and both directions returned
 * the identical list.
 *
 * Every assertion below therefore checks BOTH directions: a single direction can
 * coincide with encounter order and pass while the toggle does nothing.
 */
class FileSortDirectionTest {

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

    /**
     * Uploads b/a/c at strictly increasing clock times and with strictly
     * increasing byte sizes, so *no* key agrees with any other: upload order is
     * b, a, c; name order is a, b, c; and both differ from their own reverses.
     * A sort that ignores its key cannot accidentally satisfy an assertion.
     */
    private suspend fun seededService(): FileService {
        val clock = FakeClock(initial = 1_000L)
        val fileService = fileService(createFileProxy(clock = clock))
        listOf("b.txt" to 1, "a.txt" to 2, "c.txt" to 3).forEach { (name, size) ->
            clock.advance(10)
            assertTrue(
                fileService.defaultFileSystem.uploadFile("/", name, ByteArray(size)) is TResult.Success,
                "upload of $name should succeed",
            )
        }
        return fileService
    }

    private suspend fun FileService.namesSortedBy(
        property: Property.Value<*>,
        descending: Boolean,
        tieBreakByName: Boolean,
    ): List<String> {
        val base = defaultFileSystem.root.files
        val sorted = if (descending) base.sortByDescending(property) else base.sortBy(property)
        // Drive's own shape folds a name tie-break into non-name keys.
        val chain = if (tieBreakByName) sorted.thenBy { name.lowercase() } else sorted
        return chain
            .select { fields(File.Field.PATH) }
            .getAll()
            .getOrNull()
            ?.toList()
            ?.map { (it as Entity<out File>).path.get().substringAfterLast("/") }
            .orEmpty()
    }

    @Test
    fun lastModifiedAt_reversesWithTheDirection() = runTest {
        val fileService = seededService()

        assertEquals(
            listOf("b.txt", "a.txt", "c.txt"),
            fileService.namesSortedBy(File.LastModifiedAt, descending = false, tieBreakByName = false),
            "oldest first",
        )
        assertEquals(
            listOf("c.txt", "a.txt", "b.txt"),
            fileService.namesSortedBy(File.LastModifiedAt, descending = true, tieBreakByName = false),
            "newest first",
        )
    }

    @Test
    fun lastModifiedAt_reversesWithTheDirection_underDrivesNameTieBreak() = runTest {
        // ⚠⚠ The exact chain `TenshuFilesRepository.applySort` builds. The tie-break
        // is what made the failure look plausible rather than obviously broken: with
        // a dead primary key every row ties and the always-ascending name key
        // decides, so the screen showed a, b, c whichever direction was picked.
        val fileService = seededService()

        assertEquals(
            listOf("b.txt", "a.txt", "c.txt"),
            fileService.namesSortedBy(File.LastModifiedAt, descending = false, tieBreakByName = true),
        )
        assertEquals(
            listOf("c.txt", "a.txt", "b.txt"),
            fileService.namesSortedBy(File.LastModifiedAt, descending = true, tieBreakByName = true),
        )
    }

    @Test
    fun createdAt_reversesWithTheDirection() = runTest {
        val fileService = seededService()

        assertEquals(
            listOf("b.txt", "a.txt", "c.txt"),
            fileService.namesSortedBy(File.CreatedAt, descending = false, tieBreakByName = true),
        )
        assertEquals(
            listOf("c.txt", "a.txt", "b.txt"),
            fileService.namesSortedBy(File.CreatedAt, descending = true, tieBreakByName = true),
        )
    }

    @Test
    fun traversedListing_ordersByLastModified_theRecentFilesShape() = runTest {
        // Home's "Recent files" (`TenshuFilesRepository.recentFiles`):
        // `traverse().sortByDescending(File.LastModifiedAt).take(n)`. Same dead key,
        // different screen — the "recent" list was in traversal order, so the newest
        // file was recent only by coincidence.
        val fileService = seededService()

        val newestFirst = fileService.defaultFileSystem.root.files
            .traverse()
            .sortByDescending(File.LastModifiedAt)
            .take(2)
            .select { fields(File.Field.PATH) }
            .getAll()
            .getOrNull()
            ?.toList()
            ?.map { (it as Entity<out File>).path.get().substringAfterLast("/") }

        assertEquals(listOf("c.txt", "a.txt"), newestFirst, "the two most recently modified")
    }

    @Test
    fun size_reversesWithTheDirection() = runTest {
        // Never reported, equally broken: Size is a stored value property too.
        val fileService = seededService()

        assertEquals(
            listOf("b.txt", "a.txt", "c.txt"),
            fileService.namesSortedBy(File.Size, descending = false, tieBreakByName = true),
            "1, 2, 3 bytes",
        )
        assertEquals(
            listOf("c.txt", "a.txt", "b.txt"),
            fileService.namesSortedBy(File.Size, descending = true, tieBreakByName = true),
            "3, 2, 1 bytes",
        )
    }
}
