package org.tenshu.core.common.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The single-thread half of [ThreadConfinedSlot]'s contract, on every target. That another thread
 * sees nothing is pinned where threads exist: `ReferenceCollectorConfinementTest` (JVM) and
 * `ThreadConfinedSlotIosTest` (Kotlin/Native).
 */
class ThreadConfinedSlotTest {

    @Test
    fun startsEmptyAndReturnsWhatWasSet() {
        val slot = ThreadConfinedSlot<String>()
        assertNull(slot.get())
        slot.set("value")
        assertEquals("value", slot.get())
    }

    @Test
    fun settingNullEmptiesIt() {
        val slot = ThreadConfinedSlot<String>()
        slot.set("value")
        slot.set(null)
        assertNull(slot.get())
    }

    @Test
    fun twoSlotsDoNotShareAValue() {
        // On Kotlin/Native every slot of a thread lives in one map — they must not collide.
        val first = ThreadConfinedSlot<String>()
        val second = ThreadConfinedSlot<String>()
        first.set("first")
        assertNull(second.get())
        second.set("second")
        assertEquals("first", first.get())
        first.set(null)
        assertEquals("second", second.get())
        second.set(null)
    }
}
