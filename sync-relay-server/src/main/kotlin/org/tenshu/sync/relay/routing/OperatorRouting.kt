package org.tenshu.sync.relay.routing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.tenshu.sync.relay.mailbox.SHARE_ID_SHAPE
import org.tenshu.sync.relay.mailbox.ShareRegistry
import org.tenshu.sync.relay.mailbox.TakedownReason
import org.tenshu.sync.relay.storage.ContentReclaimJob
import org.tenshu.sync.relay.storage.ContentSweepJob
import java.net.InetAddress
import java.security.MessageDigest

/**
 * **Operator routes**: `/ops/content-reclaim/{plan,enqueue,drain}`. They drive [ContentReclaimJob],
 * the one-off reclamation of ciphertext no content describes
 * (`docs/how-to/relay-content-reclaim.md`). And `GET /ops/content-sweep/status`
 * (`content-plane-cr2-b5-spec.md` §1.5), read-only: the sweep's mode, T, interval, last run and totals.
 * It sits behind the same brakes, because what the relay is about to free is the operator's business only.
 * And `POST /ops/share/takedown`: notice-and-action on one share link by `shareId`
 * (`docs/how-to/take-down-a-share-link.md`, acceptable-use §3) — revoke, audit row, delete its chunks.
 *
 * ⚠⚠ **They can permanently delete user ciphertext**, so two brakes are required, and a missing
 * brake is not a softer answer but an absent route:
 * - **Registered only with `RELAY_OPERATOR_TOKEN` set** (and a durable store). Otherwise a probe gets
 *   a plain 404, like the account-closure route.
 * - **Loopback only, judged by the raw socket peer** (`request.local`), never by `request.origin`.
 *   `XForwardedHeaders` is installed, so `origin` would believe an `X-Forwarded-For: 127.0.0.1` sent
 *   through the public proxy. The operator calls these from inside the container.
 * - **And the token**, compared in constant time.
 *
 * The job runs **in this process** because its safety rests on the store's in-process lock (a drain
 * deletes bytes while holding it); a separate tool process could not serialise with live PUTs.
 */
