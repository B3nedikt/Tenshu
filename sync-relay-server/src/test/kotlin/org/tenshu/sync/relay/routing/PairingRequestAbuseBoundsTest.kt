package org.tenshu.sync.relay.routing

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.sync.common.deviceIdForPublicKey
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.SyncMailbox
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.testModule
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What `POST /sync/pairing/request` refuses, given that it can refuse nobody by credential — an
 * unpaired joiner holds none (`sync-relay-api.md` §3.3, threat model T-P-13).
 *
 * ⚠⚠ Run with `devMode = false`: the known-owner check is skipped in dev mode, where the device
 * registry is legitimately empty, so a dev-mode test of it passes for the wrong reason.
 */
class PairingRequestAbuseBoundsTest {

    private val key: String = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    private fun config(pairingLimit: Int = 0) = RelayConfig(
        devMode = false,
        bindHost = "127.0.0.1",
        port = 8080,
        pairingRequestRateLimitPerHour = pairingLimit,
    )

    private fun devicesWith(ownerId: String) = KnownDevicesStore().apply {
        register(ownerId = ownerId, deviceId = "host-device", publicKey = ByteArray(32))
    }

    /** A well-formed `pairingSecretHash` per label — the route checks the 64-hex shape (F7). */
    private fun hashOf(label: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(label.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private suspend fun HttpClient.postRequest(
        ownerId: String = KNOWN_OWNER,
        deviceId: String = deviceIdForPublicKey(ByteArray(32) { it.toByte() }),
        secret: String = "secret-1",
        secretHash: String = hashOf(secret),
        sealedName: ByteArray = ByteArray(66) { 7 },
    ): HttpStatusCode = post("/sync/pairing/request") {
        contentType(ContentType.Application.Json)
        val sealed = Base64.getEncoder().encodeToString(sealedName)
        setBody(
            """
            {"ownerId": "$ownerId", "pairingSecretHash": "$secretHash", "deviceId": "$deviceId",
             "sealedDeviceName": "$sealed", "publicKey": "$key", "signingPublicKey": "$key"}
            """.trimIndent()
        )
    }.status

    @Test
    fun aRequestForAnOwnerWithNoRegisteredDevice_isRefusedAndNotStored() = testApplication {
        val mailbox = SyncMailbox()
        application { testModule(config = config(), knownDevices = devicesWith(KNOWN_OWNER), syncMailbox = mailbox) }

        // Nobody could ever read these: `GET /sync/pairing/requests` needs a device JWT for the
        // owner. Accepting them is what let a caller mint one map entry per request.
        repeat(50) { i ->
            assertEquals(HttpStatusCode.NotFound, client.postRequest(ownerId = "made-up-owner-$i"))
        }
        repeat(50) { i -> assertTrue(mailbox.getPairingRequests("made-up-owner-$i").isEmpty()) }

        // …and the real joiner is unaffected.
        assertEquals(HttpStatusCode.OK, client.postRequest())
        assertEquals(1, mailbox.getPairingRequests(KNOWN_OWNER).size)
    }

    @Test
    fun anOversizedField_isRefused() = testApplication {
        val mailbox = SyncMailbox()
        application { testModule(config = config(), knownDevices = devicesWith(KNOWN_OWNER), syncMailbox = mailbox) }

        val tooLong = "x".repeat(MAX_PAIRING_REQUEST_FIELD_LENGTH + 1)
        assertEquals(HttpStatusCode.BadRequest, client.postRequest(deviceId = tooLong))
        assertEquals(HttpStatusCode.BadRequest, client.postRequest(secretHash = "a".repeat(65)))
        assertEquals(
            HttpStatusCode.BadRequest,
            client.postRequest(sealedName = ByteArray(MAX_SEALED_DEVICE_NAME_BYTES + 1)),
        )
        assertTrue(mailbox.getPairingRequests(KNOWN_OWNER).isEmpty(), "a refused request must not be stored")

        // The limits themselves are accepted — ceilings, not off-by-ones. ⚠ Not for `deviceId` any
        // more: since workstream B the only id this route accepts is the 32-hex one derived from
        // `signingPublicKey`, so a 256-character id is refused by that rule whatever its length.
        assertEquals(
            HttpStatusCode.BadRequest,
            client.postRequest(deviceId = "x".repeat(MAX_PAIRING_REQUEST_FIELD_LENGTH)),
        )
        assertEquals(HttpStatusCode.OK, client.postRequest(sealedName = ByteArray(MAX_SEALED_DEVICE_NAME_BYTES)))
    }

    @Test
    fun overThePerIpBudget_returns429() = testApplication {
        application { testModule(config = config(pairingLimit = 2), knownDevices = devicesWith(KNOWN_OWNER)) }

        repeat(2) { i ->
            assertNotEquals(
                HttpStatusCode.TooManyRequests,
                client.postRequest(secret = "s-$i"),
                "request ${i + 1} is within the budget",
            )
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.postRequest(secret = "s-over"))
    }

    @Test
    fun withTheBudgetDisabled_nothingIsThrottled() = testApplication {
        application { testModule(config = config(pairingLimit = 0), knownDevices = devicesWith(KNOWN_OWNER)) }

        repeat(10) { i -> assertEquals(HttpStatusCode.OK, client.postRequest(secret = "s-$i")) }
    }

    private companion object {
        const val KNOWN_OWNER = "owner-with-a-device"
    }
}
