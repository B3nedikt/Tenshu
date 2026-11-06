package app.tenshu.calendar.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the two kinds of "no device id": a null/blank answer is retried, a throw (a graph that
 * registers no sync service) is latched. Retrying a throw forever would trade a visible crash for
 * an invisible hang, so it has to be distinguishable from "the id is not up yet".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarDeviceIdentityTest {

    @Test
    fun resolvesTheIdOnceAvailable() = runTest {
        val identity = CalendarDeviceIdentity { "device-a" }
        assertEquals("device-a", identity.awaitOrNull())
    }

    @Test
    fun retriesWhileTheIdIsMerelyNotUpYet() = runTest {
        // The case the retry loop exists for: sync identity comes up shortly after the app does.
        var attempts = 0
        val identity = CalendarDeviceIdentity {
            attempts += 1
            if (attempts < 3) null else "device-a"
        }
        assertEquals("device-a", identity.awaitOrNull())
        assertEquals(3, attempts, "a null answer must be retried, not latched")
    }

    @Test
    fun treatsBlankAsNotYet() = runTest {
        var attempts = 0
        val identity = CalendarDeviceIdentity {
            attempts += 1
            if (attempts < 2) "   " else "device-a"
        }
        assertEquals("device-a", identity.awaitOrNull())
    }

    @Test
    fun aThrowingResolverGivesUpInsteadOfLoopingForever() = runTest {
        // ⚠ `runTest` fails on a test that never completes, so a latch-free implementation
        // (retrying the throw) would hang here rather than pass.
        var attempts = 0
        val identity = CalendarDeviceIdentity {
            attempts += 1
            error("No schema registered for service id: tenshu/sync/service")
        }
        assertNull(identity.awaitOrNull())
        assertEquals(1, attempts, "a permanent failure must be attempted once, then latched")
    }

    @Test
    fun onceUnavailableEverySubsequentWaitAnswersImmediately() = runTest {
        var attempts = 0
        val identity = CalendarDeviceIdentity {
            attempts += 1
            error("boom")
        }
        assertNull(identity.awaitOrNull())
        assertNull(identity.awaitOrNull())
        assertNull(identity.awaitOrNull())
        assertEquals(1, attempts, "the latch must not re-probe a resolver that cannot succeed")
    }

    @Test
    fun cachesAResolvedIdRatherThanReprobing() = runTest {
        var attempts = 0
        val identity = CalendarDeviceIdentity {
            attempts += 1
            "device-a"
        }
        assertEquals("device-a", identity.awaitOrNull())
        assertEquals("device-a", identity.awaitOrNull())
        assertEquals(1, attempts)
    }
}
