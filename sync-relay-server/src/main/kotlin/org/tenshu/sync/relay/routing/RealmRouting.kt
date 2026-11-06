package org.tenshu.sync.relay.routing

import org.tenshu.core.common.util.decodeHlcStamp
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.tenshu.core.common.RELAY_PROTOCOL_MIN_SUPPORTED_VERSION
import org.tenshu.core.common.RELAY_PROTOCOL_SUPPORTED_RANGE
import org.tenshu.core.common.RELAY_PROTOCOL_VERSION
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.sync.relay.auth.DeviceRegistry
import org.tenshu.sync.relay.auth.MasterDelegationVerifier
import org.tenshu.sync.relay.mailbox.RealmRegistry
import org.tenshu.sync.relay.mailbox.RealmStore

/**
 * Named rate-limit bucket for the unauthenticated realm-bootstrap endpoint. `Application.module`
 * registers it (per-IP, hourly) only when [RelayConfig.genesisRateLimitPerHour] `> 0`; the route
 * is wrapped in `rateLimit(GenesisRateLimitName) { … }` only when that registration happened.
 */
val GenesisRateLimitName = RateLimitName("genesis")

/*
 * ⚠ The E2e transition rule is RETIRED (`core-schema-evolution.md` §7.11).
 *
 * It used to read an absent `relayProtocolVersion` as protocol 1, on the premise that
 * "while this ships they are the entire deployed fleet". That premise expired on
 * 2026-07-26, when the E2e relay was deployed and its data wiped: every client that has
 * synced since is post-E2e and sends the field.
 *
 * Worse, the rule contradicted the **permanent floor rule** it was supposed to respect
 * (§7.5). That rule says `minSupported` must never fall below the version at which
 * client-side epoch fencing shipped, because "older clients don't know about epochs, so
 * for them the relay gate is the only fence". But an absent field means *unknown, and
 * possibly older than fencing* — reading it as exactly the fencing floor admitted the
 * one class the floor exists to reject. §7.8's claim that the allowance "expires by
 * construction when the floor rises" is only true above 1; at 1 it granted precisely
 * what the floor forbade.
 *
 * So an absent field is now a 426, and there is no default version to name.
 */

/**
 * Relay-protocol gate (`docs/proposals/core-schema-evolution.md` §7.5, as built §7.8,
 * tightened §7.11).
 *
 * Returns `true` (proceed) when the body's `relayProtocolVersion` falls inside
 * [RELAY_PROTOCOL_SUPPORTED_RANGE]; otherwise responds **426 Upgrade Required** naming the
 * accepted range and which side has to move, and returns `false`. A **missing** field is
 * now itself a 426 (the retired transition rule, above).
 *
 * ## ⚠ Scope: this is a GENESIS gate, not a session gate (§7.11)
 *
 * `/sync/realm` is where a realm is first established, and the only request that carries
 * a version at all — `RestSyncRelay` sends `relayProtocolVersion` in `createRealm` and
 * nowhere else. `RealmRegistrar.ensureRegistered()` then latches once-ever
 * (`realmRelayRegistered || lastSyncTimestamp > 0`), so **a device that has synced once
 * never presents a version to this relay again.** A client that goes stale *after*
 * bootstrap is therefore structurally invisible here, and always was: the pre-E2e
 * `formatVersion` gate only appeared to catch such clients because a format bump came
 * bundled with a relay redeploy + data wipe, which 401'd every device into
 * `ReregisterDevice` → `createRealm` → this gate. The wipe cycle was doing the fencing.
 *
 * Do not "fix" that by reading payload versions here again — that is what §7.5 split
 * apart. The reader-side fence is `SchemaPullFence`, which as of §7.11 covers core
 * skew too. This gate's job is only the relay's **own** contract.
 *
 * ⚠ **The relay deliberately no longer looks at `formatVersion` at all.** Payload/schema
 * versions are invisible to a ciphertext-only relay (House Rule #1) and therefore cannot
 * affect it: a client whose storage format differs from anything is **accepted**. That is
 * the point of the split, not an oversight — the read side is fenced client-side by the
 * realm epoch vector (§7.3), which is per-service, self-scoping and E2E, and an old client
 * *pushing* old-format blocks was always harmless because new peers upcast on read (§8.2).
 * Consequence, and the reason this exists: **a `TENSHU_STORAGE_FORMAT_VERSION` bump no
 * longer owes a relay redeploy** (House Rule #9). Only a [RELAY_PROTOCOL_VERSION] bump
 * does.
 */
