package org.tenshu.sync.relay.routing

import io.ktor.server.application.ApplicationCall
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.JwtAuthenticator

/**
 * Resolve the authenticated owner for a request. In production (`devMode=false`) this
 * requires a JWT signed by an admitted device key. In dev mode the relay also accepts
 * `X-Dev-Owner: <ownerId>` per the explicit dev-bypass in
 * `sync-backend-relay-plan.md` §4.1; the loopback-bind interlock in [RelayConfig.validate]
 * keeps this from being shipped accidentally.
 *
 * Returns null after writing the appropriate 4xx response — callers `return@post`.
 *
 * Shared by every routing file that needs JWT-authed ownerId resolution; kept here so the
 * JWT-vs-dev fallback logic stays in exactly one place.
 */
internal suspend fun requireOwnerId(
    call: ApplicationCall,
    config: RelayConfig,
    jwtAuthenticator: JwtAuthenticator
): String? {
    if (config.devMode) {
        val devOwner = call.request.headers["X-Dev-Owner"]
        if (devOwner != null) return devOwner
        // Fall through to JWT verification — dev mode allows JWT too, just makes the
        // dev header an additional accepted shape.
    }
    val claims = jwtAuthenticator.require(call) ?: return null
    return claims.ownerId
}

/** The authenticated caller of a device-JWT route: its realm, and the device that signed the JWT. */
internal class AuthenticatedDevice(val ownerId: String, val deviceId: String?)

/**
 * [requireOwnerId], keeping the authenticated **device** too — the JWT's `sub`. The admit routes
 * need it to bind an A33 chain's approver (`chain[0]`) to the device actually posting
 * (`admission-revocation-names-the-capability.md` §7j.3 rule 2).
 *
 * ⚠ Dev mode: `X-Dev-Owner` carries no device, so the caller may add `X-Dev-Device`; without it
 * [AuthenticatedDevice.deviceId] is null and a chain-presenting admission is refused (fails closed).
 */
internal suspend fun requireOwnerAndDevice(
    call: ApplicationCall,
    config: RelayConfig,
    jwtAuthenticator: JwtAuthenticator,
): AuthenticatedDevice? {
    if (config.devMode) {
        val devOwner = call.request.headers["X-Dev-Owner"]
        if (devOwner != null) return AuthenticatedDevice(devOwner, call.request.headers["X-Dev-Device"])
    }
    val claims = jwtAuthenticator.require(call) ?: return null
    return AuthenticatedDevice(claims.ownerId, claims.deviceId)
}
