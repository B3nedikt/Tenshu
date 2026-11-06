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
import org.tenshu.sync.relay.mailbox.RendezvousRegistry

/**
 * App↔proxy EPC rendezvous relay surface
 * (`docs/proposals/ios-proxy-architecture.md` §5, Phase 3a) — a store-and-forward
 * mailbox that brokers the EPC handshake + sealed session frames between an iOS
 * app and its proxy without either holding the other's network address. Four
 * endpoints across two route groups:
 *
 *  - `POST   /sync/epc/{sessionId}`             — proxy provisions a session.   *JWT-required (the admission gate)*
 *  - `DELETE /sync/epc/{sessionId}`             — proxy tears it down.          *JWT-required, owner-scoped*
 *  - `POST   /sync/epc/{sessionId}/{direction}` — append a sealed frame.        *Anonymous — sessionId is the capability*
 *  - `GET    /sync/epc/{sessionId}/{direction}` — fetch frames after a cursor.  *Anonymous — sessionId is the capability*
 *
 * Admission (§5): a session can only be **provisioned by a realm device** under
 * its device-key JWT, binding it to an `ownerId` and bounding it per owner
 * ([RelayConfig.maxOpenRendezvousSessionsPerOwner]) — an app (not a realm device)
 * cannot create unbounded sessions. Frame traffic is anonymous, gated by the
 * high-entropy `sessionId` (HKDF `deriveEpcRendezvousSessionId`) and requiring
 * the session to already exist. The 2a SAS, not this surface, authenticates the
 * channel; O2 (edge App Attest) is resolved *no* — JWT-gating + caps are the
 * whole admission story.
 *
 * House Rule #1: every frame is opaque sealed `EpcSession` ciphertext the relay
 * has no key for, addressed by an HKDF id the relay never seeds; the relay stores
 * routing metadata + bytes and serves them verbatim. No decrypt code server-side.
 */
fun Route.configureEpcRendezvousRouting(
    config: RelayConfig,
    jwtAuthenticator: JwtAuthenticator,
    rendezvousStore: RendezvousRegistry,
    nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    route("/sync/epc/{sessionId}") {
        // Provision (or refresh) a session — the JWT-gated admission point.
        post {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@post
            val sessionId = call.parameters["sessionId"]
            if (!isValidSessionId(sessionId)) {
                call.respond(HttpStatusCode.BadRequest, "Invalid session id")
                return@post
            }
            val now = nowEpochMillis()
            when (
                rendezvousStore.provision(
                    sessionId = sessionId!!,
                    ownerId = ownerId,
                    expiresAtMs = now + RENDEZVOUS_TTL_MS,
                    nowMs = now,
                    maxSessionsPerOwner = config.maxOpenRendezvousSessionsPerOwner,
                )
            ) {
                RendezvousRegistry.ProvisionResult.PROVISIONED -> call.respond(HttpStatusCode.NoContent)
                RendezvousRegistry.ProvisionResult.CAP_EXCEEDED ->
                    call.respond(HttpStatusCode.TooManyRequests, "open-session cap reached")
                RendezvousRegistry.ProvisionResult.OWNER_MISMATCH ->
                    call.respond(HttpStatusCode.Conflict, "session id already in use")
            }
        }

        // Tear down a session the caller owns.
        delete {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@delete
            val sessionId = call.parameters["sessionId"]
            if (!isValidSessionId(sessionId)) {
                call.respond(HttpStatusCode.BadRequest, "Invalid session id")
                return@delete
            }
            when (rendezvousStore.teardown(sessionId!!, ownerId, nowEpochMillis())) {
                RendezvousRegistry.TeardownResult.TORN_DOWN -> call.respond(HttpStatusCode.NoContent)
                RendezvousRegistry.TeardownResult.NOT_FOUND ->
                    call.respond(HttpStatusCode.NotFound, "No such session")
                RendezvousRegistry.TeardownResult.OWNER_MISMATCH ->
                    call.respond(HttpStatusCode.Forbidden, "Not the session owner")
            }
        }
    }

    route("/sync/epc/{sessionId}/{direction}") {
        // Append a sealed frame — anonymous; the session id is the capability and
        // the session must already be provisioned by its owning realm device.
        post {
            val sessionId = call.parameters["sessionId"]
            val direction = RendezvousRegistry.Direction.fromWire(call.parameters["direction"])
            if (!isValidSessionId(sessionId) || direction == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid session id or direction")
                return@post
            }
            val body = try {
                call.receive<PostFrameRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Malformed JSON body")
                return@post
            }
            val frame = try {
                decodeFrameCapped(body.frame)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "Invalid base64 payload")
                return@post
            }
            if (frame == null) {
                call.respond(HttpStatusCode.PayloadTooLarge, "frame exceeds $MAX_RENDEZVOUS_FRAME_BYTES bytes")
                return@post
            }
            when (
                rendezvousStore.append(
                    sessionId = sessionId!!,
                    direction = direction,
                    payload = frame,
                    nowMs = nowEpochMillis(),
                    maxFramesPerDirection = config.maxRendezvousFramesPerDirection,
                )
            ) {
                RendezvousRegistry.AppendResult.APPENDED -> call.respond(HttpStatusCode.NoContent)
                RendezvousRegistry.AppendResult.NO_SESSION ->
                    call.respond(HttpStatusCode.NotFound, "No such session")
                RendezvousRegistry.AppendResult.CAP_EXCEEDED ->
                    call.respond(HttpStatusCode.TooManyRequests, "direction frame cap reached")
            }
        }

        // Fetch frames after the caller's cursor — anonymous, capability-as-id.
        get {
            val sessionId = call.parameters["sessionId"]
            val direction = RendezvousRegistry.Direction.fromWire(call.parameters["direction"])
            if (!isValidSessionId(sessionId) || direction == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid session id or direction")
                return@get
            }
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            when (val fetch = rendezvousStore.fetch(sessionId!!, direction, since, nowEpochMillis())) {
                is RendezvousRegistry.Fetch.Frames -> {
                    // Empty response leaves the cursor at `since` (the cursor-advancement contract).
                    val nextSince = fetch.frames.lastOrNull()?.seq ?: since
                    call.respond(
                        HttpStatusCode.OK,
                        FetchFramesResponse(
                            frames = fetch.frames.map { FrameDto(seq = it.seq, frame = b64encode(it.payload)) },
                            nextSince = nextSince,
                        ),
                    )
                }
                RendezvousRegistry.Fetch.NoSession ->
                    call.respond(HttpStatusCode.NotFound, "No such session")
            }
        }
    }
}

