package org.tenshu.sync.relay

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.tenshu.core.common.RelayRefusal
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.auth.MasterDelegationVerifier
import org.tenshu.sync.relay.mailbox.PairingOfferRegistry
import org.tenshu.sync.relay.mailbox.PairingOfferStore
import org.tenshu.sync.relay.mailbox.RealmStore
import java.util.concurrent.atomic.AtomicLong
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST` / `GET /sync/pairing/offer-response` — the offer-flow rendezvous
 * (`sync-pairing-inverted-direction.md` §3.4 + §6 P3/P4 + §8 test plan):
 *
 *  - `PairingOfferSignatureTest` coverage: an unsigned / wrong-key / unknown-device
 *    post is rejected 401 (T-P2) and leaves the hash unconsumed.
 *  - `PairingOfferSingleUseTest` coverage: the second post for a hash → 409 (T-P3/T6).
 *  - Registration: an approved post ALSO admits the offerer's device via a master
 *    delegation over its signing key (the offer flow's analogue of
 *    /sync/pairing/response); the registration is applied when the offerer FETCHES the
 *    approval, so its first JWT-authed sync verifies and an unfetched one registers nothing.
 *  - The anonymous offerer poll round-trips exactly what was stored (T-P1 posture).
 *
 * Signatures ride [stubEd25519Signature] via the [testModule]'s StubEd25519Backend
 * (publicKey == signing key under the stub).
 */
class PairingOfferRoutingTest {

    private val ownerId = "offer-owner"
    private val responderDeviceId = "responder-device"
    private val responderDeviceKey = ByteArray(32) { (it + 1).toByte() }
    private val masterKey = ByteArray(32) { (it + 50).toByte() }
    private val publicKeyA = ByteArray(32) { (it + 100).toByte() }
    private val offererSigningKey = ByteArray(32) { (it + 150).toByte() }
    private val offererDeviceId = org.tenshu.sync.common.deviceIdForPublicKey(offererSigningKey)
    private val epoch = 3L
    private val payload = "nonce||aead-ciphertext".encodeToByteArray()
    private val hash = "a".repeat(64)

    private fun knownDevices() = KnownDevicesStore().also {
        it.register(ownerId, responderDeviceId, responderDeviceKey)
    }

    private fun realmStore() = RealmStore().also {
        it.createIfAbsent(
            RealmStore.RealmRecord(ownerId = ownerId, masterPublicKey = masterKey, createdAt = 1L),
        )
    }

    private fun signedBody(
        status: String = "approved",
        secretHash: String = hash,
        payloadBytes: ByteArray? = payload,
        signingKey: ByteArray = responderDeviceKey,
        signatureOverride: ByteArray? = null,
        includeRegistration: Boolean = true,
        delegationSigningKey: ByteArray = masterKey,
        admissionCertChain: List<org.tenshu.sync.common.AdmissionCapabilityCert>? = null,
        offererIdOverride: String? = null,
    ): String {
        val b64 = Base64.getEncoder()
        val message = MasterDelegationVerifier.offerResponseMessage(
            status = status,
            pairingSecretHash = secretHash,
            publicKeyA = publicKeyA,
            payload = payloadBytes,
        )
        val hostSignature = signatureOverride ?: stubEd25519Signature(signingKey, message)
        val fields = mutableMapOf(
            "ownerId" to "\"$ownerId\"",
            "responderDeviceId" to "\"$responderDeviceId\"",
            "pairingSecretHash" to "\"$secretHash\"",
            "status" to "\"$status\"",
            "publicKeyA" to "\"${b64.encodeToString(publicKeyA)}\"",
            "hostSignature" to "\"${b64.encodeToString(hostSignature)}\"",
        )
        if (payloadBytes != null) fields["payload"] = "\"${b64.encodeToString(payloadBytes)}\""
        if (status == "approved" && includeRegistration) {
            val offererId = offererIdOverride ?: offererDeviceId
            val delegation = stubEd25519Signature(
                delegationSigningKey,
                MasterDelegationVerifier.pairingResponseMessage(offererSigningKey, offererId, epoch),
            )
            fields["offererDeviceId"] = "\"$offererId\""
            fields["offererSigningKey"] = "\"${b64.encodeToString(offererSigningKey)}\""
            fields["masterDelegation"] = "\"${b64.encodeToString(delegation)}\""
            fields["epoch"] = "\"$epoch\""
            if (admissionCertChain != null) {
                fields["admissionCertChain"] =
                    "\"${b64.encodeToString(org.tenshu.sync.common.encodeAdmissionCertChain(admissionCertChain))}\""
            }
        }
        return fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }

    @Test
    fun scannedAnnounce_isServedToTheOffererPoll_andDoesNotConsumeTheHash() = testApplication {
        // ⚠⚠ THE property A1 exists for. The announce lets the offerer derive the SAS while the
        // approval prompt is still up — but the decision has not been made yet, so consuming the
        // single-use hash here would kill every handshake at the moment it was scanned.
        application { testModule(knownDevices = knownDevices(), realmStore = realmStore()) }

        val announce = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody(status = "scanned", payloadBytes = null))
        }
        assertEquals(HttpStatusCode.OK, announce.status)

        // The offerer's existing poll sees it, with the responder's ephemeral key and no payload.
        val polled = client.get("/sync/pairing/offer-response?hash=$hash")
        assertEquals(HttpStatusCode.OK, polled.status)
        val announced = Json.parseToJsonElement(polled.bodyAsText()).jsonObject
        assertEquals("scanned", announced["status"]?.jsonPrimitive?.content)
        assertEquals(
            Base64.getEncoder().encodeToString(publicKeyA),
            announced["publicKeyA"]?.jsonPrimitive?.content,
            "the announce must carry the key the SAS is derived from",
        )
        // ⚠ The field is serialised as JSON `null`, which parses to JsonNull — not a missing key —
        // so assert on the CONTENT rather than the element, or this passes for the wrong reason.
        assertNull(
            announced["payload"]?.jsonPrimitive?.contentOrNull,
            "an announce carries no ciphertext (House Rule #1)",
        )

        // …and the terminal decision still lands afterwards, replacing what the poll serves.
        val approve = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody())
        }
        assertEquals(
            HttpStatusCode.OK,
            approve.status,
            "the announce must NOT have consumed the hash — else the approve 409s and pairing dies",
        )
        val after = Json.parseToJsonElement(
            client.get("/sync/pairing/offer-response?hash=$hash").bodyAsText()
        ).jsonObject
        assertEquals("approved", after["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun aSecondAnnounceForTheSameHashIsRefused() = testApplication {
        // ⚠⚠ One announce per hash, and this is a security property rather than tidiness: the
        // offerer PINS the announced key and later refuses a terminal response that does not match
        // it (§2.3). A second announce that overwrote the first would re-point that pin at an
        // attacker's key and turn the lock into its opposite.
        application { testModule(knownDevices = knownDevices(), realmStore = realmStore()) }

        val first = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody(status = "scanned", payloadBytes = null))
        }
        assertEquals(HttpStatusCode.OK, first.status)

        val second = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody(status = "scanned", payloadBytes = null))
        }
        assertEquals(HttpStatusCode.Conflict, second.status)
    }

    @Test
    fun anUnsignedAnnounceIsRefused_soAQrObserverCannotPlantASas() = testApplication {
        // A passive QR observer knows the hash. If announcing were unauthenticated they could put
        // their own key on the offerer's screen and show it a SAS that matches nothing — a
        // confusion primitive. Requiring the same signature as the terminal statuses removes it.
        application { testModule(knownDevices = knownDevices(), realmStore = realmStore()) }

        val forged = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(
                signedBody(
                    status = "scanned",
                    payloadBytes = null,
                    signingKey = ByteArray(32) { 0x7f },
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
    }

    @Test
    fun anAnnounceAfterTheDecisionIsRefused() = testApplication {
        // Announcing after the hash is settled is meaningless, and admitting it would let a late
        // announce displace the terminal blob the offerer is polling for.
        application { testModule(knownDevices = knownDevices(), realmStore = realmStore()) }

        client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody())
        }
        val late = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody(status = "scanned", payloadBytes = null))
        }
        assertEquals(HttpStatusCode.Conflict, late.status)
    }

    @Test
    fun approvedOfferResponse_registersOfferer_andRoundTripsThroughThePoll() = testApplication {
        val devices = knownDevices()
        application { testModule(knownDevices = devices, realmStore = realmStore()) }

        val post = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody())
        }
        assertEquals(HttpStatusCode.OK, post.status)

        // The verified registration is queued with the response, not applied by the post: an
        // offerer that never fetches must leave no registered device behind (see below).
        assertNull(devices.getPublicKey(ownerId, offererDeviceId), "not registered until delivered")

        val get = client.get("/sync/pairing/offer-response?hash=$hash")
        assertEquals(HttpStatusCode.OK, get.status)
        // The offerer's device key is registered BEFORE the payload is served — its first JWT
        // (only possible once it has decrypted that payload) will verify.
        assertContentEquals(
            offererSigningKey,
            devices.getPublicKey(ownerId, offererDeviceId),
            "a fetched approval must register the offerer's device (else it 401s on first sync)",
        )
        assertEquals(setOf(responderDeviceId, offererDeviceId), devices.deviceIds(ownerId))
        // A re-poll is idempotent.
        assertEquals(HttpStatusCode.OK, client.get("/sync/pairing/offer-response?hash=$hash").status)
        assertEquals(setOf(responderDeviceId, offererDeviceId), devices.deviceIds(ownerId))
        val body = Json.parseToJsonElement(get.bodyAsText()).jsonObject
        assertEquals(ownerId, body["ownerId"]?.jsonPrimitive?.content)
        assertEquals("approved", body["status"]?.jsonPrimitive?.content)
        assertEquals(Base64.getEncoder().encodeToString(publicKeyA), body["publicKeyA"]?.jsonPrimitive?.content)
        assertEquals(Base64.getEncoder().encodeToString(payload), body["payload"]?.jsonPrimitive?.content)
    }

    @Test
    fun approvedOfferResponse_forARemovedOfferer_isRefused403_withTheDeviceRemovedToken() = testApplication {
        // F2′ on the offer route. The relay already refused here; what this pins is that the
        // refusal CARRIES [RelayRefusal.DEVICE_REMOVED]. The client keys on the body token, not
        // the status — two different 403s are reachable on this route, and the other one (a
        // revoked approver capability) is about a different device and is not terminal.
        //
        // ⚠ The first cut of the marker tagged the join route only. This is the route a
        // returning device actually uses, and the one `drive-web-parity.md` W10b quotes
        // (`Offer-response failed: 403`) — so an untagged refusal here left W10b's own case
        // undetectable while every test stayed green.
        val devices = knownDevices().also { it.recordRemoval(ownerId, offererDeviceId) }
        application { testModule(knownDevices = devices, realmStore = realmStore()) }

        val post = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody())
        }
        assertEquals(HttpStatusCode.Forbidden, post.status)
        val body = post.bodyAsText()
        assertTrue(
            RelayRefusal.matches(body, RelayRefusal.DEVICE_REMOVED),
            "the offer-route refusal must carry the DEVICE_REMOVED token, else the client cannot " +
                "tell this terminal 403 from a transient one — got: $body",
        )
        assertNull(devices.getPublicKey(ownerId, offererDeviceId), "a refused offerer is not registered")
    }

    /**
     * Workstream B on the offer route: a validly A1-signed approval naming an id that is not the
     * offerer's key's (a squat on another device's id) is refused 400 with the token, before anything
     * is queued — so the fetch has nothing to deliver and nothing is ever registered.
     */
    @Test
    fun approvedOfferResponse_whoseOffererIdIsNotItsKeys_isRefused400_andRegistersNothing() = testApplication {
        val devices = knownDevices()
        application { testModule(knownDevices = devices, realmStore = realmStore()) }
        val squatted = org.tenshu.sync.common.deviceIdForPublicKey(ByteArray(32) { 0x55 })

        val post = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody(offererIdOverride = squatted))
        }
        assertEquals(HttpStatusCode.BadRequest, post.status)
        val body = post.bodyAsText()
        assertTrue(RelayRefusal.matches(body, RelayRefusal.DEVICE_ID_NOT_KEY_DERIVED), body)
        assertEquals(HttpStatusCode.NotFound, client.get("/sync/pairing/offer-response?hash=$hash").status)
        assertNull(devices.getPublicKey(ownerId, squatted))
        assertNull(devices.getPublicKey(ownerId, offererDeviceId))
    }

    /**
     * Shifts the clock [PairingOfferRegistry.fetch] sees — the route stamps `expiresAtMs` from wall
     * time, so moving the reader's `now` forward is the injected clock, not a sleep.
     */
    private class ClockShiftedOfferStore(
        private val inner: PairingOfferRegistry = PairingOfferStore(),
    ) : PairingOfferRegistry by inner {
        val offsetMs = AtomicLong(0)
        override fun fetch(pairingSecretHash: String, nowMs: Long): PairingOfferRegistry.Fetch =
            inner.fetch(pairingSecretHash, nowMs + offsetMs.get())
    }

    @Test
    fun anApprovalThatExpiresUnfetched_registersNothing_andAddsNobodyToTheGcQuorum() = testApplication {
        // Pins registration on FETCH, not on the responder's POST. Registering on the POST would let
        // an offerer that never fetched within the TTL (it gave up, crashed, or its QR was approved
        // after it left) leave a registered device whose key was never delivered: a permanent member
        // of `deviceIds`, which never acks a tombstone, so the realm's tombstone-GC quorum could never
        // be met again. The join flow carries the same rule.
        val devices = knownDevices()
        val offers = ClockShiftedOfferStore()
        application { testModule(knownDevices = devices, realmStore = realmStore(), pairingOfferStore = offers) }

        val post = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody())
        }
        assertEquals(HttpStatusCode.OK, post.status)

        offers.offsetMs.set(RelayConfig.PAIRING_OFFER_RESPONSE_TTL_MS)
        assertEquals(HttpStatusCode.NotFound, client.get("/sync/pairing/offer-response?hash=$hash").status, "expired")
        assertNull(devices.getPublicKey(ownerId, offererDeviceId), "an undelivered admission registers nothing")
        assertEquals(setOf(responderDeviceId), devices.deviceIds(ownerId), "and adds nobody to the GC quorum")

        // The hash stays consumed (single-use, T-P3): expiry undoes the admission, not the marker.
        assertEquals(
            HttpStatusCode.Conflict,
            client.post("/sync/pairing/offer-response") {
                contentType(ContentType.Application.Json); setBody(signedBody())
            }.status,
        )
        assertNull(devices.getPublicKey(ownerId, offererDeviceId))
    }

    @Test
    fun anOffererRemovedBeforeItFetches_isRefused403AtDelivery_andNotRegistered() = testApplication {
        // The post-time F2′ check is up to one TTL old by the time the offerer polls.
        val devices = knownDevices()
        application { testModule(knownDevices = devices, realmStore = realmStore()) }

        assertEquals(
            HttpStatusCode.OK,
            client.post("/sync/pairing/offer-response") {
                contentType(ContentType.Application.Json); setBody(signedBody())
            }.status,
        )
        devices.recordRemoval(ownerId, offererDeviceId)

        val refused = client.get("/sync/pairing/offer-response?hash=$hash")
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertTrue(RelayRefusal.matches(refused.bodyAsText(), RelayRefusal.DEVICE_REMOVED))
        assertNull(devices.getPublicKey(ownerId, offererDeviceId), "nothing registered, nothing served")
    }

    @Test
    fun accountErasure_dropsAQueuedOfferRegistration_soAFetchCannotReRegisterIntoAnErasedRealm() = testApplication {
        // The queued registration is not in the device registry yet, so `DeviceRegistry.eraseOwner`
        // never sees it; the offer store's own erasure must take it, or a poll inside the TTL after
        // closure would recreate a device in the erased realm.
        val devices = knownDevices()
        val offers = PairingOfferStore()
        application { testModule(knownDevices = devices, realmStore = realmStore(), pairingOfferStore = offers) }

        assertEquals(
            HttpStatusCode.OK,
            client.post("/sync/pairing/offer-response") {
                contentType(ContentType.Application.Json); setBody(signedBody())
            }.status,
        )
        assertEquals(1, offers.eraseOwner(ownerId))
        devices.eraseOwner(ownerId)

        assertEquals(HttpStatusCode.NotFound, client.get("/sync/pairing/offer-response?hash=$hash").status)
        assertNull(devices.getPublicKey(ownerId, offererDeviceId))
        assertTrue(devices.deviceIds(ownerId).isEmpty())
    }

    @Test
    fun deniedOfferResponse_carriesNoPayload_andRegistersNothing() = testApplication {
        val devices = knownDevices()
        application { testModule(knownDevices = devices, realmStore = realmStore()) }

        val post = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody(status = "denied", payloadBytes = null))
        }
        assertEquals(HttpStatusCode.OK, post.status)
        assertNull(devices.getPublicKey(ownerId, offererDeviceId), "a denial admits no device")

        val get = client.get("/sync/pairing/offer-response?hash=$hash")
        val body = Json.parseToJsonElement(get.bodyAsText()).jsonObject
        assertEquals("denied", body["status"]?.jsonPrimitive?.content)
        // kotlinx encodes a null field as JSON null (not absent); either is "no payload".
        assertTrue(body["payload"] == null || body["payload"] is JsonNull, "denial carries no payload")
    }

    @Test
    fun secondPostForTheSameHash_conflicts409() = testApplication {
        application { testModule(knownDevices = knownDevices(), realmStore = realmStore()) }

        assertEquals(
            HttpStatusCode.OK,
            client.post("/sync/pairing/offer-response") {
                contentType(ContentType.Application.Json); setBody(signedBody())
            }.status,
        )
        // Even a valid, freshly-signed second post is refused: the hash is single-use.
        assertEquals(
            HttpStatusCode.Conflict,
            client.post("/sync/pairing/offer-response") {
                contentType(ContentType.Application.Json); setBody(signedBody(status = "denied", payloadBytes = null))
            }.status,
        )
        val body = Json.parseToJsonElement(
            client.get("/sync/pairing/offer-response?hash=$hash").bodyAsText(),
        ).jsonObject
        assertEquals("approved", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun wrongHostSignature_rejected401_andHashStaysUnconsumed() = testApplication {
        application { testModule(knownDevices = knownDevices(), realmStore = realmStore()) }

        val bad = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody(signingKey = ByteArray(32) { 0x7F.toByte() }))
        }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/sync/pairing/offer-response?hash=$hash").status)
        assertEquals(
            HttpStatusCode.OK,
            client.post("/sync/pairing/offer-response") {
                contentType(ContentType.Application.Json); setBody(signedBody())
            }.status,
        )
    }

    @Test
    fun invalidMasterDelegation_rejected401_andDoesNotRegister() = testApplication {
        val devices = knownDevices()
        application { testModule(knownDevices = devices, realmStore = realmStore()) }

        // hostSignature is valid, but the master delegation is signed by the wrong key.
        val response = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody(delegationSigningKey = ByteArray(32) { 0x11.toByte() }))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertNull(devices.getPublicKey(ownerId, offererDeviceId))
        // Hash not consumed by the rejected post.
        assertEquals(
            HttpStatusCode.OK,
            client.post("/sync/pairing/offer-response") {
                contentType(ContentType.Application.Json); setBody(signedBody())
            }.status,
        )
    }

    @Test
    fun approvedWithoutRegistrationFields_rejected400() = testApplication {
        application { testModule(knownDevices = knownDevices(), realmStore = realmStore()) }
        val response = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody(includeRegistration = false))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun unknownResponderDevice_rejected401() = testApplication {
        application { testModule(knownDevices = KnownDevicesStore(), realmStore = realmStore()) }
        val response = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody())
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun payloadPresence_mustMatchStatus() = testApplication {
        application { testModule(knownDevices = knownDevices(), realmStore = realmStore()) }
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/sync/pairing/offer-response") {
                contentType(ContentType.Application.Json); setBody(signedBody(status = "approved", payloadBytes = null))
            }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/sync/pairing/offer-response") {
                contentType(ContentType.Application.Json); setBody(signedBody(status = "denied", payloadBytes = payload))
            }.status,
        )
    }

    @Test
    fun offererPoll_beforeAnyResponse_returns404() = testApplication {
        application { testModule(knownDevices = knownDevices(), realmStore = realmStore()) }
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/sync/pairing/offer-response?hash=${"b".repeat(64)}").status,
        )
        assertEquals(HttpStatusCode.BadRequest, client.get("/sync/pairing/offer-response").status)
    }

    // ---- S1 on the offer flow: a chain through a removed admitter ----

    private val admitterId = "device-w"
    private val admitterA33 = ByteArray(32) { (it + 70).toByte() }

    /** W's depth-1 cert, minted by the owner (stub: A1's public key == its signing key). */
    private fun admitterChain() = listOf(
        org.tenshu.sync.common.AdmissionCapabilityCert(
            ownerId, admitterA33, admitterId, 0L,
            stubEd25519Signature(masterKey, org.tenshu.sync.common.AdmissionCapabilityCert.message(ownerId, admitterA33, admitterId, 0L)),
        ),
    )

    @Test
    fun anApprovalThroughARemovedAdmitter_isRefused403_withoutAnyRevocation() = testApplication {
        val known = knownDevices().also { it.recordRemoval(ownerId, admitterId) }
        application { testModule(knownDevices = known, realmStore = realmStore()) }

        val approve = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody(delegationSigningKey = admitterA33, admissionCertChain = admitterChain()))
        }
        assertEquals(HttpStatusCode.Forbidden, approve.status, "S1 must apply to the offer flow too")
        assertNull(known.getPublicKey(ownerId, offererDeviceId))
    }

    /**
     * ⚠⚠ The delivery half: the approval verified while W was still a member, then W was removed
     * before the offerer fetched. The queued registration must be re-checked, or a removed admitter's
     * last delegation registers its joiner anyway.
     */
    @Test
    fun anAdmitterRemovedBeforeTheOffererFetches_registersNothing() = testApplication {
        val known = knownDevices()
        application { testModule(knownDevices = known, realmStore = realmStore()) }

        val approve = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody(delegationSigningKey = admitterA33, admissionCertChain = admitterChain()))
        }
        assertEquals(HttpStatusCode.OK, approve.status, "precondition: W is still a member at post time")

        known.recordRemoval(ownerId, admitterId)

        val fetch = client.get("/sync/pairing/offer-response?hash=$hash")
        assertEquals(HttpStatusCode.Forbidden, fetch.status)
        assertNull(known.getPublicKey(ownerId, offererDeviceId), "the queued registration must not land")
    }

    /**
     * ⚠⚠ The delivery half for a KEY revocation: W's A33 key is revoked after the approval verified
     * but before the offerer fetches, and W is never removed (a Q3 subtree member, or a removal whose
     * tombstone has not been pushed yet). The removal ledger alone would let it through.
     */
    @Test
    fun anAdmitterKeyRevokedBeforeTheOffererFetches_registersNothing() = testApplication {
        val known = knownDevices()
        val revocations = org.tenshu.sync.relay.mailbox.AdmissionRevocationStore()
        application { testModule(knownDevices = known, realmStore = realmStore(), admissionRevocationStore = revocations) }

        val approve = client.post("/sync/pairing/offer-response") {
            contentType(ContentType.Application.Json)
            setBody(signedBody(delegationSigningKey = admitterA33, admissionCertChain = admitterChain()))
        }
        assertEquals(HttpStatusCode.OK, approve.status, "precondition: W is unrevoked at post time")

        val message = org.tenshu.sync.common.AdmissionRevocation.message(
            ownerId, admitterId, epoch = 1L, revokedAt = 1_000L, deviceA33PublicKey = admitterA33,
        )
        revocations.appendRevocation(
            org.tenshu.sync.relay.mailbox.AdmissionRevocationRegistry.RevocationRecord(
                ownerId = ownerId,
                deviceId = admitterId,
                epoch = 1L,
                revokedAt = 1_000L,
                signature = stubEd25519Signature(masterKey, message),
                deviceA33PublicKey = admitterA33,
            ),
        )

        val fetch = client.get("/sync/pairing/offer-response?hash=$hash")
        assertEquals(HttpStatusCode.Forbidden, fetch.status)
        assertNull(known.getPublicKey(ownerId, offererDeviceId), "the queued registration must not land")
    }
}
