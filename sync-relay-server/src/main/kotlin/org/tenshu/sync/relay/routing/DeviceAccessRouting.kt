package org.tenshu.sync.relay.routing

import org.tenshu.core.common.RelayRefusal
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.Base64
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.common.util.decodeHlcStamp
import org.tenshu.sync.common.DeviceAccessRevocation
import org.tenshu.sync.relay.auth.DeviceRevocation
import org.tenshu.sync.relay.auth.DeviceRegistry
import org.tenshu.sync.relay.logId
import org.tenshu.sync.relay.mailbox.RealmRegistry

/**
 * `POST /sync/device-access/revoke` — cut one device's relay access without a `Device` tombstone
 * (`sync-entity-identity.md` §5b precondition 0; threat model T-K-02, T-GR-08).
 *
 * Until this route, an applied `Device` tombstone was the **only** way to reach
 * `DeviceRegistry.revokeIfNotStale` (key removal + the permanent removal ledger), and a tombstone is
 * authorised by nothing but the pusher's JWT — so any admitted device could evict any other. Here the
 * authority is a **signature**, never a JWT and never the realm kind:
 *
 * | `authority` | signed by | may name |
 * |---|---|---|
 * | `master` | the realm's A1 (`realm.masterPublicKey`) | any device of the realm |
 * | `self` | the **named** device's own registered key | ⚠⚠ only itself, by construction: the signature is checked against the key of the device it names |
 *
 * No JWT on either form — the `/sync/admission/revoke` posture. ⚠⚠ The self form is how T-GR-08's
 * stopgap rule (a member device may only remove itself) survives the move off tombstones, and it
 * covers non-owner personal devices with the same rule: a device without A1 can only sign its own
 * removal. A hostile relay can forge neither signature. One realm-agnostic route, so the relay is
 * not told which realms are shared.
 *
 * ⚠ Why a signature and not the JWT for self: a JWT is a bearer token the relay sees on every
 * request, so a JWT-authorised removal is one a relay could mint by replaying any recent request.
 * A signed removal names its own `authoredAt` and a tag nothing else uses.
 *
 * Both forms carry `authoredAt`, the HLC the client authored the removal at, and both go through
 * [DeviceRevocation.applyAccessRevocation]: a removal older than the device's admission (on record,
 * or still queued for delivery) is **stale** and changes nothing. That is an answer, not an error —
 * `200 {"applied": false}` — so a client's durable queue can tell "done" from "retry".
 *
 * Repeating an applied **master** request applies again harmlessly (the key is already gone, the
 * ledger row already present). Repeating a **self** request answers `401 Unknown device`, because
 * the key it is verified against is the one it removed — a client's queue reads that as done.
 * ⚠ A replayed request (either form) is still bounded by the staleness rule;
 * against an admission registered **without** a stamp (recovery, membership grant) it applies — the
 * same "absent stamp means unknown" residual the tombstone path has (threat model T-K-02).
 *
 * House Rule #1: the relay sees a realm id, a device id, a logical clock and a signature it can
 * verify but not mint. Nothing here is new to it — the device id is already in `device` and
 * `removed_device`.
 */
fun Route.configureDeviceAccessRouting(
    realmStore: RealmRegistry,
    knownDevices: DeviceRegistry,
    deviceRevocation: DeviceRevocation,
    ed25519: Ed25519Backend,
) {
    route("/sync/device-access") {
        post("/revoke") {
            val body = try {
                call.receive<RevokeDeviceAccessRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Malformed JSON body")
                return@post
            }
            if (body.ownerId.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "ownerId is required")
                return@post
            }
            if (!RegistrationInputRules.isWellFormedDeviceId(body.deviceId)) {
                call.respond(HttpStatusCode.BadRequest, RegistrationInputRules.DEVICE_ID_REFUSAL)
                return@post
            }
            // ⚠ Required, unlike the tombstone path's: there is no legacy client of this route, and
            // a missing stamp would let a delayed request revoke a later re-admission.
            val authoredAt = decodeHlcStamp(body.authoredAt) ?: run {
                call.respond(HttpStatusCode.BadRequest, "authoredAt must be an HLC stamp (physical:counter:deviceId)")
                return@post
            }

            val authority = DeviceAccessRevocation.Authority.fromWire(body.authority) ?: run {
                call.respond(HttpStatusCode.BadRequest, "authority must be \"master\" or \"self\"")
                return@post
            }
            val signature = try {
                Base64.getDecoder().decode(body.signature)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "Invalid base64 in signature")
                return@post
            }
            // ⚠⚠ The authority decides the KEY, never the realm kind: the same rule for a personal
            // realm and a shared one, so the relay learns nothing about which is which.
            val verifyingKey: ByteArray = when (authority) {
                DeviceAccessRevocation.Authority.MASTER -> realmStore.get(body.ownerId)?.masterPublicKey ?: run {
                    call.respond(HttpStatusCode.NotFound, "Realm not found")
                    return@post
                }
                // Only the named device's own key: a SELF signature can never name anyone else,
                // because it is checked against the key of the device it names.
                DeviceAccessRevocation.Authority.SELF -> knownDevices.getPublicKey(body.ownerId, body.deviceId) ?: run {
                    // Unknown or already revoked: indistinguishable on purpose (the JWT 401 posture). The token
                    // lets the device's own retry read its earlier, lost-reply removal as done — and keeps a
                    // bad signature (below, no token) from being read the same way.
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        RelayRefusal.body(RelayRefusal.DEVICE_NOT_REGISTERED, "Unknown device"),
                    )
                    return@post
                }
            }
            val message = DeviceAccessRevocation.message(authority, body.ownerId, body.deviceId, authoredAt)
            if (!ed25519.verify(verifyingKey, message, signature)) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid revocation signature")
                return@post
            }

            val applied = deviceRevocation.applyAccessRevocation(body.ownerId, body.deviceId, authoredAt)
            if (!applied) {
                println(
                    "DeviceAccessRouting -> stale revocation for ${logId(body.deviceId)} (owner ${logId(body.ownerId)}): " +
                        "authored before the admission on record; nothing revoked."
                )
            }
            call.respond(HttpStatusCode.OK, RevokeDeviceAccessResponse(applied = applied))
        }
    }
}

/**
 * Body of `POST /sync/device-access/revoke`. [authoredAt] is `HlcStamp.encode()`; [signature] is
 * base64, over [DeviceAccessRevocation.message] for [authority].
 */
@kotlinx.serialization.Serializable
internal data class RevokeDeviceAccessRequest(
    val ownerId: String,
    val deviceId: String,
    val authoredAt: String,
    /** `"master"` or `"self"` — [DeviceAccessRevocation.Authority.wire]. */
    val authority: String,
    val signature: String,
)

/** `applied = false` means stale: authored before the admission on record. Not retryable. */
@kotlinx.serialization.Serializable
internal data class RevokeDeviceAccessResponse(val applied: Boolean)
