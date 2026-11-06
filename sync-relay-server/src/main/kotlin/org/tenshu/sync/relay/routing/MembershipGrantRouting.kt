package org.tenshu.sync.relay.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.Base64
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.sync.common.MembershipAdmission
import org.tenshu.sync.common.MembershipCapability
import org.tenshu.sync.common.MembershipChainVerifier
import org.tenshu.sync.common.MembershipRevocation
import org.tenshu.sync.common.MemberDeviceBinding
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.DeviceRegistry
import org.tenshu.sync.relay.auth.DeviceRevocation
import org.tenshu.sync.relay.auth.JwtAuthenticator
import org.tenshu.sync.relay.auth.RelayEntitlementVerifier
import org.tenshu.sync.relay.mailbox.GrantRegistry
import org.tenshu.sync.relay.mailbox.MembershipRegistry
import org.tenshu.sync.relay.mailbox.RealmRegistry

/**
 * Shared-realm membership grant relay surface
 * (`docs/proposals/shared-realm-membership-grant.md` §4, GR-2) — three endpoints
 * across two route groups:
 *
 *  - `POST /sync/grant/{address}`         — grantor drops a sealed grant.     *JWT-required*
 *  - `GET  /sync/grant/{address}`         — grantee picks up + deletes it.    *Anonymous — address is the capability*
 *  - `POST /sync/realm-member/admit`      — admit a member device by chain.   *Unauthenticated — the capability chain gates it*
 *  - `POST /sync/realm-member/revoke`     — revoke a member authority.        *Unauthenticated — the master signature gates it*
 *
 * Grant issuance is authenticated so the mailbox is bounded per grantor
 * ([RelayConfig.maxOpenGrantsPerOwner]); pickup carries no JWT so the grantee
 * stays protocol-anonymous — the **address-as-bearer** is an HKDF of the
 * handshake secret (D3, Q-c), and the blob is sealed to the grantee's A13, so a
 * leaked address yields denial, never disclosure.
 *
 * Admission mirrors `POST /sync/admit-device`: no JWT, the master-signed Q30
 * capability chain ([MembershipChainVerifier]) is the capability. Revocation
 * mirrors it the other way — the realm-master signature over the revocation is
 * the authorisation. House Rule #1: the relay stores routing metadata, public
 * keys, sealed blobs, and master-signed tokens only — never a realm key.
 */
