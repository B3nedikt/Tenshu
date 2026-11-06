package org.tenshu.epc.rendezvous

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

/** The two halves of the duplex EPC rendezvous channel (matches the relay's `Direction.wire`). */
enum class RendezvousDirection(val wire: String) {
    CLIENT_TO_PROXY("c2p"),
    PROXY_TO_CLIENT("p2c"),
}

/** One frame fetched from a rendezvous mailbox: the relay-assigned [seq] + opaque [payload]. */
class RendezvousFrame(val seq: Long, val payload: ByteArray)

/** Outcome of a directional fetch. */
sealed interface RendezvousFetch {
    /** Frames after the requested cursor (possibly empty), plus the cursor to pass next. */
    class Frames(val frames: List<RendezvousFrame>, val nextSince: Long) : RendezvousFetch

    /** No live session at this id (not provisioned, or expired). */
    data object NoSession : RendezvousFetch
}

/** A transport-level failure talking to the rendezvous mailbox. */
class RendezvousRelayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Client for the relay's EPC rendezvous mailbox (`configureEpcRendezvousRouting`,
 * `ios-proxy-architecture.md` §5, Phase 3). Sibling of `RestInviteRelayClient`:
 * the JWT-gated [provision]/[teardown] attach the device-key JWT via
 * [authTokenProvider] (the proxy, a realm device); the anonymous [append]/[fetch]
 * carry no credential — the high-entropy session id is the capability.
 *
 * It moves opaque bytes only; the EPC handshake + session framing live above it
 * ([EpcRendezvousChannel] on the app side, [EpcRendezvousAcceptor] on the
 * proxy side).
 */
@OptIn(ExperimentalEncodingApi::class)
class RendezvousRelayClient(
    baseUrl: String,
    private val httpClient: HttpClient,
    private val authTokenProvider: suspend () -> String?,
) {
    // A configured relay URL may end in `/`. Built as `"$baseUrl/share/$id"`, it would send
    // `//share/<id>`: the relay still routes that, but the access-log filters' `^/share/` rules
    // miss it (docs/how-to/server-logging.md).
    private val baseUrl: String = baseUrl.trimEnd('/')

    /** Outcome of [provision]. */
    enum class ProvisionOutcome { PROVISIONED, CAP_EXCEEDED, OWNER_MISMATCH }

    /** Provision (or refresh) a session — JWT-gated; the realm-device admission point. */
    suspend fun provision(sessionId: String): ProvisionOutcome {
        val response = request("provision EPC session") {
            httpClient.post("$baseUrl/sync/epc/$sessionId") { authHeader() }
        }
        return when {
            response.status.isSuccess() -> ProvisionOutcome.PROVISIONED
            response.status == HttpStatusCode.TooManyRequests -> ProvisionOutcome.CAP_EXCEEDED
            response.status == HttpStatusCode.Conflict -> ProvisionOutcome.OWNER_MISMATCH
            else -> throw RendezvousRelayException("provision EPC session failed: ${response.status}")
        }
    }

    /** Tear down a session the caller owns — JWT-gated; idempotent (404 is success). */
    suspend fun teardown(sessionId: String) {
        val response = request("teardown EPC session") {
            httpClient.delete("$baseUrl/sync/epc/$sessionId") { authHeader() }
        }
        if (response.status == HttpStatusCode.NotFound) return
        if (!response.status.isSuccess()) {
            throw RendezvousRelayException("teardown EPC session failed: ${response.status}")
        }
    }

    /** Append a sealed [frame] to [direction] — anonymous; throws if the session is gone. */
    suspend fun append(sessionId: String, direction: RendezvousDirection, frame: ByteArray) {
        val response = request("append EPC frame") {
            httpClient.post("$baseUrl/sync/epc/$sessionId/${direction.wire}") {
                contentType(ContentType.Application.Json)
                setBody(PostFrameRequest(frame = Base64.encode(frame)))
            }
        }
        when {
            response.status.isSuccess() -> Unit
            response.status == HttpStatusCode.NotFound ->
                throw RendezvousRelayException("append EPC frame: no such session")
            else -> throw RendezvousRelayException("append EPC frame failed: ${response.status}")
        }
    }

    /** Fetch frames of [direction] with `seq > sinceSeq`, in order — anonymous. */
    suspend fun fetch(sessionId: String, direction: RendezvousDirection, sinceSeq: Long): RendezvousFetch {
        val response = request("fetch EPC frames") {
            httpClient.get("$baseUrl/sync/epc/$sessionId/${direction.wire}") { parameter("since", sinceSeq) }
        }
        return when {
            response.status == HttpStatusCode.NotFound -> RendezvousFetch.NoSession
            response.status.isSuccess() -> {
                val body = response.body<FetchFramesResponse>()
                RendezvousFetch.Frames(
                    frames = body.frames.map { RendezvousFrame(it.seq, Base64.decode(it.frame)) },
                    nextSince = body.nextSince,
                )
            }
            else -> throw RendezvousRelayException("fetch EPC frames failed: ${response.status}")
        }
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
            throw RendezvousRelayException("$what: transport failure", t)
        }
}

// Wire envelopes — mirror `EpcRendezvousRouting`'s DTOs (`:sync-relay-server`).

@Serializable
internal data class PostFrameRequest(val frame: String)

@Serializable
internal data class FrameDto(val seq: Long, val frame: String)

@Serializable
internal data class FetchFramesResponse(val frames: List<FrameDto>, val nextSince: Long)
