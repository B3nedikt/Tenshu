package org.tenshu.sync.relay.routing

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.common.RELAY_PROTOCOL_VERSION
import org.tenshu.core.common.RelayRefusal
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.sync.common.deviceIdForPublicKey
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.auth.MasterDelegationVerifier
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SR-3b.4b: `POST /sync/admit-device` admits an additional device to an existing realm,
 * gated by a master delegation (no JWT). Mirrors [RealmRoutingTest]'s stub-crypto harness
 * (pub == priv; `stubEd25519Signature`). A shared realm is just a realm at the relay, so
 * these assertions cover the shared-realm admission path directly.
 */
class AdmitDeviceRoutingTest {

    private val masterPrivateKey = "shared-realm-master-key".encodeToByteArray().copyOf(32)
    private val masterPublicKey = masterPrivateKey // stub: pub == priv
    private val firstDevicePublicKey = "owner-device-pubkey".encodeToByteArray().copyOf(32)
    private val firstDeviceId = deviceIdForPublicKey(firstDevicePublicKey)
    private val ownerId = ownerIdForMasterPublicKey(masterPublicKey)

    private val newDevicePublicKey = "joiner-device-pubkey".encodeToByteArray().copyOf(32)
    private val newDeviceId = deviceIdForPublicKey(newDevicePublicKey)
    private val epoch = 0L

    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun bootstrapBody(): String {
        val message = MasterDelegationVerifier.realmBootstrapMessage(
            ownerId = ownerId,
            masterPublicKey = masterPublicKey,
            firstDevicePublicKey = firstDevicePublicKey,
            firstDeviceId = firstDeviceId,
        )
        val sig = stubEd25519Signature(masterPrivateKey, message)
        return """
            {
                "relayProtocolVersion": "$RELAY_PROTOCOL_VERSION",
                "ownerId": "$ownerId",
                "masterPublicKey": "${b64(masterPublicKey)}",
                "firstDevicePublicKey": "${b64(firstDevicePublicKey)}",
                "firstDeviceId": "$firstDeviceId",
                "masterSelfSignature": "${b64(sig)}"
            }
        """.trimIndent()
    }

    private fun admitBody(
        ownerIdOverride: String? = null,
        signatureKey: ByteArray = masterPrivateKey,
    ): String {
        val message = MasterDelegationVerifier.pairingResponseMessage(
            newDevicePublicKey = newDevicePublicKey,
            newDeviceId = newDeviceId,
            epoch = epoch,
        )
        val delegation = stubEd25519Signature(signatureKey, message)
        return """
            {
                "ownerId": "${ownerIdOverride ?: ownerId}",
                "newDeviceId": "$newDeviceId",
                "newDevicePublicKey": "${b64(newDevicePublicKey)}",
                "masterDelegation": "${b64(delegation)}",
                "epoch": "$epoch"
            }
        """.trimIndent()
    }

