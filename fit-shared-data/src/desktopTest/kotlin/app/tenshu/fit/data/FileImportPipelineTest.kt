package app.tenshu.fit.data

import app.tenshu.fit.data.xml.JvmXmlReader
import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.ImportStatus
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.proxy.healthServiceProxyImplementationFactory
import org.tenshu.health.proxy.initHealthService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The **file-import → store → projector** round-trip: the repository's streaming `importFiles` parses
 * a GPX + a TCX file and persists via the shared [HealthImportWriter]; the workouts read back through
 * [FitProjectionRepository] identically to any other import. Mirrors [FitPipelineIntegrationTest].
 */
class FileImportPipelineTest {

    @Test
    fun importedFilesProjectAsWorkouts() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val parser = TakeoutParser(ActivityFileParser(xml = JvmXmlReader()))

        val gpx = ActivityFile(
            "run.gpx",
            """<gpx version="1.1"><trk><name>Morning run</name><type>running</type><trkseg>
               <trkpt lat="52.5200" lon="13.4050"><time>2026-07-06T07:14:00Z</time></trkpt>
               <trkpt lat="52.5230" lon="13.4090"><time>2026-07-06T07:44:00Z</time></trkpt>
               </trkseg></trk></gpx>""".encodeToByteArray(),
        )
        val tcx = ActivityFile(
            "ride.tcx",
            """<TrainingCenterDatabase><Activities><Activity Sport="Biking"><Id>2026-07-06T09:30:00Z</Id>
               <Lap StartTime="2026-07-06T09:30:00Z"><TotalTimeSeconds>3600</TotalTimeSeconds>
               <DistanceMeters>24000</DistanceMeters><Calories>600</Calories></Lap>
               </Activity></Activities></TrainingCenterDatabase>""".encodeToByteArray(),
        )

        val repo = FitProjectionRepository(client, proxy.tContext, fileParser = parser, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        repo.importFiles(ActivityFileSource.of(listOf(gpx, tcx)))
        assertEquals(
            ImportStatus.Imported(app.tenshu.fit.model.ImportSource.ImportFile, itemCount = 2),
            repo.importStatus().first(),
            "both files import one workout each, and the outcome is reported",
        )

        val workouts = repo.workouts().first()
        assertEquals(2, workouts.size)
        assertTrue(workouts.any { it.activityType == ActivityType.Running && it.title == "Morning run" })
        assertTrue(workouts.any { it.activityType == ActivityType.Cycling && it.distanceM == 24000 })
    }

    /**
     * Found by the Android store-screenshot walk (store-screenshots.md §8.1): coming back from the
     * system file picker foregrounds Fit, which starts the automatic Health Connect refresh — and the
     * picked file's import, arriving a few milliseconds later, found the import lock taken and was
     * dropped ("an import is already in flight — skipping this one"). A running Health Connect pass
     * does not cover a file, so the file must wait its turn, not vanish.
     */
    @Test
    fun aFilePickedWhileAHealthConnectPassRunsStillImports() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val parser = TakeoutParser(ActivityFileParser(xml = JvmXmlReader()))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slowSource = object : HealthImporter {
            override fun availableSources() = listOf(ImportSource.HealthConnect)
            override suspend fun import(source: ImportSource, categories: Set<CategoryId>, window: ImportWindow): ImportOutcome {
                started.complete(Unit)
                release.await()
                return ImportOutcome.Success(ImportedHealth())
            }
        }
        val repo = FitProjectionRepository(
            client, proxy.tContext, importer = slowSource, fileParser = parser,
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        val gpx = ActivityFile(
            "run.gpx",
            """<gpx version="1.1"><trk><name>Morning run</name><type>running</type><trkseg>
               <trkpt lat="52.5200" lon="13.4050"><time>2026-07-06T07:14:00Z</time></trkpt>
               <trkpt lat="52.5230" lon="13.4090"><time>2026-07-06T07:44:00Z</time></trkpt>
               </trkseg></trk></gpx>""".encodeToByteArray(),
        )

        val pass = launch { repo.startImport(CategoryId.entries.toSet(), ImportWindow.Recent, automatic = true) }
        started.await()
        val file = launch { repo.importFiles(ActivityFileSource.of(listOf(gpx))) }
        yield()
        release.complete(Unit)
        pass.join()
        file.join()

        assertTrue(
            repo.workouts().first().any { it.title == "Morning run" },
            "the picked file was dropped because a Health Connect pass held the import lock",
        )
    }

    private fun stackProxy(scope: CoroutineScope): TestProxy {
        val registry = createCoreTestRegistry().apply {
            registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
        }
        return TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, proxy -> healthServiceProxyImplementationFactory(id, proxy.fileDatabase) },
        ).also { it.executionContext.initHealthService() }
    }

    private fun clientOf(proxy: TestProxy): TenshuClient {
        val env = TenshuEnvironment(
            taskExecutor = proxy.localTaskExecutor,
            schemaRegistry = proxy.schemaRegistry,
            tContext = proxy.tContext,
            requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
        )
        return DefaultTenshuClient(env)
    }
}
