package org.tenshu.sync.relay.routing

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.tenshu.core.common.util.decodeHlcStamp
import org.tenshu.sync.relay.ContentSweepMode
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.JwtAuthenticator
import io.ktor.server.application.ApplicationCall
import org.tenshu.core.common.security.AeadBackend
import org.tenshu.core.common.security.EntitlementTokenVerifier
import org.tenshu.sync.relay.auth.RelayEntitlementVerifier
import org.tenshu.sync.relay.mailbox.ContentBlobRegistry
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry
import org.tenshu.sync.relay.mailbox.ContentFreeing
import org.tenshu.sync.relay.mailbox.ContentRefRegistry
import org.tenshu.sync.relay.mailbox.candidateContentIds
import org.tenshu.sync.relay.mailbox.computeContentInventory
import org.tenshu.sync.relay.storage.StorageCeiling

/**
 * The **content plane** HTTP surface of the two-plane file-content design
 * (`docs/adr/0017-file-content-two-plane-channel.md`;
 * `docs/proposals/sync-relay-api.md` §3.7).
 *
 * Two blob endpoints, conforming **byte-for-byte** to the client
 * (`sync-service-proxy/.../RestContentBlobStore.kt`):
 *
 *  - `PUT  /sync/content/{ref}` — store one already-AEAD-encrypted chunk.
 *    `Content-Type: application/octet-stream`, body = opaque ciphertext bytes.
 *    The client treats any 2xx / 201 / 204 as success.
 *  - `GET  /sync/content/{ref}` — fetch the opaque ciphertext by ref. Body =
 *    the exact stored bytes, `application/octet-stream`. `404` if absent (the
 *    client gates a missing blob as a clean failure and retries — chunks are
 *    immutable + content-addressed, so retry is idempotent).
 *
 * **Auth + per-owner scoping.** Both endpoints require a JWT exactly like
 * `POST /sync/push` — resolved through the shared [requireOwnerId] helper
 * (JWT `iss` → ownerId; dev mode also accepts `X-Dev-Owner`). The blob is
 * stored and served strictly under the **authenticated owner's** namespace,
 * so a device in realm A can neither `PUT` into nor `GET` from realm B's
 * content store: `(ownerId, ref)` is the storage key and `ownerId` always
 * comes from the verified token, never the request. This mirrors
 * `RestSyncRelay`'s ownerId scoping; the relay never trusts a client-supplied
 * owner. Unauthenticated requests get 401 from [JwtAuthenticator.require].
 *
 * **House Rule #1 (zero-trust).** The relay stores and serves the
 * ciphertext addressed only by the opaque `ref`; it never decrypts, never
 * inspects, never derives anything from blob contents. All AEAD + integrity
 * verification is client-side in `FileContentManifestApplier`. The ref is the
 * client's `blob-<sha256(ciphertext)>` opaque key — it reveals nothing about
 * plaintext (`docs/sync/threat-model.md` T-R-09).
 */
