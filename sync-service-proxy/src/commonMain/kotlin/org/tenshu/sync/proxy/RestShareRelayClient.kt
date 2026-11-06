package org.tenshu.sync.proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.Serializable
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.core.TContext
import org.tenshu.sync.common.ShareRelayClient

/**
 * Real Mode-A link-share client (`docs/file-sharing.md`).
 * Talks to the relay's `configureShareRouting`:
 *
 *  - [createShare]  → `POST   /share`                          (JSON descriptor)
 *  - [putChunk]     → `PUT    /share/{shareId}/chunk/{index}`  (streamed ciphertext)
 *  - [deleteShare]  → `DELETE /share/{shareId}`                (revoke)
 *
 * Sibling of [RestContentBlobStore]: chunk PUTs stream the ciphertext through an
 * [OutgoingContent.WriteChannelContent] (no second whole-block copy — the share
 * path inherits the content plane's `O(CHUNK)` bound), and every verb attaches
 * the device-key JWT as a Bearer token via [authTokenProvider]. House Rule #1
 * unchanged: the body is opaque AEAD output the relay stores without decrypting;
 * `K_share` never reaches this client (it stays in the caller's URL fragment).
 */
class RestShareRelayClient(
    baseUrl: String,
    private val httpClient: HttpClient,
    private val context: TContext,
    private val authTokenProvider: suspend () -> String?,
    /**
     * Commerce entitlement **bearer token** presented on the share *writes* (`POST /share`, chunk
     * `PUT`) via `X-Entitlement` (`commerce-billing-plane.md` §8 Phase 2c); `null` omits it. The relay
     * gates both as part of its write set, so they must carry the token once `ENTITLEMENT_ENFORCE` is
     * on. `deleteShare` (revoke) is not gated and does not call this.
     */
    private val entitlementTokenProvider: suspend () -> String? = { null },
    /**
     * Generic entitlement **proof** presented on the share writes via the separate
     * `X-Entitlement-Proof` header (`second-device-entitlement-enforcement.md` §4, Track B3) — the
     * session-less fallback (v1: `signed-record.v1`); `null` omits it.
     */
    private val entitlementProofProvider: suspend () -> String? = { null },
) : ShareRelayClient {
    // A configured relay URL may end in `/`. Built as `"$baseUrl/share/$id"`, it would send
    // `//share/<id>`: the relay still routes that, but the access-log filters' `^/share/` rules
    // miss it (docs/how-to/server-logging.md).
    private val baseUrl: String = baseUrl.trimEnd('/')

    override suspend fun createShare(descriptor: ShareRelayClient.ShareDescriptor): TResult<Data?> {
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl/share") {
                contentType(ContentType.Application.Json)
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
                entitlementTokenProvider()?.let { header("X-Entitlement", it) }
                entitlementProofProvider()?.let { header("X-Entitlement-Proof", it) }
                setBody(
                    CreateShareRequest(
                        shareId = descriptor.shareId,
                        sealedMeta = descriptor.sealedMetaBase64Url,
                        chunkCount = descriptor.chunkCount,
                        chunkHashes = descriptor.chunkHashesBase64Url,
                        expiresAtMillis = descriptor.expiresAtMillis,
                        maxOpens = descriptor.maxOpens,
                    )
                )
            }
            // 201 Created on first create; the relay treats a same-owner re-POST
            // of an identical shareId as idempotent (also 201) — either is success.
            if (response.status.isSuccess() || response.status == HttpStatusCode.Created) {
                TResult.Success.create(context, null)
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Share POST error: ${response.status}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    override suspend fun putChunk(
        shareId: String,
        chunkIndex: Int,
        ciphertext: ByteArray,
    ): TResult<Data?> {
        return try {
            val response: HttpResponse =
                httpClient.put("$baseUrl/share/$shareId/chunk/$chunkIndex") {
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
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Share chunk PUT error: ${response.status}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    override suspend fun deleteShare(shareId: String): TResult<Data?> {
        return try {
            val response: HttpResponse = httpClient.delete("$baseUrl/share/$shareId") {
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
            }
            // 204 No Content on success; 404 means absent or owned-by-someone-else
            // (existence-leak-proof). Both the local Eager `revoked` flip already
            // happened — a 404 here is non-fatal (nothing left to revoke at the
            // relay) but surfaced as a failure so the caller can log it.
            if (response.status.isSuccess() ||
                response.status == HttpStatusCode.NoContent
            ) {
                TResult.Success.create(context, null)
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Share DELETE error: ${response.status}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    /** Streams the chunk ciphertext without an intermediate serializer copy. */
    private class ChunkBody(private val bytes: ByteArray) : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType = ContentType.Application.OctetStream
        override val contentLength: Long = bytes.size.toLong()

        override suspend fun writeTo(channel: ByteWriteChannel) {
            channel.writeFully(bytes, 0, bytes.size)
        }
    }

    /**
     * Wire shape of `POST /share`, matching `ShareRouting.CreateShareRequest`
     * exactly (field names + nullability). Kept private here — the public
     * surface is [ShareRelayClient.ShareDescriptor]; this is the JSON projection.
     */
    @Serializable
    private data class CreateShareRequest(
        val shareId: String,
        val sealedMeta: String,
        val chunkCount: Int,
        val chunkHashes: List<String> = emptyList(),
        val expiresAtMillis: Long? = null,
        val maxOpens: Int? = null,
    )
}
