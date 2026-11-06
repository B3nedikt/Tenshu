package app.tenshu.drive.ui.screens.movepicker

data class MovePickerUiState(
    /** Name of the item being moved — shown in the picker title (single source). */
    val sourceName: String = "",
    /** Number of items being moved; drives a count-based title when > 1. */
    val sourceCount: Int = 1,
    /** Display name of the folder currently being browsed (root → empty). */
    val currentFolderName: String = "",
    /**
     * Whether "Move here" is enabled. Disabled while a move is in flight and
     * when the browsed folder is the item's current parent (a no-op move).
     */
    val canMoveHere: Boolean = true,
    val isMoving: Boolean = false,
    /** Set once the move succeeds; the host pops the picker back to its origin. */
    val moved: Boolean = false,
    /** Non-null when a move failed (e.g. name clash); shown to the user. */
    val errorMessage: String? = null,
    /** Whether the "New folder" dialog is visible. */
    val showNewFolder: Boolean = false,
)
