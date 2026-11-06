package app.tenshu.liststate

/**
 * Which of the three states a list surface is in, for UI states that keep their rows in a plain
 * `List` field.
 *
 * [ListLoadState] is the repository-boundary shape — it *carries* the rows, so the failure and the
 * rows cannot be read apart. A UI state cannot use it directly without either duplicating the rows
 * or breaking every consumer that reads the plain list (SwiftUI reads these fields straight off the
 * shared view models). So the screen keeps `items: List<T>` and adds one of these beside it.
 *
 * Mirrors the `loading` / `error` booleans `SharedBrowseUiState` already ships, minus the state
 * those two can express but nothing should ever be in — both true at once.
 */
enum class ListLoadPhase {
    /** In flight, nothing yet. ⚠ Not "empty": the read has not answered. */
    LOADING,

    /** Answered. An empty list here is the genuine zero-state. */
    LOADED,

    /** The read failed. The rows alongside are the last known good ones, possibly none. */
    FAILED,
}
