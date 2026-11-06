@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who owns what an open acquired (§5.2, Appendix B.3 (23)): the opener, until it hands a live
 * store back. An open that ends any other way, by a verdict or by a throw, leaves no descriptor
 * open, however often it is repeated.
 *
 * Counted twice: through the file seam, which names what was left ([RecordingFiles.openDescriptors]),
 * and by asking the kernel about every descriptor number of the process, which also sees the
 * descriptors SQLite opened.
 */
class ReplicaOpenDescriptorsTest {

    private fun ReplicaProcess.raw(block: (SqliteConnection) -> Unit) {
        val connection = rawConnection(databasePath)
        try {
            block(connection)
        } finally {
            connection.close()
        }
    }

    private fun aStore(build: ReplicaBuild = testBuild(), then: (ReplicaProcess) -> Unit = {}): String {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        process.store(build).close()
        then(process)
        return directory
    }

    private fun withSidecarOnly(): String = tempReplica().also { PosixReplicaFiles.createExclusive("$it/objects.db-wal") }

    private fun notADatabase(): String = tempReplica().also {
        PosixReplicaFiles.closeDescriptor(PosixReplicaFiles.createAndWrite("$it/objects.db", ByteArray(8192) { 0x5a }))
    }

    private fun marked(): String = tempReplica().also {
        PosixReplicaFiles.closeDescriptor(
            PosixReplicaFiles.createAndWrite("$it/${ReplicaCorruptMarker.NAME}", "app=sibling".encodeToByteArray()),
        )
    }

    /**
     * [attempt] is one open that does not end in a live store. Run once to warm up whatever the
     * process opens lazily, then [ATTEMPTS] times: the process must hold as many descriptors
     * after as before, and the seam must have had every one of its own closed.
     */
    private fun assertNoDescriptorIsLeft(what: String, attempt: (RecordingFiles) -> Unit) {
        attempt(RecordingFiles())
        val before = openDescriptorCount()
        repeat(ATTEMPTS) { n ->
            val files = RecordingFiles()
            attempt(files)
            assertEquals(emptyMap(), files.openDescriptors, "$what, attempt ${n + 1}: left open through the file seam")
        }
        assertEquals(before, openDescriptorCount(), "$what: descriptors open in the process after $ATTEMPTS attempts")
    }

    /** The detector itself: a descriptor nobody closed is counted, by both counts. */
    @Test
    fun theCountsSeeADescriptorThatWasLeftOpen() {
        val directory = tempReplica()
        val files = RecordingFiles()
        val before = openDescriptorCount()
        val left = files.openReadOnly(directory)
        assertEquals(before + 1, openDescriptorCount())
        assertEquals(mapOf(left to "replica"), files.openDescriptors)
        files.closeDescriptor(left)
        assertEquals(before, openDescriptorCount())
        assertEquals(emptyMap(), files.openDescriptors)
    }

    // ── step 0 ──

    @Test
    fun aMarkerFoundAtStepZeroLeavesNoDescriptor() {
        val directory = marked()
        assertNoDescriptorIsLeft("the marker, made durable") { files ->
            assertIs<ReplicaOpenResult.Corrupt>(ReplicaProcess(directory, files = files).open())
        }
        assertNoDescriptorIsLeft("the marker, which could not be made durable") { files ->
            files.failOn = "fullSync replica"
            assertFailsWith<StoreUnavailable> { ReplicaProcess(directory, files = files).open() }
        }
    }

    @Test
    fun anOrphanedSidecarLeavesNoDescriptor() {
        assertNoDescriptorIsLeft("a -wal without objects.db") { files ->
            assertIs<ReplicaOpenResult.Corrupt>(ReplicaProcess(withSidecarOnly(), files = files).open())
        }
        for (step in listOf("write CORRUPT.tmp", "fullSync CORRUPT.tmp", "rename", "fullSync replica")) {
            assertNoDescriptorIsLeft("a -wal without objects.db, and the marker fails at '$step'") { files ->
                files.failOn = step
                assertFailsWith<StoreUnavailable> { ReplicaProcess(withSidecarOnly(), files = files).open() }
            }
        }
    }

