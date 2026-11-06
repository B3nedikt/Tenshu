package app.tenshu.control.data

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.core.TContext
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Path A decision logic (`bgproc-scheduling-roles.md`): the pure
 * [constraintAllowsSync] truth table the [ConstraintSyncGate] delegates to.
 * `SyncSchedulerGateTest` (`:sync-service-proxy`) covers the scheduler honouring
 * a [org.tenshu.sync.common.SyncGate]; together they cover Path A end-to-end.
 */
class ConstraintSyncGateTest {

    private fun syncContext(): TContext =
        TestProxy(schemaRegistry = createSyntheticTestRegistry())
            .apply { schemaRegistry.registerConfig(syncServiceCommonModule) }
            .tContext

    @Test
    fun wifiOnly_allowsOnlyUnmetered() {
        val wifiOnly = ConstraintSpec.create(syncContext(), network = ConstraintSpec.NETWORK_UNMETERED)
        assertTrue(constraintAllowsSync(wifiOnly, NetworkStatus.UNMETERED))
        assertFalse(constraintAllowsSync(wifiOnly, NetworkStatus.METERED))
        assertFalse(constraintAllowsSync(wifiOnly, NetworkStatus.NONE))
    }

    @Test
    fun any_allowsEveryLiveConnection() {
        val any = ConstraintSpec.create(syncContext(), network = ConstraintSpec.NETWORK_ANY)
        assertTrue(constraintAllowsSync(any, NetworkStatus.UNMETERED))
        assertTrue(constraintAllowsSync(any, NetworkStatus.METERED))
        assertFalse(constraintAllowsSync(any, NetworkStatus.NONE))
    }

    @Test
    fun nullConstraint_failsOpenUnlessOffline() {
        assertTrue(constraintAllowsSync(null, NetworkStatus.UNMETERED))
        assertTrue(constraintAllowsSync(null, NetworkStatus.METERED))
        assertFalse(constraintAllowsSync(null, NetworkStatus.NONE))
    }
}
