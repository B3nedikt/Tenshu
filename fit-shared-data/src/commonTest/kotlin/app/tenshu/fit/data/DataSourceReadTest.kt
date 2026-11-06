package app.tenshu.fit.data

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.model.DataSourceState
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DS-3 end to end: rows written by DS-2's recorder, read back off the real `:health-service`
 * stack, flattened by `DataSourceReads` and projected by `DataSourceProjection`.
 *
 * `DataSourceProjectionTest` covers the *rules* in isolation; this covers the half that unit
 * test cannot — that the extraction reads the fields the projection expects. ⚠ A correct
 * projection fed by a broken extractor is green on both sides and empty on screen.
 *
 * The file-import cases live in `desktopTest` instead: `TakeoutParser` needs a platform XML
 * reader, which `commonTest` has no way to build.
 */
class DataSourceReadTest {

    private val DEVICE = "device-under-test"

    private class FakeImporter(private val outcome: ImportOutcome) : HealthImporter {
        override fun availableSources() = listOf(ImportSource.HealthConnect)
        override suspend fun import(
            source: ImportSource,
            categories: Set<CategoryId>,
            window: ImportWindow,
        ): ImportOutcome {
            yield()
            return outcome
        }
    }

    /** One store, so a second repository over it is the closest a unit test gets to a relaunch. */
    private class Store(scope: CoroutineScope) {
        val proxy: TestProxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().apply {
                registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
            },
            coroutineScope = scope,
            implementationFactoryOverride = { id, p ->
                healthServiceProxyImplementationFactory(id, p.fileDatabase)
            },
        ).also { it.executionContext.initHealthService() }

        val client: TenshuClient = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )
    }

    private fun Store.repository(importer: HealthImporter) = FitProjectionRepository(
        client,
        proxy.tContext,
        importer = importer,
        resolveDeviceId = { DEVICE },
        controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
    )

    private fun withWorkout() = ImportOutcome.Success(
        ImportedHealth(
            workouts = listOf(
                ImportedWorkout(
                    workoutId = "w1",
                    activityType = "Running",
                    source = "HealthConnect",
                    startMs = 1_000L,
                    endMs = 2_000L,
                    durationSec = 1,
                )
            ),
            dailyActivities = listOf(ImportedDailyActivity(date = "2026-08-13", steps = 4_699)),
        )
    )

    @Test
    fun anImportBecomesARowTheSectionCanRender() = runTest {
        val store = Store(this)
        val repo = store.repository(FakeImporter(withWorkout()))

        repo.startImport(setOf(CategoryId.Activity))

        val row = repo.dataSources().first { it.isNotEmpty() }.single()
        assertEquals(DataSourceKind.HealthConnect, row.kind)
        assertTrue(row.isThisDevice)
        assertNull(row.deviceLabel, "this device is not named back to the user")
        assertTrue(row.canImportNow)
        assertTrue(row.autoImport)

        val state = row.state
        assertTrue(state is DataSourceState.Imported, "got $state")
        assertEquals(2L, state.itemCount, "a workout plus a daily activity")
    }

    /**
     * ⚠⚠ **The behaviour the whole track exists for (§0).** A *second* repository over the same
     * store has a fresh, `Idle` `ImportStatus` — exactly what the next launch has — and the row
     * must still carry the figure, because it now comes from the durable row rather than from
     * process memory. Before DS-1/DS-2 this read would have been empty.
     */
    @Test
    fun theFigureSurvivesAProcessWithNoImportOfItsOwn() = runTest {
        val store = Store(this)
        store.repository(FakeImporter(withWorkout())).startImport(setOf(CategoryId.Activity))

        val relaunched = store.repository(FakeImporter(ImportOutcome.Success(ImportedHealth())))
        assertEquals(
            app.tenshu.fit.model.ImportStatus.Idle,
            relaunched.importStatus().value,
            "precondition: this repository has imported nothing itself",
        )

        val state = relaunched.dataSources().first { it.isNotEmpty() }.single().state
        assertTrue(state is DataSourceState.Imported, "got $state")
        assertEquals(2L, state.itemCount)
    }

    @Test
    fun switchingOffAutoImportIsVisibleOnTheRow() = runTest {
        val store = Store(this)
        val repo = store.repository(FakeImporter(withWorkout()))
        repo.startImport(setOf(CategoryId.Activity))
        assertTrue(repo.dataSources().first { it.isNotEmpty() }.single().autoImport)

        repo.setSourceAutoImport(DataSourceKind.HealthConnect, DEVICE, enabled = false)

        val rows = repo.dataSources().first { it.singleOrNull()?.autoImport == false }
        assertEquals(false, rows.single().autoImport)
    }

    /**
     * ⚠⚠ DS-7's degradation, and it is load-bearing rather than defensive. This harness registers
     * only the health modules — no `:identity-service` — which is exactly what an older Control
     * or a runtime that never declared the client module looks like. `client.identityService`
     * **throws synchronously** in that state, so an unguarded roster read takes the whole Data
     * sources section down rather than just the labels. It did: six read tests died on
     * `No schema registered for service id: tenshu/identity` before a row rendered.
     *
     * The stamped label is always renderable; the roster only ever improves it.
     */
    @Test
    fun theSectionRendersWithNoIdentityServiceAtAll() = runTest {
        val store = Store(this)
        val repo = store.repository(FakeImporter(withWorkout()))

        repo.startImport(setOf(CategoryId.Activity))

        val row = repo.dataSources().first { it.isNotEmpty() }.single()
        assertEquals(DataSourceKind.HealthConnect, row.kind)
        assertTrue(row.state is DataSourceState.Imported, "the section survives a missing roster")
    }

    @Test
    fun theBatchSectionIsAbsentWithoutAFileParser() = runTest {
        val store = Store(this)
        val repo = store.repository(FakeImporter(withWorkout()))
        repo.startImport(setOf(CategoryId.Activity))

        assertNull(
            repo.batchImport().first(),
            "a shell that cannot pick a file gets no batch-import row at all",
        )
    }
}
