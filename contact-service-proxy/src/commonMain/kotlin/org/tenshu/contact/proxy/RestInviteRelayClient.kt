package org.tenshu.contact.proxy

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import org.tenshu.contact.common.InviteConflictException
import org.tenshu.contact.common.InviteGoneException
import org.tenshu.contact.common.InviteRelayClient
import org.tenshu.contact.common.InviteRelayException
import org.tenshu.contact.common.InviteUnknownException

/**
 * Production [InviteRelayClient] against the relay's `configureInviteRouting`
 * (`contact-invitation-handshake.md` §6). Sibling of `RestShareRelayClient`:
 * the four inviter-side verbs attach the device-key JWT via
 * [authTokenProvider] (the `jwtTokenProvider` seam from `PlatformProxyModule`
 * — personal-realm `ownerId`); the invitee-side verbs deliberately send **no**
 * credential, keeping the redeemer protocol-anonymous (T-CI-06).
 */
@OptIn(ExperimentalEncodingApi::class)
class RestInviteRelayClient(
    baseUrl: String,
    private val httpClient: HttpClient,
    private val authTokenProvider: suspend () -> String?,
) : InviteRelayClient {
    // A configured relay URL may end in `/`. Built as `"$baseUrl/share/$id"`, it would send
    // `//share/<id>`: the relay still routes that, but the access-log filters' `^/share/` rules
    // miss it (docs/how-to/server-logging.md).
    private val baseUrl: String = baseUrl.trimEnd('/')

    override suspend fun registerInvite(inviteId: String, expiresAtMillis: Long): Long {
        val response = request("register invite") {
            httpClient.post("$baseUrl/sync/invite") {
                contentType(ContentType.Application.Json)
                authHeader()
                setBody(RegisterInviteRequest(inviteId = inviteId, expiresAtMillis = expiresAtMillis))
            }
        }
        throwForStatus(response, "register invite")
        return response.body<RegisterInviteResponse>().expiresAtMillis
    }

    override suspend fun revokeInvite(inviteId: String) {
        val response = request("revoke invite") {
            httpClient.delete("$baseUrl/sync/invite/$inviteId") { authHeader() }
        }
        // 404 = already gone / never registered — revocation is idempotent.
        if (response.status == HttpStatusCode.NotFound) return
        throwForStatus(response, "revoke invite")
    }

    override suspend fun postResponse(inviteId: String, sealedResponse: ByteArray) {
        val response = request("post invite response") {
            httpClient.post("$baseUrl/sync/invite/response") {
                contentType(ContentType.Application.Json)
                setBody(
                    PostInviteResponseRequest(
                        inviteId = inviteId,
                        sealedResponse = Base64.encode(sealedResponse),
                    )
                )
            }
        }
        throwForStatus(response, "post invite response")
    }

    override suspend fun pendingResponses(): List<InviteRelayClient.PendingResponse> {
        val response = request("fetch invite responses") {
            httpClient.get("$baseUrl/sync/invite/responses") { authHeader() }
        }
        throwForStatus(response, "fetch invite responses")
        return response.body<PendingInviteResponses>().responses.map {
            InviteRelayClient.PendingResponse(
                inviteId = it.inviteId,
                sealedResponse = Base64.decode(it.sealedResponse),
            )
        }
    }

    override suspend fun postAck(inviteId: String, sealedAck: ByteArray) {
        val response = request("post invite ack") {
            httpClient.post("$baseUrl/sync/invite/ack") {
                contentType(ContentType.Application.Json)
                authHeader()
                setBody(PostInviteAckRequest(inviteId = inviteId, sealedAck = Base64.encode(sealedAck)))
            }
        }
        throwForStatus(response, "post invite ack")
    }

    override suspend fun fetchAck(inviteId: String): InviteRelayClient.AckPoll {
        val response = request("fetch invite ack") {
            httpClient.get("$baseUrl/sync/invite/ack") { parameter("inviteId", inviteId) }
        }
        return when {
            response.status == HttpStatusCode.NotFound -> InviteRelayClient.AckPoll.NotYet
            response.status == HttpStatusCode.Gone -> InviteRelayClient.AckPoll.Gone
            response.status.isSuccess() ->
                InviteRelayClient.AckPoll.Found(Base64.decode(response.body<InviteAckResponse>().sealedAck))

            else -> throw InviteRelayException("fetch invite ack failed: ${response.status}")
        }
    }

    override suspend fun done(inviteId: String) {
        val response = request("invite done") {
            httpClient.post("$baseUrl/sync/invite/done") {
                contentType(ContentType.Application.Json)
                setBody(InviteDoneRequest(inviteId = inviteId))
            }
        }
        throwForStatus(response, "invite done")
    }

    private suspend fun io.ktor.client.request.HttpRequestBuilder.authHeader() {
        authTokenProvider()?.let { header("Authorization", "Bearer $it") }
    }

    private suspend fun request(what: String, block: suspend () -> HttpResponse): HttpResponse =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw InviteRelayException("$what: transport failure", t)
        }

    private fun throwForStatus(response: HttpResponse, what: String) {
        when {
            response.status.isSuccess() -> Unit
            response.status == HttpStatusCode.NotFound ->
                throw InviteUnknownException("$what: unknown invite")

            response.status == HttpStatusCode.Gone ->
                throw InviteGoneException("$what: invite expired or revoked")

            response.status == HttpStatusCode.Conflict ->
                throw InviteConflictException("$what: conflict")

            else -> throw InviteRelayException("$what failed: ${response.status}")
        }
    }
}

// Wire envelopes — mirror `InviteRouting`'s DTOs (`:sync-relay-server`).

@Serializable
internal data class RegisterInviteRequest(
    val inviteId: String,
    val expiresAtMillis: Long? = null,
)

@Serializable
internal data class RegisterInviteResponse(
    val inviteId: String,
    val expiresAtMillis: Long,
)

@Serializable
internal data class PostInviteResponseRequest(
    val inviteId: String,
    val sealedResponse: String,
)

@Serializable
internal data class PendingInviteResponse(
    val inviteId: String,
    val sealedResponse: String,
)

@Serializable
internal data class PendingInviteResponses(
    val responses: List<PendingInviteResponse>,
)

@Serializable
internal data class PostInviteAckRequest(
    val inviteId: String,
    val sealedAck: String,
)

@Serializable
internal data class InviteAckResponse(
    val inviteId: String,
    val sealedAck: String,
)

@Serializable
internal data class InviteDoneRequest(
    val inviteId: String,
)
