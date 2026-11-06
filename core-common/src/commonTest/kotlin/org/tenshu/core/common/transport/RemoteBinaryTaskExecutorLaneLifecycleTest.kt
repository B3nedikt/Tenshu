package org.tenshu.core.common.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.tenshu.core.StreamElement
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The push lane's lifetime at the two edges `bind()` / `unbind()` do not cover:
 *
 *  - **Automatic reconnect.** When Control restarts, the Binder and UDS transports fire the
 *    reconnect hook without any `bind()`, and the resubscribe replay resets every interactor. A
 *    lane still holding the dead session's backlog would apply an old patch, removal or `End`
 *    between that reset and the fresh snapshot. The hook must rotate the lane *before* it launches
 *    the replay.
 *  - **The owner scope ending.** An executor on [RemoteBinaryTaskExecutor]'s `externalScope` (the
 *    iOS EPC stack) has no `unbind()` to close its lane. Stopping the consumer alone would leave an
 *    open channel that queues every later push for the transport's lifetime — payloads nothing will
 *    ever consume. The lane must be retired with its owner.
 *
 * Every dispatch is deferred and run FIFO on [flush], so the sequence of lane, replay and frames
 * is exact rather than timing-dependent.
 */
class RemoteBinaryTaskExecutorLaneLifecycleTest {

    private class DeferringDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending.addLast(block)
        }

        fun flush() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }

    private class FakeTransport : TaskTransport {
        override val isConnected = true
        override fun bind() {}
        override fun unbind() {}
        override suspend fun call(request: ByteArray): ByteArray = ByteArray(0)
        override fun sendFile(uri: String) {}
        override var onCallback: ((ByteArray) -> Unit)? = null
        private var onReconnected: (() -> Unit)? = null

        override fun setOnReconnected(callback: (() -> Unit)?) {
            onReconnected = callback
        }

        fun deliverPush(bytes: ByteArray) = onCallback?.invoke(bytes)
        fun reconnect() = onReconnected?.invoke()
    }

    private val context = createTestContext(createSyntheticTestRegistry())
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(context.schemaProvider(), context.dataFactory())

    private fun frame(id: Long) = serializer.serialize(StreamElementValueDto(id, "frame".toTString(context)))

    @Test
    fun aReconnect_dropsTheDeadSessionsBacklog_beforeTheReplayRuns() {
        val dispatcher = DeferringDispatcher()
        val transport = FakeTransport()
        val executor = RemoteBinaryTaskExecutor(
            serializer, parser, transport, context,
            externalScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val events = mutableListOf<String>()
        executor.streamElementHandler = { element ->
            if (element is StreamElement<*>) events += "frame ${element.streamId.get()}"
        }
        executor.onReconnected = { events += "replay" }

        transport.deliverPush(frame(1)) // the old proxy's frame, queued — the consumer has not run
        transport.reconnect()           // Control restarted: the hook fires, no bind()
        dispatcher.flush()              // the replay runs; a lane that kept frame 1 would apply it first
        transport.deliverPush(frame(2)) // the new session's first frame — it can only follow the replay
        dispatcher.flush()

        assertEquals(
            listOf("replay", "frame 2"),
            events,
            "frame 1 belongs to the proxy that died; only the replay and the new session's frame may run",
        )
    }

    /**
     * The lane carries every push, not only stream frames: a cross-app `OpenTargetRequest` rides
     * the same handler (`ClientPushDispatch`). A rotation must drop the dead session's *stream*
     * frames and still deliver everything else it had queued — a request queued across a
     * reconnect must still arrive, and losing it means the raised app never navigates.
     */
    @Test
    fun aReconnect_dropsTheDeadSessionsStreamFrames_butStillDeliversItsOtherPushes() {
        val dispatcher = DeferringDispatcher()
        val transport = FakeTransport()
        val executor = RemoteBinaryTaskExecutor(
            serializer, parser, transport, context,
            externalScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val events = mutableListOf<String>()
        executor.streamElementHandler = { pushed ->
            events += if (pushed is StreamElement<*>) "frame ${pushed.streamId.get()}" else "push ${(pushed as org.tenshu.core.TString).get()}"
        }
        executor.onReconnected = { events += "replay" }

        transport.deliverPush(frame(1))                                        // a dead session's delta
        transport.deliverPush(serializer.serialize("open".toTString(context))) // a request in its own right
        transport.reconnect()
        dispatcher.flush()
        transport.deliverPush(frame(2))
        dispatcher.flush()

        assertEquals(listOf("push open", "replay", "frame 2"), events)
    }

    /**
     * Every open ends the lane it displaces — as one atomic `exchange`, so it holds for two opens
     * racing each other as much as for these two in sequence. A displaced lane's queued *stream*
     * frames go with it; only the newest lane delivers.
     */
    @Test
    fun anOpenRetiresTheLaneItDisplaces_andItsQueuedFramesWithIt() {
        val dispatcher = DeferringDispatcher()
        val transport = FakeTransport()
        val executor = RemoteBinaryTaskExecutor(
            serializer, parser, transport, context,
            externalScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val events = mutableListOf<String>()
        executor.streamElementHandler = { element ->
            if (element is StreamElement<*>) events += "frame ${element.streamId.get()}"
        }
        executor.onReconnected = { events += "replay" }

        transport.deliverPush(frame(1)) // lane 1
        transport.reconnect()           // lane 2 displaces lane 1 (frame 1 goes with it)
        transport.deliverPush(frame(2)) // lane 2
        transport.reconnect()           // lane 3 displaces lane 2 (frame 2 goes with it)
        dispatcher.flush()
        transport.deliverPush(frame(3)) // lane 3, the only one left with a consumer
        dispatcher.flush()

        assertEquals(listOf("replay", "replay", "frame 3"), events)
        assertTrue(executor.hasOpenPushLane())
    }

    @Test
    fun theLaneIsRetired_whenItsOwnerScopeEnds() {
        val dispatcher = DeferringDispatcher()
        val transport = FakeTransport()
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val executor = RemoteBinaryTaskExecutor(serializer, parser, transport, context, externalScope = owner)
        assertTrue(executor.hasOpenPushLane(), "an external scope's lane opens with the executor")

        owner.cancel()

        assertFalse(
            executor.hasOpenPushLane(),
            "with its owner gone, a lane would only retain every later push for the transport's lifetime",
        )
    }
}
