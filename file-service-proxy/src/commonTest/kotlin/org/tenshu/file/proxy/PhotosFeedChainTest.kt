package org.tenshu.file.proxy

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.equalTo
import org.tenshu.core.file.Category
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.FileService
import org.tenshu.core.file.category
import org.tenshu.core.file.createdAt
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.fold
import org.tenshu.core.get
import org.tenshu.core.inRange
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The Photos feed's read path, proven before it is built** (`photos-app.md` PH-G1 — the one gap
 * the doc calls blocking).
 *
 * The feed is a flat, newest-first grid of every image in the realm, paged. `photos-app.md` argues
 * it should ride the chain DSL rather than `photosCapturedBetween`, whose own KDoc admits to being
 * an O(files-in-realm) scan that returns three scalars and cannot page at all. That argument had a
 * hole: **no predicate in this repo had ever referenced a stored value property.** Every one of
 * them — Drive's search, Mia's lowering, the `path equalTo` reads — goes through `File.path` (a
 * stored *field*) or `File.name` (a cheap dynamic property). Whether the engine resolves a
 * `Property.Value` reference inside a predicate was simply unknown, and the whole page shape rests
 * on it.
 *
 * So this pins the four things the feed needs, in the exact chain shape it will ship:
 *
 * 1. `category equalTo FileCategory.Image` — a **constant-valued** value property in a predicate.
 * 2. `createdAt.inRange(...)` — an **integer-valued** value property in a predicate, which is the
 *    keyset cursor.
 * 3. `sortByDescending(File.CreatedAt).take(n)` after both filters — the `core-ordered-window-fusion.md`
 *    F2 window, whose only shipped consumer so far is Drive's listing.
 * 4. Page 2 starting exactly where page 1 stopped: no row served twice, none skipped.
 *
 * ⚠ Two filter steps, not one: the chain DSL has no boolean combinator (`EqualTo`, `Contains` and
 * `InRange` return `TBoolean` and nothing consumes two of them), so *category ∧ window* is two
 * chained predicates. That is a shape fact worth pinning — a future `and` would let these collapse,
 * and this test should keep passing when it does.
 *
 * ⚠ This is a **correctness** pin, not a performance one. The cost question PH-G1 also asks — what
 * a cold page costs at 20 000 rows, where `traverse()` loads every reachable node — is a
 * measurement on real hardware, not a unit test, and the doc says so.
 */
class PhotosFeedChainTest {

    private val noon = 1_700_000_000_000L
    private val minute = 60_000L

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
     * The feed page, verbatim as `:photos-shared-logic` will issue it: every image in the realm,
     * newest first, bounded — with [beforeMs] as the keyset cursor (exclusive upper bound), so
     * page N+1 asks for the same thing with the last row's capture time.
     */
    private suspend fun FileService.feedPage(
        beforeMs: Long = Long.MAX_VALUE,
        pageSize: Int = 3,
    ): List<Pair<String, Long>> =
        defaultFileSystem.root.files
            .traverse()
            .filter<File> { category equalTo FileCategory.Image }
            .filter<File> { createdAt.inRange(0L, beforeMs) }
            .sortByDescending(File.CreatedAt)
            .take(pageSize)
            .select {
                fields(File.Field.PATH)
                relations(File.CreatedAt, File.Category)
            }
            .flow()
            .first()
            .fold(
                onSuccess = { list ->
                    list.toList().map { row ->
                        @Suppress("UNCHECKED_CAST")
                        val entity = row as Entity<File>
                        entity.path.get() to
                            (entity.get(File.CreatedAt)?.get() ?: 0L)
                    }
                },
                onFailure = { error("the feed chain failed on the proxy: $it") },
            )

    private suspend fun FileService.upload(parent: String, name: String, capturedAtMs: Long) {
        assertTrue(
            defaultFileSystem.uploadFile(
                parent, name, byteArrayOf(1, 2, 3), sourceModifiedAt = capturedAtMs,
            ).let { it is org.tenshu.core.TResult.Success },
            "seeding $parent/$name should succeed",
        )
    }

    /** A realm shaped like a backed-up phone: images at two depths, and non-images beside them. */
    private suspend fun FileService.seedRealm() {
        defaultFileSystem.createFolder("/Backup")
        defaultFileSystem.createFolder("/Backup/Camera")
        upload("/Backup/Camera", "IMG_0001.jpg", noon - 5 * minute)
        upload("/Backup/Camera", "IMG_0002.jpg", noon - 4 * minute)
        upload("/Backup/Camera", "IMG_0003.jpg", noon - 3 * minute)
        upload("/", "IMG_0004.jpg", noon - 2 * minute)
        upload("/", "IMG_0005.jpg", noon - 1 * minute)
        // Not photographs, and each is a different reason to be excluded.
        upload("/", "notes.txt", noon)
        upload("/Backup/Camera", "clip.mp4", noon)
    }

    @Test
    fun theFeedPageIsEveryImageNewestFirst_boundedByPageSize() = runTest {
        val service = fileService(createFileProxy(backgroundScope))
        service.seedRealm()

        val page = service.feedPage(pageSize = 3)

        assertEquals(
            listOf("/IMG_0005.jpg", "/IMG_0004.jpg", "/Backup/Camera/IMG_0003.jpg"),
            page.map { it.first },
            "newest first, across folder depths, capped at the page size",
        )
    }

    @Test
    fun nonImagesNeverReachTheFeed() = runTest {
        val service = fileService(createFileProxy(backgroundScope))
        service.seedRealm()

        // Wide enough to hold everything seeded, so anything missing is the predicate's doing.
        val everything = service.feedPage(pageSize = 50)

        assertEquals(5, everything.size, "five images were seeded")
        assertTrue(
            everything.none { it.first.endsWith(".txt") || it.first.endsWith(".mp4") },
            "a document and a video share the folder and the minute; neither is an image: $everything",
        )
        // ⚠ The folders themselves are rows too, and `traverse()` returns them — the category
        // predicate is the only thing keeping `/Backup` out of a photo grid.
        assertTrue(
            everything.none { it.first == "/Backup" || it.first == "/Backup/Camera" },
            "folders must not appear in the feed: $everything",
        )
    }

    @Test
    fun theSecondPageStartsWhereTheFirstStopped() = runTest {
        val service = fileService(createFileProxy(backgroundScope))
        service.seedRealm()

        val first = service.feedPage(pageSize = 2)
        val cursor = first.last().second
        val second = service.feedPage(beforeMs = cursor, pageSize = 2)

        assertEquals(listOf("/IMG_0005.jpg", "/IMG_0004.jpg"), first.map { it.first })
        assertEquals(
            listOf("/Backup/Camera/IMG_0003.jpg", "/Backup/Camera/IMG_0002.jpg"),
            second.map { it.first },
            "the keyset cursor is exclusive: the last row of page 1 must not repeat on page 2",
        )
        assertTrue(
            first.map { it.first }.intersect(second.map { it.first }.toSet()).isEmpty(),
            "no row may be served twice",
        )
    }

    @Test
    fun anEmptyWindowKeepsNothingRatherThanEverything() = runTest {
        val service = fileService(createFileProxy(backgroundScope))
        service.seedRealm()

        // The cursor a caller lands on after paging past the oldest row. Reading an exhausted
        // window as "no bound" would restart the feed from the top forever.
        assertEquals(emptyList(), service.feedPage(beforeMs = 0L, pageSize = 10))
    }
}
