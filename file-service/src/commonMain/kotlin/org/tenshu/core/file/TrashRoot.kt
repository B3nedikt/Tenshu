package org.tenshu.core.file

/**
 * Where a deleted file waits before it is gone for good: `/Trash/<contentId>-<name>`.
 *
 * ⚠ It lives here, in the module that owns the path namespace, for exactly [BACKUP_ROOT]'s reason —
 * **three** components must now agree on it: Photos, which moves files here; the two photo reads
 * (`PhotosCapturedBetween`, `PhotosNear`), which must not serve a trashed photo to Timeline or Mia;
 * and the Photos feed, which filters on [File.Trashed]. A convention that several modules must
 * agree on and none owns is the join that degrades silently when one of them changes it.
 *
 * ⚠⚠ **Trash is a realm folder, not an app's private state.** Drive lists it like any other folder,
 * which is correct — one realm, one trash — and it is why deleting is a *move*: the file keeps its
 * bytes, its `contentId` and its history, so restoring is another move rather than a re-upload.
 */
const val TRASH_ROOT: String = "/Trash"

/**
 * Whether [path] names a file inside the trash.
 *
 * ⚠ Prefix + separator, never `startsWith(TRASH_ROOT)` alone: a user folder called `/Trashcan`
 * would otherwise vanish from every photo read in the realm, and nothing would say why.
 */
fun isTrashedPath(path: String): Boolean = path.startsWith("$TRASH_ROOT/")
