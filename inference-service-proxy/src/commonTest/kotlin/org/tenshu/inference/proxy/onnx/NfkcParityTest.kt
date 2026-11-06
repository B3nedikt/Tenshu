package org.tenshu.inference.proxy.onnx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One table, run on every platform that has a backend: `java.text.Normalizer` on the JVM,
 * Foundation's `precomposedStringWithCompatibilityMapping` on the iOS simulator
 * (`ios-on-device-inference.md` §6 item 13).
 *
 * ⚠⚠ **The expected values are Unicode's, written out by hand from UAX #15 and the character
 * database, never captured from either platform.** A table recorded from the JVM would only prove
 * that iOS agrees with the JVM where the JVM is right.
 *
 * ⚠ Escaped, never literal: an editor or a formatter that normalises the source file would
 * silently turn a test of NFKC into a test of nothing.
 */
class NfkcParityTest {

    private class Case(val what: String, val input: String, val expected: String)

    private fun hex(text: String): String =
        text.map { it.code.toString(16).uppercase().padStart(4, '0') }.joinToString(" ")

    private fun assertTable(cases: List<Case>, transform: (String) -> String) {
        val wrong: List<String> = cases.mapNotNull { case ->
            val produced: String = transform(case.input)
            if (produced == case.expected) {
                null
            } else {
                "${case.what}: [${hex(case.input)}] gave [${hex(produced)}], expected [${hex(case.expected)}]"
            }
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} of ${cases.size} disagree with Unicode:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun nfkcAgreesWithUnicodeOnEveryRow() = assertTable(NFKC, ::nfkc)

    /**
     * Every Hangul syllable, from its conjoining jamo and from a syllable plus a trailing
     * consonant: 11 172 + 10 773 rows, computed from the standard's own arithmetic (§3.12).
     *
     * ⚠⚠ The second form is the one Foundation's `precomposedStringWithCompatibilityMapping` gets
     * wrong for every row, which is why the iOS actual is not that call.
     */
    @Test
    fun everyHangulSyllableComposesFromItsParts() {
        var wrong = 0
        var first = ""
        fun check(input: String, expected: Char) {
            val produced: String = nfkc(input)
            if (produced != expected.toString()) {
                if (wrong == 0) first = "[${hex(input)}] gave [${hex(produced)}], expected [${hex(expected.toString())}]"
                wrong++
            }
        }
        for (l in 0 until 19) {
            for (v in 0 until 21) {
                val lv: Char = (0xAC00 + (l * 21 + v) * 28).toChar()
                val leading: Char = (0x1100 + l).toChar()
                val vowel: Char = (0x1161 + v).toChar()
                check("$leading$vowel", lv)
                for (t in 1 until 28) {
                    val trailing: Char = (0x11A7 + t).toChar()
                    check("$leading$vowel$trailing", lv + t)
                    check("$lv$trailing", lv + t)
                }
            }
        }
        assertEquals(0, wrong, "$wrong Hangul sequences do not compose, first: $first")
    }

    /** Idempotent: what the tokenizer normalised once is not changed by normalising it again. */
    @Test
    fun nfkcOfItsOwnOutputIsItself() {
        NFKC.forEach { case -> assertEquals(hex(case.expected), hex(nfkc(nfkc(case.input))), case.what) }
    }

    /**
     * ⚠ Not NFKC, and tested here for the same reason: `lowercase()` is the tokenizer's first step
     * and each platform brings its own case tables.
     */
    @Test
    fun lowercaseAgreesWithUnicodeOnEveryRow() = assertTable(LOWERCASE) { it.lowercase() }

    /** The whole chain, on inputs where the order of its steps or a platform's regex would show. */
    @Test
    fun theNormaliserIsTheSameChainOnEveryPlatform() {
        val vocabulary = UnigramVocabulary.of(listOf("</s>" to 0f))
        val subject = SiglipTextTokenizer(vocabulary)
        assertTable(CHAIN, subject::normalise)
    }

    /**
     * Characters whose mapping is newer than some shipped Unicode tables.
     *
     * ⚠⚠ **Not asserted equal across platforms, because they are not.** Measured 2026-10-06: the
     * build's JDK 17 carries Unicode 13 and leaves the third row alone; the iOS 27.0 simulator maps
     * it. Over every code point the two disagree on 158, all of them modifier letters added in
     * Unicode 14 to 16 (`ios-on-device-inference.md` §10.3). Android's normaliser is the phone's
     * own ICU, so its answer moves with the OS, as iOS's does.
     *
     * A platform either knows the mapping or leaves the character alone; anything else fails.
     */
    @Test
    fun aCharacterNewerThanThePlatformsTablesIsLeftAloneNeverMangled() {
        NEWER.forEach { case ->
            val produced: String = nfkc(case.input)
            val verdict: String = when (produced) {
                case.expected -> "mapped"
                case.input -> "UNCHANGED (tables older than ${case.what})"
                else -> "MANGLED"
            }
            println("NFKC-PROBE ${case.what} [${hex(case.input)}] -> [${hex(produced)}] $verdict")
            assertTrue(produced == case.expected || produced == case.input, "${case.what}: [${hex(produced)}]")
        }
    }

    private companion object {
        val NFKC = listOf(
            // ---- plain text is left alone
            Case("ascii", "a sunset over water", "a sunset over water"),
            Case("precomposed latin", "café", "café"),
            Case("precomposed hangul", "한글", "한글"),
            Case("kanji and kana", "夕日と海", "夕日と海"),
            Case("emoji", "🌅", "🌅"),
            // ---- canonical composition
            Case("e + acute", "é", "é"),
            Case("A + ring", "Å", "Å"),
            Case("angstrom sign", "Å", "Å"),
            Case("ohm sign", "Ω", "Ω"),
            Case("kelvin sign", "K", "K"),
            Case("combining marks reordered by class", "q̣̇", "q̣̇"),
            Case("greek dialytika tonos", "̈́", "̈́"),
            Case("u + diaeresis + macron", "ǖ", "ǖ"),
            Case("UAX 15 long s with dots", "ẛ̣", "ṩ"),
            Case("kana + voiced mark", "が", "が"),
            // ---- ligatures and letterlike
            Case("fi ligature", "ﬁ", "fi"),
            Case("ffi ligature", "ﬃ", "ffi"),
            Case("st ligature", "ﬆ", "st"),
            Case("dz with caron digraph", "ǆ", "dž"),
            Case("long s", "ſ", "s"),
            Case("greek beta symbol", "ϐ", "β"),
            Case("black-letter H", "ℌ", "H"),
            Case("alef symbol", "ℵ", "א"),
            Case("trade mark", "™", "TM"),
            Case("arabic lam-alef ligature", "ﻻ", "لا"),
            Case("hebrew alef-lamed ligature", "ﭏ", "אל"),
            // ---- full-width and half-width
            Case("full-width latin", "ｆｕｌｌ", "full"),
            Case("full-width capital", "Ａ", "A"),
            Case("full-width digits", "１２３", "123"),
            Case("full-width exclamation", "！", "!"),
            Case("full-width number sign", "＃", "#"),
            Case("half-width katakana", "ｶ", "カ"),
            Case("half-width katakana + voiced mark", "ｶﾞ", "ガ"),
            Case("half-width katakana + semi-voiced mark", "ﾊﾟ", "パ"),
            // ---- numbers and symbols
            Case("circled digit", "①", "1"),
            Case("circled 20", "⑳", "20"),
            Case("superscript two", "²", "2"),
            Case("subscript three", "₃", "3"),
            Case("vulgar half", "½", "1⁄2"),
            Case("roman numeral eight", "Ⅷ", "VIII"),
            Case("ellipsis", "…", "..."),
            Case("square kg", "㎏", "kg"),
            Case("parenthesised ideograph", "㈱", "(株)"),
            // ---- spaces
            Case("no-break space", "a b", "a b"),
            Case("ideographic space", "a　b", "a b"),
            Case("en quad", "a b", "a b"),
            // ---- hangul
            Case("conjoining jamo LV", "가", "가"),
            Case("conjoining jamo LVT", "각", "각"),
            Case("syllable + trailing jamo", "각", "각"),
            Case("compatibility jamo", "ㄱ", "ᄀ"),
            Case("half-width hangul", "ﾡ", "ᄀ"),
            Case("compatibility jamo compose into a syllable", "ㄱㅏ", "가"),
            // ---- CJK compatibility
            Case("compatibility ideograph", "豈", "豈"),
            Case("compatibility ideograph FA30", "侮", "侮"),
            Case("kangxi radical", "⼀", "一"),
            Case("square katakana word", "㌀", "アパート"),
            Case("square corporation", "㍿", "株式会社"),
            Case("square era name heisei", "㍻", "平成"),
            Case("vertical form comma", "︐", ","),
        )

        val LOWERCASE = listOf(
            Case("ascii", "A SUNSET", "a sunset"),
            Case("latin-1", "CAFÉ", "café"),
            Case("capital sharp s", "ẞ", "ß"),
            Case("capital I with dot above", "İ", "i̇"),
            Case("capital dz digraph", "Ǆ", "ǆ"),
            Case("titlecase dz digraph", "ǅ", "ǆ"),
            Case("greek final sigma", "ΟΔΟΣ", "οδος"),
            Case("greek non-final sigma", "ΣΟ", "σο"),
            Case("cyrillic", "СОБАКА", "собака"),
            Case("full-width", "Ａ", "ａ"),
            Case("roman numeral", "Ⅷ", "ⅷ"),
            Case("circled letter", "Ⓐ", "ⓐ"),
            Case("astral: deseret", "𐐀", "𐐨"),
            Case("kelvin sign", "K", "k"),
            Case("caseless", "夕日", "夕日"),
        )

        val CHAIN = listOf(
            Case("case and spacing", "A  Dog", "a dog"),
            // ⚠ Punctuation is stripped BEFORE NFKC, so a full-width mark survives as its ASCII fold.
            Case("full-width punctuation survives", "ｈｉ！", "hi!"),
            Case("ascii punctuation is stripped", "hi!", "hi"),
            // ⚠ The whitespace step is six ASCII characters. A no-break space is folded by NFKC,
            // after it, and the last step collapses what that leaves.
            Case("no-break spaces collapse after NFKC", "a  b", "a b"),
            Case("ideographic spaces collapse after NFKC", "a　　b", "a b"),
            Case("tab, newline, vertical tab, form feed", "a\t\n\u000B\u000C\rb", "a b"),
            Case("line separator is not ASCII whitespace", "a b", "a b"),
            Case("ellipsis becomes dots after the strip", "wait…", "wait..."),
            Case("zero width space becomes a space", "no​width", "no width"),
            Case("capital I with dot", "İstanbul", "i̇stanbul"),
            Case("greek final sigma", "ΟΔΟΣ", "οδος"),
        )

        val NEWER = listOf(
            Case("Unicode 12.1 (2019), square era name reiwa", "㋿", "令和"),
            Case("Unicode 13 (2020), segmented digit zero", "🯰", "0"),
            Case("Unicode 14 (2021), modifier letter capital C", "ꟲ", "C"),
        )
    }
}
