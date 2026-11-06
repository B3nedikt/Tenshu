package app.tenshu.control.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.identity.EntitlementSignature
import org.tenshu.storage.proxy.EntitlementMintClient
import kotlin.io.encoding.Base64

// The [EntitlementMintClient] interface and the in-process [DevSeedEntitlementMintClient]
// were relocated to `:storage-service-proxy` in web-storage-status.md Bucket 3 so the web
// apex can mint a default-free entitlement on genesis. The off-device commerce minter
// below stays here — it is ktor / commerce-specific and the web doesn't use it in v1.

/**
 * The off-device minter: POSTs to `:commerce-service`'s `/commerce/entitlement/mint` and returns
 * the signed entitlement it issues (signed with the commerce issuer key, `tenshu.commerce`). This
 * is the production path — no signing key ever lives on-device.
 *
 * The mint endpoint is **session-gated** (`commerce-accounts-and-sessions.md` §4.3): the request
 * carries only a commerce `sessionToken`, and the service derives the owner + product from the
 * verified account. So the [EntitlementMintClient] interface's `email`/`productId` arguments are
 * **ignored here** — the account, not the caller, decides them. [sessionTokenProvider] supplies the
 * token; a `null`/blank token short-circuits to `null` (no request). Any non-200 (401 no session,
 * 403 no entitlement, 4xx/5xx, transport failure) yields `null` — the writer no-ops, leaving any
 * existing entitlement untouched.
 *
 * In Phase 3a the provider returns `null` (no device-side session flow yet), so this is a **dormant
 * but contract-correct** client; Phase 3b supplies a real session source.
 */
class CommerceEntitlementMintClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val sessionTokenProvider: () -> String?,
) : EntitlementMintClient {

    override suspend fun mint(
        ownerId: String,
        email: String?,
        productId: String,
    ): EntitlementSignature.SignedEntitlement? {
        val sessionToken = sessionTokenProvider()?.takeIf { it.isNotBlank() } ?: return null

        val response: HttpResponse = runCatchingCancellable {
            httpClient.post("${baseUrl.trimEnd('/')}$MINT_PATH") {
                contentType(ContentType.Application.Json)
                setBody(MintRequest(sessionToken = sessionToken))
            }
        }.getOrNull() ?: return null

        if (response.status != HttpStatusCode.OK) return null

        val minted = runCatching { response.body<MintedEntitlementResponse>() }.getOrNull()
            ?: return null
        val signature = runCatching { Base64.Default.decode(minted.publisherSignature) }.getOrNull()
            ?: return null

        return EntitlementSignature.SignedEntitlement(
            ownerId = minted.ownerId,
            publisherId = minted.publisherId,
            productId = minted.productId,
            validFrom = minted.validFrom,
            validUntil = minted.validUntil,
            signature = signature,
        )
    }

    private companion object {
        const val MINT_PATH = "/commerce/entitlement/mint"
    }
}

/** Mint request wire shape — mirrors `:commerce-service`'s session-gated `MintRequest` JSON. */
@Serializable
private data class MintRequest(
    val sessionToken: String,
)

/** Mint response wire shape — mirrors `:commerce-service`'s `MintedEntitlement` JSON. */
@Serializable
private data class MintedEntitlementResponse(
    val ownerId: String,
    val publisherId: String,
    val productId: String,
    val validFrom: Long,
    val validUntil: Long,
    val publisherSignature: String,
)
