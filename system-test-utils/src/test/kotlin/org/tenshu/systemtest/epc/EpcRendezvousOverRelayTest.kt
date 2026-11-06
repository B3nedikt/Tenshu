package org.tenshu.systemtest.epc

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.tenshu.core.StreamElement
import org.tenshu.core.common.assembleRemoteConsumeStack
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.jwtTokenProvider
import org.tenshu.core.common.util.deriveEpcRendezvousSessionId
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.epc.rendezvous.EpcRendezvousChannel
import org.tenshu.epc.rendezvous.RendezvousDirection
import org.tenshu.epc.rendezvous.RendezvousRelayClient
import org.tenshu.sync.proxy.epc.EpcRendezvousAcceptor
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.module
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 3b of `docs/proposals/ios-proxy-architecture.md` — proves the Phase-2
 * `EpcConnector`/`EpcResponder` work **over the real relay** (`/sync/epc`
 * mailbox, hosted in-process via Ktor `testApplication`), not just the loopback:
 * app and proxy complete the handshake (matching SAS) and round-trip encrypted
 * operations, with the proxy provisioning the session under its device-key JWT
 * (the admission gate) and the app talking anonymously by capability.
 *
 * EPC crypto is `BootstrapSyncCryptoManager` (this proves the bridge, not crypto
 * strength — that's pinned in the 2a/2c real-crypto desktopTests); JWT auth uses
 * the real `BouncyCastleEd25519Backend` so provisioning is genuinely
 * authenticated against the relay's verifier.
 */
class EpcRendezvousOverRelayTest {

    private val crypto = BootstrapSyncCryptoManager()

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun handshakeAndEncryptedOpsRoundTrip_overRelay() = testApplication {
        // Relay with a registered proxy device so its JWT verifies (real Ed25519).
        val ed = BouncyCastleEd25519Backend()
        val proxyDeviceSeed = ByteArray(32) { (it + 7).toByte() }
        val knownDevices = KnownDevicesStore().apply {
            register("proxy-owner", "proxy-device", ed.derivePublicKey(proxyDeviceSeed))
        }
        application {
            module(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                ed25519 = ed,
            )
        }
        val http = createClient { install(ContentNegotiation) { json() } }

        val sessionId = deriveEpcRendezvousSessionId("pairing-seed".encodeToByteArray()).toHexString()

        // Proxy side: provision under the device-key JWT, then serve an echo handler.
        val proxyToken = jwtTokenProvider(
            ownerIdProvider = { "proxy-owner" },
            deviceIdProvider = { "proxy-device" },
            signer = { msg -> ed.sign(proxyDeviceSeed, msg) },
            nowEpochSec = { System.currentTimeMillis() / 1000 },
        )
        val acceptor = EpcRendezvousAcceptor(
            crypto = crypto,
            client = RendezvousRelayClient(baseUrl = "", httpClient = http, authTokenProvider = proxyToken),
            sessionId = sessionId,
        )
        acceptor.provision()
        val serveScope = CoroutineScope(Dispatchers.Default)
        serveScope.launch {
            acceptor.serve { request -> "echo:".encodeToByteArray() + request }
        }

        var channel: EpcRendezvousChannel? = null
        try {
            // App side: anonymous duplex channel; run the EPC handshake over the relay mailbox.
            channel = withTimeout(15_000) {
                EpcRendezvousChannel.connect(
                    crypto = crypto,
                    client = RendezvousRelayClient(baseUrl = "", httpClient = http, authTokenProvider = { null }),
                    sessionId = sessionId,
                )
            }

            // Capture the next proxy-pushed callback up front (the reader is already running).
            val pushedCallback = CompletableDeferred<ByteArray>()
            channel.onCallback = { pushedCallback.complete(it) }

            // Both ends agreed on the SAS via the relay-brokered handshake.
            assertNotNull(acceptor.establishedSas)
            assertEquals(acceptor.establishedSas, channel.sas)

            // Encrypted operation round-trips end to end through the mailbox.
            val response1 = withTimeout(15_000) {
                channel.call("list /Photos".encodeToByteArray())
            }
            assertContentEquals("echo:list /Photos".encodeToByteArray(), response1)

            // A second op rides the same established channel (counters advance).
            val response2 = withTimeout(15_000) {
                channel.call("open /Photos/a.jpg".encodeToByteArray())
            }
            assertContentEquals("echo:open /Photos/a.jpg".encodeToByteArray(), response2)

            // Server push: the proxy emits an unsolicited callback (e.g. an observer update); it is
            // sealed on the proxy→client key, demuxed by the client reader, and surfaced via onCallback
            // — interleaved with the reply stream, not a reply to any call.
            val delivered = withTimeout(15_000) {
                acceptor.pushCallback("notify:badge=3".encodeToByteArray())
            }
            assertTrue(delivered, "pushCallback ran against an established session")
            assertContentEquals(
                "notify:badge=3".encodeToByteArray(),
                withTimeout(15_000) { pushedCallback.await() },
            )

            // Ops hookup, end-to-end: the proxy pushes a *serialized stream element* (exactly what
            // StreamRegistry sends via ClientConnection.send). The client's assembled consume stack —
            // RemoteBinaryTaskExecutor over this same channel — parses the callback into a StreamElement
            // and routes it (the iOS consume path). Proves push → onCallback → parse → handler over the
            // real relay + EPC seal, not just raw bytes.
            val context = createTestContext(createSyntheticTestRegistry())
            val consumeSerializer = BinarySerializer()
            val consumeParser = BinaryParser(context.schemaProvider(), context.dataFactory())
            val stack = assembleRemoteConsumeStack(
                transport = channel,
                serializer = consumeSerializer,
                parser = consumeParser,
                context = context,
                scope = serveScope,
            )
            val pushedElement = CompletableDeferred<StreamElement<*>>()
            stack.executor.streamElementHandler = { if (it is StreamElement<*>) pushedElement.complete(it) }

            acceptor.pushCallback(
                consumeSerializer.serialize(StreamElementValueDto(streamId = 7L, payload = "x".toTString(context))),
            )
            assertEquals(7L, withTimeout(15_000) { pushedElement.await() }.streamId.get())
        } finally {
            channel?.unbind()
            serveScope.cancel()
        }
    }

    /**
     * Robustness regression (found live: Fit's auto-connected channel died with signal 6 on-device):
     * a mailbox frame that fails AEAD authentication — a relay redelivery (the counter-based nonce
     * makes every replay unauthenticatable, which IS the replay protection) or injected garbage —
     * must be **dropped**, not escape the reader/serve coroutine. On Kotlin/Native an escaped
     * exception aborts the whole process. Poisons BOTH directions, then proves the channel still
     * round-trips an op.
     */
    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun corruptFramesAreDropped_channelAndAcceptorSurvive() = testApplication {
        val ed = BouncyCastleEd25519Backend()
        val proxyDeviceSeed = ByteArray(32) { (it + 7).toByte() }
        val knownDevices = KnownDevicesStore().apply {
            register("proxy-owner", "proxy-device", ed.derivePublicKey(proxyDeviceSeed))
        }
        application {
            module(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                ed25519 = ed,
            )
        }
        val http = createClient { install(ContentNegotiation) { json() } }
        val sessionId = deriveEpcRendezvousSessionId("poison-seed".encodeToByteArray()).toHexString()

        val proxyToken = jwtTokenProvider(
            ownerIdProvider = { "proxy-owner" },
            deviceIdProvider = { "proxy-device" },
            signer = { msg -> ed.sign(proxyDeviceSeed, msg) },
            nowEpochSec = { System.currentTimeMillis() / 1000 },
        )
        val proxyClient = RendezvousRelayClient(baseUrl = "", httpClient = http, authTokenProvider = proxyToken)
        val appClient = RendezvousRelayClient(baseUrl = "", httpClient = http, authTokenProvider = { null })
        val acceptor = EpcRendezvousAcceptor(crypto = crypto, client = proxyClient, sessionId = sessionId)
        acceptor.provision()
        val serveScope = CoroutineScope(Dispatchers.Default)
        serveScope.launch {
            acceptor.serve { request ->
                check(!request.contentEquals("boom".encodeToByteArray())) { "dispatch blew up" }
                "echo:".encodeToByteArray() + request
            }
        }

        var channel: EpcRendezvousChannel? = null
        try {
            channel = withTimeout(15_000) {
                EpcRendezvousChannel.connect(crypto = crypto, client = appClient, sessionId = sessionId)
            }
            // Sanity: the channel works before poisoning.
            assertContentEquals(
                "echo:ping".encodeToByteArray(),
                withTimeout(15_000) { channel.call("ping".encodeToByteArray()) },
            )

            // Poison both directions with bytes no session can authenticate.
            val garbage = ByteArray(48) { 0x5A }
            appClient.append(sessionId, RendezvousDirection.CLIENT_TO_PROXY, garbage) // hits the acceptor
            proxyClient.append(sessionId, RendezvousDirection.PROXY_TO_CLIENT, garbage) // hits the reader

            // Both loops must shrug the garbage off: the next op still round-trips end to end
            // (reply seq advances past the dropped frame on the client reader, too).
            assertContentEquals(
                "echo:still alive".encodeToByteArray(),
                withTimeout(15_000) { channel.call("still alive".encodeToByteArray()) },
            )

            // A THROWING dispatch (e.g. a reply that fails to serialize — the seam outside
            // ProxyGateway's executor catch) must not kill the serve loop either: the acceptor
            // answers that call with an EMPTY body (FIFO reply alignment), the call completes
            // (empty here — a real client fails it at parse), and the op AFTER it still works.
            // Before the fix this starved every subsequent op into a timeout.
            assertContentEquals(
                ByteArray(0),
                withTimeout(15_000) { channel.call("boom".encodeToByteArray()) },
            )
            assertContentEquals(
                "echo:after the boom".encodeToByteArray(),
                withTimeout(15_000) { channel.call("after the boom".encodeToByteArray()) },
            )

            // CONCURRENT calls: the session's send counter is the AEAD nonce and the proxy opens
            // frames in strict arrival order, so seal order must equal wire order — and replies
            // are FIFO-matched, so every reply must land on ITS caller. Without the channel's
            // sendMutex this races: counter-mismatched frames get dropped (observed live as
            // Fit's import writes racing UI polling ops into timeouts) and replies can
            // cross-match. 16 concurrent calls, each asserting its own echo.
            val results = (0 until 16).map { i ->
                serveScope.async(Dispatchers.Default) {
                    val payload = "concurrent-$i".encodeToByteArray()
                    payload to withTimeout(15_000) { channel.call(payload) }
                }
            }.map { it.await() }
            results.forEach { (sent, got) ->
                assertContentEquals("echo:".encodeToByteArray() + sent, got)
            }
        } finally {
            channel?.unbind()
            serveScope.cancel()
        }
    }
}
