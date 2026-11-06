package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.entity.DataId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A session's streams share one [ReferenceResolver], and each re-evaluates on its own coroutine
 * (`StreamRegistry.triggerUpdate` launches one per stream), so two [ReferenceResolver.collectingReferences] scopes
 * routinely overlap on different threads. The collector used to be a plain field of the resolver:
 * the scope entered second overwrote the first one's collector, so the first stream's reads were
 * recorded for the second stream, and the first one's exit then cleared the field under the second.
 *
 * A stream that comes out of that with a reference missing unsubscribes from the array, and
 * never hears of it again — a listing that silently stops updating. One write that touches two
 * observed arrays is enough to overlap the scopes (an upload adds the file to its folder and to
 * a second array a sibling stream watches), which is what `FilesScreenIntegrationTest` kept
 * failing on.
 *
 * JVM-only: the interleaving needs two real threads, each held at a latch.
 */
class ReferenceCollectorConfinementTest {

    @Test
    fun overlappingScopesOnTwoThreadsEachCollectOnlyTheirOwnReads() {
        val resolver = createResolver()
        val firstRef = makeArrayRef("/first")
        val secondRef = makeArrayRef("/second")
        val first = mutableSetOf<ArrayDatabaseReference>()
        val second = mutableSetOf<ArrayDatabaseReference>()

        val firstEntered = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val firstLeft = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)

        // first:  enter ──────────────── read, leave
        // second:        enter ────────────────────── read, leave
        val firstThread = thread(name = "first-stream") {
            runCatching {
                resolver.collectingReferences(into = first) {
                    firstEntered.countDown()
                    secondEntered.awaitOrFail()
                    resolver.getArrayData(firstRef)
                }
                firstLeft.countDown()
            }.onFailure { failure.set(it) }
        }
        val secondThread = thread(name = "second-stream") {
            runCatching {
                firstEntered.awaitOrFail()
                resolver.collectingReferences(into = second) {
                    secondEntered.countDown()
                    firstLeft.awaitOrFail()
                    resolver.getArrayData(secondRef)
                }
            }.onFailure { failure.set(it) }
        }
        firstThread.join(JOIN_MS)
        secondThread.join(JOIN_MS)

        assertNull(failure.get(), "neither thread may fail")
        assertEquals(setOf(firstRef), first, "the first scope's read belongs to the first scope")
        assertEquals(setOf(secondRef), second, "the second scope's read belongs to the second scope")
    }

    @Test
    fun aReadOutsideAnyScopeIsNotRecordedIntoAnotherThreadsScope() {
        val resolver = createResolver()
        val observed = makeArrayRef("/observed")
        val unrelated = makeArrayRef("/unrelated")
        val collected = mutableSetOf<ArrayDatabaseReference>()

        val entered = CountDownLatch(1)
        val unrelatedRead = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)

        val evaluating = thread(name = "stream-evaluation") {
            runCatching {
                resolver.collectingReferences(into = collected) {
                    resolver.getArrayData(observed)
                    entered.countDown()
                    unrelatedRead.awaitOrFail()
                }
            }.onFailure { failure.set(it) }
        }
        // An ordinary request on another thread, while the stream is mid-evaluation.
        entered.awaitOrFail()
        resolver.getArrayData(unrelated)
        unrelatedRead.countDown()
        evaluating.join(JOIN_MS)

        assertNull(failure.get(), "the evaluating thread may not fail")
        assertEquals(setOf(observed), collected)
    }

    private fun CountDownLatch.awaitOrFail() =
        assertTrue(await(JOIN_MS, TimeUnit.MILLISECONDS), "the other thread never reached its latch")

    private fun createResolver(): ReferenceResolver {
        val schemaRegistry = createCoreTestRegistry()
        val tContext = createTestContext(schemaRegistry)
        return ReferenceResolverImpl(
            context = tContext,
            database = object : Database {
                override fun getData(dataId: DataId, asEntity: Boolean, projection: Projection?): Data? = null
                override fun saveData(data: Data) {}
                override fun deleteData(dataId: DataId) {}
                override fun getArrayData(reference: ArrayDatabaseReference, projection: Projection?): Sequence<Data>? =
                    emptySequence()
                override fun getComposite(reference: CompositeDatabaseReference): Data? = null
                override fun addToArray(reference: ArrayDatabaseReference, value: Data, origin: WriteOrigin) {}
                override fun removeFromArray(reference: ArrayDatabaseReference, dataId: DataId, origin: WriteOrigin) {}
                override fun setArrayListener(
                    reference: ArrayDatabaseReference,
                    onAdded: (Data, WriteOrigin) -> Unit,
                    onRemoved: (DataId, WriteOrigin) -> Unit,
                ): Long = 0L
                override fun removeArrayListener(handle: Long) {}
                override fun <R> transaction(block: () -> R): R = block()
            },
            materializer = CallstackMaterializer(schemaRegistry, tContext),
        )
    }

    private fun makeArrayRef(path: String): ArrayDatabaseReference = ArrayDatabaseReference(
        arrayId = DataId.EntityFieldDataId.create(
            type = TList.ID,
            parentEntity = DataId.StringDataId.create(type = "tenshu/file", keyValue = path),
            fieldRef = "tenshu/file/files",
        ),
        isEntity = false,
    )

    private companion object {
        /** Only elapses when a thread is stuck; every latch is released by the other thread. */
        const val JOIN_MS = 10_000L
    }
}
