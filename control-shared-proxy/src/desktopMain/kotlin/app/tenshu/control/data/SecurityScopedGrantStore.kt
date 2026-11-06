package app.tenshu.control.data

import org.tenshu.core.ingest.SecurityScopedFolderGrant
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Control's private store of security-scoped folder bookmarks, keyed by grant id.
 *
 * ⚠⚠ **Control is the only writer.** Drive holds a [SecurityScopedFolderGrant] handle (id + display
 * name) and never the bytes, so when macOS reports a bookmark stale, Control re-mints it here under
 * the same id and nothing anyone else writes can put the stale one back. A bookmark is valid only for
 * the app that created it, and it embeds the folder's absolute path, so it stays in Control's own
 * container instead of in Drive's synced preferences.
 *
 * Under the App Sandbox `user.home` is Control's container, so [defaultDir] is private to it. An id
 * reaching [read] or [replace] came over IPC from a client: it is validated before it names a file.
 */
class SecurityScopedGrantStore(private val dir: File = defaultDir()) {

    /** Stores [bookmark] under a fresh random id and returns the id. */
    @Synchronized
    fun put(bookmark: ByteArray): String {
        require(bookmark.isNotEmpty()) { "an empty bookmark is not a grant" }
        val id = UUID.randomUUID().toString().replace("-", "")
        write(id, bookmark)
        return id
    }

    /** The bookmark stored under [grantId], or `null` if the id is unknown or malformed. */
    @Synchronized
    fun read(grantId: String): ByteArray? =
        fileFor(grantId)?.takeIf { it.isFile }?.readBytes()?.takeIf { it.isNotEmpty() }

    /** Replaces the bookmark under an existing [grantId]; false if there is none. */
    @Synchronized
    fun replace(grantId: String, bookmark: ByteArray): Boolean {
        if (bookmark.isEmpty() || fileFor(grantId)?.isFile != true) return false
        write(grantId, bookmark)
        return true
    }

    private fun fileFor(grantId: String): File? =
        if (SecurityScopedFolderGrant.isValidGrantId(grantId)) File(dir, "$grantId.bookmark") else null

    // Temp file + atomic rename: a crash mid-write must not leave a truncated bookmark, which
    // would turn a working folder into one that silently stops backing up.
    private fun write(grantId: String, bookmark: ByteArray) {
        dir.mkdirs()
        val target = requireNotNull(fileFor(grantId))
        val temporary = File(dir, "$grantId.bookmark.tmp")
        temporary.writeBytes(bookmark)
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    companion object {
        fun defaultDir(): File = File(
            System.getProperty("user.home"),
            "Library/Application Support/TenshuControl/folder-grants",
        )
    }
}
