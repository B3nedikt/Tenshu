package org.tenshu.sync.relay.routing

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.Base64
import org.tenshu.core.common.RELAY_PROTOCOL_VERSION
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.sync.common.MemberDeviceBinding
import org.tenshu.sync.common.MembershipCapability
import org.tenshu.sync.common.MembershipRevocation
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.auth.MasterDelegationVerifier
import org.tenshu.sync.relay.mailbox.GrantStore
import org.tenshu.sync.relay.mailbox.MembershipStore
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GR-2 relay surface (`shared-realm-membership-grant.md` §4): the grant mailbox
 * (drop / single-pickup), member-device admission via the Q30 capability chain,
 * and master-signed revocation with device purge. Mirrors
 * [AdmitDeviceRoutingTest]'s stub-crypto harness (pub == priv;
 * `stubEd25519Signature`).
 */
class MembershipGrantRoutingTest {

    // Realm master (A1) — stub: pub == priv. ownerId is the realmId.
    private val masterKey = "shared-realm-master-key".encodeToByteArray().copyOf(32)
    private val firstDevicePublicKey = "owner-device-pubkey".encodeToByteArray().copyOf(32)
    private val firstDeviceId = org.tenshu.sync.common.deviceIdForPublicKey(firstDevicePublicKey)
    private val realmId = ownerIdForMasterPublicKey(masterKey)

    // Member authority (A26) + the member's per-realm device key — stub: pub == priv.
    private val memberAuthority = "bob-member-authority".encodeToByteArray().copyOf(32)
    private val perRealmDevicePublicKey = "bob-realm-device-pubkey".encodeToByteArray().copyOf(32)
    private val memberDeviceId = org.tenshu.sync.common.deviceIdForPublicKey(perRealmDevicePublicKey)
    private val wrongKey = "attacker-key".encodeToByteArray().copyOf(32)

    private val address = "00112233445566778899aabbccddeeff"

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    // ---- request bodies -----------------------------------------------------

    private fun bootstrapBody(): String {
        val message = MasterDelegationVerifier.realmBootstrapMessage(
            ownerId = realmId,
            masterPublicKey = masterKey,
            firstDevicePublicKey = firstDevicePublicKey,
            firstDeviceId = firstDeviceId,
        )
        val sig = stubEd25519Signature(masterKey, message)
        return """
            {
                "relayProtocolVersion": "$RELAY_PROTOCOL_VERSION",
                "ownerId": "$realmId",
                "masterPublicKey": "${b64(masterKey)}",
                "firstDevicePublicKey": "${b64(firstDevicePublicKey)}",
                "firstDeviceId": "$firstDeviceId",
                "masterSelfSignature": "${b64(sig)}"
            }
        """.trimIndent()
    }

    private fun admitBody(
        capabilitySigner: ByteArray = masterKey,
        bindingSigner: ByteArray = memberAuthority,
        epoch: Long = 0L,
        boundDeviceId: String = memberDeviceId,
    ): String {
        val capSig = stubEd25519Signature(
            capabilitySigner,
            MembershipCapability.message(realmId, memberAuthority, epoch, issuedAt = 1_000L),
        )
        val bindSig = stubEd25519Signature(
            bindingSigner,
            MemberDeviceBinding.message(realmId, boundDeviceId, perRealmDevicePublicKey, boundAt = 2_000L),
        )
        return """
            {
                "realmId": "$realmId",
                "capability": {
                    "memberAuthorityPublicKey": "${b64(memberAuthority)}",
                    "epoch": $epoch,
                    "issuedAt": 1000,
                    "signature": "${b64(capSig)}"
                },
                "deviceBinding": {
                    "deviceId": "$boundDeviceId",
                    "perRealmDevicePublicKey": "${b64(perRealmDevicePublicKey)}",
                    "boundAt": 2000,
                    "signature": "${b64(bindSig)}"
                }
            }
        """.trimIndent()
    }

    private fun revokeBody(signer: ByteArray = masterKey, epoch: Long = 1L): String {
        val sig = stubEd25519Signature(
            signer,
            MembershipRevocation.message(realmId, memberAuthority, epoch, revokedAt = 3_000L),
        )
        return """
            {
                "realmId": "$realmId",
                "memberAuthorityPublicKey": "${b64(memberAuthority)}",
                "epoch": $epoch,
                "revokedAt": 3000,
                "signature": "${b64(sig)}"
            }
        """.trimIndent()
    }

