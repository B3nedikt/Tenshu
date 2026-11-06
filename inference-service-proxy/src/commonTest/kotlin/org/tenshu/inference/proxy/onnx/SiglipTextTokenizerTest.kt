package org.tenshu.inference.proxy.onnx

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [SiglipTextTokenizer] (R3). Two tiers, because the real vocabulary is a 3.1 MB side-loaded
 * artifact and the repo is public:
 *
 * - the **algorithm** is proved against a hand-built vocabulary and runs everywhere, including CI;
 * - the **fidelity** is proved against ids captured from the reference implementation, and runs
 *   only when the packed vocabulary is handed over:
 *   `-Dtenshu.inference.tokenizer=/path/to/tokenizer.bin`. The same flag reaches the iOS
 *   simulator run ([realVocabularyBytes]).
 *
 * ⚠ In `commonTest`, so the JVM and the iOS simulator run the same cases: the two platforms share
 * the algorithm and differ in [nfkc], `lowercase` and UTF-8 encoding, each of which a golden row
 * crosses. ⚠ Test names are identifiers here: a browser target refuses most punctuation in one.
 *
 * ⚠⚠ The second tier is the one that matters and the one that cannot run in CI, which is exactly
 * why the first exists. A tokenizer that disagrees with the reference returns a plausible vector
 * for the wrong query, and no assertion about "reasonable output" would ever catch it.
 */
class SiglipTextTokenizerTest {

    // ⚠ Scores are log-probabilities: less negative is better. The point of this fixture is that
    // "sunset" can be split two ways and the WRONG tokenizer (greedy longest match) picks the
    // single long piece, while Unigram picks the better-scoring pair.
    private val vocabulary = UnigramVocabulary.of(
        listOf(
            "<pad>" to 0f, "</s>" to 0f, "<unk>" to 0f,
            // ⚠ The bare metaspace piece exists in the real vocabulary and the fixture needs
            // it too: without it every string beginning with a word boundary byte-falls-back,
            // which is a property of THIS fixture rather than of the tokenizer.
            "\u2581" to -3.0f,
            "\u2581sun" to -1.0f, "set" to -1.0f, "\u2581sunset" to -5.0f,
            "\u2581a" to -1.0f, "\u2581dog" to -1.0f, "\u2581in" to -1.0f,
            "<0xC3>" to -20f, "<0xA9>" to -20f,
        ),
    )

    private fun tokenizer(length: Int = 64) = SiglipTextTokenizer(vocabulary, length)

    private val eos = vocabulary.idOf("</s>")!!

    /**
     * The content and its one terminating `</s>`, with the padding taken off.
     *
     * ⚠ The padding IS `</s>`, so the run is collapsed rather than stripped — and a row padded
     * with anything else keeps its tail and fails whichever assertion called this.
     */
    private fun ids(text: String, length: Int = 64): List<Int> =
        tokenizer(length).encode(text).toList().dropLastWhile { it == eos } + eos

    @Test
    fun itPicksTheBestScoringSegmentationNotTheLongestPiece() {
        // greedy-longest-match would answer [sunset] at -5.0; Unigram answers [sun][set] at -2.0.
        assertContentEquals(
            listOf(vocabulary.idOf("\u2581sun")!!, vocabulary.idOf("set")!!, vocabulary.idOf("</s>")!!),
            ids("sunset"),
        )
    }

    @Test
    fun anUnknownCharacterFallsBackToItsUtf8Bytes() {
        // "é" is in no piece; byte fallback keeps the lattice connected instead of dropping it.
        val out = ids("é")
        assertContentEquals(
            listOf(
                vocabulary.idOf("\u2581")!!,
                vocabulary.idOf("<0xC3>")!!,
                vocabulary.idOf("<0xA9>")!!,
                vocabulary.idOf("</s>")!!,
            ),
            out,
        )
    }

    @Test
    fun everyResultIsExactlyTheSequenceLengthPadded() {
        assertEquals(64, tokenizer().encode("a dog in").size)
        assertEquals(8, tokenizer(length = 8).encode("a dog in").size)
    }

    /**
     * ⚠⚠ The fixture has a `<pad>` piece at id 0, exactly as the real vocabulary does, and the
     * tokenizer must not use it: the checkpoint's `pad_token` is `</s>` (`models.md` §3.9.4).
     */
    @Test
    fun thePaddingIsTheEosIdNotTheVocabularysPadPiece() {
        val a = vocabulary.idOf("\u2581a")!!
        val dog = vocabulary.idOf("\u2581dog")!!
        assertContentEquals(listOf(a, dog, eos, eos, eos, eos), tokenizer(length = 6).encode("a dog").toList())
        assertContentEquals(List(4) { eos }, tokenizer(length = 4).encode("").toList())
    }

