package org.tenshu.sync.relay.routing

import io.ktor.server.application.ApplicationCall
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.RelayRefusal
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.request.RoProvider
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.OperationResolver
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.schema.Type
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.CascadedTombstonePush
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.EdgedPush
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.common.AdmissionChainVerdict
import org.tenshu.sync.common.AdmissionChainVerifier
import org.tenshu.sync.common.AdmissionRevocation
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.decodeAdmissionCertChain
import org.tenshu.sync.common.hlcTipCanonicalMessage
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.MailboxStore
import org.tenshu.sync.relay.SyncMailbox
import org.tenshu.sync.relay.logId
import org.tenshu.core.common.util.decodeHlcStamp
import org.tenshu.core.common.util.toStamp
import org.tenshu.sync.relay.auth.JwtAuthenticator
import org.tenshu.sync.relay.auth.DeviceRegistry
import org.tenshu.sync.relay.auth.DeviceRevocation
import org.tenshu.sync.relay.auth.MasterDelegationVerifier
import org.tenshu.sync.relay.auth.RelayEntitlementVerifier
import org.tenshu.sync.relay.mailbox.AdmissionRevocationRegistry
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry
import org.tenshu.sync.relay.mailbox.ContentFreeing
import org.tenshu.sync.relay.mailbox.MembershipRegistry
import org.tenshu.sync.relay.mailbox.HubOfferRegistry
import org.tenshu.sync.relay.mailbox.PairingOfferRegistry
import org.tenshu.sync.relay.mailbox.HlcTipRegistry
import org.tenshu.sync.relay.mailbox.RealmRegistry

/**
 * **Per-volume retention window (docs/adr/0006-sync-tombstones.md — pending volume-metadata path).**
 *
 * The GC sweep ([SyncMailbox.gcSweep]) reads the per-owner retention window from
 * [SyncMailbox.retentionWindowMs], which falls back to
 * [RelayConfig.DEFAULT_RETENTION_WINDOW_MS] (30 days) when no override is set.
 *
 * The doc's §5.7 "open question" lists two ways to surface this knob:
 *   - (a) a synced `Volume` entity whose unencrypted envelope carries
 *     `retentionWindow: TInteger` (schema-consistent but introduces a new entity),
 *   - (b) a per-realm relay-config endpoint (operationally simpler, more public surface).
 *
 * Neither exists (no `Volume` entity, no admin endpoint route). The seam is the in-process
 * setter [SyncMailbox.setRetentionWindowMs]: production runs the default-only path, and the
 * override hook serves tests + future admin tooling. Adding a route here while the spec is
 * open would lock in the (b) shape prematurely.
 */
