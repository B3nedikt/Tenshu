package org.tenshu.sync.proxy

import org.tenshu.core.common.RelayRefusal
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.messageOrNull

/**
 * Stable marker [RestSyncRelay] embeds in the error message of any relay response rejected with
 * HTTP `401`, so the scheduler can tell "the relay refuses this device's identity" (a doomed request
 * to back off from, and — where the device holds A1 — to repair) from a transient network failure.
 * The same producer/consumer contract [ENTITLEMENT_REJECTION_MARKER] uses for the `402`, and for the
 * same reason: both live in `:sync-service-proxy`, so no module boundary is crossed and no new
 * wire-registered error type is needed.
 *
 * ⚠⚠ **It is a marker rather than a status-text match because the status TEXT is protocol-dependent,
 * and matching it shipped a bug.** The first version of this file matched the literal
 * `"401 Unauthorized"`, which is what Ktor renders when the response carries a reason phrase.
 * **HTTP/2 has no reason phrase**, so a relay behind Caddy (which serves h2) produces
 * `"Server error: 401  — Invalid token"` — note the empty description — and the match silently
 * failed. Measured on the live relay 2026-08-17: a device in exactly the state this code exists for
 * pushed **386 queued blocks per pass at ~38 requests/second** with the backoff, the drain break and
 * the health state all inert, because none of them ever saw a 401.
 *
 * ⚠⚠ And the unit test passed the whole time: Ktor's `MockEngine` builds `HttpStatusCode.Unauthorized`
 * *with* its canonical description, so the fixture produced the one shape the matcher understood.
 * `RestSyncRelayUnauthorizedContractTest` now asserts the empty-reason-phrase shape too.
 *
 * ⚠ There is deliberately no finer-grained signal than "401": the relay maps five distinct causes
 * onto it and answers three of them identically (`JwtAuth` — *"Don't distinguish 'unknown device'
 * from 'bad signature' in the response — both look the same to a probing attacker"*), so a client
 * cannot learn why. The repair is built to be safe under that ambiguity instead — see
 * [RealmRegistrar.reregisterThisDevice].
 */
internal const val UNAUTHORIZED_REJECTION_MARKER = "relay-unauthorized-401"

/**
 * True if [this] (or anything in its cause chain) is a relay `401` — i.e. carries
 * [UNAUTHORIZED_REJECTION_MARKER]. Mirrors [isEntitlementRejection]'s traversal so a re-wrapped
 * exception is still recognised.
 */
internal fun Throwable.isRelayUnauthorized(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is TenshuException && t.error.messageOrNull()?.contains(UNAUTHORIZED_REJECTION_MARKER) == true) {
            return true
        }
        if (t.message?.contains(UNAUTHORIZED_REJECTION_MARKER) == true) return true
        t = t.cause
    }
    return false
}

/**
 * Marker [RestSyncRelay] embeds for any relay **429 Too Many Requests** — today the per-IP caps on the
 * credential-less routes (`GET /sync/recovery-challenge`, `POST /sync/pairing/request`,
 * `POST /sync/realm`). Followed by `(retry after Ns)` when the relay sent `Retry-After`.
 *
 * ⚠ **Transient, never a verdict on the request.** The same call succeeds once the window refills,
 * so a reader of this failure (a log, the JS `Recovery.claim` mapping) must present it as "try again
 * shortly", not as a rejected phrase or a missing realm. A marker rather than a status-text match for
 * the reason [UNAUTHORIZED_REJECTION_MARKER] gives: over HTTP/2 there is no reason phrase.
 */
internal const val RATE_LIMITED_REJECTION_MARKER = "relay-rate-limited-429"

/** True if this failure is a relay `429` ([RATE_LIMITED_REJECTION_MARKER]). */
internal fun TResult.Failure<*>.isRelayRateLimited(): Boolean =
    error.messageOrNull()?.contains(RATE_LIMITED_REJECTION_MARKER) == true

