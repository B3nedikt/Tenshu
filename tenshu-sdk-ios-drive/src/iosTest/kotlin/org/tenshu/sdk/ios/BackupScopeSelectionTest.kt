package org.tenshu.sdk.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The exclusivity rule behind the album picker
 * (`drive-synced-folders-new-folder-prompt.md`). "All Photos" and an album are
 * overlapping views of the same assets, so the two can never be selected together;
 * these assert that no operation can produce the forbidden combination, and that
 * selection order — which decides an overlapping photo's destination via the
 * drain's dedupe — is preserved.
 */
class BackupScopeSelectionTest {

    private val lib = DriveBackupSurface.USER_LIBRARY_BUCKET_ID

    @Test
    fun allPhotos_isTheUserLibraryAlone() {
        assertEquals(listOf(lib), BackupScopeSelection.allPhotos())
        assertTrue(BackupScopeSelection.isAllPhotos(listOf(lib)))
        assertFalse(BackupScopeSelection.isAllPhotos(listOf("album-1")))
        assertFalse(BackupScopeSelection.isAllPhotos(emptyList()))
    }

    @Test
    fun selectingAnAlbum_dropsTheUserLibrary() {
        // The whole point: ticking an album while All Photos is on must not yield
        // both — that is the double-destination state the mode split prevents.
        val next = BackupScopeSelection.withAlbum(listOf(lib), "album-1", selected = true)

        assertEquals(listOf("album-1"), next)
        assertFalse(BackupScopeSelection.isAllPhotos(next))
    }

    @Test
    fun albumsAccumulateInSelectionOrder() {
        // Order is load-bearing: the drain gives an overlapping photo to the FIRST
        // selected bucket, so a stable order makes the destination predictable.
        var s = BackupScopeSelection.withAlbum(emptyList(), "a", selected = true)
        s = BackupScopeSelection.withAlbum(s, "b", selected = true)
        s = BackupScopeSelection.withAlbum(s, "c", selected = true)

        assertEquals(listOf("a", "b", "c"), s)
    }

    @Test
    fun reSelectingAnAlbum_doesNotDuplicateItOrReorderIt() {
        var s = BackupScopeSelection.withAlbum(emptyList(), "a", selected = true)
        s = BackupScopeSelection.withAlbum(s, "b", selected = true)
        s = BackupScopeSelection.withAlbum(s, "a", selected = true)

        assertEquals(listOf("a", "b"), s)
    }

    @Test
    fun deselectingTheLastAlbum_leavesNothingSelected_notAllPhotos() {
        // Per-album mode with nothing ticked backs up nothing. It must NOT silently
        // fall back to All Photos — that would re-enable whole-library backup behind
        // the user's back.
        val next = BackupScopeSelection.withAlbum(listOf("a"), "a", selected = false)

        assertEquals(emptyList(), next)
        assertFalse(BackupScopeSelection.isAllPhotos(next))
    }

    @Test
    fun albumScope_stripsTheLibraryButKeepsChosenAlbums() {
        assertEquals(
            listOf("album-1", "album-2"),
            BackupScopeSelection.albumsOnly(listOf(lib, "album-1", "album-2")),
        )
    }
}
