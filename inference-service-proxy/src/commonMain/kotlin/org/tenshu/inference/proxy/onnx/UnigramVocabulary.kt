package org.tenshu.inference.proxy.onnx

/**
 * The 250 100-piece SentencePiece vocabulary a device reads (`models.md` §3.9.2, R3).
 *
 * ⚠⚠ **Packed, not `tokenizer.json`.** The reference file is 16.4 MB of JSON; this is 3.1 MB of
 * `float32 score | uint8 length | utf-8 bytes` after a small header. The parse has to be fast on a
 * phone, and the file sits beside a 100 MB model where a megabyte of JSON parsing is the one cost
 * nobody budgeted for.
 *
 * ⚠ It ships **with the weights**, never in the app: a tokenizer without a text tower can do
 * nothing, and `inference-service.md` §19.8's 18.5 MB is already paid by every install including
 * the ones that never switch inference on.
 */
internal class UnigramVocabulary private constructor(
    private val scores: FloatArray,
    private val ids: HashMap<String, Int>,
    val unkId: Int,
    val byteFallback: Boolean,
    /** ⚠ The Viterbi's inner loop is bounded by this rather than by the input's length. */
    val longestPiece: Int,
) {

    fun idOf(piece: String): Int? = ids[piece]

    fun scoreOf(piece: String): Float? = ids[piece]?.let { scores[it] }

    val size: Int get() = scores.size

    companion object {
        private const val MAGIC = "TSHU"
        private const val VERSION = 1

        /**
         * ⚠ Returns `null` rather than throwing on anything malformed — the file is side-loaded
         * beside the model, and a half-copied vocabulary must degrade to "this device cannot embed
         * text" (which `photosLike` already renders honestly) rather than crash an index pass.
         *
         * ⚠ Bytes, not a file: 3.1 MB, and each platform reads them its own way (`java.io` on the
         * JVM and Android, `FileDatabase` on iOS).
         */
        fun read(bytes: ByteArray): UnigramVocabulary? = runCatching {
            val input = Cursor(bytes)
            val magic = input.text(4)
            require(magic == MAGIC) { "not a Tenshu vocabulary: $magic" }
            require(input.intLe() == VERSION) { "unsupported vocabulary version" }
            val count = input.intLe()
            // ⚠ Before the allocations below: a corrupt count would otherwise size a map from noise.
            require(count >= 0 && count <= bytes.size / MIN_ENTRY_BYTES) { "implausible piece count $count" }
            val byteFallback = input.unsignedByte() != 0
            val unkId = input.intLe()

            val scores = FloatArray(count)
            // ⚠ Sized up front: 250 100 entries rehashed from a default-capacity map is
            // several full rehashes of the largest map this process will ever build.
            val ids = HashMap<String, Int>(count * 2)
            var longest = 0
            for (index in 0 until count) {
                scores[index] = Float.fromBits(input.intLe())
                val piece = input.text(input.unsignedByte())
                ids[piece] = index
                if (piece.length > longest) longest = piece.length
            }
            UnigramVocabulary(scores, ids, unkId, byteFallback, longest)
        }.getOrNull()

        /** ⚠ Every read is bounds-checked by the array itself: a truncated file throws, and [read] answers `null`. */
        private class Cursor(private val bytes: ByteArray) {
            private var position = 0

            fun unsignedByte(): Int = bytes[position++].toInt() and 0xFF

            fun intLe(): Int =
                unsignedByte() or (unsignedByte() shl 8) or (unsignedByte() shl 16) or (unsignedByte() shl 24)

            /**
             * ⚠ `throwOnInvalidSequence = false` is `String(bytes, UTF_8)`'s behaviour, which this
             * replaces: a malformed sequence becomes U+FFFD on every platform.
             */
            fun text(length: Int): String {
                val end = position + length
                require(end <= bytes.size) { "truncated vocabulary" }
                return bytes.decodeToString(position, end, throwOnInvalidSequence = false).also { position = end }
            }
        }

        /** A score and a length byte: the least one entry can occupy. */
        private const val MIN_ENTRY_BYTES = 5

        /** ⚠ For tests: a vocabulary with no file behind it, so the Viterbi is provable in CI. */
        fun of(pieces: List<Pair<String, Float>>, unkId: Int = 0, byteFallback: Boolean = true): UnigramVocabulary {
            val scores = FloatArray(pieces.size) { pieces[it].second }
            val ids = HashMap<String, Int>(pieces.size * 2)
            pieces.forEachIndexed { index, (piece, _) -> ids[piece] = index }
            return UnigramVocabulary(scores, ids, unkId, byteFallback, pieces.maxOf { it.first.length })
        }
    }
}
