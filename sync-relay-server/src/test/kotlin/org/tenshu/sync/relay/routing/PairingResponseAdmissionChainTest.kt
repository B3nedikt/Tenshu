package org.tenshu.sync.relay.routing

import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.sync.common.AdmissionCapabilityCert
import org.tenshu.sync.common.encodeAdmissionCertChain
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.auth.MasterDelegationVerifier
import org.tenshu.sync.relay.mailbox.AdmissionRevocationStore
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P2 coverage of the chain-verified `POST /sync/pairing/response` path
 * (`delegated-device-admission-authority.md` §4.3/§4.4): when the body carries
 * `admissionCertChain`, the delegation is signed by the *admitting device's
 * A33 key* and the relay walks the presented chain to the realm's stored
 * master key + checks the admission revocation list — instead of requiring an
 * A1 signature. The A1 path (no chain) is untouched and stays covered by
 * [PairingResponseMasterDelegationTest].
 *
 * Runs on the stub Ed25519 backend (`publicKey == privateKey`), like the rest
 * of the relay routing tests — what's exercised is the verification wiring.
 */
class PairingResponseAdmissionChainTest {

    private val masterKey = "master-recovery-key".encodeToByteArray().copyOf(32)
    private val ownerId = "owner-delegated"
    private val admitterA33 = ByteArray(32) { (it + 50).toByte() }   // device X's A33
    private val parentA33 = ByteArray(32) { (it + 90).toByte() }     // device W's A33 (X's admitter)
    private val wrongKey = ByteArray(32) { (it + 200).toByte() }
    private val newDevicePubKey = ByteArray(32) { (0xB0 + it).toByte() }
    private val newDeviceId = org.tenshu.sync.common.deviceIdForPublicKey(newDevicePubKey)
    private val pairingSecretHash = "e".repeat(64) // F7: the hash, never the secret
    private val epoch = 0L

    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    /** The posting device — the approver, `chain[0]` of both chains below (§7j.3 rule 2). */
    private val approverDeviceId = "device-x"

    /** A relay that knows the approver, as it does every current member that can post at all. */
    private fun approverRegistered() = KnownDevicesStore().apply {
        register(ownerId, approverDeviceId, ByteArray(32) { 7 }, admittedAt = null)
    }

    private fun seedRealm(realmStore: RealmStore) {
        realmStore.createIfAbsent(
            RealmStore.RealmRecord(ownerId = ownerId, masterPublicKey = masterKey, createdAt = 0L)
        )
    }

    private fun cert(deviceId: String, subjectKey: ByteArray, signer: ByteArray): AdmissionCapabilityCert {
        val message = AdmissionCapabilityCert.message(ownerId, subjectKey, deviceId, mintedAtEpoch = 0L)
        return AdmissionCapabilityCert(ownerId, subjectKey, deviceId, 0L, stubEd25519Signature(signer, message))
    }

    /** X's cert minted directly by the owner (A1) — the depth-1 chain. */
    private fun depth1Chain() = listOf(cert("device-x", admitterA33, signer = masterKey))

    /** Owner admits W, W admits X — X's depth-2 chain, leaf → root. */
    private fun depth2Chain() = listOf(
        cert("device-x", admitterA33, signer = parentA33),
        cert("device-w", parentA33, signer = masterKey),
    )

    private fun responseBody(
        chain: List<AdmissionCapabilityCert>,
        delegationKey: ByteArray = admitterA33,
        chainB64: String? = null,
    ): String {
        val message = MasterDelegationVerifier.pairingResponseMessage(
            newDevicePublicKey = newDevicePubKey,
            newDeviceId = newDeviceId,
            epoch = epoch
        )
        val delegation = stubEd25519Signature(delegationKey, message)
        return """
            {
                "pairingSecretHash": "$pairingSecretHash",
                "encryptedMasterKey": "${b64(byteArrayOf(5, 6, 7))}",
                "newDevicePublicKey": "${b64(newDevicePubKey)}",
                "newDeviceId": "$newDeviceId",
                "masterDelegation": "${b64(delegation)}",
                "epoch": "$epoch",
                "admissionCertChain": "${chainB64 ?: b64(encodeAdmissionCertChain(chain))}"
            }
        """.trimIndent()
    }

    private fun revokeBody(deviceId: String, key: ByteArray, signer: ByteArray = masterKey): String {
        val message = org.tenshu.sync.common.AdmissionRevocation.message(
            ownerId, deviceId, epoch = 5L, revokedAt = 1_000L, deviceA33PublicKey = key,
        )
        return """
            {
                "ownerId": "$ownerId",
                "deviceId": "$deviceId",
                "epoch": 5,
                "revokedAt": 1000,
                "signature": "${b64(stubEd25519Signature(signer, message))}",
                "deviceA33PublicKey": "${b64(key)}"
            }
        """.trimIndent()
    }

