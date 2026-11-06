package org.tenshu.inference.harness.ios

import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.inference.proxy.harness.HarnessMath

/**
 * The per-image vectors of one run, kept on the phone (§4.1).
 *
 * They answer two questions no single run can: whether a compute-units setting moves the vector
 * space (this run against the `coreml-cpu` run), and whether an iOS update does (this run against
 * the same row's file from before the update, which sets §3.2's probe threshold).
 *
 * ⚠⚠ **Both questions hold the artifact, the preprocessing and the corpus fixed**, so every file
 * carries a [Fingerprint] and [compareWithKept] compares only files that share the archive digest,
 * the preprocessing revision, the corpus and the tower. Two export runs of one checkpoint give two archive digests
 * (`ios-on-device-inference.md` §9), and a cosine across them would be reported as compute-unit or
 * OS drift while measuring a re-export.
 *
 * Text, so the file can be read off the phone and compared anywhere without this code: [MAGIC],
 * the fingerprint as `key=value` lines, a blank line, then one line a photo — the file name, a
 * tab, the normalised vector's floats as raw bits in hex.
 */
internal object VectorFile {

    /** The first line of a file this version reads. Format 1 had no header; format 2, no corpus. */
    const val MAGIC: String = "in3m-vectors 3"

    /** What a file's vectors were made with. [row] and [osBuild] are what a comparison varies. */
    data class Fingerprint(
        val tower: String,
        val row: String,
        val osBuild: String,
        /** The sha256 of the tower's archive, as the model cache was given it. */
        val archiveSha256: String,
        val preprocessingRevision: Int,
        /** [CorpusManifest.Verified.id]: one photo name in two corpora is not one photo. */
        val corpusId: String,
    )

    data class Stored(val fingerprint: Fingerprint, val vectors: Map<String, FloatArray>)

    data class Agreement(val shared: Int, val mean: Float, val min: Float)

    /** ⚠ The digest, the revision and the corpus are in the name too, so neither a re-export nor another corpus overwrites a kept run. */
    fun nameFor(fingerprint: Fingerprint): String =
        "$PREFIX${fingerprint.row}-${fingerprint.osBuild.filter { it.isLetterOrDigit() || it == '.' }}" +
            "-${fingerprint.archiveSha256.take(12)}-p${fingerprint.preprocessingRevision}-c${fingerprint.corpusId.take(8)}.txt"

    fun write(files: FileDatabase, path: String, fingerprint: Fingerprint, vectors: Map<String, FloatArray>) {
        val text = StringBuilder()
        text.append(MAGIC).append('\n')
        text.append("$KEY_TOWER=${fingerprint.tower}\n")
        text.append("$KEY_ROW=${fingerprint.row}\n")
        text.append("$KEY_OS_BUILD=${fingerprint.osBuild}\n")
        text.append("$KEY_ARCHIVE=${fingerprint.archiveSha256}\n")
        text.append("$KEY_PREPROCESSING=${fingerprint.preprocessingRevision}\n")
        text.append("$KEY_CORPUS=${fingerprint.corpusId}\n")
        text.append('\n')
        vectors.forEach { (name, vector) ->
            text.append(name).append('\t')
            vector.forEach { text.append(it.toRawBits().toUInt().toString(16).padStart(8, '0')) }
            text.append('\n')
        }
        files.writeRawByteArray(path, text.toString().encodeToByteArray())
    }

