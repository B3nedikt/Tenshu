package app.tenshu.fit.data

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportStatus
import app.tenshu.fit.model.Metric
import kotlinx.coroutines.CoroutineScope
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
 * Unit coverage for the **health-import seam** (`docs/proposals/fit-app.md` §9.4 +
 * `timeline-services.md` §4.2): the [ReferenceHealthImporter] acquires an [ImportedHealth], the
 * shared [HealthImportWriter] persists it, and the data reads back through the projector — the
 * same pipe a real Health Connect / HealthKit importer will drive. Also covers category filtering
 * (the acquisition contract) and idempotent re-import (content-addressed record ops).
 */
class HealthImportTest {

    @Test
    fun referenceImporterAdvertisesASourceAndFiltersByCategory() = runTest {
        val importer = ReferenceHealthImporter()
        assertEquals(listOf(ImportSource.HealthConnect), importer.availableSources())

        // Sleep-only selection carries sleep, nothing else.
        val sleepOnly = importer.import(ImportSource.HealthConnect, setOf(CategoryId.Sleep)).health
        assertTrue(sleepOnly.sleepSessions.isNotEmpty())
        assertTrue(sleepOnly.workouts.isEmpty() && sleepOnly.dailyActivities.isEmpty())
        assertTrue(sleepOnly.samples.isEmpty() && sleepOnly.medicalRecords.isEmpty())

        // Body pulls the body measurements (weight + height, FP-2); Vitals pulls the rest. The
        // split is a consent boundary, so it is asserted both ways round: neither body metric may
        // leak into a Vitals-only import.
        val bodyOnly = importer.import(ImportSource.HealthConnect, setOf(CategoryId.Body)).health
        assertEquals(setOf("Weight", "Height"), bodyOnly.samples.map { it.metric }.toSet())
        val vitalsOnly = importer.import(ImportSource.HealthConnect, setOf(CategoryId.Vitals)).health
        assertTrue(vitalsOnly.samples.none { it.metric == "Weight" || it.metric == "Height" })
        assertTrue(vitalsOnly.samples.isNotEmpty())
    }

    @Test
    fun importedHealthWritesThroughToTheProjector() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val importer = ReferenceHealthImporter()

        HealthImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
            importer.import(ImportSource.HealthConnect, CategoryId.entries.toSet()).health,
        )

        val repo = FitProjectionRepository(client, proxy.tContext, importer = importer, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        assertEquals(6, repo.medicalRecords().first().size, "imported medical records should read back")
        assertTrue(repo.sleepSessions().first().isNotEmpty(), "imported sleep should read back")
        assertTrue(repo.samplesLatest().first().isNotEmpty(), "imported samples should read back")
        assertTrue(repo.workouts().first().isNotEmpty(), "imported workouts should read back")
    }

    @Test
    fun referenceImportPopulatesNutritionThroughToTheProjector() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val importer = ReferenceHealthImporter()

        HealthImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
            importer.import(ImportSource.HealthConnect, CategoryId.entries.toSet()).health,
        )

        val repo = FitProjectionRepository(client, proxy.tContext, importer = importer, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        // §12 B3a: a week of nutrition reads back, oldest-first, with real energy.
        val days = repo.nutrition().first()
        assertEquals(7, days.size, "the reference nutrition week should read back")
        assertTrue(days.first().date < days.last().date, "oldest-first (trend order)")
        assertTrue(days.all { it.energyKj > 0 }, "each day carries real energy")
    }

    @Test
    fun referenceImportPopulatesCycleThroughToTheProjector() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val importer = ReferenceHealthImporter()

        HealthImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
            importer.import(ImportSource.HealthConnect, CategoryId.entries.toSet()).health,
        )

        val repo = FitProjectionRepository(client, proxy.tContext, importer = importer, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        // §12 B3b: the seeded period reads back, with one period-start day.
        val cycle = repo.cycle().first()
        assertEquals(5, cycle.size, "the reference cycle days should read back")
        assertEquals(1, cycle.count { it.periodStart }, "exactly one period-start day")
    }

    @Test
    fun referenceImportRetainsAWeightTimeSeries() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val importer = ReferenceHealthImporter()

        HealthImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
            importer.import(ImportSource.HealthConnect, CategoryId.entries.toSet()).health,
        )

        val repo = FitProjectionRepository(client, proxy.tContext, importer = importer, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        // §12 B2: distinct-time readings retain, so the whole weight series reads back (not one latest).
        val weight = repo.sampleHistory(Metric.Weight).first()
        assertEquals(5, weight.size, "the reference weight series should retain all readings")
        assertTrue(weight.first().time >= weight.last().time, "history is newest first")
        // …and the teaser's latest agrees with the newest reading.
        assertEquals(74.2, repo.samplesLatest().first().first { it.metric == Metric.Weight }.value)
    }

    @Test
    fun startImportOverTheSeamPopulatesTheStoreIdempotently() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        // The fixture dressed as a real read — the shipping fixture is refused (next test).
        val repo = FitProjectionRepository(client, proxy.tContext, importer = FixtureAsRealImporter(), controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        assertEquals(ImportSource.HealthConnect, repo.availableSources().first())

        repo.startImport(CategoryId.entries.toSet())
        repo.startImport(CategoryId.entries.toSet())   // re-import folds (content-addressed)

        assertEquals(6, repo.medicalRecords().first().size, "re-import must not duplicate records")
        assertTrue(repo.importProgress().first()?.isNotEmpty() == true, "progress should report after import")
    }

    /**
     * ⚠⚠ The store guard (`fit-app.md` §9.15). A MANUAL import — onboarding, Profile, Home's retry;
     * deliberately not gated on `autoRefreshReady` — over an importer that answers
     * [ImportOutcome.Sample] must write **nothing**: this repository writes into Control's durable,
     * synced realm, and both platform importers used to fall back to this fixture where no health
     * store exists. Same pipe and same reads as the test above, which proves they do see rows.
     */
    @Test
    fun aManualImportOfSampleDataWritesNothingToTheStore() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repo = FitProjectionRepository(client, proxy.tContext, importer = ReferenceHealthImporter(), controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        repo.startImport(CategoryId.entries.toSet(), automatic = false)

        assertEquals(ImportStatus.SourceUnavailable, repo.importStatus().first(), "sample data reads as no source")
        assertEquals(0, repo.medicalRecords().first().size, "no fixture medical records in the store")
        assertTrue(repo.sleepSessions().first().isEmpty(), "no fixture sleep in the store")
        assertTrue(repo.samplesLatest().first().isEmpty(), "no fixture vitals in the store")
        // Re-read through a second repository over the same store: nothing a cache could be hiding.
        val fresh = FitProjectionRepository(client, proxy.tContext, importer = NoHealthSource, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        assertEquals(0, fresh.medicalRecords().first().size)
    }

    /**
     * The shipping default where there is no platform source (desktop; Android without Health
     * Connect answers the same): no source offered, and a manual import writes nothing.
     */
    @Test
    fun withNoHealthSourceAManualImportOffersNothingAndWritesNothing() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repo = FitProjectionRepository(client, proxy.tContext, importer = NoHealthSource, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        assertEquals(listOf(ImportSource.StartFresh), repo.availableSources(), "only Start fresh, no health source")
        repo.startImport(CategoryId.entries.toSet(), automatic = false)

        assertEquals(ImportStatus.SourceUnavailable, repo.importStatus().first())
        assertEquals(0, repo.medicalRecords().first().size)
    }

    // ── one in-process proxy hosting the health service ──────────────────────

    private fun stackProxy(scope: CoroutineScope): TestProxy {
        val registry = createCoreTestRegistry().apply {
            registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
        }
        return TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, proxy -> healthServiceProxyImplementationFactory(id, proxy.fileDatabase) },
        ).also {
            it.executionContext.initHealthService()
        }
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
