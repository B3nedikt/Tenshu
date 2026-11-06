package app.tenshu.control.di

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.inference.MediaIndexPass
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.inference.proxy.IndexableMedia
import org.tenshu.inference.proxy.IndexingConditions
import org.tenshu.inference.proxy.InferenceBackendRegistry
import org.tenshu.inference.proxy.InMemoryEmbeddingStore
import org.tenshu.inference.proxy.MEDIA_INDEX_DEDUP_KEY
import org.tenshu.inference.proxy.MediaIndexer
import org.tenshu.inference.proxy.MediaSource
import org.tenshu.inference.proxy.inferenceServiceProxyImplementationFactory
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.inference.proxy.registerMediaIndexSchedule
import org.tenshu.core.collection.list.toTList
import org.tenshu.sync.CancelScheduled
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.ListScheduled
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.ScheduleItem
import org.tenshu.sync.ScheduleSpec
import org.tenshu.sync.Schedule
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What registering the media-index schedule asks the scheduler for, and what dispatching the row
 * does (`ios-on-device-inference.md` §13). Android and desktop register it; iOS registers nothing.
 */
class MediaIndexScheduleTest {

    private val calls = mutableListOf<Pair<String, Map<String, Data>>>()

    /** The rows `ListScheduled` answers with, by dedup key. */
    private var scheduled = listOf(MEDIA_INDEX_DEDUP_KEY)

    private inner class Recording(private val id: String) : AsyncOperationImplementation {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            if (id == ListScheduled.ID) {
                val context = executionContext.context
                return scheduled.map { key ->
                    ScheduleItem.create(
                        context = context,
                        dedupKey = key,
                        opId = "some/op",
                        scheduleSpec = ScheduleSpec.create(context, kind = ScheduleSpec.KIND_PERIODIC, minPeriodMs = 1L),
                        constraintSpec = ConstraintSpec.create(context),
                        retryPolicy = RetryPolicy.create(context),
                    )
                }.toTList(context) as T
            }
            calls += id to params.orEmpty()
            return null as T
        }
    }

    private fun proxy(resolving: Set<String> = setOf(Schedule.ID, CancelScheduled.ID, ListScheduled.ID)) = TestProxy(
        implementationFactoryOverride = { id, _ -> if (id in resolving) Recording(id) else null },
    ).apply { schemaRegistry.registerConfig(syncServiceCommonModule) }

    @Test
    fun registeringSchedulesTheMediaIndexKeyAndCancelsNothing() = runTest {
        proxy().executionContext.registerMediaIndexSchedule()

        val (operation, params) = calls.single()
        assertEquals(Schedule.ID, operation)
        assertEquals(MEDIA_INDEX_DEDUP_KEY, (params[Schedule.Params.DEDUP_KEY] as TString).get())
    }

    private fun proxyDispatching(walked: MutableList<String>) = TestProxy(
        implementationFactoryOverride = { id, _ ->
            when (id) {
                MediaIndexPass.ID -> {
                    val embeddings = InMemoryEmbeddingStore()
                    val registry = InferenceBackendRegistry()
                    inferenceServiceProxyImplementationFactory(
                        id = id,
                        registry = registry,
                        embeddings = embeddings,
                        deviceId = "device-a",
                        indexer = MediaIndexer(registry = registry, deviceId = "device-a", embeddings = embeddings, conditions = IndexingConditions { true }),
                        mediaSource = object : MediaSource {
                            override suspend fun media(executionContext: ExecutionContext): List<IndexableMedia> {
                                walked += "library"
                                return emptyList()
                            }
                        },
                    )
                }
                Schedule.ID, CancelScheduled.ID, ListScheduled.ID -> Recording(id)
                else -> null
            }
        },
    ).apply {
        schemaRegistry.registerConfig(syncServiceCommonModule)
        schemaRegistry.registerConfig(preferenceServiceCommonModule)
        schemaRegistry.registerConfig(inferenceServiceCommonModule)
    }

    /** What a drain loop does with the row: it executes the row's op, which runs the pass and cancels nothing. */
    @Test
    fun beingDispatchedRunsThePassAndCancelsNothing() = runTest {
        val walked = mutableListOf<String>()
        val proxy = proxyDispatching(walked)

        proxy.executionContext.executeAsync<Data?>(MediaIndexPass.ID, caller = null)

        assertEquals(listOf("library"), walked)
        assertEquals(emptyList(), calls)
    }
}
