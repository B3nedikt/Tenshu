package org.tenshu.sync.relay.routing

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.common.security.JwtClaims
import org.tenshu.core.common.security.encodeJwt
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A body the relay cannot parse is a **client** error, not a server fault.
 *
 * Every `/sync` route that reads a binary body used to hand it straight to
 * `BinaryParser.parse`, whose failure on a truncated or garbage envelope
 * ("Buffer overflow reading length") escaped the handler and landed in the
 * catch-all `StatusPages` mapping in `Application.kt` — answering **500
 * internal_error** and logging the throw at ERROR as an unhandled exception.
 *
 * That is wrong twice over. The status misattributes a malformed request to the
 * relay, and — the operational half — it buries genuine relay faults in a log
 * any unauthenticated-but-JWT-holding client can flood at will. The relay stores
 * ciphertext (House Rule #1), so it can never inspect a body's *meaning*; being
 * exact about the one thing it can judge, whether the envelope parses at all,
 * is the whole of the contract here.
 *
 * ⚠ Each case sends a **valid JWT**. Auth is checked before the body is read, so
 * an unauthenticated request would 401 and prove nothing about parsing.
 */
class MalformedBodyTest {

    private val devicePrivateKey = "device-malformed-key".encodeToByteArray()
    private val devicePublicKey = devicePrivateKey

    private fun store() = KnownDevicesStore().apply {
        register("alice", "device-malformed", devicePublicKey)
    }

    private fun token(): String {
        val nowSec = System.currentTimeMillis() / 1000
        return encodeJwt(JwtClaims("alice", "device-malformed", nowSec - 5, nowSec + 60)) { msg ->
            stubEd25519Signature(devicePrivateKey, msg)
        }
    }

    /** Not a truncated envelope but plausible garbage — the shape a real corrupted upload has. */
    private val garbage = ByteArray(64) { (it * 7 + 13).toByte() }

    private suspend fun io.ktor.client.HttpClient.postGarbage(path: String) = post(path) {
        contentType(ContentType.Application.OctetStream)
        header("Authorization", "Bearer ${token()}")
        setBody(garbage)
    }

    @Test
    fun push_withMalformedBody_returns400_not500() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store()
            )
        }
        assertEquals(HttpStatusCode.BadRequest, client.postGarbage("/sync/push").status)
    }

    @Test
    fun pushTombstone_withMalformedBody_returns400_not500() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store()
            )
        }
        assertEquals(HttpStatusCode.BadRequest, client.postGarbage("/sync/push-tombstone").status)
    }

    @Test
    fun fetchEntityBlocks_withMalformedBody_returns400_not500() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store()
            )
        }
        assertEquals(
            HttpStatusCode.BadRequest,
            client.postGarbage("/sync/fetch-entity-blocks").status
        )
    }

    /**
     * `/sync/changes` reads a [org.tenshu.sync.SignedHlcTip] through the shared
     * `requireTipSession` helper, so it is a fourth parse site rather than a repeat
     * of the three above — and the only one behind a helper that returns a session.
     */
    @Test
    fun changes_withMalformedTip_returns400_not500() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store()
            )
        }
        assertEquals(HttpStatusCode.BadRequest, client.postGarbage("/sync/changes").status)
    }
}
