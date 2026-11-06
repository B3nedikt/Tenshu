package org.tenshu.sync.relay.routing

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.Base64
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.JwtAuthenticator
import org.tenshu.sync.relay.auth.RelayEntitlementVerifier
import org.tenshu.sync.relay.mailbox.SHARE_ID_SHAPE
import org.tenshu.sync.relay.mailbox.ShareRegistry
import org.tenshu.sync.relay.mailbox.ShareStore
import org.tenshu.sync.relay.storage.StorageCeiling

/**
 * Mode-A link-sharing HTTP surface
 * (`docs/file-sharing.md` + §9.1).
 *
 * Five endpoints:
 *
 *  - `POST  /share`                          — sharer creates a ShareRecord.    *JWT-required*
 *  - `PUT   /share/{shareId}/chunk/{index}`  — sharer uploads a ciphertext chunk. *JWT-required*
 *  - `DELETE /share/{shareId}`               — sharer revokes a share.           *JWT-required*
 *  - `GET   /share/{shareId}`                — recipient fetches the descriptor. *Anonymous*
 *  - `GET   /share/{shareId}/chunk/{index}`  — recipient fetches a chunk.        *Anonymous*
 *
 * Anonymous reads are the deliberate exception to the per-owner JWT scoping
 * that governs the rest of the relay (T-S-02 in §5.2 — accepted residual on
 * A6 metadata). The three sharer-only verbs route through [requireOwnerId],
 * which gives a verified `ownerId` for create + scopes revoke/PUT-chunk to
 * the same owner that created the share — a different owner attempting either
 * gets 404 (existence-leak-proof).
 *
 * House Rule #1 unchanged: chunk bodies are opaque ciphertext, stored verbatim,
 * never decrypted. `K_share` is never on the wire (URL fragment only — A11).
 * The file's name, MIME type and exact size reach the relay only as
 * `sealedMeta`, an opaque AEAD blob under `K_share` that it stores and serves
 * back verbatim (`relay-metadata-minimisation.md` F3). What it keeps in the
 * clear is what it enforces: `chunkCount`, expiry, `maxOpens`, the open count
 * and revocation.
 */
