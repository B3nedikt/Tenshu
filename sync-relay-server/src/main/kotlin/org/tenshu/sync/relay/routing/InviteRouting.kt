package org.tenshu.sync.relay.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.Base64
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.JwtAuthenticator
import org.tenshu.sync.relay.mailbox.InviteRegistry

/**
 * Contact-invitation handshake mailbox
 * (`docs/proposals/contact-invitation-handshake.md` §6) — seven endpoints:
 *
 *  - `POST   /sync/invite`            — inviter registers a mailbox.   *JWT-required*
 *  - `DELETE /sync/invite/{inviteId}` — inviter revokes.               *JWT-required, owner-scoped*
 *  - `POST   /sync/invite/response`   — invitee posts sealed response. *Anonymous — `inviteId` is the capability*
 *  - `GET    /sync/invite/responses`  — inviter polls pending ones.    *JWT-required*
 *  - `POST   /sync/invite/ack`        — inviter posts sealed ack.      *JWT-required, owner-scoped*
 *  - `GET    /sync/invite/ack`        — invitee polls for the ack.     *Anonymous — capability*
 *  - `POST   /sync/invite/done`       — invitee frees the mailbox.     *Anonymous — capability*
 *
 * Issuance is authenticated so mailbox creation is bounded per owner (the
 * [RelayConfig.maxOpenInvitesPerOwner] cap, T-CI-05); redemption-side calls
 * carry no JWT so the invitee stays protocol-anonymous (T-CI-06). Error
 * vocabulary per `sync-relay-api.md`: 404 unknown, 409 conflict/slot-used,
 * 410 expired/revoked, 413 over the blob cap.
 *
 * House Rule #1: the relay stores `(inviteId → ownerId, expiry, state)` plus
 * the two **sealed** blobs, verbatim. It never sees `K_link` (or any hash of
 * it), either party's name, the invitee's identity key, or any realm
 * association — all of that rides inside the sealed payloads (§5.2;
 * T-CI-02/03).
 */
