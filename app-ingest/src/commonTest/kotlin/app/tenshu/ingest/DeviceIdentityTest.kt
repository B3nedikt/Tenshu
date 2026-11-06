package app.tenshu.ingest

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [DeviceIdentity] is the fix for the "screen opened before sync identity was up, and stayed
 * on the defaults for the rest of the session" race — so what it must prove is that a
 * resolver which is blank *at first* still lands, and that a resolver which never lands
 * doesn't park a user gesture forever.
 */
class DeviceIdentityTest {

    @Test
    fun awaitRetriesUntilTheProxyKnowsTheDeviceId() = runTest {
        var calls = 0
        val identity = DeviceIdentity {
            calls++
            if (calls < 3) null else "device-1"
        }

        assertEquals("device-1", identity.await())
        assertEquals(3, calls, "retried until resolved")
    }

    @Test
    fun theResolvedIdIsCachedAcrossCalls() = runTest {
        var calls = 0
        val identity = DeviceIdentity { calls++; "device-1" }

        assertEquals("device-1", identity.await())
        assertEquals("device-1", identity.await())
        assertEquals(1, calls, "resolved once, then served from cache")
    }

    @Test
    fun awaitOrNullGivesUpSoAWriteNeverHangs() = runTest {
        val identity = DeviceIdentity { null }

        assertNull(identity.awaitOrNull(timeoutMs = 500))
    }

    @Test
    fun aThrowingProxyIsTreatedAsUnresolvedAndRetried() = runTest {
        var calls = 0
        val identity = DeviceIdentity {
            calls++
            if (calls < 3) error("proxy not up") else "device-1"
        }

        // The old one-shot resolve let this escape into the collector's flow; an unreachable
        // proxy is exactly the state to keep retrying through.
        assertEquals("device-1", identity.await())
    }

    @Test
    fun awaitKeepsWaitingWhileIdentityIsUnresolved() = runTest {
        val identity = DeviceIdentity { null }

        // The unbounded form is for read flows, whose collector cancellation is the exit —
        // it must NOT fall through to a default the way the old one-shot resolve did.
        assertNull(withTimeoutOrNull(500) { identity.await() })
    }
}
