package org.tenshu.core.common.transport

import org.tenshu.core.Data
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ClientConnection
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.locks.ReentrantLock

class UnixSocketClientConnection(
    private val channel: SocketChannel,
    private val serializer: BinarySerializer,
    // Shared with the per-connection response writer so a stream callback
    // frame can't interleave with a (possibly concurrent) response on the
    // same socket.
    private val writeLock: ReentrantLock = ReentrantLock()
) : ClientConnection {
    override fun init() {
        // No-op for now
    }

    override fun send(data: Data) {
        if (!channel.isOpen) return

        val bytes = serializer.serialize(data)
        // Tag as CALLBACK
        val framed = StreamFramer.frame(bytes, StreamFramer.TYPE_CALLBACK)
        writeLock.lock()
        try {
            // ⚠ Drained in a loop under the shared lock, like every other writer on this channel: a
            // callback frame must never be split by a reply.
            channel.writeFully(ByteBuffer.wrap(framed))
        } finally {
            writeLock.unlock()
        }
    }

    override fun destroy() {
        if (channel.isOpen) {
            channel.close()
        }
    }
}
