package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.trait.isHeadlessSafe
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleSpec
import org.tenshu.sync.WriteSliceSnapshots
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.WriteSliceSnapshotsImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The C8 write half's wiring, mirroring [DrainSyncTest] — everything about a scheduler-dispatched
 * op that is verifiable off-device: it is `HeadlessSafe`, its id dispatches, the production
 * factory routes it, and the schedule it registers carries the constraints that keep an
 * expensive altruistic pass off the user's path.
 */
class WriteSliceSnapshotsTest {

    @Test
    fun writeSliceSnapshots_isHeadlessSafe() {
        assertTrue(
            WriteSliceSnapshots.Schema.isHeadlessSafe(),
            "ScheduleService refuses to schedule an op that is not HeadlessSafe",
        )
    }

    @Test
    fun dispatchingTheId_firesTheWriteThunk() = runTest {
        val passes = IntArray(1)
        val proxy = TestProxy(
            implementationFactoryOverride = { id, _ ->
                if (id == WriteSliceSnapshots.ID) {
                    WriteSliceSnapshotsImplementation { passes[0]++ }
                } else {
                    null
                }
            },
        ).apply { schemaRegistry.registerConfig(syncServiceCommonModule) }

        proxy.executionContext.executeAsync<Data>(
            operationId = WriteSliceSnapshots.ID,
            caller = null,
        )

        assertEquals(1, passes[0], "the op schema must be registered so dispatch resolves it")
    }

    @Test
    fun productionFactory_routesTheId() {
        val crypto = BootstrapSyncCryptoManager()
        val context = createTestContext()
        val impl = syncProxyImplementationFactory(
            id = WriteSliceSnapshots.ID,
            relay = MockSyncRelay(context),
            syncManager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), context),
            cryptoManager = crypto,
            syncSchedulerHolder = SyncSchedulerHolder(),
        )

        assertTrue(
            impl is WriteSliceSnapshotsImplementation,
            "an op the schedule registry can address but the factory cannot build is a " +
                "registration that fails only at dispatch, in the background, silently",
        )
    }

    @Test
    fun theScheduleIsIdleAndChargingAndUnmetered() = runTest {
        // ⚠ The constraints are the design, not politeness: this pass walks the tree and re-seals
        // every slice **for other devices**, so it must be allowed to wait days rather than
        // compete with anything a user is waiting for.
        val recorded = mutableListOf<Map<String, Data>>()
        val proxy = TestProxy(
            implementationFactoryOverride = { id, _ ->
                if (id == Schedule.ID) RecordingSchedule(recorded) else null
            },
        ).apply { schemaRegistry.registerConfig(syncServiceCommonModule) }

        proxy.executionContext.registerSliceSnapshotSchedule()

        val params = recorded.single()
        assertEquals(
            WriteSliceSnapshots.ID,
            (params[Schedule.Params.OP_ID] as TString).get(),
        )
        val constraints = params[Schedule.Params.CONSTRAINT_SPEC] as ConstraintSpec
        assertEquals(ConstraintSpec.POWER_CHARGING, constraints.power.get())
        assertEquals(ConstraintSpec.IDLE_DEVICE_IDLE, constraints.idle.get())
        assertEquals(ConstraintSpec.NETWORK_UNMETERED, constraints.network.get())
        assertEquals(
            SLICE_SNAPSHOT_DEDUP_KEY,
            (params[Schedule.Params.DEDUP_KEY] as TString).get(),
            "without a stable dedup key every startup adds another item",
        )
    }

    @Test
    fun theFirstPassIsDelayedPastStartup() = runTest {
        // ⚠⚠ Regression guard for a bug found on a real device. `ScheduleDrainLoop` accumulates
        // its 1 s tick against `initialDelayMs` for a never-fired item, so **no delay means a
        // threshold of zero** — the only pass a process runs lands ~1 s after proxy init, when the
        // realm is typically still locked and catch-up still walking, both of which the writer
        // refuses to publish through. It then waits a full 24 h period, so a device that looks
        // correctly scheduled effectively never publishes.
        val recorded = mutableListOf<Map<String, Data>>()
        val proxy = TestProxy(
            implementationFactoryOverride = { id, _ ->
                if (id == Schedule.ID) RecordingSchedule(recorded) else null
            },
        ).apply { schemaRegistry.registerConfig(syncServiceCommonModule) }

        proxy.executionContext.registerSliceSnapshotSchedule()

        val spec = recorded.single()[Schedule.Params.SCHEDULE_SPEC] as ScheduleSpec
        assertEquals(
            SLICE_SNAPSHOT_INITIAL_DELAY_MS,
            spec.initialDelayMs?.get(),
            "the first pass must not fire at startup, where the writer's gate is always closed",
        )
        assertTrue(
            (spec.initialDelayMs?.get() ?: 0) > 0,
            "a zero or absent initial delay is the bug this test exists for",
        )
    }

    @Test
    fun aMissingScheduleOperationDoesNotEscapeAsAnUncaughtThrow() = runTest {
        // Same hazard `OfflineKeeperScheduleFailureTest` pins: the call site is a bare
        // `launch { … }` at proxy init with no CoroutineExceptionHandler, so a throw here would
        // reach the thread's uncaught handler and, on Android, kill Control. A wiring with no
        // `Schedule` implementation is the ordinary way to reach it.
        //
        // The assertion IS the absence of a throw — `runTest` fails on one.
        TestProxy().executionContext.registerSliceSnapshotSchedule()
    }

    private class RecordingSchedule(
        private val recorded: MutableList<Map<String, Data>>,
    ) : AsyncOperationImplementation {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Data?> execute(
            executionContext: org.tenshu.core.common.proxy.ExecutionContext,
            operationContext: org.tenshu.core.common.proxy.OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            recorded += params.orEmpty()
            return null as T
        }
    }
}
