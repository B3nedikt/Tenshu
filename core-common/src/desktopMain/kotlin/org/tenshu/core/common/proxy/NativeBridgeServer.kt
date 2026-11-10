package org.tenshu.core.common.proxy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.tenshu.core.common.transport.BoundSocketFile
import org.tenshu.core.common.transport.PeerCredentialPolicy
import org.tenshu.core.common.transport.PeerCredentialVerifier
import org.tenshu.core.common.transport.SocketDirectory
import org.tenshu.core.common.transport.SocketPathResolver
import org.tenshu.core.common.transport.StreamFramer
import org.tenshu.core.common.transport.UnverifiedPeerCredentialVerifier
import org.tenshu.core.common.transport.admit
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * Small, stable, **native-facing** JSON request/response bridge over a
 * group-container Unix-domain socket, distinct from the binary
 * [org.tenshu.core.common.transport.UnixSocketServer]
 * (docs/proposals/desktop-macos-integration.md §2.2).
 *
 * The wire today is `BinarySerializer` framing; porting that codec to Swift is a
 * large, fragile job, and the macOS surfaces (tray, File Provider) need very
 * little. So the daemon exposes this minimal JSON surface; native clients speak
 * JSON, the daemon owns the mapping to the binary proxy protocol + the
 * permission checks. The bridge is **private/internal** — not the public
 * `@tenshu/sdk` surface — and versioned by [PROTOCOL_VERSION].
 *
 * ### Wire format
 * Each message is length-framed exactly like the binary transport
 * ([StreamFramer.frame] → `[4-byte BE length][1-byte type][payload]`), so a
 * native client reuses one framing routine. The payload is a UTF-8 JSON object.
 *
 * Request:  `{"v":1,"id":"<client-string>","method":"<name>","params":{…}}`
 * Success:  `{"v":1,"id":"<echoed>","result":{…}}`
 * Error:    `{"v":1,"id":"<echoed>","error":{"code":"<code>","message":"<text>"}}`
 *
 * v0 ships exactly two methods: `ping` (built in) and `getSyncState` (registered
 * by the daemon, which alone can reach `SyncManager`).
 *
 * ### Handler contract
 * Handlers speak **plain Kotlin** — params arrive as a `Map<String, String>`,
 * the result is a `Map<String, Any?>` of JSON-shaped values
 * (String / Boolean / Number / null, and nested `Map`/`List`). The server owns
 * all JSON encode/decode so feature modules registering methods never depend on
 * the JSON library. v0 params are string-valued by contract; richer param types
 * arrive with a [PROTOCOL_VERSION] bump.
 *
 * ### Threading
 * ⚠⚠ Handlers **block** (they wrap suspend reads in `runBlocking`), so they run on
 * [handlerDispatcher] — an elastic `Dispatchers.IO.limitedParallelism` view — and **never on
 * `Dispatchers.IO` itself**. The work under a handler hops to `Dispatchers.IO` (the store, the
 * IPC transport); with handlers on that same pool, 64 in flight held every IO thread while each
 * waited for one more, and the bridge deadlocked for good (seen live: Finder's thumbnail burst).
 * A view's threads do not count against `Dispatchers.IO`'s cap, so a full house of handlers can
 * only queue further requests, never starve the work they are waiting on.
 * `NativeBridgeServerTest.aFullHouseOfBlockingHandlersStillReachesDispatchersIo` pins this.
 */
class NativeBridgeServer(
    private val socketPath: String = SocketPathResolver.resolveBridge(),
    // Desktop IPC peer-credential gate (threat-model A14 / T-C-15). Default =
    // wired-but-non-enforcing until the native verifier lands; same posture as the
    // binary transport. Injecting a real verifier + strict policy turns it on.
    private val peerVerifier: PeerCredentialVerifier = UnverifiedPeerCredentialVerifier,
    private val peerPolicy: PeerCredentialPolicy = PeerCredentialPolicy(),
    // A16 developer mode: shared posture with UnixSocketServer. Sourced from a build-type
    // signal so it cannot be enabled in a release build.
    private val developerMode: Boolean = false,
    // How many handlers may block at once across all connections; requests beyond it queue.
    handlerParallelism: Int = DEFAULT_HANDLER_PARALLELISM,
) {
    private val handlers = ConcurrentHashMap<String, BridgeHandler>()

    /** Where handlers run — see *Threading* above for why this is not `Dispatchers.IO`. */
    private val handlerDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(handlerParallelism)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var serverChannel: ServerSocketChannel? = null

    @Volatile
    private var stopped = false
    private val clientChannels = ConcurrentHashMap.newKeySet<SocketChannel>()

    init {
        // Built-in liveness probe — no daemon state needed, so it lives here
        // rather than being injected. Echoes `params.message` (or "pong") back
        // plus the protocol version; this is the Phase 0 de-risk round-trip.
        register("ping") { params ->
            mapOf(
                "pong" to true,
                "v" to PROTOCOL_VERSION,
                "message" to (params["message"] ?: "pong"),
            )
        }
    }

    /** Registers (or replaces) the handler for [method]. */
    fun register(method: String, handler: BridgeHandler) {
        handlers[method] = handler
    }

    /**
     * Binds the socket and starts accepting. Binding happens on the caller's
     * thread so a bind failure surfaces here (and the socket is connectable the
     * moment this returns); only the accept loop runs on the daemon thread.
     */
    @Volatile
    private var boundFile: BoundSocketFile? = null

    /** How often each distinct gate message has been seen, so repeats stay quiet but never invisible. */
    private val gateLogCounts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    /**
     * Logs a gate message the first time, then again at the 10th, 100th, 1000th… repeat with its count. Per distinct
     * message, not "last message": two peers alternating no longer log every connection, and a rejected peer
     * retrying in a loop still shows up as a growing count.
     */
    private fun logGateOnce(message: String) {
        if (gateLogCounts.size > GATE_LOG_MAX_REASONS) gateLogCounts.clear()
        val n = gateLogCounts.computeIfAbsent(message) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        when {
            n == 1L -> System.err.println(message)
            isPowerOfTen(n) -> System.err.println("$message (seen $n times)")
        }
    }

    private fun isPowerOfTen(n: Long): Boolean {
        var v = n
        while (v >= 10 && v % 10 == 0L) v /= 10
        return v == 1L && n >= 10
    }

    fun start() {
        check(serverChannel == null) { "NativeBridgeServer already started" }
        val path = Path.of(socketPath)
        // Same owner-only guarantee as the binary transport — this socket hands a connected
        // client plaintext file read/write, so it is the last one that should sit in a
        // world-traversable directory.
        SocketDirectory.prepare(path.parent)
        // ⚠ Before the unlink: see BoundSocketFile.claim.
        val claim = BoundSocketFile.claim(path)
        Files.deleteIfExists(path)

        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(socketPath))
        boundFile = claim.bound()
        serverChannel = channel

        thread(isDaemon = true, name = "NativeBridgeServer") {
            try {
                channel.use {
                    while (true) {
                        handleClient(it.accept())
                    }
                }
            } catch (e: Exception) {
                // stop() closes the channel under the accept() — expected, stay quiet.
                if (!stopped) e.printStackTrace()
            }
        }
    }

    /**
     * Stops accepting and tears down every open connection. Idempotent. The
     * socket file is removed so a stale path can't shadow a later [start] of a
     * fresh instance — unless another instance has bound the path since ([BoundSocketFile]).
     */
    fun stop() {
        stopped = true
        runCatching { serverChannel?.close() }
        clientChannels.forEach { runCatching { it.close() } }
        clientChannels.clear()
        boundFile?.removeIfStillOurs()
    }

    private fun handleClient(clientChannel: SocketChannel) {
        clientChannels.add(clientChannel)
        thread(isDaemon = true, name = "NativeBridgeClientHandler") {
            try {
                serveClient(clientChannel)
            } finally {
                clientChannels.remove(clientChannel)
            }
        }
    }

    private fun serveClient(clientChannel: SocketChannel) {
        // Peer-credential gate (A14 / T-C-15): admit-or-close before serving any
        // frame. Non-enforcing by default; closes the channel on a strict reject.
        // The bridge serves only Control's own tray agent and has no per-app identity of its
        // own, so it uses the admit/reject half of the gate and ignores the credential.
        // ⚠ One log line per distinct reason: a tray agent opens a connection for every poll and every
        // click, so logging each admission flooded a dev run's console (the client side does the same,
        // `UnixSocketTaskTransport.logServerGateOnce`). A different peer or a new reason still logs.
        if (peerVerifier.admit(clientChannel, peerPolicy, developerMode, log = ::logGateOnce) == null) return
        val writeLock = ReentrantLock()
        val connScope = CoroutineScope(handlerDispatcher + SupervisorJob())

        fun writeResponse(payload: ByteArray) {
            writeLock.lock()
            try {
                clientChannel.write(ByteBuffer.wrap(StreamFramer.frame(payload)))
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
                    if (!StreamFramer.isValidFrameLength(length)) break

                    typeBuffer.clear()
                    if (readFully(ch, typeBuffer) == -1) break

                    val payloadBuffer = ByteBuffer.allocate(length)
                    if (readFully(ch, payloadBuffer) == -1) break
                    val payload = payloadBuffer.array().sliceArray(0 until length)

                    connScope.launch { writeResponse(dispatch(payload)) }
                } catch (e: Exception) {
                    break
                }
            }
        }
        connScope.cancel()
    }

    /** Parses one request payload, invokes the handler, and serialises the reply. */
    internal fun dispatch(payload: ByteArray): ByteArray {
        var id: String? = null
        var method: String? = null
        val reply: JsonObject = try {
            val request = json.parseToJsonElement(payload.decodeToString()).jsonObject
            id = request["id"]?.asStringOrNull()
            method = request["method"]?.asStringOrNull()
            val handler = method?.let { handlers[it] }
            when {
                method == null -> errorReply(id, "invalid_request", "missing 'method'")
                handler == null -> errorReply(id, "unknown_method", "no handler for '$method'")
                else -> {
                    val params = (request["params"] as? JsonObject)
                        ?.mapValues { (_, v) -> v.asStringOrNull() ?: v.toString() }
                        ?: emptyMap()
                    successReply(id, toJsonElement(handler.handle(params)))
                }
            }
        } catch (e: BridgeException) {
            // Authored by a handler, for the peer — the one message deliberately crossing the
            // socket. ⚠ Handlers embed the *caller's own* `params["path"]` in these ("no file at
            // '$…'"), which discloses nothing: the peer supplied it. See the KDoc note below on
            // the one shape that is not caller-supplied.
            errorReply(id, e.code, e.message ?: e.code)
        } catch (e: Exception) {
            // ⚠⚠ A FIXED string, never `e.message`. An arbitrary throwable's message is written
            // for a developer, not for a peer, and on this bridge it routinely carries an absolute
            // realm path — `java.nio` alone renders as
            // `/Users/…/Library/…/files/private/x (No such file or directory)`. Unlike the
            // BridgeException branch above, that path is NOT one the caller supplied, so returning
            // it hands the peer filesystem layout it had no other way to learn. The diagnosis goes
            // to this process's stderr instead, where it is as useful and reaches nobody else.
            System.err.println(
                "[bridge] method '${method ?: "?"}' threw ${e::class.simpleName}: ${e.message}"
            )
            errorReply(id, "internal", "internal error")
        }
        return json.encodeToString(JsonObject.serializer(), reply).encodeToByteArray()
    }

    private fun successReply(id: String?, result: JsonElement): JsonObject =
        buildJsonObject {
            put("v", PROTOCOL_VERSION)
            put("id", id ?: "")
            put("result", result)
        }

    private fun errorReply(id: String?, code: String, message: String): JsonObject =
        buildJsonObject {
            put("v", PROTOCOL_VERSION)
            put("id", id ?: "")
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }

    /** Content of a JSON string primitive, or null for non-string elements. */
    private fun JsonElement.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun readFully(ch: SocketChannel, buffer: ByteBuffer): Int {
        while (buffer.hasRemaining()) {
            if (ch.read(buffer) == -1) return -1
        }
        return buffer.position()
    }

    companion object {
        /** Bumped on any backward-incompatible change to the envelope or a method. */
        const val PROTOCOL_VERSION = 1

        /** Concurrent blocking handlers per server; generous, since each is bounded by its own work. */
        const val DEFAULT_HANDLER_PARALLELISM = 64

        /** Distinct gate messages counted before the counts start over (a bound, not a tuning knob). */
        private const val GATE_LOG_MAX_REASONS = 256

        /** Converts a handler's plain-Kotlin result value into a [JsonElement]. */
        private fun toJsonElement(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(
                value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) }
            )
            is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
            else -> JsonPrimitive(value.toString())
        }
    }
}

/**
 * Handles a single bridge method. [params] are the request's string-valued
 * params; return a JSON-shaped result map, or throw [BridgeException] to produce
 * a structured `error` reply. The built-in impl is `ping`; the daemon registers
 * the rest ([NativeBridgeServer.register]).
 */
fun interface BridgeHandler {
    fun handle(params: Map<String, String>): Map<String, Any?>
}

/** Maps to a structured `{"error":{"code","message"}}` reply. */
class BridgeException(val code: String, message: String) : Exception(message)
