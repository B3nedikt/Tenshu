package org.tenshu.sync.relay

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.sync.relay.auth.KnownDevicesStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Stage B sweep coverage. Each newly-protected endpoint rejects unauthenticated requests
 * with 401 in production mode. Happy-path coverage lives in `SyncRelayTest` (dev mode)
 * and `SyncPushAuthTest` (JWT-authed push).
 *
 * The unauthenticated capability-by-secret-hash endpoints — `POST /sync/pairing/request`,
 * `POST /sync/pairing/response/peek`, `POST /sync/pairing/ack` — are intentionally omitted; per
 * `sync-relay-api.md` §3.3 they stay unauthenticated.
 */
class SyncEndpointAuthTest {

    private fun productionApp() = io.ktor.server.application.Application::class.let {
        val module: io.ktor.server.application.Application.() -> Unit = {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }
        module
    }

    @Test
    fun syncChanges_noAuth_returns401() = testApplication {
        application(productionApp())
        // /sync/changes became POST after the HLC piggyback landed
        // (sync-backend-relay-plan.md §5.1). The body would carry the signed tip;
        // here we just assert the no-auth path rejects before even reading the body.
        val response = client.post("/sync/changes") {
            contentType(ContentType.Application.OctetStream)
            url { parameters.append("since", "0") }
            setBody(byteArrayOf())
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun syncFetchEntityBlocks_noAuth_returns401() = testApplication {
        application(productionApp())
        val response = client.post("/sync/fetch-entity-blocks") {
            contentType(ContentType.Application.OctetStream)
            setBody(byteArrayOf())
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun syncPairingRequests_noAuth_returns401() = testApplication {
        application(productionApp())
        val response = client.get("/sync/pairing/requests")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun syncPairingResponse_noAuth_returns401() = testApplication {
        application(productionApp())
        val response = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingSecretHash":"${"0".repeat(64)}","encryptedMasterKey":"AA=="}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun pairingRequest_unauthenticatedByDesign_returnsOk() = testApplication {
        // Capability-by-secret endpoint — pairing-secret is the credential. Stays open: the
        // CALLER presents nothing. ⚠ The owner it names must be one the relay has a device for —
        // a request no host could ever poll for is refused (`PairingRequestAbuseBoundsTest`).
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore().apply {
                    register(ownerId = "alice", deviceId = "alices-host", publicKey = ByteArray(32))
                },
            )
        }
        val response = client.post("/sync/pairing/request") {
            contentType(ContentType.Application.Json)
            setBody(
                """{
                    "ownerId": "alice",
                    "pairingSecretHash": "${"1".repeat(64)}",
                    "deviceId": "${org.tenshu.sync.common.deviceIdForPublicKey(ByteArray(32) { 3 })}",
                    "sealedDeviceName": "${java.util.Base64.getEncoder().encodeToString(ByteArray(66))}",
                    "publicKey": "${
                    java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
                }",
                    "signingPublicKey": "${
                    java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
                }"
                }""".trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun pairingResponsePeek_unauthenticatedByDesign_returnsNotFound() = testApplication {
        // Capability-by-secret-hash endpoint — the hash is the credential. Returns 404
        // (not 401) because no pairing response is queued for this hash.
        application(productionApp())
        val response = client.post("/sync/pairing/response/peek") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingSecretHash":"${"2".repeat(64)}"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
