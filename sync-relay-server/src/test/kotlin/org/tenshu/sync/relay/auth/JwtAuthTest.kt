package org.tenshu.sync.relay.auth

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.tenshu.core.common.security.JwtClaims
import org.tenshu.core.common.security.encodeJwt
import org.tenshu.core.security.stubEd25519Signature
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals

class JwtAuthTest {

    private val devicePrivateKey = "device-1-secret-key".encodeToByteArray()

    // Stub-level: publicKey == privateKey (matches generateKeyPair() behaviour today).
    private val devicePublicKey = devicePrivateKey

    private fun jwtAuthAppFixture(
        store: KnownDevicesStore,
        nowEpochSec: () -> Long = { 1_000L }
    ) = io.ktor.server.application.Application::class.let {
        // Returns a function suitable as the testApplication module
        val authenticator = JwtAuthenticator(store, StubEd25519Backend(), nowEpochSec)
        val module: io.ktor.server.application.Application.() -> Unit = {
            routing {
                get("/protected") {
                    val claims = authenticator.require(call) ?: return@get
                    call.respondText("ok ${claims.ownerId}/${claims.deviceId}")
                }
            }
        }
        module
    }

    private fun signToken(claims: JwtClaims): String =
        encodeJwt(claims) { msg -> stubEd25519Signature(devicePrivateKey, msg) }

    @Test
    fun happyPath_returnsClaims() = testApplication {
        val store = KnownDevicesStore().apply {
            register("owner-a", "device-1", devicePublicKey)
        }
        application(jwtAuthAppFixture(store))

        val token = signToken(JwtClaims("owner-a", "device-1", 900L, 1_300L))
        val response = client.get("/protected") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok owner-a/device-1", response.bodyAsText())
    }

    @Test
    fun missingAuthHeader_returns401() = testApplication {
        val store = KnownDevicesStore()
        application(jwtAuthAppFixture(store))

        val response = client.get("/protected")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun nonBearerScheme_returns401() = testApplication {
        val store = KnownDevicesStore()
        application(jwtAuthAppFixture(store))

        val response = client.get("/protected") {
            header("Authorization", "Basic dXNlcjpwYXNz")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun unknownDevice_returns401() = testApplication {
        // Store is empty; even a perfectly-signed token from a non-admitted device must fail.
        val store = KnownDevicesStore()
        application(jwtAuthAppFixture(store))

        val token = signToken(JwtClaims("owner-a", "ghost-device", 900L, 1_300L))
        val response = client.get("/protected") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun expiredToken_returns401() = testApplication {
        val store = KnownDevicesStore().apply {
            register("owner-a", "device-1", devicePublicKey)
        }
        // Server time is past the token's exp.
        application(jwtAuthAppFixture(store, nowEpochSec = { 9_999L }))

        val token = signToken(JwtClaims("owner-a", "device-1", 900L, 1_300L))
        val response = client.get("/protected") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun signatureFromWrongKey_returns401() = testApplication {
        // Device-1's public key registered, but the token is signed by a different private key.
        val store = KnownDevicesStore().apply {
            register("owner-a", "device-1", devicePublicKey)
        }
        application(jwtAuthAppFixture(store))

        val attackerKey = "attacker-key".encodeToByteArray()
        val claims = JwtClaims("owner-a", "device-1", 900L, 1_300L)
        val attackerToken = encodeJwt(claims) { msg -> stubEd25519Signature(attackerKey, msg) }
        val response = client.get("/protected") {
            header("Authorization", "Bearer $attackerToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun algNoneAttack_returns400() = testApplication {
        // Hand-craft an alg=none token. The decoder must reject before even reaching the
        // verifier. End-to-end behaviour: 4xx, no access.
        val store = KnownDevicesStore().apply {
            register("owner-a", "device-1", devicePublicKey)
        }
        application(jwtAuthAppFixture(store))

        val noneToken = handCraftToken(
            headerJson = """{"alg":"none","typ":"JWT"}""",
            payloadJson = """{"iss":"owner-a","sub":"device-1","iat":900,"exp":1300}""",
            signature = ByteArray(0)
        )
        val response = client.get("/protected") {
            header("Authorization", "Bearer $noneToken")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun malformedToken_returns400() = testApplication {
        val store = KnownDevicesStore()
        application(jwtAuthAppFixture(store))

        val response = client.get("/protected") {
            header("Authorization", "Bearer this-is-not-a-jwt")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun handCraftToken(
        headerJson: String,
        payloadJson: String,
        signature: ByteArray
    ): String {
        fun b64(b: ByteArray) = Base64.UrlSafe.encode(b).trimEnd('=')
        return "${b64(headerJson.encodeToByteArray())}.${b64(payloadJson.encodeToByteArray())}.${
            b64(
                signature
            )
        }"
    }
}
