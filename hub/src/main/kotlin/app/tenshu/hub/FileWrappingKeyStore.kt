package app.tenshu.hub

import app.tenshu.control.data.WrappingKeyStore
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64

/**
 * [WrappingKeyStore] backed by an owner-only file under the hub's data directory —
 * the Linux answer to the macOS Keychain-backed `KeychainWrappingKeyStore`.
 *
 * Why it exists: `DesktopMasterKeyStore` falls back to **in-memory** secrets when no
 * wrapping key source is available (the Linux desktop status quo), which means the
 * device re-pairs on every restart. A headless always-on node whose whole point is to
 * *survive reboots* (hub proposal §13 H1) cannot live with that, and a Pi has no OS
 * credential store to lean on.
 *
 * Posture (deliberate, documented): the wrapping key sits on the same disk as the
 * `secrets.enc` blob it protects, so at the *filesystem* level this is equivalent to
 * plaintext-at-rest key custody, gated only by POSIX permissions (0700 data dir,
 * 0600 key file). The disk-level at-rest answer for the Hub is **LUKS on the NVMe**
 * (hub proposal §12.1, ops-level, no code), exactly as recorded for the H3 full-replica
 * role — see `docs/sync/threat-model.md` T-C-17's hub note. What this buys over the
 * fallback is durability, not secrecy-from-root.
 */
class FileWrappingKeyStore(private val file: File) : WrappingKeyStore {

    override fun loadOrCreate(): ByteArray? = try {
        readExisting() ?: createFresh()
    } catch (e: Exception) {
        System.err.println("[FileWrappingKeyStore] cannot load or create ${file.absolutePath} (${e.message}); secrets will not persist")
        null
    }

    override fun clear() {
        file.delete()
    }

    private fun readExisting(): ByteArray? {
        if (!file.exists()) return null
        val decoded = Base64.getDecoder().decode(file.readText().trim())
        return decoded.takeIf { it.size == WRAP_KEY_LEN }
    }

    private fun createFresh(): ByteArray {
        val fresh = ByteArray(WRAP_KEY_LEN).also { SecureRandom().nextBytes(it) }
        file.parentFile?.mkdirs()
        file.writeText(Base64.getEncoder().encodeToString(fresh))
        restrictToOwner(file)
        return fresh
    }

    /** Best-effort `chmod 600` — non-POSIX filesystems (dev runs) are tolerated. */
    private fun restrictToOwner(file: File) {
        try {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
    }

    private companion object {
        const val WRAP_KEY_LEN = 32
    }
}
