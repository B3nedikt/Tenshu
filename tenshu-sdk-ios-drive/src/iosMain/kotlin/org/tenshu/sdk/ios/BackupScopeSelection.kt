package org.tenshu.sdk.ios

import app.tenshu.ingest.backup.SyncedFolderController
/**
 * The rules governing which photo buckets iOS backup may hold at once.
 *
 * Pure and separate from [DriveBackupSurface] so the one genuinely subtle part of
 * the picker — that "All Photos" and an album can never be selected together — is
 * unit-tested rather than only exercised by tapping a device.
 *
 * ## Why exclusivity is a rule and not a preference
 *
 * An Android bucket is a **directory**, so a photo lives in exactly one. An iOS
 * album is a **view**, so an asset in an album is also in the user library.
 * Selecting both would mean the same photo backing up to two destination paths.
 * The drain's cross-bucket dedupe (`SyncedFolderController`) stops that costing a
 * second upload, but the result would still be arbitrary: which folder a photo
 * lands in would depend on selection order rather than on anything the user meant.
 * Keeping the two mutually exclusive is what makes the destination meaningful.
 */
internal object BackupScopeSelection {

    /** Whether [current] is the "everything" scope rather than a per-album one. */
    fun isAllPhotos(current: List<String>): Boolean =
        DriveBackupSurface.USER_LIBRARY_BUCKET_ID in current

    /** The "All Photos" scope: the user library alone, never alongside albums. */
    fun allPhotos(): List<String> = listOf(DriveBackupSurface.USER_LIBRARY_BUCKET_ID)

    /** Drop the user library, keeping any albums already chosen. The result may be
     *  empty — per-album mode with nothing picked backs up nothing, which the picker
     *  states rather than hides. */
    fun albumsOnly(current: List<String>): List<String> =
        current.filter { it != DriveBackupSurface.USER_LIBRARY_BUCKET_ID }

    /** The album ids in [current] (i.e. everything but the user-library sentinel). */
    fun albumIds(current: List<String>): List<String> = albumsOnly(current)

    /**
     * Add or remove one album, implying per-album mode.
     *
     * Selecting an album always drops the user library — that combination is the one
     * this type exists to prevent. Appending rather than inserting keeps the list in
     * the order the user ticked things, which is load-bearing: the drain's dedupe
     * gives an overlapping photo to the **first** selected bucket, so selection order
     * decides the destination, and a stable order makes that predictable.
     */
    fun withAlbum(current: List<String>, albumId: String, selected: Boolean): List<String> {
        val albums = albumsOnly(current)
        return if (selected) (albums + albumId).distinct() else albums - albumId
    }
}