/** 16 bytes hex-encoded — the canonical session-id wire form (`deriveEpcRendezvousSessionId`). */
private fun isValidSessionId(sessionId: String?): Boolean =
    sessionId != null && sessionId.length == 32 && sessionId.all { it in '0'..'9' || it in 'a'..'f' }

/** Decode a base64 sealed frame, or null when it exceeds [MAX_RENDEZVOUS_FRAME_BYTES]. */
private fun decodeFrameCapped(encoded: String): ByteArray? {
    if (encoded.length > (MAX_RENDEZVOUS_FRAME_BYTES * 4 / 3) + 4) return null
    val decoded = Base64.getDecoder().decode(encoded)
    return decoded.takeIf { it.size <= MAX_RENDEZVOUS_FRAME_BYTES }
}

private fun b64encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/**
 * A rendezvous frame is one sealed EPC operation request/response (or a handshake
 * hello), not bulk data — large file bytes ride the content plane. 64 KiB is
 * generous headroom for an operation envelope.
 */
internal const val MAX_RENDEZVOUS_FRAME_BYTES: Int = 64 * 1024

/**
 * The rendezvous mailbox is for live session bring-up, not durable queuing — a
 * short TTL bounds storage and orphaned sessions. The proxy re-provisions
 * (idempotent) to keep a long-lived pairing alive across the window.
 */
internal const val RENDEZVOUS_TTL_MS: Long = 15L * 60L * 1_000L

/** Body of `POST /sync/epc/{sessionId}/{direction}`; `frame` is base64. */
@kotlinx.serialization.Serializable
internal data class PostFrameRequest(val frame: String)

/** One frame in a fetch response: relay-assigned [seq] + base64 sealed [frame]. */
@kotlinx.serialization.Serializable
internal data class FrameDto(val seq: Long, val frame: String)

/** Reply to `GET /sync/epc/{sessionId}/{direction}`; [nextSince] is the cursor to pass next. */
@kotlinx.serialization.Serializable
internal data class FetchFramesResponse(val frames: List<FrameDto>, val nextSince: Long)