    private fun runCase(
        body: String,
        expectedStatus: HttpStatusCode,
        expectRegistered: Boolean,
        preRevoke: String? = null,
        removedDeviceIds: Set<String> = emptySet(),
        posterDeviceId: String? = approverDeviceId,
        registerApprover: Boolean = true,
    ) = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        val knownDevices = (if (registerApprover) approverRegistered() else KnownDevicesStore())
            .apply { removedDeviceIds.forEach { recordRemoval(ownerId, it) } }
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                admissionRevocationStore = AdmissionRevocationStore(),
            )
        }

        if (preRevoke != null) {
            val revoke = client.post("/sync/admission/revoke") {
                contentType(ContentType.Application.Json)
                setBody(preRevoke)
            }
            assertEquals(HttpStatusCode.NoContent, revoke.status)
        }

        val response = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            posterDeviceId?.let { header("X-Dev-Device", it) }
            setBody(body)
        }
        assertEquals(expectedStatus, response.status)
        response.bodyAsBytes()

        // The relay registers the joiner when it FETCHES the response (PairingResponseInbox), so
        // the joiner's peek comes first; a refused post queued nothing, and its peek is a 404.
        val peek = client.post("/sync/pairing/response/peek") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingSecretHash":"$pairingSecretHash"}""")
        }
        assertEquals(if (expectRegistered) HttpStatusCode.OK else HttpStatusCode.NotFound, peek.status)
        peek.bodyAsBytes()

        val registered = knownDevices.getPublicKey(ownerId, newDeviceId)
        if (expectRegistered) {
            assertNotNull(registered)
            assertTrue(registered.contentEquals(newDevicePubKey))
        } else {
            assertNull(registered)
        }
    }

    @Test
    fun depth1Chain_admitsAndRegisters() =
        runCase(responseBody(depth1Chain()), HttpStatusCode.OK, expectRegistered = true)

    @Test
    fun depth2Chain_admitsAndRegisters() =
        runCase(responseBody(depth2Chain()), HttpStatusCode.OK, expectRegistered = true)

    @Test
    fun chainNotRootedAtMaster_returns401_andDoesNotRegister() =
        runCase(
            responseBody(listOf(cert("device-x", admitterA33, signer = wrongKey))),
            HttpStatusCode.Unauthorized,
            expectRegistered = false,
        )

    @Test
    fun delegationNotSignedByChainLeaf_returns401_andDoesNotRegister() =
        // Valid chain, but the delegation is signed by some other key — only the
        // presenting admitter's A33 (chain[0]'s subject) may sign it.
        runCase(
            responseBody(depth1Chain(), delegationKey = wrongKey),
            HttpStatusCode.Unauthorized,
            expectRegistered = false,
        )

    @Test
    fun revokedAdmitter_returns403_andDoesNotRegister() =
        runCase(
            responseBody(depth1Chain()),
            HttpStatusCode.Forbidden,
            expectRegistered = false,
            preRevoke = revokeBody("device-x", admitterA33),
        )

    /**
     * ⚠⚠ §7j.10 (2026-11-11), inverting `revokedIntermediate_returns403_forTheWholeSubtreeChain`:
     * W (X's admitter) is key-revoked AND removed — what an A1 holder's removal of W does. X is a
     * kept, current member and approves: admitted. A device is a member or removed; the per-link
     * revocation check made X a member that could not admit.
     */
    @Test
    fun aRevokedAndRemovedAncestor_isAdmitted_whenTheApproversOwnKeyIsValid() =
        runCase(
            responseBody(depth2Chain()),
            HttpStatusCode.OK,
            expectRegistered = true,
            preRevoke = revokeBody("device-w", parentA33),
            removedDeviceIds = setOf("device-w"),
        )

    /** §7j.10 keeps the approver's OWN key revocable on a deep chain too. */
    @Test
    fun aRevokedApproverOnADeepChain_returns403_andDoesNotRegister() =
        runCase(
            responseBody(depth2Chain()),
            HttpStatusCode.Forbidden,
            expectRegistered = false,
            preRevoke = revokeBody("device-x", admitterA33),
        )

    @Test
    fun undecodableChain_returns400() =
        runCase(
            responseBody(depth1Chain(), chainB64 = b64(byteArrayOf(1, 2, 3))),
            HttpStatusCode.BadRequest,
            expectRegistered = false,
        )

    /**
     * ⚠⚠ §7j (2026-11-11), inverting S1: W (X's admitter) was REMOVED, X is a current member and
     * signs the approval. Admitted. S1 refused this, and on a realm with no A1 holder that locked
     * admission for good — every device W had admitted was refused for ever.
     */
    @Test
    fun aChainThroughARemovedAncestor_isAdmitted_whenTheApproverIsCurrent() =
        runCase(
            responseBody(depth2Chain()),
            HttpStatusCode.OK,
            expectRegistered = true,
            removedDeviceIds = setOf("device-w"),
        )

    /** §7j rule 2: the APPROVER (chain[0], X) removed — refused, with the typed token, whatever its keys. */
    @Test
    fun aRemovedApprover_returns403AdmissionRevoked_withoutAnyRevocation() = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        val knownDevices = approverRegistered().apply { recordRemoval(ownerId, "device-x") }
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                admissionRevocationStore = AdmissionRevocationStore(),
            )
        }
        val response = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            header("X-Dev-Device", approverDeviceId)
            setBody(responseBody(depth2Chain()))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(
            response.bodyAsText().startsWith(org.tenshu.core.common.RelayRefusal.ADMISSION_REVOKED),
            "the client keys its copy on the token",
        )
        assertNull(knownDevices.getPublicKey(ownerId, newDeviceId))
    }

    /** §7j rule 1 (F2′, unchanged): a removed NEW device is refused even by a current approver. */
    @Test
    fun aRemovedNewDevice_returns403DeviceRemoved_evenFromACurrentApprover() = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        val knownDevices = approverRegistered().apply { recordRemoval(ownerId, newDeviceId) }
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                admissionRevocationStore = AdmissionRevocationStore(),
            )
        }
        val response = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            header("X-Dev-Device", approverDeviceId)
            setBody(responseBody(depth1Chain()))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().startsWith(org.tenshu.core.common.RelayRefusal.DEVICE_REMOVED))
        assertNull(knownDevices.getPublicKey(ownerId, newDeviceId))
    }

    // ---- §7j.3 rule 2: the approver is bound to the poster and must be registered ----

    /**
     * ⚠⚠ The attack the binding closes: a cert chain naming approver X, posted under ANOTHER device's
     * credentials. Without the binding a removed, never-revoked D could mint `[X ← D ← A1]` for a
     * fabricated X and post it under any realm JWT.
     */
    @Test
    fun aChainWhoseApproverIsNotThePoster_isRefused_andRegistersNothing() =
        runCase(
            responseBody(depth2Chain()),
            HttpStatusCode.Unauthorized,
            expectRegistered = false,
            posterDeviceId = "some-other-member",
        )

    /** A forged leaf: `chain[0]` names a device the relay has never registered — refused. */
    @Test
    fun aChainWhoseApproverIsNotRegistered_isRefused403() =
        runCase(
            responseBody(depth2Chain()),
            HttpStatusCode.Forbidden,
            expectRegistered = false,
            registerApprover = false,
        )

    /** No authenticated device at all (dev header without a device) — fails closed for a chain. */
    @Test
    fun aChainWithNoAuthenticatedPoster_isRefused() =
        runCase(
            responseBody(depth1Chain()),
            HttpStatusCode.Unauthorized,
            expectRegistered = false,
            posterDeviceId = null,
        )

    /** The poster's registration is revoked between post and delivery — nothing lands. */
    @Test
    fun aPosterUnregisteredBeforeTheJoinerFetches_registersNothing() = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        val knownDevices = approverRegistered()
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                admissionRevocationStore = AdmissionRevocationStore(),
            )
        }
        val post = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            header("X-Dev-Device", approverDeviceId)
            setBody(responseBody(depth2Chain()))
        }
        assertEquals(HttpStatusCode.OK, post.status)
        post.bodyAsBytes()

        knownDevices.revoke(ownerId, approverDeviceId)

        val peek = client.post("/sync/pairing/response/peek") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingSecretHash":"$pairingSecretHash"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, peek.status)
        peek.bodyAsBytes()
        assertNull(knownDevices.getPublicKey(ownerId, newDeviceId))
    }

    /** B1: the joiner's key must be an Ed25519 key — a 31-byte one is refused before any verification. */
    @Test
    fun aJoinerKeyThatIsNot32Bytes_returns400() =
        runCase(
            responseBody(depth1Chain()).replace(b64(newDevicePubKey), b64(newDevicePubKey.copyOf(31))),
            HttpStatusCode.BadRequest,
            expectRegistered = false,
        )

    /**
     * ⚠⚠ §7j at delivery: the response was verified, then W (an ANCESTOR) is removed before the
     * joiner peeks. The approver X is still current, so the queued registration still lands —
     * refusing it here was S1's lock-out at the second check site.
     */
    @Test
    fun anAncestorRemovedBeforeTheJoinerFetches_stillDelivers() =
        deliveryCase(removeAfterPost = "device-w", expectDelivered = true)

    /** §7j at delivery: the APPROVER X is removed before the joiner peeks — nothing lands. */
    @Test
    fun anApproverRemovedBeforeTheJoinerFetches_registersNothing() =
        deliveryCase(removeAfterPost = "device-x", expectDelivered = false)

    private fun deliveryCase(removeAfterPost: String, expectDelivered: Boolean) = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        val knownDevices = approverRegistered()
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                admissionRevocationStore = AdmissionRevocationStore(),
            )
        }
        val post = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            header("X-Dev-Device", approverDeviceId)
            setBody(responseBody(depth2Chain()))
        }
        assertEquals(HttpStatusCode.OK, post.status, "precondition: nobody is removed at post time")
        post.bodyAsBytes()

        knownDevices.recordRemoval(ownerId, removeAfterPost)

        val peek = client.post("/sync/pairing/response/peek") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingSecretHash":"$pairingSecretHash"}""")
        }
        if (expectDelivered) {
            assertEquals(HttpStatusCode.OK, peek.status)
            peek.bodyAsBytes()
            assertNotNull(knownDevices.getPublicKey(ownerId, newDeviceId), "the queued registration lands")
        } else {
            assertEquals(HttpStatusCode.Forbidden, peek.status)
            peek.bodyAsBytes()
            assertNull(knownDevices.getPublicKey(ownerId, newDeviceId), "the queued registration must not land")
        }
    }

    /**
     * ⚠⚠ At delivery, the revocation half: the APPROVER X's own A33 key is revoked (key-scoped, as
     * the remover's strip does at once) before the joiner peeks, while X's removal tombstone has not
     * landed. The delivery re-check must re-run the chain against the revocation list, not only the
     * removal ledger.
     */
    @Test
    fun anApproverRevokedBeforeTheJoinerFetches_registersNothing_evenWhileNotYetRemoved() =
        revokedBeforeFetchCase(revokeBody("device-x", admitterA33), expectDelivered = false)

    /**
     * ⚠⚠ §7j.10 at delivery, inverting `anAdmitterRevokedBeforeTheJoinerFetches_registersNothing_…`:
     * the ANCESTOR W's key is revoked before the joiner peeks. X is still a member with a valid key,
     * so the queued registration lands.
     */
    @Test
    fun anAncestorRevokedBeforeTheJoinerFetches_stillDelivers() =
        revokedBeforeFetchCase(revokeBody("device-w", parentA33), expectDelivered = true)

    private fun revokedBeforeFetchCase(revocation: String, expectDelivered: Boolean) = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        val knownDevices = approverRegistered()
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                admissionRevocationStore = AdmissionRevocationStore(),
            )
        }
        val post = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            header("X-Dev-Device", approverDeviceId)
            setBody(responseBody(depth2Chain()))
        }
        assertEquals(HttpStatusCode.OK, post.status, "precondition: nothing is revoked at post time")
        post.bodyAsBytes()

        val revoke = client.post("/sync/admission/revoke") {
            contentType(ContentType.Application.Json)
            setBody(revocation)
        }
        assertEquals(HttpStatusCode.NoContent, revoke.status)

        val peek = client.post("/sync/pairing/response/peek") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingSecretHash":"$pairingSecretHash"}""")
        }
        if (expectDelivered) {
            assertEquals(HttpStatusCode.OK, peek.status)
            peek.bodyAsBytes()
            assertNotNull(knownDevices.getPublicKey(ownerId, newDeviceId), "the queued registration lands")
        } else {
            assertEquals(HttpStatusCode.Forbidden, peek.status)
            peek.bodyAsBytes()
            assertNull(knownDevices.getPublicKey(ownerId, newDeviceId), "the queued registration must not land")
        }
    }

    private val reKeyA33 = ByteArray(32) { (it + 130).toByte() }

    /** `[X(K'), X(K_X)]` — X re-keying itself under its own A33 key K_X. */
    private fun selfReKeyChain() = listOf(cert("device-x", reKeyA33, signer = admitterA33)) + depth1Chain()

    /**
     * ⚠⚠ §7j.10 fix round, the attack: X's own key is revoked (the remover strips it at once; X's
     * tombstone has not been pushed, so X is still registered). X mints `X(K')` under the revoked
     * key and posts `[X(K'), X(K_X)]` under its own JWT. Refused — and nothing registers.
     */
    @Test
    fun aSelfReKeyThroughTheApproversOwnRevokedKey_returns403_andDoesNotRegister() =
        runCase(
            responseBody(selfReKeyChain(), delegationKey = reKeyA33),
            HttpStatusCode.Forbidden,
            expectRegistered = false,
            preRevoke = revokeBody("device-x", admitterA33),
        )

    /**
     * §7j.10 fix round: a chain naming a device twice is refused even with no revocation at all — as
     * 403 `admission-revoked`, so the approver sees §7j.5's typed copy rather than a 401's generic one.
     */
    @Test
    fun aChainThatNamesADeviceTwice_returns403AdmissionRevoked_andDoesNotRegister() = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        val knownDevices = approverRegistered()
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                admissionRevocationStore = AdmissionRevocationStore(),
            )
        }
        val response = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            header("X-Dev-Device", approverDeviceId)
            setBody(responseBody(selfReKeyChain(), delegationKey = reKeyA33))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains(org.tenshu.core.common.RelayRefusal.ADMISSION_REVOKED))
        assertNull(knownDevices.getPublicKey(ownerId, newDeviceId))
    }

    /**
     * ⚠⚠ §7j.10.2, the P4 re-key in the removal window: X's key was revoked (X removed, tombstone
     * not landed, X still registered), and an A1 holder that has not applied X's tombstone minted a
     * depth-1 `[X(K')]` for a freshly announced K'. Refused — the approver's id is revoked.
     */
    @Test
    fun aP4ReMintForARevokedApprover_returns403_andDoesNotRegister() =
        runCase(
            responseBody(listOf(cert("device-x", reKeyA33, signer = masterKey)), delegationKey = reKeyA33),
            HttpStatusCode.Forbidden,
            expectRegistered = false,
            preRevoke = revokeBody("device-x", admitterA33),
        )

    /**
     * §7j.10 fix round at delivery. A self-re-key chain is refused at post, so it never queues
     * through the route; this pins the delivery re-check directly, as [queuedChainStillAdmits] runs
     * it, for a queued registration carrying one (with and without the approver's revocation).
     */
    @Test
    fun aQueuedSelfReKeyChain_isNotDelivered() {
        val realmStore = RealmStore().apply { seedRealm(this) }
        val revocations = AdmissionRevocationStore()
        val known = approverRegistered()
        val registration = org.tenshu.sync.relay.PairingResponseInbox.PendingRegistration(
            ownerId = ownerId,
            deviceId = newDeviceId,
            publicKey = newDevicePubKey,
            admittedAt = null,
            admissionCertChain = encodeAdmissionCertChain(selfReKeyChain()),
        )
        fun delivers() = queuedChainStillAdmits(
            registration, realmStore, revocations, org.tenshu.sync.relay.auth.StubEd25519Backend(),
            isRegisteredDevice = { known.getPublicKey(ownerId, it) != null },
            isRemovedDevice = { known.wasRemoved(ownerId, it) },
        )
        // Control (before any revocation): the honest chain of the same approver delivers.
        val honest = org.tenshu.sync.relay.PairingResponseInbox.PendingRegistration(
            ownerId = ownerId, deviceId = newDeviceId, publicKey = newDevicePubKey, admittedAt = null,
            admissionCertChain = encodeAdmissionCertChain(depth2Chain()),
        )
        assertEquals(
            true,
            queuedChainStillAdmits(
                honest, realmStore, revocations, org.tenshu.sync.relay.auth.StubEd25519Backend(),
                isRegisteredDevice = { known.getPublicKey(ownerId, it) != null },
                isRemovedDevice = { known.wasRemoved(ownerId, it) },
            ),
            "a chain whose approver's key is unrevoked still delivers (depth-2, honest)",
        )
        assertEquals(false, delivers(), "a repeated id is never delivered")
        val message = org.tenshu.sync.common.AdmissionRevocation.message(
            ownerId, "device-x", epoch = 5L, revokedAt = 1_000L, deviceA33PublicKey = admitterA33,
        )
        revocations.appendRevocation(
            org.tenshu.sync.relay.mailbox.AdmissionRevocationRegistry.RevocationRecord(
                ownerId = ownerId, deviceId = "device-x", epoch = 5L, revokedAt = 1_000L,
                signature = stubEd25519Signature(masterKey, message), deviceA33PublicKey = admitterA33,
            ),
        )
        assertEquals(false, delivers(), "nor through the approver's own revoked key")
    }
}
