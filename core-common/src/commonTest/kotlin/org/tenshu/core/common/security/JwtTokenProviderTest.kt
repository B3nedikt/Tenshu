package org.tenshu.core.common.security

import kotlinx.coroutines.test.runTest
import org.tenshu.core.security.stubEd25519Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JwtTokenProviderTest {

    private val deviceKey = "device-private-key".encodeToByteArray()

    @Test
    fun mintsTokenWhenIdentityIsKnown() = runTest {
        val provider = jwtTokenProvider(
            ownerIdProvider = { "owner-1" },
            deviceIdProvider = { "device-1" },
            signer = { msg -> stubEd25519Signature(deviceKey, msg) },
            nowEpochSec = { 10_000L }
        )

        val token = provider()
        assertNotNull(token)

        // The minted token should round-trip through the same verifier and produce the
        // expected claims. iatSkewSec defaults to 5; ttlSec defaults to 60.
        val claims = decodeJwt(
            token = token,
            currentTimeSec = 10_010L
        ) { msg, sig ->
            sig.contentEquals(stubEd25519Signature(deviceKey, msg))
        }
        assertEquals("owner-1", claims.ownerId)
        assertEquals("device-1", claims.deviceId)
        assertEquals(9_995L, claims.issuedAtSec)   // 10_000 - 5
        assertEquals(10_060L, claims.expiresAtSec) // 10_000 + 60
    }

    @Test
    fun returnsNull_whenOwnerIdMissing() = runTest {
        val provider = jwtTokenProvider(
            ownerIdProvider = { null },
            deviceIdProvider = { "device-1" },
            signer = { error("must not be called when claims are incomplete") },
            nowEpochSec = { 10_000L }
        )
        assertNull(provider())
    }

    @Test
    fun returnsNull_whenDeviceIdMissing() = runTest {
        val provider = jwtTokenProvider(
            ownerIdProvider = { "owner-1" },
            deviceIdProvider = { null },
            signer = { error("must not be called when claims are incomplete") },
            nowEpochSec = { 10_000L }
        )
        assertNull(provider())
    }

    @Test
    fun mintsFreshTokenPerCall() = runTest {
        // Two calls at different times produce different tokens (different `iat`/`exp`).
        var now = 10_000L
        val provider = jwtTokenProvider(
            ownerIdProvider = { "owner-1" },
            deviceIdProvider = { "device-1" },
            signer = { msg -> stubEd25519Signature(deviceKey, msg) },
            nowEpochSec = { now }
        )

        val first = provider()
        now = 10_100L
        val second = provider()

        assertNotNull(first)
        assertNotNull(second)
        // Token strings differ because the payload (`iat`, `exp`) and therefore the
        // signature differ at different times.
        kotlin.test.assertNotEquals(first, second)
    }

    @Test
    fun customTtl_appliesToExpClaim() = runTest {
        val provider = jwtTokenProvider(
            ownerIdProvider = { "o" },
            deviceIdProvider = { "d" },
            signer = { msg -> stubEd25519Signature(deviceKey, msg) },
            nowEpochSec = { 1_000L },
            ttlSec = 300L,
            iatSkewSec = 0L
        )
        val token = provider()!!
        val claims = decodeJwt(token, currentTimeSec = 1_001L) { msg, sig ->
            sig.contentEquals(stubEd25519Signature(deviceKey, msg))
        }
        assertEquals(1_000L, claims.issuedAtSec)
        assertEquals(1_300L, claims.expiresAtSec)
    }
}
