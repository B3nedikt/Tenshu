package org.tenshu.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for [oneOfLogic] — the set membership behind the [oneOf] predicate.
 * Complements its three siblings here ([equalTo], [contains], [inRange]).
 *
 * ⚠ The empty case is the one worth pinning: reading an empty candidate list as "match everything"
 * would turn a caller's empty collection into a silently unbounded read, which is the failure this
 * predicate must never have.
 */
class OneOfLogicTest {

    @Test
    fun `matches when the receiver equals any candidate`() {
        val value = "image".toTString()
        assertTrue(oneOfLogic(value, listOf("document".toTString(), "image".toTString())).get())
    }

    @Test
    fun `matches on the first candidate`() {
        val value = "image".toTString()
        assertTrue(oneOfLogic(value, listOf("image".toTString(), "video".toTString())).get())
    }

    @Test
    fun `does not match when no candidate equals the receiver`() {
        val value = "archive".toTString()
        assertFalse(oneOfLogic(value, listOf("image".toTString(), "video".toTString())).get())
    }

    /**
     * ⚠⚠ Empty is `false` — "in none of these" — never "unfiltered". A caller that computed an
     * empty candidate set gets no rows, not every row.
     */
    @Test
    fun `an empty candidate list matches nothing`() {
        assertFalse(oneOfLogic("image".toTString(), emptyList()).get())
    }

    /**
     * ⚠ Inherits [equalToLogic] wholesale, so a non-`Data` candidate is `false` rather than a
     * crash — the same posture `equalTo` has, and the reason `File.notTrashed()` exists to stop a
     * caller passing a bare Kotlin `false`.
     */
    @Test
    fun `a non-Data candidate does not match rather than throwing`() {
        assertFalse(oneOfLogic("image".toTString(), listOf("image")).get())
    }
}