private suspend fun RoutingContext.relayProtocolVersionOk(body: Map<String, String>): Boolean {
    val raw = body["relayProtocolVersion"]
    val declared = raw?.toIntOrNull()
    if (raw == null) {
        // Absent ⇒ a client that predates E2e, i.e. of unknown and possibly
        // pre-fencing vintage. Refuse loudly rather than guess — see the retired
        // transition rule above.
        call.respond(
            HttpStatusCode.UpgradeRequired,
            "This client did not declare a relayProtocolVersion, so it predates the version " +
                "gate and cannot be served (this relay accepts " +
                "$RELAY_PROTOCOL_MIN_SUPPORTED_VERSION..$RELAY_PROTOCOL_VERSION) — " +
                "this client must be updated"
        )
        return false
    }
    if (declared == null) {
        // Present but unparseable is a protocol violation. Refusing here keeps garbage
        // from being admitted on a lenient parse.
        call.respond(
            HttpStatusCode.UpgradeRequired,
            "relayProtocolVersion is not an integer: '$raw' (this relay accepts " +
                "$RELAY_PROTOCOL_MIN_SUPPORTED_VERSION..$RELAY_PROTOCOL_VERSION)"
        )
        return false
    }
    val clientVersion = declared
    if (clientVersion in RELAY_PROTOCOL_SUPPORTED_RANGE) return true

    // Name the side that must move: below the floor the client is too old to be served at
    // all (and, per the permanent floor rule, has no client-side fence either); above
    // `current` the client is newer than this deployment, so the operator must redeploy.
    val remedy =
        if (clientVersion < RELAY_PROTOCOL_MIN_SUPPORTED_VERSION) {
            "this client must be updated"
        } else {
            "this relay must be redeployed (the client is newer than the deployment)"
        }
    call.respond(
        HttpStatusCode.UpgradeRequired,
        "Client relay protocol version $clientVersion is outside the range this relay " +
            "accepts ($RELAY_PROTOCOL_MIN_SUPPORTED_VERSION..$RELAY_PROTOCOL_VERSION) — $remedy"
    )
    return false
}

/**
 * `POST /sync/realm` per `sync-relay-api.md` §3.2 — first-device realm bootstrap.
 *
 * Unauthenticated by JWT (the realm doesn't exist yet). Authenticated by the master
 * self-signature in the body, verified against the `masterPublicKey` carried alongside
 * (TOFU on first contact for that `ownerId`). On success the relay stores the realm,
 * registers the first device's public key in [KnownDevicesStore] so subsequent JWT-
 * authenticated requests from that device verify, and returns 201.
 *
 * Server-side checks (in order):
 *   - body shape — missing field → 400.
 *   - `ownerId` matches `ownerIdForMasterPublicKey(masterPublicKey)` → otherwise 400.
 *   - `masterSelfSignature` verifies against `masterPublicKey` → otherwise 400.
 *   - no existing record for `ownerId` → otherwise 409.
 *   - all checks pass → store + register device + 201.
 */
