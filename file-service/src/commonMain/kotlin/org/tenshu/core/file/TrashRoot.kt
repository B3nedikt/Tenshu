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

/**
 * Where Photos keeps each non-destructive edit's baked **rendition** (`photos-editing.md` §12.3,
 * ED-D10): `/Trash/.edits/<contentId>-<ms>-<random>.jpg` (a name never used twice — every preview cache is keyed by path).
 *
 * ⚠⚠ **Under the trash on purpose, and these files are LIVE.** A rendition is a real file — its own
 * content, its own thumbnail rungs, counted against storage — that must never be read as a
 * photograph: in the feed it would be a duplicate, in Timeline a second photo of the moment, in the
 * indexer a second set of vectors. Every photo reader already excludes the trash, in the chain
 * ([notTrashed]) and in the proxies ([isTrashedPath]), so a rendition here is excluded everywhere by
 * construction, with no new predicate and no `:file-service` bump.
 *
 * ⚠⚠ The price is that `/Trash` no longer holds only deleted files. **A sweep that empties the trash
 * must skip [isEditRenditionPath]**, or it deletes the picture of every edited photo in the realm.
 * Nothing empties it wholesale today: Photos deletes trashed files one `TrashedPhoto` record at a time.
 */
const val EDIT_RENDITIONS_ROOT: String = "$TRASH_ROOT/.edits"

/** Whether [path] names an edit's rendition ([EDIT_RENDITIONS_ROOT]) — never a trashed photo. */
fun isEditRenditionPath(path: String): Boolean = path.startsWith("$EDIT_RENDITIONS_ROOT/")
