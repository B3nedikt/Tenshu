package org.tenshu.sdk.ios

import app.tenshu.fit.data.HealthImporter
import app.tenshu.fit.data.ImportOutcome
import app.tenshu.fit.data.ReferenceHealthImporter
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportStatus
import app.tenshu.fit.model.ImportWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins (ios-first-party-route-1-migration.md) that the route-1 in-process Fit runtime assembles the
 * FULL production proxy graph in this process and serves Fit's `:health-service` reads over a
 * test replica (`TestReplicas`) — the Fit twin of `DriveLocalRuntimeTest`: Control's iOS proxy
 * graph hosts health-service so a route-1 Fit needs no new service wiring.
 *
 * Real dispatchers on purpose ([runBlocking], not `runTest`): the proxy graph owns live
 * `Dispatchers.Default` scopes and the executor initialises lazily on the first op.
 */
class FitLocalRuntimeTest {

    private fun tempReplicaDir(): String =
        NSTemporaryDirectory() + "fit-local-runtime-test-" + Random.nextLong().toString(16)

    @Test
    fun assemblesTheFullRuntimeAndReadsEmptyWorkouts() = runBlocking {
        val assembly = FitLocalRuntime.assemble(
            replicaDirectory = tempReplicaDir(),
            secureStorage = InMemorySecureStorageDelegate(),
            outbox = null,
        )
        val workouts = assembly.client.fitRepository.workouts().first()
        assertTrue(workouts.isEmpty(), "a fresh replica lists no workouts, got: $workouts")
    }

    @Test
    fun importedWorkoutsLandDurablyAndACoResidentRuntimeReadsThem() = runBlocking {
        // The write path: an import through the in-process `:health-service` proxy lands durable
        // Workout rows in the shared replica (recordWorkout, like Drive's createFolder, writes rows
        // even sealed — the seal only gates SyncBlock emission).
        // ⚠⚠ The importer is the fixture dressed as a REAL read ([FixtureAsRealImporter]). The bare
        // [ReferenceHealthImporter] answers `ImportOutcome.Sample`, which `FitProjectionRepository`
        // refuses to persist on every platform (`fit-app.md` §9.15) — over it this test would poll
        // an empty store for 30 s and time out.
        val dir = tempReplicaDir()
        val writer = FitLocalRuntime.assemble(
            dir, InMemorySecureStorageDelegate(), outbox = null, importer = FixtureAsRealImporter(),
        )
        writer.client.fitRepository.startImport(CategoryId.entries.toSet())

        // `startImport` suspends until the import has run, so its outcome is settled here. Asserting
        // it turns "nothing was written" into a named failure instead of a 30 s poll timeout.
        val status = writer.client.fitRepository.importStatus().value
        assertIs<ImportStatus.Imported>(status, "the import did not persist anything, status: $status")

        withTimeout(30_000) {
            while (writer.client.fitRepository.workouts().first().isEmpty()) {
                delay(100)
            }
        }

        // A second, independent runtime over the SAME replica sees the writer's durable rows — the
        // in-process approximation of "Control wrote the replica, Fit reads it" (one test replica's
        // rows are shared by every assembly over its name), mirroring DriveLocalRuntimeTest's cross-runtime read.
        val reader = FitLocalRuntime.assemble(
            dir, InMemorySecureStorageDelegate(), outbox = null, importer = FixtureAsRealImporter(),
        )
        val readWorkouts = reader.client.fitRepository.workouts().first()
        assertTrue(
            readWorkouts.isNotEmpty(),
            "reader runtime lists the imported workouts over the same replica, got: ${readWorkouts.size}",
        )
    }
}

/**
 * **Test-only:** the [ReferenceHealthImporter] fixture presented as a real read
 * ([ImportOutcome.Success]) so the persist path writes rows — the iOS-test twin of
 * `fit-shared-data`'s internal `FixtureAsRealImporter`. ⚠⚠ The repository refuses every
 * [ImportOutcome.Sample] (`fit-app.md` §9.15); living in test sources is what keeps a shipping
 * path from dressing the fixture up as the user's data.
 */
private class FixtureAsRealImporter(
    private val fixture: HealthImporter = ReferenceHealthImporter(),
) : HealthImporter by fixture {
    override suspend fun import(source: ImportSource, categories: Set<CategoryId>, window: ImportWindow): ImportOutcome =
        ImportOutcome.Success(fixture.import(source, categories, window).health)
}