fun Route.configureOperatorRouting(
    token: String?,
    job: ContentReclaimJob?,
    isLoopbackPeer: (ApplicationCall) -> Boolean = ::rawPeerIsLoopback,
    now: () -> Long = System::currentTimeMillis,
    sweep: ContentSweepJob? = null,
    shares: ShareRegistry? = null,
) {
    if (token.isNullOrBlank()) return
    val expected = token.encodeToByteArray()

    suspend fun RoutingContext.authorised(): Boolean {
        val presented = call.request.headers[OPERATOR_TOKEN_HEADER]?.encodeToByteArray()
        if (!isLoopbackPeer(call) || presented == null || !MessageDigest.isEqual(presented, expected)) {
            // ⚠ One answer for every refusal — which brake failed is nobody's business.
            call.respond(HttpStatusCode.NotFound)
            return false
        }
        return true
    }

    // ⚠ Registered without a durable store too: it is read-only, and it is how an operator sees that an
    // in-memory relay frees logically only (`durableDrain: false`).
    if (sweep != null) {
        get("/ops/content-sweep/status") {
            if (!authorised()) return@get
            call.respond(sweep.status())
        }
    }

    // Share takedown. Registered over the in-memory share store too (dev relays): a takedown there is
    // as real as the store is. The body, not the query string, carries the note, so it never reaches
    // an access log.
    if (shares != null) {
        post("/ops/share/takedown") {
            if (!authorised()) return@post
            val body = runCatching { call.receive<TakedownRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, OpError("body must be {shareId, reason, note?}"))
            val shareId = body.shareId.takeIf { SHARE_ID_SHAPE.matches(it) }
                ?: return@post call.respond(HttpStatusCode.BadRequest, OpError("shareId must be the id alone (base64url), never the URL or its #k= key"))
            val reason = TakedownReason.fromCode(body.reason)
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    OpError("reason must be one of ${TakedownReason.entries.joinToString { it.code }}"),
                )
            val note = body.note?.trim()?.takeIf { it.isNotEmpty() }
            if (note != null && (note.length > MAX_NOTE_CHARS || note.any { it.isISOControl() })) {
                return@post call.respond(HttpStatusCode.BadRequest, OpError("note: one line, at most $MAX_NOTE_CHARS characters"))
            }
            val result = shares.takeDown(shareId, reason, note, now())
            call.respond(
                TakedownResponse(
                    auditId = result.record.id,
                    shareId = result.record.shareId,
                    outcome = result.record.outcome.code,
                    ownerId = result.record.ownerId,
                    takenDownAtMs = result.record.takenDownAtMs,
                    reason = result.record.reason.code,
                    bytesDeleted = result.bytesDeleted,
                    bytesDeleteCompleted = result.bytesDeleteCompleted,
                ),
            )
        }
    }

    // The reclaim routes delete, so they exist only over SQLite + a BlobStore.
    if (job == null) return

    fun RoutingContext.owner(): String? = call.request.queryParameters["owner"]?.takeIf { it.isNotBlank() }
    /**
     * The idle window in ms, or null when the parameter is invalid. Zero or negative
     * would make an upload in progress a candidate, and a huge value could overflow into one. Bounded
     * to `[1 h, 1 year]`, and rejected rather than clamped: a destructive call never runs on a guess.
     */
    fun RoutingContext.minIdleMs(): Long? {
        val raw = call.request.queryParameters["minIdleHours"] ?: return DEFAULT_MIN_IDLE_HOURS * 3_600_000L
        val hours = raw.toLongOrNull()?.takeIf { it in 1..MAX_MIN_IDLE_HOURS } ?: return null
        return hours * 3_600_000L
    }

    get("/ops/content-reclaim/plan") {
        if (!authorised()) return@get
        val owner = owner() ?: return@get call.respond(HttpStatusCode.BadRequest, OpError("owner required"))
        val idle = minIdleMs() ?: return@get call.respond(HttpStatusCode.BadRequest, OpError(IDLE_ERROR))
        call.respond(job.plan(owner, idle, now()))
    }

    post("/ops/content-reclaim/enqueue") {
        if (!authorised()) return@post
        val owner = owner() ?: return@post call.respond(HttpStatusCode.BadRequest, OpError("owner required"))
        // ⚠ Required, never defaulted: it is the operator's proof of having read THIS plan's set.
        val expect = call.request.queryParameters["expectDigest"]?.takeIf { it.isNotBlank() }
            ?: return@post call.respond(HttpStatusCode.BadRequest, OpError("expectDigest required"))
        val idle = minIdleMs() ?: return@post call.respond(HttpStatusCode.BadRequest, OpError(IDLE_ERROR))
        try {
            call.respond(job.enqueue(owner, idle, now(), expect))
        } catch (e: ContentReclaimJob.Refused) {
            call.respond(HttpStatusCode.Conflict, OpError(e.message ?: "refused"))
        }
    }

    post("/ops/content-reclaim/drain") {
        if (!authorised()) return@post
        val owner = owner() ?: return@post call.respond(HttpStatusCode.BadRequest, OpError("owner required"))
        val batches = call.request.queryParameters["maxBatches"]?.toIntOrNull() ?: DEFAULT_MAX_BATCHES
        call.respond(job.drain(owner, maxBatches = batches))
    }
}

@Serializable
private data class OpError(val error: String)

@Serializable
private data class TakedownRequest(val shareId: String, val reason: String, val note: String? = null)

/**
 * `outcome` is `taken_down`, `already_taken_down` or `not_found` — always 200, because the bodyless
 * 404 is reserved for a refused caller and an operator must be able to tell the two apart.
 * `ownerId` is what repeat-infringer handling and the owner's statement of reasons start from.
 */
@Serializable
private data class TakedownResponse(
    val auditId: Long,
    val shareId: String,
    val outcome: String,
    val ownerId: String?,
    val takenDownAtMs: Long,
    val reason: String,
    val bytesDeleted: Long,
    val bytesDeleteCompleted: Boolean,
)


private const val MAX_NOTE_CHARS = 500

/** The socket's own peer: unaffected by any forwarded header. */
private fun rawPeerIsLoopback(call: ApplicationCall): Boolean =
    runCatching { InetAddress.getByName(call.request.local.remoteAddress).isLoopbackAddress }.getOrDefault(false)

const val OPERATOR_TOKEN_HEADER = "X-Operator-Token"

/** Untouched for a day before an undescribed blob is a candidate: a content uploads its chunks before it is described. */
private const val DEFAULT_MIN_IDLE_HOURS = 24L

private const val MAX_MIN_IDLE_HOURS = 24L * 365

private const val IDLE_ERROR = "minIdleHours must be a whole number of hours in 1..8760"

/** One drain call does at most this many batches, so a call returns and reports progress. */
private const val DEFAULT_MAX_BATCHES = 50
