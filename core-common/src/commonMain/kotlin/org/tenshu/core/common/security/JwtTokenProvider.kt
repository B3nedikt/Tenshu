package org.tenshu.core.common.security

/**
 * Builds a `suspend () -> String?` that mints a fresh device-signed JWT per call.
 * Suitable for wiring into HTTP clients (e.g. `RestSyncRelay`'s `authTokenProvider`)
 * so every request carries a short-lived token signed by the device's Ed25519 key
 * (asset A4) per `sync-backend-relay-plan.md` §4.1.
 *
 * Returns null when [ownerIdProvider] or [deviceIdProvider] returns null — the device
 * isn't yet associated with a realm and the caller should treat the request as
 * unauthenticated. (A pairing or recovery flow has to land before this provider can
 * mint anything.)
 *
 * Token lifetime is [ttlSec] (default 60s) — short enough to bound replay-window risk
 * but long enough to absorb modest clock skew between the device and the relay. The
 * [ttlSec] cap of 5 minutes from the proposal is enforced by the *server*, not here.
 *
 * @param nowEpochSec callable returning seconds-since-epoch; tests pass a deterministic value
 * @param iatSkewSec how far back to backdate `iat` to absorb client/server clock skew
 *                  (default 5s; covers small NTP drift without weakening replay protection)
 */
fun jwtTokenProvider(
    ownerIdProvider: suspend () -> String?,
    deviceIdProvider: suspend () -> String?,
    signer: (ByteArray) -> ByteArray,
    nowEpochSec: () -> Long,
    ttlSec: Long = 60L,
    iatSkewSec: Long = 5L
): suspend () -> String? = provider@{
    val owner = ownerIdProvider() ?: return@provider null
    val device = deviceIdProvider() ?: return@provider null
    val now = nowEpochSec()
    encodeJwt(
        claims = JwtClaims(
            ownerId = owner,
            deviceId = device,
            issuedAtSec = now - iatSkewSec,
            expiresAtSec = now + ttlSec
        ),
        signer = signer
    )
}
