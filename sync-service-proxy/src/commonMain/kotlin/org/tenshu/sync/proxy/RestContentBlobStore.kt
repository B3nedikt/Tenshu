package org.tenshu.sync.proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import kotlinx.io.readByteArray
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.core.TContext
import org.tenshu.core.toTByteArray
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.ContentDescription
import org.tenshu.sync.common.ContentRefusal
import org.tenshu.sync.common.DescribeOutcome
import org.tenshu.sync.common.ReapRemoval
import org.tenshu.core.common.RELAY_PROTOCOL_VERSION

/**
 * Real content-plane client for the two-plane file-content design
 * (`docs/adr/0017-file-content-two-plane-channel.md`). Streams one
 * already-AEAD-encrypted chunk to / from the relay's content store
 * under an opaque ref via `PUT` / `GET /sync/content/{ref}`.
 *
 * **Streamed, not buffered.** The PUT body is written through a
 * [OutgoingContent.WriteChannelContent] and the GET body read through a
 * streamed [bodyAsChannel] — neither goes through
 * `BinarySerializer.serialize` / `toByteArray` (the doubling
 * `ByteStringBuilder` + final copy that was a multiplier in the
 * confirmed Control OOM). The only resident copy is the single
 * `CHUNK`-sized ciphertext, so both directions stay `O(CHUNK)`. One
 * chunk *is* the memory unit; reading that one chunk into a single
 * `ByteArray` is the read bound (no whole-file / multi-chunk buffer).
 *
 * House Rule #1 unchanged: the ciphertext is opaque AEAD output; the
 * relay stores and serves it without decrypting, addressed only by the
 * `blob-<sha256(ciphertext)>` ref. All decrypt + integrity-verify
 * happens client-side in the manifest applier. The relay-side store and
 * the `/sync/content` routes shipped over a durable SQLite + object-store
 * backend. (The "ref-counted GC" once planned alongside them is
 * House-Rule-#1-impossible and was superseded — reclamation is owner- and
 * ref-scoped; see `retention-quota-model.md`.) An older relay without
 * `/sync/content` still 404/501s; emit treats a non-2xx PUT as a
 * failed chunk and aborts the manifest, and [getChunk] returns a
 * [TResult.Failure] the reader gates on (file does not surface; a later
 * resolve retries — content blobs are content-addressed + immutable so
 * retry is idempotent). The desktop round-trip proof uses the in-memory
 * `ContentBlobStore`; production end-to-end depends on the P3 relay
 * store accepting `PUT`/`GET`.
 */