    @Test
    fun aStepZeroThatThrowsAfterTheMarkerCheckLeavesNoDescriptor() {
        val empty = tempReplica()
        assertNoDescriptorIsLeft("the lookup of objects.db fails") { files ->
            files.failLookupOf = "objects.db"
            assertFailsWith<StoreUnavailable> { ReplicaProcess(empty, files = files).open() }
        }
        assertNoDescriptorIsLeft("the lookup of the -wal fails") { files ->
            files.failLookupOf = "objects.db-wal"
            assertFailsWith<StoreUnavailable> { ReplicaProcess(empty, files = files).open() }
        }
        assertNoDescriptorIsLeft("the empty objects.db cannot be created") { files ->
            files.failOn = "createExclusive"
            assertFailsWith<StoreUnavailable> { ReplicaProcess(empty, files = files).open() }
        }
        assertNoDescriptorIsLeft("the directory cannot be synced after the create") { files ->
            files.failOn = "fullSync replica"
            assertFailsWith<StoreUnavailable> { ReplicaProcess(tempReplica(), files = files).open() }
        }
        assertNoDescriptorIsLeft("protected data is unavailable while a step-0 call fails") { files ->
            files.failOn = "createExclusive"
            val process = ReplicaProcess(empty, files = files).apply { protectedData = false }
            assertFailsWith<ProtectedDataUnavailable> { process.open() }
        }
    }

    // ── the read-only classification ──