fun Route.configureContentRouting(
    config: RelayConfig,
    jwtAuthenticator: JwtAuthenticator,
    contentBlobStore: ContentBlobRegistry,
    entitlementVerifier: RelayEntitlementVerifier? = null,
    /**
     * The holder-keyed edge set (`content-plane-refcount.md` Q2). ⚠ Nullable so a relay built
     * without one does not expose the routes at all, rather than exposing ones that silently
     * forget — the posture the account-closure route already takes.
     */
    contentEdges: ContentEdgeRegistry? = null,
    /**
     * The per-content ref sets (`content-plane-refcount.md`). ⚠ Null leaves edges declarable and
     * content undescribed — a relay without descriptions, not a broken one.
     */
    contentRefs: ContentRefRegistry? = null,
    /**
     * The FREED state (`content-plane-cr2-b4-spec.md` §1.4): a describe naming a FREED id is
     * refused, and the whole request runs in its transaction. ⚠ Null leaves the describe without
     * the FREED check.
     */
    contentFreeing: ContentFreeing? = null,
    /**
     * `content-plane-cr2-b5-spec.md` D5. In [ContentSweepMode.ARMED] held bytes leave usage
     * and the quota; in the other modes they count. ⚠ There is no client FREE route
     * (`/sync/content/delete`, `/sync/content/reclaim` do not exist): no client frees.
     */
    contentSweepMode: ContentSweepMode = ContentSweepMode.OFF,
    /** The relay-wide cost brake ([StorageCeiling]); null = no ceiling. */
    storageCeiling: StorageCeiling? = null,
) {
    val armed = contentSweepMode == ContentSweepMode.ARMED

    /**
     * D5: the owner's bytes the relay holds but does not charge — UNREFERENCED or queued for deletion,
     * and shared with no live content. Zero unless armed: in `off`/`dry_run` nothing will ever free those
     * bytes, so they must count, or deleting would grant unbounded uncharged storage.
     */
    fun heldBytes(ownerId: String): Long =
        if (armed && contentFreeing != null) contentFreeing.heldBytes(ownerId) else 0L

    route("/sync/content") {
        put("/{ref}") {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@put
            // Write-gating: storing a content blob requires a live, owner-bound entitlement when
            // enforcement is on (opt-in; a no-op otherwise). The sibling GET is a read and stays
            // ungated (§9 Q5). resolveEntitlement carries the tier's quota through on the allow path
            // so this byte-metered endpoint can enforce it.
            val entitlement = resolveEntitlement(call, ownerId, entitlementVerifier, config.entitlementEnforce)
            if (entitlement is EntitlementOutcome.Rejected) {
                return@put
            }
            val ref = call.parameters["ref"]
            if (ref.isNullOrEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "Missing content ref")
                return@put
            }
            // Reject obvious garbage early — refs are minted client-side as
            // `blob-<sha256(ciphertext) hex>` (see `SyncObserver.emitFileContent`)
            // and proven structurally in the T-R-09 amendment. Enforcing the
            // shape here prevents abuse of the content store as a generic blob
            // bucket under arbitrary keys and gives a clean error when a
            // misconfigured client mints a wrong-shape ref.
            if (!REF_PATTERN.matches(ref)) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    "ref must match blob-<64-hex-chars>"
                )
                return@put
            }
            // Hard cap defends against (a) accidental whole-file PUTs from a
            // buggy client that bypassed the chunking layer, and (b) an
            // admitted-device abuse vector spamming oversized blobs into the
            // store, which no time-based sweep will ever drop (content GC is
            // off by default — durable data is not time-reaped). We
            // pre-check Content-Length so a 100 MB PUT is rejected before
            // reading a byte, and re-check the materialised body because a
            // client could lie about Content-Length.
            val advertisedLength = call.request.headers["Content-Length"]?.toLongOrNull()
            if (advertisedLength != null && advertisedLength > MAX_CONTENT_BLOB_BYTES) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    "content blob exceeds ${MAX_CONTENT_BLOB_BYTES / (1024 * 1024)} MiB cap"
                )
                return@put
            }
            // Relay-wide ceiling (the operator's cost brake). Checked before the body is read, so a
            // refused upload costs no transfer. ⚠ Only GROWTH is refused: a re-upload of a ref this
            // owner holds, at no larger size, is a retry or an in-place re-seal (a manifest, a relation
            // slice), and refusing it would break sync for data that is already paid for. ⚠⚠ A larger
            // body under a held ref is growth like any other — exempting it would let tiny refs be
            // overwritten with full-size ones past the ceiling. Same `507` as a full owner, so the
            // client's existing back-off applies; the code tells the two apart for anyone who reads it.
            val claimedLength = advertisedLength ?: MAX_CONTENT_BLOB_BYTES.toLong()
            val ceilingHeldLength: Long? = if (storageCeiling != null && !storageCeiling.admits(claimedLength)) {
                contentBlobStore.storedLength(ownerId, ref)?.takeIf { claimedLength <= it } ?: run {
                    call.respond(HttpStatusCode.InsufficientStorage, mapOf("error" to STORAGE_CEILING_ERROR))
                    return@put
                }
            } else {
                null
            }
            // Opaque ciphertext bytes. The relay never parses, decrypts, or
            // otherwise interprets this body — it is stored verbatim under the
            // authenticated owner's namespace (House Rule #1).
            val ciphertext = call.receive<ByteArray>()
            // ⚠⚠ A body below one AEAD envelope cannot be a blob this store should hold.
            // Checked on the MATERIALISED body, never on `Content-Length`: the whole failure this
            // closes is a body that arrives shorter than its headers promised.
            if (ciphertext.size < MIN_CONTENT_BLOB_BYTES) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    "content blob is ${ciphertext.size} bytes, below the ${MIN_CONTENT_BLOB_BYTES}-byte " +
                        "AEAD envelope minimum — the body was truncated"
                )
                return@put
            }
            if (ciphertext.size > MAX_CONTENT_BLOB_BYTES) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    "content blob exceeds ${MAX_CONTENT_BLOB_BYTES / (1024 * 1024)} MiB cap"
                )
                return@put
            }
            // ⚠ The ceiling exemption above trusted Content-Length; the body decides.
            if (ceilingHeldLength != null && ciphertext.size > ceilingHeldLength) {
                call.respond(HttpStatusCode.InsufficientStorage, mapOf("error" to STORAGE_CEILING_ERROR))
                return@put
            }
            // Per-owner quota: when enforcing, the tier's `quotaBytes` (from the resolved
            // entitlement) caps the owner's total stored bytes. A new chunk that would push the owner
            // over → 507 with a content-free `over_quota` code (the client maps it to a typed storage
            // error). A re-PUT of an already-stored ref is charged only on growth, so a retry of an
            // at-quota owner's emit still succeeds. quotaBytes is null when the gate is a no-op (enforcement
            // off / no issuer key) — store unmetered.
            val quotaBytes = (entitlement as EntitlementOutcome.Allowed).quotaBytes
            // ⚠ D5 (armed only): held bytes are left out of the owner's charged total, expressed as an
            // allowance on top of the quota because the store's check sums every indexed byte. The held
            // figure is read just before the store's own locked check, so a push reviving held content in
            // between can overshoot the quota by at most that content, once — the in-memory store's
            // bounded-overshoot posture, never a lasting excess (the next PUT sees the real figure).
            if (quotaBytes == null) {
                contentBlobStore.put(ownerId, ref, ciphertext)
            } else if (contentBlobStore.putWithinQuota(ownerId, ref, ciphertext, quotaWithHeld(quotaBytes, heldBytes(ownerId))) ==
                ContentBlobRegistry.PutResult.OVER_QUOTA
            ) {
                // 507 Insufficient Storage (RFC 4918 §11.5): the request is well-formed and
                // authorised, the server just can't store it within the owner's entitlement.
                // Distinct from 413 (a single oversized blob) and 402 (no/expired entitlement).
                call.respond(HttpStatusCode.InsufficientStorage, mapOf("error" to "over_quota"))
                return@put
            }
            storageCeiling?.recordStored(ciphertext.size.toLong())

            // 204 No Content: the client (RestContentBlobStore.putChunk)
            // accepts any 2xx / 201 / 204; an empty body is the lightest
            // success ack and avoids a needless allocation per chunk.
            call.respond(HttpStatusCode.NoContent)
        }

        get("/{ref}") {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@get
            val ref = call.parameters["ref"]
            if (ref.isNullOrEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "Missing content ref")
                return@get
            }
            // Reject malformed refs early so a probing client (or a
            // pathfinder scan) doesn't get to fish through the per-owner
            // store.
            if (!REF_PATTERN.matches(ref)) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    "ref must match blob-<64-hex-chars>"
                )
                return@get
            }

            // Per-owner scoped lookup: a device can only read blobs in its own
            // realm because `ownerId` is the verified-token owner, never a
            // client-supplied value.
            val ciphertext = contentBlobStore.get(ownerId, ref)
            // ⚠⚠ A stored blob below the envelope minimum is corrupt, and answering `200` with it
            // hides it — `FileBlobStore.get` returns `readBytes()` for any file that exists, so a
            // 0-byte file reads back as a *successful* empty body and the caller fails to DECRYPT
            // rather than seeing a 404.
            //
            // ⭐ 404 is the right shape, not 5xx: the client already treats a missing chunk as a
            // clean failure and retries, and a chunk is immutable + content-addressed, so a re-emit
            // is idempotent. This turns silent corruption into the path that already heals.
            if (ciphertext != null && ciphertext.size < MIN_CONTENT_BLOB_BYTES) {
                call.respond(HttpStatusCode.NotFound, "Content not found")
                return@get
            }
            if (ciphertext == null) {
                // Missing blob: never stored, dropped by the owner's own
                // reclamation (ST-3b `dropRefs`), or reclaimed on account
                // closure. Nothing ages out — the time-based GC is off.
                // RestContentBlobStore.getChunk maps a non-2xx to a clean
                // TResult.Failure the manifest applier gates on; the file
                // doesn't surface and a later resolve retries.
                call.respond(HttpStatusCode.NotFound, "Content not found")
                return@get
            }
            call.respondBytes(ciphertext, contentType = ContentType.Application.OctetStream)
        }
    }

    // `content-plane-refcount.md` §6e: the client DESCRIBES which blobs form one content object —
    // `contentId -> refs`. `referenceId`/`hlc` are unknown keys — a 400.
    //
    // ⚠⚠ The only form (`content-plane-cr2-b3-wire-spec.md` §3.4). There is no **edge form**
    // (holder, property, content) here: every edge arrives with the push of the row that names it,
    // HLC-stamped and in the block's transaction (`/sync/push`), so a second, unordered way to write
    // one would be a way to write an edge the log does not hold. A body carrying `edges` has an
    // unknown key, so it fails to decode — a 400.
    //
    // ⚠⚠ HR #1 intact: a description writes `content_ref` rows and **no `content_edge` row**, so the
    // relay learns which blobs form one content object and nothing about which entity or slot holds it.
    //
    // ⚠ Owner comes from the verified token, never the body — the `/sync/usage` posture.
    //
    // ⚠ Registered outside the edge guard: the describe needs only the ref store.
    post("/sync/content/declare") {
        val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@post
        // ⚠ A body that did not DECODE and a body that decoded to an empty array are different
        // client bugs, and collapsing them answers the wrong one.
        val decoded = runCatching { call.receive<ContentDeclareRequest>() }
        val request = decoded.getOrNull()
        if (request == null) {
            // ⚠ The ROOT cause, not the top one: ContentNegotiation wraps the failure as
            // "Failed to convert request body to class …", which names the type and not the field.
            // The serializer's own message is the one that says WHICH field is missing.
            val root = generateSequence(decoded.exceptionOrNull()) { it.cause }.last()
            val why = (root.message ?: "it could not be decoded").take(200)
            call.respond(HttpStatusCode.BadRequest, "Malformed declare body: $why")
            return@post
        }
        if (request.contents.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "Expected a non-empty contents array")
            return@post
        }
        if (request.contents.size > MAX_DESCRIBE_CONTENTS) {
            call.respond(
                HttpStatusCode.BadRequest,
                "At most $MAX_DESCRIBE_CONTENTS content descriptions per request",
            )
            return@post
        }
        // ⚠⚠ Loud, not silent. A relay with no ref store cannot record a description at all, and
        // answering 200 to one would tell a client its content is described when nothing on this relay
        // knows the bytes — the exact belief under which an enumeration pass later reads those bytes as
        // undescribed.
        val describeInto = contentRefs
        if (describeInto == null) {
            call.respond(HttpStatusCode.NotFound, "Content descriptions are not available")
            return@post
        }
        // ⚠⚠ Empty refs is a 400: an entry is *only* a ref set, so an empty one means `declareRefs`
        // deletes the rows it claims to be writing — a description that erases the description.
        val malformedContent = request.contents.firstOrNull { c ->
            !EDGE_ID_PATTERN.matches(c.contentId) ||
                c.refs.isEmpty() ||
                c.refs.size > MAX_REFS_PER_EDGE ||
                c.refs.any { !REF_PATTERN.matches(it) }
        }
        if (malformedContent != null) {
            call.respond(
                HttpStatusCode.BadRequest,
                "Malformed description for content '${malformedContent.contentId.take(64)}'",
            )
            return@post
        }
        // ⚠ The ceiling bounds one request's JSON and one hold on the store.
        val totalRefs = request.contents.sumOf { it.refs.size }
        if (totalRefs > MAX_TOTAL_REFS_PER_REQUEST) {
            call.respond(
                HttpStatusCode.BadRequest,
                "At most $MAX_TOTAL_REFS_PER_REQUEST refs per request",
            )
            return@post
        }

        var refsDeclared = 0
        var contentsDescribed = 0
        // ⚠⚠ `content-plane-cr2-b4-spec.md` §1.4: the freed-check and EVERY entry's write in ONE
        // transaction for the whole request. One lock section per entry would race a `free()` landing
        // between the check and the write — a description of content the relay has just committed to
        // deleting.
        val writeAll: () -> FreedRefusal? = {
            val freed = contentFreeing?.freedAmong(ownerId, request.contents.map { it.contentId }).orEmpty()
            if (freed.isNotEmpty()) {
                // ⚠ A describe is never the way out of FREED; only a revive on a push is (§10.12 Q3).
                // Refused whole: nothing in the request is written.
                FreedRefusal(FreedRefusal.CONTENT_FREED, freed.toList())
            } else {
                for (c in request.contents) {
                    describeInto.declareRefs(ownerId, c.contentId, c.refs)
                    refsDeclared += c.refs.size
                    contentsDescribed++
                }
                null
            }
        }
        // ⚠ Not `?.atomically(…) ?: writeAll()`: a success is null, and the elvis would write twice.
        val refusal = if (contentFreeing != null) contentFreeing.atomically(writeAll) else writeAll()
        if (refusal != null) {
            call.respond(HttpStatusCode.Conflict, refusal)
            return@post
        }
        call.respond(
            ContentDeclareResponse(
                refsDeclared = refsDeclared,
                contentsDescribed = contentsDescribed,
            ),
        )
    }

    if (contentEdges != null) {
        // ⭐ The inventory (`content-plane-refcount.md` §11.9/§11.11): `blobs − refs(described)`
        // for the calling owner — how many stored bytes no declaration reaches.
        //
        // ⚠⚠ **This route frees nothing and must never learn how.** Its whole value is that it can be
        // run against production with no decision attached. Freeing is a separate, separately gated
        // act.
        //
        // ⚠ An undescribed blob is NOT an orphan — four populations legitimately carry no declaration
        // (§9.3). [computeContentInventory] carries that caveat in full; do not let a caller read this
        // count as a work list.
        //
        // ⚠ Caller-scoped like `/sync/usage`: the owner is the verified-token owner, so this cannot
        // size another tenant's realm. HR #1 intact — every term is relay state the relay was told.
        get("/sync/content/inventory") {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@get
            if (contentRefs == null) {
                // ⚠ 404, not an empty report: with no ref table every blob would read as undescribed,
                // which is a maximally alarming number that means only "the inventory is not installed".
                call.respond(HttpStatusCode.NotFound, "The content inventory is not available")
                return@get
            }
            val report = computeContentInventory(ownerId, contentBlobStore, contentRefs, contentEdges)
            call.respond(
                ContentInventoryResponse(
                    blobCount = report.blobCount,
                    blobBytes = report.blobBytes,
                    zeroLengthBlobCount = report.zeroLengthBlobCount,
                    describedContentCount = report.describedContentCount,
                    candidateContentCount = report.candidateContentCount,
                    undescribedBlobCount = report.undescribedBlobCount,
                    undescribedBlobBytes = report.undescribedBlobBytes,
                    zeroLengthUndescribedBlobCount = report.zeroLengthUndescribedBlobCount,
                    recentlyTouchedBlobCount = report.recentlyTouchedBlobCount,
                    recentlyTouchedBlobBytes = report.recentlyTouchedBlobBytes,
                ),
            )
        }

        // ⭐ `content-plane-cr2-b3-wire-spec.md` §3.3: a device's REAPS — the children a
        // deleter's cascade could not name, because a peer pushed them before the deleter pulled them.
        //
        // Rule 2 per entry at the entry's `hlc` (the FOLDER tombstone's HLC, §10.12 Q4 revised): the
        // holder's watermark rises to it and its registers stamped at or below it become none. A
        // recreate stamped above it survives.
        //
        // ⚠ Idempotent by construction: every device that reaps sends the same `(holder, T)`, and the
        // rule is a max and a conditional clear. The client's outbox retries until 2xx.
        //
        // ⚠ This route removes edges only. It frees nothing (G2), and it is not a replacement for
        // the tombstone — the tombstone still carries the delete to peers.
        post("/sync/content/reap") {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@post
            val request = runCatching { call.receive<ContentReapRequest>() }.getOrNull()
            if (request == null) {
                call.respond(HttpStatusCode.BadRequest, "Malformed reap body")
                return@post
            }
            if (!relayProtocolSupported(call, request.relayProtocolVersion)) return@post
            if (request.removals.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "Expected a non-empty removals array")
                return@post
            }
            // ⚠ Rejected rather than truncated: dropping the tail would leave edges the client believes
            // it has removed.
            if (request.removals.size > MAX_REAP_REMOVALS) {
                call.respond(HttpStatusCode.BadRequest, "At most $MAX_REAP_REMOVALS removals per request")
                return@post
            }
            val parsed = request.removals.map { r -> r.holder to decodeHlcStamp(r.hlc) }
            val malformed = parsed.firstOrNull { (holder, stamp) -> !EDGE_ID_PATTERN.matches(holder) || stamp == null }
            if (malformed != null) {
                // ⚠⚠ A 400, not a skip: an unreadable stamp cannot be ordered, and guessing one would
                // either fail to remove a dead edge or remove a recreated one.
                call.respond(HttpStatusCode.BadRequest, "Malformed removal for holder '${malformed.first.take(64)}'")
                return@post
            }
            // ⚠ One transaction for the whole batch, so a retried batch is all-or-nothing.
            val cleared = contentEdges.atomically {
                parsed.sumOf { (holder, stamp) -> contentEdges.removeHolders(ownerId, listOf(holder), stamp!!) }
            }
            call.respond(ContentReapResponse(removals = parsed.size, edgesCleared = cleared))
        }
    }

    // Content-plane usage surface (`docs/proposals/retention-quota-model.md` §5 / D5).
    // Reports the **authenticated owner's own** total stored ciphertext bytes for the
    // client's `StorageStatus` gauge (`EntitlementStorageProvider`). A dedicated authed endpoint — NOT a sync-protocol
    // piggyback — so usage stays orthogonal to the sync hot path and is fetched on natural
    // triggers (usage is approximate/eventually-consistent, so on-demand is fine).
    //
    // **Strictly caller-scoped.** `ownerId` is the verified-token owner (never a
    // client-supplied value), and the only number returned is `bytesUsed(ownerId)` — the
    // byte COUNT the relay already keeps for the per-owner quota gate. There is no way to
    // query another owner's usage. House Rule #1 intact: this is a count the relay maintains
    // for accounting, not anything derived from decrypting blob contents; unauthenticated
    // requests get 401 from [JwtAuthenticator.require] (`docs/sync/threat-model.md` T-R-09).
    get("/sync/usage") {
        val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@get
        // D5 (`content-plane-cr2-b5-spec.md`): armed, the charged figure leaves out held bytes, which
        // are reported beside it.
        val held = heldBytes(ownerId)
        call.respond(
            UsageResponse(
                usedBytes = (contentBlobStore.bytesUsed(ownerId) - held).coerceAtLeast(0L),
                limitBytes = reportableQuota(call, ownerId, entitlementVerifier),
                heldBytes = held,
            ),
        )
    }
}

