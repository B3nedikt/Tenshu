@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.module.schemaMigration
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.schema.migration.CORE_SERVICE_ID
import org.tenshu.core.common.schema.migration.CompositeMigration
import org.tenshu.core.common.schema.migration.MigrationContext
import org.tenshu.core.common.schema.migration.ThingFields
import org.tenshu.core.common.schema.migration.SchemaMigration
import org.tenshu.core.common.schema.migration.StoreSchemaMigrations
import org.tenshu.core.common.schema.migration.THING_ID
import org.tenshu.core.common.schema.migration.THING_SERVICE_ID
import org.tenshu.core.common.schema.migration.createMigrationTestRegistry
import org.tenshu.core.common.schema.migration.schemaMigration as stepOf
import org.tenshu.core.common.schema.migration.thingMigrationStep
import org.tenshu.core.schema.service as serviceSchema
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ⚠⚠ Appendix B.24: the schema registry's chains, through [RegistryReplicaMigrationSteps], run by
 * the production chunked migrator over a real store. Every build here is made from a **real schema
 * registry with a real bump** of a test-only service (`THING_SERVICE_ID`, and `:core` under a test
 * core version), so the production versions stay at 1.
 *
 * The fixtures are the JVM's (`SchemaMigrationTestFixtures`): the v1→v2 step renames, drops,
 * derives and converts, so a row migrated twice, or not at all, cannot come out right.
 */
class RegistryReplicaMigrationStepsTest {

    /** Seven rows a chunk: every job below is many chunks. */
    private val chunky = ReplicaLimits(chunkMaxRows = 7)

    private val untouched = "tenshu/test/migration/untouched"

    /** A stand-in for the outbound queue: entries of a type no step reads, and the list naming them. */
    private val queued = "tenshu/test/migration/outbound-entry"
    private val queueList = 777_000L

    // ── the builds: one registry each ──

    private fun chainsOf(
        version: Int,
        vararg steps: SchemaMigration,
        coreVersion: Int = 1,
    ): RegistryReplicaMigrationSteps {
        val registry = createMigrationTestRegistry().apply {
            registerConfig(tModule {
                service(serviceSchema(id = THING_SERVICE_ID, version = version))
                steps.forEach { schemaMigration(it) }
            })
        }
        return RegistryReplicaMigrationSteps(
            StoreSchemaMigrations(context = createTestContext(registry), schemas = registry, deviceId = "replica-chains-test"),
            coreVersion = coreVersion,
        )
    }

    /** The build that wrote the old store: the thing service at v1, no chain. */
    private val v1 get() = chainsOf(1)

    /** v2 with the v1→v2 step. */
    private val v2 get() = chainsOf(2, thingMigrationStep())

    /** A step of the thing service that only retires a property: no `CompositeMigration` at all. */
    private fun dropStep(from: Int, typeId: String, propertyId: String) =
        stepOf(THING_SERVICE_ID, fromVersion = from) { dropProperty(typeId, propertyId) }

    private fun ReplicaProcess.with(chains: RegistryReplicaMigrationSteps) = also { it.steps = chains }

    private fun ReplicaProcess.count(table: String): Long = sql { it.queryLong("SELECT count(*) FROM $table") }
    private fun ReplicaProcess.hasMeta(key: String): Boolean = sql { it.queryLong("SELECT count(*) FROM meta WHERE k = '$key'") } == 1L
    private fun ReplicaProcess.stamp(service: String): Long =
        sql { it.queryLong("SELECT version FROM schema_service_versions WHERE service_id = '$service'") }
    private fun ReplicaProcess.userVersion(): Long = sql { it.queryLong("PRAGMA user_version") }

    // ── the rows ──

    /** A thing as the v1 snapshot stores it: [title, legacy, size-as-string], with a property. */
    private fun v1Thing(hash: Long) = DbEntry(
        type = THING_ID,
        dataIdHash = hash,
        fields = listOf(DbValue.StringValue("title-$hash"), DbValue.StringValue("junk"), DbValue.StringValue("${hash * 10}")),
        properties = mapOf("some/property" to DbValue.StringValue("kept-$hash")),
    )

