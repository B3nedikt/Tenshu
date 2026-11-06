package org.tenshu.sdk.ios

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * P3 coverage (ios-first-party-route-1-migration.md): the route-1 in-process Timeline runtime
 * assembles the FULL production proxy graph in this process and serves Timeline's
 * `:timeline-service`/`:place-service` reads over a test replica (`TestReplicas`) — the
 * Timeline twin of [FitLocalRuntimeTest] / [DriveLocalRuntimeTest], proving Control's iOS proxy
 * graph already hosts those services so a route-1 Timeline needs no new service wiring.
 *
 * A real Timeline *write* comes from device capture (`VisitDetector`) — not an easy in-process
 * fixture — so the assembly + shared-replica read is the in-process proof here (the cross-runtime
 * write round-trip is proven by the shared substrate, [DriveLocalRuntimeTest], + the on-device pass).
 */
class TimelineLocalRuntimeTest {

    private fun tempReplicaDir(): String =
        NSTemporaryDirectory() + "timeline-local-runtime-test-" + Random.nextLong().toString(16)

    @Test
    fun assemblesTheFullRuntimeAndReadsEmptyTimeline() = runBlocking {
        val assembly = TimelineLocalRuntime.assemble(
            replicaDirectory = tempReplicaDir(),
            secureStorage = InMemorySecureStorageDelegate(),
        )
        val repo = assembly.client.timelineRepository
        assertTrue(repo.places().first().isEmpty(), "a fresh replica lists no places")
        assertTrue(repo.homeDays().first().isEmpty(), "a fresh replica lists no days")
    }

    @Test
    fun aSecondRuntimeOverTheSameReplicaAssemblesAndReads() = runBlocking {
        // Two independent runtimes over ONE replica dir both assemble the full graph and read it
        // (one test replica's rows are shared by every assembly over it) — the cross-runtime read shape.
        val dir = tempReplicaDir()
        val a = TimelineLocalRuntime.assemble(dir, InMemorySecureStorageDelegate())
        val b = TimelineLocalRuntime.assemble(dir, InMemorySecureStorageDelegate())
        assertTrue(a.client.timelineRepository.places().first().isEmpty())
        assertTrue(b.client.timelineRepository.places().first().isEmpty())
    }
}
