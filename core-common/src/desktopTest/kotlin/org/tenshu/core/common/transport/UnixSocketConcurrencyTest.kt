package org.tenshu.core.common.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TConstant
import org.tenshu.core.TValue
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.reference.EntityListReference
import org.tenshu.core.reference.EntityReference
import org.tenshu.core.reference.ServiceTypeReference
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals

class UnixSocketConcurrencyTest {

    /**
     * This test explicitly verifies that the [UnixSocketTaskTransport.call] method is thread-safe.
     * Without the synchronization lock in the 'call' method, concurrent calls would interleave 
     * their data on the socket, leading to threads receiving mismatched responses (or corrupted data).
     */
    @Test
    fun testHighConcurrencyCalls() {
        runBlocking {
            val tempSocket = Files.createTempFile("tenshu-stress-test", ".sock")
            Files.delete(tempSocket)
            val socketPath = tempSocket.toAbsolutePath().toString()

            val transport = UnixSocketTaskTransport(
                parser = BinaryParser(
                    schemaProvider = object : SchemaProvider {
                        override fun getSchema(type: String) = null
                        override fun getTrait(type: String, trait: String) = null
                        override fun getTrait(
                            type: String,
                            traitSchema: org.tenshu.core.trait.TraitSchema
                        ) = null
                        override fun getServiceDataRelation(
                            service: ServiceTypeReference,
                            ref: org.tenshu.core.reference.Reference
                        ) = null

                        override fun getServiceRelation(from: List<String>) = null
                        override fun getTypeId(type: ByteArray) = ""
                        override fun getTypeId(kClass: kotlin.reflect.KClass<Data>) = ""
                        override fun getReference(type: String) = error("")
                    },
                    dataFactory = object : DataFactory {
                        override fun <T : TConstant> createConstant(type: String) = error("")
                        override fun <T : TValue> createValue(type: String, value: Any) = error("")
                        override fun <T : Composite> createComposite(
                            type: String,
                            fields: List<Data?>
                        ) = error("")

                        override fun <T : Data> createList(values: List<Data?>) = error("")
                        override fun <T : Composite> createEntity(
                            type: String,
                            fields: Map<String, Data>,
                            relations: Map<String, Data>
                        ) = error("")
                    }
                ),
                socketPath = socketPath
            )

            // Start a server that handles requests concurrently and returns responses with variable delays
            thread(isDaemon = true, name = "StressTestServer") {
                try {
                    val address = UnixDomainSocketAddress.of(socketPath)
                    ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { serverChannel ->
                        serverChannel.bind(address)
                        while (true) {
                            val clientChannel = serverChannel.accept()
                            thread {
                                clientChannel.use { ch ->
                                    val headerBuffer = ByteBuffer.allocate(4)
                                    val typeBuffer = ByteBuffer.allocate(1)
                                    while (ch.isOpen) {
                                        if (readFully(ch, headerBuffer) == -1) break
                                        headerBuffer.flip()
                                        val length = headerBuffer.getInt()

                                        if (readFully(ch, typeBuffer) == -1) break
                                        val type = typeBuffer.get(0)

                                        val payloadBuffer = ByteBuffer.allocate(length)
                                        if (readFully(ch, payloadBuffer) == -1) break
                                        // The client opens every connection with the wire hello.
                                        if (type == StreamFramer.TYPE_HELLO) {
                                            ch.write(
                                                ByteBuffer.wrap(
                                                    StreamFramer.frame(StreamFramer.HELLO_BODY, StreamFramer.TYPE_HELLO)
                                                )
                                            )
                                            headerBuffer.clear()
                                            typeBuffer.clear()
                                            continue
                                        }
                                        val (callId, request) =
                                            StreamFramer.unpackTagged(payloadBuffer.array().sliceArray(0 until length))

                                        // Random delay to ensure responses finish out-of-order from the server's perspective
                                        val delayTime = (10..100).random().toLong()
                                        Thread.sleep(delayTime)

                                        val response =
                                            "Echo: ${request.decodeToString()}".encodeToByteArray()
                                        val framedResponse = StreamFramer.frame(
                                            StreamFramer.packTagged(callId, response),
                                            StreamFramer.TYPE_CORRELATED_RESPONSE,
                                        )
                                        ch.write(ByteBuffer.wrap(framedResponse))
                                        headerBuffer.clear()
                                        typeBuffer.clear()
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Server closed
                }
            }

            try {
                delay(500) // Wait for server setup
                transport.bind()

                val callCount = 50
                val deferreds = (1..callCount).map { i ->
                    async(Dispatchers.Default) {
                        val requestText = "Request_$i"
                        val response = transport.call(requestText.encodeToByteArray())
                        val responseText = response.decodeToString()

                        // Verify that the response exactly matches the request, even with 50 concurrent calls
                        assertContentEquals(
                            "Echo: $requestText".encodeToByteArray(),
                            response,
                            "Thread $i received wrong response: $responseText"
                        )
                    }
                }

                withTimeout(15000L) {
                    deferreds.awaitAll()
                }

            } finally {
                transport.unbind()
                Files.deleteIfExists(java.nio.file.Path.of(socketPath))
            }
        }
    }

    private fun readFully(ch: SocketChannel, buffer: ByteBuffer): Int {
        while (buffer.hasRemaining()) {
            val read = ch.read(buffer)
            if (read == -1) return -1
            if (read == 0) Thread.sleep(1)
        }
        return buffer.position()
    }
}
