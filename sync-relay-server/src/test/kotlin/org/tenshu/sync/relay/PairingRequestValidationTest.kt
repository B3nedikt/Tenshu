package org.tenshu.sync.relay

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `POST /sync/pairing/request` rejects a key it cannot store meaningfully.
 *
 * The route is necessarily **unauthenticated** — an unpaired joiner holds no credentials — so
 * whatever it accepts is what every host on that owner id has to cope with. A key that is not
 * 32 bytes makes the host's SAS underivable (threat model **T-P-13**), and malformed base64 used
 * to throw out of `Base64.decode` and surface as a 500: the caller's fault reported as the
 * server's.
 *
 * ⚠ This is **defence in depth, not the defence**. House Rule #1 makes the relay untrusted, so the
 * host drops underivable-SAS requests on its own side regardless of what this route allows
 * (`MalformedPairingRequestSystemTest`). Rejecting here keeps junk out of the mailbox and hands a
 * broken client a 400 it can act on.
 */
class PairingRequestValidationTest {

    private val validKey: String = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun aWellFormedRequestIsAccepted() = testApplication {
        application { testModule() }
        assertEquals(HttpStatusCode.OK, post(publicKey = validKey, signingPublicKey = validKey))
    }

    @Test
    fun aShortPublicKeyIsRejected() = testApplication {
        application { testModule() }
        val short = Base64.getEncoder().encodeToString(byteArrayOf(0x01))
        assertEquals(HttpStatusCode.BadRequest, post(publicKey = short, signingPublicKey = validKey))
    }

    @Test
    fun anOversizedSigningKeyIsRejected() = testApplication {
        application { testModule() }
        val long = Base64.getEncoder().encodeToString(ByteArray(64))
        assertEquals(HttpStatusCode.BadRequest, post(publicKey = validKey, signingPublicKey = long))
    }

    /**
     * Workstream B: a joiner's id is its A4 key's. A request naming any other id — a UUID, or another
     * key's id — is refused before it reaches a host's mailbox.
     */
    @Test
    fun aDeviceIdThatIsNotTheSigningKeys_isRejected() = testApplication {
        val mailbox = org.tenshu.sync.relay.SyncMailbox()
        application { testModule(syncMailbox = mailbox) }
        val othersId = org.tenshu.sync.common.deviceIdForPublicKey(ByteArray(32) { 0x55 })
        assertEquals(HttpStatusCode.BadRequest, post(publicKey = validKey, signingPublicKey = validKey, deviceIdJson = othersId))
        assertEquals(
            HttpStatusCode.BadRequest,
            post(publicKey = validKey, signingPublicKey = validKey, deviceIdJson = "6f1c2a7e-0b9d-4c55-9a51-2f3e4d5c6b7a"),
        )
        assertTrue(mailbox.getPairingRequests("owner-1").isEmpty(), "a refused request must not be stored")
    }

    /** B1: the device-id rule every registering route shares — no control characters. */
    @Test
    fun aDeviceIdWithControlCharacters_isRejected() = testApplication {
        application { testModule() }
        assertEquals(
            HttpStatusCode.BadRequest,
            post(publicKey = validKey, signingPublicKey = validKey, deviceIdJson = "joiner\\u0000device"),
        )
    }

    // ── F7: the relay is never sent the secret or the clear name ──

    @Test
    fun aRawSecretInTheBody_isRefused() = testApplication {
        // The pre-F7 wire. Refused outright, so a stale client fails loudly instead of leaking.
        application { testModule() }
        assertEquals(
            HttpStatusCode.BadRequest,
            post(publicKey = validKey, signingPublicKey = validKey, extra = "\"pairingSecret\": \"secret-1\","),
        )
    }

    @Test
    fun aClearDeviceNameInTheBody_isRefused() = testApplication {
        application { testModule() }
        assertEquals(
            HttpStatusCode.BadRequest,
            post(publicKey = validKey, signingPublicKey = validKey, extra = "\"deviceName\": \"Joiner\","),
        )
    }

    @Test
    fun aSecretInTheHashSlot_isRefused() = testApplication {
        // A UUID-shaped secret where the 64-hex hash belongs — the exact bug openapi known issue 4
        // described ("the raw secret in the slot named pairingSecretHash").
        application { testModule() }
        assertEquals(
            HttpStatusCode.BadRequest,
            post(publicKey = validKey, signingPublicKey = validKey, hash = "6f1c2a7e-0b9d-4c55-9a51-2f3e4d5c6b7a"),
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            post(publicKey = validKey, signingPublicKey = validKey, hash = "A".repeat(64)),
            "upper-case hex is not the construction's output",
        )
    }

    @Test
    fun aMissingOrOversizedSealedName_isRefused() = testApplication {
        application { testModule() }
        assertEquals(HttpStatusCode.BadRequest, post(publicKey = validKey, signingPublicKey = validKey, sealedName = null))
        assertEquals(
            HttpStatusCode.BadRequest,
            post(
                publicKey = validKey,
                signingPublicKey = validKey,
                sealedName = Base64.getEncoder().encodeToString(ByteArray(org.tenshu.sync.relay.routing.MAX_SEALED_DEVICE_NAME_BYTES + 1)),
            ),
        )
    }

    @Test
    fun malformedBase64IsARejectionNotAServerError() = testApplication {
        application { testModule() }
        // Bare `Base64.decode` threw here, which Ktor rendered as a 500.
        assertEquals(
            HttpStatusCode.BadRequest,
            post(publicKey = "not!valid!base64", signingPublicKey = validKey),
        )
    }

    /**
     * The bare `testApplication` client carries no `ContentNegotiation`, so the body goes as a
     * literal JSON string rather than a map (same caveat `TestSystemBuilder` documents).
     */
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.post(
        publicKey: String,
        signingPublicKey: String,
        hash: String = "c".repeat(64),
        sealedName: String? = Base64.getEncoder().encodeToString(ByteArray(66) { 7 }),
        extra: String = "",
        deviceIdJson: String = org.tenshu.sync.common.deviceIdForPublicKey(Base64.getDecoder().decode(signingPublicKey)),
    ): HttpStatusCode = client.post("/sync/pairing/request") {
        contentType(ContentType.Application.Json)
        val sealedField = sealedName?.let { "\"sealedDeviceName\": \"$it\"," } ?: ""
        setBody(
            """
            {
              "ownerId": "owner-1",
              "pairingSecretHash": "$hash",
              "deviceId": "$deviceIdJson",
              $sealedField
              $extra
              "publicKey": "$publicKey",
              "signingPublicKey": "$signingPublicKey"
            }
            """.trimIndent()
        )
    }.status
}
