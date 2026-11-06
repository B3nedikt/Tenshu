package org.tenshu.sync.relay.routing

import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.auth.RelayEntitlementVerifier
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The browser's preflight for the web runtime's content upload: `PUT /sync/content/{ref}` carrying
 * `Authorization` and the `X-Entitlement-Proof` write credential (`JsRuntime`). Before this the CORS
 * config allowed neither PUT nor the proof header, so the preflight was refused and no web upload
 * could reach the route at all.
 */
class ContentUploadCorsTest {

    @Test
    fun aCrossOriginContentPut_passesPreflight() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
            )
        }
        val response = client.options("/sync/content/blob-" + "a".repeat(64)) {
            header(HttpHeaders.Origin, "https://tenshu.app")
            header(HttpHeaders.AccessControlRequestMethod, "PUT")
            header(
                HttpHeaders.AccessControlRequestHeaders,
                "authorization,content-type,${RelayEntitlementVerifier.PROOF_HEADER.lowercase()}",
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("https://tenshu.app", response.headers[HttpHeaders.AccessControlAllowOrigin])
        val methods = response.headers[HttpHeaders.AccessControlAllowMethods].orEmpty()
        assertTrue("PUT" in methods, "PUT must be allowed: $methods")
        val headers = response.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().lowercase()
        assertTrue(RelayEntitlementVerifier.PROOF_HEADER.lowercase() in headers, "proof header must be allowed: $headers")
    }
}