fun Route.configureRealmRouting(
    realmStore: RealmRegistry,
    knownDevices: DeviceRegistry,
    ed25519: Ed25519Backend,
    nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    /**
     * When non-null, the `POST /sync/realm` bootstrap is wrapped in `rateLimit(genesisRateLimit)`.
     * `Application.module` passes [GenesisRateLimitName] iff it installed the matching limiter
     * (i.e. [RelayConfig.genesisRateLimitPerHour] `> 0`); `null` (the default, and what tests use)
     * mounts the route unwrapped. The name must match a registered limiter or the route fails to
     * build — hence the install/pass are gated on the same condition.
     */
    genesisRateLimit: RateLimitName? = null
) {
    route("/sync") {
        // The bootstrap handler, registered either bare or inside a per-IP rate-limit scope. It's
        // the one write-shaped route that can't be JWT- or entitlement-gated (no realm exists yet),
        // so the per-IP cap is the only bound on anonymous owner creation.
        if (genesisRateLimit != null) {
            rateLimit(genesisRateLimit) {
                post("/realm") { handleRealmBootstrap(realmStore, knownDevices, ed25519, nowEpochMillis) }
            }
        } else {
            post("/realm") { handleRealmBootstrap(realmStore, knownDevices, ed25519, nowEpochMillis) }
        }

        // `POST /sync/admit-device` (SR-3b.4b, `sync-multi-realm-runtime.md` §3.1) — admit
        // an additional device to an existing realm. Used for **shared realms**: the realm's
        // OWNER (the holder of its Ed25519 master key) signs a delegation over
        // `(newDevicePublicKey || newDeviceId || epoch)` — the same shape
        // `POST /sync/pairing/response` uses — admitting another of the user's devices' A4 to
        // the shared `ownerId`, so that device's JWT verifies on the shared stream.
        //
        // Unauthenticated by JWT: the master delegation in the body is the capability (mirrors
        // `/realm` + `/recovery-claim`). Decoupled from the pairing handshake — there is no
        // pairingSecret / encryptedMasterKey here, because the admitted device already holds the
        // realm's keys (it received A12 via the `SharedRealmMembership` synced over its personal
        // realm, SR-3b.1/4a); only the relay-side device registration is missing.
        post("/admit-device") {
            val body = try {
                call.receive<Map<String, String>>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Malformed JSON body")
                return@post
            }

            val ownerId = body["ownerId"]
            val newDeviceId = body["newDeviceId"]
            val newDevicePublicKeyB64 = body["newDevicePublicKey"]
            val masterDelegationB64 = body["masterDelegation"]
            val epochStr = body["epoch"]

            if (ownerId == null || newDeviceId == null || newDevicePublicKeyB64 == null ||
                masterDelegationB64 == null || epochStr == null
            ) {
                call.respond(HttpStatusCode.BadRequest, "Missing required field")
                return@post
            }

            // NB: no relay-protocol gate here. `/sync/admit-device` requires an
            // already-bootstrapped realm, and that realm can only exist if a
            // first device passed the gate on `/sync/realm` (above) — the one
            // chokepoint every realm passes through. Gating admit-device too
            // would be redundant. (An admitted device may in principle be at a
            // different protocol version than the bootstrapping one; if that ever
            // needs enforcing, this is the second site to add it, not the first.)

            val newDevicePublicKey = try {
                java.util.Base64.getDecoder().decode(newDevicePublicKeyB64)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "newDevicePublicKey is not valid base64")
                return@post
            }
            val masterDelegation = try {
                java.util.Base64.getDecoder().decode(masterDelegationB64)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "masterDelegation is not valid base64")
                return@post
            }
            val epoch = epochStr.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, "epoch is not a valid integer")
                return@post
            }
            // B1: unauthenticated route — a 32-byte key and a printable id, or nothing is verified.
            if (!RegistrationInputRules.isEd25519PublicKey(newDevicePublicKey)) {
                return@post call.respond(HttpStatusCode.BadRequest, RegistrationInputRules.KEY_REFUSAL)
            }
            if (!RegistrationInputRules.isWellFormedDeviceId(newDeviceId)) {
                return@post call.respond(HttpStatusCode.BadRequest, RegistrationInputRules.DEVICE_ID_REFUSAL)
            }
            // Workstream B: the id is the key's — before the realm lookup and the signature.
            if (!RegistrationInputRules.isKeyDerivedDeviceId(newDeviceId, newDevicePublicKey)) {
                return@post call.respond(HttpStatusCode.BadRequest, RegistrationInputRules.DEVICE_ID_NOT_KEY_DERIVED_REFUSAL)
            }

            val realm = realmStore.get(ownerId) ?: run {
                call.respond(HttpStatusCode.NotFound, "Realm not found")
                return@post
            }

            // Verify the delegation against the realm's master public key — same message shape
            // and verifier the pairing-response path uses.
            val message = MasterDelegationVerifier.pairingResponseMessage(
                newDevicePublicKey = newDevicePublicKey,
                newDeviceId = newDeviceId,
                epoch = epoch
            )
            if (!MasterDelegationVerifier.verify(realm.masterPublicKey, message, masterDelegation, ed25519)) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid master delegation")
                return@post
            }

            // Record WHEN the admitter authored this admission, so a `Device` tombstone that was
            // authored earlier but delivered later cannot revoke it (see DeviceRegistry.revokeIfNotStale).
            // ⚠ Optional, and NOT a legacy fallback: the primary realm's admitters stamp every admission
            // (`SyncManager.admissionStamp`), but `SharedRealmDeviceAdmitter` — the shared-realm owner
            // admitting a member's device — sends none. Absent ⇒ null ⇒ the guard degrades to
            // unconditional revocation. Requiring it would 400 every shared-realm device admission.
            knownDevices.register(
                ownerId,
                newDeviceId,
                newDevicePublicKey,
                admittedAt = decodeHlcStamp(body["admittedAtHlc"]),
            )
            call.respond(HttpStatusCode.OK, "OK")
        }
    }
}

/**
 * Body of `POST /sync/realm` (see [configureRealmRouting] KDoc for the ordered checks). Extracted
 * to a named handler so the route can be registered either bare or inside a `rateLimit { … }`
 * scope without duplicating the logic.
 */