/**
 * The caller's own entitled byte quota, for **reporting only** — or `null` when it cannot be
 * determined (no verifier configured, no credential presented, or a credential that does not
 * verify).
 *
 * ⚠⚠ Deliberately NOT [resolveEntitlement]. That helper *rejects* — it writes a 402/403 and the
 * caller returns — which is right for a write gate and wrong here: failing to prove an entitlement
 * must leave usage readable, not turn a status read into a payment error. This function never
 * responds and never rejects.
 *
 * ⚠ Also deliberately independent of `entitlementEnforce`. Enforcement is about **refusing**
 * writes; knowing your own quota is not enforcement, and a relay running with the gate off should
 * still be able to tell a client what its tier grants — otherwise every non-enforcing deployment
 * would show "unknown" forever.
 *
 * ⚠ Owner-scoped like everything else on this route: a token asserting a different owner is
 * ignored rather than reported, so this cannot be used to peek at another owner's tier.
 */
private fun reportableQuota(
    call: ApplicationCall,
    ownerId: String,
    verifier: RelayEntitlementVerifier?,
): Long? {
    if (verifier == null) return null
    verifier.tokenHeader(call)?.let { header ->
        val result = verifier.verifyHeader(header)
        if (result is EntitlementTokenVerifier.Result.Valid) {
            val sub = result.claims.ownerId
            // ⚠ The claim, matching what `resolveEntitlement` meters against — see the note there
            // for why a token's number is authoritative while a record's is looked up. Reporting a
            // different figure from the one enforced would make the gauge disagree with the wall.
            if (sub == null || sub == ownerId) return result.claims.quotaBytes
        }
        // A token, once presented, is final — mirrors resolveEntitlement's contract, so a bad token
        // does not silently fall through to the proof header and report a different tier.
        return null
    }
    val proof = verifier.proofHeader(call) ?: return null
    val result = verifier.verifyProof(proof)
    return (result as? RelayEntitlementVerifier.ProofResult.Valid)
        ?.takeIf { it.ownerId == ownerId }
        ?.quotaBytes
}

