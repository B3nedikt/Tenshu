package org.tenshu.core.common.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ProxyGateway
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/** How long [UnixSocketServer.start] waits for its socket to bind before giving up. */
private const val BIND_TIMEOUT_SECONDS = 10L

/**
 * The usable length of `sockaddr_un.sun_path`, in bytes.
 *
 * ⚠⚠ **Measured, not read off a header.** macOS declares `sun_path[104]`, which suggests 103 usable
 * once the NUL is accounted for — but a bind at 103 fails and 102 succeeds, so the usable maximum
 * is 102. The one-byte discrepancy is exactly the sort of thing an arithmetic argument gets wrong,
 * which is why these are the measured numbers.
 */
private const val MAC_SUN_PATH_MAX = 102

/** ⚠ Linux declares `sun_path[108]`; not measured here, so the conservative NUL-adjusted value. */
private const val LINUX_SUN_PATH_MAX = 106

class UnixSocketServer(
    private val proxyGateway: ProxyGateway,
    private val serializer: BinarySerializer,
    private val socketPath: String = SocketPathResolver.resolve(),
    // Desktop IPC peer-credential gate (threat-model A14 / T-C-11). Default =
    // wired-but-non-enforcing; a real verifier + strict policy turns it on. Shared
    // posture with the JSON NativeBridgeServer.
    private val peerVerifier: PeerCredentialVerifier = UnverifiedPeerCredentialVerifier,
    private val peerPolicy: PeerCredentialPolicy = PeerCredentialPolicy(),
    // A16 developer mode: downgrades a strict rejection to admit-with-warning so an
    // unsigned local build can still connect. Inert while the policy is non-strict.
    // ⚠ Must be sourced from a build-type signal, never a runtime setting — see the
    // call site in control-desktop's Main.
    private val developerMode: Boolean = false,
    /**
     * Ceiling on a single inbound frame's payload — [StreamFramer.MAX_FRAME_BYTES] in
     * production. Overridable **so the guard is testable**: proving that an over-cap header is
     * refused otherwise means actually sending one, and a 256 MiB allocation in a test JVM
     * proves nothing except that the heap ran out (which is how the *unguarded* server also
     * ends up closing the connection — it dies on an `OutOfMemoryError` the read loop's
     * `catch (e: Exception)` does not catch). A small injected cap makes the difference
     * between guarded and unguarded observable without depending on heap size at all.
     */
    private val maxFrameBytes: Int = StreamFramer.MAX_FRAME_BYTES,
) {
    private val remoteConnections = ConcurrentHashMap<String, UnixSocketClientConnection>()
    /** Client ids whose connection satisfied the peer-credential gate. See [isAttested]. */
    private val attestedClientIds = ConcurrentHashMap.newKeySet<String>()

    fun getRemoteConnection(clientId: String): UnixSocketClientConnection? {
        return remoteConnections[clientId]
    }

    /**
     * The client ids currently holding a connection. Exists so the session-identity contract
     * is assertable: that two peers occupy two slots (`desktop-ipc-client-identity.md` P2) is
     * invisible from the outside otherwise.
     */
    internal fun registeredClientIds(): Set<String> = remoteConnections.keys.toSet()

    /**
     * Whether [clientId]'s connection presented a code signature that satisfied the gate —
     * the desktop backing for `AppRegistration.verified`
     * (`org.tenshu.core.common.app.proxy.PeerAttestationProbe`).
     *
     * Tracked explicitly rather than inferred from the id's shape: "does it start with
     * `unattested-`" would be a second, silently-drifting encoding of the same fact.
     */
    fun isAttested(clientId: String): Boolean = attestedClientIds.contains(clientId)

    @Volatile
    private var boundFile: BoundSocketFile? = null

    /**
     * Removes the socket file on a clean quit, if it is still the one this server bound. A
     * leftover file answers "connection refused", the answer a daemon that is still starting
     * gives; gone, the failure reads as what it is. ⚠ Does not stop the server: the process is
     * exiting, and connected clients are not affected by the name going away.
     */
    fun removeSocketFile() {
        boundFile?.removeIfStillOurs()
    }

    /**
     * Binds the socket and begins accepting. **Returns only once the socket is bound**, so a caller
     * may connect (or advertise the path) the moment this returns.
     *
     * ⚠⚠ That guarantee is the whole point. Returning before the accept thread has bound leaves a
     * gap in which a client connecting gets no listener — and
     * `UnixSocketTaskTransport.openConnection` **swallows** a failed connect, leaving a null channel
     * and *no* reconnect scheduled (a reconnect is only ever armed when an established connection
     * dies). The request then never crosses the socket and the caller waits out its whole budget.
     * `PhaseL2CancellationTest.socket_cancel_frame_stops_the_server_side_coroutine` fails that way
     * when the gap is open: the signal never arrives, however generous the budget.
     *
     * ⚠ Reachable outside tests: any host that starts the server and then immediately tells a
     * client to connect races the same window. It is simply far likelier to be lost on a loaded box.
     */
    fun start() {
        val path = Path.of(socketPath)
        // Owner-only, created or repaired (`desktop-ipc.md` §3): under a stock umask `~/.tenshu`
        // is `0755` — every local user could reach the socket, and (worse) place one here before
        // the daemon started.
        SocketDirectory.prepare(path.parent)
        // ⚠ Before the unlink: see BoundSocketFile.claim.
        val claim = BoundSocketFile.claim(path)
        Files.deleteIfExists(path)

        val bound = CountDownLatch(1)
        val bindFailure = AtomicReference<Exception?>(null)

        thread(isDaemon = true, name = "UnixSocketServer") {
            val address = UnixDomainSocketAddress.of(socketPath)
            try {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { serverChannel ->
                    try {
                        serverChannel.bind(address)
                        boundFile = claim.bound()
                    } catch (e: Exception) {
                        // Report the bind on the STARTING thread: a caller told "started" by a
                        // method that only managed a stack trace on a daemon thread has no way to
                        // know its socket does not exist.
                        bindFailure.set(e)
                        throw e
                    } finally {
                        bound.countDown()
                    }
                    while (true) {
                        val clientChannel = serverChannel.accept()
                        handleClient(clientChannel)
                    }
                }
            } catch (e: Exception) {
                bound.countDown() // idempotent; covers a throw from open() before the inner try
                e.printStackTrace()
            }
        }

        if (!bound.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            error("UnixSocketServer did not bind $socketPath within ${BIND_TIMEOUT_SECONDS}s")
        }
        bindFailure.get()?.let { throw IllegalStateException(bindFailureMessage(socketPath), it) }
    }

    /**
     * Why the bind failed, naming the **one cause the OS will not tell you about**.
     *
     * ⚠⚠ A Unix domain socket's path lives in `sockaddr_un.sun_path`, a fixed byte array — 104 on
     * macOS, 108 on Linux — and a path that does not fit is refused with a generic error that says
     * nothing about length. Measured on macOS: **102 bytes binds, 103 does not.** A bare
     * "could not bind <path>" sends a reader looking at permissions, stale sockets and SIP, none
     * of which is the problem.
     *
     * ⚠ It is not only a test concern. The path is `<home>/.tenshu/ipc.sock`-shaped, so a deep
     * enough home directory — a long username, a synced or containerised home — reaches the limit
     * on a real machine, and the failure there is a daemon that will not start.
     *
     * ⚠ Bytes, not characters: a non-ASCII home directory spends more of the budget than it looks.
     */
    private fun bindFailureMessage(socketPath: String): String {
        val bytes = socketPath.encodeToByteArray().size
        val macOs = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
        val limit = if (macOs) MAC_SUN_PATH_MAX else LINUX_SUN_PATH_MAX
        val suffix = if (bytes > limit) {
            " — the path is $bytes bytes and a Unix socket path may be at most $limit on this OS " +
                "(sockaddr_un.sun_path); shorten the directory it lives in"
        } else {
            ""
        }
        return "UnixSocketServer could not bind $socketPath$suffix"
    }

    private fun handleClient(clientChannel: SocketChannel) {
        thread(isDaemon = true, name = "UnixSocketClientHandler") {
            // Peer-credential gate (A14 / T-C-11): admit-or-close before serving.
            // Non-enforcing by default; closes the channel on a strict reject.
            val credential = peerVerifier.admit(
                clientChannel, peerPolicy, developerMode,
            ) ?: return@thread

            // The peer's identity is whatever its code signature attested to
            // (desktop-ipc-client-identity.md P2). One id for *every* peer would make every
            // desktop app register as Drive and inherit Drive's grants, and — because
            // `remoteConnections` is keyed by this id — the second app to connect would
            // replace the first, so proxy→client pushes would reach the wrong socket.
            //
            // ⚠ An unattested peer gets a **unique** synthetic id, never a shared constant.
            // A constant would recreate that same collision among unattested peers. It
            // resolves to no app through `AppIdentityResolver`, so
            // registration stays a no-op (D3: fail closed into *anonymous*, never into a
            // fallback identity).
            val clientId = credential.appId ?: "unattested-${UUID.randomUUID()}"
            // The read loop must NOT block on proxyGateway.call —
            // otherwise a long-running cancellable call would stall the loop
            // and the very TYPE_CANCEL frame meant to stop it could never be
            // read. Each frame is dispatched on this scope; the loop only
            // reads + routes. Writes are serialised through `writeLock` (shared
            // with UnixSocketClientConnection so stream callbacks can't
            // interleave with concurrent responses).
            val writeLock = ReentrantLock()
            val connection = UnixSocketClientConnection(clientChannel, serializer, writeLock)
            remoteConnections[clientId] = connection
            if (credential.verified) attestedClientIds.add(clientId)
            val connScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            fun writeFramed(payload: ByteArray, type: Byte) {
                writeLock.lock()
                try {
                    // ⚠ Drained in a loop under the lock: a frame must never be split by another writer's
                    // A blocking channel writes everything in one call by contract; the loop
                    // keeps the frame whole if a channel is ever made non-blocking.
                    clientChannel.writeFully(ByteBuffer.wrap(StreamFramer.frame(payload, type)))
                } finally {
                    writeLock.unlock()
                }
            }

            clientChannel.use { ch ->
                val headerBuffer = ByteBuffer.allocate(4)
                val typeBuffer = ByteBuffer.allocate(1)
                while (ch.isOpen) {
                    try {
                        headerBuffer.clear()
                        if (readFully(ch, headerBuffer) == -1) break
                        headerBuffer.flip()
                        val length = headerBuffer.getInt()
                        if (!StreamFramer.isValidFrameLength(length, maxFrameBytes)) {
                            // Four bytes off the wire never size the allocation directly: a
                            // hostile or desynchronised header could ask for 2 GiB. Drop the
                            // connection instead: the stream cannot be resynchronised from here.
                            System.err.println(
                                "[ipc] $clientId sent an out-of-range frame length ($length B, cap " +
                                    "$maxFrameBytes) — closing the connection"
                            )
                            break
                        }

                        typeBuffer.clear()
                        if (readFully(ch, typeBuffer) == -1) break
                        typeBuffer.flip()
                        val type = typeBuffer.get()

                        val payloadBuffer = ByteBuffer.allocate(length)
                        if (readFully(ch, payloadBuffer) == -1) break
                        val payload = payloadBuffer.array().sliceArray(0 until length)

                        when (type) {
                            StreamFramer.TYPE_CANCEL -> {
                                val taskId = payload.decodeToString()
                                connScope.launch { proxyGateway.cancel(clientId, taskId) }
                            }

                            // desktop-ipc.md §2.3: keyed by THIS socket, so a cancel can only
                            // ever reach a read this connection sent.
                            StreamFramer.TYPE_CANCEL_READ -> {
                                val callId = payload.decodeToString()
                                connScope.launch { proxyGateway.cancelRead(clientId, ch, callId) }
                            }

                            StreamFramer.TYPE_CANCELLABLE_REQUEST -> {
                                val (taskId, body) = StreamFramer.unpackTagged(payload)
                                connScope.launch {
                                    try {
                                        val response = proxyGateway.call(
                                            clientId = clientId,
                                            request = body,
                                            sessionIdentity = ch,
                                            taskId = taskId
                                        )
                                        writeFramed(
                                            StreamFramer.packTagged(taskId, response),
                                            StreamFramer.TYPE_CANCELLABLE_RESPONSE
                                        )
                                    } catch (e: CancellationException) {
                                        // Cancelled via TaskHandle — the client
                                        // already abandoned its waiter; nothing
                                        // to send back.
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }

                            // desktop-ipc.md §2.2: answered inline, from the read loop, so a busy
                            // proxy still answers at once — the client drops the connection if
                            // nothing comes back.
                            StreamFramer.TYPE_HELLO ->
                                writeFramed(StreamFramer.HELLO_BODY, StreamFramer.TYPE_HELLO)

                            StreamFramer.TYPE_CORRELATED_REQUEST -> {
                                val (callId, body) = StreamFramer.unpackTagged(payload)
                                connScope.launch {
                                    // ⚠ A plain call: the call id tags the reply and nothing else.
                                    // Passing it as `taskId` would register, detach and sync-lane
                                    // the call (ProxyGateway.call) — a different operation.
                                    //
                                    // It IS the read id (§2.3): that only makes the call findable
                                    // by a TYPE_CANCEL_READ on this socket, which a client sends
                                    // for a `cancellableReads { }` call alone — never for a write.
                                    val (reply, type) = try {
                                        proxyGateway.call(
                                            clientId = clientId,
                                            request = body,
                                            sessionIdentity = ch,
                                            readId = callId,
                                        ) to StreamFramer.TYPE_CORRELATED_RESPONSE
                                    } catch (e: CancellationException) {
                                        // A read its client stopped (`ReadCancelledException`):
                                        // the client already dropped the id, so no reply. Any
                                        // other: the connection is gone; nobody to answer.
                                        throw e
                                    } catch (e: Exception) {
                                        // The plain path's silent `printStackTrace` is a reply
                                        // that never comes; this one tells the caller.
                                        e.printStackTrace()
                                        (e.message ?: e::class.simpleName ?: "error")
                                            .encodeToByteArray() to StreamFramer.TYPE_CORRELATED_FAILURE
                                    }
                                    runCatching {
                                        writeFramed(StreamFramer.packTagged(callId, reply), type)
                                    }
                                }
                            }

                            // A plain uncorrelated request (TYPE_RESPONSE) is the pre-hello wire,
                            // which no shipped client speaks, and any other type is
                            // one this build does not know. A client gates every frame type on the
                            // revision our hello announced, so either is a peer we cannot answer
                            // correctly: fail closed and drop the connection.
                            else -> {
                                System.err.println(
                                    "[ipc] $clientId sent frame type $type, which this proxy does not " +
                                        "serve — closing the connection"
                                )
                                break
                            }
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }
            connScope.cancel()
            remoteConnections.remove(clientId, connection)
            attestedClientIds.remove(clientId)
        }
    }

    private fun readFully(ch: SocketChannel, buffer: ByteBuffer): Int {
        while (buffer.hasRemaining()) {
            val read = ch.read(buffer)
            if (read == -1) return -1
        }
        return buffer.position()
    }
}
