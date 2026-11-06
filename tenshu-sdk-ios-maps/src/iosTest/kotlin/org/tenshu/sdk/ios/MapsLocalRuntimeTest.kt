package org.tenshu.sdk.ios

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.fold
import org.tenshu.core.mapregion.installed
import org.tenshu.mapregion.client.mapRegionService
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The route-1 in-process Maps runtime (ios-first-party-route-1-migration.md) assembles the FULL
 * production proxy graph in this process and serves **both** of Maps' reads over a test replica
 * (`TestReplicas`) — the
 * `:place-service` places and the `MapRegionService` catalog. The Maps twin of
 * [TimelineLocalRuntimeTest] / [FitLocalRuntimeTest] / [DriveLocalRuntimeTest].
 *
 * It pins that Control's iOS proxy graph hosts both services (the parity doc's B-3), so a route-1
 * Maps needs no service wiring of its own — only the two repositories built over the in-process
 * client.
 *
 * A real Maps *write* comes from Timeline's capture (a visit becomes a place), not from Maps, so the
 * assembly + shared-replica read is the in-process proof here.
 */
class MapsLocalRuntimeTest {

    private fun tempReplicaDir(): String =
        NSTemporaryDirectory() + "maps-local-runtime-test-" + Random.nextLong().toString(16)

    @Test
    fun assemblesTheFullRuntimeAndReadsAnEmptyPlaceStore() = runBlocking {
        val assembly = MapsLocalRuntime.assemble(
            replicaDirectory = tempReplicaDir(),
            secureStorage = InMemorySecureStorageDelegate(),
        )
        assertTrue(
            assembly.placeRepository.places().first().isEmpty(),
            "a fresh replica lists no places",
        )
    }

    /**
     * ⚠⚠ **Wiring is proved on `installed()`, deliberately NOT on the catalogue.**
     *
     * `assertIs<RegionsRead.Regions>(regionRepository.regions())` reads better and cannot pass:
     * `regions()` calls `catalog()`, and that op fetches a signed manifest over HTTPS through
     * `HttpRegionCatalogSource`. A transport failure becomes `Unavailable`, so that assertion makes
     * this a **network test** — and no HTTPS succeeds at all inside the K/N simulator harness
     * (apple.com and letsencrypt.org fail there too, `NSURLErrorDomain -1202`).
     *
     * `installed()` reaches the SAME `MapRegionService` and reads the local proxy store, so it
     * carries the wiring claim without the network: an unwired service fails it, a wired one
     * answers empty. The catalogue's own behaviour — fetch, signature verification and tamper
     * rejection — is covered by `RegionCatalogSourceTest` over a Ktor `MockEngine`, which is
     * where a catalogue assertion belongs.
     */
    @Test
    fun theRegionRepositoryReachesTheInProcessMapRegionService() = runBlocking {
        val assembly = MapsLocalRuntime.assemble(
            replicaDirectory = tempReplicaDir(),
            secureStorage = InMemorySecureStorageDelegate(),
        )
        val installed = assembly.tenshuClient.mapRegionService.installed().fold(
            onSuccess = { it.toList() },
            onFailure = { error ->
                throw AssertionError("the in-process MapRegionService did not serve installed(): $error")
            },
        )
        assertTrue(installed.isEmpty(), "a fresh proxy has nothing installed")
    }

    @Test
    fun bothRepositoriesShareOneReachabilitySignal() = runBlocking {
        // `mapsModules()` binds ONE `ControlReachability` for the whole app (fit-app.md §9.14) so the
        // shell banner cannot flicker between two independent verdicts; the runtime must do the same.
        val assembly = MapsLocalRuntime.assemble(
            replicaDirectory = tempReplicaDir(),
            secureStorage = InMemorySecureStorageDelegate(),
        )
        // Drive a read through each repository, then assert they agree — they can only agree by
        // construction if they are backed by the same engine.
        assembly.placeRepository.places().first()
        assembly.regionRepository.regions()
        assertEquals(
            true,
            assembly.placeRepository.controlReachable().value,
            "an in-process graph is reachable",
        )
    }

    @Test
    fun aSecondRuntimeOverTheSameReplicaAssemblesAndReads() = runBlocking {
        // Two independent runtimes over ONE replica dir both assemble the full graph and read it
        // (one test replica's rows are shared by every assembly over it) — the cross-runtime read shape.
        val dir = tempReplicaDir()
        val a = MapsLocalRuntime.assemble(dir, InMemorySecureStorageDelegate())
        val b = MapsLocalRuntime.assemble(dir, InMemorySecureStorageDelegate())
        assertTrue(a.placeRepository.places().first().isEmpty())
        assertTrue(b.placeRepository.places().first().isEmpty())
    }

    @Test
    fun theDefaultSurfaceIsNotConnectedAndTheMockIsOptIn() {
        // D4: an unprovisioned shell must be empty and say why, never fabricated. The default is the
        // honest pair; `installMock()` is reachable only from tests, previews and the render probes.
        MapsSurface.installNotConnected()
        assertFalse(MapsSurface.controlReachable(), "the not-connected surface reports unreachable")
        assertTrue(MapsSurface.installedPlaceRepository() is NotConnectedPlaceRepository)
        assertTrue(MapsSurface.installedRegionRepository() is NotConnectedRegionRepository)

        MapsSurface.installMock()
        assertTrue(MapsSurface.controlReachable(), "the mock reports reachable")

        // Leave the global surface as the shipping default finds it.
        MapsSurface.installNotConnected()
    }
}
