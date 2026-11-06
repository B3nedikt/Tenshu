package app.tenshu.control.inference

import app.tenshu.control.ui.screens.models.ClientIndexingPause
import app.tenshu.control.ui.screens.models.ClientModelsConsents
import app.tenshu.control.ui.screens.models.ClientModelsRepository
import app.tenshu.control.ui.screens.models.ModelsDevices
import app.tenshu.control.ui.screens.models.OnDeviceModelsCapabilities
import app.tenshu.control.ui.screens.models.RosterDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.inference.IndexingPaused
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.client.readOnceForDevice
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.preference.proxy.preferenceServiceProxyImplementationFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pause row as both call sites read it — Photos' `ClientIndexingPause` and Control's
 * `ClientModelsRepository.indexingPaused()` — over the **real** preference service of a
 * `TestProxy`, whose device read emits nothing while the relation holds no row, as production's.
 */
class IndexingPauseRowTest {

    private class Phone(scope: CoroutineScope) {
        val proxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().apply {
                registerConfig(preferenceServiceCommonModule)
                registerConfig(preferenceServiceClientModule)
            },
            coroutineScope = scope,
            implementationFactoryOverride = { id, _ -> preferenceServiceProxyImplementationFactory(id) },
        )
        val client: TenshuClient = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )

        /** Blank until the case says the device has its id. */
        var id = ""
        val devices = object : ModelsDevices {
            override suspend fun deviceId(): String = id
            override val roster: Flow<List<RosterDevice>?> = flowOf(null)
        }
    }

    private object NoPass : IndexProgressSource {
        override val progress: Flow<IndexPassPosition?> = flowOf(null)
    }

    /** The two production call sites, each as (its read, its write). */
    private fun sites(phone: Phone, scope: CoroutineScope): Map<String, Pair<() -> Flow<Boolean>, suspend (Boolean) -> Unit>> {
        val pause = ClientIndexingPause(phone.client, ClientModelsConsents(phone.client, phone.devices, OnDeviceModelsCapabilities.features))
        val control = ClientModelsRepository(phone.client, phone.devices, NoPass, scope = scope)
        return mapOf(
            "ClientIndexingPause" to (pause::paused to pause::set),
            "ClientModelsRepository" to (control::indexingPaused to control::setIndexingPaused),
        )
    }

    private suspend fun TestScope.check(site: String, case: suspend TestScope.(Phone, () -> Flow<Boolean>, suspend (Boolean) -> Unit) -> Unit) {
        val phone = Phone(backgroundScope)
        val (read, write) = sites(phone, backgroundScope).getValue(site)
        case(phone, read, write)
    }

    /**
     * Opened before the id was known; paused under the placeholder; the real id arrives with no
     * row of its own. ⚠⚠ The relation then holds the placeholder's row only, and the real id's
     * read must say **not paused** — what the pass reads — rather than keep the placeholder's.
     */
    private fun aPauseUnderThePlaceholderIsNotShownOnceTheRealIdIsKnown(site: String) = runTest {
        check(site) { phone, read, write ->
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { read().collect { seen += it } }
            runCurrent()
            write(true)
            runCurrent()
            assertEquals(true, seen.lastOrNull(), "$site: the pause under the placeholder was not read: $seen")

            phone.id = "phone-1"
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(false, seen.last(), "$site: the placeholder's pause is still shown under the real id: $seen")
        }
    }

    /** ⚠ And the other way: a pause written under the real id is shown, so *Resume* is offered. */
    private fun aPauseUnderTheRealIdIsShown(site: String) = runTest {
        check(site) { phone, read, write ->
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { read().collect { seen += it } }
            runCurrent()
            phone.id = "phone-1"
            advanceTimeBy(2_000)
            runCurrent()
            write(true)
            runCurrent()
            assertEquals(true, seen.lastOrNull(), "$site: a pause under the real id is not shown: $seen")
            // The row the pass reads.
            assertEquals(true, phone.client.preferenceService.readOnceForDevice(IndexingPaused, "phone-1"))
        }
    }

    /** A device that never paused: the relation holds no row, and the read still answers. */
    private fun aDeviceWithNoRowAtAllReadsNotPaused(site: String) = runTest {
        check(site) { _, read, _ ->
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { read().collect { seen += it } }
            runCurrent()
            assertEquals(listOf(false), seen, "$site: an empty relation said nothing")
        }
    }

    @Test fun photosPauseUnderThePlaceholderIsNotShownOnceTheRealIdIsKnown() = aPauseUnderThePlaceholderIsNotShownOnceTheRealIdIsKnown("ClientIndexingPause")
    @Test fun controlsPauseUnderThePlaceholderIsNotShownOnceTheRealIdIsKnown() = aPauseUnderThePlaceholderIsNotShownOnceTheRealIdIsKnown("ClientModelsRepository")
    @Test fun photosPauseUnderTheRealIdIsShown() = aPauseUnderTheRealIdIsShown("ClientIndexingPause")
    @Test fun controlsPauseUnderTheRealIdIsShown() = aPauseUnderTheRealIdIsShown("ClientModelsRepository")
    @Test fun photosDeviceWithNoRowReadsNotPaused() = aDeviceWithNoRowAtAllReadsNotPaused("ClientIndexingPause")
    @Test fun controlsDeviceWithNoRowReadsNotPaused() = aDeviceWithNoRowAtAllReadsNotPaused("ClientModelsRepository")
}
