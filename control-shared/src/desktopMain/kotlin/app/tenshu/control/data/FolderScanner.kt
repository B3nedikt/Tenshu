package app.tenshu.control.data

import java.io.File

/** One on-disk file the desktop [FolderWatcher] may ingest. */
data class ScannedFile(
    val absolutePath: String,
    val name: String,
    val lastModifiedMillis: Long,
)

/**
 * The pure desktop folder-walk for Control's in-process [FolderWatcher]
 * (`drive-synced-folders-phase2.md` §4.2/§7). Ported from Drive's
 * `DesktopMediaSourceProvider.listNewItems` — but Control-side, generic over the
 * registry entry's watched paths, with **no `:drive-shared` dependency** (open-Q6:
 * a clean dedicated walker, not a relocation of the Drive enumerator).
 *
 * Lists immediate image files under each watched directory with
 * `lastModified > sinceMillis`, oldest-first so a watermark advances monotonically.
 * Images only for v1 — Control can't see Drive's per-device video toggle through
 * the registry; video parity is a follow-up. Kept pure (no watcher / coroutine /
 * client state) so it is desktop-unit-tested independently.
 */
object FolderScanner {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")

    fun scanNewImages(paths: List<String>, sinceMillis: Long): List<ScannedFile> =
        paths
            .flatMap { path ->
                File(path).listFiles()?.asList().orEmpty()
                    .filter { it.isFile && isImage(it.name) && it.lastModified() > sinceMillis }
                    .map { ScannedFile(it.absolutePath, it.name, it.lastModified()) }
            }
            .sortedBy { it.lastModifiedMillis }

    private fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
}
