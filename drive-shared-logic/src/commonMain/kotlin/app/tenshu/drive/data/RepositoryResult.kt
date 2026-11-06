package app.tenshu.drive.data

import app.tenshu.error.ErrorCopyKey

sealed interface RepositoryResult<out T> {
    data class Success<T>(val value: T) : RepositoryResult<T>
    data class Error(val error: RepositoryError) : RepositoryResult<Nothing>
}

sealed interface RepositoryError {
    data object AlreadyExists : RepositoryError

    /**
     * CR-2 B5 §4 (`StillSyncingError`): the row is still a provisional slice-snapshot row on a device
     * catching up, so moving or renaming it is refused until the log reaches it. Transient — the
     * copy says to retry, and a retry after catch-up succeeds.
     */
    data object StillSyncing : RepositoryError

    /**
     * CR-2 B6 D3 (`MoveNeedsLocalBytesError`): an offline move or rename of content this device does
     * not hold is refused — the one case B4's repair cannot cover. The copy names the two ways out:
     * download first, or move once online.
     */
    data object MoveNeedsLocalBytes : RepositoryError
    data class UnexpectedError(val message: String) : RepositoryError
    /**
     * ⚠⚠ Carries a catalogue **key**, not a sentence — `core-error-copy-catalogue.md` §5.0.
     *
     * ⭐ The `String` it used to carry was never rendered: all four consumers discarded it
     * (`AddFileActionsViewModel` -> `ERROR_UPLOAD_FAILED`; the rename, create-folder and
     * move-picker ViewModels -> `ERROR_FAILED`). So the sentences being marshalled here — mostly
     * `RestSyncRelay` reason phrases — were reaching a field nothing ever read. A key is strictly
     * more useful and cannot be rendered as the proxy's English.
     */
    data class Unknown(val copyKey: ErrorCopyKey) : RepositoryError

    /**
     * Tier-3 pre-flight (`docs/proposals/storage-capacity-gating.md` §2): the
     * action won't fit in the owner's remaining storage. Surfaced by the
     * folder-upload pre-flight so the loop never starts a doomed job.
     */
    data class InsufficientStorage(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : RepositoryError
}

/**
 * Catalogue keys Drive owns, for failures that are **not** `TError`s — a preview whose bytes
 * arrived but would not decode.
 *
 * ⭐ `core-error-copy-catalogue.md` §5.1's chaining, the same shape `ContactsErrorKeys` uses:
 * `:ui-core` owns the core taxonomy and the fallback, an app contributes its own keys in front.
 * The copy lives in `:drive-shared-ui`'s `strings.xml`, not in `:ui-core` — app-specific sentences
 * in the shared module are the wrong layer (ADR 0016 anchor #7).
 */
object DriveErrorKeys {
    val PREVIEW_UNDECODABLE = ErrorCopyKey("error_drive_preview_undecodable")
    val PREVIEW_PDF_UNREADABLE = ErrorCopyKey("error_drive_preview_pdf_unreadable")
    val PREVIEW_VIDEO_UNPLAYABLE = ErrorCopyKey("error_drive_preview_video_unplayable")
}

/**
 * The catalogue key for a repository failure — what a **user-facing** surface may render.
 *
 * ⚠⚠ **This exists because three screens were rendering `error.toString()`**, which puts the sealed
 * class's own name and the key vocabulary on screen: literally
 * `Unknown(copyKey=ErrorCopyKey(value=error_not_found))`. `core-error-copy-catalogue.md` §5.0 names
 * that exact failure — *"a catalogue miss renders the generic sentence, never the error's own
 * message"* — and it was worst on a cross-app open, where the preview is the whole app (CO-D11) and
 * that string was the only thing the user got.
 *
 * ⚠ [RepositoryError.UnexpectedError]'s `message` is deliberately **dropped** rather than shown. It
 * is a diagnostic authored below the proxy boundary; the whole point of §5.0 is that it never
 * reaches a user's screen.
 */
fun RepositoryError.copyKey(): ErrorCopyKey = when (this) {
    is RepositoryError.Unknown -> copyKey
    RepositoryError.AlreadyExists -> ErrorCopyKey("error_already_exists")
    RepositoryError.StillSyncing -> ErrorCopyKey("error_still_syncing")
    RepositoryError.MoveNeedsLocalBytes -> ErrorCopyKey("error_move_needs_local_bytes")
    is RepositoryError.InsufficientStorage -> ErrorCopyKey("error_insufficient")
    is RepositoryError.UnexpectedError -> ErrorCopyKey("error_unexpected")
}
