package app.tenshu.drive.ui.error

import androidx.compose.runtime.Composable
import app.tenshu.drive.data.DriveErrorKeys
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.error_drive_preview_pdf_unreadable
import app.tenshu.drive.shared.resources.error_drive_preview_undecodable
import app.tenshu.drive.shared.resources.error_drive_preview_video_unplayable
import app.tenshu.error.ErrorCopyKey
import app.tenshu.ui.error.label
import org.jetbrains.compose.resources.stringResource

/**
 * Drive's own error copy, chained in front of the shared catalogue — the same shape
 * `ContactsErrorCopy.contactsLabel()` uses, for the same reason.
 *
 * ⭐ `core-error-copy-catalogue.md` §5.1: `:ui-core` owns the core `TError` taxonomy and the
 * fallback; an app contributes keys for failures that are not `TError`s at all (bytes that arrived
 * and would not decode). Screens call this rather than `label()` directly, so an app key and a core
 * key look identical at the call site.
 *
 * ⚠⚠ A miss falls through to [label], which renders the **generic sentence** — never the error's own
 * message. Three preview screens used to render `RepositoryError.toString()` instead, putting
 * `Unknown(copyKey=ErrorCopyKey(value=…))` on a user's screen; on a cross-app open that string was
 * the entire app (CO-D11).
 */
@Composable
fun ErrorCopyKey.driveLabel(): String = when (this) {
    DriveErrorKeys.PREVIEW_UNDECODABLE -> stringResource(Res.string.error_drive_preview_undecodable)
    DriveErrorKeys.PREVIEW_PDF_UNREADABLE -> stringResource(Res.string.error_drive_preview_pdf_unreadable)
    DriveErrorKeys.PREVIEW_VIDEO_UNPLAYABLE -> stringResource(Res.string.error_drive_preview_video_unplayable)
    else -> label()
}
