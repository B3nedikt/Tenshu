package org.tenshu.inference.harness.ios

import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.util.Sha256Digest

/**
 * The corpus a run measures, named file by file (`ios-on-device-inference.md` §9.2).
 *
 * ⚠⚠ **The manifest is the corpus; the directory is not.** `devicectl` cannot delete from an
 * app's container, so `Documents/photos/` accumulates every push: the real 562 photos pushed over
 * a 300-photo stand-in would be measured as their union and still reported as a measurement.
 * `run-in3m.sh push-photos` writes this manifest last, and a run embeds exactly the files it
 * names — after checking each one's size and sha256 — or refuses.
 *
 * The format is `corpus_manifest` in `run-in3m.sh`, whose self-test and `CorpusManifestTest` read
 * the same golden corpus.
 */
internal object CorpusManifest {

    const val FILE: String = "in3m-corpus.txt"
    const val MAGIC: String = "in3m-corpus 1"

    class Entry(val name: String, val sha256: String, val bytes: Long)

    /** A corpus every named file of which is on the phone, byte for byte. */
    class Verified(
        /** sha256 over the `name<TAB>sha256` lines: two reports with one id are over one corpus. */
        val id: String,
        val names: List<String>,
        /** Files in the photo directory the manifest does not name. Never measured. */
        val unnamed: List<String>,
    )

    /**
     * @param present the files in the photo directory.
     * @return the corpus, or why this run must not be called a measurement.
     */
    fun verify(files: FileDatabase, photosDirectory: String, present: List<String>): Result<Verified> {
        val text: String = files.readBytes(FILE)?.get()?.decodeToString()
            ?: return refused("no corpus manifest — expected Documents/$FILE, which `run-in3m.sh push-photos` writes last")
        val lines: List<String> = text.split('\n')
        if (lines.firstOrNull() != MAGIC) return refused("Documents/$FILE does not open with '$MAGIC'")
        val headerEnd: Int = lines.indexOf("").takeIf { it > 0 } ?: return refused("Documents/$FILE has no entries")
        val header: Map<String, String> = lines.subList(1, headerEnd).filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val entries: List<Entry> = lines.subList(headerEnd + 1, lines.size).filter { it.isNotEmpty() }.map { line ->
            val fields: List<String> = line.split('\t', limit = 3)
            val bytes: Long? = fields.getOrNull(1)?.toLongOrNull()
            if (fields.size != 3 || bytes == null) return refused("Documents/$FILE has an unreadable line: '$line'")
            Entry(name = fields[2], sha256 = fields[0], bytes = bytes)
        }

        // The manifest must describe itself before it may describe the phone.
        val id: String = sha256Hex(entries.joinToString("") { "${it.name}\t${it.sha256}\n" }.encodeToByteArray())
        if (header["id"] != id) return refused("Documents/$FILE declares corpus ${header["id"]}, its entries are corpus $id")
        if (header["count"]?.toIntOrNull() != entries.size) {
            return refused("Documents/$FILE declares ${header["count"]} photos and lists ${entries.size}")
        }

        entries.forEach { entry ->
            val path = "$photosDirectory/${entry.name}"
            val bytes: Long = files.fileSize(path)
                ?: return refused("the corpus names ${entry.name}, which is not in Documents/$photosDirectory/")
            if (bytes != entry.bytes) {
                return refused("${entry.name} is $bytes bytes, the corpus manifest says ${entry.bytes}")
            }
            val sha256: String = sha256Of(files, path)
            if (sha256 != entry.sha256) {
                return refused("${entry.name} has sha256 ${sha256.take(12)}…, the corpus manifest says ${entry.sha256.take(12)}…")
            }
        }
        val named: Set<String> = entries.map { it.name }.toSet()
        return Result.success(Verified(id, entries.map { it.name }, present.filter { it !in named }))
    }

    /** The sha256 of a stored file, read in windows. */
    fun sha256Of(files: FileDatabase, path: String): String {
        val digest = Sha256Digest()
        var offset = 0L
        while (true) {
            val chunk: ByteArray = files.readBytesRange(path, offset, HASH_CHUNK) ?: break
            if (chunk.isEmpty()) break
            digest.update(chunk)
            offset += chunk.size
        }
        return hex(digest.digest())
    }

    private fun sha256Hex(bytes: ByteArray): String = hex(Sha256Digest().also { it.update(bytes) }.digest())

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun refused(reason: String): Result<Verified> = Result.failure(CorpusRefusedException(reason))

    private const val HASH_CHUNK: Int = 1 shl 20
}

/** The corpus on the phone is not the corpus its manifest names. The message is the report line. */
internal class CorpusRefusedException(message: String) : Exception(message)