class RestContentBlobStore(
    baseUrl: String,
    private val httpClient: HttpClient,
    private val context: TContext,
    private val authTokenProvider: suspend () -> String?,
    /**
     * Commerce entitlement **bearer token** presented on the content `PUT` (write) via `X-Entitlement`
     * (`commerce-billing-plane.md` §8 Phase 2c); `null` omits the header. The sibling `GET` is a read
     * and never calls this — a download must not depend on a commerce token fetch (§9 Q5).
     */
    private val entitlementTokenProvider: suspend () -> String? = { null },
    /**
     * Generic entitlement **proof** presented on the content `PUT` via the separate
     * `X-Entitlement-Proof` header (`second-device-entitlement-enforcement.md` §4, Track B3) — the
     * session-less fallback (v1: `signed-record.v1`); `null` omits it.
     */
    private val entitlementProofProvider: suspend () -> String? = { null },
) : ContentBlobStore {
    // A configured relay URL may end in `/`. Built as `"$baseUrl/share/$id"`, it would send
    // `//share/<id>`: the relay still routes that, but the access-log filters' `^/share/` rules
    // miss it (docs/how-to/server-logging.md).
    private val baseUrl: String = baseUrl.trimEnd('/')

    override suspend fun putChunk(ref: String, ciphertext: ByteArray): TResult<Data?> {
        return try {
            val response: HttpResponse = httpClient.put("$baseUrl/sync/content/$ref") {
                contentType(ContentType.Application.OctetStream)
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
                entitlementTokenProvider()?.let { header("X-Entitlement", it) }
                entitlementProofProvider()?.let { header("X-Entitlement-Proof", it) }
                setBody(ChunkBody(ciphertext))
            }
            if (response.status.isSuccess() ||
                response.status == HttpStatusCode.Created ||
                response.status == HttpStatusCode.NoContent
            ) {
                TResult.Success.create(context, null)
            } else if (response.status == HttpStatusCode.PaymentRequired) {
                // Entitlement enforcement (second-device-entitlement-enforcement.md §1): tag the
                // 402 with ENTITLEMENT_REJECTION_MARKER so callers can recognise a doomed write.
                TResult.Failure.create(
                    context,
                    GenericError.create(
                        context,
                        "$ENTITLEMENT_REJECTION_MARKER: content PUT rejected (${response.status})"
                    )
                )
            } else if (response.status == HttpStatusCode.InsufficientStorage) {
                // Per-owner quota (commerce-billing-plane.md §8 Phase 2b): the owner is at their
                // tier ceiling, so this blob cannot land until bytes are freed or the tier rises.
                //
                // ⚠⚠ Tagged for the same reason the 402 above is, and its absence was the bug:
                // an untagged 507 is indistinguishable from a transient network failure, so the
                // content drain retried the whole journal every tick forever. See
                // [STORAGE_REJECTION_MARKER] for the measured storm.
                //
                // ⚠ The relay reads the whole body *before* checking quota, so each doomed attempt
                // also uploads a full chunk — the storm costs bandwidth, not just CPU.
                TResult.Failure.create(
                    context,
                    GenericError.create(
                        context,
                        "$STORAGE_REJECTION_MARKER: content PUT rejected (${response.status})"
                    )
                )
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "$CONTENT_PUT_ERROR_PREFIX ${response.status}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    /**
     * `POST /sync/content/declare`, describe form only (CR-2 B3, `content-plane-cr2-b3-wire-spec.md`
     * §3.4). The edge form and `/sync/content/undeclare` are deleted at the relay; edges travel with
     * each holder's push.
     *
     * ⚠ Authorization only; no entitlement header. A description is accounting, not storage: an
     * expired account must still be able to say what its bytes are made of.
     *
     * ⚠⚠ Any non-2xx and any transport failure is `false`, never fatal to the caller and never success.
     */
    override suspend fun describeContent(contents: List<ContentDescription>): Boolean =
        describe(contents) == DescribeOutcome.Described

    /**
     * ⭐ CR-2 B4 (§1.4, §2.1): a `409 content_freed` names every freed id of the batch, and the relay
     * wrote **nothing** of that batch. So the batch is resent once without them, and the outcome is
     * [DescribeOutcome.Refused] only when everything else landed — the caller then treats the freed ids
     * as final (a describe is never the way out of FREED) and the rest as described.
     */
    override suspend fun describe(contents: List<ContentDescription>): DescribeOutcome {
        if (contents.isEmpty()) return DescribeOutcome.Described
        return try {
            val freed = mutableListOf<String>()
            for (batch in contents.chunked(ContentBlobStore.MAX_DESCRIBE_BATCH)) {
                when (val refusal = postDescribe(batch)) {
                    null -> Unit
                    is ContentRefusal.ContentFreed -> {
                        freed += refusal.contentIds
                        val rest = batch.filter { it.contentId !in refusal.contentIds }
                        // ⚠ A second refusal of the same batch is a relay that moved on meanwhile (a free
                        // between the two); report the batch failed and let the caller's retry run.
                        if (rest.isNotEmpty() && postDescribe(rest) != null) return DescribeOutcome.Failed
                    }
                    else -> return DescribeOutcome.Failed
                }
            }
            if (freed.isEmpty()) DescribeOutcome.Described else DescribeOutcome.Refused(freed.distinct())
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            DescribeOutcome.Failed
        }
    }

    /**
     * One describe request: null when accepted, the refusal when the relay answered B4's 409, and a
     * [DescribeRequestFailed] throw for anything else.
     */
    private suspend fun postDescribe(batch: List<ContentDescription>): ContentRefusal? {
        val response: HttpResponse = httpClient.post("$baseUrl/sync/content/declare") {
            contentType(ContentType.Application.Json)
            authTokenProvider()?.let { header("Authorization", "Bearer $it") }
            setBody(
                ContentDeclareRequestDto(
                    contents = batch.map {
                        ContentDescriptionDto(it.contentId, it.refs)
                    },
                )
            )
        }
        if (response.status.isSuccess()) return null
        if (response.status == HttpStatusCode.Conflict) response.contentRefusal()?.let { return it }
        throw DescribeRequestFailed(response.status.value)
    }

    private class DescribeRequestFailed(status: Int) : RuntimeException("describe refused with $status")

    /**
     * `POST /sync/content/reap` (CR-2 B3, wire spec §3.3), at most [ContentBlobStore.MAX_REAP_BATCH]
     * removals per request and stamped with [RELAY_PROTOCOL_VERSION] for the relay's 426 gate.
     *
     * ⚠ Authorization only, like the describe: a removal frees nothing by itself and only ever
     * shrinks what the owner is charged for.
     */
    override suspend fun reapRemovals(removals: List<ReapRemoval>): Boolean {
        if (removals.isEmpty()) return true
        return try {
            removals.chunked(ContentBlobStore.MAX_REAP_BATCH).all { batch ->
                val response: HttpResponse = httpClient.post("$baseUrl/sync/content/reap") {
                    contentType(ContentType.Application.Json)
                    authTokenProvider()?.let { header("Authorization", "Bearer $it") }
                    setBody(
                        ContentReapRequestDto(
                            relayProtocolVersion = RELAY_PROTOCOL_VERSION,
                            removals = batch.map { ReapRemovalDto(it.holder, it.hlc) },
                        )
                    )
                }
                response.status.isSuccess()
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            false
        }
    }

    override suspend fun getChunk(ref: String): TResult<TByteArray> {
        return try {
            // prepareGet + execute keeps the body streamed and releases
            // the connection promptly. One chunk IS the memory unit, so
            // reading exactly that chunk's bytes is the O(CHUNK) bound —
            // there is no whole-file / multi-chunk response to buffer
            // (selective fetch: this GET pulls only this one ref).
            httpClient.prepareGet("$baseUrl/sync/content/$ref") {
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
            }.execute { response: HttpResponse ->
                if (!response.status.isSuccess()) {
                    return@execute TResult.Failure.create<TByteArray>(
                        context,
                        GenericError.create(
                            context,
                            "Content GET error for $ref: ${response.status}"
                        )
                    )
                }
                val bytes = response.bodyAsChannel().readRemaining().readByteArray()
                TResult.Success.create(context, bytes.toTByteArray(context))
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    /**
     * A `GET` whose body is never read: the status is the whole answer, and leaving `execute` without
     * consuming the body drops the rest of the response. 2xx is present, `404` absent (the relay's
     * answer for a ref it does not hold, `ContentRouting`), anything else unknown.
     */
    override suspend fun hasChunk(ref: String): Boolean? {
        return try {
            httpClient.prepareGet("$baseUrl/sync/content/$ref") {
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
            }.execute { response: HttpResponse ->
                when {
                    response.status.isSuccess() -> true
                    response.status == HttpStatusCode.NotFound -> false
                    else -> null
                }
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Streams the chunk ciphertext to the wire without an intermediate
     * serializer/builder copy. `length` is set so the relay can stream
     * to its content-addressed store too (no relay-side full buffer
     * needed either).
     */
    private class ChunkBody(private val bytes: ByteArray) : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.Application.OctetStream
        override val contentLength: Long = bytes.size.toLong()

        override suspend fun writeTo(channel: ByteWriteChannel) {
            // One CHUNK is already the memory bound; write it straight
            // through. Sliced writes here would not lower the bound
            // (the caller holds the chunk) and only add overhead.
            channel.writeFully(bytes, 0, bytes.size)
        }
    }
}

/** The describe form of `/sync/content/declare` — the only form left after CR-2 B3. */
@kotlinx.serialization.Serializable
internal data class ContentDeclareRequestDto(
    val contents: List<ContentDescriptionDto> = emptyList(),
)

/**
 * One describe entry: a content and its refs. ⚠ CR-2 B6: no `referenceId` (nor its `hlc` stamp) — the
 * relay holds no reference handles, and since the 2026-10 reset rejects either as an unknown key (400).
 */
@kotlinx.serialization.Serializable
internal data class ContentDescriptionDto(
    val contentId: String,
    val refs: List<String>,
)

/** Body of `POST /sync/content/reap` (wire spec §3.3). ⚠ No ownerId — the relay takes it from the token. */
@kotlinx.serialization.Serializable
internal data class ContentReapRequestDto(
    val relayProtocolVersion: Int,
    val removals: List<ReapRemovalDto>,
)

/** One removal: [holder]'s edges at or below [hlc] (an `HlcStamp` string) go. */
@kotlinx.serialization.Serializable
internal data class ReapRemovalDto(val holder: String, val hlc: String)
