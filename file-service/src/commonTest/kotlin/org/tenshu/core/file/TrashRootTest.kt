package org.tenshu.core.file

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one thing a path-prefix convention gets wrong if nobody writes a test for it. */
class TrashRootTest {

    @Test
    fun aFileInTheTrashIsTrashed() {
        assertTrue(isTrashedPath("/Trash/abc-IMG_0421.jpg"))
        assertTrue(isTrashedPath("/Trash/nested/IMG_0421.jpg"))
    }

    @Test
    fun aFolderThatMerelyStartsWithTrashIsNot() {
        // ⚠⚠ `startsWith("/Trash")` alone would make every photo in a user's `/Trashcan` folder
        // disappear from the feed, from Timeline and from Mia, with nothing anywhere saying why.
        assertFalse(isTrashedPath("/Trashcan/IMG_0421.jpg"))
        assertFalse(isTrashedPath("/Trashed Photos/IMG_0421.jpg"))
    }

    @Test
    fun theTrashFolderItselfIsNotAFileInIt() {
        assertFalse(isTrashedPath("/Trash"))
    }

    @Test
    fun anOrdinaryPathIsNot() {
        assertFalse(isTrashedPath("/Backup/Phone/Camera/IMG_0421.jpg"))
        assertFalse(isTrashedPath("/"))
    }
}
