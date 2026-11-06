package app.tenshu.control

import org.tenshu.core.common.storage.SingleInstanceLock
import org.tenshu.core.common.transport.StreamFramer
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Makes sure one Control at most runs against a data root (`desktop-macos-distribution.md` §2.11).
 *
 * ⚠⚠ Every install channel (development, Developer ID, Homebrew, Mac App Store) resolves the same
 * App Group container, which is what lets data survive a channel switch. Before this guard,
 * nothing stopped two of them running at once. On 2026-11-04 a development Control and a TestFlight
 * Control both had `objects.db` and `ipc.sock` open. The store's own lock only *reports*
 * contention, and `UnixSocketServer.start` deletes a live socket file before binding, so the
 * second copy took over the socket and both wrote one store.
 *
 * Two checks, in order:
 *  1. [LOCK_FILE], an exclusive OS lock held for the life of the process. This is the guard.
 *  2. A connect to `ipc.sock`. This catches a running Control from before this guard, which never
 *     takes the lock. A stale socket file left by a killed Control refuses the connect, so it does
 *     not count as running.
 *
 * A refused copy asks the running one to show its window over `bridge.sock`, the same call the
 * tray's *Open Control* makes, and exits 0. A copy started hidden (login autostart) exits without
 * asking: two login items firing at once must not pop a window up.
 */
internal object ControlInstanceGuard {
    /** At the data root, beside the sockets: one per App Group container. */
    const val LOCK_FILE = "control.instance.lock"

    private const val HANDOFF_TIMEOUT_MS = 3_000L

    sealed interface Decision {
        /** Go ahead. [lock] is null only when locking was unavailable (we run unguarded, loudly). */
        data class Run(val lock: SingleInstanceLock?) : Decision

        /** Another Control is running. [holder] names it when known. */
        data class Defer(val holder: String) : Decision
    }

    fun decide(lockFile: File, binarySocket: String, log: (String) -> Unit): Decision =
        when (val outcome = SingleInstanceLock.tryAcquire(lockFile)) {
            is SingleInstanceLock.Outcome.HeldElsewhere ->
                Decision.Defer(outcome.holder ?: "another Control (lock held, holder unknown)")
            is SingleInstanceLock.Outcome.Acquired ->
                if (isServing(binarySocket)) {
                    // We hold the lock and someone still answers on the socket: a Control from
                    // before this guard. Let go so the next start is not blocked by us.
                    outcome.lock.release()
                    Decision.Defer("a Control without the instance lock, serving $binarySocket")
                } else {
                    Decision.Run(outcome.lock)
                }
            is SingleInstanceLock.Outcome.Unavailable -> {
                log(
                    "WARN ControlInstanceGuard: could not lock ${lockFile.path} " +
                        "(${outcome.cause::class.simpleName}: ${outcome.cause.message}). " +
                        "Starting unguarded: a second Control would not be refused.",
                )
                if (isServing(binarySocket)) {
                    Decision.Defer("a Control serving $binarySocket")
                } else {
                    Decision.Run(null)
                }
            }
        }

    /** True when something accepts a connection on the Unix socket at [path]. */
    fun isServing(path: String): Boolean {
        if (!File(path).exists()) return false
        return runCatching {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
                ch.connect(UnixDomainSocketAddress.of(path))
            }
        }.getOrDefault(false)
    }

    /**
     * Asks the Control serving [bridgeSocket] to show its window. True on a success reply.
     * Bounded: a wedged peer must not keep this copy from exiting.
     */
    fun requestOpenWindow(bridgeSocket: String, timeoutMs: Long = HANDOFF_TIMEOUT_MS): Boolean {
        val channel = runCatching {
            SocketChannel.open(StandardProtocolFamily.UNIX).apply {
                connect(UnixDomainSocketAddress.of(bridgeSocket))
            }
        }.getOrNull() ?: return false
        return channel.use { ch ->
            runCatching {
                CompletableFuture.supplyAsync { exchange(ch) }.get(timeoutMs, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
        }
    }

    private fun exchange(ch: SocketChannel): Boolean {
        val request = """{"v":1,"id":"instance-handoff","method":"openControlWindow","params":{}}"""
        ch.write(ByteBuffer.wrap(StreamFramer.frame(request.encodeToByteArray())))
        // `[4-byte length][1-byte type][payload]`. A peer-gate reject closes the channel instead.
        val header = ByteBuffer.allocate(5)
        if (!readFully(ch, header)) return false
        header.flip()
        val length = header.getInt()
        if (!StreamFramer.isValidFrameLength(length)) return false
        val payload = ByteBuffer.allocate(length)
        if (!readFully(ch, payload)) return false
        return "\"result\"" in String(payload.array())
    }

    private fun readFully(ch: SocketChannel, buffer: ByteBuffer): Boolean {
        while (buffer.hasRemaining()) {
            if (ch.read(buffer) == -1) return false
        }
        return true
    }
}
