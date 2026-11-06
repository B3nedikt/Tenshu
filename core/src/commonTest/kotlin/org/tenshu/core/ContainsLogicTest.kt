package org.tenshu.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for [containsLogic] — the substring match behind the [contains]
 * predicate. **Case-sensitive by default** (matching Kotlin's `String.contains`);
 * insensitivity is the explicit `ignoreCase` opt-in. Complements the end-to-end
 * proxy passes in `SearchAllAndFilterTest`.
 */
class ContainsLogicTest {

    @Test
    fun matchesSubstringCaseSensitivelyByDefault() {
        assertTrue(containsLogic("Report.pdf".toTString(), "Rep".toTString()).get())
        assertTrue(containsLogic("Report.pdf".toTString(), "Report.pdf".toTString()).get())
        // Kotlin semantics: case mismatch does NOT match without the flag.
        assertFalse(containsLogic("Report.pdf".toTString(), "rep".toTString()).get())
        assertFalse(containsLogic("Report.pdf".toTString(), "PORT".toTString()).get())
    }

    @Test
    fun ignoreCaseMatchesAcrossCase() {
        assertTrue(containsLogic("Report.pdf".toTString(), "rep".toTString(), ignoreCase = true).get())
        assertTrue(containsLogic("Report.pdf".toTString(), "PORT".toTString(), ignoreCase = true).get())
    }

    @Test
    fun nonMatchingSubstringIsFalse() {
        assertFalse(containsLogic("Report.pdf".toTString(), "xyz".toTString()).get())
        assertFalse(containsLogic("Report.pdf".toTString(), "xyz".toTString(), ignoreCase = true).get())
    }

    @Test
    fun acceptsRawStringOperand() {
        // The direct in-memory `contains` path hands the operand through as a raw
        // String (not yet coerced to a TString) — that must still match.
        assertTrue(containsLogic("Vacation".toTString(), "cat").get())
        assertTrue(containsLogic("Vacation".toTString(), "CAT", ignoreCase = true).get())
    }

    @Test
    fun nonStringReceiverIsFalse() {
        // contains is text-only: a non-string receiver never matches.
        assertFalse(containsLogic(true.toTBoolean(), "true").get())
    }

    @Test
    fun nonStringOperandIsFalse() {
        assertFalse(containsLogic("abc".toTString(), 1L).get())
    }
}