    /** ⚠ No fallback id: a guessed padding is a plausible vector for the wrong query. */
    @Test
    fun aVocabularyWithoutEosIsRefusedRatherThanPaddedWithAGuess() {
        val headless = UnigramVocabulary.of(listOf("<pad>" to 0f, "<unk>" to 0f, "\u2581a" to -1f))
        assertFailsWith<IllegalArgumentException> { SiglipTextTokenizer(headless) }
    }

    /** ⚠ A query longer than the window keeps its head and still terminates. */
    @Test
    fun anOverLongQueryIsTruncatedButStillEndsInEos() {
        val out = tokenizer(length = 4).encode("a dog in a dog in a dog in")
        assertEquals(4, out.size)
        assertEquals(vocabulary.idOf("</s>"), out.last())
    }

    /** ⚠ The reference emits `</s>` alone; Metaspace does not prepend to an empty string. */
    @Test
    fun textThatNormalisesAwayToNothingIsJustEos() {
        listOf("", "   ", "|---|---|", "!!!").forEach { blank ->
            assertContentEquals(listOf(vocabulary.idOf("</s>")!!), ids(blank), "for ${blank.length} chars")
        }
    }

    @Test
    fun theNormaliserMatchesTheReferenceChain() {
        val subject = tokenizer()
        assertEquals("a dog", subject.normalise("A  Dog"))
        assertEquals("a dog", subject.normalise("  a, dog!  "))
        assertEquals("its fine", subject.normalise("it's fine"))
        // ⚠⚠ The measured fix-ups (`models.md` §3.9.1). Deleted and replaced-by-a-space are
        // different behaviours and the difference is load-bearing.
        assertEquals("no zero width", subject.normalise("no\u200bzero\u200bwidth"))
        assertEquals("a b", subject.normalise("a\u2581b"))
        assertEquals("xy", subject.normalise("x\u007fy"))
        assertEquals("file", subject.normalise("\ufb01le"))
    }

    // ⚠ U+1FAE0 and U+1FAE8 are in no piece here, as in the real vocabulary; U+1F305 is a piece
    // in both. `<0x3F>` and U+FFFD's three bytes are present so that a regression to either
    // platform's own reading of half a surrogate pair is its own ids, not `<unk>`.
    private val astral = UnigramVocabulary.of(
        listOf(
            "<pad>" to 0f, "</s>" to 0f, "<unk>" to 0f,
            "\u2581" to -3.0f, "\u2581a" to -1.0f, "b" to -1.0f, SUNRISE to -2.0f,
            "<0xF0>" to -20f, "<0x9F>" to -20f, "<0xAB>" to -20f, "<0xA0>" to -20f, "<0xA8>" to -20f,
            "<0x3F>" to -20f, "<0xEF>" to -20f, "<0xBF>" to -20f, "<0xBD>" to -20f,
        ),
    )

    private fun astralIds(text: String, length: Int = 64): List<Int> =
        SiglipTextTokenizer(astral, length).encode(text).toList().dropLastWhile { it == astral.idOf("</s>")!! }

    private fun pieces(vararg names: String): List<Int> = names.map { astral.idOf(it)!! }

    /**
     * ⚠⚠ The reference's answer (`models.md` §3.9.5), and four ids where the fallback used to
     * emit two `?`: it walked one UTF-16 unit at a time and met the character as two halves.
     */
    @Test
    fun anAstralCharacterOutsideTheVocabularyIsItsFourUtf8Bytes() {
        assertContentEquals(pieces("\u2581", "<0xF0>", "<0x9F>", "<0xAB>", "<0xA0>"), astralIds(MELTING))
    }

    @Test
    fun adjacentAstralCharactersAreEachTheirOwnFourBytes() {
        assertContentEquals(
            pieces("\u2581", "<0xF0>", "<0x9F>", "<0xAB>", "<0xA0>", "<0xF0>", "<0x9F>", "<0xAB>", "<0xA8>"),
            astralIds(MELTING + SHAKING),
        )
    }

