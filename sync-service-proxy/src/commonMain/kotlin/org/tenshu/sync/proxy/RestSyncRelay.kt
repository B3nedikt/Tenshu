package org.tenshu.sync.proxy

import org.tenshu.core.common.RelayRefusal
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.RELAY_PROTOCOL_VERSION
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.DataId
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTString
import org.tenshu.core.common.content.ContentEdge
import org.tenshu.sync.CascadedTombstonePush
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.EdgedPush
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.PushedEdge
import org.tenshu.sync.ReviveEntry
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncLogResponse
import org.tenshu.sync.TombstonesResponse
import org.tenshu.sync.common.MemberDeviceBinding
import org.tenshu.sync.common.MembershipCapability
import org.tenshu.sync.common.MembershipRevocation
import org.tenshu.sync.common.PushOutcome
import org.tenshu.sync.common.RevivedContent
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.WriteProbe
import kotlin.io.encoding.Base64

class RestSyncRelay(
    baseUrl: String,
    private val httpClient: HttpClient,
    private val context: TContext,
    private val authTokenProvider: suspend () -> String?,
    private val ownerIdProvider: suspend () -> String?,
    /**
     * Supplies the commerce entitlement **bearer token** presented on relay **writes** via
     * `X-Entitlement` (`commerce-billing-plane.md` §8 Phase 2c), alongside the device-JWT
     * `Authorization`. Returns `null` when no token is available (mock/unconfigured, or no bound
     * account) — the header is then omitted, which the relay tolerates until `ENTITLEMENT_ENFORCE` is
     * on. Only the *gated* writes call it (`/sync/push`, grant drop); reads never do, so a read never
     * blocks on a token fetch.
     */
    private val entitlementTokenProvider: suspend () -> String? = { null },
    /**
     * Supplies the generic entitlement **proof** presented on writes via the *separate*
     * `X-Entitlement-Proof` header (`second-device-entitlement-enforcement.md` §4) — a
     * `<type>.v<n>:<payload>` value (v1: `signed-record.v1`) a session-less device presents when it
     * has no bearer token. `null` omits it. Independent of [entitlementTokenProvider]; the relay
     * prefers a present token, so a device with a session simply doesn't need this.
     */
    private val entitlementProofProvider: suspend () -> String? = { null },
) : SyncRelay {
    // A configured relay URL may end in `/`. Built as `"$baseUrl/share/$id"`, it would send
    // `//share/<id>`: the relay still routes that, but the access-log filters' `^/share/` rules
    // miss it (docs/how-to/server-logging.md).
    private val baseUrl: String = baseUrl.trimEnd('/')

    private val serializer = BinarySerializer()
    private val parser = BinaryParser(
        context.schemaProvider(),
        context.dataFactory()
    )

    override suspend fun probeWrite(): WriteProbe = try {
        // ⚠ Bounded: the fence waits on this, and a user is waiting on the fence. A relay that does
        // not answer in time is a relay a write would not reach either.
        kotlinx.coroutines.withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            val response: HttpResponse = httpClient.post("$baseUrl/sync/push/probe") {
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
                entitlementTokenProvider()?.let { header("X-Entitlement", it) }
                entitlementProofProvider()?.let { header("X-Entitlement-Proof", it) }
            }
            when {
                response.status.isSuccess() -> WriteProbe.ACCEPTED
                // An older relay without the route: could not ask, NOT refused.
                response.status == HttpStatusCode.NotFound -> WriteProbe.UNSUPPORTED
                else -> WriteProbe.REFUSED
            }
        } ?: WriteProbe.REFUSED
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        WriteProbe.REFUSED
    }

    /**
     * `POST /sync/push` with an `EdgedPush` body (`content-plane-cr2-b3-wire-spec.md` §3.1):
     * the block, its row's [edges] and the protocol version the relay's per-request 426 gate reads.
     *
     * ⚠⚠ The body is serialised HERE, inside the call, and that is load-bearing: a queued block is
     * lazily backed and its row is deleted once the drain retires it (`RetiredBlockDisposal`).
     */
    override suspend fun pushBlock(block: SyncBlock, edges: List<ContentEdge>): TResult<Data?> =
        when (val outcome = pushEdged(block, edges)) {
            PushOutcome.Accepted -> TResult.Success.create(context, null)
            is PushOutcome.Failed -> TResult.Failure.create(context, outcome.error)
            // ⚠ The ids survive whole in the message for a caller that only logs (the iOS probes); every
            // drain that can resolve a refusal calls [pushEdged] and gets them typed.
            is PushOutcome.Refused -> TResult.Failure.create(
                context,
                GenericError.create(context, "$CONTENT_REFUSAL_MARKER: push refused (${outcome.refusal})"),
            )
        }

    /**
     * ⭐ The push with its [revive] entries (`content-plane-cr2-b4-spec.md` §0, §2.1), and a
     * `409` answered with a content-refusal body parsed into [PushOutcome.Refused] from the JSON, never through
     * [statusWithReason], which truncates the body at 200 characters.
     */
    override suspend fun pushEdged(
        block: SyncBlock,
        edges: List<ContentEdge>,
        revive: List<RevivedContent>,
    ): PushOutcome {
        // ⚠⚠ The send-side guard for the keyed property tag (B3 wire spec §11): every drain tags its
        // edges with `ContentPropertyTagger` first, and a schema property id must never reach the relay.
        // A caller that forgot fails here, loudly, and its block stays queued.
        if (edges.any { !ContentPropertyTag.looksLikeTag(it.propertyId) }) {
            return PushOutcome.Failed(
                GenericError.create(context, "push refused locally: an edge carries an untagged property id")
            )
        }
        return try {
            val body = serializer.serialize(
                EdgedPush.create(
                    context = context,
                    relayProtocolVersion = RELAY_PROTOCOL_VERSION,
                    block = block,
                    edges = edges.map { PushedEdge.create(context, it.propertyId, it.contentId, it.regenerable) },
                    revive = revive.map { ReviveEntry.create(context, it.contentId, it.refs) },
                )
            )
            val response: HttpResponse = httpClient.post("$baseUrl/sync/push") {
                contentType(ContentType.Application.OctetStream)
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
                entitlementTokenProvider()?.let { header("X-Entitlement", it) }
                entitlementProofProvider()?.let { header("X-Entitlement-Proof", it) }
                setBody(body)
            }
            if (response.status.isSuccess()) {
                PushOutcome.Accepted
            } else if (response.status == HttpStatusCode.Conflict) {
                // ⚠ A 409 without a content-refusal body (an older relay, a proxy) is an ordinary
                // failure: the block stays queued.
                response.contentRefusal()?.let { PushOutcome.Refused(it) }
                    ?: PushOutcome.Failed(GenericError.create(context, "Server error: ${response.statusWithReason()}"))
            } else if (response.status == HttpStatusCode.PaymentRequired) {
                // Entitlement enforcement (second-device-entitlement-enforcement.md §1). Tag the
                // error with ENTITLEMENT_REJECTION_MARKER so the scheduler backs off (a doomed
                // write) instead of hammering, rather than treating it as a transient failure.
                PushOutcome.Failed(
                    GenericError.create(
                        context,
                        "$ENTITLEMENT_REJECTION_MARKER: push rejected (${response.statusWithReason()})"
                    )
                )
            } else {
                PushOutcome.Failed(GenericError.create(context, "Server error: ${response.statusWithReason()}"))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            PushOutcome.Failed(GenericError.create(context, e.message ?: "Network error"))
        }
    }

    override suspend fun getChanges(
        sinceTimestamp: Long,
        tip: SignedHlcTip
    ): TResult<ChangesResponse> {
        return try {
            // POST instead of GET because the body carries the signed tip
            // (sync-backend-relay-plan.md §5.1). Some intermediaries strip GET bodies.
            val response: HttpResponse = httpClient.post("$baseUrl/sync/changes") {
                contentType(ContentType.Application.OctetStream)
                parameter("since", sinceTimestamp)
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
                setBody(serializer.serialize(tip))
            }
            if (response.status.isSuccess()) {
                val bytes: ByteArray = response.body()
                val changesResponse = parser.parse<ChangesResponse>(bytes)
                TResult.Success.create(context, changesResponse)
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Server error: ${response.statusWithReason()}")
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
     * The merged lane (`sync-lane-cursor-split.md` L3). Same auth + signed-tip contract as
     * [getChanges]; one ordered page covering both kinds.
     *
     * ⚠ **A bare 404 here means the relay predates the route**, not that anything is wrong.
     * `/sync/log` is purely additive, so an older deployment simply has no handler and Ktor
     * answers with its unmatched-route 404. Reporting that as
     * [SyncRelay.SyncLogFetch.RelayUnsupported] rather than an error is what lets
     * `SyncScheduler` fall back to the two-lane path *without* scoring the tick as offline —
     * a working relay must never be reported unreachable merely for being older than the
     * client.
     */
    override suspend fun getSyncLog(
        sinceTimestamp: Long,
        tip: SignedHlcTip
    ): SyncRelay.SyncLogFetch {
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl$SYNC_LOG_ROUTE") {
                contentType(ContentType.Application.OctetStream)
                parameter("since", sinceTimestamp)
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
                setBody(serializer.serialize(tip))
            }
            when {
                response.status.isSuccess() ->
                    SyncRelay.SyncLogFetch.Found(parser.parse<SyncLogResponse>(response.body()))

                response.status == HttpStatusCode.NotFound ->
                    SyncRelay.SyncLogFetch.RelayUnsupported

                else -> SyncRelay.SyncLogFetch.Error("Server error: ${response.statusWithReason()}")
            }
        } catch (e: Exception) {
            SyncRelay.SyncLogFetch.Error(e.message ?: "Network error")
        }
    }

    /**
     * The backfill page (C2) — the same `/sync/log` route with an added `before=`. ⚠ Additive by
     * construction: a client that never sends the parameter is served exactly as before, which is
     * what keeps this off `RELAY_PROTOCOL_VERSION`.
     */
    override suspend fun getSyncLogBefore(
        beforeSeq: Long,
        tip: SignedHlcTip
    ): SyncRelay.SyncLogFetch {
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl$SYNC_LOG_ROUTE") {
                contentType(ContentType.Application.OctetStream)
                parameter("before", beforeSeq)
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
                setBody(serializer.serialize(tip))
            }
            when {
                response.status.isSuccess() ->
                    SyncRelay.SyncLogFetch.Found(parser.parse<SyncLogResponse>(response.body()))

                response.status == HttpStatusCode.NotFound ->
                    SyncRelay.SyncLogFetch.RelayUnsupported

                else -> SyncRelay.SyncLogFetch.Error("Server error: ${response.statusWithReason()}")
            }
        } catch (e: Exception) {
            SyncRelay.SyncLogFetch.Error(e.message ?: "Network error")
        }
    }

    /**
     * `POST /sync/push-tombstone` with a `CascadedTombstonePush` body (`content-plane-cr2-b3-wire-spec.md` §3.2): the
     * tombstone and the wire names of the subtree its recursive delete removed.
     */
    override suspend fun pushTombstone(block: DeleteBlock, cascade: List<String>): TResult<Data?> {
        return try {
            val body = serializer.serialize(
                CascadedTombstonePush.create(
                    context = context,
                    relayProtocolVersion = RELAY_PROTOCOL_VERSION,
                    block = block,
                    cascade = cascade,
                )
            )
            val response: HttpResponse = httpClient.post("$baseUrl/sync/push-tombstone") {
                contentType(ContentType.Application.OctetStream)
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
                setBody(body)
            }
            if (response.status.isSuccess()) {
                // RH-1a α: the relay answers with the sequence it assigned this tombstone
                // (`sync-retention-horizons.md` §3.5). ⚠ A relay older than this client answers the
                // literal "OK", so a non-numeric body is a normal outcome, not an error — it means
                // only that this candidate stays uncomparable and keeps waiting on the tip.
                val assignedSeq = response.bodyAsText().trim().toLongOrNull()
                TResult.Success.create(context, assignedSeq?.toTInteger(context))
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Server error: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    override suspend fun getTombstones(sinceTimestamp: Long): TResult<TombstonesResponse> {
        return try {
            val response: HttpResponse = httpClient.get("$baseUrl/sync/tombstones") {
                parameter("since", sinceTimestamp)
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status.isSuccess()) {
                val bytes: ByteArray = response.body()
                val tombstonesResponse = parser.parse<TombstonesResponse>(bytes)
                TResult.Success.create(context, tombstonesResponse)
            } else if (response.status == HttpStatusCode.NoContent) {
                // No-content response: keep the cursor where it was so the next pull
                // doesn't skip anything that might have been pushed mid-call.
                TResult.Success.create(
                    context,
                    TombstonesResponse.create(
                        context = context,
                        tombstones = emptyList(),
                        nextCursor = sinceTimestamp
                    )
                )
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Server error: ${response.statusWithReason()}")
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
     * `POST /sync/fetch-entity-blocks` with the entity's wire id as a UTF-8 text body, compared
     * verbatim by the relay (B3 wire spec §11.3). ⚠ No `DataId` crosses: it carried the path.
     */
    override suspend fun fetchEntityBlocks(
        entityWireId: String
    ): TResult<TList<SyncBlock>> {
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl/sync/fetch-entity-blocks") {
                contentType(ContentType.Text.Plain)
                ownerIdProvider()?.let { header("X-Owner-Id", it) }
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
                setBody(entityWireId)
            }
            if (response.status.isSuccess()) {
                val bytes: ByteArray = response.body()
                if (bytes.isEmpty()) {
                    return TResult.Success.create(
                        context,
                        context.dataFactory().createList<SyncBlock>(emptyList())
                    )
                }
                @Suppress("UNCHECKED_CAST")
                val blocks = parser.parse<TList<SyncBlock>>(bytes)
                TResult.Success.create(context, blocks)
            } else if (response.status == HttpStatusCode.NotFound) {
                TResult.Success.create(
                    context,
                    context.dataFactory().createList<SyncBlock>(emptyList())
                )
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Server error: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    override suspend fun postPairingRequest(
        ownerId: String,
        pairingSecretHash: String,
        deviceId: String,
        sealedDeviceName: ByteArray,
        publicKey: ByteArray,
        signingPublicKey: ByteArray
    ): TResult<Data?> {
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl/sync/pairing/request") {
                contentType(ContentType.Application.Json)
                // F7: the hash of the secret and the sealed name — never either in clear.
                setBody(
                    mapOf(
                        "ownerId" to ownerId,
                        "pairingSecretHash" to pairingSecretHash,
                        "deviceId" to deviceId,
                        "sealedDeviceName" to Base64.encode(sealedDeviceName),
                        "publicKey" to Base64.encode(publicKey),
                        "signingPublicKey" to Base64.encode(signingPublicKey)
                    )
                )
            }
            if (response.status.isSuccess()) {
                TResult.Success.create(context, null)
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Pairing failed: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    override suspend fun getPairingRequests(ownerId: String): SyncRelay.PairingRequestsFetch {
        return try {
            val response: HttpResponse = httpClient.get("$baseUrl/sync/pairing/requests") {
                parameter("ownerId", ownerId)
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status.isSuccess()) {
                val body: List<PairingRequestReplyDto> = response.body()
                SyncRelay.PairingRequestsFetch.Found(
                    body.map {
                        SyncRelay.RelayPairingRequest(
                            deviceId = it.deviceId,
                            sealedDeviceName = Base64.decode(it.sealedDeviceName),
                            publicKey = Base64.decode(it.publicKey),
                            signingPublicKey = Base64.decode(it.signingPublicKey),
                            pairingSecretHash = it.pairingSecretHash,
                        )
                    }
                )
            } else {
                SyncRelay.PairingRequestsFetch.Failed(
                    GenericError.create(context, "Fetch failed: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            SyncRelay.PairingRequestsFetch.Failed(
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    override suspend fun postPairingResponse(
        pairingSecretHash: String,
        encryptedMasterKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        masterDelegation: ByteArray,
        epoch: Long,
        admissionCertChain: ByteArray?,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl/sync/pairing/response") {
                contentType(ContentType.Application.Json)
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
                setBody(
                    buildMap {
                        put("pairingSecretHash", pairingSecretHash)
                        put("encryptedMasterKey", Base64.encode(encryptedMasterKey))
                        put("newDevicePublicKey", Base64.encode(newDevicePublicKey))
                        put("newDeviceId", newDeviceId)
                        put("masterDelegation", Base64.encode(masterDelegation))
                        put("epoch", epoch.toString())
                        // Delegated admitter (P3): present the A33 capability chain so the
                        // relay verifies the chain instead of an A1 master signature.
                        if (admissionCertChain != null) {
                            put("admissionCertChain", Base64.encode(admissionCertChain))
                        }
                        // When this admission was authored, so the relay can refuse a `Device`
                        // tombstone that predates it (DeviceRegistry.revokeIfNotStale).
                        if (admittedAtHlc != null) put("admittedAtHlc", admittedAtHlc)
                    }
                )
            }
            if (response.status.isSuccess()) {
                TResult.Success.create(context, null)
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Response failed: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    override suspend fun revokeAdmission(
        ownerId: String,
        deviceId: String,
        epoch: Long,
        revokedAt: Long,
        signature: ByteArray,
        deviceA33PublicKey: ByteArray,
    ): TResult<Data?> {
        // Unauthenticated — the master signature is the authorisation (P2 posture).
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl/sync/admission/revoke") {
                contentType(ContentType.Application.Json)
                setBody(
                    RevokeAdmissionBody(
                        ownerId = ownerId,
                        deviceId = deviceId,
                        epoch = epoch,
                        revokedAt = revokedAt,
                        signature = Base64.encode(signature),
                        // F1′; required since F5b — the relay 400s a keyless body.
                        deviceA33PublicKey = Base64.encode(deviceA33PublicKey),
                    )
                )
            }
            if (response.status.isSuccess()) {
                TResult.Success.create(context, null)
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Admission revoke failed: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    override suspend fun ackPairingResponse(pairingSecretHash: String): TResult<Data?> {
        // The secret's hash is the capability — no JWT.
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl/sync/pairing/ack") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("pairingSecretHash" to pairingSecretHash))
            }
            if (response.status.isSuccess()) {
                TResult.Success.create(context, null)
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Ack failed: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    override suspend fun getPairingResponse(pairingSecretHash: String): TResult<Data?> {
        return try {
            // A POST so the lookup key rides the body: a query string or path segment reaches
            // access logs, and this key is the mailbox capability (F4/F7).
            val response: HttpResponse = httpClient.post("$baseUrl/sync/pairing/response/peek") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("pairingSecretHash" to pairingSecretHash))
            }
            if (response.status.isSuccess()) {
                val bytes: ByteArray = response.body()
                TResult.Success.create(context, TByteArrayDto(bytes))
            } else if (response.status == HttpStatusCode.NotFound) {
                TResult.Success.create(context, null)
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Fetch failed: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    override suspend fun postOfferResponse(
        ownerId: String,
        responderDeviceId: String,
        pairingSecretHash: String,
        status: String,
        publicKeyA: ByteArray,
        payload: ByteArray?,
        hostSignature: ByteArray,
        offererDeviceId: String?,
        offererSigningKey: ByteArray?,
        masterDelegation: ByteArray?,
        epoch: Long?,
        admissionCertChain: ByteArray?,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl/sync/pairing/offer-response") {
                contentType(ContentType.Application.Json)
                // Unauthenticated by JWT — the hostSignature (verified against the
                // responder's registered device key) is the capability, mirroring the
                // pairing-secret posture of the join-flow response POST.
                setBody(
                    buildMap {
                        put("ownerId", ownerId)
                        put("responderDeviceId", responderDeviceId)
                        put("pairingSecretHash", pairingSecretHash)
                        put("status", status)
                        put("publicKeyA", Base64.encode(publicKeyA))
                        put("hostSignature", Base64.encode(hostSignature))
                        payload?.let { put("payload", Base64.encode(it)) }
                        offererDeviceId?.let { put("offererDeviceId", it) }
                        offererSigningKey?.let { put("offererSigningKey", Base64.encode(it)) }
                        masterDelegation?.let { put("masterDelegation", Base64.encode(it)) }
                        epoch?.let { put("epoch", it.toString()) }
                        admissionCertChain?.let { put("admissionCertChain", Base64.encode(it)) }
                        // When this admission was authored — the relay orders `Device` tombstones
                        // against it (DeviceRegistry.revokeIfNotStale).
                        admittedAtHlc?.let { put("admittedAtHlc", it) }
                    }
                )
            }
            if (response.status.isSuccess()) {
                TResult.Success.create(context, null)
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Offer-response failed: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    override suspend fun getOfferResponse(pairingSecretHash: String): SyncRelay.OfferResponseFetch {
        return try {
            val response: HttpResponse = httpClient.get("$baseUrl/sync/pairing/offer-response") {
                parameter("hash", pairingSecretHash)
            }
            when {
                response.status.isSuccess() -> {
                    val body: OfferResponseReplyDto = response.body()
                    SyncRelay.OfferResponseFetch.Found(
                        ownerId = body.ownerId,
                        status = body.status,
                        publicKeyA = Base64.decode(body.publicKeyA),
                        payload = body.payload?.let { Base64.decode(it) },
                    )
                }
                response.status == HttpStatusCode.NotFound ->
                    SyncRelay.OfferResponseFetch.NotFound
                else ->
                    SyncRelay.OfferResponseFetch.Error("Offer-response fetch failed: ${response.statusWithReason()}")
            }
        } catch (e: Exception) {
            SyncRelay.OfferResponseFetch.Error(e.message ?: "Network error")
        }
    }

    override suspend fun postHubOffer(
        pairingSecretHash: String,
        ephemeralPublicKey: ByteArray,
        deviceId: String,
        sealedDeviceName: ByteArray,
        signature: ByteArray,
    ): TResult<Data?> {
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl/sync/pairing/hub-offer") {
                contentType(ContentType.Application.Json)
                // Unauthenticated by design — the hub has no relay credentials yet.
                // Public material + a signature only; the phone verifies the signature
                // against the PRINTED key, so the relay stores this opaquely (H2 §11.3).
                setBody(
                    mapOf(
                        "pairingSecretHash" to pairingSecretHash,
                        "ephemeralPublicKey" to Base64.encode(ephemeralPublicKey),
                        "deviceId" to deviceId,
                        "sealedDeviceName" to Base64.encode(sealedDeviceName),
                        "signature" to Base64.encode(signature),
                    )
                )
            }
            when {
                response.status.isSuccess() -> TResult.Success.create(context, null)
                // The mailbox route itself never 404s — a 404 here is Ktor's unmatched-route
                // reply from a relay that predates hub pairing. Surface it distinctly.
                response.status == HttpStatusCode.NotFound -> TResult.Failure.create(
                    context,
                    GenericError.create(
                        context,
                        "${org.tenshu.sync.OfferHubPairing.ERROR_RELAY_UNSUPPORTED}: " +
                            "the relay does not support hub pairing yet",
                    )
                )
                else -> TResult.Failure.create(
                    context,
                    GenericError.create(context, "Hub-offer post failed: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    override suspend fun getHubOffer(pairingSecretHash: String): SyncRelay.HubOfferFetch {
        return try {
            // The hash rides the BODY, never the URI: it is the hub's long-lived rendezvous key
            // (threat model T-P-17), the `/response/peek` precedent.
            val response: HttpResponse = httpClient.post("$baseUrl/sync/pairing/hub-offer/fetch") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("pairingSecretHash" to pairingSecretHash))
            }
            when {
                response.status.isSuccess() -> {
                    val body: HubOfferReplyDto = response.body()
                    SyncRelay.HubOfferFetch.Found(
                        ephemeralPublicKey = Base64.decode(body.ephemeralPublicKey),
                        deviceId = body.deviceId,
                        sealedDeviceName = Base64.decode(body.sealedDeviceName),
                        signature = Base64.decode(body.signature),
                    )
                }
                response.status == HttpStatusCode.NotFound -> {
                    // A supporting relay marks its empty-mailbox 404 with a body marker;
                    // a bare 404 is an old relay's unmatched-route reply.
                    val marker: String = try {
                        response.body()
                    } catch (_: Exception) {
                        ""
                    }
                    if (marker == HUB_OFFER_NONE_MARKER) SyncRelay.HubOfferFetch.NotFound
                    else SyncRelay.HubOfferFetch.RelayUnsupported
                }
                else -> SyncRelay.HubOfferFetch.Error("Hub-offer fetch failed: ${response.statusWithReason()}")
            }
        } catch (e: Exception) {
            SyncRelay.HubOfferFetch.Error(e.message ?: "Network error")
        }
    }

    override suspend fun createRealm(
        ownerId: String,
        masterPublicKey: ByteArray,
        firstDevicePublicKey: ByteArray,
        firstDeviceId: String,
        masterSelfSignature: ByteArray,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        // Unauthenticated by JWT — the realm doesn't exist yet and the master self-signature
        // in the body is the credential. See sync-relay-api.md §3.2.
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl/sync/realm") {
                contentType(ContentType.Application.Json)
                setBody(
                    mapOf(
                        "ownerId" to ownerId,
                        "masterPublicKey" to Base64.encode(masterPublicKey),
                        "firstDevicePublicKey" to Base64.encode(firstDevicePublicKey),
                        "firstDeviceId" to firstDeviceId,
                        "masterSelfSignature" to Base64.encode(masterSelfSignature),
                        // The relay's 426 gate (core-schema-evolution.md §7.5/§7.8): an
                        // E2e relay accepts a RANGE of `relayProtocolVersion`s and ignores
                        // payload/schema versions entirely.
                        "relayProtocolVersion" to RELAY_PROTOCOL_VERSION.toString(),
                    ) + buildMap {
                        // Genesis is also the route `reassertGenesis` re-runs to repair a lost
                        // registration, so it is a re-admission like any other: stamp it so a
                        // `Device` tombstone authored earlier cannot revoke it.
                        admittedAtHlc?.let { put("admittedAtHlc", it) }
                    }
                )
            }
            // NB: a 409 (realm already exists) stays a Failure here on purpose — it is
            // load-bearing for `ReregisterDeviceImplementation`, which relies on
            // createRealm failing to fall back to `admitDevice` (a non-first device's
            // 409 does NOT mean that device is registered). RealmRegistrar avoids the
            // 409 entirely via its persistent once-ever latch (SyncManager.realmRelayRegistered).
            if (response.status.isSuccess()) {
                TResult.Success.create(context, null)
            } else {
                // ⚠ Marker, not a status-text match. `409 Conflict` reads as `409 ` over HTTP/2
                // (no reason phrase), so a status-text detector never fires. The consumer is [RealmRegistrar.ensureRegistered].
                val prefix = when (response.status) {
                    HttpStatusCode.Conflict -> "$REALM_EXISTS_REJECTION_MARKER: "
                    HttpStatusCode.Gone -> "$REALM_ERASED_REJECTION_MARKER: "
                    else -> ""
                }
                TResult.Failure.create(
                    context,
                    GenericError.create(
                        context,
                        "${prefix}Realm creation failed: ${response.statusWithReason()}",
                    )
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    override suspend fun admitDevice(
        ownerId: String,
        newDeviceId: String,
        newDevicePublicKey: ByteArray,
        masterDelegation: ByteArray,
        epoch: Long,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        // Unauthenticated by JWT — the master delegation in the body is the credential
        // (the relay verifies it against the realm's stored master public key). See
        // sync-multi-realm-runtime.md §3.1.
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl/sync/admit-device") {
                contentType(ContentType.Application.Json)
                setBody(
                    mapOf(
                        "ownerId" to ownerId,
                        "newDeviceId" to newDeviceId,
                        "newDevicePublicKey" to Base64.encode(newDevicePublicKey),
                        "masterDelegation" to Base64.encode(masterDelegation),
                        "epoch" to epoch.toString(),
                    ) + buildMap {
                        admittedAtHlc?.let { put("admittedAtHlc", it) }
                    }
                    // No version fields: only /sync/realm carries the gate (see RealmRouting).
                )
            }
            if (response.status.isSuccess()) {
                TResult.Success.create(context, null)
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Device admission failed: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    override suspend fun dropGrant(address: String, sealedGrant: ByteArray): TResult<Data?> {
        // JWT-authed by the grantor's personal identity (this relay's own auth).
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl/sync/grant/$address") {
                contentType(ContentType.Application.Json)
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
                entitlementTokenProvider()?.let { header("X-Entitlement", it) }
                entitlementProofProvider()?.let { header("X-Entitlement-Proof", it) }
                setBody(PostGrantBody(sealedGrant = Base64.encode(sealedGrant)))
            }
            if (response.status.isSuccess()) {
                TResult.Success.create(context, null)
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Grant drop failed: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(context, GenericError.create(context, e.message ?: "Network error"))
        }
    }

    override suspend fun pickupGrant(address: String): TResult<org.tenshu.core.TByteArray?> {
        // Anonymous — the address is the bearer capability.
        return try {
            val response: HttpResponse = httpClient.get("$baseUrl/sync/grant/$address")
            when (response.status) {
                HttpStatusCode.OK -> {
                    val body = response.body<GrantPickupBody>()
                    TResult.Success.create(context, TByteArrayDto(Base64.decode(body.sealedGrant)))
                }
                HttpStatusCode.NotFound -> TResult.Success.create(context, null)
                else -> TResult.Failure.create(
                    context,
                    GenericError.create(context, "Grant pickup failed: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(context, GenericError.create(context, e.message ?: "Network error"))
        }
    }

    override suspend fun admitMember(
        realmId: String,
        capability: MembershipCapability,
        binding: MemberDeviceBinding,
    ): TResult<Data?> {
        // Unauthenticated by JWT — the capability chain in the body is the credential.
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl/sync/realm-member/admit") {
                contentType(ContentType.Application.Json)
                setBody(
                    AdmitMemberBody(
                        realmId = realmId,
                        capability = CapabilityBody(
                            memberAuthorityPublicKey = Base64.encode(capability.memberAuthorityPublicKey),
                            epoch = capability.epoch,
                            issuedAt = capability.issuedAt,
                            signature = Base64.encode(capability.signature),
                        ),
                        deviceBinding = DeviceBindingBody(
                            deviceId = binding.deviceId,
                            perRealmDevicePublicKey = Base64.encode(binding.perRealmDevicePublicKey),
                            boundAt = binding.boundAt,
                            signature = Base64.encode(binding.signature),
                        ),
                    )
                )
            }
            if (response.status.isSuccess()) {
                TResult.Success.create(context, null)
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Member admission failed: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(context, GenericError.create(context, e.message ?: "Network error"))
        }
    }

    override suspend fun revokeMember(
        realmId: String,
        revocation: MembershipRevocation,
    ): TResult<Data?> {
        // Unauthenticated by JWT — the master signature on the revocation is the credential.
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl/sync/realm-member/revoke") {
                contentType(ContentType.Application.Json)
                setBody(
                    RevokeMemberBody(
                        realmId = realmId,
                        memberAuthorityPublicKey = Base64.encode(revocation.memberAuthorityPublicKey),
                        epoch = revocation.epoch,
                        revokedAt = revocation.revokedAt,
                        signature = Base64.encode(revocation.signature),
                    )
                )
            }
            if (response.status.isSuccess()) {
                TResult.Success.create(context, null)
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Member revocation failed: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(context, GenericError.create(context, e.message ?: "Network error"))
        }
    }

    override suspend fun recoveryChallenge(): TResult<org.tenshu.core.TByteArray> {
        // Unauthenticated by JWT — recovering device hasn't been admitted yet. The
        // master signature on the follow-up /sync/recovery-claim is the credential.
        // ⚠ Names no realm (F8): an ownerId here was an existence oracle.
        return try {
            val response: HttpResponse = httpClient.get("$baseUrl/sync/recovery-challenge")
            if (response.status.isSuccess()) {
                val body: Map<String, String> = response.body()
                val challengeB64 = body["challenge"]
                    ?: return TResult.Failure.create(
                        context,
                        GenericError.create(context, "Missing challenge in response")
                    )
                TResult.Success.create(
                    context,
                    TByteArrayDto(Base64.decode(challengeB64))
                )
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Challenge fetch failed: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    override suspend fun recoveryClaim(
        masterPublicKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        challenge: ByteArray,
        masterSignature: ByteArray
    ): TResult<Data?> {
        return try {
            val response: HttpResponse = httpClient.post("$baseUrl/sync/recovery-claim") {
                contentType(ContentType.Application.Json)
                setBody(
                    mapOf(
                        "masterPublicKey" to Base64.encode(masterPublicKey),
                        "newDevicePublicKey" to Base64.encode(newDevicePublicKey),
                        "newDeviceId" to newDeviceId,
                        "challenge" to Base64.encode(challenge),
                        "masterSignature" to Base64.encode(masterSignature)
                    )
                )
            }
            if (response.status.isSuccess()) {
                TResult.Success.create(context, null)
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Recovery claim failed: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }

    override suspend fun fetchRealmEpoch(
        ownerId: String
    ): TResult<org.tenshu.core.number.TInteger> {
        // ⚠ Device-JWT authenticated (F8): both callers run after the relay registered this
        // device. The relay reads the realm from the JWT; [ownerId] is not sent.
        return try {
            val response: HttpResponse = httpClient.get("$baseUrl/sync/realm-epoch") {
                authTokenProvider()?.let { header("Authorization", "Bearer $it") }
            }
            if (response.status.isSuccess()) {
                val body: Map<String, String> = response.body()
                val epoch = body["epoch"]?.toLongOrNull()
                    ?: return TResult.Failure.create(
                        context,
                        GenericError.create(context, "Missing or malformed epoch in response")
                    )
                TResult.Success.create(context, epoch.toTInteger(context))
            } else {
                TResult.Failure.create(
                    context,
                    GenericError.create(context, "Realm-epoch fetch failed: ${response.statusWithReason()}")
                )
            }
        } catch (e: Exception) {
            TResult.Failure.create(
                context,
                GenericError.create(context, e.message ?: "Network error")
            )
        }
    }
}

// --- Membership-grant request bodies (GR-3) — base64 byte fields, mirroring the
// relay's MembershipGrantRouting request shapes byte-for-byte. ---

@kotlinx.serialization.Serializable
private data class OfferResponseReplyDto(
    val ownerId: String,
    val status: String,
    val publicKeyA: String,
    val payload: String? = null,
)

/** Reply to `POST /sync/pairing/hub-offer/fetch` (hub proposal §11.3, H2). */
@kotlinx.serialization.Serializable
private data class HubOfferReplyDto(
    val ephemeralPublicKey: String,
    val deviceId: String,
    /** Base64 of `PairingRendezvous.sealHubDeviceName` — the relay never holds the clear name (F7). */
    val sealedDeviceName: String,
    val signature: String,
)

/** One element of `GET /sync/pairing/requests` (F7: hashed secret, sealed name). */
@kotlinx.serialization.Serializable
private data class PairingRequestReplyDto(
    val deviceId: String,
    val sealedDeviceName: String,
    val publicKey: String,
    val signingPublicKey: String,
    val pairingSecretHash: String,
)

/**
 * The merged-lane route (`sync-lane-cursor-split.md` L3). Named rather than inlined so the
 * 404-means-unsupported mapping and the route it refers to cannot drift apart.
 *
 * ⚠ The relay declares this as `post("/log")` nested under its `route("/sync")` block, so
 * the two halves are literals on either side — the same shape as `/sync/changes` and
 * `/sync/tombstones`. `SyncLogRoutingTest` is what pins them together.
 */
internal const val SYNC_LOG_ROUTE = "/sync/log"

/**
 * Body a hub-pairing-aware relay attaches to its empty-mailbox 404 so clients can tell
 * "no offer waiting" from an old relay's unmatched-route 404. Must match the relay's
 * `HubOfferRouting` constant byte-for-byte.
 */
internal const val HUB_OFFER_NONE_MARKER = "hub-offer:none"

@kotlinx.serialization.Serializable
private data class PostGrantBody(val sealedGrant: String)

@kotlinx.serialization.Serializable
private data class GrantPickupBody(val sealedGrant: String)

@kotlinx.serialization.Serializable
private data class CapabilityBody(
    val memberAuthorityPublicKey: String,
    val epoch: Long,
    val issuedAt: Long,
    val signature: String,
)

@kotlinx.serialization.Serializable
private data class DeviceBindingBody(
    val deviceId: String,
    val perRealmDevicePublicKey: String,
    val boundAt: Long,
    val signature: String,
)

@kotlinx.serialization.Serializable
private data class AdmitMemberBody(
    val realmId: String,
    val capability: CapabilityBody,
    val deviceBinding: DeviceBindingBody,
)

@kotlinx.serialization.Serializable
private data class RevokeAdmissionBody(
    val ownerId: String,
    val deviceId: String,
    val epoch: Long,
    val revokedAt: Long,
    val signature: String,
    /** F1′ — base64 A33 capability key. Required since F5b. */
    val deviceA33PublicKey: String,
)

@kotlinx.serialization.Serializable
private data class RevokeMemberBody(
    val realmId: String,
    val memberAuthorityPublicKey: String,
    val epoch: Long,
    val revokedAt: Long,
    val signature: String,
)

/**
 * The status line plus whatever the relay said about it.
 *
 * The bare status throws away the only part that distinguishes the causes. A 401 in particular is
 * five different bugs at the relay — missing header, empty token, invalid token (unknown device
 * *or* bad signature, deliberately merged), expired, not-yet-valid — and all five would arrive at
 * the caller as the word "401 Unauthorized". A load-dependent pairing failure reported that way is
 * not diagnosable from the run that produced it.
 *
 * Failure path only, so the extra read costs nothing in the normal case; body text is bounded and
 * a body that cannot be read degrades to the status alone rather than replacing one unhelpful
 * error with another. Existing callers match these messages with `contains` on a leading marker
 * ([ENTITLEMENT_REJECTION_MARKER]), so appending is safe.
 */
private suspend fun HttpResponse.statusWithReason(): String {
    val reason = runCatchingCancellable { bodyAsText() }.getOrNull()?.trim()?.replace('\n', ' ')
    // ⚠⚠ A `401` additionally carries [UNAUTHORIZED_REJECTION_MARKER], because the interpolated
    // `$status` is NOT a dependable signal: HTTP/2 has no reason phrase, so the same rejection reads
    // "401 Unauthorized" over h1 and "401 " over h2 (which is what the production relay serves
    // behind Caddy). Consumers match the marker; see that constant for the bug this caused.
    // ⚠ The `403` branch keys on the BODY token, not the status: two different 403s are reachable
    // on the pairing routes and only one of them is terminal. The token is a `:core-common`
    // constant both the relay and this file compile against, so it cannot drift the way the reason
    // phrase did — see [RelayRefusal] and [DEVICE_REMOVED_REJECTION_MARKER].
    val prefix = when {
        status == HttpStatusCode.Unauthorized -> "$UNAUTHORIZED_REJECTION_MARKER: ${status.value}"
        status == HttpStatusCode.Forbidden &&
            RelayRefusal.matches(reason, RelayRefusal.DEVICE_REMOVED) ->
            "$DEVICE_REMOVED_REJECTION_MARKER: ${status.value}"
        status == HttpStatusCode.Forbidden &&
            RelayRefusal.matches(reason, RelayRefusal.ADMISSION_REVOKED) ->
            "$ADMISSION_REVOKED_REJECTION_MARKER: ${status.value}"
        status == HttpStatusCode.Conflict &&
            RelayRefusal.matches(reason, RelayRefusal.PAIRING_RESPONSE_CONSUMED) ->
            "$PAIRING_RESPONSE_CONSUMED_MARKER: ${status.value}"
        // ⚠ Transient: the per-IP caps on the credential-less routes. `Retry-After` is the relay's
        // own answer to "when", so it travels with the marker.
        status == HttpStatusCode.TooManyRequests ->
            "$RATE_LIMITED_REJECTION_MARKER: ${status.value}" +
                (headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.let { " (retry after ${it}s)" } ?: "")
        else -> "$status"
    }
    return if (reason.isNullOrEmpty()) prefix else "$prefix — ${reason.take(REASON_MAX_CHARS)}"
}

private const val REASON_MAX_CHARS = 200

/** How long the move fence waits for [RestSyncRelay.probeWrite] before treating the relay as unreachable. */
internal const val PROBE_TIMEOUT_MS = 5_000L
