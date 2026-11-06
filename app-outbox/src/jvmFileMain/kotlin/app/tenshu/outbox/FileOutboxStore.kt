package app.tenshu.outbox

import java.io.File

/**
 * File-backed [OutboxStore]: one file per entry under [dir] — app-private storage on both
 * platforms that bind it (Android: `filesDir/<name>/`, encrypted-at-rest per the platform
 * posture; desktop: an app data directory). Entry ids are sanitised into filenames
 * deterministically, so [remove] finds what [append] wrote; ordering is by filename, which a
 * timestamp-prefixed id makes capture-ordered.
 */
class FileOutboxStore(private val dir: File) : OutboxStore {

    override fun append(id: String, payload: String) {
        dir.mkdirs()
        // Write-then-rename so a crash mid-write leaves a .tmp the reader ignores, never a
        // half-written entry (the outbox purges corrupt payloads, but don't rely on it).
        val tmp = File(dir, fileNameOf(id) + ".tmp")
        tmp.writeText(payload)
        tmp.renameTo(File(dir, fileNameOf(id)))
    }

    override fun entries(): List<OutboxEntry> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(SUFFIX) }
            .sortedBy { it.name }
            .mapNotNull { f ->
                try {
                    OutboxEntry(id = f.name.removeSuffix(SUFFIX), payload = f.readText())
                } catch (_: Exception) {
                    null
                }
            }

    override fun remove(id: String) {
        File(dir, fileNameOf(id)).delete()
    }

    private fun fileNameOf(id: String) = id.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("") + SUFFIX

    private companion object {
        const val SUFFIX = ".json"
    }
}
