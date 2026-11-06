package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.DeviceLastSeen
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The "last seen" observation behind the roster line (`tech-debt.md`, *"a `Device` row that
 * outlives the identity it describes"*). It exists so a device that has gone quiet is visibly
 * different from one that has not — the fact that was missing when an orphaned row appeared as a
 * second, identically-named "iPhone".
 */
class DeviceLastSeenRecorderTest {

    private lateinit var testProxy: TestProxy
    private val own = "own-device"
    private val peer = "peer-device"

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
    }

    private fun recorder() = DeviceLastSeenRecorder(
        executionContext = testProxy.executionContext,
        ownDeviceId = { own },
    )

    private fun hlc(deviceId: String, ms: Long): HybridLogicalClock = HybridLogicalClock.create(
        context = testProxy.tContext,
        physicalTime = ms,
        logicalCounter = 0L,
        deviceId = deviceId,
    )

    private fun recorded(deviceId: String): Long? {
        val id = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = DeviceLastSeen.ID,
            keyValue = deviceId,
        )
        return (testProxy.executionContext.database.getData(id) as? DeviceLastSeen)?.lastSeenMs?.get()
    }

    @Test
    fun recordsAPeersWriteTime() {
        recorder().record(hlc(peer, 1_000_000L))
        assertEquals(1_000_000L, recorded(peer))
    }

    @Test
    fun doesNotRecordOurOwnWrites() {
        // Our own blocks are not an observation of anything, and the roster marks this device
        // separately. Recording them would also double every local save's store traffic.
        recorder().record(hlc(own, 1_000_000L))
        assertNull(recorded(own))
    }

    @Test
    fun ignoresAnEmptyAuthor() {
        recorder().record(hlc("", 1_000_000L))
        assertNull(recorded(""))
    }

    @Test
    fun neverMovesTheObservationBackwards() {
        // ⚠ Blocks arrive out of order — per-lane cursors drift arbitrarily — so an older block
        // reaching us later must not make a live device look staler than it is. A fresh recorder
        // models a restarted process, i.e. an empty throttle map, so the guard has to be the
        // *stored* value rather than the in-memory one.
        recorder().record(hlc(peer, 5_000_000L))
        recorder().record(hlc(peer, 1_000_000L))
        assertEquals(5_000_000L, recorded(peer))
    }

    @Test
    fun throttlesWritesWithinTheGranularityWindow() {
        val recorder = recorder()
        recorder.record(hlc(peer, 1_000_000L))
        // A burst: seconds apart, far below the 5-minute granularity. The stored value must stay
        // put — a row per ingested block is exactly the write amplification the store cannot take.
        recorder.record(hlc(peer, 1_001_000L))
        recorder.record(hlc(peer, 1_002_000L))
        assertEquals(1_000_000L, recorded(peer))
    }

    @Test
    fun advancesOnceTheWindowHasPassed() {
        val recorder = recorder()
        recorder.record(hlc(peer, 1_000_000L))
        recorder.record(hlc(peer, 1_000_000L + 6 * 60 * 1000L))
        assertEquals(1_000_000L + 6 * 60 * 1000L, recorded(peer))
    }

    @Test
    fun tracksEachDeviceIndependently() {
        val recorder = recorder()
        recorder.record(hlc(peer, 1_000_000L))
        recorder.record(hlc("other-peer", 2_000_000L))
        assertEquals(1_000_000L, recorded(peer))
        assertEquals(2_000_000L, recorded("other-peer"))
    }
}
