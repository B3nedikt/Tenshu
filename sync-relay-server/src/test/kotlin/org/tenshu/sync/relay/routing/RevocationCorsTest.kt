package org.tenshu.sync.relay.routing

import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The browser's preflight for the web runtime's two revocations: cancelling a contact invite
 * (`DELETE /sync/invite/{id}`) and a share link (`DELETE /share/{id}`), both carrying
 * `Authorization`. Without DELETE in the CORS config the preflight is refused with 403, so the
 * browser never sends either request and the web UI can only report that the cancel failed.
 */
class RevocationCorsTest {

    private fun preflightDelete(path: String) = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
            )
        }
        val response = client.options(path) {
            header(HttpHeaders.Origin, "https://tenshu.app")
            header(HttpHeaders.AccessControlRequestMethod, "DELETE")
            header(HttpHeaders.AccessControlRequestHeaders, "authorization")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("https://tenshu.app", response.headers[HttpHeaders.AccessControlAllowOrigin])
        val methods = response.headers[HttpHeaders.AccessControlAllowMethods].orEmpty()
        assertTrue("DELETE" in methods, "DELETE must be allowed: $methods")
    }

    @Test
    fun aCrossOriginInviteRevoke_passesPreflight() = preflightDelete("/sync/invite/" + "a".repeat(32))

    @Test
    fun aCrossOriginShareRevoke_passesPreflight() = preflightDelete("/share/" + "b".repeat(32))
}
