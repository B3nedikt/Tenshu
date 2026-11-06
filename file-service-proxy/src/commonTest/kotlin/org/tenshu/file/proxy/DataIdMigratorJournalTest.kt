package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.file.File
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.DataIdMigrator
import org.tenshu.sync.common.RekeyMigrationMarker
import org.tenshu.sync.common.TombstoneEmitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The crash-safe intent-journal for [DataIdMigrator] (content-addressing §3.3.4 slice 3c):
 * `migrate` writes a durable [RekeyMigrationMarker] before retiring the old key and clears it
 * after; a marker surviving a crash is completed by `resumeIncompleteMigrations` on the next
 * start.
 */
class DataIdMigratorJournalTest {

    private companion object {
        const val OWNER_ID = "owner-1"
    }

    @Test
    fun migrate_writesAMarker_thenClearsItOnSuccess() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        testProxy.upload("/a.txt")
        val oldDataId = DataId.StringDataId.create(type = File.ID, keyValue = "/a.txt")
        val oldHash = oldDataId.getDataIdHash().toString()
        val newHash = DataId.StringDataId.create(type = File.ID, keyValue = "/b.txt")
            .getDataIdHash().toString()
        val emitter = RecordingTombstoneEmitter()

        DataIdMigrator(testProxy.executionContext, emitter)
            .migrate(oldDataId, newHash, File.ID)

