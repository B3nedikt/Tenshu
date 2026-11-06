package org.tenshu.sync.relay.routing

import org.tenshu.sync.relay.testModule
import kotlin.test.assertNull
import io.ktor.client.statement.bodyAsText
import org.tenshu.core.common.RelayRefusal

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.auth.MasterDelegationVerifier
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stage-5a coverage of the `POST /sync/pairing/response` master-delegation extension and
 * the `POST /sync/pairing/ack` flow per `sync-relay-api.md` §3.3.
 *
 * The host signs `(newDevicePublicKey || newDeviceId || epoch)` with the master identity;
 * the relay verifies against the realm's stored master public key and only then registers
 * the new device's signing key in `KnownDevicesStore` — without this, an attacker who
 * intercepts the pairing request could substitute a key under a different `(ownerId,
 * deviceId)` and bypass the realm's trust chain.
 *
 * The ack flow replaces the destructive-read on GET so a device that crashes between
 * fetch and processing can still re-fetch within the TTL.
 */
class PairingResponseMasterDelegationTest {

    private val masterKey = "master-recovery-key".encodeToByteArray().copyOf(32)
    private val ownerId = "owner-handshake"
    private val deviceBPubKey = ByteArray(32) { (0xB0 + it).toByte() }
    private val deviceBSigningKey = ByteArray(32) { (0xC0 + it).toByte() }
    private val deviceBId = org.tenshu.sync.common.deviceIdForPublicKey(deviceBSigningKey)
    /** F7: every join route is addressed by the secret's hash (64 lower-case hex), never the secret. */
    private val pairingSecretHash = "d".repeat(64)
    private val epoch = 0L

    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun seedRealm(realmStore: RealmStore) {
        realmStore.createIfAbsent(
            RealmStore.RealmRecord(
                ownerId = ownerId,
                masterPublicKey = masterKey,
                createdAt = 0L
            )
        )
    }

    private fun pairingRequestBody() = """
        {
            "ownerId": "$ownerId",
            "pairingSecretHash": "$pairingSecretHash",
            "deviceId": "$deviceBId",
            "sealedDeviceName": "${b64(ByteArray(66) { 3 })}",
            "publicKey": "${b64(deviceBPubKey)}",
            "signingPublicKey": "${b64(deviceBSigningKey)}"
        }
    """.trimIndent()

    private fun responseBody(
        signatureKey: ByteArray = masterKey,
        signedMessage: ByteArray? = null,
        deviceIdOverride: String? = null,
    ): String {
        val message = signedMessage ?: MasterDelegationVerifier.pairingResponseMessage(
            newDevicePublicKey = deviceBSigningKey,
            newDeviceId = deviceIdOverride ?: deviceBId,
            epoch = epoch
        )
        val delegation = stubEd25519Signature(signatureKey, message)
        return """
            {
                "pairingSecretHash": "$pairingSecretHash",
                "encryptedMasterKey": "${b64(byteArrayOf(5, 6, 7))}",
                "newDevicePublicKey": "${b64(deviceBSigningKey)}",
                "newDeviceId": "${deviceIdOverride ?: deviceBId}",
                "masterDelegation": "${b64(delegation)}",
                "epoch": "$epoch"
            }
        """.trimIndent()
    }

