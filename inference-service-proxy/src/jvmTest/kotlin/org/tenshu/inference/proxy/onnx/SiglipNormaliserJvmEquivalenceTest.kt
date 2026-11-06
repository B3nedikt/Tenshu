package org.tenshu.inference.proxy.onnx

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tokenizer left `onnxMain` for `commonMain`, and three JVM-library calls became spelled-out
 * rules so that no platform's library decides them. This proves, over **every** UTF-16 code unit,
 * that the first two rules are what the JVM calls did, and the third what the JDK's codec does.
 *
 * ⚠ JVM-only by nature: it is the old implementation that is being compared against.
 */
class SiglipNormaliserJvmEquivalenceTest {

    private val subject = SiglipTextTokenizer(UnigramVocabulary.of(listOf("</s>" to 0f)))

    /** The two `Replace` normalizers as `tokenizer.json` spells them, which is what `onnxMain` compiled. */
    private val referencePunctuation = Regex("""[!"\#\$%\&'\(\)\*\+,\-\.:;=\?@\[\\\]\^_`\{\|\}\~]""")
    private val referenceWhitespace = Regex("""\s+""")

    @Test
    fun thePunctuationSetIsTheReferenceRegex() {
        for (code in 0..0xFFFF) {
            val character: Char = code.toChar()
            assertEquals(
                referencePunctuation.matches(character.toString()),
                character in SiglipTextTokenizer.PUNCTUATION,
                "U+${code.toString(16)}",
            )
        }
    }

    @Test
    fun theWhitespaceClassIsTheJvmsBackslashS() {
        for (code in 0..0xFFFF) {
            val text: String = code.toChar().toString()
            assertEquals(
                referenceWhitespace.matches(text),
                SiglipTextTokenizer.WHITESPACE.matches(text),
                "U+${code.toString(16)}",
            )
        }
    }

    /**
     * ⚠ The third rule is no longer the JVM's reading (`?` for half a surrogate pair) but U+FFFD,
     * decided before any library sees the string. The JDK's UTF-16 codec makes the same
     * replacement, so it is the independent implementation here.
     */
    @Test
    fun wellFormedIsTheJdksOwnUtf16Replacement() {
        fun jdk(text: String) = String(text.toByteArray(Charsets.UTF_16BE), Charsets.UTF_16BE)
        for (code in 0..0xFFFF) {
            val unit: String = code.toChar().toString()
            listOf(unit, "a${unit}b", unit + unit, "\uD83C$unit", "$unit\uDF05").forEach { text ->
                assertEquals(jdk(text), subject.wellFormed(text), "U+${code.toString(16)} in ${text.length} units")
            }
        }
    }
}
