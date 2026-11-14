package org.tenshu.sync.relay.routing

import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.mailbox.ShareStore
import org.tenshu.sync.relay.testModule
import java.net.BindException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The operator routes are "not reachable from the internet" because the relay judges the **raw socket
 * peer**, never a forwarded header. The routing tests stub that judgement; this one does not. A real
 * Netty relay runs the production module, and the same request — right token, forged
 * `X-Forwarded-For: 127.0.0.1` — is sent once over a non-loopback address and once over loopback.
 * Only the loopback one may act.
 *
 * ⚠⚠ **It binds the two addresses it dials, not the wildcard the container binds.** On macOS a
 * `0.0.0.0` listener and another process's `127.0.0.1` listener can share a port, and loopback
 * connections go to the specific one (`tech-debt.md`, the closed `S3BlobStoreContractTest` member),
 * so a wildcard bind let a foreign listener answer the loopback arm. A specific bind on port 0 gets
 * a port no other listener on that address holds. The judgement is unaffected: it reads the socket
 * peer (`rawPeerIsLoopback`), which is the same whichever listener accepted the connection.
 * Pinned by [eachDialledPort_isBoundToItsAddressAlone].
 *
 * ⚠ Skipped (not passed) on a machine with no non-loopback IPv4 address, where it would prove nothing.
 */
class OperatorRouteNetworkExposureTest {

    @Test
    fun theTakedownRoute_answersOnlyALoopbackSocketPeer_whateverTheHeadersSay() {
        val external = externalIpv4()
        assumeTrue("no non-loopback IPv4 address to probe from", external != null)

        val shares = ShareStore()
        shares.create(
            ShareStore.ShareRecord(
                shareId = SHARE, ownerId = "alice", sealedMeta = byteArrayOf(1), chunkCount = 1,
                chunkHashes = emptyList(), expiresAtMillis = null, maxOpens = null, createdAtMs = 0,
            ),
        )
        val server = startRelay(external!!, shares)
        try {
            val ports = portsByHost(server)
            val http = HttpClient.newHttpClient()
            fun takeDown(host: InetAddress): HttpResponse<String> = http.send(
                HttpRequest.newBuilder(URI("http://${host.hostAddress}:${ports.getValue(host.hostAddress)}/ops/share/takedown"))
                    .header(OPERATOR_TOKEN_HEADER, TOKEN)
                    .header("X-Forwarded-For", "127.0.0.1")
                    .header("X-Real-IP", "127.0.0.1")
                    .header("Forwarded", "for=127.0.0.1")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"shareId":"$SHARE","reason":"other"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            val fromOutside = takeDown(external)
            assertEquals(404, fromOutside.statusCode(), "reachable over ${external.hostAddress}: ${fromOutside.body()}")
            assertTrue(shares.takedowns(SHARE).isEmpty(), "a non-loopback caller must not act")
            assertNotNull(shares.open(SHARE), "the link still serves")

            val fromLoopback = takeDown(LOOPBACK)
            assertEquals(200, fromLoopback.statusCode(), fromLoopback.body())
            assertEquals(1, shares.takedowns(SHARE).size)
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun eachDialledPort_isBoundToItsAddressAlone() {
        // Two probes, because each OS discriminates on a different one (`docs/testing-flakes.md` §4):
        //  1. A decoy on 127.0.0.1:<port> is the mechanism itself. It binds beside a wildcard listener
        //     on macOS and not beside a specific one; Linux refuses it either way.
        //  2. A connect to the external address on the LOOPBACK connector's port is answered by a
        //     wildcard listener on any OS, and refused by a loopback-only one. Its forced arm runs
        //     first: a wildcard socket must answer it, or the probe tests nothing on this machine.
        val external = externalIpv4()
        assumeTrue("no non-loopback IPv4 address to probe from", external != null)
        ServerSocket(0, 1, InetAddress.getByName("0.0.0.0")).use { wildcard ->
            assertTrue(accepts(external!!, wildcard.localPort), "forced arm: a wildcard listener should answer on ${external.hostAddress}")
        }

        val server = startRelay(external!!, ShareStore())
        try {
            val loopbackPort = portsByHost(server).getValue(LOOPBACK.hostAddress)
            assertFalse(canBind(LOOPBACK, loopbackPort), "a decoy bound ${LOOPBACK.hostAddress}:$loopbackPort alongside the relay and would take its loopback requests")
            assertFalse(accepts(external, loopbackPort), "the loopback connector answered on ${external.hostAddress}, so it is bound to the wildcard")
        } finally {
            server.stop(0, 0)
        }
    }

    private companion object {
        const val TOKEN = "operator-secret"
        const val SHARE = "CCCCCCCCCCCCCCCCCCCCCC"
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

        fun externalIpv4(): InetAddress? = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }

        /** The production module, on one connector per dialled address, each on its own port 0. */
        fun startRelay(external: InetAddress, shares: ShareStore) = embeddedServer(
            Netty,
            configure = {
                listOf(LOOPBACK, external).forEach { address ->
                    connector {
                        host = address.hostAddress
                        port = 0
                    }
                }
            },
        ) {
            testModule(
                // Production shape: no dev bypass and the operator token set. `bindHost` is what the
                // container binds; only production `main` reads it, never testModule.
                config = RelayConfig(devMode = false, bindHost = "0.0.0.0", port = 0, operatorToken = TOKEN),
                shareStore = shares,
            )
        }.start(wait = false)

        fun portsByHost(server: io.ktor.server.engine.EmbeddedServer<*, *>): Map<String, Int> =
            runBlocking { server.engine.resolvedConnectors() }.associate { it.host to it.port }

        fun canBind(host: InetAddress, port: Int): Boolean = ServerSocket().use { decoy ->
            decoy.reuseAddress = true
            try {
                decoy.bind(InetSocketAddress(host, port))
                true
            } catch (_: BindException) {
                false
            }
        }

        fun accepts(address: InetAddress, port: Int): Boolean = Socket().use { socket ->
            try {
                // wall-clock: a connect to one of this host's own addresses is refused or accepted
                // at once; the bound only stops a filtered interface from hanging the test.
                socket.connect(InetSocketAddress(address, port), 5_000)
                true
            } catch (_: ConnectException) {
                false
            }
        }
    }
}
