package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.sync.common.PendingContentEmit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The stamps [ContentDeclarationBackfill] revalidates against. ⚠ Per CONTENT: the manifest is
 * content-addressed, so a hold through any property must move the one stamp every property reads.
 */
class EmitKeyLocksStampTest {

    private val p1 = PendingContentEmit.contentIdKeyOf("c1", "tenshu/file/file/data")
    private val p2 = PendingContentEmit.contentIdKeyOf("c1", "tenshu/file/file/thumbnail")

    @Test
    fun aHoldThroughOneProperty_movesTheContentsStamp() = runTest {
        val locks = EmitKeyLocks()
        val before = locks.stampOf("c1")

        locks.beginHold(p1)
        locks.endHold(p1)

        assertNotEquals(before, locks.stampOf("c1"))
    }

    @Test
    fun aContentReadsBusyWhileAnyOfItsKeysIsHeld() = runTest {
        val locks = EmitKeyLocks()
        locks.beginHold(p1)
        locks.beginHold(p2)
        locks.endHold(p1)

        assertNull(locks.stampOf("c1"), "p2 is still being emitted")
        locks.endHold(p2)
        assertEquals(locks.stampOf("c1"), locks.stampOf("c1"), "idle again, and stable")
    }

    @Test
    fun aLegacyEntityKey_touchesNoContentStamp() = runTest {
        val locks = EmitKeyLocks()
        val before = locks.stampOf("c1")
        locks.beginHold(PendingContentEmit.keyOf("12345", "tenshu/file/file/data"))

        assertEquals(before, locks.stampOf("c1"))
    }
}