    private suspend fun ApplicationTestBuilder.bootstrapRealm() {
        client.post("/sync/realm") {
            contentType(ContentType.Application.Json); setBody(bootstrapBody())
        }.bodyAsText()
    }

    // ---- grant mailbox ------------------------------------------------------

    @Test
    fun grant_dropThenPickup_deletesOnRead() = testApplication {
        val grantStore = GrantStore()
        application { testModule(grantStore = grantStore) }

        val drop = client.post("/sync/grant/$address") {
            header("X-Dev-Owner", "alice")
            contentType(ContentType.Application.Json)
            setBody("""{"sealedGrant":"${b64("sealed-grant-bytes".encodeToByteArray())}"}""")
        }
        assertEquals(HttpStatusCode.NoContent, drop.status)

        val pickup = client.get("/sync/grant/$address")
        assertEquals(HttpStatusCode.OK, pickup.status)
        assertTrue(pickup.bodyAsText().contains(b64("sealed-grant-bytes".encodeToByteArray())))

        // Single-pickup: the second GET finds nothing.
        assertEquals(HttpStatusCode.NotFound, client.get("/sync/grant/$address").status)
        assertEquals(0, grantStore.recordCount())
    }

    @Test
    fun grant_pickupUnknownAddress_returns404() = testApplication {
        application { testModule() }
        assertEquals(HttpStatusCode.NotFound, client.get("/sync/grant/$address").status)
    }

