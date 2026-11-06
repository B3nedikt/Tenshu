package org.tenshu.core.common.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tenshu.core.common.transport.StreamFramer
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeBridgeServerTest {

    private val json = Json
    private val tempDir = Files.createTempDirectory("tenshu-bridge-test")
    private var socketServer: NativeBridgeServer? = null

    @AfterTest
    fun cleanup() {
        socketServer?.stop()
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun parse(bytes: ByteArray): JsonObject =
        json.parseToJsonElement(bytes.decodeToString()).jsonObject

    private fun request(id: String, method: String, params: String = "{}"): ByteArray =
        """{"v":1,"id":"$id","method":"$method","params":$params}""".encodeToByteArray()

    // ---- dispatch() unit tests (no socket) -------------------------------

    @Test
    fun pingEchoesMessageAndProtocolVersion() {
        val reply = parse(NativeBridgeServer().dispatch(request("1", "ping", """{"message":"hi"}""")))

        assertEquals("1", reply["id"]!!.jsonPrimitive.content)
        assertEquals(NativeBridgeServer.PROTOCOL_VERSION, reply["v"]!!.jsonPrimitive.content.toInt())
        val result = reply["result"]!!.jsonObject
        assertTrue(result["pong"]!!.jsonPrimitive.boolean)
        assertEquals("hi", result["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun registeredHandlerResultIsMappedToJson() {
        val server = NativeBridgeServer()
        server.register("getSyncState") {
            mapOf(
                "state" to "ACTIVE",
                "enabled" to true,
                "lastSyncTimestamp" to 42L,
                "ownerId" to null,
            )
        }

        val result = parse(server.dispatch(request("7", "getSyncState"))).get("result")!!.jsonObject
        assertEquals("ACTIVE", result["state"]!!.jsonPrimitive.content)
        assertTrue(result["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(42L, result["lastSyncTimestamp"]!!.jsonPrimitive.content.toLong())
        assertTrue(result["ownerId"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun unknownMethodYieldsErrorReply() {
        val reply = parse(NativeBridgeServer().dispatch(request("2", "nope")))
        assertNull(reply["result"])
        assertEquals("unknown_method", reply["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun handlerExceptionBecomesStructuredError() {
        val server = NativeBridgeServer()
        server.register("boom") { throw BridgeException("teapot", "I'm a teapot") }

        val error = parse(server.dispatch(request("3", "boom")))["error"]!!.jsonObject
        assertEquals("teapot", error["code"]!!.jsonPrimitive.content)
        assertEquals("I'm a teapot", error["message"]!!.jsonPrimitive.content)
    }

    /**
     * ⚠⚠ **An unexpected throw must not hand the peer its message.** `dispatch`'s catch-all used
     * to reply with `e.message`, and on this bridge that routinely carries an absolute realm path
     * — the File Provider methods work in `…/files/…`, so a `java.nio` failure renders as
     * `/Users/…/files/private/secret.txt (No such file or directory)`.
     *
     * ⚠ The distinction this pins is *who supplied the string*, not "does it look like a path":
     * a [BridgeException] echoing the caller's own `params["path"]` discloses nothing (the test
     * above), while an arbitrary throwable's message is internal detail the peer had no other way
     * to learn. So the assertion is that the secret is absent AND that the reply is still a
     * well-formed `internal` error — a bridge that answered nothing would "pass" a
     * secret-is-absent check while breaking every caller.
     */
    @Test
    fun anUnexpectedThrowDoesNotLeakItsMessageToThePeer() {
        val server = NativeBridgeServer()
        val secret = "/Users/someone/Library/Containers/app.tenshu.control/files/private/secret.txt"
        server.register("explode") { throw IllegalStateException("$secret (No such file or directory)") }

        val reply = parse(server.dispatch(request("4", "explode")))
        val error = reply["error"]!!.jsonObject
        val message = error["message"]!!.jsonPrimitive.content

        assertEquals("internal", error["code"]!!.jsonPrimitive.content)
        assertEquals("internal error", message)
        assertFalse(secret in message, "the throwable's message must not cross the socket")
        assertFalse("secret.txt" in reply.toString(), "nor any fragment of it, anywhere in the reply")
    }

    @Test
    fun malformedJsonIsHandledGracefully() {
        val reply = parse(NativeBridgeServer().dispatch("not json".encodeToByteArray()))
        assertTrue(reply.containsKey("error"))
    }

    // ---- real Unix-domain socket round-trip ------------------------------

    @Test
    fun socketRoundTripPing() {
        val socketPath = tempDir.resolve("bridge.sock").toString()
        socketServer = NativeBridgeServer(socketPath).also { it.start() }

        // start() binds before returning, but keep the retry as scheduling slack.
        connectWithRetry(socketPath).use { ch ->
            ch.write(ByteBuffer.wrap(StreamFramer.frame(request("9", "ping"))))
            val reply = parse(readFrame(ch))
            assertEquals("9", reply["id"]!!.jsonPrimitive.content)
            assertTrue(reply["result"]!!.jsonObject["pong"]!!.jsonPrimitive.boolean)
        }
    }

    /**
     * ⚠⚠ **A full house of blocking handlers must not starve the work they are waiting on.**
     * Handlers are blocking (`runBlocking` around suspend reads), and the work under them hops to
     * `Dispatchers.IO` (the store, the IPC transport). Handlers used to run ON `Dispatchers.IO`, so
     * 64 of them in flight — Finder asking for a folder's worth of thumbnails — held every IO
     * thread while each waited for an IO thread of its own: a permanent deadlock that froze every
     * bridge method, seen live as 64 workers parked at the `thumbnail` handler.
     *
     * The latch makes all [HANDLERS] park at once before any of them asks for IO, which is the
     * worst case and exactly the one that wedged. They must all still answer.
     */
    @Test
    fun aFullHouseOfBlockingHandlersStillReachesDispatchersIo() {
        val socketPath = tempDir.resolve("bridge.sock").toString()
        val server = NativeBridgeServer(socketPath).also { socketServer = it; it.start() }
        val allParked = CountDownLatch(HANDLERS)
        server.register("blockOnIo") {
            allParked.countDown()
            allParked.await(10, TimeUnit.SECONDS)
            mapOf("ok" to runBlocking { withContext(Dispatchers.IO) { true } })
        }

        connectWithRetry(socketPath).use { ch ->
            repeat(HANDLERS) { ch.write(ByteBuffer.wrap(StreamFramer.frame(request("$it", "blockOnIo")))) }
            val reader = Executors.newSingleThreadExecutor()
            try {
                val ids = reader.submit<Set<String>> {
                    (1..HANDLERS).map { parse(readFrame(ch))["id"]!!.jsonPrimitive.content }.toSet()
                }.get(30, TimeUnit.SECONDS)
                assertEquals((0 until HANDLERS).map { "$it" }.toSet(), ids)
            } catch (e: TimeoutException) {
                throw AssertionError("bridge deadlocked: blocking handlers starved Dispatchers.IO", e)
            } finally {
                reader.shutdownNow()
            }
        }
    }

    private fun connectWithRetry(socketPath: String): SocketChannel {
        val address = UnixDomainSocketAddress.of(socketPath)
        val deadline = System.currentTimeMillis() + 2000
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                ch.connect(address)
                return ch
            } catch (e: Exception) {
                last = e
                ch.close()
                Thread.sleep(20)
            }
        }
        throw AssertionError("could not connect to bridge socket", last)
    }

    private fun readFrame(ch: SocketChannel): ByteArray {
        val header = ByteBuffer.allocate(4); readFully(ch, header); header.flip()
        val length = header.getInt()
        readFully(ch, ByteBuffer.allocate(1)) // type byte
        val payload = ByteBuffer.allocate(length); readFully(ch, payload)
        return payload.array()
    }

    private fun readFully(ch: SocketChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) if (ch.read(buf) == -1) error("eof")
    }

    private companion object {
        /** `Dispatchers.IO`'s default cap — the number of handlers that wedged the bridge. */
        const val HANDLERS = 64
    }
}
