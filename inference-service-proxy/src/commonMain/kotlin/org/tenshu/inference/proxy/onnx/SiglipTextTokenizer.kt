package org.tenshu.inference.proxy.onnx

/**
 * `SiglipTokenizer` in Kotlin — SentencePiece **Unigram**, byte fallback, Metaspace (R3).
 *
 * ⚠⚠ **Measured against the reference before it was written** (`models.md` §3.9): the
 * reference normalizer ends in a `Precompiled` charsmap, a 317 KB compiled SentencePiece blob with
 * no cheap equivalent on any platform we ship. Rather than guess whether that mattered, the
 * disagreement was measured — **5 000/5 000 lines of real prose and 70/70 photo queries
 * identical**, and 7 of 63 859 codepoints differing, all of them NFKC folding a rare character
 * *more* than the charsmap does. So this is NFKC plus [DELETED] and [TO_SPACE], and the blob is
 * not needed.
 *
 * ⚠⚠ A tokenizer that disagrees with the reference is the quietest possible defect: it
 * produces a **plausible** query vector that retrieves the wrong photographs, with nothing anywhere
 * to compare it against. That is `models.md` §3.7.1's failure class — right-looking numbers from
 * wrong inputs — which is why the tests pin token *ids* against reference output rather than
 * asserting that tokenization "looks reasonable".
 *
 * ⚠ Nothing here is ONNX-specific: it feeds the Core ML text tower on iOS too. The one thing each
 * platform supplies is [nfkc]. Everything else is spelled so that no platform's library decides
 * it — see [WHITESPACE].
 */
