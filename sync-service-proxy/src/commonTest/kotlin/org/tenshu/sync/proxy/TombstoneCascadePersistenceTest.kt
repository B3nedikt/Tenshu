package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CR-2 B3b (`content-plane-cr2-quorumless.md` §10.7, B2 audit W5): a pending tombstone's cascade
 * survives a restart.
 *
 * ⚠⚠ The defect: the tombstone is durable, its cascade lived only in memory, so a process death
 * between the delete and the push lost every descendant's name. Under candidate B the tombstone push
 * must carry that list (§10.2 G1), so a restart would push a folder's tombstone unable to name its
 * subtree, and the subtree's edges would stand for ever.
 */
class TombstoneCascadePersistenceTest {

    private fun proxy() = TestProxy(
        schemaRegistry = createSyntheticTestRegistry().apply { registerConfig(syncServiceCommonModule) },
    )

    @Test
    fun aCascadeRecordedBeforeARestartIsStillThereAfterIt() {
        val proxy = proxy()
        // "Before the restart": the process-shared instance the observer records into.
        TombstoneCascades(DatabaseTombstoneCascadeStore(proxy.executionContext))
            .record("block-1", listOf("wire-a", "wire-b"))

        // "After the restart": a fresh instance over the same store, nothing in memory.
        val afterRestart = TombstoneCascades(DatabaseTombstoneCascadeStore(proxy.executionContext))

        assertEquals(listOf("wire-a", "wire-b"), afterRestart.holdersOf("block-1"))
    }

    @Test
    fun forgettingACascadeRemovesItFromTheStore_soItDoesNotComeBackAfterTheNextRestart() {
        val proxy = proxy()
        val store = DatabaseTombstoneCascadeStore(proxy.executionContext)
        val cascades = TombstoneCascades(store)
        cascades.record("block-1", listOf("wire-a"))
        cascades.record("block-2", listOf("wire-b"))

        cascades.forget(listOf("block-1"))

        assertTrue(store.load("block-1").isEmpty(), "the forgotten cascade is gone from the store")
        assertEquals(listOf("wire-b"), store.load("block-2"), "and the other one is untouched")
        assertTrue(TombstoneCascades(store).holdersOf("block-1").isEmpty(), "a forgotten cascade must not be re-read")
    }

    @Test
    fun anEmptyCascadeIsNotStored() {
        val proxy = proxy()
        val store = DatabaseTombstoneCascadeStore(proxy.executionContext)

        TombstoneCascades(store).record("block-1", emptyList())

        assertTrue(store.load("block-1").isEmpty(), "a delete with no descendants journals nothing")
    }

    @Test
    fun aStoreThatFailsToWriteDoesNotFailTheDelete() {
        // ⚠ The delete must not fail because its cascade could not be journalled. ⭐ CR-2 B3d: the push
        // reads the cascade from the store, so an unjournalled one is simply not on the wire — the
        // in-memory copy B3b kept for this case could never reach the push, and is gone. A leak, never
        // a loss: every other device's reaps still remove those edges.
        val failing = object : TombstoneCascadeStore {
            override fun save(blockId: String, holderNames: List<String>) = error("disk full")
            override fun load(blockId: String): List<String> = emptyList()
            override fun delete(blockIds: Collection<String>) = Unit
        }

        TombstoneCascades(failing).record("block-1", listOf("wire-a"))
    }

    @Test
    fun aStoreThatFailsToReadAnswersEmpty_andIsAskedAgainNextTime() {
        var reads = 0
        val flaky = object : TombstoneCascadeStore {
            override fun save(blockId: String, holderNames: List<String>) = Unit
            override fun load(blockId: String): List<String> {
                reads++
                if (reads == 1) error("store not open yet")
                return listOf("wire-a")
            }
            override fun delete(blockIds: Collection<String>) = Unit
        }
        val cascades = TombstoneCascades(flaky)

        assertTrue(cascades.holdersOf("block-1").isEmpty(), "the first read failed")
        assertEquals(listOf("wire-a"), cascades.holdersOf("block-1"), "and the next ask reads again")
    }
}
