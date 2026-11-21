package app.tenshu.photos

import app.tenshu.photos.data.TenshuPhotosRepository
import app.tenshu.photos.data.asRevisionChanges
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.inference.client.inferenceServiceClientModule
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.inference.proxy.InMemoryEmbeddingStore
import org.tenshu.inference.proxy.InferenceBackendRegistry
import org.tenshu.inference.proxy.inferenceServiceProxyImplementationFactory
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * [TenshuPhotosRepository.peopleIndexChanges] over the real op (`photos-pets.md` §10.19): Control's
 * revision becomes Collections' changes — the subscription's baseline is NOT one, every move is.
 */
class PeopleIndexChangesReadTest {

    @Test
    fun theBaselineIsNotAChangeAndEveryMoveIs() = runTest(timeout = 30.seconds) {
        val revision = MutableStateFlow(7L)
        val proxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().apply {
                registerConfig(preferenceServiceCommonModule)
                registerConfig(inferenceServiceCommonModule)
                registerConfig(inferenceServiceClientModule)
            },
            coroutineScope = backgroundScope,
            implementationFactoryOverride = { id, _ ->
                inferenceServiceProxyImplementationFactory(
                    id = id,
                    registry = InferenceBackendRegistry(emptyList()),
                    embeddings = InMemoryEmbeddingStore(),
                    deviceId = "device-a",
                    peopleRevision = revision,
                )
            },
        )
        val client = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )
        val repository = TenshuPhotosRepository(client, clockMs = { 1_000L }, reachability = null /* test */)

        val changes = MutableStateFlow(0)
        backgroundScope.launch(Dispatchers.Default) { repository.peopleIndexChanges().collect { changes.value++ } }
        suspend fun until(what: () -> Boolean) = withContext(Dispatchers.Default) { withTimeout(5_000) { while (!what()) delay(10) } }

        until { revision.subscriptionCount.value == 1 }
        revision.value = 8L
        until { changes.value >= 1 }
        revision.value = 0L
        until { changes.value >= 2 }
    }

    /** The mapping alone, deterministically: the baseline is not a change, a repeat is not, a reset is. */
    @Test
    fun aRevisionsBaselineAndRepeatsAreNotChanges() = runTest {
        assertEquals(0, flowOf(7L).asRevisionChanges().toList().size, "the baseline was a change")
        assertEquals(0, flowOf(7L, 7L).asRevisionChanges().toList().size, "a repeat was a change")
        // ⚠ A restarted Control counts from 0 again: lower is still different, still a change.
        assertEquals(2, flowOf(7L, 8L, 0L).asRevisionChanges().toList().size)
    }
}
