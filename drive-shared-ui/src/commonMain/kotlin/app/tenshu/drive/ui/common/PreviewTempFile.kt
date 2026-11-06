package app.tenshu.drive.ui.common

/**
 * Materialises decrypted file [bytes] to a **private** temp file and returns its
 * absolute path (or `null` on failure). Two preview paths need a real file rather
 * than a memory blob: video playback (a player seeks against a file path — D2a,
 * `docs/proposals/drive-content-preview.md` §5) and "open in another app" (the OS
 * hands a third-party app a file/URI — D3, §6). [fileExtension] (no leading dot)
 * hints the format so the opening app picks the right handler.
 *
 * ⚠ The file is **transient decrypted plaintext on disk** (threat-model A5
 * amendment): it lives in the app-private temp dir and must be removed via
 * [deletePreviewTempFile] as soon as the preview ends.
 */
expect fun writePreviewTempFile(bytes: ByteArray, fileExtension: String): String?

/** Deletes a temp file created by [writePreviewTempFile]. Best-effort, never throws. */
expect fun deletePreviewTempFile(filePath: String)
