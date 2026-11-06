package org.tenshu.core.common.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tenshu.core.StreamElement
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A frame handed to the executor while it is unbound belongs to the session that just ended and
 * must never reach the consumer the next `bind()` starts.
 *
 * Android recreates activities routinely — `client.destroy()` unbinds, the next `client.init()`
 * rebinds — and a Binder callback can be in flight across that edge. A single shared channel would
 * keep such a frame: enqueued after `unbind()` drained it, it would sit there until the next bind's
 * consumer delivered it into a new session. So every bind opens its own lane — channel plus
 * consumer — and `unbind()` closes the old lane's channel, which makes any late enqueue fail
 * instead of survive.
 */
class RemoteBinaryTaskExecutorRebindTest {

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
    fun aFrameHandedToAnUnboundExecutor_neverReachesTheNextBindsConsumer() = runBlocking {
        val context = createTestContext(createSyntheticTestRegistry())
        val serializer = BinarySerializer()
        val parser = BinaryParser(context.schemaProvider(), context.dataFactory())
        val transport = FakeTransport()
        val executor = RemoteBinaryTaskExecutor(serializer, parser, transport, context)
        val received = Channel<Long>(Channel.UNLIMITED)
        executor.streamElementHandler = { element ->
            if (element is StreamElement<*>) received.trySend(element.streamId.get())
        }
        fun frame(id: Long) = serializer.serialize(StreamElementValueDto(id, "frame".toTString(context)))

        executor.bind()
        transport.deliverPush(frame(1))
        assertEquals(1L, withTimeout(5_000) { received.receive() }, "a bound executor delivers")

        executor.unbind()
        transport.deliverPush(frame(2)) // the old session's frame, arriving across the edge

        executor.bind()
        transport.deliverPush(frame(3))
        assertEquals(3L, withTimeout(5_000) { received.receive() }, "the rebound executor delivers again")

        delay(200)
        assertTrue(
            received.tryReceive().isFailure,
            "frame 2 was handed to an unbound executor and must never be delivered, before or after the rebind",
        )
        executor.unbind()
    }
}
