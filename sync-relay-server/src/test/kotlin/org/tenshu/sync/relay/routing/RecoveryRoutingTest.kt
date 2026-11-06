package org.tenshu.sync.relay.routing

import org.tenshu.sync.relay.testModule

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.auth.MasterDelegationVerifier
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.mailbox.RecoveryChallengeStore
import org.tenshu.sync.relay.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecoveryRoutingTest {

    private val masterPrivateKey = "master-recovery-key".encodeToByteArray().copyOf(32)
    private val masterPublicKey = masterPrivateKey  // stub: pub == priv

    private val newDevicePrivateKey = "new-device-private".encodeToByteArray().copyOf(32)
    private val newDevicePublicKey = newDevicePrivateKey
    private val newDeviceId = org.tenshu.sync.common.deviceIdForPublicKey(newDevicePublicKey)

    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)
    private fun decodeB64(s: String) = java.util.Base64.getDecoder().decode(s)

    private fun seedRealm(realmStore: RealmStore): String {
        val ownerId = ownerIdForMasterPublicKey(masterPublicKey)
        realmStore.createIfAbsent(
            RealmStore.RealmRecord(
                ownerId = ownerId,
                masterPublicKey = masterPublicKey,
                createdAt = 0L
            )
        )
        return ownerId
    }

    private fun claimBody(
        claimedMasterPublicKey: ByteArray,
        challenge: ByteArray,
        signatureKey: ByteArray = masterPrivateKey,
        devicePublicKey: ByteArray = newDevicePublicKey,
        deviceId: String = newDeviceId
    ): String {
        val message = MasterDelegationVerifier.recoveryClaimMessage(
            newDevicePublicKey = devicePublicKey,
            newDeviceId = deviceId,
            challenge = challenge
        )
        val signature = stubEd25519Signature(signatureKey, message)
        return """
            {
                "masterPublicKey": "${b64(claimedMasterPublicKey)}",
                "newDevicePublicKey": "${b64(devicePublicKey)}",
                "newDeviceId": "$deviceId",
                "challenge": "${b64(challenge)}",
                "masterSignature": "${b64(signature)}"
            }
        """.trimIndent()
    }

    @Test
    fun happyPath_admitsNewDeviceAndConsumesChallenge() = testApplication {
        val realmStore = RealmStore()
        val challengeStore = RecoveryChallengeStore()
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                challengeStore = challengeStore
            )
        }
        val ownerId = seedRealm(realmStore)

        // Step 1: GET challenge.
        val challengeResponse = client.get("/sync/recovery-challenge")
        assertEquals(HttpStatusCode.OK, challengeResponse.status)
        val challengeJson = challengeResponse.bodyAsText()
        val challengeB64 = Regex("\"challenge\"\\s*:\\s*\"([^\"]+)\"").find(challengeJson)
            ?.groupValues?.get(1)
        assertNotNull(challengeB64, "Response must carry a challenge")
        val challenge = decodeB64(challengeB64)

        // Step 2: POST claim with master signature over the challenge.
        val claimResponse = client.post("/sync/recovery-claim") {
            contentType(ContentType.Application.Json)
            setBody(claimBody(masterPublicKey, challenge))
        }
        assertEquals(HttpStatusCode.OK, claimResponse.status)
        claimResponse.bodyAsText()

        // Side effect: device public key registered for JWT verification.
        val registered = knownDevices.getPublicKey(ownerId, newDeviceId)
        assertNotNull(registered)
        assertTrue(registered.contentEquals(newDevicePublicKey))
    }

    /**
     * ⚠⚠ **Recovery works even for an identity the owner previously removed — deliberately.**
     *
     * The claim is verified against the realm's **master** public key, so reaching this point means
     * A1 is in hand, and `admission-revocation-names-the-capability.md` §5b.9 (*"An owner device
     * cannot be removed"*) makes that unfenceable by design. The removal ledger fences the join and
     * offer flows, where a device presents itself **without** A1.
     *
     * ⚠ Guarding this route would look like hardening and would instead break recovery for an owner
     * whose identity had been retired — with no remedy, since recovery *is* the remedy.
     */
    @Test
    fun recoveryWorks_forAnIdentityThatWasPreviouslyRemoved() = testApplication {
        val realmStore = RealmStore()
        val challengeStore = RecoveryChallengeStore()
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                challengeStore = challengeStore
            )
        }
        val ownerId = seedRealm(realmStore)
        knownDevices.recordRemoval(ownerId, newDeviceId)

        val challengeResponse = client.get("/sync/recovery-challenge")
        val challenge = decodeB64(
            Regex("\"challenge\"\\s*:\\s*\"([^\"]+)\"").find(challengeResponse.bodyAsText())!!
                .groupValues[1]
        )

        val claimResponse = client.post("/sync/recovery-claim") {
            contentType(ContentType.Application.Json)
            setBody(claimBody(masterPublicKey, challenge))
        }

        assertEquals(HttpStatusCode.OK, claimResponse.status, "A1 in hand — see the KDoc")
        assertNotNull(knownDevices.getPublicKey(ownerId, newDeviceId))
    }

    @Test
    fun replay_consumedChallenge_returns401() = testApplication {
        val realmStore = RealmStore()
        val challengeStore = RecoveryChallengeStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = realmStore,
                challengeStore = challengeStore
            )
        }
        val ownerId = seedRealm(realmStore)
        val challenge = challengeStore.issue()!!

        // First claim: succeeds.
        val first = client.post("/sync/recovery-claim") {
            contentType(ContentType.Application.Json)
            setBody(claimBody(masterPublicKey, challenge))
        }
        assertEquals(HttpStatusCode.OK, first.status)
        first.bodyAsText()

        // Second claim with the same challenge: must be rejected. Replay is the canonical
        // attack on signature-based admission; one-shot challenge consumption is the defense.
        val second = client.post("/sync/recovery-claim") {
            contentType(ContentType.Application.Json)
            setBody(claimBody(masterPublicKey, challenge))
        }
        assertEquals(HttpStatusCode.Unauthorized, second.status)
    }

    @Test
    fun unknownChallenge_returns401() = testApplication {
        val realmStore = RealmStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = realmStore,
                challengeStore = RecoveryChallengeStore()
            )
        }
        val ownerId = seedRealm(realmStore)

        // Caller fabricates a challenge that the relay never issued.
        val fakeChallenge = ByteArray(32) { 0xFF.toByte() }
        val response = client.post("/sync/recovery-claim") {
            contentType(ContentType.Application.Json)
            setBody(claimBody(masterPublicKey, fakeChallenge))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun badSignature_returns401() = testApplication {
        val realmStore = RealmStore()
        val challengeStore = RecoveryChallengeStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = realmStore,
                challengeStore = challengeStore
            )
        }
        val ownerId = seedRealm(realmStore)
        val challenge = challengeStore.issue()!!

        // Sign with the wrong key — the signature won't verify against the realm's masterPublicKey.
        val attackerKey = "attacker-master-key".encodeToByteArray().copyOf(32)
        val response = client.post("/sync/recovery-claim") {
            contentType(ContentType.Application.Json)
            setBody(claimBody(masterPublicKey, challenge, signatureKey = attackerKey))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    /**
     * ⚠⚠ F8: the challenge names no realm and is issued to anyone. It used to 404 for an unknown
     * ownerId — an existence oracle for a value every pairing QR carries.
     */
    @Test
    fun challenge_namesNoRealm_andIsIssuedToAnyone() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore(),
                challengeStore = RecoveryChallengeStore()
            )
        }
        val response = client.get("/sync/recovery-challenge")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"challenge\""))
    }

    @Test
    fun challenge_fullStore_returns503_ratherThanGrowing() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore(),
                challengeStore = RecoveryChallengeStore(maxOutstanding = 1)
            )
        }
        assertEquals(HttpStatusCode.OK, client.get("/sync/recovery-challenge").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/sync/recovery-challenge").status)
    }

    /**
     * ⚠⚠ F8: a caller who cannot sign with the master key gets the SAME answer whether or not the
     * realm exists. Before F8 the claim looked the realm up first: 404 for none, 401 for a bad
     * signature — so a fresh challenge plus a garbage signature probed any public ownerId.
     */
    @Test
    fun strangerClaim_getsTheSameAnswer_whetherOrNotTheRealmExists() = testApplication {
        val realmStore = RealmStore()
        val challengeStore = RecoveryChallengeStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = realmStore,
                challengeStore = challengeStore
            )
        }
        seedRealm(realmStore)
        val attackerKey = "attacker-master-key".encodeToByteArray().copyOf(32)
        val neverSeenMasterPub = "nobody-has-this-realm".encodeToByteArray()

        val existing = client.post("/sync/recovery-claim") {
            contentType(ContentType.Application.Json)
            setBody(claimBody(masterPublicKey, challengeStore.issue()!!, signatureKey = attackerKey))
        }
        val absent = client.post("/sync/recovery-claim") {
            contentType(ContentType.Application.Json)
            setBody(claimBody(neverSeenMasterPub, challengeStore.issue()!!, signatureKey = attackerKey))
        }
        assertEquals(HttpStatusCode.Unauthorized, existing.status)
        assertEquals(existing.status, absent.status)
        assertEquals(existing.bodyAsText(), absent.bodyAsText())
    }

    /** A caller who DOES hold the master key may learn its realm is gone: that is the only 404. */
    @Test
    fun unknownRealm_claimWithAValidMasterSignature_returns404() = testApplication {
        val challengeStore = RecoveryChallengeStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore(),
                challengeStore = challengeStore
            )
        }
        val response = client.post("/sync/recovery-claim") {
            contentType(ContentType.Application.Json)
            setBody(claimBody(masterPublicKey, challengeStore.issue()!!))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    /**
     * Workstream B on recovery: a valid A1 claim naming an id that is not the new key's is refused
     * 400 with the token — and BEFORE the challenge is spent, so the honest retry still has it.
     */
    @Test
    fun aClaimWhoseIdIsNotTheKeys_returns400_registersNothing_andLeavesTheChallenge() = testApplication {
        val realmStore = RealmStore()
        val challengeStore = RecoveryChallengeStore()
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                challengeStore = challengeStore
            )
        }
        val ownerId = seedRealm(realmStore)
        val challenge = challengeStore.issue()!!
        val squatted = org.tenshu.sync.common.deviceIdForPublicKey(ByteArray(32) { 0x55 })

        val refused = client.post("/sync/recovery-claim") {
            contentType(ContentType.Application.Json)
            setBody(claimBody(masterPublicKey, challenge, deviceId = squatted))
        }
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        val body = refused.bodyAsText()
        assertTrue(org.tenshu.core.common.RelayRefusal.matches(body, org.tenshu.core.common.RelayRefusal.DEVICE_ID_NOT_KEY_DERIVED), body)
        assertNull(knownDevices.getPublicKey(ownerId, squatted))

        val honest = client.post("/sync/recovery-claim") {
            contentType(ContentType.Application.Json)
            setBody(claimBody(masterPublicKey, challenge))
        }
        assertEquals(HttpStatusCode.OK, honest.status, "the refused claim must not have spent the challenge")
    }

    @Test
    fun missingField_returns400() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = RealmStore(),
                challengeStore = RecoveryChallengeStore()
            )
        }
        val response = client.post("/sync/recovery-claim") {
            contentType(ContentType.Application.Json)
            setBody("""{"masterPublicKey":"eA=="}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
