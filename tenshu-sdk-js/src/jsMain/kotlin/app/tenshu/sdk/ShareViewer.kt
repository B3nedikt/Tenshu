@file:OptIn(ExperimentalJsExport::class, ExperimentalEncodingApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.TransportFailureMapping
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.serialization.Serializable
import org.tenshu.core.common.security.DefaultSyncCryptoManager
import org.tenshu.core.common.security.NobleAeadBackend
import org.tenshu.core.common.security.NobleEd25519Backend
import org.tenshu.core.common.security.NobleKdfBackend
import org.tenshu.core.common.security.NobleX25519Backend
import org.tenshu.sync.common.ShareMeta
import org.tenshu.sync.common.ShareMetaMalformedException
import org.tenshu.sync.common.openShareMeta
import org.tenshu.sync.common.shareChunkAad

/**
 * Mode-A **recipient** surface (`docs/file-sharing.md` /
 * §6.5). A single call that fetches a shared file from the relay and returns
 * its decrypted bytes for the web viewer to render.
 *
 * **Standalone by design.** A recipient clicks a link without any Tenshu
 * session — so [ShareViewer] does NOT touch [app.tenshu.sdk.internal.JsRuntime]
 * (no realm, no device, no JWT). It constructs its own anonymous [HttpClient]
 * and its own [DefaultSyncCryptoManager] (only the AEAD backend is exercised),
 * and hits the relay's anonymous `GET /share/...` endpoints. `K_share` arrives
 * in the URL fragment the page already parsed and is passed in here — it never
 * goes on the wire (A11); the relay only ever served ciphertext.
 *
 * **Integrity.** Each chunk is AEAD-decrypted under `K_share` with AAD
 * `(shareId, chunkIndex, chunkCount)` ([shareChunkAad]) — the same construction
 * the sharer used. A tampering / wrong-chunk-serving relay fails decryption
 * (surfaced as [ShareFetchResult.errorKind] `"decrypt"`); a reordered or
 * cross-share chunk fails the AAD check; a missing chunk reads 410 → `"gone"`.
 * This is the v1 integrity boundary (proposal §5.5); the descriptor's
 * `chunkHashes` anchor is not populated in v1 (see `ShareLinkImplementation`).
 *
 * **Metadata (F3).** The descriptor carries no clear file name, MIME type or
 * size: they arrive in `sealedMeta`, which this opens under `K_share`
 * ([openShareMeta]) *before* fetching any chunk. A wrong key fails there
 * (`"decrypt"`); a descriptor with no or malformed `sealedMeta` fails as
 * `"meta"`. The viewer never falls back to a blank or placeholder name. The
 * decrypted byte count must equal the sealed size, else `"decrypt"`.
 */
@JsExport
object ShareViewer {

    /**
     * Fetch + decrypt the share. [kShareBase64Url] is the `#k=` fragment value
     * (base64url, no padding); [relayBaseUrl] is the relay origin the apex is
     * configured against (e.g. `https://relay.tenshu.app`).
     *
     * Never rejects for an expected condition: a revoked/expired/absent share
     * resolves to `{ ok: false, errorKind: "gone" }`, a transport failure to
     * `"network"`, a decryption failure (wrong key included) to `"decrypt"`, and
     * a descriptor without readable sealed metadata to `"meta"` — so the React
     * route can branch on `errorKind` instead of catching. The Promise only
     * rejects on a genuinely unexpected (programming) error.
     */
    fun fetch(
        shareId: String,
        kShareBase64Url: String,
        relayBaseUrl: String,
    ): Promise<ShareFetchResult> = GlobalScope.promise {
        fetchWith(httpClient, shareId, kShareBase64Url, relayBaseUrl.trimEnd('/'))
    }

    /** [fetch] over an injected [client] — the seam `ShareViewerTest` drives with a mock engine. */
    internal suspend fun fetchWith(
        client: HttpClient,
        shareId: String,
        kShareBase64Url: String,
        relayBaseUrl: String,
    ): ShareFetchResult {
        val kShare = try {
            decodeBase64Url(kShareBase64Url)
        } catch (e: Throwable) {
            return ShareFetchResult.error("decrypt")
        }

        val descriptor: ShareDescriptorDto = try {
            val response = client.get("$relayBaseUrl/share/$shareId")
            if (response.status == HttpStatusCode.Gone) return ShareFetchResult.error("gone")
            if (!response.status.isOk()) return ShareFetchResult.error("network")
            response.body()
        } catch (e: Throwable) {
            return ShareFetchResult.error("network")
        }

        val crypto = cryptoManager
        val sealed = try {
            decodeBase64Url(descriptor.sealedMeta)
        } catch (e: Throwable) {
            return ShareFetchResult.error("meta")
        }
        val meta: ShareMeta = try {
            openShareMeta(crypto, kShare, shareId, descriptor.chunkCount, sealed)
        } catch (e: ShareMetaMalformedException) {
            return ShareFetchResult.error("meta")
        } catch (e: Throwable) {
            // Wrong / truncated key, tampered blob, or a (shareId, chunkCount) the blob was not
            // sealed for.
            return ShareFetchResult.error("decrypt")
        }

        val parts = ArrayList<ByteArray>(descriptor.chunkCount)
        var index = 0
        while (index < descriptor.chunkCount) {
            val cipher: ByteArray = try {
                val response = client.get("$relayBaseUrl/share/$shareId/chunk/$index")
                if (response.status == HttpStatusCode.Gone) return ShareFetchResult.error("gone")
                if (!response.status.isOk()) return ShareFetchResult.error("network")
                response.body()
            } catch (e: Throwable) {
                return ShareFetchResult.error("network")
            }
            val aad = shareChunkAad(shareId, index, descriptor.chunkCount)
            val plain = try {
                crypto.decryptWithAad(cipher, kShare, aad)
            } catch (e: Throwable) {
                return ShareFetchResult.error("decrypt")
            }
            parts += plain
            index++
        }

        val bytes = concat(parts)
        if (bytes.size.toLong() != meta.size) return ShareFetchResult.error("decrypt")
        return ShareFetchResult.ok(
            bytes = bytes,
            fileName = meta.fileName,
            mimeType = meta.mimeType,
        )
    }

    private fun HttpStatusCode.isOk(): Boolean = value in 200..299

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64Url(s: String): ByteArray {
        // RFC 4648 §5 base64url, padding omitted by the URL-fragment producer.
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        return Base64.UrlSafe.decode(padded)
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    // Single shared anonymous HTTP client — the JS engine reuses the browser's
    // `fetch`, so pooling is the browser's job; ContentNegotiation parses the
    // descriptor JSON.
    private val httpClient: HttpClient by lazy {
        HttpClient(Js) {
            install(ContentNegotiation) { json() }
            install(TransportFailureMapping)
        }
    }

    // Only the AEAD backend is exercised (decryptWithAad), but the production
    // crypto manager wires all four backends — mirror it so the construction
    // is identical to the in-realm path.
    private val cryptoManager by lazy {
        DefaultSyncCryptoManager(
            ed25519 = NobleEd25519Backend(),
            x25519 = NobleX25519Backend(),
            aead = NobleAeadBackend(),
            kdf = NobleKdfBackend(),
        )
    }
}

/**
 * Result of [ShareViewer.fetch]. Discriminated by [ok] so the JS caller can
 * branch without catching: on success [bytes] / [fileName] / [mimeType] are
 * set; on failure [errorKind] is one of `"gone"` / `"network"` / `"decrypt"` /
 * `"meta"` (the descriptor carries no readable sealed metadata — F3).
 */
@JsExport
class ShareFetchResult internal constructor(
    val ok: Boolean,
    val errorKind: String?,
    val bytes: ByteArray?,
    val fileName: String?,
    val mimeType: String?,
) {
    internal companion object {
        fun ok(bytes: ByteArray, fileName: String, mimeType: String) =
            ShareFetchResult(true, null, bytes, fileName, mimeType)

        fun error(kind: String) =
            ShareFetchResult(false, kind, null, null, null)
    }
}

/** The relay's `GET /share/{shareId}` body (`ShareRouting.ShareDescriptor`). */
@Serializable
private data class ShareDescriptorDto(
    val shareId: String,
    /** Absent only from a malformed descriptor — opens as `"meta"`, never as a blank name. */
    val sealedMeta: String = "",
    val chunkCount: Int,
    val chunkHashes: List<String> = emptyList(),
    val expiresAtMillis: Long? = null,
    val opensRemaining: Int? = null,
)