private suspend fun RoutingContext.handleRealmBootstrap(
    realmStore: RealmRegistry,
    knownDevices: DeviceRegistry,
    ed25519: Ed25519Backend,
    nowEpochMillis: () -> Long
) {
    val body = try {
        call.receive<Map<String, String>>()
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, "Malformed JSON body")
        return
    }

    val claimedOwnerId = body["ownerId"]
    val masterPublicKeyB64 = body["masterPublicKey"]
    val firstDevicePublicKeyB64 = body["firstDevicePublicKey"]
    val firstDeviceId = body["firstDeviceId"]
    val masterSelfSignatureB64 = body["masterSelfSignature"]

    if (claimedOwnerId == null || masterPublicKeyB64 == null ||
        firstDevicePublicKeyB64 == null || firstDeviceId == null ||
        masterSelfSignatureB64 == null
    ) {
        call.respond(HttpStatusCode.BadRequest, "Missing required field")
        return
    }

    // Relay-protocol gate runs after field validation so a genuinely malformed body still
    // 400s. A well-formed pre-E2e client (all content fields, no `relayProtocolVersion`)
    // is ACCEPTED while the floor is still 1 — see UNDECLARED_CLIENT_PROTOCOL_VERSION.
    if (!relayProtocolVersionOk(body)) return

    val masterPublicKey = try {
        java.util.Base64.getDecoder().decode(masterPublicKeyB64)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, "masterPublicKey is not valid base64")
        return
    }
    val firstDevicePublicKey = try {
        java.util.Base64.getDecoder().decode(firstDevicePublicKeyB64)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, "firstDevicePublicKey is not valid base64")
        return
    }
    val signature = try {
        java.util.Base64.getDecoder().decode(masterSelfSignatureB64)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, "masterSelfSignature is not valid base64")
        return
    }

    // B1: both keys are Ed25519 public keys and the first device id is printable.
    if (!RegistrationInputRules.isEd25519PublicKey(masterPublicKey) ||
        !RegistrationInputRules.isEd25519PublicKey(firstDevicePublicKey)
    ) {
        call.respond(HttpStatusCode.BadRequest, RegistrationInputRules.KEY_REFUSAL)
        return
    }
    if (!RegistrationInputRules.isWellFormedDeviceId(firstDeviceId)) {
        call.respond(HttpStatusCode.BadRequest, RegistrationInputRules.DEVICE_ID_REFUSAL)
        return
    }
    // Workstream B: the first device's id is its key's, like every other registration's.
    if (!RegistrationInputRules.isKeyDerivedDeviceId(firstDeviceId, firstDevicePublicKey)) {
        call.respond(HttpStatusCode.BadRequest, RegistrationInputRules.DEVICE_ID_NOT_KEY_DERIVED_REFUSAL)
        return
    }

    // Deterministic-derivation check: per §5.3, ownerId is fixed by masterPublicKey,
    // so the relay can independently re-derive it. Mismatch is a 400 — the client
    // is mis-implementing the protocol.
    val derivedOwnerId = ownerIdForMasterPublicKey(masterPublicKey)
    if (claimedOwnerId != derivedOwnerId) {
        call.respond(
            HttpStatusCode.BadRequest,
            "ownerId does not match the deterministic derivation of masterPublicKey"
        )
        return
    }

    if (realmStore.isErased(claimedOwnerId)) {
        call.respond(HttpStatusCode.Gone, "Realm was closed and deleted")
        return
    }

    // Master self-signature check.
    val signedMessage = MasterDelegationVerifier.realmBootstrapMessage(
        ownerId = claimedOwnerId,
        masterPublicKey = masterPublicKey,
        firstDevicePublicKey = firstDevicePublicKey,
        firstDeviceId = firstDeviceId
    )
    if (!MasterDelegationVerifier.verify(masterPublicKey, signedMessage, signature, ed25519)) {
        call.respond(HttpStatusCode.BadRequest, "Invalid masterSelfSignature")
        return
    }

    // Atomic insert. Two clients racing for the same derived ownerId — which only
    // happens if they generate the same master keypair, a 1-in-2^256 event — would
    // settle here with exactly one winner; the other gets 409.
    val createdAt = nowEpochMillis()
    val record = RealmStore.RealmRecord(
        ownerId = claimedOwnerId,
        masterPublicKey = masterPublicKey,
        createdAt = createdAt
    )
    if (!realmStore.createIfAbsent(record)) {
        call.respond(HttpStatusCode.Conflict, "Realm already exists for this ownerId")
        return
    }

    // Register the first device so subsequent JWT-auth'd calls from it verify. The stamp is the
    // genesis author's HLC — `reassertGenesis` re-runs this route to repair a lost registration,
    // so it is a re-admission like any other and must be ordered against tombstones the same way.
    knownDevices.register(
        claimedOwnerId,
        firstDeviceId,
        firstDevicePublicKey,
        admittedAt = decodeHlcStamp(body["admittedAtHlc"]),
    )

    call.respond(
        HttpStatusCode.Created,
        mapOf("ownerId" to claimedOwnerId, "createdAt" to createdAt.toString())
    )
}
