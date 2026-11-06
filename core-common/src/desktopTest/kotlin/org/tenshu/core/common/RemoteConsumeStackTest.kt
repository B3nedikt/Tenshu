package org.tenshu.core.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tenshu.core.StreamElement
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.transport.TaskTransport
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.toTString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteConsumeStackTest {

    /** A transport that lets the test deliver a server push by invoking [onCallback] directly. */
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
    fun routes_a_pushed_stream_element_through_the_assembled_executor() = runBlocking {
        val context = createTestContext(createSyntheticTestRegistry())
        val serializer = BinarySerializer()
        val parser = BinaryParser(context.schemaProvider(), context.dataFactory())
        val transport = FakeTransport()

        val stack = assembleRemoteConsumeStack(
            transport, serializer, parser, context,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

        // The helper wires a push handler (to the provider/dispatcher) and an interactor provider.
        assertTrue(stack.executor.streamElementHandler != null)

        // Observe the parsed element a transport push yields (replace the provider hop, which would
        // no-op for an unregistered streamId — registering a real interactor needs a live op).
        val received = CompletableDeferred<StreamElement<*>>()
        stack.executor.streamElementHandler = { if (it is StreamElement<*>) received.complete(it) }

        val payload = "badge=3".toTString(context)
        transport.deliverPush(serializer.serialize(StreamElementValueDto(streamId = 42L, payload = payload)))

        val element = withTimeout(5_000) { received.await() }
        assertEquals(42L, element.streamId.get())
    }
}
