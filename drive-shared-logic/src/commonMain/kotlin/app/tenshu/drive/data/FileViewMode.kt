package app.tenshu.drive.data

/**
 * How the folder listing is laid out — the list-vs-grid display switch (the
 * second half of the listing toolbar, alongside [FileSort]). Pure presentation:
 * unlike the sort, it does not touch the listing chain, only which item
 * composable the list container renders.
 */
enum class FileViewMode {
    /** A vertical list of rows (`HorizontalFileItem`) — the default. */
    LIST,

    /** A thumbnail-forward grid of tiles (`VerticalFileItem`). */
    GRID,
    ;

    /** The other mode — backs the single toolbar toggle button. */
    val toggled: FileViewMode get() = if (this == LIST) GRID else LIST
}
