package app.tenshu.drive.data

/**
 * The sort keys a folder listing can be ordered by — the Drive counterpart of
 * Google Drive's "Sort by" menu. Each key maps to a `File` property the
 * `sortBy` chain step (`docs/proposals/core-chain-sort.md`) can order on:
 *
 *  - [NAME] → the derived `File.Name` (sorted via a selector lambda, since it is
 *    a computed dynamic property, not a stored value the property-sort branch reads);
 *  - [MODIFIED] → the `File.ContentModifiedAt` value property — the file's own date, the one
 *    every row shows, not the realm's "last touched" (`File.LastModifiedAt`);
 *  - [CREATED] → the `File.CreatedAt` value property;
 *  - [SIZE] → the `File.Size` value property.
 *
 * Google's "…by me" rows are intentionally absent: they need per-actor
 * attribution ("modified by me" only differs from "modified" in a shared realm)
 * or an access log we don't keep ("opened by me"). See the sort-menu discussion.
 */
enum class FileSortKey {
    NAME,
    MODIFIED,
    CREATED,
    SIZE,
    ;

    /**
     * The direction a freshly-picked key defaults to: A→Z for [NAME], but
     * newest / largest first for the date and size keys (matching the intuition
     * that "sort by date" means "most recent on top").
     */
    val defaultDescending: Boolean get() = this != NAME
}

/**
 * A folder listing's sort selection: a [key] plus a direction. Held in
 * [app.tenshu.drive.ui.screens.files.FilesScreenViewModel] and pushed into
 * [FilesRepository.getFilesPaged]'s chain.
 */
data class FileSort(
    val key: FileSortKey = FileSortKey.NAME,
    val descending: Boolean = FileSortKey.NAME.defaultDescending,
) {
    companion object {
        /**
         * Name, A→Z — the conventional *first* selection, and the default D6 of
         * `drive-sorted-folder-paging.md` picks once natural order goes. Today the
         * listing's actual default is still **no sort** (natural order, a `null`
         * selection), and the sort menu offers no way back to it; SP-6 retires that
         * path after SP-3's device pass (D5). ⚠ Until 2026-09-15 a sort was also the
         * *slow* path — the whole folder as one page; it pages like the natural
         * listing now (see [FilesRepository.getFilesPaged]).
         */
        val Default = FileSort()
    }
}