    /**
     * `null` for a file that is absent, or that does not open with [MAGIC] and a whole fingerprint.
     * ⚠ That includes every file written before the header existed: what it was made with is not
     * known, so it is never compared.
     */
    fun read(files: FileDatabase, path: String): Stored? {
        val text: String = files.readBytes(path)?.get()?.decodeToString() ?: return null
        val lines: List<String> = text.split('\n')
        if (lines.firstOrNull() != MAGIC) return null
        val headerEnd: Int = lines.indexOf("").takeIf { it > 0 } ?: return null
        val header: Map<String, String> = lines.subList(1, headerEnd)
            .filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val fingerprint = Fingerprint(
            tower = header[KEY_TOWER] ?: return null,
            row = header[KEY_ROW] ?: return null,
            osBuild = header[KEY_OS_BUILD] ?: return null,
            archiveSha256 = header[KEY_ARCHIVE] ?: return null,
            preprocessingRevision = header[KEY_PREPROCESSING]?.toIntOrNull() ?: return null,
            corpusId = header[KEY_CORPUS] ?: return null,
        )
        val vectors = LinkedHashMap<String, FloatArray>()
        lines.subList(headerEnd + 1, lines.size).filter { '\t' in it }.forEach { line ->
            val hex: String = line.substringAfter('\t')
            vectors[line.substringBefore('\t')] =
                FloatArray(hex.length / 8) { Float.fromBits(hex.substring(it * 8, it * 8 + 8).toUInt(16).toInt()) }
        }
        return Stored(fingerprint, vectors)
    }

    /**
     * Why [theirs] cannot be compared with [mine], or `null` when it can.
     *
     * ⚠ The row and the OS build are deliberately not here: a different one is the comparison.
     */
    fun incomparable(mine: Fingerprint, theirs: Fingerprint): String? = when {
        theirs.tower != mine.tower -> "the ${theirs.tower} tower, not the ${mine.tower} tower"
        theirs.archiveSha256 != mine.archiveSha256 ->
            "another archive (sha256 ${theirs.archiveSha256.take(12)}…, this run ${mine.archiveSha256.take(12)}…)"
        theirs.preprocessingRevision != mine.preprocessingRevision ->
            "preprocessing revision ${theirs.preprocessingRevision}, this run ${mine.preprocessingRevision}"
        theirs.corpusId != mine.corpusId ->
            "another corpus (${theirs.corpusId.take(12)}…, this run ${mine.corpusId.take(12)}…)"
        else -> null
    }

    /**
     * One report line for every kept vector file in [names] other than [mine]: its agreement with
     * this run, or that it was skipped and why. ⚠ No file is dropped without a line.
     */
    fun compareWithKept(
        files: FileDatabase,
        names: List<String>,
        mine: String,
        fingerprint: Fingerprint,
        vectors: Map<String, FloatArray>,
    ): List<String> = names.filter { it.startsWith(PREFIX) && it != mine }.map { other ->
        val kept: Stored = read(files, other)
            ?: return@map "skipped $other: no fingerprint header (not a '$MAGIC' file), so what it was made with is unknown"
        incomparable(fingerprint, kept.fingerprint)?.let { reason -> return@map "skipped $other: $reason" }
        val agreement: Agreement = agreement(vectors, kept.vectors)
            ?: return@map "cosine vs $other: no photo in common"
        "cosine vs $other (${kept.fingerprint.row}, ${kept.fingerprint.osBuild}): shared ${agreement.shared}" +
            "  mean ${In3mHarness.fixed(agreement.mean.toDouble(), 6)}  min ${In3mHarness.fixed(agreement.min.toDouble(), 6)}"
    }

    /** Mean and minimum cosine over the photos both runs embedded, or `null` when they share none. */
    fun agreement(mine: Map<String, FloatArray>, theirs: Map<String, FloatArray>): Agreement? {
        val cosines: List<Float> = mine.mapNotNull { (name, vector) ->
            theirs[name]?.takeIf { it.size == vector.size }?.let { HarnessMath.cosine(vector, it) }
        }
        if (cosines.isEmpty()) return null
        return Agreement(cosines.size, cosines.sum() / cosines.size, cosines.min())
    }

    private const val PREFIX: String = "in3m-vectors-"
    private const val KEY_TOWER: String = "tower"
    private const val KEY_ROW: String = "row"
    private const val KEY_OS_BUILD: String = "osBuild"
    private const val KEY_ARCHIVE: String = "archiveSha256"
    private const val KEY_PREPROCESSING: String = "preprocessingRevision"
    private const val KEY_CORPUS: String = "corpusId"
}
