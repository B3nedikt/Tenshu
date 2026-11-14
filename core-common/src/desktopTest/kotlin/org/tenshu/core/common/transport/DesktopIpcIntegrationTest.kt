package org.tenshu.core.common.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.tenshu.core.StreamCallback
import org.tenshu.core.StreamElement
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.asStreamInteractor
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.common.DefaultInteractorProvider
import org.tenshu.core.common.di.coreCommonModule
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.common.impl.TStringDto
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.core.common.util.toInt
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.number.toTInteger
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DesktopIpcIntegrationTest {

    private val socketPath = Files.createTempFile("tenshu-ipc-test", ".sock")
        .also { Files.delete(it) }
        .toAbsolutePath()
        .toString()

    @BeforeTest
    fun setup() {
        val koinApp = startKoin {
            modules(coreCommonModule())
        }
        val koin = koinApp.koin

        val tContext = koin.get<TContext>()

        koin.get<SchemaRegistry>()

        assertNotNull(tContext.dataFactory(), "DataFactory missing in TContext")
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Files.deleteIfExists(Path.of(socketPath))
    }

    @Test
    fun testEndToEndCallbackFlow() = runBlocking {
        withTimeout(15000L) {
            val koin = GlobalContext.get()
            val tContext = koin.get<TContext>()
            val serializer = BinarySerializer()
            val parser = BinaryParser(tContext.schemaProvider(), tContext.dataFactory())

            val serverReady = CompletableDeferred<Unit>()

            // 1. Setup Mock Server
            thread(isDaemon = true, name = "TestServer") {
                try {
                    val address = UnixDomainSocketAddress.of(socketPath)
                    ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { serverChannel ->
                        serverChannel.bind(address)
                        serverReady.complete(Unit)

                        val clientChannel = serverChannel.accept()

                        fun readFully(buffer: ByteBuffer): Boolean {
                            while (buffer.hasRemaining()) {
                                val read = clientChannel.read(buffer)
                                if (read == -1) return false
                                if (read == 0) Thread.sleep(10)
                            }
                            return true
                        }

                        val headerBuffer = ByteBuffer.allocate(4)
                        val typeBuffer = ByteBuffer.allocate(1)
                        while (clientChannel.isOpen) {
                            headerBuffer.clear()
                            if (!readFully(headerBuffer)) break
                            headerBuffer.flip()
                            val length = headerBuffer.array().toInt()

                            typeBuffer.clear()
                            if (!readFully(typeBuffer)) break
                            val type = typeBuffer.get(0)

                            val payloadBuffer = ByteBuffer.allocate(length)
                            if (length > 0) {
                                if (!readFully(payloadBuffer)) break
                            }
                            // The client opens every connection with the wire hello.
                            if (type == StreamFramer.TYPE_HELLO) {
                                clientChannel.write(
                                    ByteBuffer.wrap(StreamFramer.frame(StreamFramer.HELLO_BODY, StreamFramer.TYPE_HELLO))
                                )
                                continue
                            }
                            val (callId, _) = StreamFramer.unpackTagged(payloadBuffer.array())
                            // Send callback
                            val push = StreamElementValueDto(
                                streamId = 123L,
                                payload = TStringDto("Notification")
                            )
                            val pushBytes = serializer.serialize(push)
                            val framedPush =
                                StreamFramer.frame(pushBytes, StreamFramer.TYPE_CALLBACK)
                            clientChannel.write(ByteBuffer.wrap(framedPush))

                            // Send response
                            val response = TResult.Success.create(tContext, TStringDto("OK"))
                            val responseBytes = serializer.serialize(response)
                            val framedResponse = StreamFramer.frame(
                                StreamFramer.packTagged(callId, responseBytes),
                                StreamFramer.TYPE_CORRELATED_RESPONSE,
                            )
                            clientChannel.write(ByteBuffer.wrap(framedResponse))
                        }
                    }
                } catch (e: Exception) {
                    println("SERVER ERROR: ${e.message}")
                    e.printStackTrace()
                }
            }

            serverReady.await()

            // 2. Setup Client Transport
            val transport = UnixSocketTaskTransport(parser, socketPath)
            val executor = RemoteBinaryTaskExecutor(serializer, parser, transport, tContext, this)
            executor.bind()

            // Register the executor in Koin BEFORE resolving DefaultInteractorProvider so that
            // coreCommonModule's auto-wire branch sees a RemoteBinaryTaskExecutor and hooks
            // executor.streamElementHandler to provider.dispatchElement. This mirrors the
            // production wiring in PlatformClientModule and ensures asStreamInteractor() —
            // which routes through tContext.interactorProvider() (the Koin-registered
            // singleton) — and the inbound stream-element path share the same provider
            // instance, so registered interactors actually receive pushed elements.
            loadKoinModules(
                module {
                    single<TaskExecutor> { executor }
                    single<RemoteTaskExecutor> { executor }
                }
            )
            koin.get<DefaultInteractorProvider>()

            // 3. Register Interactor
            val callbackReceived = CompletableDeferred<String>()
            val ro = object : OperationRo(
                requestChain = LocalRequestChain(executor, tContext, streamId = 123L)
                    .addArray(
                        OperationDto(
                            schema = Stream.Schema,
                            params = mapOf(Stream.Params.STREAM_ID to 123L.toTInteger(tContext))
                        )
                    ),
                context = tContext
            ) {
                override val schema = Stream.Schema
            }
            val interactor = ro.asStreamInteractor<TString>()
            interactor.registerCallback(object : StreamCallback<TString> {
                override fun start(error: TError?) {}
                override fun element(streamElement: StreamElement<TString>) {
                    if (streamElement is StreamElement.Value) {
                        callbackReceived.complete(streamElement.payload.get())
                    }
                }

                override fun end(error: TError?) {}
            })

            // 4. Trigger call
            val task = TListDto(listOf(TStringDto("Trigger")))
            val result = executor.executeAsync(task)

            // 5. Verify Response
            @Suppress("UNCHECKED_CAST")
            val success = result as TResult.Success<TString>
            assertEquals("OK", success.value?.get())

            // 6. Verify Notification
            val notification = callbackReceived.await()
            assertEquals("Notification", notification)

            executor.unbind()
        }
    }
}
