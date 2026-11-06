package app.tenshu.drive.di

import app.tenshu.ingest.backup.IngestRegistry
import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.RealmVerdict
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR 0025 §8.6: `SyncedFolderController` registers each backup source and mirrors its status into
 * synced rows at every launch, so Drive's [IngestRegistry] sits behind the realm gate. With Control
 * known to have no realm, nothing is written; once a realm appears, the waiting write lands.
 */
class RealmGatedIngestRegistryTest {

    private class RecordingRegistry : IngestRegistry {
        val writes = mutableListOf<String>()
        override suspend fun registerSource(sourceId: String, displayName: String, sourceKind: String, watchedPaths: String) {
            writes += "register:$sourceId"
        }
        override suspend fun updateStatus(sourceId: String, status: String, pendingCount: Long) {
            writes += "status:$sourceId:$status"
        }
        override suspend fun isEnabled(sourceId: String): Boolean = true
    }

    @Test
    fun aRegistryWriteWaitsForARealmAndLandsWhenOneAppears() = runTest {
        var hasRealm = false
        val gate = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { hasRealm },
        )
        val delegate = RecordingRegistry()
        val registry = RealmGatedIngestRegistry(delegate, gate)

        val register = async { registry.registerSource("camera", "Camera", "android_media", "") }
        val status = async { registry.updateStatus("camera", "disabled", 0) }
        advanceTimeBy(150); runCurrent()
        assertEquals(RealmVerdict.ABSENT, gate.realmVerdict.value)
        assertFalse(register.isCompleted || status.isCompleted, "the writes wait while there is no realm")
        assertTrue(delegate.writes.isEmpty(), "nothing written into a store with no realm")