/**
 * Marker [RestSyncRelay] embeds when a pairing response is refused **403 Forbidden** because the
 * joining device is in the relay's **removal ledger** (F2′) — it was removed from this realm and
 * cannot rejoin under the same identity.
 *
 * ⚠⚠ **Terminal, and that is the point of having it.** Every other rejection this file models is
 * worth retrying eventually: a `401` may be repaired by re-registration, a `402` by a credential
 * arriving, a `409` is success in disguise. This one never resolves — the identity is dead for this
 * realm, permanently and by design (`admission-revocation-names-the-capability.md` §4.3, decision
 * F). A client that cannot tell it apart from a transient `403` either retries forever or tells the
 * user to try again, and both are wrong.
 *
 * ⚠ **The status alone cannot carry this**, unlike the 401/409 markers: *two* different `403`s are
 * reachable on that route, and the other one ([RelayRefusal.ADMISSION_REVOKED]) is about the
 * **approver**, not the joiner. So the discriminator is the token the relay puts at the front of
 * the body — a constant in `:core-common` that both sides compile against
 * ([RelayRefusal]), never prose one side spells and the other re-spells.
 *
 * The consumer this exists for is the *"start fresh"* route: `drive-web-parity.md` W10b and
 * `admission-revocation-names-the-capability.md` §7h (F6-2). ⚠⚠ Read §7h.2 before wiring it to an
 * automatic remedy — offering "start fresh" straight off this marker deletes the approver's only
 * signal that a removed device is being re-added.
 */
internal const val DEVICE_REMOVED_REJECTION_MARKER = "relay-device-removed-403"

/** True if [this] carries [DEVICE_REMOVED_REJECTION_MARKER] anywhere in its cause chain. */
internal fun Throwable.isDeviceRemoved(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is TenshuException && t.error.messageOrNull()?.contains(DEVICE_REMOVED_REJECTION_MARKER) == true) {
            return true
        }
        if (t.message?.contains(DEVICE_REMOVED_REJECTION_MARKER) == true) return true
        t = t.cause
    }
    return false
}

/**
 * Marker [RestSyncRelay] embeds when `POST /sync/realm` is refused **409 Conflict** — a realm
 * already exists for this ownerId.
 *
 * ⚠⚠ That is not a failure, it is the desired state. `ownerId` is derived from the master public
 * key, so a 409 means *our own* realm is already registered; genesis is idempotent and
 * [RealmRegistrar.ensureRegistered] treats it as success.
 *
 * ⚠ Why it matters beyond tidiness: the scheduler calls `ensureRegistered` as soon as pushes are
 * allowed, and `CreateRealmImplementation` holds a *different* registrar instance with its own
 * latch. If the scheduler registers first, the explicit bootstrap POSTs and gets a 409 — and
 * reported genesis as FAILED. Surfaced 2026-08-18 by `BootstrapSystemTest` the moment the system
 * harness started wiring a registrar the way production does; the same race exists in production
 * whenever the storage gate opens before genesis completes.
 *
 * ⚠ A marker rather than a status-text match, for the reason [UNAUTHORIZED_REJECTION_MARKER]
 * documents at length: HTTP/2 sends no reason phrase.
 */
internal const val REALM_EXISTS_REJECTION_MARKER = "relay-realm-exists-409"

/**
 * Marker [RestSyncRelay] embeds when `POST /sync/pairing/response` is refused **409** with
 * [RelayRefusal.PAIRING_RESPONSE_CONSUMED] — the joiner already acked a response under this hash.
 * Keyed on the body token, not the status: the route has other `409`s.
 */
internal const val PAIRING_RESPONSE_CONSUMED_MARKER = "relay-pairing-response-consumed-409"

/** True if this failure is the relay's [PAIRING_RESPONSE_CONSUMED_MARKER] refusal. */
internal fun TResult.Failure<*>.isPairingResponseConsumed(): Boolean =
    error.messageOrNull()?.contains(PAIRING_RESPONSE_CONSUMED_MARKER) == true
internal const val REALM_ERASED_REJECTION_MARKER = "relay-realm-erased-410"

/** True if [this] carries [REALM_EXISTS_REJECTION_MARKER] anywhere in its cause chain. */
internal fun Throwable.isRealmAlreadyExists(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is TenshuException && t.error.messageOrNull()?.contains(REALM_EXISTS_REJECTION_MARKER) == true) {
            return true
        }
        if (t.message?.contains(REALM_EXISTS_REJECTION_MARKER) == true) return true
        t = t.cause
    }
    return false
}

internal fun Throwable.isRealmErased(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is TenshuException && t.error.messageOrNull()?.contains(REALM_ERASED_REJECTION_MARKER) == true) {
            return true
        }
        if (t.message?.contains(REALM_ERASED_REJECTION_MARKER) == true) return true
        t = t.cause
    }
    return false
}

/**
 * True if [this] is the relay refusing genesis because the realm already exists — i.e. the state
 * genesis wanted. Shared so `RealmRegistrar` and `CreateRealmImplementation` cannot drift on what
 * "already registered" means.
 */
internal fun TResult<*>.isRealmAlreadyExistsFailure(): Boolean =
    this is TResult.Failure<*> && TenshuException(error).isRealmAlreadyExists()

internal fun TResult<*>.isRealmErasedFailure(): Boolean =
    this is TResult.Failure<*> && TenshuException(error).isRealmErased()