/**
 * Reply to `GET /sync/usage`: the authenticated owner's total stored content bytes, and — when the
 * caller presents a credential the relay can verify — the byte quota that credential grants
 * (`retention-quota-model.md`, `entitlement-trust-anchor-distribution.md`).
 *
 * ⚠ [limitBytes] is nullable and additive. The client's `RelayUsageClient` decodes with
 * `ignoreUnknownKeys = true`, so a client that does not know it ignores it — no protocol-version
 * coordination is owed (HR#9).
 *
 * ⚠⚠ The limit is reported from the **caller's own credential**, because the relay keeps no
 * entitlement state of its own. A client presenting nothing gets `null`, which the client renders
 * as "unknown" rather than as zero — a 0 here would read as "you have no storage", which is a
 * different and much more alarming claim than "I could not determine your tier".
 */
@kotlinx.serialization.Serializable
internal data class UsageResponse(
    val usedBytes: Long,
    val limitBytes: Long? = null,
    /**
     * `content-plane-cr2-b5-spec.md` D5, diagnostics: the bytes the relay still stores for this owner
     * but does not charge — content UNREFERENCED (waiting out T) or freed and queued for deletion, and
     * shared with no live content. Always 0 unless the sweep is armed. Additive, like [limitBytes].
     */
    val heldBytes: Long = 0,
)

