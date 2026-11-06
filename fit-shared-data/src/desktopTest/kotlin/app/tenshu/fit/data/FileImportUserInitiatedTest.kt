package app.tenshu.fit.data

import app.tenshu.fit.data.xml.JvmXmlReader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.tenshu.core.UserInitiatedElement
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.health.proxy.healthServiceProxyImplementationFactory
import org.tenshu.health.proxy.initHealthService
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `control-background-work-off-fgs.md` §5.7: a picked Takeout file is always the user's act, so the
 * import's writes reach Control marked user-started (`userInitiatedWork { }`), which is what lets
 * Control hold its foreground service for them. Observed where the import reads its files.
 */
class FileImportUserInitiatedTest {

    @Test
    fun aFileImportRunsUnderTheUserStartedMark() = runTest {
        val registry = createCoreTestRegistry().apply {
            registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
        }
        val proxy = TestProxy(
            schemaRegistry = registry,
            coroutineScope = this,
            implementationFactoryOverride = { id, p -> healthServiceProxyImplementationFactory(id, p.fileDatabase) },
        ).also { it.executionContext.initHealthService() }
        val client: TenshuClient = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )
        val repo = FitProjectionRepository(
            client, proxy.tContext,
            fileParser = TakeoutParser(ActivityFileParser(xml = JvmXmlReader())),
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        val marked = mutableListOf<Boolean>()

        repo.importFiles(ActivityFileSource { marked += currentCoroutineContext()[UserInitiatedElement] != null })

        assertEquals(listOf(true), marked)
    }
}
