package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.file.File
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import org.tenshu.sync.common.DataIdMigrator
import org.tenshu.sync.common.TombstoneEmitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The move tombstone must be named by the **emitter**, not by the caller.
 *
 * ⚠⚠ Why this needs its own test. A tombstone's relay-visible target is
 * `HMAC(K_entity, identity)`, which only the emitter can derive, and `TombstoneEmitter`'s contract
 * spells out what a locally-named one costs: *"its tombstone addresses an entity no peer can match,
 * and nothing errors: both sides sync and the row simply never dies"*. A `DataIdMigrator.retire`
 * that passes `getDataIdHash()` — the local name — leaves peers holding every moved or renamed file
 * at **both** paths, silently.
 *
 * ⚠⚠ And why [DataIdMigratorJournalTest] cannot catch it: its fake collapses the id form into the
 * hash form (its own KDoc says it "does NOT cover the wire"), so both overloads record identically
 * there. The assertion that bites is **which overload was called** — comparing names cannot
 * distinguish them, because a fake with no realm derives the same local value either way.
 */
class DataIdMigratorWireNameTest {

    @Test
    fun retire_emitsThroughTheIdForm_soTheEmitterCanNameItForTheWire() = runTest {
        val testProxy = createFileProxy(backgroundScope)
        testProxy.upload("/a.txt")
        val oldDataId = DataId.StringDataId.create(type = File.ID, keyValue = "/a.txt")
        val newHash = DataId.StringDataId.create(type = File.ID, keyValue = "/b.txt")
            .getDataIdHash().toString()
        val emitter = OverloadRecordingEmitter()

        DataIdMigrator(testProxy.executionContext, emitter).migrate(oldDataId, newHash, File.ID)

        assertEquals(1, emitter.byId.size, "exactly one old-key tombstone")
        assertEquals(
            oldDataId.getDataIdHash().toString(),
            emitter.byId.single().getDataIdHash().toString(),
            "the migrator must hand the emitter the DataId, so it can derive the keyed wire name",
        )
        assertTrue(
            emitter.byHash.isEmpty(),
            "a locally-named tombstone addresses an entity no peer can match, and fails silently — " +
                "which is why this asserts the overload rather than the resulting name",
        )
    }

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

    /** ⚠ Deliberately does NOT collapse the two overloads — a collapse would hide a call to the wrong one. */
    private class OverloadRecordingEmitter : TombstoneEmitter {
        val byId = mutableListOf<DataId>()
        val byHash = mutableListOf<String>()
        private var emitCount = 0

        override fun emit(
            targetEntityId: DataId,
            targetEntityType: String,
            revokedDeviceId: String?,
        ): String {
            byId += targetEntityId
            return "block-${++emitCount}"
        }

        override fun emit(
            targetEntityIdHash: String,
            targetEntityType: String,
            revokedDeviceId: String?,
        ): String {
            byHash += targetEntityIdHash
            return "block-${++emitCount}"
        }
    }
}