/** The quota a PUT is checked against once [held] bytes are not charged (D5); saturating, never overflowing. */
internal fun quotaWithHeld(quotaBytes: Long, held: Long): Long =
    if (held <= 0) quotaBytes else if (quotaBytes > Long.MAX_VALUE - held) Long.MAX_VALUE else quotaBytes + held

/**
 * Hard ceiling on the size of a single content blob the relay will
 * accept. Production chunks are 2 MiB plaintext + AEAD overhead; this
 * cap gives generous headroom (≈4×) for a future chunk-size bump or
 * a different AEAD primitive without forcing a relay-side change. A
 * larger payload gets 413.
 *
 * The cap and the per-owner store are orthogonal defences —
 * per-owner scoping bounds *who* can spam the store, the cap bounds
 * *how much* a single admitted device can spam per request.
 */
internal const val MAX_CONTENT_BLOB_BYTES: Int = 8 * 1024 * 1024

/**
 * The `error` code of a `507` caused by the relay-wide [StorageCeiling], as opposed to `over_quota`
 * (this owner is full). The client treats both as "storage full" today.
 */
internal const val STORAGE_CEILING_ERROR: String = "storage_ceiling"


/**
 * ⭐ The floor under `MAX_CONTENT_BLOB_BYTES`.
 *
 * Every content blob is AEAD output — **chunk and manifest alike** — so none can be shorter than one
 * empty envelope: `nonce(24) || ciphertext || tag(16)`. A shorter body is therefore not a small
 * blob, it is a **truncated** one, and storing it silently replaces real ciphertext with something
 * that can never decrypt.
 *
 * ⚠⚠ **This is the strongest check the relay can make, and the reason is structural.** The store is
 * content-addressed, so `sha256(body) == ref` looks like the obvious guard — but a *manifest's* ref
 * is `sha256("manifest:$contentId")` (`FileContentChunking.manifestContentRef`), **not** a hash of
 * its bytes, and manifests share the `blob-<64 hex>` namespace with chunk refs, which *are*
 * `sha256(ciphertext)`. The relay cannot tell the two apart, so blanket verification would reject
 * every manifest `PUT`. A length floor holds for both.
 *
 * Without it, a truncated upload stores a zero-length blob under a well-formed ref, and the real
 * ciphertext is unrecoverable.
 */
