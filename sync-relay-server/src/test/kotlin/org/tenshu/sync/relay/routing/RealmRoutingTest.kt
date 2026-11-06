package org.tenshu.sync.relay.routing

import org.tenshu.sync.relay.testModule

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.common.RELAY_PROTOCOL_MIN_SUPPORTED_VERSION
import org.tenshu.core.common.RELAY_PROTOCOL_VERSION
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.sync.common.deviceIdForPublicKey
import org.tenshu.core.common.RelayRefusal
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.auth.MasterDelegationVerifier
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealmRoutingTest {

    private val masterPrivateKey = "test-master-key-bytes".encodeToByteArray().copyOf(32)
    private val masterPublicKey = masterPrivateKey  // stub-level: pub == priv
    private val firstDevicePublicKey = "first-device-pubkey".encodeToByteArray().copyOf(32)
    private val firstDeviceId = deviceIdForPublicKey(firstDevicePublicKey)

    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun realmBootstrapBody(
        ownerId: String = ownerIdForMasterPublicKey(masterPublicKey),
        masterPubKeyOverride: ByteArray? = null,
        devicePubKeyOverride: ByteArray? = null,
        deviceIdOverride: String? = null,
        signatureKey: ByteArray = masterPrivateKey,
        // E2e relay-protocol gate (core-schema-evolution.md §7.5/§7.8): the relay contract
        // version the client declares. Defaults to the current one so ordinary tests pass
        // the gate; `null` omits the field to model a **pre-E2e** client, which the relay
        // must read as protocol 1 and (while the floor is 1) admit.
        relayProtocolVersion: Int? = RELAY_PROTOCOL_VERSION,
        // Legacy payload-version field. Production clients still send it through the
        // transition window, but an E2e relay never reads it — see
        // `differingFormatVersion_isAccepted`.
        formatVersion: Int? = TENSHU_STORAGE_FORMAT_VERSION
    ): String {
        val mPub = masterPubKeyOverride ?: masterPublicKey
        val dPub = devicePubKeyOverride ?: firstDevicePublicKey
        val dId = deviceIdOverride ?: deviceIdForPublicKey(dPub)
        val message = MasterDelegationVerifier.realmBootstrapMessage(
            ownerId = ownerId,
            masterPublicKey = mPub,
            firstDevicePublicKey = dPub,
            firstDeviceId = dId
        )
        val signature = stubEd25519Signature(signatureKey, message)
        val formatVersionLine =
            if (formatVersion != null) "\n                \"formatVersion\": \"$formatVersion\"," else ""
        val relayProtocolLine =
            if (relayProtocolVersion != null) {
                "\n                \"relayProtocolVersion\": \"$relayProtocolVersion\","
            } else {
                ""
            }
        return """
            {$relayProtocolLine$formatVersionLine
                "ownerId": "$ownerId",
                "masterPublicKey": "${b64(mPub)}",
                "firstDevicePublicKey": "${b64(dPub)}",
                "firstDeviceId": "$dId",
                "masterSelfSignature": "${b64(signature)}"
            }
        """.trimIndent()
    }

    @Test
    fun happyPath_creates201AndRegistersDeviceKey() = testApplication {
        val realmStore = RealmStore()
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore
            )
        }

        val response = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            setBody(realmBootstrapBody())
        }
        assertEquals(HttpStatusCode.Created, response.status)
        response.bodyAsText() // Consume

        val ownerId = ownerIdForMasterPublicKey(masterPublicKey)
        // Side effects: realm stored, first device registered for JWT verification.
        val record = realmStore.get(ownerId)
        assertNotNull(record)
        assertEquals(ownerId, record.ownerId)
        assertTrue(record.masterPublicKey.contentEquals(masterPublicKey))

        val devicePubKey = knownDevices.getPublicKey(ownerId, firstDeviceId)
        assertNotNull(devicePubKey)
        assertTrue(devicePubKey.contentEquals(firstDevicePublicKey))
    }

    /**
     * Workstream B: a master-signed genesis whose first device id is not its key's — the A1 signature
     * is valid, so only the derivation rule stands between it and a registered squat.
     */
    @Test
    fun aFirstDeviceIdThatIsNotTheKeys_returns400_andCreatesNothing() = testApplication {
        val realmStore = RealmStore()
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore
            )
        }
        val squatted = deviceIdForPublicKey(ByteArray(32) { 0x55 })
        val response = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            setBody(realmBootstrapBody(deviceIdOverride = squatted))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(RelayRefusal.matches(response.bodyAsText(), RelayRefusal.DEVICE_ID_NOT_KEY_DERIVED))
        val ownerId = ownerIdForMasterPublicKey(masterPublicKey)
        assertNull(realmStore.get(ownerId), "no realm may be created")
        assertNull(knownDevices.getPublicKey(ownerId, squatted))
    }

    @Test
    fun duplicateOwner_returns409() = testApplication {
        val realmStore = RealmStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = realmStore
            )
        }

        val first = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            setBody(realmBootstrapBody())
        }
        assertEquals(HttpStatusCode.Created, first.status)
        first.bodyAsText()

        val second = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            setBody(realmBootstrapBody())
        }
        assertEquals(HttpStatusCode.Conflict, second.status)
    }

    @Test
    fun ownerIdMismatch_returns400() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore()
            )
        }

        // Claim a fabricated ownerId that doesn't match the deterministic derivation.
        val response = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            setBody(realmBootstrapBody(ownerId = "spoofed-owner-id"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun badSignature_returns400() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore()
            )
        }

        // Sign with the wrong key — the signature won't verify against masterPublicKey.
        val attackerKey = "attacker-key".encodeToByteArray().copyOf(32)
        val response = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            setBody(realmBootstrapBody(signatureKey = attackerKey))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun missingField_returns400() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore()
            )
        }

        val response = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            setBody("""{"ownerId": "x"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun unauthenticatedByDesign_noAuthHeaderRequired() = testApplication {
        // The realm-bootstrap endpoint is intentionally unauthenticated at the JWT
        // level — the master self-signature in the body is the credential. Even with
        // a strict production config (devMode=false), no Authorization header is needed.
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore()
            )
        }
        val response = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            setBody(realmBootstrapBody())
        }
        assertEquals(HttpStatusCode.Created, response.status)
        response.bodyAsText()
    }

    // ---------------------------------------------------------------------------------
    // The 426 gate, E2e-shaped (core-schema-evolution.md §7.5, as built §7.8).
    //
    // ⚠ DELIBERATE CONTRACT CHANGE vs the pre-E2e gate: the relay keys 426 on
    // `relayProtocolVersion` accepting an inclusive RANGE, and no longer looks at
    // `formatVersion` at all. `staleFormatVersion_returns426` /
    // `missingFormatVersion_returns426` are therefore gone; their inverse assertions live
    // in `differingFormatVersion_isAccepted` below, which is the regression test proving
    // the split.
    // ---------------------------------------------------------------------------------

    @Test
    fun minSupportedProtocolVersion_isAccepted() = testApplication {
        // The lower end of the accepted range is inside it. Identical to the happy path
        // while min == current, and stays meaningful the moment they diverge (which is the
        // point of a range: a relay speaking N and N+1 serves both).
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore()
            )
        }

        val response = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            setBody(realmBootstrapBody(relayProtocolVersion = RELAY_PROTOCOL_MIN_SUPPORTED_VERSION))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        response.bodyAsText()
    }

    @Test
    fun belowMinSupportedProtocolVersion_returns426NamingTheRange() = testApplication {
        val realmStore = RealmStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = realmStore
            )
        }

        val response = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            setBody(realmBootstrapBody(relayProtocolVersion = RELAY_PROTOCOL_MIN_SUPPORTED_VERSION - 1))
        }
        assertEquals(HttpStatusCode.UpgradeRequired, response.status)
        val body = response.bodyAsText()
        assertTrue(
            body.contains("$RELAY_PROTOCOL_MIN_SUPPORTED_VERSION..$RELAY_PROTOCOL_VERSION"),
            "the 426 must name the accepted range so an operator can act on it: $body"
        )
        assertTrue(
            body.contains("client must be updated"),
            "a below-floor client is the side that has to move: $body"
        )
        // Refused *before* any realm is created.
        assertNull(realmStore.get(ownerIdForMasterPublicKey(masterPublicKey)))
    }

    @Test
    fun aboveCurrentProtocolVersion_returns426NamingTheRelayAsTheSideToMove() = testApplication {
        // A client newer than this deployment. The remedy is a relay redeploy, not a client
        // downgrade — naming the right side is why the message is not symmetric.
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore()
            )
        }

        val response = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            setBody(realmBootstrapBody(relayProtocolVersion = RELAY_PROTOCOL_VERSION + 1))
        }
        assertEquals(HttpStatusCode.UpgradeRequired, response.status)
        val body = response.bodyAsText()
        assertTrue(
            body.contains("$RELAY_PROTOCOL_MIN_SUPPORTED_VERSION..$RELAY_PROTOCOL_VERSION"),
            "the 426 must name the accepted range: $body"
        )
        assertTrue(
            body.contains("relay must be redeployed"),
            "an above-current client means the deployment is behind: $body"
        )
    }

    @Test
    fun unparseableProtocolVersion_returns426() = testApplication {
        // Present-but-garbage is a protocol violation, not a pre-E2e client — the
        // "absent means 1" reading applies only to a body that never mentions the field.
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore()
            )
        }

        val response = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            // The harness renders the value verbatim into the JSON string field.
            setBody(realmBootstrapBody().replace("\"relayProtocolVersion\": \"$RELAY_PROTOCOL_VERSION\"", "\"relayProtocolVersion\": \"not-a-number\""))
        }
        assertEquals(HttpStatusCode.UpgradeRequired, response.status)
        assertTrue(
            response.bodyAsText().contains("not an integer"),
            "the 426 should say the field was unparseable rather than name a version"
        )
    }

    @Test
    fun preE2eClient_withoutRelayProtocolVersion_isRefused() = testApplication {
        // ⚠ THE TRANSITION RULE IS RETIRED (§7.11). It used to read an absent field as
        // protocol 1, on the premise that pre-E2e clients "are the entire deployed fleet" —
        // true when E2e shipped, expired at the 2026-07-26 deploy + wipe.
        //
        // It also contradicted the permanent floor rule it was meant to respect (§7.5): an
        // absent field means "unknown, possibly older than client-side fencing", and reading
        // it as exactly the fencing floor admitted the one class that floor exists to reject.
        // §7.8's "expires by construction when the floor rises" is only true above 1.
        val realmStore = RealmStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = realmStore
            )
        }

        val response = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            setBody(realmBootstrapBody(relayProtocolVersion = null))
        }
        assertEquals(HttpStatusCode.UpgradeRequired, response.status)
        assertTrue(
            response.bodyAsText().contains("did not declare a relayProtocolVersion"),
            "the 426 must name the missing declaration, not guess a version",
        )
        assertNull(
            realmStore.get(ownerIdForMasterPublicKey(masterPublicKey)),
            "a refused bootstrap must not create the realm",
        )
    }

    @Test
    fun noVersionFieldsAtAll_isRefused() = testApplication {
        // A client predating even the Phase-0 `formatVersion` gate — the same refusal, for
        // the same reason. Note what is NOT being judged here: its *payload* format is still
        // none of the relay's business (House Rule #1, and `differingFormatVersion_isAccepted`
        // below). It is refused for failing to declare the RELAY's contract version, which is
        // the one thing the relay does have a stake in.
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore()
            )
        }

        val response = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            setBody(realmBootstrapBody(relayProtocolVersion = null, formatVersion = null))
        }
        assertEquals(HttpStatusCode.UpgradeRequired, response.status)
    }

    @Test
    fun differingFormatVersion_isAccepted() = testApplication {
        // ⚠ THE REGRESSION TEST FOR THE SPLIT (§7.5). Payload/schema versions no longer gate:
        // a client whose storage format is wildly different from the relay's build — one
        // behind, or absurdly ahead — is ACCEPTED, because under House Rule #1 the relay
        // stores ciphertext and cannot be affected by the payload schema. The read side is
        // fenced client-side by the realm epoch vector (§7.3), which is per-service and
        // self-scoping; an old client *pushing* old blocks was always harmless (peers upcast,
        // §8.2). Consequence: a TENSHU_STORAGE_FORMAT_VERSION bump owes NO relay redeploy.
        val realmStore = RealmStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = realmStore
            )
        }

        val stale = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            setBody(realmBootstrapBody(formatVersion = TENSHU_STORAGE_FORMAT_VERSION - 1))
        }
        assertEquals(HttpStatusCode.Created, stale.status)
        stale.bodyAsText()
        assertNotNull(realmStore.get(ownerIdForMasterPublicKey(masterPublicKey)))

        // …and one absurdly ahead of the relay's build, to pin that the relay reads the field
        // in no direction at all (409 = the realm above already exists, i.e. it got past the
        // gate to the store).
        val ahead = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            setBody(realmBootstrapBody(formatVersion = TENSHU_STORAGE_FORMAT_VERSION + 9_000))
        }
        assertEquals(HttpStatusCode.Conflict, ahead.status)
    }
}