    @Test
    fun validDelegation_registersTheDevice() = testApplication {
        val realmStore = RealmStore()
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
            )
        }

        client.post("/sync/realm") {
            contentType(ContentType.Application.Json); setBody(bootstrapBody())
        }.bodyAsText()

        val response = client.post("/sync/admit-device") {
            contentType(ContentType.Application.Json); setBody(admitBody())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        response.bodyAsText()

        val registered = knownDevices.getPublicKey(ownerId, newDeviceId)
        assertNotNull(registered, "the admitted device's A4 is registered for the realm")
        assertTrue(registered.contentEquals(newDevicePublicKey))
    }

    /**
     * ⚠⚠ **A removed device holding A1 can still re-register, and that is DELIBERATE.**
     *
     * This route verifies against the realm's **master** public key, so reaching it at all means A1
     * is in hand. `threat-model.md` records the consequence explicitly: *"an A1 holder re-derives the
     * volume key from the phrase and re-registers itself with `reregisterThisDevice`, so no rotation
     * can exclude it … Any 'one secret regenerates everything' recovery design accepts this trade
     * deliberately"*, and `admission-revocation-names-the-capability.md` §5b.9 is titled *"An owner
     * device cannot be removed"*.
     *
     * ⚠ So the removal ledger is **not** consulted here, on purpose. It fences the join and offer
     * flows, where a device presents *itself* without A1. Adding a `wasRemoved` refusal to this route
     * looks like closing a hole and actually breaks `RealmRegistrar.reregisterThisDevice`, whose
     * admit-device fallback is how a device repairs a registration the relay lost — turning a
     * transient failure into a permanent one. This test exists so that change fails loudly.
     */
    @Test
    fun anA1Holder_reRegisters_evenAfterItsIdentityWasRemoved() = testApplication {
        val realmStore = RealmStore()
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
            )
        }
        client.post("/sync/realm") {
            contentType(ContentType.Application.Json); setBody(bootstrapBody())
        }.bodyAsText()

        knownDevices.recordRemoval(ownerId, newDeviceId)

        val response = client.post("/sync/admit-device") {
            contentType(ContentType.Application.Json); setBody(admitBody())
        }

        assertEquals(
            HttpStatusCode.OK,
            response.status,
            "A1 in hand outranks the ledger here — see the KDoc, this is the accepted residual",
        )
        assertNotNull(knownDevices.getPublicKey(ownerId, newDeviceId))
    }

    @Test
    fun invalidDelegation_returns401_andDoesNotRegister() = testApplication {
        val realmStore = RealmStore()
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
            )
        }
        client.post("/sync/realm") {
            contentType(ContentType.Application.Json); setBody(bootstrapBody())
        }.bodyAsText()

        // Signed with an attacker key, not the realm master — must not verify.
        val response = client.post("/sync/admit-device") {
            contentType(ContentType.Application.Json)
            setBody(admitBody(signatureKey = "attacker".encodeToByteArray()))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertNull(knownDevices.getPublicKey(ownerId, newDeviceId), "no registration on a bad delegation")
    }

    @Test
    fun unknownRealm_returns404() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore(),
            )
        }
        // No realm bootstrapped → admit must 404.
        val response = client.post("/sync/admit-device") {
            contentType(ContentType.Application.Json); setBody(admitBody())
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun missingField_returns400() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore(),
            )
        }
        val response = client.post("/sync/admit-device") {
            contentType(ContentType.Application.Json); setBody("""{"ownerId": "x"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ---- B1: the unauthenticated route cannot be fed another message's signature ----

    private fun admitRaw(newDeviceIdJson: String, key: ByteArray, delegation: ByteArray, epochValue: Long) = """
        {
            "ownerId": "$ownerId",
            "newDeviceId": "$newDeviceIdJson",
            "newDevicePublicKey": "${b64(key)}",
            "masterDelegation": "${b64(delegation)}",
            "epoch": "$epochValue"
        }
    """.trimIndent()

    private fun runAdmit(body: String, expectRegisteredId: String?): HttpStatusCode {
        var status = HttpStatusCode.OK
        testApplication {
            val realmStore = RealmStore()
            val knownDevices = KnownDevicesStore()
            application {
                testModule(
                    config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                    knownDevices = knownDevices,
                    realmStore = realmStore,
                )
            }
            client.post("/sync/realm") { contentType(ContentType.Application.Json); setBody(bootstrapBody()) }.bodyAsText()
            val response = client.post("/sync/admit-device") { contentType(ContentType.Application.Json); setBody(body) }
            response.bodyAsText()
            status = response.status
            if (expectRegisteredId != null) {
                assertEquals(null, knownDevices.getPublicKey(ownerId, expectRegisteredId), "nothing may be registered")
            }
        }
        return status
    }

    /**
     * ⚠⚠ The B1 attack against this route: an A1-signed root cert C, re-presented as a delegation
     * with `K = C[0:|C|−12]`, `deviceId = "\u0000\u0000\u0000\b"`, `epoch` = C's last 8 bytes. Under
     * the old bare encoding that delegation WAS C, so the cert's signature registered a junk device.
     */
    @Test
    fun aCertsSignature_replayedAsADelegation_registersNothing() {
        val a33 = ByteArray(32) { (it + 9).toByte() }
        val cert = org.tenshu.sync.common.AdmissionCapabilityCert.message(ownerId, a33, "device-a", 7L)
        val certSignature = stubEd25519Signature(masterPrivateKey, cert)
        val status = runAdmit(
            admitRaw("\\u0000\\u0000\\u0000\\b", cert.copyOfRange(0, cert.size - 12), certSignature, 7L),
            expectRegisteredId = "\u0000\u0000\u0000\b",
        )
        assertTrue(status != HttpStatusCode.OK, "a cert signature admitted a device: $status")
    }

    /**
     * The same replay, shaped to PASS the input rules (a 32-byte K cut from the cert, a well-formed
     * id, the cert's epoch), so it reaches signature verification: the tagged delegation bytes are
     * not the cert's, and the cert's A1 signature is refused as a delegation — 401, not 400.
     */
    @Test
    fun aCertsSignature_passingTheInputRules_isRefusedByTheSignatureCheck() {
        val a33 = ByteArray(32) { (it + 9).toByte() }
        val cert = org.tenshu.sync.common.AdmissionCapabilityCert.message(ownerId, a33, "device-a", 7L)
        val certSignature = stubEd25519Signature(masterPrivateKey, cert)
        val k = cert.copyOfRange(0, 32)
        val id = deviceIdForPublicKey(k)
        assertEquals(
            HttpStatusCode.Unauthorized,
            runAdmit(admitRaw(id, k, certSignature, 7L), expectRegisteredId = id),
        )
    }

    @Test
    fun aKeyThatIsNot32Bytes_returns400() {
        val shortKey = ByteArray(31) { 1 }
        assertEquals(
            HttpStatusCode.BadRequest,
            runAdmit(admitRaw(newDeviceId, shortKey, ByteArray(32), epoch), expectRegisteredId = newDeviceId),
        )
    }

    @Test
    fun aDeviceIdWithControlCharacters_returns400() {
        val message = MasterDelegationVerifier.pairingResponseMessage(newDevicePublicKey, "bad\u0001id", epoch)
        assertEquals(
            HttpStatusCode.BadRequest,
            runAdmit(
                admitRaw("bad\\u0001id", newDevicePublicKey, stubEd25519Signature(masterPrivateKey, message), epoch),
                expectRegisteredId = "bad\u0001id",
            ),
        )
    }

    // ---- Workstream B: the id is the key's ----

    /**
     * ⚠⚠ A VALID A1 delegation over `(key, id)` where `id` is not `deviceIdForPublicKey(key)` — the
     * squat: an admitter (or anyone holding its signature) registering a second key under an id that
     * is someone else's. Refused before the signature is even looked at, and nothing is registered.
     */
    @Test
    fun aValidDelegation_overAnIdThatIsNotTheKeys_returns400_andRegistersNothing() {
        val victimsId = deviceIdForPublicKey(ByteArray(32) { 0x55 })
        val message = MasterDelegationVerifier.pairingResponseMessage(newDevicePublicKey, victimsId, epoch)
        var body = ""
        var status = HttpStatusCode.OK
        testApplication {
            val realmStore = RealmStore()
            val knownDevices = KnownDevicesStore()
            application {
                testModule(
                    config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                    knownDevices = knownDevices,
                    realmStore = realmStore,
                )
            }
            client.post("/sync/realm") { contentType(ContentType.Application.Json); setBody(bootstrapBody()) }.bodyAsText()
            val response = client.post("/sync/admit-device") {
                contentType(ContentType.Application.Json)
                setBody(admitRaw(victimsId, newDevicePublicKey, stubEd25519Signature(masterPrivateKey, message), epoch))
            }
            body = response.bodyAsText()
            status = response.status
            assertNull(knownDevices.getPublicKey(ownerId, victimsId), "a squatted id was registered")
        }
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(RelayRefusal.matches(body, RelayRefusal.DEVICE_ID_NOT_KEY_DERIVED), body)
    }

    /**
     * Pins the ORDER, not just the presence: a mismatched id with a FORGED delegation is answered
     * with the derivation refusal (400 + token), not the signature's 401 — the id is checked before
     * any signature is verified.
     */
    @Test
    fun aMismatchedId_isRefusedBeforeTheSignatureIsChecked() {
        val victimsId = deviceIdForPublicKey(ByteArray(32) { 0x55 })
        var body = ""
        var status = HttpStatusCode.OK
        testApplication {
            application {
                testModule(
                    config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                    knownDevices = KnownDevicesStore(),
                    realmStore = RealmStore(),
                )
            }
            client.post("/sync/realm") { contentType(ContentType.Application.Json); setBody(bootstrapBody()) }.bodyAsText()
            val response = client.post("/sync/admit-device") {
                contentType(ContentType.Application.Json)
                setBody(admitRaw(victimsId, newDevicePublicKey, ByteArray(32) { 9 }, epoch))
            }
            body = response.bodyAsText()
            status = response.status
        }
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(RelayRefusal.matches(body, RelayRefusal.DEVICE_ID_NOT_KEY_DERIVED), body)
    }
}