internal val MIN_CONTENT_BLOB_BYTES: Int = AeadBackend.AEAD_MIN_CIPHERTEXT_SIZE

/**
 * Refs are minted client-side as `blob-<sha256(ciphertext) hex>` per
 * the proxy's `SyncObserver.emitFileContent` and proven structurally
 * in the T-R-09 amendment (`sync/threat-model.md`). Enforcing the
 * shape here lets us reject obvious garbage early (and prevents abuse
 * of the content store as a generic blob bucket under arbitrary keys).
 */
internal val REF_PATTERN: Regex = Regex("^blob-[0-9a-f]{64}$")

/**
 * One **holder-less** description: this content object is made of these [refs]
 * (`content-plane-refcount.md` §6e).
 *
 * ⚠⚠ No `holderHash` and no `propertyId`: which entity names this content travels on the push
 * (`content-plane-cr2-b3-wire-spec.md`), never here. What this discloses is which blobs form one
 * content object, accepted at **Low** in the threat model.
 *
 * ⚠⚠ **Empty [refs] is malformed** — an entry is *only* a ref set, so an empty one describes nothing,
 * and obeying it would make the write delete the rows it claims to be writing. ⇒ 400.
 *
 * ⚠ `referenceId` / `hlc` are not declared: the JSON decoder rejects an unknown key, so a body
 * carrying them is a 400.
 */
