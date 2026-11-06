package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.ScheduleItem
import org.tenshu.sync.ScheduleSpec
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The platform-agnostic half of the Android `WorkManagerScheduler` (bgproc §5.6):
 * `ScheduleItem` → [ScheduledWorkPlan]. Unit-tested here without Android so the
 * error-prone constraint/schedule vocabulary mapping is covered even though the
 * `WorkRequest` translation itself is device-gated.
 */
class ScheduledWorkPlanTest {

    private val ctx = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        .apply { schemaRegistry.registerConfig(syncServiceCommonModule) }
        .executionContext.context

    private fun item(
        dedupKey: String = "job",
        opId: String = "test/op",
        kind: String = ScheduleSpec.KIND_PERIODIC,
        minPeriodMs: Long? = 900_000L,
        initialDelayMs: Long? = null,
        network: String = ConstraintSpec.NETWORK_ANY,
        power: String = ConstraintSpec.POWER_ANY,
        idle: String = ConstraintSpec.IDLE_ANY,
        storage: String = ConstraintSpec.STORAGE_ANY,
        battery: String = ConstraintSpec.BATTERY_ANY,
        retryPolicy: RetryPolicy = RetryPolicy.create(ctx),
    ): ScheduleItem = ScheduleItem.create(
        context = ctx,
        dedupKey = dedupKey,
        opId = opId,
        scheduleSpec = ScheduleSpec.create(ctx, kind = kind, minPeriodMs = minPeriodMs, initialDelayMs = initialDelayMs),
        constraintSpec = ConstraintSpec.create(ctx, network = network, power = power, idle = idle, storage = storage, battery = battery),
        retryPolicy = retryPolicy,
    )

    @Test
    fun periodicItem_carriesScheduleFields() {
        val plan = item(dedupKey = "sync-drain", opId = "test/drain", minPeriodMs = 3_600_000L).toWorkPlan()
        assertEquals("sync-drain", plan.dedupKey)
        assertEquals("test/drain", plan.opId)
        assertTrue(plan.periodic)
        assertEquals(3_600_000L, plan.minPeriodMs)
    }

    @Test
    fun oneShotItem_isNotPeriodic() {
        val plan = item(kind = ScheduleSpec.KIND_ONE_SHOT, minPeriodMs = null, initialDelayMs = 5_000L).toWorkPlan()
        assertFalse(plan.periodic)
        assertEquals(5_000L, plan.initialDelayMs)
    }

    @Test
    fun networkVocabulary_mapsToRequirement() {
        assertEquals(NetworkRequirement.UNMETERED, item(network = ConstraintSpec.NETWORK_UNMETERED).toWorkPlan().network)
        assertEquals(NetworkRequirement.CONNECTED, item(network = ConstraintSpec.NETWORK_ANY).toWorkPlan().network)
        assertEquals(NetworkRequirement.NONE, item(network = ConstraintSpec.NETWORK_NONE).toWorkPlan().network)
    }

    @Test
    fun powerStorageBatteryIdle_flagsMap() {
        val plan = item(
            power = ConstraintSpec.POWER_CHARGING,
            idle = ConstraintSpec.IDLE_DEVICE_IDLE,
            storage = ConstraintSpec.STORAGE_NOT_LOW,
            battery = ConstraintSpec.BATTERY_NOT_LOW,
        ).toWorkPlan()
        assertTrue(plan.requireCharging)
        assertTrue(plan.requireDeviceIdle)
        assertTrue(plan.requireStorageNotLow)
        assertTrue(plan.requireBatteryNotLow)
    }

    @Test
    fun defaultConstraints_areAllRelaxed() {
        val plan = item().toWorkPlan()
        assertEquals(NetworkRequirement.CONNECTED, plan.network)
        assertFalse(plan.requireCharging)
        assertFalse(plan.requireDeviceIdle)
        assertFalse(plan.requireStorageNotLow)
        assertFalse(plan.requireBatteryNotLow)
    }

    /**
     * Media pipeline P4: a deferred run becomes `Result.retry()` on Android, so the backoff is what
     * makes it "about 15 minutes". ⚠ The default policy (1 s) must stay "unset", or every existing
     * item's failure retries would move from WorkManager's 30 s default to its 10 s floor.
     */
    @Test
    fun retryPolicy_aboveThePlatformFloor_becomesTheBackoff() {
        val plan = item(
            retryPolicy = RetryPolicy.create(ctx, backoff = RetryPolicy.BACKOFF_LINEAR, initialBackoffMs = 900_000L),
        ).toWorkPlan()
        assertEquals(WorkBackoff(exponential = false, initialMs = 900_000L), plan.backoff)
    }

    /**
     * `control-background-work-off-fgs.md` §3.6: WorkManager's builder throws "Cannot set backoff
     * criteria on an idle mode job", and that throw left the media index pass with no job on Android.
     */
    @Test
    fun anIdleModeItem_carriesNoBackoff_whateverItsRetryPolicyAsks() {
        val retry = RetryPolicy.create(ctx, backoff = RetryPolicy.BACKOFF_LINEAR, initialBackoffMs = 900_000L)
        assertNull(item(idle = ConstraintSpec.IDLE_DEVICE_IDLE, power = ConstraintSpec.POWER_CHARGING, retryPolicy = retry).toWorkPlan().backoff)
        // Charging alone is not idle mode: the backoff stands.
        assertEquals(
            WorkBackoff(exponential = false, initialMs = 900_000L),
            item(power = ConstraintSpec.POWER_CHARGING, retryPolicy = retry).toWorkPlan().backoff,
        )
    }

    /** §5.6: every constraint the in-process loop cannot check makes the item the OS scheduler's alone. */
    @Test
    fun platformOwned_isEveryConstraintTheLoopCannotCheck() {
        assertFalse(item().toWorkPlan().platformOwned, "any connection: the loop's process has what it has")
        assertFalse(item(network = ConstraintSpec.NETWORK_NONE).toWorkPlan().platformOwned)
        assertTrue(item(network = ConstraintSpec.NETWORK_UNMETERED).toWorkPlan().platformOwned)
        assertTrue(item(power = ConstraintSpec.POWER_CHARGING).toWorkPlan().platformOwned)
        assertTrue(item(idle = ConstraintSpec.IDLE_DEVICE_IDLE).toWorkPlan().platformOwned)
        assertTrue(item(storage = ConstraintSpec.STORAGE_NOT_LOW).toWorkPlan().platformOwned)
        assertTrue(item(battery = ConstraintSpec.BATTERY_NOT_LOW).toWorkPlan().platformOwned)
        // A punctual one-shot is the alarm's, and the loop stays its fallback.
        assertFalse(
            item(kind = ScheduleSpec.KIND_ONE_SHOT, minPeriodMs = null, network = ConstraintSpec.NETWORK_UNMETERED)
                .toWorkPlan { true }.platformOwned,
        )
    }

    @Test
    fun defaultRetryPolicy_leavesThePlatformBackoffAlone() {
        assertNull(item().toWorkPlan().backoff)
    }
}