fun Route.configureShareRouting(
    config: RelayConfig,
    jwtAuthenticator: JwtAuthenticator,
    shareStore: ShareRegistry,
    entitlementVerifier: RelayEntitlementVerifier? = null,
    /** The relay-wide cost brake ([StorageCeiling]); null = no ceiling. */
    storageCeiling: StorageCeiling? = null,
) {
    route("/share") {
        post {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@post
            // §8 Phase 2 write-gating: a share drop (descriptor create) requires a live, owner-bound
            // entitlement when enforcement is on (opt-in; a no-op otherwise). DELETE (revoke) and the
            // anonymous recipient GETs stay ungated (§9 Q5 — revoke is cleanup, not a new write).
            if (!requireEntitlement(call, ownerId, entitlementVerifier, config.entitlementEnforce)) {
                return@post
            }
            val body = try {
                call.receive<CreateShareRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Malformed JSON body")
                return@post
            }
            val sealedMeta = runCatching { Base64.getUrlDecoder().decode(body.sealedMeta) }.getOrNull()
            // ⚠ The takedown route accepts only this shape, so nothing else may be created.
            if (!SHARE_ID_SHAPE.matches(body.shareId) || body.chunkCount < 0 ||
                sealedMeta == null || sealedMeta.isEmpty() || sealedMeta.size > MAX_SEALED_META_BYTES
            ) {
                call.respond(HttpStatusCode.BadRequest, "Invalid share descriptor")
                return@post
            }
            val record = ShareStore.ShareRecord(
                shareId = body.shareId,
                ownerId = ownerId,
                sealedMeta = sealedMeta,
                chunkCount = body.chunkCount,
                chunkHashes = body.chunkHashes.map { Base64.getUrlDecoder().decode(it) },
                expiresAtMillis = body.expiresAtMillis.takeIf { it != null && it > 0L },
                maxOpens = body.maxOpens.takeIf { it != null && it > 0 },
                createdAtMs = System.currentTimeMillis(),
            )
            // Relay-wide ceiling. Charged here, at create, for every declared chunk at the per-chunk
            // cap — chunk sizes are never indexed, so this is the upper bound the ceiling's own
            // measurement uses too. The chunk PUTs that follow are therefore never refused: their
            // bytes were admitted with the descriptor. ⚠ A same-owner retry of a create is not new
            // storage and is let through.
            val declaredBytes = record.chunkCount.toLong() * MAX_SHARE_CHUNK_BYTES
            val isRetry = shareStore.peek(record.shareId)?.ownerId == ownerId
            if (storageCeiling != null && !isRetry && !storageCeiling.admits(declaredBytes)) {
                call.respond(HttpStatusCode.InsufficientStorage, mapOf("error" to STORAGE_CEILING_ERROR))
                return@post
            }
            val created = shareStore.create(record)
            if (created != null && !isRetry) storageCeiling?.recordStored(declaredBytes)
            if (created == null) {
                // shareId already taken by a different owner.
                call.respond(HttpStatusCode.Conflict, "shareId already in use")
                return@post
            }
            call.respond(HttpStatusCode.Created, mapOf("shareId" to created.shareId))
        }

        delete("/{shareId}") {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@delete
            val shareId = call.parameters["shareId"]
            if (shareId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Missing shareId")
                return@delete
            }
            val ok = shareStore.revoke(shareId, ownerId)
            if (!ok) {
                // Either absent or not-owned-by-me; respond 404 in both cases
                // so we don't leak existence to a different owner.
                call.respond(HttpStatusCode.NotFound, "Share not found")
                return@delete
            }
            call.respond(HttpStatusCode.NoContent)
        }

        put("/{shareId}/chunk/{chunkIndex}") {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@put
            // §8 Phase 2 write-gating: uploading a share chunk requires a live, owner-bound
            // entitlement when enforcement is on (opt-in; a no-op otherwise).
            if (!requireEntitlement(call, ownerId, entitlementVerifier, config.entitlementEnforce)) {
                return@put
            }
            val shareId = call.parameters["shareId"]
            val chunkIndex = call.parameters["chunkIndex"]?.toIntOrNull()
            if (shareId == null || !SHARE_ID_SHAPE.matches(shareId) || chunkIndex == null || chunkIndex < 0) {
                call.respond(HttpStatusCode.BadRequest, "Invalid chunk address")
                return@put
            }
            val record = shareStore.peek(shareId)
            if (record == null || record.ownerId != ownerId) {
                // Existence-leak-proof: same 404 for absent or owned-by-someone-else.
                call.respond(HttpStatusCode.NotFound, "Share not found")
                return@put
            }
            // A revoked share — by its owner, or by an operator takedown — takes no more bytes: they
            // could never be served, and after a takedown they would re-store what was just deleted.
            if (record.revokedAtMs != null) {
                call.respond(HttpStatusCode.Gone, "Share is no longer available")
                return@put
            }
            // Only the declared chunks: an index past `chunkCount` is never fetched by a viewer, and
            // neither the GC nor a takedown (both delete `0 until chunkCount`) would ever reach it.
            if (chunkIndex >= record.chunkCount) {
                call.respond(HttpStatusCode.BadRequest, "Invalid chunk address")
                return@put
            }
            // Same hard cap shape as the content plane (T-R-09 P3a): keeps an
            // accidentally-non-chunked client (or a malicious admitted device)
            // from spamming the in-memory store with oversized blobs.
            val advertisedLength = call.request.headers["Content-Length"]?.toLongOrNull()
            if (advertisedLength != null && advertisedLength > MAX_SHARE_CHUNK_BYTES) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    "share chunk exceeds ${MAX_SHARE_CHUNK_BYTES / (1024 * 1024)} MiB cap"
                )
                return@put
            }
            val ciphertext = call.receive<ByteArray>()
            if (ciphertext.size > MAX_SHARE_CHUNK_BYTES) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    "share chunk exceeds ${MAX_SHARE_CHUNK_BYTES / (1024 * 1024)} MiB cap"
                )
                return@put
            }
            shareStore.putChunk(shareId, chunkIndex, ciphertext)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/{shareId}") {
            // Anonymous. Atomically bumps `opensSoFar` (so a `maxOpens` cap
            // counts descriptor fetches, not chunk fetches — one open = one
            // recipient view session).
            val shareId = call.parameters["shareId"]
            if (shareId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Missing shareId")
                return@get
            }
            val record = shareStore.open(shareId)
            if (record == null) {
                // 410 covers "absent, revoked, expired, or cap-exhausted" —
                // the recipient UI surfaces the same "this link is no longer
                // available" message for all four cases.
                call.respond(HttpStatusCode.Gone, "Share is no longer available")
                return@get
            }
            call.respond(
                HttpStatusCode.OK,
                ShareDescriptor(
                    shareId = record.shareId,
                    sealedMeta = Base64.getUrlEncoder().withoutPadding().encodeToString(record.sealedMeta),
                    chunkCount = record.chunkCount,
                    chunkHashes = record.chunkHashes.map {
                        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
                    },
                    expiresAtMillis = record.expiresAtMillis,
                    opensRemaining = record.maxOpens?.let { it - record.opensSoFar }?.coerceAtLeast(0),
                )
            )
        }

        get("/{shareId}/chunk/{chunkIndex}") {
            // Anonymous chunk fetch — relay serves opaque ciphertext from its
            // chunk store. The viewer JS performs AEAD + per-chunk hash check
            // client-side using `K_share` from the URL fragment.
            val shareId = call.parameters["shareId"]
            val chunkIndex = call.parameters["chunkIndex"]?.toIntOrNull()
            if (shareId.isNullOrBlank() || chunkIndex == null || chunkIndex < 0) {
                call.respond(HttpStatusCode.BadRequest, "Invalid chunk address")
                return@get
            }
            val ciphertext = shareStore.getChunk(shareId, chunkIndex)
            if (ciphertext == null) {
                call.respond(HttpStatusCode.Gone, "Chunk is no longer available")
                return@get
            }
            call.respondBytes(ciphertext, contentType = ContentType.Application.OctetStream)
        }
    }
}