@kotlinx.serialization.Serializable
internal data class ContentDescriptionDto(
    val contentId: String,
    val refs: List<String>,
)

/**
 * ⚠ No ownerId — it comes from the verified token, so there is no field here an attacker could aim
 * at another tenant. ⚠ No `edges` (`content-plane-cr2-b3-wire-spec.md` §3.4): a body carrying it
 * has an unknown key — a 400.
 */
@kotlinx.serialization.Serializable
internal data class ContentDeclareRequest(
    val contents: List<ContentDescriptionDto> = emptyList(),
)

/**
 * [refsDeclared] counts ref rows written; [contentsDescribed] the entries.
 */
@kotlinx.serialization.Serializable
internal data class ContentDeclareResponse(
    val refsDeclared: Int = 0,
    val contentsDescribed: Int = 0,
)

/**
 * `POST /sync/content/reap` (`content-plane-cr2-b3-wire-spec.md` §3.3). [relayProtocolVersion] is
 * required: an absent one is a malformed body (400); one outside the supported range is a 426.
 */
@kotlinx.serialization.Serializable
internal data class ContentReapRequest(
    val relayProtocolVersion: Long,
    val removals: List<ReapRemovalDto> = emptyList(),
)

/** One reap: [holder]'s wire name, removed at [hlc] (an `HlcStamp` encoding — the folder tombstone's). */
@kotlinx.serialization.Serializable
internal data class ReapRemovalDto(val holder: String, val hlc: String)