fun Route.configureInviteRouting(
    config: RelayConfig,
    jwtAuthenticator: JwtAuthenticator,
    inviteStore: InviteRegistry,
) {
    route("/sync/invite") {
        post {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@post
            val body = try {
                call.receive<RegisterInviteRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Malformed JSON body")
                return@post
            }
            if (!isValidInviteId(body.inviteId)) {
                call.respond(HttpStatusCode.BadRequest, "Invalid inviteId")
                return@post
            }
            val now = System.currentTimeMillis()
            val requested = body.expiresAtMillis ?: (now + DEFAULT_INVITE_TTL_MS)
            if (requested <= now) {
                call.respond(HttpStatusCode.BadRequest, "expiresAtMillis is in the past")
                return@post
            }
            // §3: the relay caps any requested expiry at 7 days.
            val expiresAt = minOf(requested, now + MAX_INVITE_TTL_MS)
            when (
                inviteStore.register(
                    inviteId = body.inviteId,
                    ownerId = ownerId,
                    expiresAtMs = expiresAt,
                    nowMs = now,
                    maxOpenPerOwner = config.maxOpenInvitesPerOwner,
                )
            ) {
                InviteRegistry.RegisterResult.REGISTERED ->
                    call.respond(
                        HttpStatusCode.Created,
                        RegisterInviteResponse(inviteId = body.inviteId, expiresAtMillis = expiresAt)
                    )

                InviteRegistry.RegisterResult.DUPLICATE ->
                    call.respond(HttpStatusCode.Conflict, "inviteId already in use")

                InviteRegistry.RegisterResult.CAP_EXCEEDED ->
                    call.respond(HttpStatusCode.Conflict, "open-invite cap reached")
            }
        }

        delete("/{inviteId}") {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@delete
            val inviteId = call.parameters["inviteId"]
            if (inviteId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Missing inviteId")
                return@delete
            }
            if (!inviteStore.revoke(inviteId, ownerId)) {
                // Absent or not-owned-by-me: same 404 (existence-leak-proof).
                call.respond(HttpStatusCode.NotFound, "Invite not found")
                return@delete
            }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/response") {
            // Anonymous: knowledge of inviteId is the capability (§3); the
            // payload is sealed to the inviter's A13, so the relay relays
            // bytes it cannot read or forge (T-CI-02).
            val body = try {
                call.receive<PostInviteResponseRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Malformed JSON body")
                return@post
            }
            val sealed = try {
                decodeSizeCapped(body.sealedResponse)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "Invalid base64 payload")
                return@post
            }
            if (sealed == null) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    "sealed blob exceeds $MAX_INVITE_BLOB_BYTES bytes"
                )
                return@post
            }
            when (inviteStore.postResponse(body.inviteId, sealed)) {
                InviteRegistry.ResponseResult.POSTED -> call.respond(HttpStatusCode.NoContent)
                InviteRegistry.ResponseResult.UNKNOWN ->
                    call.respond(HttpStatusCode.NotFound, "Invite not found")

                InviteRegistry.ResponseResult.GONE ->
                    call.respond(HttpStatusCode.Gone, "Invite expired or revoked")

                InviteRegistry.ResponseResult.ALREADY_RESPONDED ->
                    call.respond(HttpStatusCode.Conflict, "Invite already responded")
            }
        }

        get("/responses") {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@get
            call.respond(
                HttpStatusCode.OK,
                PendingInviteResponses(
                    responses = inviteStore.pendingResponses(ownerId).map {
                        PendingInviteResponse(
                            inviteId = it.inviteId,
                            sealedResponse = b64encode(it.sealedResponse),
                        )
                    }
                )
            )
        }

        post("/ack") {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@post
            val body = try {
                call.receive<PostInviteAckRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Malformed JSON body")
                return@post
            }
            val sealed = try {
                decodeSizeCapped(body.sealedAck)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "Invalid base64 payload")
                return@post
            }
            if (sealed == null) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    "sealed blob exceeds $MAX_INVITE_BLOB_BYTES bytes"
                )
                return@post
            }
            when (inviteStore.postAck(body.inviteId, ownerId, sealed)) {
                InviteRegistry.AckResult.POSTED -> call.respond(HttpStatusCode.NoContent)
                InviteRegistry.AckResult.UNKNOWN ->
                    call.respond(HttpStatusCode.NotFound, "Invite not found")

                InviteRegistry.AckResult.CONFLICT ->
                    call.respond(HttpStatusCode.Conflict, "Invite has no pending response")
            }
        }

        get("/ack") {
            // Anonymous poll; the ack is sealed to the invitee's A13, so a
            // channel observer replaying the inviteId learns nothing (§6).
            val inviteId = call.request.queryParameters["inviteId"]
            if (inviteId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Missing inviteId")
                return@get
            }
            when (val fetch = inviteStore.getAck(inviteId)) {
                is InviteRegistry.AckFetch.Found ->
                    call.respond(
                        HttpStatusCode.OK,
                        InviteAckResponse(inviteId = inviteId, sealedAck = b64encode(fetch.sealedAck))
                    )

                InviteRegistry.AckFetch.NotFound ->
                    // Unknown and not-yet-acked share a 404 — the invitee
                    // keeps polling until 200 or 410.
                    call.respond(HttpStatusCode.NotFound, "No ack available")

                InviteRegistry.AckFetch.Gone ->
                    call.respond(HttpStatusCode.Gone, "Invite expired or revoked")
            }
        }

        post("/done") {
            // Anonymous + idempotent: frees relay state; TTL GC is the
            // fallback for parties that never call it.
            val body = try {
                call.receive<InviteDoneRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Malformed JSON body")
                return@post
            }
            inviteStore.done(body.inviteId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** 16 bytes hex-encoded — the canonical `inviteId` wire form (`InviteLinkPayload.inviteIdHex`). */
private fun isValidInviteId(inviteId: String): Boolean =
    inviteId.length == 32 && inviteId.all { it in '0'..'9' || it in 'a'..'f' }

/** Decode a base64 sealed blob, or null when it exceeds [MAX_INVITE_BLOB_BYTES]. */
private fun decodeSizeCapped(encoded: String): ByteArray? {
    // Base64 expands 4/3: a quick length pre-check before decoding keeps an
    // oversized body from being materialised at all.
    if (encoded.length > (MAX_INVITE_BLOB_BYTES * 4 / 3) + 4) return null
    val decoded = Base64.getDecoder().decode(encoded)
    return decoded.takeIf { it.size <= MAX_INVITE_BLOB_BYTES }
}

private fun b64encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/** §6: sealed response/ack bodies are size-capped (~4 KiB). */
internal const val MAX_INVITE_BLOB_BYTES: Int = 4 * 1024

/** §3: 72-hour default invite validity. */
internal const val DEFAULT_INVITE_TTL_MS: Long = 72L * 60L * 60L * 1_000L

/** §3/§6: the relay caps any requested expiry at 7 days. */
internal const val MAX_INVITE_TTL_MS: Long = 7L * 24L * 60L * 60L * 1_000L

/** Body of `POST /sync/invite`. `expiresAtMillis` omitted ⇒ the 72 h default. */
@kotlinx.serialization.Serializable
internal data class RegisterInviteRequest(
    val inviteId: String,
    val expiresAtMillis: Long? = null,
)

/** Reply to `POST /sync/invite` — echoes the (possibly clamped) expiry. */
@kotlinx.serialization.Serializable
internal data class RegisterInviteResponse(
    val inviteId: String,
    val expiresAtMillis: Long,
)

/** Body of `POST /sync/invite/response`; `sealedResponse` is base64. */
@kotlinx.serialization.Serializable
internal data class PostInviteResponseRequest(
    val inviteId: String,
    val sealedResponse: String,
)

/** One entry of `GET /sync/invite/responses`. */
@kotlinx.serialization.Serializable
internal data class PendingInviteResponse(
    val inviteId: String,
    val sealedResponse: String,
)

/** Reply to `GET /sync/invite/responses`. */
@kotlinx.serialization.Serializable
internal data class PendingInviteResponses(
    val responses: List<PendingInviteResponse>,
)

/** Body of `POST /sync/invite/ack`; `sealedAck` is base64. */
@kotlinx.serialization.Serializable
internal data class PostInviteAckRequest(
    val inviteId: String,
    val sealedAck: String,
)

/** Reply to `GET /sync/invite/ack`. */
@kotlinx.serialization.Serializable
internal data class InviteAckResponse(
    val inviteId: String,
    val sealedAck: String,
)

/** Body of `POST /sync/invite/done`. */
@kotlinx.serialization.Serializable
internal data class InviteDoneRequest(
    val inviteId: String,
)