fun Route.configureMembershipGrantRouting(
    config: RelayConfig,
    jwtAuthenticator: JwtAuthenticator,
    grantStore: GrantRegistry,
    membershipStore: MembershipRegistry,
    realmStore: RealmRegistry,
    knownDevices: DeviceRegistry,
    /** F11: member-device removal also forgets the device's tip and cursor in this realm. */
    deviceRevocation: DeviceRevocation,
    ed25519: Ed25519Backend,
    nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    entitlementVerifier: RelayEntitlementVerifier? = null,
) {
    route("/sync/grant/{address}") {
        post {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@post
            // §8 Phase 2 write-gating: a grant drop requires a live, owner-bound entitlement when
            // enforcement is on (opt-in; a no-op otherwise). The anonymous pickup GET and the
            // capability-chain-gated admit/revoke endpoints stay ungated (§9 Q5).
            if (!requireEntitlement(call, ownerId, entitlementVerifier, config.entitlementEnforce)) {
                return@post
            }
            val address = call.parameters["address"]
            if (!isValidGrantAddress(address)) {
                call.respond(HttpStatusCode.BadRequest, "Invalid grant address")
                return@post
            }
            val body = try {
                call.receive<PostGrantRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Malformed JSON body")
                return@post
            }
            val sealed = try {
                decodeGrantCapped(body.sealedGrant)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "Invalid base64 payload")
                return@post
            }
            if (sealed == null) {
                call.respond(HttpStatusCode.PayloadTooLarge, "sealed grant exceeds $MAX_GRANT_BLOB_BYTES bytes")
                return@post
            }
            val now = nowEpochMillis()
            when (
                grantStore.put(
                    address = address!!,
                    ownerId = ownerId,
                    sealedGrant = sealed,
                    expiresAtMs = now + GRANT_TTL_MS,
                    nowMs = now,
                    maxOpenPerOwner = config.maxOpenGrantsPerOwner,
                )
            ) {
                GrantRegistry.PutResult.STORED -> call.respond(HttpStatusCode.NoContent)
                GrantRegistry.PutResult.CAP_EXCEEDED ->
                    call.respond(HttpStatusCode.TooManyRequests, "open-grant cap reached")
            }
        }

        get {
            // Anonymous: knowledge of the address is the capability; the grant is
            // sealed to the grantee's A13, so the relay relays bytes it cannot read.
            val address = call.parameters["address"]
            if (!isValidGrantAddress(address)) {
                call.respond(HttpStatusCode.BadRequest, "Invalid grant address")
                return@get
            }
            when (val pickup = grantStore.pickup(address!!, nowEpochMillis())) {
                is GrantRegistry.Pickup.Found ->
                    call.respond(HttpStatusCode.OK, GetGrantResponse(sealedGrant = b64encode(pickup.sealedGrant)))

                GrantRegistry.Pickup.Empty ->
                    call.respond(HttpStatusCode.NotFound, "No grant available")
            }
        }
    }

    route("/sync/realm-member") {
        // Admit a member's device to a shared realm by verifying the Q30 capability
        // chain against the realm's stored master key + revocation list (D1/D6). No
        // JWT — the chain is the capability, the `/admit-device` posture.
        post("/admit") {
            val body = try {
                call.receive<AdmitMemberRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Malformed JSON body")
                return@post
            }
            val capability = try {
                MembershipCapability(
                    realmId = body.realmId,
                    memberAuthorityPublicKey = decodeB64(body.capability.memberAuthorityPublicKey),
                    epoch = body.capability.epoch,
                    issuedAt = body.capability.issuedAt,
                    signature = decodeB64(body.capability.signature),
                )
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "Invalid base64 in capability")
                return@post
            }
            val binding = try {
                MemberDeviceBinding(
                    realmId = body.realmId,
                    deviceId = body.deviceBinding.deviceId,
                    perRealmDevicePublicKey = decodeB64(body.deviceBinding.perRealmDevicePublicKey),
                    boundAt = body.deviceBinding.boundAt,
                    signature = decodeB64(body.deviceBinding.signature),
                )
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "Invalid base64 in deviceBinding")
                return@post
            }

            // B1: the per-realm device key and the authority key are Ed25519 keys; the bound id is
            // printable — before anything is verified or registered.
            if (!RegistrationInputRules.isEd25519PublicKey(binding.perRealmDevicePublicKey) ||
                !RegistrationInputRules.isEd25519PublicKey(capability.memberAuthorityPublicKey)
            ) {
                return@post call.respond(HttpStatusCode.BadRequest, RegistrationInputRules.KEY_REFUSAL)
            }
            if (!RegistrationInputRules.isWellFormedDeviceId(binding.deviceId)) {
                return@post call.respond(HttpStatusCode.BadRequest, RegistrationInputRules.DEVICE_ID_REFUSAL)
            }
            // Workstream B: the realm-local id is the per-realm key's (a fresh key, a fresh unlinkable id).
            if (!RegistrationInputRules.isKeyDerivedDeviceId(binding.deviceId, binding.perRealmDevicePublicKey)) {
                return@post call.respond(HttpStatusCode.BadRequest, RegistrationInputRules.DEVICE_ID_NOT_KEY_DERIVED_REFUSAL)
            }

            val realm = realmStore.get(body.realmId) ?: run {
                call.respond(HttpStatusCode.NotFound, "Realm not found")
                return@post
            }

            val revocations = membershipStore.revocations(body.realmId).map {
                MembershipRevocation(
                    realmId = it.realmId,
                    memberAuthorityPublicKey = it.memberAuthorityPublicKey,
                    epoch = it.epoch,
                    revokedAt = it.revokedAt,
                    signature = it.signature,
                )
            }

            when (
                MembershipChainVerifier.verifyAdmission(
                    masterPublicKey = realm.masterPublicKey,
                    capability = capability,
                    binding = binding,
                    revocations = revocations,
                    verify = ed25519::verify,
                )
            ) {
                MembershipAdmission.Admit -> {
                    membershipStore.recordAdmission(body.realmId, capability.memberAuthorityPublicKey, binding.deviceId)
                    knownDevices.register(body.realmId, binding.deviceId, binding.perRealmDevicePublicKey)
                    call.respond(HttpStatusCode.OK, "OK")
                }

                MembershipAdmission.RejectBadCapability,
                MembershipAdmission.RejectBadBinding ->
                    call.respond(HttpStatusCode.Unauthorized, "Invalid membership capability chain")

                MembershipAdmission.RejectRevoked ->
                    call.respond(HttpStatusCode.Forbidden, "Membership has been revoked")
            }
        }

        // Revoke a member authority: append a master-signed revocation and purge that
        // authority's already-admitted device registrations (D6 immediacy). No JWT —
        // the realm-master signature over the revocation is the authorisation.
        post("/revoke") {
            val body = try {
                call.receive<RevokeMemberRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Malformed JSON body")
                return@post
            }
            val memberAuthorityPublicKey = try {
                decodeB64(body.memberAuthorityPublicKey)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "Invalid base64 in memberAuthorityPublicKey")
                return@post
            }
            val signature = try {
                decodeB64(body.signature)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "Invalid base64 in signature")
                return@post
            }

            val realm = realmStore.get(body.realmId) ?: run {
                call.respond(HttpStatusCode.NotFound, "Realm not found")
                return@post
            }

            val revocation = MembershipRevocation(
                realmId = body.realmId,
                memberAuthorityPublicKey = memberAuthorityPublicKey,
                epoch = body.epoch,
                revokedAt = body.revokedAt,
                signature = signature,
            )
            if (!ed25519.verify(realm.masterPublicKey, revocation.signedMessage(), signature)) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid revocation signature")
                return@post
            }

            membershipStore.appendRevocation(
                MembershipRegistry.RevocationRecord(
                    realmId = body.realmId,
                    memberAuthorityPublicKey = memberAuthorityPublicKey,
                    epoch = body.epoch,
                    revokedAt = body.revokedAt,
                    signature = signature,
                )
            )
            // Immediacy (D6 step a): purge the per-realm device registrations bound by
            // this authority, then forget the now-stale associations.
            membershipStore.admittedDevices(body.realmId, memberAuthorityPublicKey).forEach { deviceId ->
                deviceRevocation.revokeMemberDevice(body.realmId, deviceId)
            }
            membershipStore.clearAdmissions(body.realmId, memberAuthorityPublicKey)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** 16 bytes hex-encoded — the canonical grant-address wire form (`deriveGrantAddress`). */
