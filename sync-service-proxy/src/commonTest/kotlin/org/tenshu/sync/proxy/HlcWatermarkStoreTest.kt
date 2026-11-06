package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.sync.common.HlcWatermark
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The [org.tenshu.sync.common.HlcWatermarkStore] contract, exercised against the
 * delegate-backed implementation (`sync-hlc-tip-device-identity.md` TC-2a).
 *
 * The contract is small and the whole design rests on it: **`raiseTo` never lowers, and returns
 * the merged value.** A store that can be lowered re-creates the backward tip this whole track
 * exists to remove; one that does not return the merge leaves a lagging runtime republishing its
 * own stale position forever.
 */
class HlcWatermarkStoreTest {

    private fun store() = SecureStorageDelegateHlcWatermarkStore(InMemorySecureStorageDelegate())

    @Test
    fun unwrittenReadsAsNone() = runTest {
        assertEquals(HlcWatermark.NONE, store().read())
    }

    @Test
    fun raiseToAdvancesAndPersists() = runTest {
        val store = store()
        assertEquals(HlcWatermark(100L, 0L), store.raiseTo(HlcWatermark(100L, 0L)))
        assertEquals(HlcWatermark(100L, 0L), store.read())
    }

    @Test
    fun raiseToNeverLowers() = runTest {
        val store = store()
        store.raiseTo(HlcWatermark(200L, 0L))

        // The lagging runtime's own clock. It must come back with the *device's* position, not
        // its own — that returned value is what it then publishes and adopts locally.
        assertEquals(HlcWatermark(200L, 0L), store.raiseTo(HlcWatermark(100L, 0L)))
        assertEquals(HlcWatermark(200L, 0L), store.read())
    }

    @Test
    fun equalPhysicalTimeBreaksOnTheCounter() = runTest {
        val store = store()
        store.raiseTo(HlcWatermark(100L, 5L))
        // Same millisecond, lower counter — still a step backwards, because the HLC's order is
        // (physical, counter). Two runtimes writing inside one millisecond is exactly when this
        // matters, and it is the case a physical-time-only comparison would wave through.
        assertEquals(HlcWatermark(100L, 5L), store.raiseTo(HlcWatermark(100L, 3L)))
        assertEquals(HlcWatermark(100L, 7L), store.raiseTo(HlcWatermark(100L, 7L)))
    }

    @Test
    fun twoRuntimesOverOneStoreConvergeUpward() = runTest {
        // The shape of the live bug: two runtimes, one device, alternating positions. Whatever
        // order they arrive in, neither ever reads back something lower than what was written.
        val store = store()
        val a = store.raiseTo(HlcWatermark(1_000L, 0L))
        val b = store.raiseTo(HlcWatermark(940L, 0L))   // the idle sibling, 60 ms behind
        val c = store.raiseTo(HlcWatermark(1_100L, 0L))

        assertEquals(HlcWatermark(1_000L, 0L), a)
        assertEquals(HlcWatermark(1_000L, 0L), b, "the lagging runtime adopts the device position")
        assertEquals(HlcWatermark(1_100L, 0L), c)
    }

    @Test
    fun aCorruptRecordDegradesToNoneRatherThanThrowing() = runTest {
        val delegate = InMemorySecureStorageDelegate()
        delegate.storeString(SecureStorageDelegateHlcWatermarkStore.DEFAULT_KEY, "not-a-watermark")
        val store = SecureStorageDelegateHlcWatermarkStore(delegate)

        // The watermark is an optimisation over a clock that works without it. A corrupt entry
        // must cost us the optimisation, never the startup.
        assertEquals(HlcWatermark.NONE, store.read())
        assertEquals(HlcWatermark(50L, 0L), store.raiseTo(HlcWatermark(50L, 0L)))
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val watermark = HlcWatermark(1_787_922_439_939L, 7L)
        assertEquals(
            watermark,
            SecureStorageDelegateHlcWatermarkStore.decode(
                SecureStorageDelegateHlcWatermarkStore.encode(watermark)
            ),
        )
    }
}
