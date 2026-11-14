package org.tenshu.sync.relay.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.Base64
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.sync.common.AdmissionRevocation
import org.tenshu.sync.relay.mailbox.AdmissionRevocationRegistry
import org.tenshu.sync.relay.mailbox.RealmRegistry

/**
 * Delegated device-admission authority relay surface
 * (`docs/proposals/delegated-device-admission-authority.md` §5, P2):
 *
 *  - `POST /sync/admission/revoke` — revoke a device's admission capability
 *    (A33). *Unauthenticated — the realm-master signature over the revocation
 *    is the authorisation*, the `/sync/realm-member/revoke` posture.
 *
 * Appending a revocation stops the chain-verified `POST /sync/pairing/response`
 * path admitting with that device's cert as the approver (`chain[0]`). ⚠⚠ Not
 * through a chain the device merely vouched for: the verifier checks the
 * approver's key only (`admission-revocation-names-the-capability.md` §7j.10 —
 * a device is a member or removed, never a member that cannot admit). Revocation of the device's *data* access (removal + Kr rotation)
 * is the existing device-management flow; this endpoint only strips minting
 * power. House Rule #1: the relay stores an opaque id, an epoch, a public key and
 * a master-signed token — nothing it could mint itself.
 */
fun Route.configureAdmissionRouting(
    admissionRevocationStore: AdmissionRevocationRegistry,
    realmStore: RealmRegistry,
    ed25519: Ed25519Backend,
) {
    route("/sync/admission") {
        post("/revoke") {
            val body = try {
                call.receive<RevokeAdmissionRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Malformed JSON body")
                return@post
            }
            val signature = try {
                Base64.getDecoder().decode(body.signature)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "Invalid base64 in signature")
                return@post
            }

            // F1′, required since F5b (§7g): a revocation names the capability KEY. ⚠⚠ There is no
            // keyless form any more — a body without a key is a 400 (it fails to parse), never a
            // device-scoped revocation, because the verifier no longer has an epoch rule to apply
            // one with.
            val capabilityKey = try {
                Base64.getDecoder().decode(body.deviceA33PublicKey)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "Invalid base64 in deviceA33PublicKey")
                return@post
            }
            // B1: exactly an Ed25519 key (an empty one, the F5b case, is a special case of this).
            if (!RegistrationInputRules.isEd25519PublicKey(capabilityKey)) {
                call.respond(HttpStatusCode.BadRequest, "deviceA33PublicKey: ${RegistrationInputRules.KEY_REFUSAL}")
                return@post
            }

            val realm = realmStore.get(body.ownerId) ?: run {
                call.respond(HttpStatusCode.NotFound, "Realm not found")
                return@post
            }

            // The key is inside the signed bytes, so a body naming a different key than the one
            // A1 signed fails here with a 401 rather than being stored.
            val revocation = AdmissionRevocation(
                ownerId = body.ownerId,
                deviceId = body.deviceId,
                epoch = body.epoch,
                revokedAt = body.revokedAt,
                signature = signature,
                deviceA33PublicKey = capabilityKey,
            )
            if (!ed25519.verify(realm.masterPublicKey, revocation.signedMessage(), signature)) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid revocation signature")
                return@post
            }

            admissionRevocationStore.appendRevocation(
                AdmissionRevocationRegistry.RevocationRecord(
                    ownerId = body.ownerId,
                    deviceId = body.deviceId,
                    epoch = body.epoch,
                    revokedAt = body.revokedAt,
                    signature = signature,
                    deviceA33PublicKey = capabilityKey,
                )
            )
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** Body of `POST /sync/admission/revoke`; `signature` is base64. */
@kotlinx.serialization.Serializable
internal data class RevokeAdmissionRequest(
    val ownerId: String,
    val deviceId: String,
    val epoch: Long,
    val revokedAt: Long,
    val signature: String,
    /**
     * F1′ — base64 A33 capability key. ⚠ **Required** since F5b (protocol-1 re-cut): there is no
     * default, so a keyless body is malformed rather than a device-scoped revocation.
     */
    val deviceA33PublicKey: String,
)
