package org.tenshu.sync.relay.routing

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.mailbox.ShareStore
import org.tenshu.sync.relay.testModule
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The operator routes are "not reachable from the internet" because the relay judges the **raw socket
 * peer**, never a forwarded header. The routing tests stub that judgement; this one does not. A real
 * Netty relay binds every interface (as the container does), and the same request — right token,
 * forged `X-Forwarded-For: 127.0.0.1` — is sent once over a non-loopback address and once over
 * loopback. Only the loopback one may act.
 *
 * ⚠ Skipped (not passed) on a machine with no non-loopback IPv4 address, where it would prove nothing.
 */
class OperatorRouteNetworkExposureTest {

    @Test
    fun theTakedownRoute_answersOnlyALoopbackSocketPeer_whateverTheHeadersSay() {
        val external = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        assumeTrue("no non-loopback IPv4 address to probe from", external != null)

        val shares = ShareStore()
        shares.create(
            ShareStore.ShareRecord(
                shareId = SHARE, ownerId = "alice", sealedMeta = byteArrayOf(1), chunkCount = 1,
                chunkHashes = emptyList(), expiresAtMillis = null, maxOpens = null, createdAtMs = 0,
            ),
        )
        val server = embeddedServer(Netty, port = 0, host = "0.0.0.0") {
            testModule(
                // Production shape: no dev bypass, every interface bound, the operator token set.
                config = RelayConfig(devMode = false, bindHost = "0.0.0.0", port = 0, operatorToken = TOKEN),
                shareStore = shares,
            )
        }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val http = HttpClient.newHttpClient()
            fun takeDown(host: InetAddress): HttpResponse<String> = http.send(
                HttpRequest.newBuilder(URI("http://${host.hostAddress}:$port/ops/share/takedown"))
                    .header(OPERATOR_TOKEN_HEADER, TOKEN)
                    .header("X-Forwarded-For", "127.0.0.1")
                    .header("X-Real-IP", "127.0.0.1")
                    .header("Forwarded", "for=127.0.0.1")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"shareId":"$SHARE","reason":"other"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            val fromOutside = takeDown(external!!)
            assertEquals(404, fromOutside.statusCode(), "reachable over ${external.hostAddress}: ${fromOutside.body()}")
            assertTrue(shares.takedowns(SHARE).isEmpty(), "a non-loopback caller must not act")
            assertNotNull(shares.open(SHARE), "the link still serves")

            val fromLoopback = takeDown(InetAddress.getLoopbackAddress())
            assertEquals(200, fromLoopback.statusCode(), fromLoopback.body())
            assertEquals(1, shares.takedowns(SHARE).size)
        } finally {
            server.stop(0, 0)
        }
    }

    private companion object {
        const val TOKEN = "operator-secret"
        const val SHARE = "CCCCCCCCCCCCCCCCCCCCCC"
    }
}