        hasRealm = true
        advanceTimeBy(150); runCurrent()
        register.await(); status.await()
        assertEquals(setOf("register:camera", "status:camera:disabled"), delegate.writes.toSet())
    }

    /** Like `TenshuIngestRegistry`: a status for a source with no row yet is a no-op. */
    private class RowRegistry : IngestRegistry {
        val rows = mutableMapOf<String, String?>()
        override suspend fun registerSource(sourceId: String, displayName: String, sourceKind: String, watchedPaths: String) {
            rows.getOrPut(sourceId) { null }
        }
        override suspend fun updateStatus(sourceId: String, status: String, pendingCount: Long) {
            if (sourceId in rows) rows[sourceId] = status
        }
        override suspend fun isEnabled(sourceId: String): Boolean = true
    }

    /**
     * ⚠⚠ Codex P2 on bdbf30de0: with the gate held, a status arrives BEFORE the registration, so on
     * release it resumes first — against a row that does not exist yet. The row must still end up
     * with the latest status: the controller never re-emits an unchanged one.
     */
    @Test
    fun aStatusThatResumesBeforeItsRegistrationStillLands() = runTest {
        var hasRealm = false
        val gate = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { hasRealm },
        )
        val delegate = RowRegistry()
        val registry = RealmGatedIngestRegistry(delegate, gate)

        val status = async { registry.updateStatus("camera", "disabled", 0) }
        runCurrent()
        val register = async { registry.registerSource("camera", "Camera", "android_media", "") }
        advanceTimeBy(150); runCurrent()
        assertEquals(RealmVerdict.ABSENT, gate.realmVerdict.value)
        assertTrue(delegate.rows.isEmpty(), "nothing written while there is no realm")

        hasRealm = true
        advanceTimeBy(150); runCurrent()
        status.await(); register.await()
        assertEquals(mapOf<String, String?>("camera" to "disabled"), delegate.rows, "the row carries the latest status")
    }

    /**
     * A row registry with hooks: [onRegister] runs inside the registration (to move the gate
     * mid-sequence), status "A" for an existing row suspends on [blockA] (to hold a replay in
     * flight), and every status written while the gate holds is logged in [heldWrites].
     */
    private class HookedRegistry(private val heldNow: () -> Boolean) : IngestRegistry {
        val rows = mutableMapOf<String, String?>()
        val heldWrites = mutableListOf<String>()
        var onRegister: suspend () -> Unit = {}
        val blockA = kotlinx.coroutines.CompletableDeferred<Unit>()
        var blockAEnabled = false
        override suspend fun registerSource(sourceId: String, displayName: String, sourceKind: String, watchedPaths: String) {
            rows.getOrPut(sourceId) { null }
            onRegister()
        }
        override suspend fun updateStatus(sourceId: String, status: String, pendingCount: Long) {
            if (heldNow()) heldWrites += status
            if (sourceId !in rows) return
            if (blockAEnabled && status == "A") blockA.await()
            rows[sourceId] = status
        }
        override suspend fun isEnabled(sourceId: String): Boolean = true
    }

    /**
     * ⚠⚠ Codex P1 on 0686fa2cf: the status replay after a registration is a second synced write and
     * re-checks the gate itself. Control loses its realm while the registration is in flight; the
     * replay must write nothing while the gate holds, and land once the realm is back.
     */
    @Test
    fun theReplayAfterARegistrationReChecksTheGate() = runTest {
        var hasRealm = true
        val gate = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { hasRealm },
        )
        advanceTimeBy(150); runCurrent()
        assertEquals(RealmVerdict.PRESENT, gate.realmVerdict.value)
        val delegate = HookedRegistry(heldNow = { gate.realmWritesHeld })
        val registry = RealmGatedIngestRegistry(delegate, gate)

        registry.updateStatus("camera", "disabled", 0) // no row yet: remembered, written as a no-op
        var flipped = false
        delegate.onRegister = {
            if (!flipped) {
                flipped = true
                // Control drops mid-registration and its re-read answers "no realm".
                hasRealm = false
                gate.markUnreachable()
                kotlinx.coroutines.delay(50)
            }
        }
        val register = async { registry.registerSource("camera", "Camera", "android_media", "") }
        advanceTimeBy(60); runCurrent()
        assertTrue(gate.realmWritesHeld, "the gate holds after the flip")
        assertTrue(delegate.heldWrites.isEmpty(), "no replay written while the gate holds: ${delegate.heldWrites}")
        assertFalse(register.isCompleted, "the registration waits for the next release")

        hasRealm = true
        advanceTimeBy(300); runCurrent()
        register.await()
        assertTrue(delegate.heldWrites.isEmpty(), "nothing written while held: ${delegate.heldWrites}")
        assertEquals(mapOf<String, String?>("camera" to "disabled"), delegate.rows, "the replay landed on release")
    }

    /**
     * ⚠⚠ Codex P2 on 0686fa2cf: a registration's replay of status A is in flight when a newer status B
     * arrives. B must not be overtaken by the stale A — the final row is B.
     */
    @Test
    fun aNewerStatusIsNeverOvertakenByAReplay() = runTest {
        val gate = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { true },
        )
        advanceTimeBy(150); runCurrent()
        val delegate = HookedRegistry(heldNow = { gate.realmWritesHeld })
        val registry = RealmGatedIngestRegistry(delegate, gate)

        registry.updateStatus("camera", "A", 0) // no row yet: remembered, written as a no-op
        delegate.blockAEnabled = true
        val register = async { registry.registerSource("camera", "Camera", "android_media", "") }
        runCurrent() // the row exists and the replay of A is in flight
        val newer = async { registry.updateStatus("camera", "B", 0) }
        runCurrent()
        delegate.blockA.complete(Unit)
        register.await(); newer.await()
        assertEquals(mapOf<String, String?>("camera" to "B"), delegate.rows, "the newer status wins")
    }

    /** The pair: with a realm present the registry writes straight through. */
    @Test
    fun withARealmTheRegistryWritesStraightThrough() = runTest {
        val gate = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { true },
        )
        advanceTimeBy(150); runCurrent()
        val delegate = RecordingRegistry()
        RealmGatedIngestRegistry(delegate, gate).registerSource("camera", "Camera", "android_media", "")
        assertEquals(listOf("register:camera"), delegate.writes)
    }
}
