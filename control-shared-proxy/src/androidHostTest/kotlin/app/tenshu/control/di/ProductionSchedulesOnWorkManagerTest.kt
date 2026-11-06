package app.tenshu.control.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tenshu.calendar.DeliverDueReminders
import org.tenshu.calendar.common.calendarServiceCommonModule
import org.tenshu.calendar.proxy.registerSubscriptionRefreshSchedule
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.registerOfflineKeeperSchedule
import org.tenshu.file.proxy.registerThumbnailBackfillSchedule
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.inference.proxy.registerMediaIndexSchedule
import org.tenshu.routine.EvaluateDueRoutines
import org.tenshu.routine.common.routineServiceCommonModule
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.DrainSync
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleItem
import org.tenshu.sync.ScheduleSpec
import org.tenshu.sync.proxy.MockSyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.BackgroundSyncScheduler
import org.tenshu.sync.proxy.PlatformScheduler
import org.tenshu.sync.proxy.SyncManagerImpl
import org.tenshu.sync.proxy.WorkManagerScheduler
import org.tenshu.sync.proxy.registerContentBackfillSchedule
import org.tenshu.sync.proxy.registerSliceSnapshotSchedule
import org.tenshu.sync.proxy.syncProxyImplementationFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that every production schedule registers through the REAL [WorkManagerScheduler] — the path
 * where WorkManager rejects what common tests accept, e.g. *"Cannot set backoff criteria on an idle
 * mode job"* (`control-background-work-off-fgs.md` §3.6). And each one's ownership: an owned item
 * has WorkManager as its only driver (§5.6), so a wrong answer either leaves an idle+charging pass
 * to the in-process loop or strands an item nothing runs.
 *
 * ⚠ The routine and the reminder are registered with their armers' exact parameters, not through
 * the armers themselves, which are internal to their modules. What they pin is the punctual path:
 * a punctual one-shot is the alarm's, never owned, and its row survives a failed registration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProductionSchedulesOnWorkManagerTest {

    private lateinit var context: Context

    /** The real scheduler, with a record of what it was asked and what it answered. */
    private inner class Recording(val real: WorkManagerScheduler) : PlatformScheduler {
        val owned = mutableMapOf<String, Boolean>()
        val failures = mutableMapOf<String, Throwable>()
        override fun register(item: ScheduleItem, punctual: Boolean) {
            val key = item.dedupKey.get()
            owned[key] = real.ownsDispatch(item, punctual)
            try {
                real.register(item, punctual)
            } catch (t: Throwable) {
                failures[key] = t
                throw t
            }
        }
        override fun ownsDispatch(item: ScheduleItem, punctual: Boolean) = real.ownsDispatch(item, punctual)
        override fun cancel(dedupKey: String) = real.cancel(dedupKey)
        override fun cancelAll() = real.cancelAll()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Test
    fun everyProductionScheduleRegisters_andIsOwnedExactlyWhenItHasAConstraintTheLoopCannotCheck() = runTest {
        val recording = Recording(WorkManagerScheduler(context))
        val registry = createCoreTestRegistry().apply {
            registerConfig(syncServiceCommonModule)
            registerConfig(fileServiceCommonModule)
            registerConfig(calendarServiceCommonModule)
            registerConfig(inferenceServiceCommonModule)
            registerConfig(routineServiceCommonModule)
            // The op schemas the armers schedule are registered by the client modules.
            registerConfig(org.tenshu.routine.client.routineServiceClientModule)
            registerConfig(org.tenshu.calendar.client.calendarServiceClientModule)
        }
        lateinit var syncManager: SyncManagerImpl
        val proxy = TestProxy(schemaRegistry = registry, coroutineScope = backgroundScope) { id, p ->
            syncProxyImplementationFactory(
                id, MockSyncRelay(p.tContext), syncManager, p.tContext.syncCryptoManager(),
                platformScheduler = recording,
            )
        }
        syncManager = SyncManagerImpl(
            cryptoManager = proxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        val ctx = proxy.executionContext

        ctx.registerOfflineKeeperSchedule()
        ctx.registerThumbnailBackfillSchedule()
        ctx.registerSubscriptionRefreshSchedule()
        ctx.registerMediaIndexSchedule()
        ctx.registerContentBackfillSchedule()
        ctx.registerSliceSnapshotSchedule()
        // Both DrainSync variants: any connection, and the realm's Wi-Fi-only policy.
        for ((key, network) in listOf("drain-any" to ConstraintSpec.NETWORK_ANY, "drain-wifi" to ConstraintSpec.NETWORK_UNMETERED)) {
            BackgroundSyncScheduler(
                executionContext = ctx,
                constraints = flowOf(ConstraintSpec.create(ctx.context, network = network)),
                scope = this,
                opId = DrainSync.ID,
                periodMs = 60L * 60 * 1000,
                dedupKey = key,
            ).start()
        }
        testScheduler.advanceUntilIdle()
        // The armers' exact parameters (RoutineScheduleArmer / ReminderArmer).
        ctx.oneShot(EvaluateDueRoutines.ID, "tenshu/routine/nextWake")
        ctx.oneShot(DeliverDueReminders.ID, "tenshu/calendar/nextReminder")
        // A punctual op that ALSO carries a constraint the loop cannot check. Still the
        // alarm's, so never owned — and the loop stays its fallback.
        ctx.oneShot(EvaluateDueRoutines.ID, "punctual-with-constraint", network = ConstraintSpec.NETWORK_UNMETERED)

        assertEquals(emptyMap(), recording.failures, "no production schedule may fail to register")
        val expected = mapOf(
            "tenshu/file/offlineKeeper" to false,          // no network: eviction runs on cellular (S4)
            "tenshu/calendar/subscriptionRefresh" to true,
            "tenshu/inference/mediaIndexPass" to true,
            "sync/contentDeclarationBackfill" to true,
            "drain-any" to false,
            "drain-wifi" to true,
            "tenshu/routine/nextWake" to false,             // punctual: the alarm's (S5)
            "tenshu/calendar/nextReminder" to false,
            "punctual-with-constraint" to false,
        )
        for ((key, owned) in expected) {
            assertEquals(owned, recording.owned[key], "ownership of $key; recorded ${recording.owned}")
        }
        assertTrue(recording.owned.size >= expected.size + 2, "thumbnail backfill and slice snapshots registered too: ${recording.owned.keys}")
        assertTrue(recording.owned.filterKeys { it !in expected }.values.all { it }, "the idle+charging passes are owned: ${recording.owned}")
        // And WorkManager really holds the media index pass (§3.6).
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork("tenshu/inference/mediaIndexPass").get()
        assertEquals(1, work.size, "the index pass has its job")
    }

    private suspend fun org.tenshu.core.common.proxy.ExecutionContext.oneShot(
        opId: String,
        key: String,
        network: String = ConstraintSpec.NETWORK_ANY,
    ) {
        executeAsync<Data>(
            operationId = Schedule.ID,
            caller = null,
            params = mapOf(
                Schedule.Params.OP_ID to opId.toTString(context),
                Schedule.Params.SCHEDULE_SPEC to ScheduleSpec.create(context, kind = ScheduleSpec.KIND_ONE_SHOT, initialDelayMs = 60_000),
                Schedule.Params.CONSTRAINT_SPEC to ConstraintSpec.create(context, network = network),
                Schedule.Params.RETRY_POLICY to RetryPolicy.create(context),
                Schedule.Params.DEDUP_KEY to key.toTString(context),
            ),
        )
    }
}