private fun isValidGrantAddress(address: String?): Boolean =
    address != null && address.length == 32 && address.all { it in '0'..'9' || it in 'a'..'f' }

/** Decode a base64 sealed grant, or null when it exceeds [MAX_GRANT_BLOB_BYTES]. */
private fun decodeGrantCapped(encoded: String): ByteArray? {
    if (encoded.length > (MAX_GRANT_BLOB_BYTES * 4 / 3) + 4) return null
    val decoded = Base64.getDecoder().decode(encoded)
    return decoded.takeIf { it.size <= MAX_GRANT_BLOB_BYTES }
}

private fun decodeB64(encoded: String): ByteArray = Base64.getDecoder().decode(encoded)

private fun b64encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/**
 * §4: the sealed grant carries `wrappedKr + capability + label + fingerprint`,
 * larger than an invite blob — an 8 KiB cap leaves generous headroom.
 */
internal const val MAX_GRANT_BLOB_BYTES: Int = 8 * 1024

/** D4: an unaccepted grant expires after 30 days. Server-fixed; the grantor sets no expiry. */
internal const val GRANT_TTL_MS: Long = 30L * 24L * 60L * 60L * 1_000L

/** Body of `POST /sync/grant/{address}`; `sealedGrant` is base64. */
@kotlinx.serialization.Serializable
internal data class PostGrantRequest(val sealedGrant: String)

/** Reply to `GET /sync/grant/{address}`. */
@kotlinx.serialization.Serializable
internal data class GetGrantResponse(val sealedGrant: String)

/** The Q30 capability fields (base64 byte fields) inside an admit request. */
@kotlinx.serialization.Serializable
internal data class AdmitCapabilityDto(
    val memberAuthorityPublicKey: String,
    val epoch: Long,
    val issuedAt: Long,
    val signature: String,
)

/** The device-binding fields (base64 byte fields) inside an admit request. */
@kotlinx.serialization.Serializable
internal data class AdmitDeviceBindingDto(
    val deviceId: String,
    val perRealmDevicePublicKey: String,
    val boundAt: Long,
    val signature: String,
)

/** Body of `POST /sync/realm-member/admit`. */
@kotlinx.serialization.Serializable
internal data class AdmitMemberRequest(
    val realmId: String,
    val capability: AdmitCapabilityDto,
    val deviceBinding: AdmitDeviceBindingDto,
)

/** Body of `POST /sync/realm-member/revoke`; key + signature are base64. */
@kotlinx.serialization.Serializable
internal data class RevokeMemberRequest(
    val realmId: String,
    val memberAuthorityPublicKey: String,
    val epoch: Long,
    val revokedAt: Long,
    val signature: String,
)