    @Test
    fun grant_dropWithoutAuth_returns401() = testApplication {
        application {
            testModule(config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080))
        }
        val drop = client.post("/sync/grant/$address") {
            contentType(ContentType.Application.Json)
            setBody("""{"sealedGrant":"${b64("x".encodeToByteArray())}"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, drop.status)
    }

    @Test
    fun grant_invalidAddress_returns400() = testApplication {
        application { testModule() }
        val drop = client.post("/sync/grant/not-hex") {
            header("X-Dev-Owner", "alice")
            contentType(ContentType.Application.Json)
            setBody("""{"sealedGrant":"${b64("x".encodeToByteArray())}"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, drop.status)
    }

    @Test
    fun grant_capExceeded_returns429() = testApplication {
        application {
            testModule(config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080, maxOpenGrantsPerOwner = 1))
        }
        // First address fills the single slot.
        client.post("/sync/grant/$address") {
            header("X-Dev-Owner", "alice")
            contentType(ContentType.Application.Json)
            setBody("""{"sealedGrant":"${b64("a".encodeToByteArray())}"}""")
        }.bodyAsText()
        // A second, different address exceeds the cap.
        val second = client.post("/sync/grant/ffffffffffffffffffffffffffffffff") {
            header("X-Dev-Owner", "alice")
            contentType(ContentType.Application.Json)
            setBody("""{"sealedGrant":"${b64("b".encodeToByteArray())}"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
    }

    @Test
    fun grant_reGrantSameAddress_lastWriteWins() = testApplication {
        application {
            testModule(config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080, maxOpenGrantsPerOwner = 1))
        }
        client.post("/sync/grant/$address") {
            header("X-Dev-Owner", "alice")
            contentType(ContentType.Application.Json)
            setBody("""{"sealedGrant":"${b64("first".encodeToByteArray())}"}""")
        }.bodyAsText()
        // Same address re-grant: allowed even at cap=1, supersedes the first.
        val reGrant = client.post("/sync/grant/$address") {
            header("X-Dev-Owner", "alice")
            contentType(ContentType.Application.Json)
            setBody("""{"sealedGrant":"${b64("second".encodeToByteArray())}"}""")
        }
        assertEquals(HttpStatusCode.NoContent, reGrant.status)
        assertTrue(client.get("/sync/grant/$address").bodyAsText().contains(b64("second".encodeToByteArray())))
    }

    // ---- member admission ---------------------------------------------------

    @Test
    fun admit_validChain_registersTheDevice() = testApplication {
        val knownDevices = KnownDevicesStore()
        application { testModule(knownDevices = knownDevices, realmStore = RealmStore()) }
        bootstrapRealm()

        val response = client.post("/sync/realm-member/admit") {
            contentType(ContentType.Application.Json); setBody(admitBody())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val registered = knownDevices.getPublicKey(realmId, memberDeviceId)
        assertNotNull(registered, "the admitted member device's per-realm key is registered")
        assertTrue(registered.contentEquals(perRealmDevicePublicKey))
    }

    /**
     * Workstream B on the member route: a valid capability + binding (both properly signed) whose
     * realm-local id is not the per-realm key's. Refused 400 with the token; nothing registered.
     */
    @Test
    fun admit_aBindingWhoseIdIsNotItsKeys_returns400_andRegistersNothing() = testApplication {
        val knownDevices = KnownDevicesStore()
        application { testModule(knownDevices = knownDevices, realmStore = RealmStore()) }
        bootstrapRealm()
        val squatted = org.tenshu.sync.common.deviceIdForPublicKey(ByteArray(32) { 0x55 })

        val response = client.post("/sync/realm-member/admit") {
            contentType(ContentType.Application.Json); setBody(admitBody(boundDeviceId = squatted))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(org.tenshu.core.common.RelayRefusal.matches(body, org.tenshu.core.common.RelayRefusal.DEVICE_ID_NOT_KEY_DERIVED), body)
        assertNull(knownDevices.getPublicKey(realmId, squatted))
    }

    @Test
    fun admit_capabilitySignedByWrongKey_returns401() = testApplication {
        val knownDevices = KnownDevicesStore()
        application { testModule(knownDevices = knownDevices, realmStore = RealmStore()) }
        bootstrapRealm()

        val response = client.post("/sync/realm-member/admit") {
            contentType(ContentType.Application.Json); setBody(admitBody(capabilitySigner = wrongKey))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertNull(knownDevices.getPublicKey(realmId, memberDeviceId))
    }

    @Test
    fun admit_bindingSignedByWrongAuthority_returns401() = testApplication {
        val knownDevices = KnownDevicesStore()
        application { testModule(knownDevices = knownDevices, realmStore = RealmStore()) }
        bootstrapRealm()

        val response = client.post("/sync/realm-member/admit") {
            contentType(ContentType.Application.Json); setBody(admitBody(bindingSigner = wrongKey))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertNull(knownDevices.getPublicKey(realmId, memberDeviceId))
    }

    @Test
    fun admit_unknownRealm_returns404() = testApplication {
        application { testModule(realmStore = RealmStore()) }
        // No realm bootstrapped.
        val response = client.post("/sync/realm-member/admit") {
            contentType(ContentType.Application.Json); setBody(admitBody())
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ---- revocation ---------------------------------------------------------

    @Test
    fun revoke_validMasterSignature_purgesAdmittedDeviceAndBlocksReAdmit() = testApplication {
        val knownDevices = KnownDevicesStore()
        val membershipStore = MembershipStore()
        application {
            testModule(knownDevices = knownDevices, realmStore = RealmStore(), membershipStore = membershipStore)
        }
        bootstrapRealm()

        // Admit, then revoke.
        client.post("/sync/realm-member/admit") {
            contentType(ContentType.Application.Json); setBody(admitBody())
        }.bodyAsText()
        assertNotNull(knownDevices.getPublicKey(realmId, memberDeviceId))

        val revoke = client.post("/sync/realm-member/revoke") {
            contentType(ContentType.Application.Json); setBody(revokeBody())
        }
        assertEquals(HttpStatusCode.NoContent, revoke.status)
        // Immediacy: the device registration is purged.
        assertNull(knownDevices.getPublicKey(realmId, memberDeviceId), "revocation purges the device registration")
        assertEquals(1, membershipStore.recordCount())

        // A fresh admit at epoch 0 is now superseded by the epoch-1 revocation.
        val reAdmit = client.post("/sync/realm-member/admit") {
            contentType(ContentType.Application.Json); setBody(admitBody())
        }
        assertEquals(HttpStatusCode.Forbidden, reAdmit.status)
    }

    @Test
    fun revoke_signedByNonMaster_returns401_andDoesNotRevoke() = testApplication {
        val membershipStore = MembershipStore()
        application { testModule(realmStore = RealmStore(), membershipStore = membershipStore) }
        bootstrapRealm()

        val revoke = client.post("/sync/realm-member/revoke") {
            contentType(ContentType.Application.Json); setBody(revokeBody(signer = wrongKey))
        }
        assertEquals(HttpStatusCode.Unauthorized, revoke.status)
        assertEquals(0, membershipStore.recordCount())
    }

    @Test
    fun revoke_unknownRealm_returns404() = testApplication {
        application { testModule(realmStore = RealmStore()) }
        val revoke = client.post("/sync/realm-member/revoke") {
            contentType(ContentType.Application.Json); setBody(revokeBody())
        }
        assertEquals(HttpStatusCode.NotFound, revoke.status)
    }
}
