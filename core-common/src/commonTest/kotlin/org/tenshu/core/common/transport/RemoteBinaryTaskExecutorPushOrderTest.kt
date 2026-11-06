package org.tenshu.core.common.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import org.tenshu.core.StreamElement
import org.tenshu.core.StreamElementDispatcher
import org.tenshu.core.common.app.wireClientPushDispatch
import org.tenshu.core.common.StreamElementReceipt
import org.tenshu.core.common.StreamElementReceipts
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.toTString
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The wire-client half of the forced pair behind `docs/tech-debt.md`'s gate-flake sighting of
 * 2026-09-14 (second of the day). The in-process half is
 * `FileDetailsDeferredPreviewForcedInterleavingTest` (`:drive-shared-ui`).
 *
 * The proxy sends stream frames in order — a snapshot, then the deferred patch that amends one of
 * its rows — and every transport hands them to [RemoteBinaryTaskExecutor]'s `onCallback` in that
 * order. What the executor did with them was `scope.launch { parse; dispatch }` **per frame**: two
 * coroutines with no order between them, so on a multi-threaded dispatcher the patch could reach
 * the client's interactor before the snapshot it patches.
 *
 * [ReversingDispatcher] is the worst case that dispatcher can produce, made deterministic: every
 * dispatched block is deferred, and [ReversingDispatcher.flush] runs them last-first. Red with the
 * launch per frame (the handler sees the second frame first); green with the single ordered
 * consumer, where the only thing ever dispatched is that consumer.
 */
class RemoteBinaryTaskExecutorPushOrderTest {

    private class ReversingDispatcher : CoroutineDispatcher() {
        private val pending = mutableListOf<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending.add(block)
        }

        /** Run everything deferred so far, newest first — and whatever that dispatches, likewise. */
        fun flush() {
            while (pending.isNotEmpty()) {
                val batch = pending.toList().asReversed()
                pending.clear()
                batch.forEach { it.run() }
            }
        }
    }

    private class FakeTransport : TaskTransport {
        override val isConnected = true
        override fun bind() {}
        override fun unbind() {}
        override suspend fun call(request: ByteArray): ByteArray = ByteArray(0)
        override fun sendFile(uri: String) {}
        override var onCallback: ((ByteArray) -> Unit)? = null

        fun deliverPush(bytes: ByteArray) = onCallback?.invoke(bytes)
    }

    @Test
    fun pushesReachTheHandlerInWireOrder_whateverOrderTheirCoroutinesAreRunIn() {
        val context = createTestContext(createSyntheticTestRegistry())
        val serializer = BinarySerializer()
        val parser = BinaryParser(context.schemaProvider(), context.dataFactory())
        val transport = FakeTransport()
        val dispatcher = ReversingDispatcher()

        val executor = RemoteBinaryTaskExecutor(
            serializer = serializer,
            parser = parser,
            transport = transport,
            context = context,
            externalScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val received = mutableListOf<Long>()
        executor.streamElementHandler = { element ->
            if (element is StreamElement<*>) received += element.streamId.get()
        }

        // Two frames, in wire order, before any coroutine gets to run.
        transport.deliverPush(serializer.serialize(StreamElementValueDto(1L, "snapshot".toTString(context))))
        transport.deliverPush(serializer.serialize(StreamElementValueDto(2L, "patch".toTString(context))))
        dispatcher.flush()

        assertEquals(
            listOf(1L, 2L),
            received,
            "the handler must see frames in the order the transport delivered them, " +
                "not in the order their coroutines happened to run",
        )
    }

    /**
     * The receipt `DefaultInteractorProvider` routes by is taken when the transport hands a frame
     * over, not when the consumer gets to it: a frame received before a re-subscription registered
     * must read as received before it however long it then waited in the lane, or the provider
     * hands an ended subscription's snapshot to the next one of the same id
     * (`StreamResubscribeStaleBacklogTest` is the in-process half, end to end).
     */
    @Test
    fun aFramesReceiptIsTakenOnArrival_notWhenTheConsumerReachesIt() {
        val context = createTestContext(createSyntheticTestRegistry())
        val serializer = BinarySerializer()
        val parser = BinaryParser(context.schemaProvider(), context.dataFactory())
        val transport = FakeTransport()
        val dispatcher = ReversingDispatcher()

        val executor = RemoteBinaryTaskExecutor(
            serializer = serializer,
            parser = parser,
            transport = transport,
            context = context,
            externalScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val receipts = mutableMapOf<Long, Long?>()
        executor.streamElementHandler = { element ->
            if (element is StreamElement<*>) {
                receipts[element.streamId.get()] = currentCoroutineContext()[StreamElementReceipt]?.stamp
            }
        }

        transport.deliverPush(serializer.serialize(StreamElementValueDto(1L, "before".toTString(context))))
        // A registration made now — while the first frame is still queued behind the consumer.
        val registeredAt = StreamElementReceipts.current()
        transport.deliverPush(serializer.serialize(StreamElementValueDto(2L, "after".toTString(context))))
        dispatcher.flush()

        val before = assertNotNull(receipts[1L], "the first frame reached the handler with no receipt")
        val after = assertNotNull(receipts[2L], "the second frame reached the handler with no receipt")
        assertTrue(before <= registeredAt, "a frame received before the registration read as after it ($before > $registeredAt)")
        assertTrue(after > registeredAt, "a frame received after the registration read as before it ($after <= $registeredAt)")
    }

    /**
     * The shared production push handler (`wireClientPushDispatch`) must hand the dispatcher the
     * receipt: it reaches `DefaultInteractorProvider` only through the coroutine context, so a launch
     * or hop added in the handler would lose it, and the provider would route by id alone again.
     */
    @Test
    fun theSharedPushHandlerDeliversTheReceiptToTheDispatcher() {
        val context = createTestContext(createSyntheticTestRegistry())
        val serializer = BinarySerializer()
        val parser = BinaryParser(context.schemaProvider(), context.dataFactory())
        val transport = FakeTransport()
        val dispatcher = ReversingDispatcher()
        val executor = RemoteBinaryTaskExecutor(
            serializer = serializer,
            parser = parser,
            transport = transport,
            context = context,
            externalScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val receipts = mutableListOf<Long?>()
        executor.wireClientPushDispatch(
            streams = {
                object : StreamElementDispatcher {
                    override suspend fun dispatchElement(element: StreamElement<*>) {
                        receipts += currentCoroutineContext()[StreamElementReceipt]?.stamp
                    }
                }
            },
        )

        transport.deliverPush(serializer.serialize(StreamElementValueDto(1L, "row".toTString(context))))
        dispatcher.flush()

        assertEquals(1, receipts.size, "the element reached the dispatcher")
        assertNotNull(receipts.single(), "the element reached the dispatcher without its receipt")
    }
}
