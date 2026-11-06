package org.tenshu.file.client

import org.tenshu.core.file.GetStoredThumbnail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The one decision in the app-side declaration (`drive-synced-thumbnails.md` §15.11): what the row becomes, and when it is left alone. */
class WantedThumbnailClassDeclarationTest {

    private val xlarge = GetStoredThumbnail.CLASS_XLARGE
    private val motion = GetStoredThumbnail.CLASS_MOTION

    @Test
    fun aNewClassIsUnionedIntoTheRow() {
        assertEquals(listOf(motion, xlarge), wantedThumbnailClassesAfterDeclaring(stored = listOf(motion), classes = setOf(xlarge)))
        assertEquals(listOf(xlarge), wantedThumbnailClassesAfterDeclaring(stored = emptyList(), classes = setOf(xlarge)))
    }

    /** Steady state writes nothing: a launch that declares what the row already says puts no block on the wire. */
    @Test
    fun anAlreadyDeclaredClassLeavesTheRowAlone() {
        assertNull(wantedThumbnailClassesAfterDeclaring(stored = listOf(xlarge, motion), classes = setOf(xlarge)))
        assertNull(wantedThumbnailClassesAfterDeclaring(stored = listOf(motion, xlarge), classes = setOf(xlarge, motion)))
    }

    /** Only declarable classes ever reach the row — the implicit rungs and typos are dropped, and a row holding them is not "changed" by dropping them alone. */
    @Test
    fun onlyDeclarableClassesCount() {
        assertNull(wantedThumbnailClassesAfterDeclaring(stored = emptyList(), classes = setOf(GetStoredThumbnail.CLASS_LIST, "xxlarge-typo")))
        assertEquals(listOf(xlarge), wantedThumbnailClassesAfterDeclaring(stored = listOf("list"), classes = setOf(xlarge, "large")))
    }
}
