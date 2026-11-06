package org.tenshu.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit coverage for [lowercaseLogic] — the transform behind the [lowercase]
 * chain op (the call-site opt-in for case-insensitive ordering,
 * `sortBy { name.lowercase() }`).
 */
class LowercaseLogicTest {

    @Test
    fun lowercasesMixedCase() {
        assertEquals("report.pdf", lowercaseLogic("Report.PDF".toTString()).get())
    }

    @Test
    fun alreadyLowercaseAndEmptyPassThrough() {
        assertEquals("abc", lowercaseLogic("abc".toTString()).get())
        assertEquals("", lowercaseLogic("".toTString()).get())
    }

    @Test
    fun nonStringReceiverErrors() {
        // A non-string receiver is a wiring bug, not a value case — it must
        // surface loudly rather than return something.
        assertFailsWith<IllegalStateException> {
            lowercaseLogic(true.toTBoolean())
        }
    }
}