/** [edgesCleared] counts registers that went from an edge to none; a retry reports 0 and is not an error. */
@kotlinx.serialization.Serializable
internal data class ContentReapResponse(val removals: Int, val edgesCleared: Int)

/**
 * The inventory's report. ⚠ Byte figures are **lower bounds**: `zeroLength*` counts rows recording 0 bytes —
 * a stored zero-length body, which no legitimate blob is; see
 * [org.tenshu.sync.relay.mailbox.ContentBlobRegistry.blobIndex].
 */
@kotlinx.serialization.Serializable
internal data class ContentInventoryResponse(
    val blobCount: Int,
    val blobBytes: Long,
    val zeroLengthBlobCount: Int,
    val describedContentCount: Int,
    val candidateContentCount: Int,
    val undescribedBlobCount: Int,
    val undescribedBlobBytes: Long,
    val zeroLengthUndescribedBlobCount: Int,
    val recentlyTouchedBlobCount: Int,
    val recentlyTouchedBlobBytes: Long,
)

/** Batch cap for the `contents` form: bound one request's hold on the store. */
private const val MAX_DESCRIBE_CONTENTS = 512

/** One reap request's cap (`content-plane-cr2-b3-wire-spec.md` §3.3). */
internal const val MAX_REAP_REMOVALS = 1_000

/**
 * Per-edge ref ceiling. A 2 GiB file at the 2 MiB chunk size is ~1 024 chunks plus its manifest, so
 * this is roughly an 8 GiB content object — well past anything the upload path produces, and
 * a bound rather than a policy.
 */
internal const val MAX_REFS_PER_EDGE = 4_096

/**
 * Ceiling on the refs of a whole request, which the per-edge cap alone does not give.
 *
 * ⚠⚠ Without it, 512 edges × 4 096 refs is **2.1 M refs, ~150 MB of JSON in one authenticated
 * request** — a far cheaper lever on the per-owner row count than the row inflation T-R-09 already
 * names.
 *
 * 8 192 is chosen for the declaration backfill, which declares many small content objects at once: at
 * ~3 refs each that is ~2 700 per request, and a single large file's ~1 000 still fits whole.
 */
internal const val MAX_TOTAL_REFS_PER_REQUEST = 8_192

/**
 * Length bounds on the identifiers an edge carries.
 *
 * ⚠⚠ Deliberately a **length + charset** bound and not an exact-shape regex, because the two
 * directions are not symmetric: over-strict validation on *declare* rejects a real reference and
 * leaves live content looking orphaned — the loss direction — while a loose bound only lets a
 * caller waste its own rows. `refs` are the exception: `REF_PATTERN` is already normative for the
 * blob store, so a ref that does not match it can address nothing.
 *
 * `holderHash` is a 32-hex EI-1 wire id, `contentId` is a UUID or a 64-hex derived id, and a
 * pushed edge's `propertyId` is a 64-hex keyed property tag (`content-plane-cr2-b3-wire-spec.md`
 * §11), never a schema id —
 * all far inside these. ⚠ The relay never interprets any of them: they are key components only.
 */
internal const val MAX_ID_LENGTH = 256
internal val EDGE_ID_PATTERN: Regex = Regex("^[A-Za-z0-9_.:/@+~-]{1,$MAX_ID_LENGTH}$")