    @Test
    fun everyVerdictOfTheClassificationThatIsNotAStoreLeavesNoDescriptor() {
        val newer = aStore(testBuild(core = 23))
        assertNoDescriptorIsLeft("skew") { files ->
            assertIs<ReplicaOpenResult.Skew>(ReplicaProcess(newer, files = files).open())
        }
        val wiping = aStore { it.raw { c -> c.exec("INSERT INTO meta (k, v) VALUES ('wipe', ${wipeFrame()})") } }
        assertNoDescriptorIsLeft("updating, a wipe") { files ->
            assertEquals(ReplicaJob.WIPE, assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(wiping, files = files).open()).job)
        }
        val migrating = aStore { it.raw { c -> c.exec("INSERT INTO meta (k, v) VALUES ('migration', ${migrationFrame()})") } }
        assertNoDescriptorIsLeft("updating, a migration") { files ->
            assertEquals(ReplicaJob.MIGRATION, assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(migrating, files = files).open()).job)
        }
        assertNoDescriptorIsLeft("a migration met in the background") { files ->
            assertFailsWith<StoreNeedsForeground> { ReplicaProcess(migrating, files = files).open(foreground = false) }
        }
        val behind = aStore(testBuild(core = 20))
        assertNoDescriptorIsLeft("behind, met in the background") { files ->
            assertFailsWith<StoreNeedsForeground> { ReplicaProcess(behind, files = files).open(foreground = false) }
        }
        assertNoDescriptorIsLeft("behind, a migration started") { files ->
            assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(behind, files = files).open())
        }
        assertNoDescriptorIsLeft("below the floor, a wipe started") { files ->
            assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(aStore(testBuild(core = 20)), files = files).open(testBuild(coreFloor = 21)))
        }
    }

    @Test
    fun everyCorruptVerdictLeavesNoDescriptor() {
        assertNoDescriptorIsLeft("not a database") { files ->
            assertIs<ReplicaOpenResult.Corrupt>(ReplicaProcess(notADatabase(), files = files).open())
        }
        assertNoDescriptorIsLeft("not a database, and the marker cannot be written") { files ->
            files.failOn = "rename"
            assertFailsWith<StoreUnavailable> { ReplicaProcess(notADatabase(), files = files).open() }
        }
        assertNoDescriptorIsLeft("a damaged frozen marker, confirmed by the second read") { files ->
            val directory = tempReplica()
            ReplicaProcess(directory).raw { connection ->
                connection.exec("PRAGMA application_id = 12345")
                connection.exec("CREATE TABLE somebody_elses (x)")
            }
            assertIs<ReplicaOpenResult.Corrupt>(ReplicaProcess(directory, files = files).open())
        }
        assertNoDescriptorIsLeft("schema validation under the write lock") { files ->
            val directory = aStore { it.raw { c -> c.exec("DROP INDEX objects_type") } }
            assertIs<ReplicaOpenResult.Corrupt>(ReplicaProcess(directory, files = files).open())
        }
        assertNoDescriptorIsLeft("schema validation, and the marker cannot be written") { files ->
            val directory = aStore { it.raw { c -> c.exec("DROP INDEX objects_type") } }
            files.failOn = "fullSync CORRUPT.tmp"
            assertFailsWith<StoreUnavailable> { ReplicaProcess(directory, files = files).open() }
        }
    }

    @Test
    fun everyRetryLeavesNoDescriptor() {
        val unopenable = tempReplica().also {
            NSFileManager.defaultManager.createDirectoryAtPath("$it/objects.db", true, null, null)
        }
        assertNoDescriptorIsLeft("a database that cannot be opened") { files ->
            assertFailsWith<StoreUnavailable> { ReplicaProcess(unopenable, files = files).open() }
        }
        val garbage = notADatabase()
        assertNoDescriptorIsLeft("damage while protected data is unavailable") { files ->
            val process = ReplicaProcess(garbage, files = files).apply { protectedData = false }
            assertFailsWith<ProtectedDataUnavailable> { process.open() }
        }
        val healthy = aStore()
        assertNoDescriptorIsLeft("a closed gate") { files ->
            val process = ReplicaProcess(healthy, files = files)
            assertTrue(process.registry.gate.closeAndDrain(100))
            assertFailsWith<ReplicaGateClosed> { process.open() }
        }
        // objects.db is there and empty, so step 0 creates nothing and the create runs under the
        // write lock, where its directory sync fails.
        val emptyFile = tempReplica().also { PosixReplicaFiles.createExclusive("$it/objects.db") }
        assertNoDescriptorIsLeft("the creating transaction's directory sync fails") { files ->
            files.failOn = "fullSync replica"
            assertFailsWith<StoreUnavailable> { ReplicaProcess(emptyFile, files = files).open() }
        }
    }

    // ── the two halves, as a test or a later slice may call them ──

    @Test
    fun eachHalfOfTheOpenLeavesNoDescriptorWhenItDoesNotEndInAStore() {
        val healthy = aStore()
        assertNoDescriptorIsLeft("the read-only half alone") { files ->
            val opener = ReplicaProcess(healthy, files = files).opener()
            assertEquals(ReplicaOpener.Classified.Judged(ReplicaClassification.Compatible), opener.classifyReadOnly())
        }
        assertNoDescriptorIsLeft("a sibling's stamp met under the write lock") { files ->
            val directory = aStore()
            val opener = ReplicaProcess(directory, files = files).opener()
            opener.classifyReadOnly()
            ReplicaProcess(directory).raw { it.exec("PRAGMA user_version = 23") }
            assertIs<ReplicaOpenResult.Skew>(opener.finish(ReplicaClassification.Compatible))
        }
        assertNoDescriptorIsLeft("a marker a sibling wrote between the halves") { files ->
            val directory = aStore()
            val opener = ReplicaProcess(directory, files = files).opener()
            opener.classifyReadOnly()
            PosixReplicaFiles.closeDescriptor(
                PosixReplicaFiles.createAndWrite("$directory/${ReplicaCorruptMarker.NAME}", "app=sibling".encodeToByteArray()),
            )
            assertIs<ReplicaOpenResult.Corrupt>(opener.finish(ReplicaClassification.Compatible))
        }
    }

    // ── the hand-over ──

    @Test
    fun aLiveStoreOwnsItsDescriptorsAndGivesThemBackWhenItIsReleased() {
        val directory = aStore()
        assertNoDescriptorIsLeft("opened, used, closed") { files ->
            val store = assertIs<ReplicaOpenResult.Ready>(ReplicaProcess(directory, files = files).open()).store
            store.storeObject(row(1))
            assertEquals(
                listOf("objects.db-wal", "replica"),
                files.openDescriptors.values.sorted(),
                "the store holds the marker's directory and the -wal, and nothing else of its own",
            )
            store.close()
        }
        assertNoDescriptorIsLeft("created, closed") { files ->
            assertIs<ReplicaOpenResult.Ready>(ReplicaProcess(tempReplica(), files = files).open()).store.close()
        }
        assertNoDescriptorIsLeft("opened, then fenced by a sibling's marker at an access") { files ->
            val fresh = aStore()
            val store = assertIs<ReplicaOpenResult.Ready>(ReplicaProcess(fresh, files = files).open()).store
            PosixReplicaFiles.closeDescriptor(
                PosixReplicaFiles.createAndWrite("$fresh/${ReplicaCorruptMarker.NAME}", "app=sibling".encodeToByteArray()),
            )
            assertNull(store.getObject(1))
            assertEquals(ReplicaFence.CORRUPT, store.fencedBy)
        }
    }

    private companion object {
        const val ATTEMPTS = 12
    }
}
