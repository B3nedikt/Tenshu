package org.tenshu.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit coverage for [substringAfterLastLogic] — the transform behind
 * [substringAfterLast], used by the derived `File.Name` dynamic-property template.
 */
class SubstringAfterLastLogicTest {

    @Test
    fun returnsSegmentAfterLastSeparator() {
        assertEquals("c.txt", substringAfterLastLogic("/a/b/c.txt".toTString(), "/").get())
        assertEquals("photo.png", substringAfterLastLogic("/photo.png".toTString(), "/").get())
    }

    @Test
    fun absentSeparatorReturnsWholeString() {
        // Mirrors Kotlin's String.substringAfterLast — a rootless name is itself.
        assertEquals("readme", substringAfterLastLogic("readme".toTString(), "/").get())
    }

    @Test
    fun acceptsTStringSeparator() {
        // The wire path hands the separator back as a TString.
        assertEquals("c", substringAfterLastLogic("a/b/c".toTString(), "/".toTString()).get())
    }
}