    /** ⚠ The lattice stays connected on both sides of a two-unit fallback span. */
    @Test
    fun anAstralFallbackSitsBetweenPiecesAndBesideAnAstralPiece() {
        assertContentEquals(
            pieces("\u2581a", "b", "<0xF0>", "<0x9F>", "<0xAB>", "<0xA0>", "b"),
            astralIds("ab${MELTING}b"),
        )
        assertContentEquals(
            pieces("\u2581a", "\u2581", SUNRISE, "<0xF0>", "<0x9F>", "<0xAB>", "<0xA0>", SUNRISE, "\u2581a"),
            astralIds("a $SUNRISE$MELTING$SUNRISE a"),
        )
    }

    /** ⚠ An astral character that IS a piece never reaches the fallback. */
    @Test
    fun anAstralCharacterInTheVocabularyIsItsPiece() {
        assertContentEquals(pieces("\u2581", SUNRISE), astralIds(SUNRISE))
    }

    /** ⚠ As the reference truncates: by ids, so the window may end inside a character. */
    @Test
    fun aRowOfAstralFallbacksIsTruncatedByIdsNotByCharacters() {
        val out = SiglipTextTokenizer(astral, 8).encode(MELTING + MELTING).toList()
        assertContentEquals(
            pieces("\u2581", "<0xF0>", "<0x9F>", "<0xAB>", "<0xA0>", "<0xF0>", "<0x9F>", "</s>"),
            out,
        )
    }

    /**
     * ⚠⚠ Half a surrogate pair is U+FFFD, and U+FFFD is a space: the one reading that no
     * platform's library gets to decide. Left to them it is `?` on the JVM and U+FFFD's three
     * bytes on Kotlin/Native, so one query would be different ids on Android and iOS. The
     * reference refuses the string, so there is nothing to match (`models.md` §3.9.5).
     */
    @Test
    fun aLoneSurrogateIsASpaceOnEveryPlatform() {
        val high = Char(0xD83E).toString()
        val low = Char(0xDEE0).toString()
        val wordBreak = pieces("\u2581a", "\u2581a")
        listOf(
            "a${high}a", "a${low}a", "a$low${high}a", "a$high${high}a", "a$high a", "a $low a", "a\uFFFDa",
        ).forEach { text ->
            assertContentEquals(wordBreak, astralIds(text), "units ${text.map { it.code.toString(16) }}")
        }
        // At either end it is trimmed, as a space is.
        listOf(high, low, low + high).forEach { lone ->
            assertContentEquals(pieces("\u2581a"), astralIds("a$lone"), "trailing ${lone.length}")
            assertContentEquals(pieces("\u2581a"), astralIds("${lone}a"), "leading ${lone.length}")
            assertContentEquals(emptyList(), astralIds(lone), "alone ${lone.length}")
        }
        // ⚠ And the half that IS paired is untouched by the half beside it that is not.
        assertContentEquals(
            pieces("\u2581", "<0xF0>", "<0x9F>", "<0xAB>", "<0xA0>"),
            astralIds(high + MELTING),
        )
    }

    @Test
    fun wellFormedReplacesExactlyTheUnpairedSurrogates() {
        val subject = SiglipTextTokenizer(astral)
        for (code in 0..0xFFFF) {
            val unit = Char(code).toString()
            val expected = if (code in 0xD800..0xDFFF) "\uFFFD" else unit
            assertEquals(expected, subject.wellFormed(unit), "U+${code.toString(16)}")
        }
        listOf("", "a", MELTING, "a${MELTING}b", MELTING + SHAKING, SUNRISE + MELTING).forEach { text ->
            assertEquals(text, subject.wellFormed(text), "units ${text.map { it.code.toString(16) }}")
        }
    }

    // ------------------------------------------------------------------ tier 2: the real thing

    private class Golden(val text: String, val ids: IntArray)

    @Test
    fun itReproducesTheReferenceTokenizerExactly() {
        val bytes = realVocabularyBytes()
        if (bytes == null) {
            println("skipped: no -Dtenshu.inference.tokenizer=<tokenizer.bin> — see the KDoc")
            return
        }
        val vocabulary = requireNotNull(UnigramVocabulary.read(bytes)) { "unreadable vocabulary" }
        assertEquals(250_100, vocabulary.size, "not the pinned checkpoint's vocabulary")
        val subject = SiglipTextTokenizer(vocabulary)

        var checked = 0
        GOLDEN.forEach { golden ->
            // ⚠ The whole row, padding included: the text tower reads all 64 positions.
            val expected = golden.ids.toList() + List(64 - golden.ids.size) { REFERENCE_PAD_ID }
            val produced = subject.encode(golden.text).toList()
            assertContentEquals(expected, produced, "tokenising ${golden.text.length}-char case")
            checked++
        }
        assertTrue(checked >= 57, "only $checked golden cases ran")
        println("IN-3b R3: $checked golden cases reproduced exactly")
    }

