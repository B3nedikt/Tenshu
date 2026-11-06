package app.tenshu.control.epc

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.tenshu.core.Data
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.toTString
import kotlin.test.assertContentEquals

/**
 * The proxy-side EPC server-push connection: `send(Data)` serialises with the same `BinarySerializer`
 * the other transports use and routes the bytes (in emission order) to the session's push fn, which a
 * paired app parses with the same `BinaryParser`.
 */
class EpcClientConnectionTest {

    private val context = createTestContext(createSyntheticTestRegistry())
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(context.schemaProvider(), context.dataFactory())

    @Test
    fun serializes_and_routes_pushes_in_emission_order() = runBlocking {
        val clientId = "epc:abc"
        val router = EpcCallbackRouter()
        val received = Channel<ByteArray>(Channel.UNLIMITED)
        router.register(clientId) { received.trySend(it); true }
        val connection = EpcClientConnection(clientId, router, serializer)

        val first = "first".toTString(context)
        val second = "second".toTString(context)
        connection.send(first)
        connection.send(second)

        val r1 = withTimeout(5_000) { received.receive() }
        val r2 = withTimeout(5_000) { received.receive() }

        // Each push is exactly the serialized Data, delivered in emission order…
        assertContentEquals(serializer.serialize(first), r1)
        assertContentEquals(serializer.serialize(second), r2)
        // …and round-trips back through the same BinaryParser the client uses.
        assertContentEquals(r1, serializer.serialize(parser.parse<Data>(r1)))

        connection.destroy()
    }

    @Test
    fun drops_pushes_when_no_route_is_registered() = runBlocking {
        // Unregistered clientId → the consumer finds no route and drops the push (no crash).
        val connection = EpcClientConnection("epc:none", EpcCallbackRouter(), serializer)
        connection.send("ignored".toTString(context))
        connection.destroy()
    }
}