fun Route.configureSyncRouting(
    config: RelayConfig,
    jwtAuthenticator: JwtAuthenticator,
    realmStore: RealmRegistry,
    knownDevices: DeviceRegistry,
    hlcTipStore: HlcTipRegistry,
    /**
     * F11: an applied `Device` tombstone revokes **and** forgets the device's tip, and
     * a revoked device's late `/changes`/`/log` cannot re-create it. ⚠ Must be built over the same
     * [knownDevices] and [hlcTipStore] passed here.
     */
    deviceRevocation: DeviceRevocation,
    syncMailbox: MailboxStore,
    ed25519: Ed25519Backend,
    entitlementVerifier: RelayEntitlementVerifier? = null,
    admissionRevocationStore: AdmissionRevocationRegistry = org.tenshu.sync.relay.mailbox.AdmissionRevocationStore(),
    pairingOfferStore: PairingOfferRegistry = org.tenshu.sync.relay.mailbox.PairingOfferStore(),
    hubOfferStore: HubOfferRegistry = org.tenshu.sync.relay.mailbox.HubOfferStore(),
    /**
     * The edge registers, written by `/push` and `/push-tombstone` in the same transaction as
     * the mailbox append. ⚠ Must share the mailbox's database in production for that to hold.
     */
    contentEdges: ContentEdgeRegistry = org.tenshu.sync.relay.mailbox.ContentEdgeStore(),
    /**
     * The FREED state: G3's refusal and REVIVE on `/push` (`content-plane-cr2-b4-spec.md` §1.3).
     * ⚠ Null leaves G3 off and refuses every revive with a 400, for a relay built without one.
     * Production always wires it, over the same database as [contentEdges].
     */
    contentFreeing: ContentFreeing? = null,
    /**
     * When non-null, `POST /sync/pairing/request` is wrapped in `rateLimit(pairingRequestRateLimit)`
     * — the same arrangement, and the same install/pass coupling, as `configureRealmRouting`'s
     * `genesisRateLimit`. `null` (the default, and what tests use) mounts it unwrapped.
     */
    pairingRequestRateLimit: RateLimitName? = null,
    /**
     * Which devices of a shared realm are **member** devices. A `Device` tombstone pushed by one
     * of them revokes nothing unless it names the pusher itself (see `/push-tombstone`).
     * ⚠ Must be the store `/sync/realm-member/admit` records into; the empty default turns the
     * guard off.
     */
    membershipStore: MembershipRegistry = org.tenshu.sync.relay.mailbox.MembershipStore(),
) {
    val context = object : TContext {
        override var components: Map<String, TComponent> = emptyMap()
    }

    val registry = createDefaultSchemaRegistry(
        tContextProvider = { context },
        extraConfigs = listOf(
            coreCommonModule,
            syncServiceCommonModule,
            identityServiceCommonModule
        )
    )

    context.components = mapOf(
        SchemaProvider.COMPONENT_ID to registry,
        DataFactory.COMPONENT_ID to registry,
        RoProvider.COMPONENT_ID to registry,
        OperationResolver.COMPONENT_ID to registry
    )

    val parser = BinaryParser(registry, registry)
    val serializer = BinarySerializer(registry)

    /**
     * Who a tip-carrying request is from, once authenticated. [registered] is false only for a dev-mode
     * `X-Dev-Owner` fixture, which never had a registration to lose.
     */
    data class TipSession(val ownerId: String, val deviceId: String, val registered: Boolean)

    /**
     * F11's late-write guard, run **after** a tip write: a device revoked while its request
     * was in flight gets its just-written row dropped and a 401, so a revocation cannot be undone by
     * the revoked device's own last poll. Returns true after having responded.
     */
    suspend fun revokedMidRequest(call: ApplicationCall, session: TipSession): Boolean {
        if (!session.registered) return false
        if (!deviceRevocation.forgetIfUnregistered(session.ownerId, session.deviceId)) return false
        call.respond(HttpStatusCode.Unauthorized, "Unknown device")
        return true
    }

    /**
     * The auth + signed-HLC-tip preamble shared by `POST /sync/changes` and `POST /sync/log`.
     *
     * Shared rather than copied (`sync-lane-cursor-split.md` L3): this block is where a device's
     * *claim* to be a given owner/device is checked and where a forged HLC tip is rejected. Two
     * copies of a security check drift — one gets a fix and the other does not.
     *
     * Returns `null` **after having already responded**; callers must simply return.
     */
    suspend fun authenticateAndRecordTip(call: ApplicationCall): TipSession? {
        val devOwner = if (config.devMode) call.request.headers["X-Dev-Owner"] else null
        val ownerId: String
        val deviceId: String
        if (devOwner != null) {
            ownerId = devOwner
            // Dev-mode test fixtures have no real device key; allow them to claim a
            // deviceId via an X-Dev-Device header (default to a sentinel string).
            deviceId = call.request.headers["X-Dev-Device"] ?: "dev-device"
        } else {
            val claims = jwtAuthenticator.require(call) ?: return null
            ownerId = claims.ownerId
            deviceId = claims.deviceId
        }

        val tip = receiveParsed<SignedHlcTip>(call, parser) ?: return null

        // Self-attribution checks: a device cannot publish a tip claiming to be from
        // someone else. The relay can't verify the HLC value itself (only the device
        // knows what it has actually observed) but it can pin who said it.
        if (tip.ownerId.get() != ownerId) {
            call.respond(HttpStatusCode.Forbidden, "tip.ownerId does not match authenticated owner")
            return null
        }
        if (tip.deviceId.get() != deviceId) {
            call.respond(HttpStatusCode.Forbidden, "tip.deviceId does not match authenticated device")
            return null
        }

        // Tip-signature verification (skipped in dev mode where there's no real device key).
        // The signature is what receivers later use to verify the tip wasn't forged by
        // the relay — we do the same check here so a compromised JWT-holder still can't
        // publish a tip with a bogus HLC. See `hlcTipCanonicalMessage` for the byte order.
        if (devOwner == null) {
            val publicKey = knownDevices.getPublicKey(ownerId, deviceId)
            if (publicKey == null) {
                call.respond(HttpStatusCode.Unauthorized, "Unknown device")
                return null
            }
            val message = hlcTipCanonicalMessage(ownerId, deviceId, tip.hlc)
            if (!ed25519.verify(publicKey, message, tip.signature.get())) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid tip signature")
                return null
            }
        }

        hlcTipStore.record(ownerId, tip)
        val session = TipSession(ownerId, deviceId, registered = devOwner == null)
        if (revokedMidRequest(call, session)) return null
        return session
    }

    route("/sync") {
        /**
         * "Would a push from me be accepted RIGHT NOW?" — `/push`'s own gates and nothing else.
         *
         * ⚠⚠ Exactly the gates `/push` runs, in the same order, so the answer cannot drift from the
         * write it stands in for: owner auth (`401`), then the entitlement write-gate (`402`). A READ
         * is not evidence about writes — a `402` refuses pushes while pulls succeed — which is why the
         * move fence needs this rather than any successful pull.
         *
         * ⚠ No body, no state, nothing learned: the caller is already authenticated as the owner it
         * asks about (HR #1 — the relay reads nothing).
         */
        post("/push/probe") {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@post
            if (!requireEntitlement(call, ownerId, entitlementVerifier, config.entitlementEnforce)) {
                return@post
            }
            call.respond(HttpStatusCode.NoContent)
        }

        /**
         * `content-plane-cr2-b3-wire-spec.md` §3.1: a block **and its row's edges**, in one
         * transaction. Body: binary [EdgedPush]; anything else (a bare `SyncBlock` included) is a 400.
         *
         * ⚠⚠ The holder is `block.targetEntityIdHash` and the stamp is `block.hlc`. Neither is a field
         * of the edge, so neither can disagree with the block, and no write clock reaches the relay
         * that the block does not already expose.
         *
         * ⚠⚠ The block is appended EVEN WHEN rule 1 makes every edge a no-op (a late, older block
         * behind a delete): the mailbox is the log and peers order it themselves. Only the registers
         * are HLC-guarded here.
         */
        post("/push") {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@post
            // Write-gating (`commerce-billing-plane.md` §8): a mailbox push requires a live, owner-bound
            // entitlement when enforcement is on (opt-in; a no-op otherwise). Gated before the body is
            // even read.
            if (!requireEntitlement(call, ownerId, entitlementVerifier, config.entitlementEnforce)) {
                return@post
            }

            val push = receiveParsed<EdgedPush>(call, parser) ?: return@post
            if (!relayProtocolSupported(call, push.relayProtocolVersion.get())) return@post
            val block = push.block

            // Cross-check from sync-backend-relay-plan.md §4.1: the block's claimed owner
            // must match the authenticated owner. Mismatch is a 403 (the request is well-
            // formed but the device is trying to push under someone else's identity).
            if (block.ownerId.get() != ownerId) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    "block.ownerId does not match authenticated owner"
                )
                return@post
            }

            val edges = push.edges.toList()
            // ⚠ The serializer can round-trip an absent optional TString as "" (see the Device
            // tombstone note below), so blank means absent for the target and for a content id.
            val holder = block.targetEntityIdHash?.get()?.takeIf { it.isNotEmpty() }
            if (edges.isNotEmpty()) {
                if (holder == null) {
                    // A batch: its rows have no single holder, and §10.12 Q7 makes every
                    // content-naming delta standalone — so edges here are a client bug.
                    call.respond(HttpStatusCode.BadRequest, "edges require a standalone block (one with a target)")
                    return@post
                }
                if (edges.size > MAX_PUSHED_EDGES) {
                    call.respond(HttpStatusCode.BadRequest, "At most $MAX_PUSHED_EDGES edges per push")
                    return@post
                }
                val propertyIds = edges.map { it.propertyId.get() }
                if (propertyIds.toSet().size != propertyIds.size) {
                    call.respond(HttpStatusCode.BadRequest, "duplicate propertyId in edges")
                    return@post
                }
                val malformed = edges.firstOrNull { e ->
                    !EDGE_ID_PATTERN.matches(e.propertyId.get()) ||
                        e.contentId?.get()?.takeIf { it.isNotEmpty() }?.let { !EDGE_ID_PATTERN.matches(it) } == true
                }
                if (malformed != null || !EDGE_ID_PATTERN.matches(holder)) {
                    call.respond(HttpStatusCode.BadRequest, "Malformed edge")
                    return@post
                }
            }
            // ── The revive's shape (`content-plane-cr2-b4-spec.md` §1.3 step 4), before any lock. ──
            val revive = push.revive.toList().map { r -> r.contentId.get() to r.refs.toList().map { it.get() } }
            if (revive.isNotEmpty()) {
                if (contentFreeing == null) {
                    call.respond(HttpStatusCode.BadRequest, "revive is not available on this relay")
                    return@post
                }
                val nonRegenerableIds = edges
                    .filter { !it.regenerable.get() }
                    .mapNotNullTo(mutableSetOf()) { it.contentId?.get()?.takeIf { id -> id.isNotEmpty() } }
                // ⚠ A revive must ride the push that needs it: one naming no non-regenerable edge of THIS
                // push is a client bug, never a describe by another door (§10.12 Q3).
                if (revive.any { (id, _) -> id !in nonRegenerableIds }) {
                    call.respond(HttpStatusCode.BadRequest, "revive entry names no edge of this push")
                    return@post
                }
                if (revive.map { it.first }.toSet().size != revive.size) {
                    call.respond(HttpStatusCode.BadRequest, "duplicate contentId in revive")
                    return@post
                }
                // The describe's validation, verbatim: a revive IS a describe that also leaves FREED.
                if (revive.any { (_, refs) -> refs.isEmpty() || refs.size > MAX_REFS_PER_EDGE || refs.any { !REF_PATTERN.matches(it) } }) {
                    call.respond(HttpStatusCode.BadRequest, "Malformed revive entry")
                    return@post
                }
                if (revive.sumOf { it.second.size } > MAX_TOTAL_REFS_PER_REQUEST) {
                    call.respond(HttpStatusCode.BadRequest, "At most $MAX_TOTAL_REFS_PER_REQUEST refs per request")
                    return@post
                }
            }
            val writes = edges.map { e ->
                ContentEdgeRegistry.EdgeWrite(
                    propertyId = e.propertyId.get(),
                    // ⚠⚠ Absent = NONE, written as a register (rule 1) — never skipped and never a
                    // delete, or a later stale write would have no stamp to lose against.
                    contentId = e.contentId?.get()?.takeIf { it.isNotEmpty() },
                )
            }

            // ⚠⚠ ONE transaction: the block and its registers commit together or not at all. A block
            // stored without its edges is a row whose content the relay believes unreferenced.
            // ⚠⚠ G3 and the revive run INSIDE it, before the append (`content-plane-cr2-b4-spec.md` §1.3),
            // so a push and a `free()` on the same content serialise, and a refusal writes nothing.
            val refusal = contentEdges.atomically {
                if (contentFreeing != null) {
                    val named = edges.map { e -> e.contentId?.get()?.takeIf { it.isNotEmpty() } to e.regenerable.get() }
                    applyFreedRules(contentFreeing, ownerId, named, revive)?.let { return@atomically it }
                }
                syncMailbox.pushBlock(ownerId, block)
                if (writes.isNotEmpty()) {
                    contentEdges.writeEdges(ownerId, holder!!, block.hlc.toStamp(), writes)
                }
                null
            }
            if (refusal != null) {
                call.respond(HttpStatusCode.Conflict, refusal)
                return@post
            }
            call.respond("OK")
        }

        post("/changes") {
            // Per `sync-backend-relay-plan.md` §5.1: the request carries a signed HLC tip
            // (the highest HLC the requesting device has observed). Body is the binary-
            // serialised SignedHlcTip; `?since=` stays as a query param. Response is a
            // ChangesResponse wrapping the blocks AND every other realm device's last
            // signed tip — receivers compare those for divergence (T-T-04). Was a `GET`
            // before this slice; switched to `POST` because GET-with-body is fragile.
            val session = authenticateAndRecordTip(call) ?: return@post
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val ownerId = session.ownerId
            val deviceId = session.deviceId

            val changes = syncMailbox.getChanges(ownerId, since)
            val peerTips = hlcTipStore.getPeerTips(ownerId, excludingDeviceId = deviceId)
            val response = ChangesResponse.create(
                context = context,
                blocks = changes.blocks,
                peerTips = peerTips,
                nextCursor = changes.nextCursor
            )

            val responseBytes = serializer.serialize(
                response,
                isTopLevel = true,
                expectedType = Type.create(baseSchema = registry.getSchema(ChangesResponse.ID)!!)
            )
            call.respondBytes(responseBytes, contentType = ContentType.Application.OctetStream)
        }

        /**
         * The **merged lane** (`sync-lane-cursor-split.md` Option C / L3): one ordered page
         * covering blocks *and* tombstones, under one cursor.
         *
         * Same contract as `/changes` — signed HLC tip in the body, `?since=`, peer tips in
         * the response — so a client on this route needs neither `/changes` nor
         * `/tombstones`.
         *
         * ⚠ **Purely additive: `/changes` and `/tombstones` are untouched and stay served.**
         * That is what makes this deployable with no coordination and no
         * `RELAY_PROTOCOL_VERSION` bump (`RelayProtocol.kt`, "Purely additive routes do NOT
         * need a bump"). An older client keeps using the two lanes forever; a newer client
         * meeting an older relay gets this route's absence as a bare 404 and falls back. No
         * flag day in either direction.
         */
        post("/log") {
            val session = authenticateAndRecordTip(call) ?: return@post
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L

            // ⚠ `before=` is OPTIONAL and purely additive (`sync-device-catch-up.md` C2 / §5.4): a
            // client that never sends it gets byte-identical forward behaviour, which is what keeps
            // this off `RELAY_PROTOCOL_VERSION`. Present ⇒ serve the page walking BACKWARDS from it,
            // so a rebuilding device receives recent state first instead of crawling up from July.
            //
            // Both directions answer in the same `SyncLogResponse`: `nextCursor` is "the cursor to
            // send next in the direction you asked" and `hasMore` is "more exists that way".
            val before = call.request.queryParameters["before"]?.toLongOrNull()
            val page =
                if (before != null) syncMailbox.getSyncLogBefore(session.ownerId, before)
                else syncMailbox.getSyncLog(session.ownerId, since)

            val peerTips = hlcTipStore.getPeerTips(session.ownerId, excludingDeviceId = session.deviceId)
            val response = org.tenshu.sync.SyncLogResponse.create(
                context = context,
                blocks = page.blocks,
                tombstones = page.tombstones,
                peerTips = peerTips,
                nextCursor = page.nextCursor,
                hasMore = page.hasMore
            )

            val responseBytes = serializer.serialize(
                response,
                isTopLevel = true,
                expectedType = Type.create(
                    baseSchema = registry.getSchema(org.tenshu.sync.SyncLogResponse.ID)!!
                )
            )
            call.respondBytes(responseBytes, contentType = ContentType.Application.OctetStream)
        }

        post("/push-tombstone") {
            // Wire-distinct tombstone push per `docs/adr/0006-sync-tombstones.md`
            // Same auth rules as `/sync/push`: JWT-authenticated; the embedded ownerId must
            // match the authenticated owner. The tombstone GC sweep drops bodies once every realm
            // device has acked (`sync-backend-relay-plan.md` §3.1).
            //
            // `content-plane-cr2-b3-wire-spec.md` §3.2: the body is a [CascadedTombstonePush] — the
            // tombstone plus the wire names of every descendant its recursive delete removed. Anything
            // else is a 400.
            // The pushing device is needed too: the `Device` revocation below depends on who sent it.
            val devOwner = if (config.devMode) call.request.headers["X-Dev-Owner"] else null
            val ownerId: String
            val pusherDeviceId: String?
            if (devOwner != null) {
                ownerId = devOwner
                pusherDeviceId = call.request.headers["X-Dev-Device"]
            } else {
                val claims = jwtAuthenticator.require(call) ?: return@post
                ownerId = claims.ownerId
                pusherDeviceId = claims.deviceId
            }

            val push = receiveParsed<CascadedTombstonePush>(call, parser) ?: return@post
            if (!relayProtocolSupported(call, push.relayProtocolVersion.get())) return@post
            val block = push.block

            if (block.ownerId.get() != ownerId) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    "tombstone.ownerId does not match authenticated owner"
                )
                return@post
            }
            // ⭐ Envelope authentication (`sync-envelope-authentication.md` §3.1/§5): every tombstone carries a 32-byte
            // `authTag`. ⚠⚠ A FORMAT check, not a security one — the relay holds no realm key and cannot
            // verify the tag (House Rule 1); receivers do. It only stops a stale or buggy client from
            // storing a tombstone every peer would drop, which the relay keeps for ever.
            if ((block.authTag?.get()?.size ?: 0) != EnvelopeAuth.TAG_SIZE) {
                call.respond(HttpStatusCode.BadRequest, "tombstone.authTag missing or not ${EnvelopeAuth.TAG_SIZE} bytes")
                return@post
            }
            val cascade = push.cascade.toList().map { it.get() }.filter { it.isNotEmpty() }
            if (cascade.any { !EDGE_ID_PATTERN.matches(it) }) {
                call.respond(HttpStatusCode.BadRequest, "Malformed cascade holder")
                return@post
            }
            val removed = (listOf(block.targetEntityIdHash.get()) + cascade).filter { it.isNotEmpty() }

            // RH-1a: the relay has always assigned this and thrown it away. A reclamation
            // candidate is identified by `tombstonedAtMs` — a millisecond — while a peer cursor
            // is a relay sequence, so the barrier cannot compare them
            // (`sync-retention-horizons.md` §3.3, D9). Returning the seq is what makes the two
            // sides commensurable. ⚠ Nothing consumes it yet: the client half needs a link from
            // a candidate to the push that carried its tombstone, which does not exist today.
            //
            // ⚠⚠ ONE transaction with rule 2 (wire spec §2) for the target and every cascade name at
            // T = block.hlc: each holder's watermark rises to T and its registers stamped ≤ T become
            // none. A recreate stamped above T survives.
            val assignedSeq = contentEdges.atomically {
                val seq = syncMailbox.pushTombstone(ownerId, block)
                contentEdges.removeHolders(ownerId, removed, block.hlc.toStamp())
                seq
            }

            // T-K-02 enforcement (docs/adr/0006-sync-tombstones.md): a Device-targeted
            // tombstone carries the revoked deviceId in its `revokedDeviceId` field.
            // Pulling that here lets us revoke the device's KnownDevicesStore entry,
            // so subsequent /sync/push from that device's JWT returns 401. Without
            // this, a revoked device could keep posting junk to the mailbox until the
            // tombstone's HLC propagated everywhere AND receivers manually short-
            // circuited it on merge — wasted relay storage + a real security gap.
            //
            // The lookup is identity-service-aware: only `Device.ID` (= "tenshu/identity/device")
            // entity tombstones trigger revocation. Other entity-type tombstones flow
            // through unchanged.
            if (block.targetEntityType.get() == org.tenshu.identity.Device.ID) {
                // The serializer round-trips an absent optional `TString` as an empty
                // string rather than null (known quirk in the binary parser). Guard
                // both: a missing field gives null OR ""; a present field gives a
                // non-empty deviceId.
                val namedDeviceId = block.revokedDeviceId?.get()?.takeIf { it.isNotEmpty() }
                // ⚠⚠ A shared-realm MEMBER device may revoke only itself. Owner and member devices
                // share one registry under the realm's ownerId, the tag is unverifiable here, and
                // the HLC is the sender's choice — so without this, any member could evict the
                // owner's and every other member's devices. Removing a member is the master-signed
                // `/sync/realm-member/revoke`. The tombstone is still stored; only the registry
                // side effect is skipped (threat model T-GR-08).
                val memberRevokingOther = namedDeviceId != null && pusherDeviceId != null &&
                    namedDeviceId != pusherDeviceId &&
                    membershipStore.isMemberDevice(ownerId, pusherDeviceId)
                if (memberRevokingOther) {
                    println(
                        "SyncRouting -> ignored a Device tombstone for ${logId(namedDeviceId!!)} pushed by " +
                            "member device ${logId(pusherDeviceId!!)} (realm ${logId(ownerId)}): a member " +
                            "device may only revoke itself."
                    )
                }
                namedDeviceId?.takeUnless { memberRevokingOther }?.let { revokedDeviceId ->
                    // ⚠⚠ Ordered, not unconditional. The tombstone carries the HLC it was
                    // AUTHORED at (`SyncObserver.emit` → `hlcManager.tick()`), and delivery order
                    // is not authorship order: a retirement that could not push for hours (401 on
                    // a wiped relay, 402 without an entitlement) must not revoke, on arrival, a
                    // device that was legitimately re-admitted in the meantime. `SyncProcessor`
                    // treats a write newer than a tombstone as a recreate; this is the same rule.
                    val authoredAt = block.hlc.toStamp()
                    // F11: the registration, the removal ledger and the tip go together.
                    val applied = deviceRevocation.applyDeviceTombstone(ownerId, revokedDeviceId, authoredAt)
                    if (!applied) {
                        println(
                            "SyncRouting -> ignored a stale Device tombstone for ${logId(revokedDeviceId)} (owner ${logId(ownerId)}): " +
                                "authored at ${authoredAt.encode()}, which predates the admission " +
                                "on record. The mailbox still carries it for peers; only the " +
                                "registry revocation is skipped."
                        )
                    }
                }
            }

            // ⚠ Backward compatible in both directions: the client checks only
            // `response.status.isSuccess()` and ignores this body entirely, and an older
            // relay answers "OK" here, so a reader must tolerate a non-numeric body.
            call.respond(assignedSeq.toString())
        }

        get("/tombstones") {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@get
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L

            val result = syncMailbox.getTombstones(ownerId, since)
            val response = org.tenshu.sync.TombstonesResponse.create(
                context = context,
                tombstones = result.tombstones,
                nextCursor = result.nextCursor
            )

            val responseBytes = serializer.serialize(
                response,
                isTopLevel = true,
                expectedType = Type.create(
                    baseSchema = registry.getSchema(org.tenshu.sync.TombstonesResponse.ID)!!
                )
            )

            call.respondBytes(responseBytes, contentType = ContentType.Application.OctetStream)
        }

        get("/realm-epoch") {
            // Out-of-band realm-epoch fetch per `docs/proposals/sync-relay-api.md` §3.6,
            // closing the recovery-race carve-out from `docs/adr/0007-sync-device-management.md`. A
            // freshly-recovered (or pairing-claim) device starts at local epoch 0 because
            // it hasn't ingested tombstones yet; without this endpoint its self-pushed
            // `Device { admittedAtEpoch = 0 }` would be rejected by every realm device
            // that has already advanced past 0 via merge-time T-K-02 enforcement.
            //
            // The count is metadata the relay already exposes — push-rejection
            // reads `DeleteBlock.revokedDeviceId` on Device-targeted tombstones to revoke
            // KnownDevicesStore entries; the count of those tombstones is just an
            // aggregation over the same field. Zero new decryption surface on the relay.
            //
            // ⚠⚠ Device-JWT authenticated (`relay-metadata-minimisation.md` F8). Unauthenticated, it
            // would answer anyone holding an `ownerId` — realm existence plus its device-removal
            // count — and `ownerId` is in every pairing QR. Both callers (`RecoveryClaimImplementation`,
            // `PairingKeyInstaller`) fetch the epoch only AFTER the relay registered their device
            // key (recovery-claim / the admitter's response), so they hold a JWT by then. The realm
            // is the JWT's owner; there is no ownerId parameter to probe. Trust residual unchanged:
            // threat-model T-K-02 (the relay can over- or understate; enforcement is `<` only).
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@get
            val epoch = syncMailbox.deviceTombstoneCount(ownerId)
            call.respond(HttpStatusCode.OK, mapOf("epoch" to epoch.toString()))
        }

        post("/fetch-entity-blocks") {
            val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@post

            // ⭐ The body is the entity's EI-1 **wire id** as UTF-8 text — the same opaque name every
            // block is stored under (`sync_block.target_entity_id_hash`), compared verbatim. ⚠ Never a
            // serialised `DataId` (a File's path, in the clear): it would leak the path, and hashed
            // with the LOCAL `getDataIdHash()` it matches no EI-1 block
            // (`content-plane-cr2-b3-wire-spec.md` §11.3). No property id: that lives inside the encrypted payload
            // (threat model T-R-08).
            val entityWireId = runCatching { call.receive<ByteArray>().decodeToString().trim() }.getOrNull()
            if (entityWireId == null || !EDGE_ID_PATTERN.matches(entityWireId)) {
                call.respond(HttpStatusCode.BadRequest, "malformed request body: expected an entity wire id")
                return@post
            }

            val blocks = syncMailbox.fetchEntityBlocks(ownerId = ownerId, entityIdHash = entityWireId)
            val tList = blocks.toTList(context)

            val responseBytes = serializer.serialize(
                tList,
                isTopLevel = true,
                expectedType = Type.create(baseSchema = registry.getSchema(TList.ID)!!)
            )
            call.respondBytes(responseBytes, contentType = ContentType.Application.OctetStream)
        }

        route("/pairing") {
            // `POST /sync/pairing/request`, mounted bare or inside a per-IP rate-limit scope.
            val pairingRequest: suspend io.ktor.server.routing.RoutingContext.() -> Unit = pairingRequest@{
                val params = call.receive<Map<String, String>>()
                // Required-field validation responds with 400 rather than throwing — a
                // raw `error(...)` here would surface as Ktor's "Unhandled exception"
                // ERROR-level log entry plus a 500 to the client, which both
                // misrepresents the failure (it's the caller's fault, not the server's)
                // and adds red stack traces to test output for any negative-case test.
                // ⚠⚠ F7 (`relay-metadata-minimisation.md`): the relay is sent the secret's HASH and
                // the device name SEALED under a key derived from the secret — never either in
                // clear. A body still carrying the pre-F7 fields is refused outright rather than
                // half-accepted, so a stale client fails loudly instead of leaking.
                if ("pairingSecret" in params || "deviceName" in params) {
                    return@pairingRequest call.respond(
                        HttpStatusCode.BadRequest,
                        "pairingSecret and deviceName are not accepted: send pairingSecretHash and sealedDeviceName",
                    )
                }
                val ownerId = params["ownerId"]
                    ?: return@pairingRequest call.respond(HttpStatusCode.BadRequest, "Missing ownerId")
                val pairingSecretHash = params["pairingSecretHash"]
                    ?: return@pairingRequest call.respond(HttpStatusCode.BadRequest, "Missing pairingSecretHash")
                val deviceId = params["deviceId"]
                    ?: return@pairingRequest call.respond(HttpStatusCode.BadRequest, "Missing deviceId")
                val sealedDeviceNameBase64 = params["sealedDeviceName"]
                    ?: return@pairingRequest call.respond(HttpStatusCode.BadRequest, "Missing sealedDeviceName")

                // ⚠⚠ Everything this route stores was sent by someone holding no credential, so
                // the size of an entry is theirs to choose unless it is capped here. The per-owner
                // and TTL bounds in [PairingRequestInbox] count entries, not bytes.
                if (listOf(ownerId, deviceId).any { it.length > MAX_PAIRING_REQUEST_FIELD_LENGTH }) {
                    return@pairingRequest call.respond(
                        HttpStatusCode.BadRequest,
                        "ownerId and deviceId are limited to $MAX_PAIRING_REQUEST_FIELD_LENGTH characters",
                    )
                }
                // The one device-id rule every registering route shares.
                if (!RegistrationInputRules.isWellFormedDeviceId(deviceId)) {
                    return@pairingRequest call.respond(HttpStatusCode.BadRequest, RegistrationInputRules.DEVICE_ID_REFUSAL)
                }
                if (!isPairingSecretHash(pairingSecretHash)) {
                    return@pairingRequest call.respond(
                        HttpStatusCode.BadRequest,
                        "pairingSecretHash must be 64 lower-case hex characters",
                    )
                }
                val sealedDeviceName = decodeSealedNameOrNull(sealedDeviceNameBase64)
                    ?: return@pairingRequest call.respond(
                        HttpStatusCode.BadRequest,
                        "sealedDeviceName must be base64 of 1..$MAX_SEALED_DEVICE_NAME_BYTES bytes",
                    )

                // `sync-relay-api.md` §3.3: "`ownerId` exists". A request can only ever be read by
                // a host polling `GET /sync/pairing/requests` with a device JWT, so one addressed
                // to an owner with no registered device is unreadable by construction — and
                // accepting it anyway let a caller mint a fresh map entry per request, without
                // bound. ⚠ Not an enumeration oracle worth the name: an owner id is derived from a
                // master key and is not guessable, and whoever holds a real one already knows it
                // exists. ⚠ Skipped in dev mode, where `X-Dev-Owner` stands in for registration
                // and the device registry is legitimately empty.
                if (!config.devMode && knownDevices.deviceIds(ownerId).isEmpty()) {
                    return@pairingRequest call.respond(HttpStatusCode.NotFound, "Unknown owner")
                }
                val publicKeyBase64 = params["publicKey"]
                    ?: return@pairingRequest call.respond(HttpStatusCode.BadRequest, "Missing publicKey")
                val signingPublicKeyBase64 = params["signingPublicKey"]
                    ?: return@pairingRequest call.respond(HttpStatusCode.BadRequest, "Missing signingPublicKey")

                // Key well-formedness, checked here as **defence in depth, not as the defence**.
                // This route is necessarily unauthenticated (an unpaired joiner holds no
                // credentials), so anyone who scanned the host's QR can post to it. A key that
                // isn't a 32-byte X25519/Ed25519 value makes the host's SAS underivable, and a host
                // that failed its whole pending-requests poll over one such entry would hide the
                // legitimate joiner (threat model T-P-13).
                //
                // ⚠ The real fix lives on the client, which drops requests whose SAS won't derive:
                // House Rule #1 makes the relay untrusted, so a host must survive whatever arrives
                // regardless of what this route allows. Rejecting here just stops junk occupying a
                // mailbox and gives a broken client a 400 it can act on instead of silence.
                //
                // Bare `Base64.decode` also *threw* on malformed input, which Ktor turned into a
                // 500 — the caller's fault reported as the server's, and the same red-herring
                // stack traces the required-field checks above were written to avoid.
                val publicKey = decodeKeyOrNull(publicKeyBase64, PAIRING_KEY_SIZE)
                    ?: return@pairingRequest call.respond(
                        HttpStatusCode.BadRequest,
                        "publicKey must be base64 of exactly $PAIRING_KEY_SIZE bytes",
                    )
                val signingPublicKey = decodeKeyOrNull(signingPublicKeyBase64, PAIRING_KEY_SIZE)
                    ?: return@pairingRequest call.respond(
                        HttpStatusCode.BadRequest,
                        "signingPublicKey must be base64 of exactly $PAIRING_KEY_SIZE bytes",
                    )
                // Workstream B: the joiner's id is its A4 key's id. Refused here, not only at
                // `/pairing/response`, so a host is never shown a request it could not complete.
                if (!RegistrationInputRules.isKeyDerivedDeviceId(deviceId, signingPublicKey)) {
                    return@pairingRequest call.respond(
                        HttpStatusCode.BadRequest,
                        RegistrationInputRules.DEVICE_ID_NOT_KEY_DERIVED_REFUSAL,
                    )
                }

                syncMailbox.addPairingRequest(
                    ownerId, SyncMailbox.PairingRequestData(
                        deviceId = deviceId,
                        sealedDeviceName = sealedDeviceName,
                        publicKey = publicKey,
                        signingPublicKey = signingPublicKey,
                        pairingSecretHash = pairingSecretHash
                    )
                )
                call.respond("OK")
            }
            if (pairingRequestRateLimit != null) {
                rateLimit(pairingRequestRateLimit) { post("/request") { pairingRequest() } }
            } else {
                post("/request") { pairingRequest() }
            }

            get("/requests") {
                // Host poll. JWT required in production per sync-relay-api.md §3.3.
                val ownerId = requireOwnerId(call, config, jwtAuthenticator) ?: return@get
                // F7: each entry is the hash of the joiner's secret, its sealed name and its two
                // public keys — the relay has nothing more to serve. JSON, like the offer and hub
                // replies: the IPC-facing `PairingRequest` composite carries the CLEAR name, which
                // only the host can produce after unsealing, so it is not the wire shape.
                val base64 = java.util.Base64.getEncoder()
                val reply = syncMailbox.getPairingRequests(ownerId).map { req ->
                    PairingRequestReply(
                        deviceId = req.deviceId,
                        sealedDeviceName = base64.encodeToString(req.sealedDeviceName),
                        publicKey = base64.encodeToString(req.publicKey),
                        signingPublicKey = base64.encodeToString(req.signingPublicKey),
                        pairingSecretHash = req.pairingSecretHash,
                    )
                }
                call.respond(HttpStatusCode.OK, reply)
            }

            post("/response") {
                // Host posts the encrypted master key + the delegation admitting the new
                // device's signing key. Per sync-relay-api.md §3.3, the relay verifies the
                // delegation and only then registers `(ownerId, newDeviceId) →
                // newDevicePublicKey` so the new device's subsequent JWTs verify. Two
                // admitter shapes (delegated-device-admission-authority.md §4.3/§4.4):
                //   - no `admissionCertChain` → the delegation is signed by the realm
                //     master key (A1) — the original path, verified as before;
                //   - `admissionCertChain` present → the delegation is signed by the
                //     admitting device's A33 key; the relay walks the chain to the
                //     realm's stored master key and checks the admission revocation list.
                val caller = requireOwnerAndDevice(call, config, jwtAuthenticator) ?: return@post
                val ownerId = caller.ownerId

                val params = call.receive<Map<String, String>>()
                // F7: the joiner's mailbox is addressed by the secret's hash; the host never sends
                // the secret itself.
                val pairingSecretHash = params["pairingSecretHash"]
                val encryptedMasterKeyBase64 = params["encryptedMasterKey"]
                val newDevicePublicKeyB64 = params["newDevicePublicKey"]
                val newDeviceId = params["newDeviceId"]
                val masterDelegationB64 = params["masterDelegation"]
                val epochStr = params["epoch"]
                val admissionCertChainB64 = params["admissionCertChain"]

                if (pairingSecretHash == null || encryptedMasterKeyBase64 == null ||
                    newDevicePublicKeyB64 == null || newDeviceId == null ||
                    masterDelegationB64 == null || epochStr == null
                ) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        "Missing required field"
                    )
                    return@post
                }
                if (!isPairingSecretHash(pairingSecretHash)) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        "pairingSecretHash must be 64 lower-case hex characters",
                    )
                }
                val encryptedMasterKey = runCatching {
                    java.util.Base64.getDecoder().decode(encryptedMasterKeyBase64)
                }.getOrNull() ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "encryptedMasterKey is not valid base64",
                )

                val newDevicePublicKey = try {
                    java.util.Base64.getDecoder().decode(newDevicePublicKeyB64)
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        "newDevicePublicKey is not valid base64"
                    )
                    return@post
                }
                val masterDelegation = try {
                    java.util.Base64.getDecoder().decode(masterDelegationB64)
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        "masterDelegation is not valid base64"
                    )
                    return@post
                }
                val epoch = epochStr.toLongOrNull() ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        "epoch is not a valid integer"
                    )
                    return@post
                }

                val admissionCertChain = admissionCertChainB64?.let {
                    try {
                        java.util.Base64.getDecoder().decode(it)
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, "admissionCertChain is not valid base64")
                        return@post
                    }
                }
                when (
                    verifyAdmissionDelegation(
                        realmStore = realmStore,
                        admissionRevocationStore = admissionRevocationStore,
                        ed25519 = ed25519,
                        ownerId = ownerId,
                        newDeviceSigningKey = newDevicePublicKey,
                        newDeviceId = newDeviceId,
                        epoch = epoch,
                        masterDelegation = masterDelegation,
                        admissionCertChain = admissionCertChain,
                        isRemovedDevice = { knownDevices.wasRemoved(ownerId, it) },
                        // §7j.3 rule 2: the chain's approver must be the JWT's device, registered now.
                        approverDeviceId = caller.deviceId,
                        isRegisteredDevice = { knownDevices.getPublicKey(ownerId, it) != null },
                    )
                ) {
                    DelegationOutcome.ADMITTED -> Unit
                    DelegationOutcome.MALFORMED_INPUT ->
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            "${RegistrationInputRules.DEVICE_ID_REFUSAL}; ${RegistrationInputRules.KEY_REFUSAL}",
                        )
                    DelegationOutcome.DEVICE_ID_NOT_KEY_DERIVED ->
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            RegistrationInputRules.DEVICE_ID_NOT_KEY_DERIVED_REFUSAL,
                        )
                    DelegationOutcome.REALM_NOT_FOUND ->
                        return@post call.respond(HttpStatusCode.NotFound, "Realm not found")
                    DelegationOutcome.MALFORMED_CHAIN ->
                        return@post call.respond(HttpStatusCode.BadRequest, "admissionCertChain is not a valid cert chain")
                    DelegationOutcome.REVOKED ->
                        return@post call.respond(
                            HttpStatusCode.Forbidden,
                            RelayRefusal.body(
                                RelayRefusal.ADMISSION_REVOKED,
                                "Admission capability has been revoked",
                            ),
                        )
                    DelegationOutcome.INVALID ->
                        return@post call.respond(HttpStatusCode.Unauthorized, "Invalid master delegation")
                }

                // ── F2′: a removed identity never returns ─────────────────────────────────
                //
                // ⚠⚠ Consults the REMOVAL LEDGER, never `revocations(ownerId)`. That list can hold
                // keys of devices that were *not* removed (legacy Q3 subtree strips, retired in
                // §7j.10) and must stay re-mintable — refusing on it would bar them permanently
                // (§7c.1).
                //
                // ⚠ On iOS the Keychain outlives the app, so a reinstalled device returns with the
                // SAME deviceId and lands here. That is why RG-1 ("Start fresh") had to ship first:
                // it is the only supported way to obtain a new identity, and without it this refusal
                // would be a dead end with no in-app remedy (§7b / §4.3).
                if (knownDevices.wasRemoved(ownerId, newDeviceId)) {
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        RelayRefusal.body(
                            RelayRefusal.DEVICE_REMOVED,
                            "This device was removed from the realm and cannot rejoin under the same identity",
                        ),
                    )
                }

                // An acked hash is retired for 24 h (PairingResponseInbox): checked BEFORE any side
                // effect, so a refused post registers nothing.
                // ⚠ The body token ([RelayRefusal.PAIRING_RESPONSE_CONSUMED]) is load-bearing: a host
                // retrying a post whose reply it lost reads this 409 as "delivered", not failure.
                if (syncMailbox.isPairingResponseConsumed(pairingSecretHash)) {
                    return@post call.respond(HttpStatusCode.Conflict, pairingResponseConsumedBody)
                }

                // ⚠⚠ The verified registration is QUEUED with the response, not applied here: the
                // peek below registers it before serving the bytes, so the joiner's first
                // authenticated call still finds it, while a response that expires unfetched
                // leaves no registered device whose key was never delivered (it would sit in
                // `deviceIds` forever and block the realm's tombstone-GC quorum). See
                // PairingResponseInbox.
                val registration = org.tenshu.sync.relay.PairingResponseInbox.PendingRegistration(
                    ownerId = ownerId,
                    deviceId = newDeviceId,
                    publicKey = newDevicePublicKey,
                    admittedAt = decodeHlcStamp(params["admittedAtHlc"]),
                    admissionCertChain = admissionCertChain,
                )
                when (syncMailbox.addPairingResponse(pairingSecretHash, encryptedMasterKey, registration)) {
                    org.tenshu.sync.relay.PairingResponseInbox.PostResult.STORED -> Unit
                    // Only an ack racing this very request lands here.
                    org.tenshu.sync.relay.PairingResponseInbox.PostResult.CONSUMED ->
                        return@post call.respond(HttpStatusCode.Conflict, pairingResponseConsumedBody)
                }
                syncMailbox.removePairingRequest(ownerId, pairingSecretHash)
                call.respond("OK")
            }

            post("/ack") {
                // Per sync-relay-api.md §3.3: the new device acknowledges consumption of
                // the response. Capability is the secret's hash (F7). Replaces the destructive
                // read on GET so a device that crashes between fetch and processing can
                // still re-fetch and re-process within the TTL.
                val params = call.receive<Map<String, String>>()
                val pairingSecretHash = params["pairingSecretHash"] ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        "Missing pairingSecretHash"
                    )
                    return@post
                }
                if (!isPairingSecretHash(pairingSecretHash)) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        "pairingSecretHash must be 64 lower-case hex characters",
                    )
                }
                syncMailbox.ackPairingResponse(pairingSecretHash)
                call.respond("OK")
            }

            post("/response/peek") {
                // The joiner's poll for its sealed response — peek-only, acked separately above.
                //
                // ⚠⚠ F4/F7: a POST so the lookup key rides the BODY, never a query string, which
                // reaches access logs. The key is the secret's hash, and still never in a URI: a
                // path segment would land in route logs, and the hash is the mailbox capability.
                val params = call.receive<Map<String, String>>()
                val pairingSecretHash = params["pairingSecretHash"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing pairingSecretHash")
                if (!isPairingSecretHash(pairingSecretHash)) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        "pairingSecretHash must be 64 lower-case hex characters",
                    )
                }
                val delivery = syncMailbox.getPairingResponse(pairingSecretHash)
                    ?: return@post call.respond(HttpStatusCode.NotFound)

                // The registration the host's verified post queued (see `POST /response`) lands
                // NOW, before the bytes leave: the joiner can only authenticate after decrypting
                // them, so it always finds its key registered. Idempotent across re-peeks.
                delivery.registration?.let { reg ->
                    // Re-checked at delivery: the approver may have been removed, or a link revoked,
                    // since the post. ⚠ An ancestor's removal is NOT a refusal here either (§7j).
                    if (!queuedChainStillAdmits(reg, realmStore, admissionRevocationStore, ed25519, { knownDevices.getPublicKey(reg.ownerId, it) != null }) {
                            knownDevices.wasRemoved(reg.ownerId, it)
                        }
                    ) {
                        return@post call.respond(HttpStatusCode.Forbidden, chainAdmitterRemovedBody)
                    }
                    // F2′ re-checked at delivery: the post-time check is up to one TTL old.
                    if (knownDevices.wasRemoved(reg.ownerId, reg.deviceId)) {
                        return@post call.respond(
                            HttpStatusCode.Forbidden,
                            RelayRefusal.body(
                                RelayRefusal.DEVICE_REMOVED,
                                "This device was removed from the realm and cannot rejoin under the same identity",
                            ),
                        )
                    }
                    // Unconditional (it also refreshes
                    // `admittedAt`, which orders this admission against Device tombstones). The
                    // joiner peeks once or twice after the response lands, so the write is rare.
                    knownDevices.register(reg.ownerId, reg.deviceId, reg.publicKey, admittedAt = reg.admittedAt)
                }
                call.respondBytes(
                    delivery.encryptedKey,
                    contentType = ContentType.Application.OctetStream
                )
            }

            post("/offer-response") {
                // Offer flow (sync-pairing-inverted-direction.md §3.4): the responder —
                // an existing realm device that scanned a new device's offer QR — posts
                // the single sealed offer-response for that QR's pairingSecretHash.
                // Auth is the hostSignature itself: an Ed25519 signature by the
                // responder's registered device key (A4) over the canonical
                // status || hash || publicKeyA || payload bytes (T-P2) — no JWT needed,
                // and replay is inert because the hash is single-use (409 below).
                // `responderDeviceId` selects which registered key to verify against —
                // an as-built addition to the §3.4 body (the signed bytes are unchanged).
                val params = call.receive<Map<String, String>>()
                val ownerId = params["ownerId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing ownerId")
                val responderDeviceId = params["responderDeviceId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing responderDeviceId")
                val pairingSecretHash = params["pairingSecretHash"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing pairingSecretHash")
                val status = params["status"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing status")
                // A1 (`sync-pairing-scanned-announce.md`): `scanned` is the responder's
                // PRE-APPROVAL announce of its ephemeral key, so the offerer can derive and show
                // the SAS while the approval prompt is still up. It is signed exactly like the
                // terminal statuses — a passive QR observer therefore cannot announce, which
                // matters: an unauthenticated announce would let an observer plant a bogus SAS on
                // the offerer's screen, a confusion primitive signing removes for free.
                if (status != "approved" && status != "denied" && status != "scanned") {
                    return@post call.respond(HttpStatusCode.BadRequest, "status must be approved|denied|scanned")
                }
                val publicKeyA = try {
                    java.util.Base64.getDecoder().decode(
                        params["publicKeyA"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing publicKeyA")
                    )
                } catch (e: IllegalArgumentException) {
                    return@post call.respond(HttpStatusCode.BadRequest, "publicKeyA is not valid base64")
                }
                // Payload present iff approved (§3.4): an approval without key material
                // is useless to the offerer; a denial carrying ciphertext is malformed.
                val payloadB64 = params["payload"]
                if ((status == "approved") != (payloadB64 != null)) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        "payload must be present iff status == approved"
                    )
                }
                val payload = try {
                    payloadB64?.let { java.util.Base64.getDecoder().decode(it) }
                } catch (e: IllegalArgumentException) {
                    return@post call.respond(HttpStatusCode.BadRequest, "payload is not valid base64")
                }
                val hostSignature = try {
                    java.util.Base64.getDecoder().decode(
                        params["hostSignature"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing hostSignature")
                    )
                } catch (e: IllegalArgumentException) {
                    return@post call.respond(HttpStatusCode.BadRequest, "hostSignature is not valid base64")
                }

                val devicePublicKey = knownDevices.getPublicKey(ownerId, responderDeviceId)
                    ?: return@post call.respond(
                        HttpStatusCode.Unauthorized,
                        "Unknown responder device"
                    )
                val message = MasterDelegationVerifier.offerResponseMessage(
                    status = status,
                    pairingSecretHash = pairingSecretHash,
                    publicKeyA = publicKeyA,
                    payload = payload
                )
                if (!ed25519.verify(devicePublicKey, message, hostSignature)) {
                    return@post call.respond(
                        HttpStatusCode.Unauthorized,
                        "Invalid offer-response signature"
                    )
                }

                // Approved offers ALSO admit the offerer's device — the offer flow's
                // analogue of POST /sync/pairing/response (sync-pairing-inverted-direction.md
                // §6 P3/P4 as-built): the responder relays the offerer's Ed25519 signing key
                // (learned from the scanned QR's `g=` field) + a master/A33 delegation over
                // `offererSigningKey || offererDeviceId || epoch`. Without this the offerer's
                // first JWT-authed sync would 401 — it has no other path into KnownDevicesStore.
                // Denials register nothing (no device is being admitted).
                // ⚠⚠ The verified registration is QUEUED with the response and applied when the
                // offerer FETCHES it (`GET /offer-response` below), exactly like the join flow's
                // PairingResponseInbox: an approval the offerer never fetches must leave no
                // registered device behind — it would sit in `deviceIds` forever, never ack a
                // tombstone, and block the realm's tombstone-GC quorum.
                var registration: org.tenshu.sync.relay.PairingResponseInbox.PendingRegistration? = null
                if (status == "approved") {
                    val offererDeviceId = params["offererDeviceId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing offererDeviceId")
                    val offererSigningKey = try {
                        java.util.Base64.getDecoder().decode(
                            params["offererSigningKey"]
                                ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing offererSigningKey")
                        )
                    } catch (e: IllegalArgumentException) {
                        return@post call.respond(HttpStatusCode.BadRequest, "offererSigningKey is not valid base64")
                    }
                    val masterDelegation = try {
                        java.util.Base64.getDecoder().decode(
                            params["masterDelegation"]
                                ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing masterDelegation")
                        )
                    } catch (e: IllegalArgumentException) {
                        return@post call.respond(HttpStatusCode.BadRequest, "masterDelegation is not valid base64")
                    }
                    val epoch = params["epoch"]?.toLongOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "epoch is not a valid integer")
                    val admissionCertChain = params["admissionCertChain"]?.let {
                        try {
                            java.util.Base64.getDecoder().decode(it)
                        } catch (e: IllegalArgumentException) {
                            return@post call.respond(HttpStatusCode.BadRequest, "admissionCertChain is not valid base64")
                        }
                    }
                    when (
                        verifyAdmissionDelegation(
                            realmStore = realmStore,
                            admissionRevocationStore = admissionRevocationStore,
                            ed25519 = ed25519,
                            ownerId = ownerId,
                            newDeviceSigningKey = offererSigningKey,
                            newDeviceId = offererDeviceId,
                            epoch = epoch,
                            masterDelegation = masterDelegation,
                            admissionCertChain = admissionCertChain,
                            isRemovedDevice = { knownDevices.wasRemoved(ownerId, it) },
                            // §7j.3 rule 2: the approver is the responder — authenticated above by
                            // its registered A4 key over `hostSignature`, not merely named.
                            approverDeviceId = responderDeviceId,
                            isRegisteredDevice = { knownDevices.getPublicKey(ownerId, it) != null },
                        )
                    ) {
                        DelegationOutcome.MALFORMED_INPUT ->
                            return@post call.respond(
                                HttpStatusCode.BadRequest,
                                "${RegistrationInputRules.DEVICE_ID_REFUSAL}; ${RegistrationInputRules.KEY_REFUSAL}",
                            )
                        DelegationOutcome.DEVICE_ID_NOT_KEY_DERIVED ->
                            return@post call.respond(
                                HttpStatusCode.BadRequest,
                                RegistrationInputRules.DEVICE_ID_NOT_KEY_DERIVED_REFUSAL,
                            )
                        DelegationOutcome.ADMITTED ->
                            // ⚠⚠ F2′ applies HERE TOO. Both admit paths register a device, so
                            // guarding only the join flow would leave the offer flow as an open
                            // door — and the offer flow is precisely the one a returning device
                            // uses (its comment below says so). A rule enforced on one of two
                            // equivalent routes is not enforced.
                            if (knownDevices.wasRemoved(ownerId, offererDeviceId)) {
                                // ⚠ The TOKEN applies here too, not only the refusal. The first
                                // cut of the marker tagged the join route alone — and this is
                                // the route W10b's `Offer-response failed: 403` comes through.
                                return@post call.respond(
                                    HttpStatusCode.Forbidden,
                                    RelayRefusal.body(
                                        RelayRefusal.DEVICE_REMOVED,
                                        "This device was removed from the realm and cannot rejoin " +
                                            "under the same identity",
                                    ),
                                )
                            } else {
                                // The responder's HLC at approval — this is the route a device the
                                // relay had forgotten comes back through, so ordering it against
                                // tombstones is the whole point of the guard. Re-checked (F2′) and
                                // applied on delivery.
                                registration = org.tenshu.sync.relay.PairingResponseInbox.PendingRegistration(
                                    ownerId = ownerId,
                                    deviceId = offererDeviceId,
                                    publicKey = offererSigningKey,
                                    admittedAt = decodeHlcStamp(params["admittedAtHlc"]),
                                    admissionCertChain = admissionCertChain,
                                )
                            }
                        DelegationOutcome.REALM_NOT_FOUND ->
                            return@post call.respond(HttpStatusCode.NotFound, "Realm not found")
                        DelegationOutcome.MALFORMED_CHAIN ->
                            return@post call.respond(HttpStatusCode.BadRequest, "admissionCertChain is not a valid cert chain")
                        DelegationOutcome.REVOKED ->
                            return@post call.respond(
                            HttpStatusCode.Forbidden,
                            RelayRefusal.body(
                                RelayRefusal.ADMISSION_REVOKED,
                                "Admission capability has been revoked",
                            ),
                        )
                        DelegationOutcome.INVALID ->
                            return@post call.respond(HttpStatusCode.Unauthorized, "Invalid master delegation")
                    }
                }

                // ⚠⚠ An announce must NOT consume the hash — the terminal approve/deny still has
                // to arrive. Separate store call, separate result type; see
                // `PairingOfferRegistry.postAnnounce`.
                if (status == "scanned") {
                    val announce = pairingOfferStore.postAnnounce(
                        pairingSecretHash = pairingSecretHash,
                        response = PairingOfferRegistry.OfferResponse(
                            ownerId = ownerId,
                            status = status,
                            publicKeyA = publicKeyA,
                            payload = null,
                        ),
                        expiresAtMs = System.currentTimeMillis() +
                            RelayConfig.PAIRING_OFFER_RESPONSE_TTL_MS,
                    )
                    return@post when (announce) {
                        PairingOfferRegistry.AnnounceResult.STORED -> call.respond("OK")
                        PairingOfferRegistry.AnnounceResult.ALREADY_ANNOUNCED -> call.respond(
                            HttpStatusCode.Conflict,
                            "pairingSecretHash already announced",
                        )
                        PairingOfferRegistry.AnnounceResult.CONSUMED -> call.respond(
                            HttpStatusCode.Conflict,
                            "pairingSecretHash already consumed",
                        )
                    }
                }

                val result = pairingOfferStore.post(
                    pairingSecretHash = pairingSecretHash,
                    response = PairingOfferRegistry.OfferResponse(
                        ownerId = ownerId,
                        status = status,
                        publicKeyA = publicKeyA,
                        payload = payload,
                        registration = registration,
                    ),
                    expiresAtMs = System.currentTimeMillis() +
                        RelayConfig.PAIRING_OFFER_RESPONSE_TTL_MS,
                )
                when (result) {
                    PairingOfferRegistry.PostResult.STORED -> call.respond("OK")
                    PairingOfferRegistry.PostResult.CONSUMED -> call.respond(
                        HttpStatusCode.Conflict,
                        "pairingSecretHash already consumed"
                    )
                }
            }

            get("/offer-response") {
                // Offerer poll — unauthenticated by design (the offerer has no
                // credentials yet, T-P1): the reply is only routing metadata + the
                // responder's ephemeral public key + AEAD ciphertext. Confidentiality
                // rests on privB (never left the offerer); integrity on the verified
                // hostSignature above plus the offerer's own master-key→ownerId check.
                val hash = call.request.queryParameters["hash"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing hash")
                when (val fetch = pairingOfferStore.fetch(hash)) {
                    is PairingOfferRegistry.Fetch.Found -> {
                        // The registration the responder's verified approval queued (see
                        // `POST /offer-response`) lands NOW, before the payload leaves: the
                        // offerer can only authenticate after decrypting it, so it always finds
                        // its key registered, and an approval that expires unfetched registers
                        // nothing. Idempotent across re-polls.
                        fetch.response.registration?.let { reg ->
                            // Re-checked at delivery (see the join-flow peek; §7j).
                            if (!queuedChainStillAdmits(reg, realmStore, admissionRevocationStore, ed25519, { knownDevices.getPublicKey(reg.ownerId, it) != null }) {
                                    knownDevices.wasRemoved(reg.ownerId, it)
                                }
                            ) {
                                return@get call.respond(HttpStatusCode.Forbidden, chainAdmitterRemovedBody)
                            }
                            // F2′ re-checked at delivery: the post-time check is up to one TTL old.
                            if (knownDevices.wasRemoved(reg.ownerId, reg.deviceId)) {
                                return@get call.respond(
                                    HttpStatusCode.Forbidden,
                                    RelayRefusal.body(
                                        RelayRefusal.DEVICE_REMOVED,
                                        "This device was removed from the realm and cannot rejoin " +
                                            "under the same identity",
                                    ),
                                )
                            }
                            knownDevices.register(reg.ownerId, reg.deviceId, reg.publicKey, admittedAt = reg.admittedAt)
                        }
                        call.respond(
                            HttpStatusCode.OK,
                            PairingOfferResponseReply(
                                ownerId = fetch.response.ownerId,
                                status = fetch.response.status,
                                publicKeyA = java.util.Base64.getEncoder()
                                    .encodeToString(fetch.response.publicKeyA),
                                payload = fetch.response.payload?.let {
                                    java.util.Base64.getEncoder().encodeToString(it)
                                },
                            )
                        )
                    }

                    PairingOfferRegistry.Fetch.NotFound -> call.respond(HttpStatusCode.NotFound)
                }
            }

            post("/hub-offer") {
                // Headless hub pairing (hub proposal §11.3, H2): the hub — unpaired and
                // credential-less — posts its signed ephemeral offer, keyed by the hash
                // of its PRINTED long-lived pairing secret. Unauthenticated by design
                // (T-P-09..T-P-11 in the threat model): the relay stores only public
                // material + an Ed25519 signature it CANNOT verify — the verify key is
                // on the hub's printed label, and the phone verifies against that (the
                // relay-substitution threat closes phone-side, not here). Re-posting
                // replaces (re-arming); a fetch consumes; TTL bounds the arming window.
                val params = call.receive<Map<String, String>>()
                val pairingSecretHash = params["pairingSecretHash"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing pairingSecretHash")
                val deviceId = params["deviceId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing deviceId")
                // F7: the hub's name crosses sealed under a key derived from its printed secret,
                // which the relay never sees. A clear `deviceName` is refused, not stored.
                if ("deviceName" in params) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        "deviceName is not accepted: send sealedDeviceName",
                    )
                }
                val sealedDeviceName = decodeSealedNameOrNull(
                    params["sealedDeviceName"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing sealedDeviceName")
                ) ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "sealedDeviceName must be base64 of 1..$MAX_SEALED_DEVICE_NAME_BYTES bytes",
                )
                val ephemeralPublicKey = try {
                    java.util.Base64.getDecoder().decode(
                        params["ephemeralPublicKey"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing ephemeralPublicKey")
                    )
                } catch (e: IllegalArgumentException) {
                    return@post call.respond(HttpStatusCode.BadRequest, "ephemeralPublicKey is not valid base64")
                }
                val signature = try {
                    java.util.Base64.getDecoder().decode(
                        params["signature"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing signature")
                    )
                } catch (e: IllegalArgumentException) {
                    return@post call.respond(HttpStatusCode.BadRequest, "signature is not valid base64")
                }
                hubOfferStore.post(
                    pairingSecretHash = pairingSecretHash,
                    offer = HubOfferRegistry.HubOffer(
                        ephemeralPublicKey = ephemeralPublicKey,
                        deviceId = deviceId,
                        sealedDeviceName = sealedDeviceName,
                        signature = signature,
                    ),
                    expiresAtMs = System.currentTimeMillis() + RelayConfig.HUB_OFFER_TTL_MS,
                )
                call.respond("OK")
            }

            post("/hub-offer/fetch") {
                // Phone fetch — unauthenticated (the phone has no relation to the hub
                // yet; the secret hash is the rendezvous capability). Consumes the
                // stored offer: a consumed hash closes the arming window until the hub
                // re-arms (§11.4). The 404 body marker lets clients distinguish "no
                // offer waiting" from an OLD relay's unmatched-route 404 — must match
                // the client's HUB_OFFER_NONE_MARKER byte-for-byte.
                // ⚠ A POST with the hash in the BODY, like the join flow's
                // `/response/peek`: the hub's rendezvous key is long-lived and links its arms
                // (T-P-17), so it stays out of URIs.
                val params = call.receive<Map<String, String>>()
                val hash = params["pairingSecretHash"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing pairingSecretHash")
                when (val fetch = hubOfferStore.consume(hash)) {
                    is HubOfferRegistry.Fetch.Found -> call.respond(
                        HttpStatusCode.OK,
                        HubOfferReply(
                            ephemeralPublicKey = java.util.Base64.getEncoder()
                                .encodeToString(fetch.offer.ephemeralPublicKey),
                            deviceId = fetch.offer.deviceId,
                            sealedDeviceName = java.util.Base64.getEncoder()
                                .encodeToString(fetch.offer.sealedDeviceName),
                            signature = java.util.Base64.getEncoder()
                                .encodeToString(fetch.offer.signature),
                        )
                    )

                    HubOfferRegistry.Fetch.NotFound ->
                        call.respond(HttpStatusCode.NotFound, HUB_OFFER_NONE_MARKER)
                }
            }
        }
    }
}

/**
 * Body a hub-pairing-aware relay attaches to its empty-mailbox 404 so clients can tell
 * "no offer waiting" from an old relay's unmatched-route 404 (which has no body).
 */
internal const val HUB_OFFER_NONE_MARKER = "hub-offer:none"

/** Reply to `POST /sync/pairing/hub-offer/fetch` (hub proposal §11.3, H2). */
@kotlinx.serialization.Serializable
internal data class HubOfferReply(
    val ephemeralPublicKey: String,
    val deviceId: String,
    /** Base64 AEAD ciphertext — the relay never holds the hub's clear name (F7). */
    val sealedDeviceName: String,
    val signature: String,
)

/** Reply to `GET /sync/pairing/offer-response?hash=…` (offer flow §3.4). */
@kotlinx.serialization.Serializable
internal data class PairingOfferResponseReply(
    val ownerId: String,
    val status: String,
    val publicKeyA: String,
    val payload: String? = null,
)

/** Verdict of [verifyAdmissionDelegation] — the caller maps each to its HTTP status. */
internal enum class DelegationOutcome {
    ADMITTED, REALM_NOT_FOUND, MALFORMED_INPUT, DEVICE_ID_NOT_KEY_DERIVED, MALFORMED_CHAIN, REVOKED, INVALID,
}

/**
 * §7j.3 rule 2, the binding half: an A33 chain's approver (`chain[0]`) must be the device that is
 * actually asking — [posterDeviceId], authenticated by the route — and be **registered right now**.
 *
 * ⚠⚠ Without this the relay checked only that `chain[0]` was not in the removal ledger. A removed
 * device D whose A33 key was never key-revoked (a realm with no A1 mints no revocations, and a
 * device can blank its own key) could mint a cert for a fabricated leaf X and post `[X, D, …]`
 * under any realm JWT: D is only an ancestor, X was never removed ⇒ admitted. §7j's relaxation
 * made that reachable; this closes it. Returns null when bound, else the refusal.
 */
internal fun approverBinding(
    chain: List<org.tenshu.sync.common.AdmissionCapabilityCert>,
    posterDeviceId: String?,
    isRegisteredDevice: (deviceId: String) -> Boolean,
): DelegationOutcome? {
    val approver = chain.firstOrNull()?.deviceId ?: return DelegationOutcome.INVALID
    if (posterDeviceId == null || approver != posterDeviceId) return DelegationOutcome.INVALID
    if (!isRegisteredDevice(approver)) return DelegationOutcome.REVOKED
    return null
}

/**
 * Shared device-admission verification for the two paths that register a new device by
 * master/A33 delegation: `POST /sync/pairing/response` (join flow) and the approved
 * `POST /sync/pairing/offer-response` (offer flow). Verifies [masterDelegation] is a valid
 * signature over `MasterDelegationVerifier.pairingResponseMessage(newDeviceSigningKey,
 * newDeviceId, epoch)` — either directly by the realm master (A1, [admissionCertChain] null)
 * or by an A33 admitter whose presented chain walks to the master and clears the revocation
 * list. Does **not** register — the caller registers on [DelegationOutcome.ADMITTED] (order
 * matters: register before storing/queuing so the new device's JWTs verify immediately).
 */
internal fun verifyAdmissionDelegation(
    realmStore: RealmRegistry,
    admissionRevocationStore: AdmissionRevocationRegistry,
    ed25519: Ed25519Backend,
    ownerId: String,
    newDeviceSigningKey: ByteArray,
    newDeviceId: String,
    epoch: Long,
    masterDelegation: ByteArray,
    admissionCertChain: ByteArray?,
    /**
     * The F2′ removal ledger, consulted for the chain's APPROVER only (`chain[0]`). ⚠⚠ Not for an
     * ancestor link: `admission-revocation-names-the-capability.md` §7j retired S1's ancestor rule,
     * which locked a realm with no A1 holder out of admission for good.
     */
    isRemovedDevice: (deviceId: String) -> Boolean = { false },
    /**
     * The device the route authenticated as the poster (`chain[0]` must equal it), and whether an id
     * is registered now — [approverBinding]. Only consulted for an A33 chain; an A1-signed admission
     * carries no chain and no approver to bind.
     */
    approverDeviceId: String?,
    isRegisteredDevice: (deviceId: String) -> Boolean,
): DelegationOutcome {
    // An exactly-32-byte key and a printable id, before anything is verified — see
    // [RegistrationInputRules].
    if (!RegistrationInputRules.isEd25519PublicKey(newDeviceSigningKey) ||
        !RegistrationInputRules.isWellFormedDeviceId(newDeviceId)
    ) {
        return DelegationOutcome.MALFORMED_INPUT
    }
    // Workstream B: before the realm lookup and every signature — see
    // [RegistrationInputRules.isKeyDerivedDeviceId].
    if (!RegistrationInputRules.isKeyDerivedDeviceId(newDeviceId, newDeviceSigningKey)) {
        return DelegationOutcome.DEVICE_ID_NOT_KEY_DERIVED
    }
    val realm = realmStore.get(ownerId) ?: return DelegationOutcome.REALM_NOT_FOUND
    val message = MasterDelegationVerifier.pairingResponseMessage(
        newDevicePublicKey = newDeviceSigningKey,
        newDeviceId = newDeviceId,
        epoch = epoch,
    )
    if (admissionCertChain != null) {
        val chain = try {
            decodeAdmissionCertChain(admissionCertChain)
        } catch (e: IllegalArgumentException) {
            return DelegationOutcome.MALFORMED_CHAIN
        }
        approverBinding(chain, approverDeviceId, isRegisteredDevice)?.let { return it }
        val revocations = admissionRevocationsOf(admissionRevocationStore, ownerId)
        return when (
            AdmissionChainVerifier.verifyAdmission(
                masterPublicKey = realm.masterPublicKey,
                ownerId = ownerId,
                chain = chain,
                delegationMessage = message,
                delegationSignature = masterDelegation,
                revocations = revocations,
                verify = ed25519::verify,
                isRemovedDevice = isRemovedDevice,
            )
        ) {
            AdmissionChainVerdict.Admit -> DelegationOutcome.ADMITTED
            // ⚠ `RejectRepeatedDevice` is a REVOKED, not an INVALID: its only honest source is a member
            // whose chain names it twice (§7j.10.1's accepted edge), and the approver must see §7j.5's
            // typed copy — a 401 reads as `AdmitterUnrecognised` / the generic error.
            AdmissionChainVerdict.RejectRevoked,
            AdmissionChainVerdict.RejectRemovedApprover,
            AdmissionChainVerdict.RejectRepeatedDevice -> DelegationOutcome.REVOKED
            else -> DelegationOutcome.INVALID
        }
    }
    return if (MasterDelegationVerifier.verify(realm.masterPublicKey, message, masterDelegation, ed25519)) {
        DelegationOutcome.ADMITTED
    } else {
        DelegationOutcome.INVALID
    }
}

// `requireOwnerId` lives in `RequireOwnerId.kt` so the JWT-vs-dev fallback logic stays in one place.

/**
 * Edges one pushed row may carry. ⚠ A bound, not a policy: a row has a handful of content-bearing
 * properties (a primary plus its thumbnail rungs), so this only stops one request inflating the
 * register table.
 */
internal const val MAX_PUSHED_EDGES = 256

/**
 * G3 and REVIVE for one push (`content-plane-cr2-b4-spec.md` §1.3 steps 1–4), run inside the
 * push's transaction **before** the block is appended. Returns the 409 body when the push is refused —
 * having written nothing — or `null` once the regenerable rungs are un-freed and every revive applied.
 *
 * [edges] is `(contentId, regenerable)` per pushed edge; [revive] is `(contentId, refs)`,
 * already shape-checked by the route.
 *
 * ⚠⚠ Every check precedes every write: a 409 must leave no row changed, and the in-memory twin cannot
 * roll back.
 */
internal fun applyFreedRules(
    freeing: ContentFreeing,
    ownerId: String,
    edges: List<Pair<String?, Boolean>>,
    revive: List<Pair<String, List<String>>>,
): FreedRefusal? {
    val named = edges.mapNotNull { it.first }
    val freed = if (named.isEmpty()) emptySet() else freeing.freedAmong(ownerId, named)
    // 3. A non-regenerable edge naming a FREED id must be covered by a revive. ⚠ Every offender, in
    //    request order, deduplicated — a client resolves them all from one answer.
    val revived = revive.mapTo(mutableSetOf()) { it.first }
    val uncovered = edges
        .filter { (id, regenerable) -> !regenerable && id != null && id in freed && id !in revived }
        .mapNotNull { it.first }
        .distinct()
    if (uncovered.isNotEmpty()) return FreedRefusal(FreedRefusal.CONTENT_FREED, uncovered)
    // 4. Every ref of every revive present and not queued (PUT since the free), or nothing is written.
    val incomplete = revive
        .filter { (_, refs) -> freeing.unrevivableRefs(ownerId, refs).isNotEmpty() }
        .map { it.first }
        .distinct()
    if (incomplete.isNotEmpty()) return FreedRefusal(FreedRefusal.REVIVE_INCOMPLETE, incomplete)
    // 2. A regenerable rung naming a FREED id leaves FREED: it is re-minted, and its old refs stay queued.
    //    Never a 409 (`content-plane-cr2-quorumless.md` §10.11).
    edges.filter { (id, regenerable) -> regenerable && id != null && id in freed }
        .mapNotNull { it.first }
        .distinct()
        .forEach { freeing.unfree(ownerId, it) }
    // 4 (cont.). Replace the ref set and leave FREED. ⚠ For a non-FREED id this is a plain describe, which
    //    is what makes a retry after a lost 200 idempotent.
    for ((id, refs) in revive) freeing.revive(ownerId, id, refs)
    return null
}

/**
 * The FREED 409 body (`content-plane-cr2-b4-spec.md` §0), on `/sync/push` and
 * `/sync/content/declare`. [contentIds] lists every offending id of the request, deduplicated, in
 * request order.
 *
 * ⚠ Reveals only that ids the client itself named are FREED (threat model).
 */
@kotlinx.serialization.Serializable
internal data class FreedRefusal(val error: String, val contentIds: List<String>) {
    companion object {
        const val CONTENT_FREED = "content_freed"
        const val REVIVE_INCOMPLETE = "revive_incomplete"
    }
}

/**
 * X25519 and Ed25519 public keys are both 32 bytes, and the pairing request carries one of
 * each. Named rather than inlined so the two checks in `/sync/pairing/request` read as one rule.
 */
private const val PAIRING_KEY_SIZE = 32

/**
 * Longest `ownerId` / `deviceId` `POST /sync/pairing/request` stores. Real values are a hash and
 * a UUID; 256 characters is several times either.
 */
const val MAX_PAIRING_REQUEST_FIELD_LENGTH = 256

/**
 * Largest sealed device name (decoded bytes) the join request and hub offer accept — bounds what
 * an unauthenticated caller can make the relay hold. The value lives in `:core-common`
 * ([org.tenshu.core.common.PairingWireLimits]) because the client fits the name to the same cap
 * before sealing; a name that would not fit is truncated there, never sent to be refused here.
 */
const val MAX_SEALED_DEVICE_NAME_BYTES = org.tenshu.core.common.PairingWireLimits.MAX_SEALED_DEVICE_NAME_BYTES

/**
 * The shape of every join-flow rendezvous key: lower-case hex SHA-256, 64 characters
 * (`PairingRendezvous.joinSecretHash`). Checked so a client that sends the raw secret — a UUID,
 * the pre-F7 wire — is refused rather than silently stored (F7).
 */
private val PAIRING_SECRET_HASH_PATTERN = Regex("^[0-9a-f]{64}$")

/** `true` when [value] is shaped like a join-flow `pairingSecretHash`. */
internal fun isPairingSecretHash(value: String): Boolean = PAIRING_SECRET_HASH_PATTERN.matches(value)

/** Base64 of a sealed device name, or `null` when malformed, empty or over [MAX_SEALED_DEVICE_NAME_BYTES]. */
private fun decodeSealedNameOrNull(base64: String): ByteArray? =
    runCatching { java.util.Base64.getDecoder().decode(base64) }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() && it.size <= MAX_SEALED_DEVICE_NAME_BYTES }

