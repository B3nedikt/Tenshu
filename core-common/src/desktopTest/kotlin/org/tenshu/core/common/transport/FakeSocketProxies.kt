package org.tenshu.core.common.transport

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Hand-rolled socket peers for the desktop transport tests, speaking the wire of
 * `docs/protocol/desktop-ipc.md` byte for byte.
 */
internal object FakeSocketProxies {

    /** One frame as the fake read it off the socket. */
    class Frame(val type: Byte, val payload: ByteArray)

    class Handle(val thread: Thread) {
        /** Every frame the fake read, in order. */
        val received: MutableList<Frame> = CopyOnWriteArrayList()

        /** Counted down when the client's connection ends (EOF or error). */
        val disconnected = CountDownLatch(1)

        fun stop() = thread.interrupt()
    }

    /**
     * Serves one connection: reads frames and hands each to [onFrame], which may write replies.
     * The fake never answers anything on its own.
     */
    fun serve(
        socketPath: String,
        name: String,
        onFrame: (ch: SocketChannel, frame: Frame, handle: Handle) -> Unit,
    ): Handle {
        lateinit var handle: Handle
        val bound = CountDownLatch(1)
        val t = thread(start = false, isDaemon = true, name = name) {
            runCatching {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                    server.bind(UnixDomainSocketAddress.of(socketPath))
                    bound.countDown()
                    server.accept().use { ch ->
                        try {
                            val header = ByteBuffer.allocate(4)
                            val type = ByteBuffer.allocate(1)
                            while (ch.isOpen) {
                                header.clear(); ch.readFully(header); header.flip()
                                val length = header.getInt()
                                type.clear(); ch.readFully(type); type.flip()
                                val payload = ByteBuffer.allocate(length)
                                ch.readFully(payload)
                                val frame = Frame(type.get(), payload.array().copyOf(length))
                                handle.received += frame
                                onFrame(ch, frame, handle)
                            }
                        } finally {
                            handle.disconnected.countDown()
                        }
                    }
                }
            }
            bound.countDown()
        }
        handle = Handle(t)
        t.start()
        bound.await()
        return handle
    }

    /**
     * A current Control's wire: answers the hello at [helloRevision] and echoes each correlated
     * request's body under its call id. A body of `"fail"` gets a
     * [StreamFramer.TYPE_CORRELATED_FAILURE]; a body starting `"hold"` is answered only when
     * [release] is counted down, on a separate thread.
     */
    fun correlatedEcho(
        socketPath: String,
        release: CountDownLatch = CountDownLatch(0),
        helloRevision: Int = StreamFramer.WIRE_REVISION,
    ): Handle =
        serve(socketPath, "CorrelatedEchoProxy") { ch, frame, _ ->
            when (frame.type) {
                StreamFramer.TYPE_HELLO -> ch.writeFrame(
                    StreamFramer.helloBody(helloRevision),
                    StreamFramer.TYPE_HELLO,
                )
                StreamFramer.TYPE_CORRELATED_REQUEST -> {
                    val (id, body) = StreamFramer.unpackTagged(frame.payload)
                    val text = body.decodeToString()
                    when {
                        text == "fail" -> ch.writeFrame(
                            StreamFramer.packTagged(id, "boom".encodeToByteArray()),
                            StreamFramer.TYPE_CORRELATED_FAILURE,
                        )
                        text.startsWith("hold") -> thread(isDaemon = true) {
                            release.await()
                            ch.writeFrame(StreamFramer.packTagged(id, body), StreamFramer.TYPE_CORRELATED_RESPONSE)
                        }
                        else -> ch.writeFrame(
                            StreamFramer.packTagged(id, body),
                            StreamFramer.TYPE_CORRELATED_RESPONSE,
                        )
                    }
                }
                // TYPE_CANCEL_READ and the rest: a current proxy sends no reply.
                else -> Unit
            }
        }

    private fun SocketChannel.readFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (read(buffer) == -1) throw java.io.IOException("closed")
        }
    }

    fun SocketChannel.writeFrame(payload: ByteArray, type: Byte) {
        synchronized(this) { write(ByteBuffer.wrap(StreamFramer.frame(payload, type))) }
    }
}