/**
 * Hard ceiling on a single share-chunk PUT, mirroring the content-plane cap
 * (T-R-09 P3a). The proposal targets the same 1–4 MiB CHUNK as the regular
 * file-content plane (§3.3); 8 MiB gives generous headroom.
 */
internal const val MAX_SHARE_CHUNK_BYTES: Int = 8 * 1024 * 1024

/**
 * Ceiling on the decoded `sealedMeta` blob. A real one is a 256-byte bucket plus the AEAD's
 * nonce/tag and AAD frame (~350 bytes); the sharer caps each string at 4 KiB, so 16 KiB admits
 * every legitimate record while keeping a descriptor row small.
 */
internal const val MAX_SEALED_META_BYTES: Int = 16 * 1024

/**
 * Body of `POST /share`. The sharer mints `shareId` client-side as 16 random
 * bytes base64url-encoded — global namespace, enforced unique by the store.
 *
 * `chunkHashes` is the per-chunk hash anchor the recipient verifies against
 * each fetched chunk's ciphertext (mirrors the whole-file integrity discipline
 * in `FileContentManifestPayload`). Each entry is base64url of the raw hash
 * bytes (so the JSON body stays text).
 *
 * `sealedMeta` is base64url (no padding) of the sharer's AEAD blob carrying the
 * file's name, MIME type and exact size under `K_share`
 * (`org.tenshu.sync.common.sealShareMeta`). Required and opaque: the relay
 * checks only that it decodes and is within [MAX_SEALED_META_BYTES].
 */
@kotlinx.serialization.Serializable
internal data class CreateShareRequest(
    val shareId: String,
    val sealedMeta: String,
    val chunkCount: Int,
    val chunkHashes: List<String> = emptyList(),
    val expiresAtMillis: Long? = null,
    val maxOpens: Int? = null,
)

/**
 * Body of `GET /share/{shareId}`. Mirrors the recipient-side fields the
 * proposal §3.4 calls for. `opensRemaining` is `null` when there is no cap.
 * `sealedMeta` is served back verbatim; the viewer opens it with `K_share`.
 */
@kotlinx.serialization.Serializable
internal data class ShareDescriptor(
    val shareId: String,
    val sealedMeta: String,
    val chunkCount: Int,
    val chunkHashes: List<String>,
    val expiresAtMillis: Long?,
    val opensRemaining: Int?,
)