/** One element of `GET /sync/pairing/requests` (F7): hashed secret, sealed name, two public keys. */
@kotlinx.serialization.Serializable
internal data class PairingRequestReply(
    val deviceId: String,
    val sealedDeviceName: String,
    val publicKey: String,
    val signingPublicKey: String,
    val pairingSecretHash: String,
)

/**
 * Named rate-limit bucket for the unauthenticated `POST /sync/pairing/request`. Registered by
 * `Application.module` only when [RelayConfig.pairingRequestRateLimitPerHour] `> 0`, and passed to
 * [configureSyncRouting] under the same condition — as [GenesisRateLimitName] is for the bootstrap.
 */
val PairingRequestRateLimitName = RateLimitName("pairing-request")

/**
 * Base64-decodes a public key from an **unauthenticated** request, returning `null` — never
 * throwing — when it is not valid base64 of exactly [expectedSize] bytes. The caller turns that
 * into a 400; see the note at `/sync/pairing/request` for why this is defence in depth rather
 * than the defence.
 */
private fun decodeKeyOrNull(base64: String, expectedSize: Int): ByteArray? =
    runCatching { java.util.Base64.getDecoder().decode(base64) }
        .getOrNull()
        ?.takeIf { it.size == expectedSize }

/** The realm's stored admission revocations, in the verifier's shape. */
private fun admissionRevocationsOf(
    admissionRevocationStore: AdmissionRevocationRegistry,
    ownerId: String,
): List<AdmissionRevocation> =
    admissionRevocationStore.revocations(ownerId).map {
        AdmissionRevocation(
            ownerId = it.ownerId,
            deviceId = it.deviceId,
            epoch = it.epoch,
            revokedAt = it.revokedAt,
            signature = it.signature,
            // F1′: the key IS the revocation — the verifier matches on it alone since F5b.
            // `SqliteAdmissionRevocationStoreTest` pins the store half of that chain.
            deviceA33PublicKey = it.deviceA33PublicKey,
        )
    }