    @Test
    fun validDelegation_registersDeviceKey() = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore
            )
        }

        // Pre-flight: Device B posts pairing request.
        client.post("/sync/pairing/request") {
            contentType(ContentType.Application.Json)
            setBody(pairingRequestBody())
        }.bodyAsBytes()

        // Host posts response with valid master delegation.
        val response = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            setBody(responseBody())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        response.bodyAsBytes()

        // The verified registration is queued with the response, not applied by the post: a
        // joiner that never fetches must leave no registered device behind (see below).
        assertNull(knownDevices.getPublicKey(ownerId, deviceBId), "not registered until delivered")

        // Device B fetches its response: its key is registered BEFORE the bytes are served, so its
        // first JWT-authed request (only possible after it decrypts them) verifies.
        assertEquals(HttpStatusCode.OK, peek().status)
        val registered = knownDevices.getPublicKey(ownerId, deviceBId)
        assertNotNull(registered)
        assertTrue(registered.contentEquals(deviceBSigningKey))
        assertEquals(setOf(deviceBId), knownDevices.deviceIds(ownerId))
    }

    @Test
    fun aResponseThatExpiresUnfetched_leavesNoRegisteredDevice() = testApplication {
        // The relay used to register the joiner when the host POSTED. A joiner that never fetched
        // within the TTL left a registered device whose key was never delivered: a permanent member
        // of `deviceIds`, which never acks a tombstone, so the realm's tombstone-GC quorum could
        // never be met again. Injected clock, not a sleep.
        val realmStore = RealmStore().apply { seedRealm(this) }
        val knownDevices = KnownDevicesStore()
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                syncMailbox = org.tenshu.sync.relay.SyncMailbox(
                    pairingResponses = org.tenshu.sync.relay.PairingResponseInbox(nowMs = { now.get() }),
                ),
            )
        }
        client.post("/sync/pairing/request") {
            contentType(ContentType.Application.Json)
            setBody(pairingRequestBody())
        }.bodyAsBytes()
        val posted = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            setBody(responseBody())
        }
        assertEquals(HttpStatusCode.OK, posted.status)
        posted.bodyAsBytes()

        now.addAndGet(org.tenshu.sync.relay.PAIRING_REQUEST_TTL_MS)
        assertEquals(HttpStatusCode.NotFound, peek().status, "expired")
        assertNull(knownDevices.getPublicKey(ownerId, deviceBId), "an undelivered admission registers nothing")
        assertTrue(knownDevices.deviceIds(ownerId).isEmpty(), "and adds nobody to the GC quorum")
    }

    @Test
    fun aJoinerRemovedBeforeItFetches_isRefusedAtDelivery_andNotRegistered() = testApplication {
        // The post-time F2′ check is up to one TTL old by the time the joiner fetches.
        val realmStore = RealmStore().apply { seedRealm(this) }
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
            )
        }
        client.post("/sync/pairing/request") {
            contentType(ContentType.Application.Json)
            setBody(pairingRequestBody())
        }.bodyAsBytes()
        val posted = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            setBody(responseBody())
        }
        assertEquals(HttpStatusCode.OK, posted.status)
        posted.bodyAsBytes()

        knownDevices.recordRemoval(ownerId, deviceBId)
        val refused = peek()
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertTrue(refused.bodyAsText().startsWith(RelayRefusal.DEVICE_REMOVED))
        assertNull(knownDevices.getPublicKey(ownerId, deviceBId))
    }

    @Test
    fun removedDevice_isRefused403_withTheDeviceRemovedToken_andIsNotRegistered() = testApplication {
        // F2′ on the join route, pinned at the wire: the refusal must CARRY
        // [RelayRefusal.DEVICE_REMOVED], because the client keys on the body token — this same
        // route answers 403 for a revoked approver capability, which is about a different device
        // and is not terminal. `PairingOfferRoutingTest` pins the offer route; a token enforced
        // on one of two equivalent routes is not enforced.
        val realmStore = RealmStore().apply { seedRealm(this) }
        val knownDevices = KnownDevicesStore().apply { recordRemoval(ownerId, deviceBId) }
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore
            )
        }

        client.post("/sync/pairing/request") {
            contentType(ContentType.Application.Json)
            setBody(pairingRequestBody())
        }.bodyAsBytes()

        val response = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            setBody(responseBody())
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsText()
        assertTrue(
            RelayRefusal.matches(body, RelayRefusal.DEVICE_REMOVED),
            "the join-route refusal must carry the DEVICE_REMOVED token — got: $body",
        )
        assertNull(knownDevices.getPublicKey(ownerId, deviceBId), "a refused joiner is not registered")
    }

    /**
     * Workstream B on the join route: the master signs a delegation over the joiner's real key but an
     * id that is not that key's — a valid A1 signature that would register a squat. Refused 400 with
     * the token, before anything is queued, and nothing is ever delivered or registered.
     */
    @Test
    fun aValidDelegation_overAnIdThatIsNotTheKeys_isRefused400_andNothingIsQueued() = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore
            )
        }
        val squatted = org.tenshu.sync.common.deviceIdForPublicKey(ByteArray(32) { 0x55 })
        val response = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            setBody(responseBody(deviceIdOverride = squatted))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(RelayRefusal.matches(body, RelayRefusal.DEVICE_ID_NOT_KEY_DERIVED), body)
        assertEquals(HttpStatusCode.NotFound, peek().status, "nothing may be queued for delivery")
        assertNull(knownDevices.getPublicKey(ownerId, squatted))
        assertNull(knownDevices.getPublicKey(ownerId, deviceBId))
    }

    /** Order: a mismatched id under a FORGED delegation is the derivation's 400, not the signature's 401. */
    @Test
    fun aMismatchedId_underAForgedDelegation_isTheDerivationRefusal_notA401() = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = realmStore
            )
        }
        val response = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            setBody(
                responseBody(
                    signatureKey = "attacker-master-key".encodeToByteArray().copyOf(32),
                    deviceIdOverride = org.tenshu.sync.common.deviceIdForPublicKey(ByteArray(32) { 0x55 }),
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(RelayRefusal.matches(response.bodyAsText(), RelayRefusal.DEVICE_ID_NOT_KEY_DERIVED))
    }

    @Test
    fun forgedDelegation_returns401_andDoesNotRegister() = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore
            )
        }

        client.post("/sync/pairing/request") {
            contentType(ContentType.Application.Json)
            setBody(pairingRequestBody())
        }.bodyAsBytes()

        // Sign with the wrong key — verification must fail and the device must NOT be
        // admitted. This is the load-bearing protection for §3.3.
        val attackerKey = "attacker-master-key".encodeToByteArray().copyOf(32)
        val response = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            setBody(responseBody(signatureKey = attackerKey))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)

        // No device registered.
        kotlin.test.assertNull(knownDevices.getPublicKey(ownerId, deviceBId))
    }

    @Test
    fun missingDelegationField_returns400() = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = realmStore
            )
        }

        client.post("/sync/pairing/request") {
            contentType(ContentType.Application.Json)
            setBody(pairingRequestBody())
        }.bodyAsBytes()

        // Body without master-delegation fields — pre-extension shape — must be rejected.
        val response = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            setBody(
                """{
                    "pairingSecretHash": "$pairingSecretHash",
                    "encryptedMasterKey": "${b64(byteArrayOf(1, 2))}"
                }""".trimIndent()
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun unknownRealm_returns404() = testApplication {
        // No realm seeded — should 404 even with a well-formed body.
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore()
            )
        }

        client.post("/sync/pairing/request") {
            contentType(ContentType.Application.Json)
            setBody(pairingRequestBody())
        }.bodyAsBytes()

        val response = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            setBody(responseBody())
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun getResponse_isPeekOnly_repeatedReadsReturnSameBytes() = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = realmStore
            )
        }

        client.post("/sync/pairing/request") {
            contentType(ContentType.Application.Json)
            setBody(pairingRequestBody())
        }.bodyAsBytes()

        client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            setBody(responseBody())
        }.bodyAsBytes()

        // First fetch — succeeds.
        val firstFetch = peek()
        assertEquals(HttpStatusCode.OK, firstFetch.status)
        val firstBytes = firstFetch.bodyAsBytes()

        // Second fetch BEFORE ack — same bytes, no destructive-read footgun.
        val secondFetch = peek()
        assertEquals(HttpStatusCode.OK, secondFetch.status)
        assertTrue(firstBytes.contentEquals(secondFetch.bodyAsBytes()))
    }

    @Test
    fun aResponsePastItsTtl_peeksAs404() = testApplication {
        // F7: responses now expire like the offer flow's (PAIRING_REQUEST_TTL_MS). Pinned at the
        // route, not only on PairingResponseInbox, with an injected clock rather than a sleep.
        val realmStore = RealmStore().apply { seedRealm(this) }
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = realmStore,
                syncMailbox = org.tenshu.sync.relay.SyncMailbox(
                    pairingResponses = org.tenshu.sync.relay.PairingResponseInbox(nowMs = { now.get() }),
                ),
            )
        }

        client.post("/sync/pairing/request") {
            contentType(ContentType.Application.Json)
            setBody(pairingRequestBody())
        }.bodyAsBytes()
        val posted = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            setBody(responseBody())
        }
        assertEquals(HttpStatusCode.OK, posted.status)
        posted.bodyAsBytes()

        now.addAndGet(org.tenshu.sync.relay.PAIRING_REQUEST_TTL_MS - 1)
        assertEquals(HttpStatusCode.OK, peek().status, "still served one millisecond before the TTL")

        now.addAndGet(1)
        assertEquals(HttpStatusCode.NotFound, peek().status, "gone at the TTL")
    }

    @Test
    fun ack_consumesResponseState_subsequentGetReturns404() = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = realmStore
            )
        }

        client.post("/sync/pairing/request") {
            contentType(ContentType.Application.Json)
            setBody(pairingRequestBody())
        }.bodyAsBytes()

        client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            setBody(responseBody())
        }.bodyAsBytes()

        // Device B fetches once (peek)…
        val fetch = peek()
        assertEquals(HttpStatusCode.OK, fetch.status)
        fetch.bodyAsBytes()

        // …then ack's after processing the master key. Capability is the secret's hash.
        val ack = client.post("/sync/pairing/ack") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingSecretHash":"$pairingSecretHash"}""")
        }
        assertEquals(HttpStatusCode.OK, ack.status)
        ack.bodyAsBytes()

        // After ack, the entry is gone…
        assertEquals(HttpStatusCode.NotFound, peek().status)

        // …and the hash is retired: a second response under it is refused before it registers
        // anything (the offer flow's consumed-marker shape, F7).
        val refill = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            setBody(responseBody())
        }
        assertEquals(HttpStatusCode.Conflict, refill.status)
        // ⚠ Distinguishable from every other 409: a host retrying a post whose reply it lost
        // reads this token as "delivered" (ApprovePairingRequestImplementation).
        assertTrue(
            RelayRefusal.matches(refill.bodyAsText(), RelayRefusal.PAIRING_RESPONSE_CONSUMED),
            "the consumed 409 must carry its RelayRefusal token",
        )
    }

    @Test
    fun theOldQueryStringFetch_isGone() = testApplication {
        // F4/F7: `GET /sync/pairing/response?pairingSecret=` put the raw secret in a URI. There is
        // no such route any more — not kept beside the new one.
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore().apply { seedRealm(this) }
            )
        }
        val legacy = client.get("/sync/pairing/response") {
            url { parameters.append("pairingSecret", "secret-xyz") }
        }
        assertTrue(
            legacy.status == HttpStatusCode.NotFound || legacy.status == HttpStatusCode.MethodNotAllowed,
            "the raw-secret GET must not be served, got ${legacy.status}",
        )
    }

    @Test
    fun peekAndAck_refuseAKeyThatIsNotAHash() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore().apply { seedRealm(this) }
            )
        }
        val peek = client.post("/sync/pairing/response/peek") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingSecretHash":"secret-xyz"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, peek.status)
        val ack = client.post("/sync/pairing/ack") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingSecret":"secret-xyz"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, ack.status)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.peek() =
        client.post("/sync/pairing/response/peek") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingSecretHash":"$pairingSecretHash"}""")
        }
}