    private companion object {
        // ⚠ Spelled as UTF-16 units: these three are the subject, and a reader has to be able to
        // tell a pair from a half.
        /** U+1FAE0, in no piece of the real vocabulary. */
        private const val MELTING = "\uD83E\uDEE0"

        /** U+1FAE8, likewise. */
        private const val SHAKING = "\uD83E\uDEE8"

        /** U+1F305, a piece of the real vocabulary. */
        private const val SUNRISE = "\uD83C\uDF05"

        /**
         * Captured from the reference implementation over the query groups of `models.md` §3.9,
         * plus every character the fix-up table exists for, plus characters above U+FFFF that
         * the vocabulary does not hold (`models.md` §3.9.5).
         *
         * ⚠ Generated by `scripts/inference/golden-tokenizer-cases.py` — `transformers` loading
         * the checkpoint directory, called with `padding="max_length"` — and printed with the
         * padding run taken off; [REFERENCE_PAD_ID] is the id that run was made of. Regenerate if
         * the checkpoint changes: these ids are a property of one vocabulary, and a different
         * checkpoint makes every one of them wrong.
         */
        private const val REFERENCE_PAD_ID = 1

        private val GOLDEN = listOf(
        Golden("a sunset over water", intArrayOf(259, 262, 259, 114441, 910, 4582, 1)),   // english
        Golden("my dog in the snow", intArrayOf(1037, 10990, 281, 287, 52529, 1)),   // english
        Golden("birthday cake with candles", intArrayOf(51796, 57161, 514, 259, 77328, 263, 1)),   // english
        Golden("screenshot of a bank statement", intArrayOf(259, 127788, 304, 259, 262, 4896, 28176, 1)),   // english
        Golden("sonnenuntergang am see", intArrayOf(259, 263, 42645, 46553, 3383, 728, 2354, 1)),   // german
        Golden("mein hund im schnee", intArrayOf(7399, 36148, 859, 163403, 265, 265, 1)),   // german
        Golden("geburtstagskuchen mit kerzen", intArrayOf(259, 188295, 263, 103026, 749, 14004, 2790, 1)),   // german
        Golden("kassenbon vom supermarkt", intArrayOf(259, 109091, 13238, 5529, 177949, 270, 1)),   // german
        Golden("coucher de soleil sur la mer", intArrayOf(317, 64078, 269, 259, 57109, 865, 283, 1420, 1)),   // accented_latin
        Golden("café au lait", intArrayOf(29923, 804, 127332, 1)),   // accented_latin
        Golden("el niño en la playa", intArrayOf(362, 259, 29166, 289, 283, 5233, 262, 1)),   // accented_latin
        Golden("crème brûlée", intArrayOf(317, 83291, 330, 66077, 84924, 1)),   // accented_latin
        Golden("mom's birthday", intArrayOf(71528, 51796, 1)),   // punctuation
        Golden("the dog's toy", intArrayOf(287, 259, 62194, 5777, 1)),   // punctuation
        Golden("sunset — golden hour", intArrayOf(259, 114441, 661, 74172, 27247, 1)),   // punctuation
        Golden("a “quoted” sign", intArrayOf(259, 262, 359, 7676, 345, 365, 11329, 1)),   // punctuation
        Golden("🌅 sunset", intArrayOf(259, 243616, 259, 114441, 1)),   // emoji
        Golden("birthday 🎂", intArrayOf(51796, 259, 240890, 1)),   // emoji
        Golden("dog 🐕 in snow", intArrayOf(10990, 259, 242042, 281, 52529, 1)),   // emoji
        Golden("🏔️ mountains", intArrayOf(259, 244395, 4667, 259, 120337, 1)),   // emoji
        Golden("夕日と海", intArrayOf(259, 64935, 838, 725, 4093, 1)),   // cjk
        Golden("犬と雪", intArrayOf(259, 33290, 725, 15404, 1)),   // cjk
        Golden("誕生日ケーキ", intArrayOf(259, 128493, 79775, 1)),   // cjk
        Golden("東京タワー", intArrayOf(93285, 150052, 1)),   // cjk
        Golden("A SUNSET over WATER", intArrayOf(259, 262, 259, 114441, 910, 4582, 1)),   // spacing_case
        Golden("  leading and trailing   ", intArrayOf(259, 22119, 305, 66698, 347, 1)),   // spacing_case
        Golden("double  spaced  words", intArrayOf(14626, 11495, 285, 16732, 1)),   // spacing_case
        Golden("MiXeD cAsE tExT", intArrayOf(259, 80232, 4072, 7461, 1)),   // spacing_case
        Golden("ﬁle screenshot", intArrayOf(4696, 259, 127788, 1)),   // unicode_oddities
        Golden("ｆｕｌｌｗｉｄｔｈ ｔｅｘｔ", intArrayOf(3622, 1493, 7461, 1)),   // unicode_oddities
        Golden("no\u200bzero\u200bwidth", intArrayOf(375, 40211, 259, 1493, 1)),   // unicode_oddities
        Golden("①②③ numbers", intArrayOf(19176, 259, 58250, 1)),   // unicode_oddities
        Golden("no\u200bzero\u200bwidth", intArrayOf(375, 40211, 259, 1493, 1)),   // fixup
        Golden("a\u2581b", intArrayOf(259, 262, 330, 1)),   // fixup
        Golden("x\u007fy", intArrayOf(74151, 1)),   // fixup
        Golden("\ufeffbom at the start", intArrayOf(14134, 344, 287, 3014, 1)),   // fixup
        Golden("|---|---|", intArrayOf(1)),   // fixup
        Golden("", intArrayOf(1)),   // fixup
        Golden("   ", intArrayOf(1)),   // fixup
        Golden("ﬁle", intArrayOf(4696, 1)),   // fixup
        Golden("ｆｕｌｌ", intArrayOf(3622, 1)),   // fixup
        Golden("🫠", intArrayOf(259, 243, 162, 174, 163, 1)),   // astral_fallback
        Golden("🫠🫨", intArrayOf(259, 243, 162, 174, 163, 243, 162, 174, 171, 1)),   // astral_fallback
        Golden("a 🫠 melting", intArrayOf(259, 262, 259, 243, 162, 174, 163, 25852, 1821, 1)),   // astral_fallback
        Golden("🎳 bowling night", intArrayOf(259, 243, 162, 145, 182, 67100, 347, 9713, 1)),   // astral_fallback
        Golden("x🫠y", intArrayOf(259, 329, 243, 162, 174, 163, 276, 1)),   // astral_fallback
        Golden("🌅🫠", intArrayOf(259, 243616, 243, 162, 174, 163, 1)),   // astral_fallback
        Golden("🫠🌅", intArrayOf(259, 243, 162, 174, 163, 243616, 1)),   // astral_fallback
        Golden("dog 🐕 and 🫠 in snow", intArrayOf(10990, 259, 242042, 305, 259, 243, 162, 174, 163, 281, 52529, 1)),   // astral_fallback
        Golden("𠀀", intArrayOf(259, 243, 163, 131, 131, 1)),   // astral_fallback
        Golden("犬と𠀁雪", intArrayOf(259, 33290, 725, 243, 163, 131, 132, 15404, 1)),   // astral_fallback
        Golden("𐍈", intArrayOf(259, 243, 147, 144, 139, 1)),   // astral_fallback
        Golden("🫠🫠🫠🫠🫠🫠🫠🫠🫠🫠🫠🫠🫠🫠🫠🫠🫠🫠🫠🫠", intArrayOf(259, 243, 162, 174, 163, 243, 162, 174, 163, 243, 162, 174, 163, 243, 162, 174, 163, 243, 162, 174, 163, 243, 162, 174, 163, 243, 162, 174, 163, 243, 162, 174, 163, 243, 162, 174, 163, 243, 162, 174, 163, 243, 162, 174, 163, 243, 162, 174, 163, 243, 162, 174, 163, 243, 162, 174, 163, 243, 162, 174, 163, 243, 162, 1)),   // astral_fallback
        Golden("𝐝𝐨𝐠 in snow", intArrayOf(10990, 281, 52529, 1)),   // astral_folded
        Golden("\ud87e\udc00", intArrayOf(259, 30112, 1)),   // astral_folded
        Golden("a\ufffdb", intArrayOf(259, 262, 330, 1)),   // replacement_character
        Golden("\ufffd sunset", intArrayOf(259, 114441, 1)),   // replacement_character
        )
    }
}
