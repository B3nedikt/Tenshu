package org.tenshu.sync.relay.routing

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.sync.relay.auth.ClientRateLimiter
import org.tenshu.sync.relay.auth.DeviceRegistry
import org.tenshu.sync.relay.auth.MasterDelegationVerifier
import org.tenshu.sync.relay.auth.clientNetworkKey
import org.tenshu.sync.relay.mailbox.RealmRegistry
import org.tenshu.sync.relay.mailbox.RecoveryChallengeStore

/**
 * `GET /sync/recovery-challenge` + `POST /sync/recovery-claim` per `sync-relay-api.md`
 * §3.4. The recovery flow lets a device that has the recovery code (and therefore can
 * derive the master identity) re-join an existing realm without going through pairing.
 *
 * Both endpoints are unauthenticated by JWT — the recovering device hasn't been admitted
 * to the realm yet. The challenge-response protocol prevents signature replay:
 *
 *   1. Device GETs `/sync/recovery-challenge` → fresh random bytes (5-min TTL). No owner.
 *   2. Device signs `(newDevicePublicKey || newDeviceId || challenge)` with the master
 *      identity and POSTs `/sync/recovery-claim` carrying the master **public key**.
 *   3. Relay consumes the challenge, verifies the signature against that key, and only then
 *      looks the realm up (`ownerId = sha256(masterPub)`) and registers the device's public
 *      key — subsequent JWTs from this device verify normally.
 *
 * ⚠⚠ **ownerId-blind** (`relay-metadata-minimisation.md` F8). `ownerId` is public — every
 * pairing QR carries it — so no route here may answer differently for "a realm with this
 * ownerId exists" to a caller who has not proven the master key. The challenge is issued to
 * anyone, and the claim says 404 only *after* the signature verified. (The A9 recovery-blob
 * routes, which served a blob by ownerId, were removed with F8: no client used them.)
 *
 * [challengeRateLimiter], when non-null, caps challenges per client IP (an IPv6 client per /64) and
 * answers `429` + `Retry-After` past it — threat model T-RC-08 residual (a): without it one source
 * could fill [challengeStore]'s bounded set and delay every real recovery for a TTL. It runs
 * *before* the store, so a throttled caller holds no slot. ⚠ The key is the forwarded client IP and
 * must never be logged; the limit is the same for every realm, so the route stays ownerId-blind.
 */
fun Route.configureRecoveryRouting(
    realmStore: RealmRegistry,
    challengeStore: RecoveryChallengeStore,
    knownDevices: DeviceRegistry,
    ed25519: Ed25519Backend,
    challengeRateLimiter: ClientRateLimiter? = null,
) {
    route("/sync") {

        get("/recovery-challenge") {
            // ⚠ No ownerId, no realm lookup (F8): the answer must be the same for every caller.
            val limited = challengeRateLimiter
                ?.tryAcquire(clientNetworkKey(call.request.origin.remoteAddress))
                as? ClientRateLimiter.Decision.Limited
            if (limited != null) {
                call.response.header(HttpHeaders.RetryAfter, limited.retryAfterSeconds.toString())
                call.respond(HttpStatusCode.TooManyRequests, "Too many recovery challenges from this address")
                return@get
            }
            val challenge = challengeStore.issue()
            if (challenge == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, "Too many outstanding recovery challenges")
                return@get
            }
            call.respond(
                HttpStatusCode.OK,
                mapOf("challenge" to java.util.Base64.getEncoder().encodeToString(challenge))
            )
        }

        post("/recovery-claim") {
            val body = try {
                call.receive<Map<String, String>>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Malformed JSON body")
                return@post
            }

            val masterPublicKeyB64 = body["masterPublicKey"]
            val newDevicePublicKeyB64 = body["newDevicePublicKey"]
            val newDeviceId = body["newDeviceId"]
            val challengeB64 = body["challenge"]
            val masterSignatureB64 = body["masterSignature"]

            if (masterPublicKeyB64 == null || newDevicePublicKeyB64 == null ||
                newDeviceId == null || challengeB64 == null ||
                masterSignatureB64 == null
            ) {
                call.respond(HttpStatusCode.BadRequest, "Missing required field")
                return@post
            }

            val masterPublicKey = try {
                java.util.Base64.getDecoder().decode(masterPublicKeyB64)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "masterPublicKey is not valid base64")
                return@post
            }
            val newDevicePublicKey = try {
                java.util.Base64.getDecoder().decode(newDevicePublicKeyB64)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "newDevicePublicKey is not valid base64")
                return@post
            }
            val challenge = try {
                java.util.Base64.getDecoder().decode(challengeB64)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "challenge is not valid base64")
                return@post
            }
            val signature = try {
                java.util.Base64.getDecoder().decode(masterSignatureB64)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "masterSignature is not valid base64")
                return@post
            }

            // B1: an Ed25519 key and a printable id, checked before the challenge is spent.
            if (!RegistrationInputRules.isEd25519PublicKey(newDevicePublicKey)) {
                return@post call.respond(HttpStatusCode.BadRequest, RegistrationInputRules.KEY_REFUSAL)
            }
            if (!RegistrationInputRules.isWellFormedDeviceId(newDeviceId)) {
                return@post call.respond(HttpStatusCode.BadRequest, RegistrationInputRules.DEVICE_ID_REFUSAL)
            }
            // Workstream B: the recovering device's id is its key's — checked before the challenge is
            // spent, so a mis-derived claim leaves it usable.
            if (!RegistrationInputRules.isKeyDerivedDeviceId(newDeviceId, newDevicePublicKey)) {
                return@post call.respond(HttpStatusCode.BadRequest, RegistrationInputRules.DEVICE_ID_NOT_KEY_DERIVED_REFUSAL)
            }

            // Consume the challenge: must be outstanding, not expired, and not already used.
            // Consume happens BEFORE signature verification to make replay impossible even
            // if the verifier is buggy — a consumed challenge is gone regardless.
            if (!challengeStore.consume(challenge)) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    "Challenge unknown, expired, or already consumed"
                )
                return@post
            }

            // Verify the signature against the master public key the claim carries, BEFORE any
            // realm lookup (F8): a caller who cannot sign gets the same 401 whether or not a realm
            // exists, so the route is no existence oracle for a public ownerId.
            val message = MasterDelegationVerifier.recoveryClaimMessage(
                newDevicePublicKey = newDevicePublicKey,
                newDeviceId = newDeviceId,
                challenge = challenge
            )
            if (!MasterDelegationVerifier.verify(masterPublicKey, message, signature, ed25519)) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid master signature")
                return@post
            }

            // The caller holds the master key: telling it whether its realm exists leaks nothing.
            // The realm row's key was bound to its ownerId at genesis; compare anyway.
            val ownerId = ownerIdForMasterPublicKey(masterPublicKey)
            val realm = realmStore.get(ownerId)
            if (realm == null) {
                call.respond(HttpStatusCode.NotFound, "Realm not found")
                return@post
            }
            if (!realm.masterPublicKey.contentEquals(masterPublicKey)) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid master signature")
                return@post
            }

            // Admission successful: register the new device's public key. Subsequent JWT-
            // authenticated requests from this device will verify against this entry.
            knownDevices.register(ownerId, newDeviceId, newDevicePublicKey)

            call.respond(HttpStatusCode.OK, mapOf("status" to "admitted"))
        }
    }
}