/**
 * At delivery: re-runs the full chain verification of a queued registration — signatures, and the
 * key-scoped revocation list and the removal ledger, both for the approver (`chain[0]`) only
 * (`admission-revocation-names-the-capability.md` §7j, §7j.10) — because the post-time check is up to
 * one TTL old. ⚠⚠ Both halves are needed: a remover strips the removed device's key at once while the
 * removal tombstone is pushed later. ⚠⚠ An ancestor removed or key-revoked after the post does not
 * stop the delivery: refusing it was the lock-out §7j and §7j.10 retired.
 *
 * True for an A1-direct admission (no chain). Fails closed when the realm is gone or the stored chain
 * no longer decodes.
 */
internal fun queuedChainStillAdmits(
    registration: org.tenshu.sync.relay.PairingResponseInbox.PendingRegistration,
    realmStore: RealmRegistry,
    admissionRevocationStore: AdmissionRevocationRegistry,
    ed25519: Ed25519Backend,
    /** The approver must still be registered at delivery — see [approverBinding]. */
    isRegisteredDevice: (deviceId: String) -> Boolean,
    isRemovedDevice: (deviceId: String) -> Boolean,
): Boolean {
    val encoded = registration.admissionCertChain ?: return true
    val realm = realmStore.get(registration.ownerId) ?: return false
    val chain = try {
        decodeAdmissionCertChain(encoded)
    } catch (e: IllegalArgumentException) {
        return false
    }
    // The post bound `chain[0]` to the authenticated poster, so `chain[0]` IS the poster here: it
    // must still be registered (a poster removed between post and delivery delivers nothing).
    val approver = chain.firstOrNull()?.deviceId ?: return false
    if (!isRegisteredDevice(approver)) return false
    return AdmissionChainVerifier.verifyChain(
        masterPublicKey = realm.masterPublicKey,
        ownerId = registration.ownerId,
        chain = chain,
        revocations = admissionRevocationsOf(admissionRevocationStore, registration.ownerId),
        verify = ed25519::verify,
        isRemovedDevice = isRemovedDevice,
    ) == AdmissionChainVerdict.Admit
}

/**
 * The `403` body when a queued registration's approver was removed, or its own A33 key revoked,
 * after the post. ⚠ The token is what the clients key their copy on; the text is diagnostics.
 */
private val chainAdmitterRemovedBody: String =
    RelayRefusal.body(RelayRefusal.ADMISSION_REVOKED, "The approving device was removed, or its admission capability revoked")

/** The `409` body of a join-flow response post whose hash the joiner already acked (F7). */
private val pairingResponseConsumedBody: String =
    org.tenshu.core.common.RelayRefusal.body(
        org.tenshu.core.common.RelayRefusal.PAIRING_RESPONSE_CONSUMED,
        "pairingSecretHash already consumed",
    )