        // On success the journal is empty (marker cleared), the old entity is gone, and the
        // tombstone was emitted.
        assertTrue(testProxy.pendingMarkers().isEmpty(), "marker must be cleared after a clean migrate")
        assertNull(
            testProxy.executionContext.database.getData(oldDataId),
            "the old-key entity must be retired",
        )
        assertEquals(listOf(oldHash to File.ID), emitter.calls, "the old-key tombstone must be emitted")
    }

    @Test
    fun resume_completesALingeringMarker_afterACrash() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        testProxy.upload("/a.txt")
        val oldDataId = DataId.StringDataId.create(type = File.ID, keyValue = "/a.txt")
        val oldHash = oldDataId.getDataIdHash().toString()
        val newHash = DataId.StringDataId.create(type = File.ID, keyValue = "/b.txt")
            .getDataIdHash().toString()

        // Simulate a crash right after the marker was written but before any retirement ran:
        // the marker is in the journal and the old entity is still present.
        testProxy.writeMarker(oldDataId, newHash, File.ID)
        assertNotNull(
            testProxy.executionContext.database.getData(oldDataId),
            "precondition: the old entity is still present (retirement hadn't run)",
        )
        assertEquals(1, testProxy.pendingMarkers().size, "precondition: one pending marker")

        val emitter = RecordingTombstoneEmitter()
        val resumed = DataIdMigrator(testProxy.executionContext, emitter)
            .resumeIncompleteMigrations()

        assertEquals(1, resumed, "one migration resumed")
        assertNull(
            testProxy.executionContext.database.getData(oldDataId),
            "resume must retire the old-key entity",
        )
        assertEquals(listOf(oldHash to File.ID), emitter.calls, "resume must emit the old-key tombstone")
        assertTrue(testProxy.pendingMarkers().isEmpty(), "resume must clear the marker")
    }

    /**
     * ⚠⚠ The data-loss case the two-phase journal exists for. A migration that could not emit (no
     * realm) leaves a retry behind; if the user then creates a new file at the retired path, the
     * replay must **not** delete it — and must not tombstone it either, because the tombstone's wire
     * name is derived from the path and so names the new row just as well as the old one.
     */
    @Test
    fun resume_doesNotDeleteOrTombstoneARowRecreatedAtTheRetiredPath() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        testProxy.upload("/a.txt")
        val oldDataId = DataId.StringDataId.create(type = File.ID, keyValue = "/a.txt")
        val newHash = DataId.StringDataId.create(type = File.ID, keyValue = "/b.txt")
            .getDataIdHash().toString()

        // The realm is locked / sync is off: the retirement runs locally, the tombstone does not go
        // out, and the retry survives.
        val offline = RecordingTombstoneEmitter(emits = false)
        val migrator = DataIdMigrator(testProxy.executionContext, offline)
        migrator.migrate(oldDataId, newHash, File.ID)
        assertNull(
            testProxy.executionContext.database.getData(oldDataId),
            "precondition: the local retirement ran — only the tombstone is owed",
        )
        assertEquals(1, testProxy.owedTombstones().size, "precondition: the retry was kept")

        // The user creates a new file at the freed-up path while still locked.
        testProxy.upload("/a.txt")
        val recreated = testProxy.executionContext.database.getData(oldDataId)
        assertNotNull(recreated, "precondition: a new row lives at the old key")

        // The realm unlocks and the retry replays.
        val online = RecordingTombstoneEmitter()
        DataIdMigrator(testProxy.executionContext, online).resumeIncompleteMigrations()

        assertNotNull(
            testProxy.executionContext.database.getData(oldDataId),
            "the recreated row must survive the replay — deleting it is silent data loss",
        )
        assertTrue(
            online.calls.isEmpty(),
            "and no tombstone: it would name the recreated row on every peer just as well",
        )
        assertTrue(testProxy.owedTombstones().isEmpty(), "the abandoned retry must be cleared")
    }

    /** The other half of that split: with the path still free, the owed tombstone does go out. */
    @Test
    fun resume_emitsTheOwedTombstone_whenTheRetiredPathIsStillFree() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        testProxy.upload("/a.txt")
        val oldDataId = DataId.StringDataId.create(type = File.ID, keyValue = "/a.txt")
        val oldHash = oldDataId.getDataIdHash().toString()
        val newHash = DataId.StringDataId.create(type = File.ID, keyValue = "/b.txt")
            .getDataIdHash().toString()

        DataIdMigrator(testProxy.executionContext, RecordingTombstoneEmitter(emits = false))
            .migrate(oldDataId, newHash, File.ID)
        assertEquals(1, testProxy.owedTombstones().size, "precondition: the retry was kept")
        assertTrue(testProxy.pendingMarkers().isEmpty(), "and it left the delete-authorising phase")

        val online = RecordingTombstoneEmitter()
        DataIdMigrator(testProxy.executionContext, online).resumeIncompleteMigrations()

        assertEquals(listOf(oldHash to File.ID), online.calls, "the owed tombstone must go out")
        assertTrue(testProxy.owedTombstones().isEmpty(), "and the retry is cleared once it did")
    }

    /**
     * ⚠⚠ An owed tombstone is bound to the realm it was authored in, and the marker cannot say so:
     * the retry exists *because* there was no realm to name the target in, so no `ownerId` was
     * knowable when it was written. Replayed after an owner switch, its `DataId` resolves under the
     * new realm's key and names whatever lives at that path *there*. So the owner transition drops
     * them — which is what `installReceivedPairingPayload` calls this for.
     */
    @Test
    fun discardOwedTombstones_dropsTheRetries_withoutEmittingAnything() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        testProxy.upload("/a.txt")
        val oldDataId = DataId.StringDataId.create(type = File.ID, keyValue = "/a.txt")
        val newHash = DataId.StringDataId.create(type = File.ID, keyValue = "/b.txt")
            .getDataIdHash().toString()

        DataIdMigrator(testProxy.executionContext, RecordingTombstoneEmitter(emits = false))
            .migrate(oldDataId, newHash, File.ID)
        assertEquals(1, testProxy.owedTombstones().size, "precondition: a retry is owed")

        val online = RecordingTombstoneEmitter()
        val migrator = DataIdMigrator(testProxy.executionContext, online)
        assertEquals(1, migrator.discardOwedTombstones(), "the owed retry is dropped")

        assertTrue(testProxy.owedTombstones().isEmpty(), "and the journal is empty")
        assertTrue(online.calls.isEmpty(), "nothing is emitted into the realm being joined")
        // And a later resume must not resurrect it.
        migrator.resumeIncompleteMigrations()
        assertTrue(online.calls.isEmpty(), "still nothing after a resume")
    }

    /**
     * ⚠⚠ The cross-realm replay, closed structurally rather than by a guard at the transition. A
     * retry names its target by `DataId`; the wire name is derived from that **plus the realm key**,
     * so one written under realm A and replayed under realm B tombstones whatever lives at that path
     * in B — on every one of B's peers. ⚠ A cleanup at the owner switch cannot be the answer on its
     * own: a move can add its marker after that cleanup's snapshot and before the new keys are
     * seated, and no lock spans the two. Naming the array after the owner means the replay never
     * sees the other realm's retries at all.
     */
    @Test
    fun aRetryWrittenUnderOneRealmIsInvisibleToAnother() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        testProxy.upload("/a.txt")
        val oldDataId = DataId.StringDataId.create(type = File.ID, keyValue = "/a.txt")
        val newHash = DataId.StringDataId.create(type = File.ID, keyValue = "/b.txt")
            .getDataIdHash().toString()

        // Realm A, locked: the retirement runs locally and the retry is kept.
        DataIdMigrator(testProxy.executionContext, RecordingTombstoneEmitter(emits = false))
            .migrate(oldDataId, newHash, File.ID)
        assertEquals(1, testProxy.owedTombstones().size, "precondition: realm A holds the retry")

        // The device adopts realm B and unlocks. Its resume must not see A's retry.
        val inRealmB = RecordingTombstoneEmitter(ownerId = "owner-2")
        DataIdMigrator(testProxy.executionContext, inRealmB).resumeIncompleteMigrations()

        assertTrue(
            inRealmB.calls.isEmpty(),
            "replaying it under B names whatever lives at that path in B, and deletes it everywhere",
        )
        assertEquals(
            1,
            testProxy.owedTombstones().size,
            "and A's retry is untouched — B has no business clearing it either",
        )
    }

    /**
     * ⚠⚠ A realm adopted *during* a migration is the case the snapshot guards cannot see. They check
     * that a call's reads agree with each other — and after an adoption they do: the wire name is
     * derived under the new realm, the block is stamped for it, everything is self-consistent. The
     * tombstone is then perfectly matchable against an unrelated entity at the same path in the
     * realm this device just joined, and deletes it on every one of that realm's peers.
     *
     * ⚠ `extraLocalCleanup` runs inside `retire`, before the emit — which is exactly where the
     * adoption has to land for this to bite, so it is where the test puts it.
     */
    @Test
    fun migrate_doesNotEmitIntoARealmAdoptedMidOperation() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        testProxy.upload("/a.txt")
        val oldDataId = DataId.StringDataId.create(type = File.ID, keyValue = "/a.txt")
        val newHash = DataId.StringDataId.create(type = File.ID, keyValue = "/b.txt")
            .getDataIdHash().toString()

        val emitter = RecordingTombstoneEmitter()
        DataIdMigrator(testProxy.executionContext, emitter).migrate(
            oldDataId = oldDataId,
            newHash = newHash,
            entityType = File.ID,
            extraLocalCleanup = { emitter.ownerId = "owner-2" },
        )

        assertTrue(
            emitter.calls.isEmpty(),
            "the tombstone would name an entity in the realm this device just joined, and kill it",
        )
        assertEquals(
            1,
            testProxy.owedTombstones().size,
            "and the retry stays in the ORIGINAL realm's journal, where a later session there can " +
                "still deliver it — the new realm never sees it",
        )
    }

    @Test
    fun resume_isANoOp_whenTheJournalIsEmpty() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        val emitter = RecordingTombstoneEmitter()
        val resumed = DataIdMigrator(testProxy.executionContext, emitter)
            .resumeIncompleteMigrations()
        assertEquals(0, resumed)
        assertTrue(emitter.calls.isEmpty())
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private suspend fun TestProxy.upload(path: String) {
        executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(tContext),
                UploadFile.Params.FILE_NAME to path.removePrefix("/").toTString(tContext),
                UploadFile.Params.DATA to ByteArray(4).toTByteArray(tContext),
            )
        )
    }

    private fun TestProxy.journalRef(
        relationId: String = SyncService.Collections.PENDING_REKEY_MIGRATIONS,
    ): ArrayDatabaseReference = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = tContext,
            type = RekeyMigrationMarker.ID,
            relationId = relationId,
        ),
        isEntity = false,
        elementSchemaId = RekeyMigrationMarker.ID,
    )

    private fun TestProxy.pendingMarkers(): List<RekeyMigrationMarker> = markersIn(journalRef())

    /** ⚠ Suffixed: the phase-2 journal is scoped to the realm, so a bare name reads an empty array. */
    private fun TestProxy.owedTombstones(): List<RekeyMigrationMarker> = markersIn(
        journalRef(SyncService.Collections.PENDING_REKEY_TOMBSTONES + "/" + OWNER_ID)
    )

    private fun TestProxy.markersIn(ref: ArrayDatabaseReference): List<RekeyMigrationMarker> =
        executionContext.database.getArrayData(ref)
            ?.filterIsInstance<RekeyMigrationMarker>()
            ?.toList()
            ?: emptyList()

    /** Mirror `DataIdMigrator.beginMarker` to plant a marker as if a migration crashed mid-flight. */
    private fun TestProxy.writeMarker(oldDataId: DataId, newHash: String, entityType: String) {
        val marker = RekeyMigrationMarker.create(
            context = tContext,
            markerKey = oldDataId.getDataIdHash().toString(),
            oldDataId = oldDataId,
            newHash = newHash,
            entityType = entityType,
        )
        executionContext.database.transaction {
            executionContext.database.addToArray(journalRef(), marker)
        }
    }

    /** @param emits `false` models "no realm to name the target in" — the retained-retry case. */
    private class RecordingTombstoneEmitter(
        private val emits: Boolean = true,
        ownerId: String = OWNER_ID,
    ) : TombstoneEmitter {
        /** ⚠ The phase-2 journal is scoped to this — a fake with no realm keeps no retries at all. */
        var ownerId: String = ownerId
        override val residentOwnerId: String get() = ownerId

        /** ⚠ A fake with no realm: it names locally and therefore does NOT cover the wire (EI-1). */
        override fun emit(
            targetEntityId: org.tenshu.core.entity.DataId,
            targetEntityType: String,
            revokedDeviceId: String?,
        ): String? = emit(
            targetEntityIdHash = targetEntityId.getDataIdHash().toString(),
            targetEntityType = targetEntityType,
            revokedDeviceId = revokedDeviceId,
        )

        val calls = mutableListOf<Pair<String, String>>()
        override fun emit(targetEntityIdHash: String, targetEntityType: String, revokedDeviceId: String?): String? {
            if (!emits) return null
            calls += targetEntityIdHash to targetEntityType
            return "block-${++emitCount}"
        }

        private var emitCount = 0
    }
}
