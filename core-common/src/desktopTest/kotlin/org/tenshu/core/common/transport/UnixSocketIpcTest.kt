package org.tenshu.core.common.transport

import kotlinx.coroutines.CompletableDeferred
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
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals

class UnixSocketIpcTest {

    @Test
    fun testBidirectionalCommunication() {
        runBlocking {
            withTimeout(10000L) {
                val tempSocket = Files.createTempFile("tenshu-test", ".sock")
                Files.delete(tempSocket) // Server will create it
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
                            override fun getTypeId(kClass: kotlin.reflect.KClass<Data>) =
                                ""

                            override fun getReference(type: String) = error("")
                        },
                        dataFactory = object : DataFactory {
                            override fun <T : TConstant> createConstant(type: String) =
                                error("")

                            override fun <T : TValue> createValue(
                                type: String,
                                value: Any
                            ) = error("")

                            override fun <T : Composite> createComposite(
                                type: String,
                                fields: List<Data?>
                            ) = error("")

                            override fun <T : Data> createList(values: List<Data?>) =
                                error("")

                            override fun <T : Composite> createEntity(
                                type: String,
                                fields: Map<String, Data>,
                                relations: Map<String, Data>
                            ) = error("")
                        }
                    ),
                    socketPath = socketPath
                )

                thread(isDaemon = true, name = "TestServer") {
                    try {
                        val address = UnixDomainSocketAddress.of(socketPath)
                        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { serverChannel ->
                            serverChannel.bind(address)
                            serverChannel.accept()
                                .use { clientChannel -> // Ensure client channel is closed

                                    fun readFully(buffer: ByteBuffer) {
                                        while (buffer.hasRemaining()) {
                                            val read = clientChannel.read(buffer)
                                            if (read == -1) throw java.io.IOException("Socket closed")
                                            if (read == 0) Thread.sleep(10)
                                        }
                                    }

                                    val headerBuffer = ByteBuffer.allocate(4)
                                    val typeBuffer = ByteBuffer.allocate(1)
                                    while (clientChannel.isOpen) {
                                        try {
                                            headerBuffer.clear()
                                            readFully(headerBuffer)
                                            headerBuffer.flip()
                                            val length = headerBuffer.getInt()

                                            typeBuffer.clear()
                                            readFully(typeBuffer)
                                            val type = typeBuffer.get(0)

                                            val payloadBuffer = ByteBuffer.allocate(length)
                                            readFully(payloadBuffer)
                                            // The client opens every connection with the wire hello.
                                            if (type == StreamFramer.TYPE_HELLO) {
                                                clientChannel.write(
                                                    ByteBuffer.wrap(
                                                        StreamFramer.frame(StreamFramer.HELLO_BODY, StreamFramer.TYPE_HELLO)
                                                    )
                                                )
                                                continue
                                            }
                                            val (callId, request) =
                                                StreamFramer.unpackTagged(payloadBuffer.array().sliceArray(0 until length))

                                            // Send response
                                            val response =
                                                ("Response to: " + request.decodeToString()).encodeToByteArray()
                                            val framedResponse = StreamFramer.frame(
                                                StreamFramer.packTagged(callId, response),
                                                StreamFramer.TYPE_CORRELATED_RESPONSE
                                            )
                                            clientChannel.write(ByteBuffer.wrap(framedResponse))

                                            // Send an async push immediately after
                                            Thread.sleep(100)
                                            val push = "Push Notification".encodeToByteArray()
                                            val framedPush =
                                                StreamFramer.frame(push, StreamFramer.TYPE_CALLBACK)
                                            clientChannel.write(ByteBuffer.wrap(framedPush))
                                        } catch (e: Exception) {
                                            break
                                        }
                                    }
                                }
                        }
                    } catch (e: Exception) {
                        // e.printStackTrace()
                    }
                }

                try {
                    delay(500) // Wait for server to start
                    transport.bind()

                    val callbackReceived = CompletableDeferred<ByteArray>()
                    transport.onCallback = {
                        callbackReceived.complete(it)
                    }

                    // Test 1: Request-Response
                    val request = "Hello".encodeToByteArray()
                    val response = transport.call(request)
                    assertContentEquals("Response to: Hello".encodeToByteArray(), response)

                    // Test 2: Push Notification should have arrived
                    val receivedPush = callbackReceived.await()
                    assertContentEquals("Push Notification".encodeToByteArray(), receivedPush)
                } finally {
                    transport.unbind()
                    Files.deleteIfExists(java.nio.file.Path.of(socketPath))
                }
            }
        }
    }
}