    /** The same thing as v2 stores it: [name, kind (derived), size (converted), note (absent)]. */
    private fun v2Thing(hash: Long) = DbEntry(
        type = THING_ID,
        dataIdHash = hash,
        fields = listOf(DbValue.StringValue("title-$hash"), DbValue.StringValue("kind:title-$hash"), DbValue.LongValue(hash * 10), DbValue.NullValue),
        properties = mapOf("some/property" to DbValue.StringValue("kept-$hash")),
    )

    private fun untouchedRow(hash: Long) = DbEntry(
        type = untouched,
        dataIdHash = hash,
        fields = listOf(DbValue.StringValue("keep-me-$hash")),
        properties = mapOf("retired/root" to DbValue.StringValue("r"), "live/value" to DbValue.StringValue("v")),
    )

    private val thingHashes = (1L..30L)
    private val untouchedHashes = (5_001L..5_008L)
    private val queuedHashes = (6_001L..6_012L)

    private fun queuedRow(hash: Long) = DbEntry(queued, hash, listOf(DbValue.StringValue("block-$hash"), DbValue.LongValue(hash)))
    private fun queueRow() = DbEntry(TList.ID, queueList, queuedHashes.map { DbValue.LongValue(it) })

    /** A store written by [writer]: thirty things, rows of a type no step reads, and the outbound queue. Closed. */
    private fun anOldStore(writer: RegistryReplicaMigrationSteps = v1): String {
        val directory = tempReplica()
        val store = ReplicaProcess(directory, chunky).with(writer).store(writer.replicaBuild())
        val rows = thingHashes.map(::v1Thing) + untouchedHashes.map(::untouchedRow) + queuedHashes.map(::queuedRow) + queueRow()
        rows.chunked(10).forEach { some -> store.databaseLock.withLock { some.forEach(store::storeObject) } }
        store.close()
        return directory
    }

