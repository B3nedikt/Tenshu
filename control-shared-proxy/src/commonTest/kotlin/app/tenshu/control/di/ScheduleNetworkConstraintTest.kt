package app.tenshu.control.di

import kotlinx.coroutines.test.runTest
import org.tenshu.calendar.proxy.registerSubscriptionRefreshSchedule
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.file.proxy.registerOfflineKeeperSchedule
import org.tenshu.file.proxy.registerThumbnailBackfillSchedule
import org.tenshu.inference.proxy.registerMediaIndexSchedule
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.Schedule
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.NetworkRequirement
import org.tenshu.sync.proxy.networkRequirement
import org.tenshu.sync.proxy.registerContentBackfillSchedule
import org.tenshu.sync.proxy.registerSliceSnapshotSchedule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What each Control-registered maintenance schedule asks the **platform** scheduler for, network-wise
 * — the value WorkManager stores (`NetworkRequirement.NONE` → `NOT_REQUIRED`, `CONNECTED`,
 * `UNMETERED`). Lives here because this is the one test module that sees every `-proxy` that
 * registers a schedule *and* `:sync-service-proxy`'s [networkRequirement].
 *
 * ⚠⚠ Each schedule's own test asserts the [ConstraintSpec] string it builds; none of them could see
 * that leaving `network` out (`NETWORK_ANY`, the default) is folded to "needs a connection", which
 * makes a job like the media index pass wait for a network it never uses
 * (`media-processing-pipeline-sweep.md` F3.2 / D7). This pins the *mapped* outcome, schedule by
 * schedule, so a changed default or a changed fold names the job it moved.
 */
class ScheduleNetworkConstraintTest {

    private suspend fun requirementOf(register: suspend ExecutionContext.() -> Unit): NetworkRequirement {
        val recorded = mutableListOf<Map<String, Data>>()
        val proxy = TestProxy(
            implementationFactoryOverride = { id, _ -> if (id == Schedule.ID) RecordingSchedule(recorded) else null },
        ).apply { schemaRegistry.registerConfig(syncServiceCommonModule) }

        proxy.executionContext.register()

        // ⚠ Anti-vacuity: every register* swallows its own failure (best-effort at proxy init), so a
        // registration that threw would otherwise read as "no constraint".
        val params = recorded.single()
        return (params[Schedule.Params.CONSTRAINT_SPEC] as ConstraintSpec).networkRequirement()
    }

    /** D7: the pass reads local bytes and runs a local model — WorkManager must not wait for a network. */
    @Test
    fun theMediaIndexPassRequiresNoNetwork() = runTest {
        assertEquals(NetworkRequirement.NONE, requirementOf { registerMediaIndexSchedule() })
    }

    /** Content- and upload-bearing jobs keep their network requirement. */
    @Test
    fun contentAndUploadJobsStillRequireANetwork() = runTest {
        assertEquals(NetworkRequirement.UNMETERED, requirementOf { registerThumbnailBackfillSchedule() })
        assertEquals(NetworkRequirement.UNMETERED, requirementOf { registerContentBackfillSchedule() })
        assertEquals(NetworkRequirement.UNMETERED, requirementOf { registerSliceSnapshotSchedule() })
        // ⚠ Not UNMETERED: the evict half must run on cellular, so the item
        // asks for no network and the download half refuses a metered network itself.
        assertEquals(NetworkRequirement.NONE, requirementOf { registerOfflineKeeperSchedule() })
        assertEquals(NetworkRequirement.UNMETERED, requirementOf { registerSubscriptionRefreshSchedule() })
    }

    /** The fold itself: `NETWORK_ANY` — `ConstraintSpec.create`'s default — is a connection, not "none". */
    @Test
    fun theDefaultNetworkIsAConnectionNotNone() = runTest {
        val context = TestProxy().apply { schemaRegistry.registerConfig(syncServiceCommonModule) }.tContext
        assertEquals(NetworkRequirement.CONNECTED, ConstraintSpec.create(context).networkRequirement())
        assertEquals(
            NetworkRequirement.NONE,
            ConstraintSpec.create(context, network = ConstraintSpec.NETWORK_NONE).networkRequirement(),
        )
    }

    private class RecordingSchedule(
        private val recorded: MutableList<Map<String, Data>>,
    ) : AsyncOperationImplementation {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            recorded += params.orEmpty()
            return null as T
        }
    }
}
