package app.tenshu.fit.data

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportWindow
import kotlinx.coroutines.CoroutineScope
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
 * `control-background-work-off-fgs.md` §5.7: Control holds its `dataSync` foreground service only
 * for work a client marks user-started (`userInitiatedWork { }`). A manual Fit import is the user's
 * act and is marked, so its writes reach Control as user-started; an automatic pass never is —
 * Play allows no foreground service for it.
 *
 * ⚠ The mark is a coroutine-context element that the transport reads per call, so "the import ran
 * under it" is observed where the import's own reads and writes run: inside the importer.
 */
class ImportUserInitiatedTest {

    /** Records, for each import, whether it ran under the user-started mark. */
    private class MarkRecordingImporter : HealthImporter {
        val marked = mutableListOf<Boolean>()
        override fun availableSources() = listOf(ImportSource.HealthConnect)
        override suspend fun import(source: ImportSource, categories: Set<CategoryId>, window: ImportWindow): ImportOutcome {
            marked += currentCoroutineContext()[UserInitiatedElement] != null
            return ImportOutcome.Success(
                ImportedHealth(dailyActivities = listOf(ImportedDailyActivity(date = "2026-07-13", steps = 4_699))),
            )
        }
    }

    @Test
    fun aManualImportIsMarkedUserStarted_andAnAutomaticPassIsNot() = runTest {
        val importer = MarkRecordingImporter()
        val repo = repository(this, importer)

        repo.startImport(CategoryId.entries.toSet(), automatic = false)
        repo.startImport(CategoryId.entries.toSet(), automatic = true)

        assertEquals(listOf(true, false), importer.marked)
    }

    private fun repository(scope: CoroutineScope, importer: HealthImporter): FitProjectionRepository {
        val registry = createCoreTestRegistry().apply {
            registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
        }
        val proxy = TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, proxy -> healthServiceProxyImplementationFactory(id, proxy.fileDatabase) },
        ).also { it.executionContext.initHealthService() }
        val client: TenshuClient = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )
        return FitProjectionRepository(client, proxy.tContext, importer = importer, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
    }
}
