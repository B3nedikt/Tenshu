@file:OptIn(ExperimentalForeignApi::class)

package app.tenshu.outbox

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * `NSFileManager`-backed [OutboxStore] — the iOS twin of the java.io [FileOutboxStore]: one file
 * per entry under [dirPath] (an app-container subdirectory), so a workout journalled before its
 * save survives process death and a Control SIGKILL (fit-app.md §11.3). Entry ids are sanitised
 * into filenames deterministically, so [remove] finds what [append] wrote; ordering is by
 * filename, which a timestamp-prefixed id makes capture-ordered.
 *
 * Writes are atomic (`writeToFile:atomically:` = write-temp-then-rename), so a crash mid-write
 * never leaves a half-written entry. The store holds route + HR plaintext (GDPR Art. 9,
 * threat-model A34) — app-private container storage only, the visit-outbox posture (A35).
 */
class NsFileOutboxStore(private val dirPath: String) : OutboxStore {

    private val fm = NSFileManager.defaultManager

    override fun append(id: String, payload: String) {
        fm.createDirectoryAtPath(dirPath, withIntermediateDirectories = true, attributes = null, error = null)
        (payload as NSString).writeToFile(
            path = "$dirPath/${fileNameOf(id)}",
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
    }

    override fun entries(): List<OutboxEntry> {
        val names = fm.contentsOfDirectoryAtPath(dirPath, null) ?: return emptyList()
        return names.filterIsInstance<String>()
            .filter { it.endsWith(SUFFIX) }
            .sorted()
            .mapNotNull { name ->
                val payload = NSString.stringWithContentsOfFile("$dirPath/$name", NSUTF8StringEncoding, null) as String?
                payload?.let { OutboxEntry(id = name.removeSuffix(SUFFIX), payload = it) }
            }
    }

    override fun remove(id: String) {
        fm.removeItemAtPath("$dirPath/${fileNameOf(id)}", null)
    }

    private fun fileNameOf(id: String) =
        id.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("") + SUFFIX

    private companion object {
        const val SUFFIX = ".json"
    }
}
