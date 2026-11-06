package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.evaluateCallstack
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.file.CaptureLatE7
import org.tenshu.core.file.CaptureLngE7
import org.tenshu.core.file.Category
import org.tenshu.core.file.ContentHash
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.PixelHeight
import org.tenshu.core.file.PixelWidth
import org.tenshu.core.file.Size
import org.tenshu.core.file.files
import org.tenshu.core.file.notTrashed
import org.tenshu.core.file.path
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.relation.Property
import org.tenshu.core.request.RequestObject
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A projection of several stored value properties reads each row from the store **once**, not once
 * per property (`photos-app.md` §39).
 *
 * ## ⚠⚠ Why this counts store reads instead of timing
 *
 * The defect was invisible in every result: `Select` resolved each `relations(…)` entry with its own
 * `getData` — a full read and decode of the row — so the Photos content index (path + nine
 * properties over every file in the realm) paid **9.2 store reads per file** where a path-only walk
 * pays 2.0. It answered correctly and took seconds on a phone. Only the number of reads says it.
 *
 * ⚠ The parity test is the half a counting test must not lose: one read per row is only an
 * improvement if every property still answers exactly what it answered on its own.
 */
class SelectReadsEachRowOnceTest {

    private class CountingStore(private val delegate: ObjectStore) : ObjectStore by delegate {
        var gets = 0
            private set
        override fun getObject(dataIdHash: Long): ObjectStore.DbEntry? {
            gets++
            return delegate.getObject(dataIdHash)
        }
    }

    /** The content index's own nine properties — the projection that exposed the defect. */
    @Suppress("UNCHECKED_CAST")
    private val indexProperties: List<Property<Data>> = listOf<Property<*>>(
        File.ContentHash, File.ContentId, File.CreatedAt, File.Category, File.Size,
        File.CaptureLatE7, File.CaptureLngE7, File.PixelWidth, File.PixelHeight,
    ).map { it as Property<Data> }

    private suspend fun seeded(store: CountingStore, proxy: TestProxy): FileService {
        val fs = service(proxy)
        fs.defaultFileSystem.createFolder("/Camera")
        repeat(FILES) { i ->
            val result = fs.defaultFileSystem.uploadFile(
                parentPath = if (i % 2 == 0) "/" else "/Camera",
                fileName = "IMG_$i.jpg",
                data = byteArrayOf(1, 2, 3, i.toByte()),
                sourceModifiedAt = 1_700_000_000_000L - i * 60_000L,
                // Every other photo has a place, so absent properties are exercised too.
                captureLatE7 = if (i % 2 == 0) 480_000_000L + i else null,
                captureLngE7 = if (i % 2 == 0) 110_000_000L + i else null,
            )
            check(result is TResult.Success) { "seed $i: $result" }
        }
        return fs
    }

    @Test
    fun aNinePropertyProjection_readsEachRowOnce_notOncePerProperty() = runTest {
        val store = CountingStore(InMemoryObjectStore())
        val proxy = createFileProxy(coroutineScope = this, objectStore = store)
        val fs = seeded(store, proxy)

        val pathOnly = fs.defaultFileSystem.root.files.traverse()
            .filter<File> { notTrashed() }
            .select { fields(File.Field.PATH) }
        val indexShaped = fs.defaultFileSystem.root.files.traverse()
            .filter<File> { notTrashed() }
            .select {
                fields(File.Field.PATH)
                relations(*indexProperties.toTypedArray())
            }

        val before = store.gets
        val rows = proxy.evaluate(pathOnly).size
        val walk = store.gets - before
        val mid = store.gets
        assertEquals(rows, proxy.evaluate(indexShaped).size)
        val projected = store.gets - mid

        // The walk itself (the arrays, each row, the trash predicate) is the same for both chains;
        // what the nine properties may add is ONE read of each row. Master added nine.
        val extraPerRow = (projected - walk).toDouble() / rows
        assertTrue(
            extraPerRow <= 1.0,
            "nine stored properties cost ${"%.1f".format(extraPerRow)} extra store reads per row " +
                "($projected vs a path-only $walk over $rows rows) — each row must be read once, " +
                "not once per property",
        )
    }

    @Test
    fun everyPropertyAnswersWhatItAnswersOnItsOwn() = runTest {
        val store = CountingStore(InMemoryObjectStore())
        val proxy = createFileProxy(coroutineScope = this, objectStore = store)
        val fs = seeded(store, proxy)

        val together = proxy.evaluate(
            fs.defaultFileSystem.root.files.traverse().select {
                fields(File.Field.PATH)
                relations(*indexProperties.toTypedArray())
            },
        ).associate { row -> row.pathOf() to indexProperties.map { (row as Entity<*>).get(it) } }

        indexProperties.forEachIndexed { i, property ->
            val alone = proxy.evaluate(
                fs.defaultFileSystem.root.files.traverse().select {
                    fields(File.Field.PATH)
                    relations(property)
                },
            ).associate { row -> row.pathOf() to (row as Entity<*>).get(property) }
            assertEquals(alone.keys, together.keys)
            alone.forEach { (path, value) ->
                assertEquals(value?.toString(), together.getValue(path)[i]?.toString(), "$path ${property.id}")
            }
        }
        // ⚠ And the fixture must actually carry values and absences, or parity proves nothing.
        assertTrue(together.values.any { it[5] != null } && together.values.any { it[5] == null && it[2] != null })
    }

    private fun Data.pathOf(): String = (this as Entity<*>).getFieldValue<org.tenshu.core.TString>(File.Field.PATH).get()

    private fun TestProxy.evaluate(chain: TList<*>): List<Data> =
        (evaluateCallstack(
            executionContext, null, (chain as RequestObject).requestChain.getCallstack(), allowDeferring = false,
        ) as TList<*>).toList().map { it as Data }

    private fun service(proxy: TestProxy): FileService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        return TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        ).getService(FileService.ID)
    }

    private companion object {
        const val FILES = 24
    }
}