internal class SiglipTextTokenizer(
    private val vocabulary: UnigramVocabulary,
    /** ⚠ SigLIP's text tower is trained at a fixed length and padded to it — never inferred. */
    private val sequenceLength: Int = 64,
) {

    private val eosId = requireNotNull(vocabulary.idOf(EOS)) { "vocabulary has no $EOS" }

    /**
     * ⚠⚠ The checkpoint pads with `</s>`, **not** with the vocabulary's `<pad>` piece: its
     * `tokenizer_config.json` declares `pad_token` = `</s>`. The text tower takes no attention
     * mask, so the padding is input the model reads, and any other id is a different query
     * (`models.md` §3.9.4). A vocabulary without `</s>` has already failed above — there is no
     * fallback id, because a guessed one yields exactly that plausible, wrong vector.
     */
    private val padId = eosId

    /** @return exactly [sequenceLength] ids, padded — the shape the exported graph accepts. */
    fun encode(text: String): IntArray {
        val normalised = normalise(text)
        val ids = if (normalised.isEmpty()) {
            IntArray(0)
        } else {
            viterbi(METASPACE + normalised.replace(' ', METASPACE))
        }
        val out = IntArray(sequenceLength) { padId }
        // ⚠ Truncated to leave room for the EOS: a query longer than the window keeps its head
        // and still terminates, which is what the model was trained to see.
        val kept = minOf(ids.size, sequenceLength - 1)
        for (i in 0 until kept) out[i] = ids[i]
        out[kept] = eosId
        return out
    }

    /**
     * Well-formed -> lowercase -> the measured fix-ups -> punctuation -> whitespace -> NFKC -> collapse.
     *
     * ⚠ The two `Replace` steps are the regexes out of `tokenizer.json`, not an interpretation
     * of them, and the order is the reference's order: normalising *before* stripping punctuation
     * would fold characters into punctuation that then gets stripped.
     */
    internal fun normalise(text: String): String {
        val mapped = StringBuilder(text.length)
        wellFormed(text).lowercase().forEach { character ->
            when (character.code) {
                in DELETED -> Unit
                in TO_SPACE -> mapped.append(' ')
                else -> mapped.append(character)
            }
        }
        var result: String = mapped.filterNot { it in PUNCTUATION }.toString()
        result = WHITESPACE.replace(result, " ").trim()
        result = nfkc(result)
        return REPEATED_SPACES.replace(result, " ")
    }

    /**
     * [text] with every **lone** surrogate replaced by U+FFFD, which [TO_SPACE] then makes a space.
     *
     * ⚠⚠ Decided here, before any platform library reads the string, because each reads a lone
     * surrogate its own way: encoded to UTF-8 it is `?` on the JVM and U+FFFD's three bytes on
     * Kotlin/Native and in a browser, and Swift's string bridge has already made it U+FFFD before
     * the query reaches Kotlin. The reference has no answer to copy: half a character is not UTF-8,
     * and SentencePiece refuses the string (`models.md` §3.9.5). It does answer for U+FFFD, the
     * character every standard decoder puts in its place, and that answer is a space.
     */
    internal fun wellFormed(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val unit = text[index]
            when {
                isSurrogatePairAt(text, index) -> {
                    out.append(unit).append(text[index + 1])
                    index++
                }
                unit.isSurrogate() -> out.append(REPLACEMENT)
                else -> out.append(unit)
            }
            index++
        }
        return out.toString()
    }

    /**
     * The Unigram decode: the highest-scoring segmentation of [text], by dynamic programming.
     *
     * ⚠⚠ Not greedy-longest-match, which is the tempting shortcut and a *different tokenizer*. The scores are log-probabilities and the best segmentation is frequently not the
     * one with the longest first piece - only the arithmetic knows which.
     */
    private fun viterbi(text: String): IntArray {
        val n = text.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        best[0] = 0.0

        for (i in 0 until n) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue
            val limit = minOf(n, i + vocabulary.longestPiece)
            for (j in i + 1..limit) {
                val score = vocabulary.scoreOf(text.substring(i, j)) ?: continue
                val candidate = best[i] + score
                if (candidate > best[j]) {
                    best[j] = candidate
                    from[j] = i
                }
            }
            // ⚠⚠ Byte fallback keeps the lattice connected: without it a single unknown
            // character makes the whole string untokenizable, and the query silently becomes
            // nothing.
            // ⚠⚠ Its span is one **code point**, so two UTF-16 units for a character above
            // U+FFFF: the reference falls back to that character's four UTF-8 bytes, and a
            // surrogate has no bytes of its own (`models.md` §3.9.5). No piece ends between the
            // two halves, so the low half is never a start.
            val next = i + if (isSurrogatePairAt(text, i)) 2 else 1
            if (from[next] == -1 && vocabulary.byteFallback) {
                val fallback = best[i] + byteScore(text.substring(i, next))
                if (fallback > best[next]) {
                    best[next] = fallback
                    from[next] = i
                }
            }
        }

        val pieces = ArrayList<String>()
        var index = n
        while (index > 0) {
            val previous = from[index]
            if (previous < 0) return IntArray(0)
            pieces += text.substring(previous, index)
            index = previous
        }
        pieces.reverse()

        val ids = ArrayList<Int>(pieces.size)
        pieces.forEach { piece ->
            val id = vocabulary.idOf(piece)
            if (id != null) {
                ids += id
            } else {
                piece.encodeToByteArray().forEach { byte ->
                    ids += vocabulary.idOf(byteToken(byte)) ?: vocabulary.unkId
                }
            }
        }
        return ids.toIntArray()
    }

    /**
     * ⚠ [character] is one whole code point of normalised text, so it is well-formed and its
     * UTF-8 is the same bytes on every platform ([wellFormed]).
     */
    private fun byteScore(character: String): Double =
        character.encodeToByteArray().sumOf { byte ->
            (vocabulary.scoreOf(byteToken(byte)) ?: UNKNOWN_BYTE_PENALTY).toDouble()
        }

    private fun isSurrogatePairAt(text: String, index: Int): Boolean =
        text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()

    private fun byteToken(byte: Byte): String =
        "<0x" + (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') + ">"

    internal companion object {
        const val METASPACE = '\u2581'
        private const val EOS = "</s>"
        private const val UNKNOWN_BYTE_PENALTY = -1e9f

        private const val REPLACEMENT = '\uFFFD'

        /**
         * The two `Replace` normalizers of `tokenizer.json`, as the JVM reads them.
         *
         * ⚠⚠ **Spelled as characters, never as the reference's regex source.** The source is
         * `[!"\#\$%\&'…]` and `\s+`, and each regex engine reads those its own way: `\s` is six
         * ASCII characters on the JVM and every Unicode space in a browser, and a browser's
         * Unicode mode refuses `\#` outright. The parity measured in `models.md` §3.9 is the
         * JVM's reading, so that reading is what every platform gets.
         */
        internal const val PUNCTUATION = "!\"#\$%&'()*+,-.:;=?@[\\]^_`{|}~"
        internal val WHITESPACE = Regex("[ \t\n\u000B\u000C\r]+")
        private val REPEATED_SPACES = Regex(" {2,}")

        /**
         * ⚠⚠ **Enumerated from a measurement, not derived from a rule** (`models.md`
         * §3.9.1). Deleting the whole `Cf`/`Cc` categories was tried and took the gap from 17
         * codepoints to 72 — the charsmap *keeps* most of them (soft hyphen, U+0080) and touches
         * exactly these.
         */
        internal val DELETED = setOf(0x7F, 0x8F, 0x9F)

        /**
         * ⚠⚠ Deleted and replaced-by-a-space are **different**, and a probe that places the
         * character between spaces cannot tell them apart — which is how the first measurement got
         * this wrong. These directions came from 5 000 lines of independent prose.
         * ⚠ U+2581 is the Metaspace replacement itself: left in, it injects a word boundary the
         * model never saw in training.
         */
        internal val TO_SPACE = setOf(0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0xFEFF, 0x2581, 0xFFFD)
    }
}
