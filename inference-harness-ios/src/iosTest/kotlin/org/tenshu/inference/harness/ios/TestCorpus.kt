package org.tenshu.inference.harness.ios

import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.util.Sha256Digest

internal fun sha256HexOf(bytes: ByteArray): String =
    Sha256Digest().also { it.update(bytes) }.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/**
 * Writes the manifest `run-in3m.sh push-photos` would write for [names], from the bytes now under
 * `photos/`, and returns the corpus id.
 */
internal fun writeCorpusManifest(files: FileDatabase, vararg names: String): String {
    val sums: List<Pair<String, ByteArray>> = names.map { it to files.readBytes("photos/$it")!!.get() }
    val id: String = sha256HexOf(sums.joinToString("") { (name, bytes) -> "$name\t${sha256HexOf(bytes)}\n" }.encodeToByteArray())
    val text = StringBuilder("in3m-corpus 1\nid=$id\ncount=${names.size}\n\n")
    sums.forEach { (name, bytes) -> text.append("${sha256HexOf(bytes)}\t${bytes.size}\t$name\n") }
    files.writeRawByteArray(CorpusManifest.FILE, text.toString().encodeToByteArray())
    return id
}
