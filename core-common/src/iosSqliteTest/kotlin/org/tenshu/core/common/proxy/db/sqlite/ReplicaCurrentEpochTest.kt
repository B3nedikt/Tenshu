package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.proxy.db.ReplicaEpochSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [SharedReplicaStore.currentEpoch]: what a device-local cache derived from the store keys itself
 * to (Photos' inference stamp, `ios-face-grouping.md` §2.2 item 8; threat model A40).
 *
 * ⚠⚠ The property that matters is the **unknown** answer: a handle that cannot vouch for the store
 * says `null`, never the epoch its open read. A sibling's wipe moves the epoch under a handle that
 * has not accessed since, and an answer from memory would call that store unchanged.
 */
class ReplicaCurrentEpochTest {

    private fun ReplicaProcess.metaEpoch(): Long = sql { it.queryLong("SELECT v FROM meta WHERE k = 'epoch'") }

    @Test
    fun aServingHandleAnswersTheStoresEpochAndItHoldsAcrossWrites() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        assertIs<ReplicaEpochSource>(store)
        assertEquals(process.metaEpoch(), store.currentEpoch())
        store.storeObject(row(1))
        store.removeObject(1)
        assertEquals(store.epoch, store.currentEpoch(), "a write is not a replacement")
        // And a second process's handle on the same file reads the same one.
        assertEquals(store.epoch, ReplicaProcess(process.directory).store().currentEpoch())
    }

    @Test
    fun aSiblingsWipeIsUnknownToTheOldHandleAndANewEpochToTheNextOpen() {
        val directory = tempReplica()
        val resident = ReplicaProcess(directory).store()
        resident.storeObject(row(1))
        val before = assertNotNull(resident.currentEpoch())

        // Another app wipes the store (a reset, Forget this device) while [resident] is open.
        runBlocking { ReplicaProcess(directory).store().wipeAllObjects() }

        // ⚠⚠ The open read [before]; the store no longer has it. Not that value, and not a throw.
        assertNull(resident.currentEpoch(), "a replaced store was vouched for from the open's epoch")
        assertEquals(ReplicaFence.REPLACED, resident.fencedBy, "the read is an access: it fences")
        val reopened = ReplicaProcess(directory).store()
        assertNotEquals(before, assertNotNull(reopened.currentEpoch()), "the wiped store kept its epoch")
        assertNull(reopened.getObject(1))
    }

    @Test
    fun theWipingHandleItselfCannotVouchForTheStoreAfterwards() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        val before = store.epoch
        runBlocking { store.wipeAllObjects() }
        assertNull(store.currentEpoch())
        assertNotEquals(before, process.metaEpoch())
    }

    @Test
    fun aReleasedHandleCannotVouchForTheStore() {
        val store = ReplicaProcess(tempReplica()).store()
        store.close()
        assertNull(store.currentEpoch())
    }

    @Test
    fun anEpochTheStoreCannotReadIsUnknownAndNeverThrows() {
        val store = ReplicaProcess(tempReplica()).store()
        store.epochFault = { throw SqliteException(1, "the epoch read failed (test)") }
        assertNull(store.currentEpoch())
    }
}
