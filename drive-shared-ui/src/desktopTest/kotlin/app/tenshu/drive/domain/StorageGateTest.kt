package app.tenshu.drive.domain

import app.tenshu.ingest.backup.StorageStatusModel
import kotlin.test.Test
import kotlin.test.assertEquals

class StorageGateTest {

    @Test
    fun unknownStatus_isAllowed_optimistically() {
        assertEquals(StorageGateDecision.Allow, StorageGate.evaluate(null))
    }

    @Test
    fun noAccount_needsStorage() {
        val status = StorageStatusModel(hasStorage = false, usedBytes = 0L, limitBytes = 0L)
        assertEquals(StorageGateDecision.NeedsStorage, StorageGate.evaluate(status))
    }

    @Test
    fun boundWithRoom_isAllowed() {
        val status = StorageStatusModel(hasStorage = true, usedBytes = 10L, limitBytes = 1_000L)
        assertEquals(StorageGateDecision.Allow, StorageGate.evaluate(status))
    }

    @Test
    fun boundButFull_isFull() {
        val status = StorageStatusModel(hasStorage = true, usedBytes = 1_000L, limitBytes = 1_000L)
        assertEquals(StorageGateDecision.Full, StorageGate.evaluate(status))
    }

    @Test
    fun boundAndOverLimit_isFull() {
        val status = StorageStatusModel(hasStorage = true, usedBytes = 2_000L, limitBytes = 1_000L)
        assertEquals(StorageGateDecision.Full, StorageGate.evaluate(status))
        assertEquals(0L, status.remainingBytes)
    }
}
