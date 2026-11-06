package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.SortBy
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Size
import org.tenshu.core.file.path
import org.tenshu.core.toTBoolean
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Engine-level test for the `sortBy(property)` overload (Path A,
 * docs/proposals/core-chain-sort.md §2.2 / file-service-history §9.9). Drives
 * [org.tenshu.core.common.proxy.SortByImplementation] with a single
 * `StaticPropertyRelation` selector (what `sortByProperty` records) and asserts the list
 * orders by the **value property** read off each entity via `getProperty` — the
 * path that lets `allFiles.sortByDescending(File.LastModifiedAt)` work.
 */
class SortByPropertyTest {

    @Test
    fun sortsByValueProperty_descending() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val ctx = proxy.tContext

        fun file(path: String, lastModified: Long): Entity<File> =
            Entity.create(ctx, File.create(ctx, path)) {
                set(File.Size, 0L)
                set(File.LastModifiedAt, lastModified)
            }

        val caller = listOf(
            file("/a", 100L),
            file("/b", 300L),
            file("/c", 200L),
        ).toTList(ctx)

        val result = proxy.executionContext.execute<Data>(
            operationId = SortBy.ID,
            caller = caller,
            params = mapOf(
                SortBy.Params.SELECTOR to SimpleTList(listOf<Data>(File.LastModifiedAt.selector())),
                SortBy.Params.DESCENDING to true.toTBoolean(ctx),
            ),
        )

        val paths = (result as TList<*>).toList()
            .map { (it as Entity<out File>).path.get() }
        assertEquals(listOf("/b", "/c", "/a"), paths, "ordered by lastModifiedAt desc (300,200,100)")
    }

    @Test
    fun sortsByValueProperty_ascending_missingKeysLast() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val ctx = proxy.tContext

        val withKey = Entity.create<File>(ctx, File.create(ctx, "/keyed")) {
            set(File.Size, 0L)
            set(File.LastModifiedAt, 50L)
        }
        // No LastModifiedAt property set -> sorts last in ascending order.
        val withoutKey = Entity.create<File>(ctx, File.create(ctx, "/bare")) {
            set(File.Size, 0L)
        }

        val result = proxy.executionContext.execute<Data>(
            operationId = SortBy.ID,
            caller = listOf(withoutKey, withKey).toTList(ctx),
            params = mapOf(
                SortBy.Params.SELECTOR to SimpleTList(listOf<Data>(File.LastModifiedAt.selector())),
                SortBy.Params.DESCENDING to false.toTBoolean(ctx),
            ),
        )

        val paths = (result as TList<*>).toList()
            .map { (it as Entity<out File>).path.get() }
        assertEquals(listOf("/keyed", "/bare"), paths, "present key first, missing key last")
    }

    @Test
    fun sortsLexicographically_primaryThenSecondaryKey() = runTest {
        // `thenBy` folds a secondary key into SortBy's THEN_* tail; the engine builds
        // a lexicographic comparator (primary first, then each secondary with its own
        // direction). Here: Size ASC, then LastModifiedAt DESC.
        val proxy = createFileProxy(backgroundScope)
        val ctx = proxy.tContext

        fun file(path: String, size: Long, lastModified: Long): Entity<File> =
            Entity.create(ctx, File.create(ctx, path)) {
                set(File.Size, size)
                set(File.LastModifiedAt, lastModified)
            }

        val caller = listOf(
            file("/a", size = 10L, lastModified = 100L),
            file("/b", size = 10L, lastModified = 300L),
            file("/c", size = 5L, lastModified = 200L),
        ).toTList(ctx)

        val result = proxy.executionContext.execute<Data>(
            operationId = SortBy.ID,
            caller = caller,
            params = mapOf(
                SortBy.Params.SELECTOR to SimpleTList(listOf<Data>(File.Size.selector())),
                SortBy.Params.DESCENDING to false.toTBoolean(ctx),
                // THEN_SELECTORS is a list of callstacks; one secondary key here.
                SortBy.Params.THEN_SELECTORS to SimpleTList(
                    listOf<Data>(SimpleTList(listOf<Data>(File.LastModifiedAt.selector())))
                ),
                SortBy.Params.THEN_DESCENDINGS to SimpleTList(listOf<Data>(true.toTBoolean(ctx))),
            ),
        )

        val paths = (result as TList<*>).toList()
            .map { (it as Entity<out File>).path.get() }
        // Size asc puts /c (5) first; the size-10 tie breaks by LastModifiedAt desc
        // → /b (300) before /a (100).
        assertEquals(listOf("/c", "/b", "/a"), paths, "primary Size asc, tie-break LastModifiedAt desc")
    }
}
