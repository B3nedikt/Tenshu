package app.tenshu.drive.ui.common

import androidx.compose.runtime.Composable

/**
 * Platform "open in another app" for a file Drive can't preview in-app — documents,
 * archives, and any unhandled type (`docs/proposals/drive-content-preview.md` §6,
 * Decision D3). It hands the OS the **already-decrypted** temp file (written via
 * [writePreviewTempFile]) at [open]'s `filePath`, so a third-party app can open it:
 * Android via a `FileProvider` content URI + `ACTION_VIEW` chooser, desktop via
 * `java.awt.Desktop.open`.
 *
 * [canOpenExternally] decides whether the affordance is shown at all: `true`
 * (Android, and desktop where a default handler exists) → the screen offers an
 * "Open in another app" action; `false` → it's hidden. [open] takes the temp file's
 * absolute `filePath` and the original `fileName` (its extension picks the handler /
 * MIME type).
 *
 * ⚠ Handing the file to another app is a **deliberate, user-initiated disclosure**
 * of decrypted A5 user data via the OS (threat-model A5 amendment). The caller writes
 * the temp file and deletes it on screen exit; once a third-party app has opened it,
 * that app's own copy is outside Drive's control.
 */
class OpenFileLauncher(
    val canOpenExternally: Boolean,
    val open: (filePath: String, fileName: String) -> Unit,
)

@Composable
expect fun rememberOpenFileLauncher(): OpenFileLauncher
