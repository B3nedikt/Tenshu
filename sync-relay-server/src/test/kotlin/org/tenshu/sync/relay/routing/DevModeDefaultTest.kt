package org.tenshu.sync.relay.routing

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pins the fail-closed `DEV_MODE` default (`relay-metadata-minimisation.md` D13; openapi known
 * issue 1, resolved): a relay whose environment does **not** set `DEV_MODE` must not honour
 * `X-Dev-Owner`. Before this, an unset variable meant `devMode = true`, so a deployment that
 * forgot `DEV_MODE=false` accepted any caller as any owner.
 *
 * The config is built through [RelayConfig.fromEnv] — the path `main()` takes — rather than the
 * constructor, because the constructor has no default for `devMode` and so cannot regress this.
 */
class DevModeDefaultTest {

    private val blobRef = "blob-" + "a".repeat(64)

    @Test
    fun unsetDevMode_xDevOwnerIsNotHonoured_requestWithoutJwtIsRejected() = testApplication {
        val config = RelayConfig.fromEnv { null }
        assertFalse(config.devMode, "unset DEV_MODE must mean devMode = false")
        application { testModule(config = config, knownDevices = KnownDevicesStore()) }

        val get = client.get("/sync/content/$blobRef") { header("X-Dev-Owner", "alice") }
        assertEquals(HttpStatusCode.Unauthorized, get.status)

        val put = client.put("/sync/content/$blobRef") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(byteArrayOf(1, 2, 3))
        }
        assertEquals(HttpStatusCode.Unauthorized, put.status)

        // The sync plane's own bypass (`authenticateAndRecordTip`) — auth runs before the body.
        val changes = client.post("/sync/changes") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "alice")
            setBody(byteArrayOf())
        }
        assertEquals(HttpStatusCode.Unauthorized, changes.status)
    }

    @Test
    fun explicitDevModeTrue_stillHonoursXDevOwner() = testApplication {
        // The opt-in still works: `./gradlew :sync-relay-server:run` exports DEV_MODE=true.
        // (Loopback bind so the config is one `validate()` would accept.)
        val config = RelayConfig.fromEnv { if (it == "DEV_MODE") "true" else null }
            .copy(bindHost = "127.0.0.1")
        application { testModule(config = config, knownDevices = KnownDevicesStore()) }

        // Authenticated as alice via the bypass ⇒ the route runs and finds no such blob.
        val get = client.get("/sync/content/$blobRef") { header("X-Dev-Owner", "alice") }
        assertEquals(HttpStatusCode.NotFound, get.status)
    }
}
