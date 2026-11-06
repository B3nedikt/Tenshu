package org.tenshu.core.file

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The half of a deliberate duplication that lives on this side
 * (`control-cross-app-open.md` CO-D2, TT-2a).
 */
class FileTypeIdTest {

    @Test
    fun theTypeStringMatchesTheOneThePlatformRoutesOn() {
        // ⚠⚠ `core-common`'s `HandlerTable` spells this literal out rather than depending on
        // `:file-service` — the platform must not learn what a file is to route to one, exactly as
        // it must not learn what a calendar is (`OccurrenceIdTest` is the same test for the same
        // reason). This is the cheap version of what Rei's declared `handles` set does properly at
        // TT-4. If it fails, a file is addressable and unroutable, which at the tap looks like
        // "nothing happened".
        assertEquals("tenshu/file", File.ID)
    }
}