    /** Opens with [chains] in the foreground, expecting a migration, and runs it to its finish. */
    private fun migrate(directory: String, chains: RegistryReplicaMigrationSteps) {
        val opened = assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(directory, chunky).with(chains).open(chains.replicaBuild()))
        assertEquals(ReplicaJob.MIGRATION, opened.job, "a store at or above every floor is migrated, never wiped")
        assertEquals(ReplicaRunOutcome.Finished, opened.replacement.run())
    }

    private fun readBack(directory: String, chains: RegistryReplicaMigrationSteps, block: (SharedReplicaStore) -> Unit) {
        val store = assertIs<ReplicaOpenResult.Ready>(ReplicaProcess(directory, chunky).with(chains).open(chains.replicaBuild())).store
        try {
            block(store)
        } finally {
            store.close()
        }
    }

    private fun assertQueueKept(store: SharedReplicaStore) {
        assertEquals(queueRow(), store.getObject(queueList), "the outbound queue's list is kept whole")
        for (hash in queuedHashes) assertEquals(queuedRow(hash), store.getObject(hash), "queued entry $hash is kept")
    }

    private fun assertJobGone(process: ReplicaProcess) {
        assertFalse(process.hasMeta("migration"), "the marker is gone")
        assertFalse(process.hasMeta("wipe"), "and it never became a wipe")
        assertEquals(0, process.count("migration_done"))
        assertEquals(0, process.count("migration_dropped"))
    }

    // ── the floors ──

    @Test
    fun eachFloorIsTheOldestVersionItsRegisteredChainMigratesFrom() {
        assertEquals(1, v1.replicaBuild().serviceFloors[THING_SERVICE_ID], "no chain: the floor is the version")
        assertEquals(1, v2.replicaBuild().serviceFloors[THING_SERVICE_ID], "a v1→v2 step reads v1")
        val v3 = chainsOf(3, thingMigrationStep(), dropStep(2, THING_ID, "some/property"))
        assertEquals(1, v3.replicaBuild().serviceFloors[THING_SERVICE_ID], "two steps read back to v1")
        val cut = chainsOf(3, dropStep(2, THING_ID, "some/property"))
        assertEquals(2, cut.replicaBuild().serviceFloors[THING_SERVICE_ID], "a chain cut at v2 reads no further back")
        val core = chainsOf(1, SchemaMigration(CORE_SERVICE_ID, fromVersion = 1, migrations = emptyList()), coreVersion = 2)
        assertEquals(2, core.replicaBuild().coreVersion)
        assertEquals(1, core.replicaBuild().coreFloor, "`:core`'s floor walks back over its own steps")
        assertEquals(2, chainsOf(1, coreVersion = 2).replicaBuild().coreFloor, "no core step: the floor is the version")
    }

    // ── one step ──

    /**
     * ⚠⚠ The data-loss case HR #9 forbids: a service bump with its step. Every row of the step's
     * type is rewritten in place, every other row (the outbound queue among them) is kept byte for
     * byte, nothing is wiped, and the store is stamped at the new version.
     */
    @Test
    fun aStoreAtVnOpenedByVnPlusOneWithItsStepIsMigratedNotWiped() {
        val directory = anOldStore()
        migrate(directory, v2)

        val process = ReplicaProcess(directory, chunky)
        assertJobGone(process)
        assertEquals(2, process.stamp(THING_SERVICE_ID), "the new version is stamped")
        readBack(directory, v2) { store ->
            for (hash in thingHashes) assertEquals(v2Thing(hash), store.getObject(hash), "thing $hash in the v2 shape")
            for (hash in untouchedHashes) assertEquals(untouchedRow(hash), store.getObject(hash), "a row no step reads is untouched")
            assertQueueKept(store)
        }
    }

    // ── the dropped-property strip ──

    /**
     * A retired property leaves every row of its type, on a type the step also reshapes and on one
     * it does not; every other property stays. ⚠ The second step has **no** `CompositeMigration`:
     * without its `droppedProperties` in the step's types it would read no row at all (B.3 (25)).
     */
    @Test
    fun aDroppedPropertyLeavesEveryRowOfItsTypeWhetherOrNotTheStepReshapesIt() {
        val directory = anOldStore()
        val chains = chainsOf(
            2,
            SchemaMigration(
                serviceId = THING_SERVICE_ID,
                fromVersion = 1,
                migrations = thingMigrationStep().migrations,
                droppedProperties = stepOf(THING_SERVICE_ID, fromVersion = 1) {
                    dropProperty(THING_ID, "some/property")
                    dropProperty(untouched, "retired/root")
                }.droppedProperties,
            ),
        )
        migrate(directory, chains)

        assertJobGone(ReplicaProcess(directory, chunky))
        readBack(directory, chains) { store ->
            for (hash in thingHashes) assertEquals(v2Thing(hash).copy(properties = emptyMap()), store.getObject(hash), "thing $hash: rewritten, then stripped")
            for (hash in untouchedHashes) {
                assertEquals(
                    untouchedRow(hash).copy(properties = mapOf("live/value" to DbValue.StringValue("v"))),
                    store.getObject(hash),
                    "row $hash: only the retired slot is gone",
                )
            }
            assertQueueKept(store)
        }
    }

    // ── rows dropped by decision ──

    /**
     * A step that drops rows by decision (its migrate returns null for every third thing) and also
     * retires a property of the same type. The dropped rows are deleted, their hashes are swept out
     * of a plain list and of a list kept in **segments** (ten members a segment here), and a dropped
     * row is never handed back stripped: it is gone, not kept less a property.
     */
    @Test
    fun aRowDroppedByDecisionIsDeletedSweptFromEveryListAndNeverStripped() {
        val limits = ReplicaLimits(listSegmentMembers = 10, chunkMaxRows = 7)
        val reshape = thingMigrationStep().migrations.single()
        val dropping = object : CompositeMigration by reshape {
            override fun migrate(old: Data, context: MigrationContext): Data? {
                val title = (old as Composite).getField<TString>(ThingFields.TITLE).get()
                return if (title.removePrefix("title-").toLong() % 3 == 0L) null else reshape.migrate(old, context)
            }
        }
        val chains = chainsOf(
            2,
            SchemaMigration(
                serviceId = THING_SERVICE_ID,
                fromVersion = 1,
                migrations = listOf(dropping),
                droppedProperties = stepOf(THING_SERVICE_ID, fromVersion = 1) { dropProperty(THING_ID, "some/property") }.droppedProperties,
            ),
        )
        val dropped = thingHashes.filter { it % 3 == 0L }.toSet()

        val directory = tempReplica()
        val writer = v1
        val old = ReplicaProcess(directory, limits).with(writer).store(writer.replicaBuild())
        val plain = 880_001L
        val segmented = 880_002L
        val plainMembers = (1L..9L).map { DbValue.LongValue(it) }
        val segmentedMembers = thingHashes.map { DbValue.LongValue(it) }
        (thingHashes.map(::v1Thing) + untouchedHashes.map(::untouchedRow)).chunked(10).forEach { some ->
            old.databaseLock.withLock { some.forEach(old::storeObject) }
        }
        old.databaseLock.withLock {
            old.storeObject(DbEntry(TList.ID, plain, plainMembers))
            old.storeObject(DbEntry(TList.ID, segmented, segmentedMembers))
        }
        old.close()
        val heads = ReplicaProcess(directory, limits).sql { it.queryLong("SELECT count(*) FROM objects WHERE type = '${ReplicaListSegments.SEGMENT_TYPE}'") }
        assertTrue(heads >= 2, "the 30-member list is kept in segments: $heads segment rows")

        // The step itself: a dropped row is null, never a stripped copy.
        val step = chains.step(THING_SERVICE_ID, 1) ?: fail("no step")
        assertNull(step.migrate(v1Thing(3)), "a dropped row is dropped, not stripped")
        assertEquals(v2Thing(1).copy(properties = emptyMap()), step.migrate(v1Thing(1)))

        val opened = assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(directory, limits).with(chains).open(chains.replicaBuild()))
        assertEquals(ReplicaJob.MIGRATION, opened.job)
        assertEquals(ReplicaRunOutcome.Finished, opened.replacement.run())

        assertJobGone(ReplicaProcess(directory, limits))
        val store = assertIs<ReplicaOpenResult.Ready>(ReplicaProcess(directory, limits).with(chains).open(chains.replicaBuild())).store
        try {
            for (hash in thingHashes) {
                if (hash in dropped) assertNull(store.getObject(hash), "thing $hash was dropped by decision: deleted")
                else assertEquals(v2Thing(hash).copy(properties = emptyMap()), store.getObject(hash), "thing $hash: rewritten, then stripped")
            }
            for (hash in untouchedHashes) assertEquals(untouchedRow(hash), store.getObject(hash))
            assertEquals(plainMembers.filter { it.value !in dropped }, store.getObject(plain)?.fields, "the plain list names no dropped row")
            assertEquals(segmentedMembers.filter { it.value !in dropped }, store.getObject(segmented)?.fields, "the segmented list names no dropped row, in any segment")
        } finally {
            store.close()
        }
    }

    // ── a chain of steps ──

    /** v1 → v3 in one open: the reshape, then a strip-only step, each over the rows the one before committed. */
    @Test
    fun aStoreTwoStepsBehindRunsTheWholeChainInOneJob() {
        val directory = anOldStore()
        val v3 = chainsOf(3, thingMigrationStep(), dropStep(2, THING_ID, "some/property"))
        migrate(directory, v3)

        val process = ReplicaProcess(directory, chunky)
        assertJobGone(process)
        assertEquals(3, process.stamp(THING_SERVICE_ID))
        readBack(directory, v3) { store ->
            for (hash in thingHashes) assertEquals(v2Thing(hash).copy(properties = emptyMap()), store.getObject(hash), "thing $hash: both steps, once each")
            for (hash in untouchedHashes) assertEquals(untouchedRow(hash), store.getObject(hash))
            assertQueueKept(store)
        }
    }

    // ── `:core` ──

    /** A `:core` step (`TENSHU_STORAGE_FORMAT_VERSION`'s chain), at a test core version: rows rewritten, `user_version` stamped. */
    @Test
    fun aCoreStepMigratesItsRowsAndStampsUserVersion() {
        val directory = anOldStore()
        assertEquals(1, ReplicaProcess(directory, chunky).userVersion())
        val core = chainsOf(1, SchemaMigration(CORE_SERVICE_ID, fromVersion = 1, migrations = thingMigrationStep().migrations), coreVersion = 2)
        migrate(directory, core)

        val process = ReplicaProcess(directory, chunky)
        assertJobGone(process)
        assertEquals(2, process.userVersion(), "core's version is user_version")
        assertEquals(1, process.stamp(THING_SERVICE_ID), "the service did not move")
        readBack(directory, core) { store ->
            for (hash in thingHashes) assertEquals(v2Thing(hash), store.getObject(hash))
            assertQueueKept(store)
        }
    }

    // ── below the floor ──

    /** The chain was cut at v2 (its 1→2 step deleted): a v1 store no step can read is wiped, as designed. */
    @Test
    fun aStoreBelowTheFloorIsWiped() {
        val directory = anOldStore()
        val cut = chainsOf(3, dropStep(2, THING_ID, "some/property"))
        val opened = assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(directory, chunky).with(cut).open(cut.replicaBuild()))
        assertEquals(ReplicaJob.WIPE, opened.job)
        assertEquals(ReplicaRunOutcome.Finished, opened.replacement.run())

        val process = ReplicaProcess(directory, chunky)
        assertEquals(3, process.stamp(THING_SERVICE_ID), "the wipe stamps this build's versions")
        readBack(directory, cut) { store -> assertTrue(store.isEmpty()) }
    }

    // ── a newer store ──

    /**
     * ⚠⚠ The JVM migrator wipes a store "written by newer code"; on iOS that path does not exist. A
     * v2 store opened by a v1 build (chains registered or not) is `Skew`: refused, nothing written,
     * the file byte for byte as it was. And the steps themselves refuse to be asked for a version
     * at or above this build's, so no defect can turn a newer store into a wipe.
     */
    @Test
    fun aNewerStoreIsRefusedUntouchedAndTheStepsRefuseToBeAskedForIt() {
        val directory = anOldStore()
        migrate(directory, v2)
        val process = ReplicaProcess(directory, chunky)
        val database = bytesOf(process.databasePath)
        val wal = bytesOf(process.databasePath + "-wal")

        for (older in listOf(v1, chainsOf(1, SchemaMigration(CORE_SERVICE_ID, fromVersion = 1, migrations = emptyList()), coreVersion = 2))) {
            assertIs<ReplicaOpenResult.Skew>(ReplicaProcess(directory, chunky).with(older).open(older.replicaBuild()))
            assertIs<ReplicaOpenResult.Skew>(ReplicaProcess(directory, chunky).with(older).open(older.replicaBuild(), foreground = false))
        }
        assertContentEquals(database, bytesOf(process.databasePath), "nothing was written to the database")
        assertContentEquals(wal, bytesOf(process.databasePath + "-wal"), "nor to its log")
        assertEquals(2, process.stamp(THING_SERVICE_ID))

        assertFailsWith<IllegalStateException> { v2.step(THING_SERVICE_ID, 2) }
        assertFailsWith<IllegalStateException> { v2.step(THING_SERVICE_ID, 3) }
        assertFailsWith<IllegalStateException> { v2.step(CORE_SERVICE_ID, 1) }
        assertFailsWith<IllegalStateException> { v2.step("tenshu/test/unknown", 1) }
        assertFailsWith<IllegalStateException> { v2.step(ReplicaListSegments.FORMAT_SERVICE, 0) }
        assertNull(chainsOf(3, dropStep(2, THING_ID, "some/property")).step(THING_SERVICE_ID, 1), "below the chain: no step, so a wipe")
    }

    // ── killed and resumed ──

    /**
     * Killed at every commit of a two-step chain in turn, and resumed by a new process: every row
     * ends in the v3 shape, migrated exactly once by each step (the v1→v2 decode of a v2 row
     * cannot come out as the expected row), and nothing is ever served in between.
     */
    @Test
    fun aChainKilledAtEveryCommitResumesWithNoRowSkippedOrMigratedTwice() {
        val v3 = chainsOf(3, thingMigrationStep(), dropStep(2, THING_ID, "some/property"))
        var commitsAllowed = 0
        while (true) {
            val directory = anOldStore()
            val opened = assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(directory, chunky).with(v3).open(v3.replicaBuild()))
            var left = commitsAllowed
            var killed = false
            opened.replacement.beforeCommit = {
                if (left-- == 0) {
                    killed = true
                    throw RuntimeException("killed")
                }
            }
            val outcome = opened.replacement.run()
            if (!killed) {
                assertEquals(ReplicaRunOutcome.Finished, outcome)
                assertTrue(commitsAllowed > 10, "the chain was many chunks: $commitsAllowed commits")
                return
            }
            assertIs<ReplicaRunOutcome.Stopped>(outcome, "killed at commit $commitsAllowed")
            // A new process of the same build: the store is still fenced, and resumes.
            val resumed = assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(directory, chunky).with(v3).open(v3.replicaBuild()), "killed at commit $commitsAllowed")
            assertEquals(ReplicaJob.MIGRATION, resumed.job, "killed at commit $commitsAllowed: never a wipe")
            assertEquals(ReplicaRunOutcome.Finished, resumed.replacement.run(), "killed at commit $commitsAllowed")
            assertJobGone(ReplicaProcess(directory, chunky))
            readBack(directory, v3) { store ->
                for (hash in thingHashes) assertEquals(v2Thing(hash).copy(properties = emptyMap()), store.getObject(hash), "killed at commit $commitsAllowed: thing $hash")
                assertQueueKept(store)
            }
            commitsAllowed++
        }
    }

    // ── a second process ──

    /**
     * Several apps of one build share the store. One starts the migration; a second of the same
     * build that opens meanwhile is fenced "updating" (never served a half-migrated store) and may
     * carry the job on; an older build's process is refused `Skew` and writes nothing; whichever
     * runs last finds the job done. Each row is migrated exactly once.
     */
    @Test
    fun aSecondProcessOpeningDuringTheMigrationWaitsOrCarriesItOnAndAnOlderBuildIsRefused() {
        val directory = anOldStore()
        // A sibling of the old build, open on the store before the migration starts.
        val oldSibling = ReplicaProcess(directory, chunky).with(v1).store(v1.replicaBuild())

        val first = assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(directory, chunky).with(v2).open(v2.replicaBuild()))
        var chunks = 0
        assertIs<ReplicaRunOutcome.Stopped>(first.replacement.run { ++chunks > 2 }, "stopped after a few chunks")
        assertTrue(ReplicaProcess(directory, chunky).hasMeta("migration"))

        // The old build's open handle is fenced by the Start's epoch; its new open is Skew.
        assertNull(oldSibling.getObject(1), "the old build's handle serves nothing")
        assertEquals(ReplicaFence.REPLACED, oldSibling.fencedBy)
        val process = ReplicaProcess(directory, chunky)
        val database = bytesOf(process.databasePath)
        val wal = bytesOf(process.databasePath + "-wal")
        assertIs<ReplicaOpenResult.Skew>(ReplicaProcess(directory, chunky).with(v1).open(v1.replicaBuild()), "an older build may not resume a migration past it")
        assertContentEquals(database, bytesOf(process.databasePath), "the older build wrote nothing")
        assertContentEquals(wal, bytesOf(process.databasePath + "-wal"))

        // The same build in another process: fenced "updating", and it carries the job on.
        val second = assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(directory, chunky).with(v2).open(v2.replicaBuild()))
        assertEquals(ReplicaJob.MIGRATION, second.job)
        chunks = 0
        assertIs<ReplicaRunOutcome.Stopped>(second.replacement.run { ++chunks > 3 })
        // The first process resumes from where the second left the cursor, and finishes.
        assertEquals(ReplicaRunOutcome.Finished, first.replacement.run())
        assertEquals(ReplicaRunOutcome.Finished, second.replacement.run(), "the second finds the job done")

        assertJobGone(process)
        readBack(directory, v2) { store ->
            for (hash in thingHashes) assertEquals(v2Thing(hash), store.getObject(hash), "thing $hash: exactly once")
            assertQueueKept(store)
        }
        oldSibling.close()
    }
}
