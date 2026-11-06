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
