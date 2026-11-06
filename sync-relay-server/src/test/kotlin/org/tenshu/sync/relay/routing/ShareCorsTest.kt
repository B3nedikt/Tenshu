package org.tenshu.sync.relay.routing

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CORS coverage for the anonymous Mode-A share viewer (`GET /share/{shareId}`), which a
 * browser on the web origin fetches cross-origin from the relay origin. Without the right
 * `Access-Control-Allow-Origin` the browser blocks the fetch and the link "leads nowhere"
 * in the viewer even though the relay is healthy.
 *
 * The allowlist is `RelayConfig.corsAllowedOrigins` (env `CORS_ALLOWED_ORIGINS`), defaulting
 * to the apex (`tenshu.app`) where the viewer is served today.
 */
class ShareCorsTest {

    private fun prod(corsAllowedOrigins: List<String> = emptyList()) = RelayConfig(
        devMode = false,
        bindHost = "127.0.0.1",
        port = 8080,
        corsAllowedOrigins = corsAllowedOrigins,
    )

    @Test
    fun default_allowsApexViewerOrigin() = testApplication {
        application { testModule(config = prod(), knownDevices = KnownDevicesStore()) }
        // Absent share => the route runs and 410s; CORS echoes the allowed origin on it.
        val response = client.get("/share/absent") {
            header(HttpHeaders.Origin, "https://tenshu.app")
        }
        assertEquals(HttpStatusCode.Gone, response.status)
        assertEquals("https://tenshu.app", response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun configuredOrigin_isAllowed() = testApplication {
        application {
            testModule(
                config = prod(corsAllowedOrigins = listOf("https://app.example.com:8443")),
                knownDevices = KnownDevicesStore(),
            )
        }
        val response = client.get("/share/absent") {
            header(HttpHeaders.Origin, "https://app.example.com:8443")
        }
        assertEquals(HttpStatusCode.Gone, response.status)
        assertEquals(
            "https://app.example.com:8443",
            response.headers[HttpHeaders.AccessControlAllowOrigin],
        )
    }

    @Test
    fun disallowedOrigin_isForbidden() = testApplication {
        application { testModule(config = prod(), knownDevices = KnownDevicesStore()) }
        // tenshu.app is the only default-allowed origin; an unrelated one is rejected by
        // the CORS plugin (403) before the route runs.
        val response = client.get("/share/absent") {
            header(HttpHeaders.Origin, "https://evil.example.com")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun configuredAllowlist_doesNotImplicitlyKeepApex() = testApplication {
        // A deployment that sets CORS_ALLOWED_ORIGINS fully specifies its origins — the
        // apex default is replaced, not merged. Documents the substitution behaviour.
        application {
            testModule(
                config = prod(corsAllowedOrigins = listOf("https://app.example.com")),
                knownDevices = KnownDevicesStore(),
            )
        }
        val response = client.get("/share/absent") {
            header(HttpHeaders.Origin, "https://tenshu.app")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
