package org.tenshu.core.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CryptoTest {

    @Test
    fun testBip39Generation() {
        val mnemonic = Bip39.generateMnemonic(16)
        val words = mnemonic.split(" ")
        assertEquals(12, words.size)
        words.forEach { word ->
            assertTrue(BIP39_ENGLISH_WORDLIST.contains(word), "Word $word not in list")
        }
    }

    /**
     * The default entropy size must produce a 24-word mnemonic per the threat model's
     * §11.2 resolved decision (256-bit recovery code).
     */
    @Test
    fun testBip39DefaultIs24Words() {
        val mnemonic = Bip39.generateMnemonic()
        val words = mnemonic.split(" ")
        assertEquals(24, words.size)
        words.forEach { word ->
            assertTrue(BIP39_ENGLISH_WORDLIST.contains(word), "Word $word not in list")
        }
    }

    @Test
    fun testBech32Encoding() {
        val data = "hello world".encodeToByteArray()
        val encoded = Bech32.encode("tenshu", data)
        assertTrue(encoded.startsWith("tenshu1"))

        val (hrp, decoded) = Bech32.decode(encoded)
        assertEquals("tenshu", hrp)
        assertEquals("hello world", data.decodeToString())
        assertEquals(data.size, decoded.size)
    }

    @Test
    fun testBip39Decode_roundTrip12Word() {
        repeat(50) {
            val entropy = nextSecureRandomBytes(16)
            val mnemonic = Bip39.encode(entropy)
            val decoded = Bip39.decode(mnemonic)
            assertContentEquals(entropy, decoded)
        }
    }

    @Test
    fun testBip39Decode_roundTrip24Word() {
        repeat(50) {
            val entropy = nextSecureRandomBytes(32)
            val mnemonic = Bip39.encode(entropy)
            val decoded = Bip39.decode(mnemonic)
            assertContentEquals(entropy, decoded)
        }
    }

    @Test
    fun testBip39Decode_roundTripViaGenerateMnemonic() {
        val mnemonic = Bip39.generateMnemonic()
        val decoded = Bip39.decode(mnemonic)
        assertEquals(32, decoded.size)
    }

    @Test
    fun testBip39Decode_whitespaceTolerance() {
        val canonical = Bip39.generateMnemonic()
        val baseline = Bip39.decode(canonical)

        val padded = "  $canonical  "
        val tabbed = canonical.replace(" ", "\t")
        val doubleSpaced = canonical.replace(" ", "  ")
        val mixedWs = "\t  ${canonical.replace(" ", " \t  ")}  \n"

        assertContentEquals(baseline, Bip39.decode(padded))
        assertContentEquals(baseline, Bip39.decode(tabbed))
        assertContentEquals(baseline, Bip39.decode(doubleSpaced))
        assertContentEquals(baseline, Bip39.decode(mixedWs))
    }

    @Test
    fun testBip39Decode_caseTolerance() {
        val canonical = Bip39.generateMnemonic()
        val baseline = Bip39.decode(canonical)

        // Capitalise the first word — this is the auto-capitalisation case from mobile keyboards.
        val words = canonical.split(" ").toMutableList()
        words[0] = words[0].replaceFirstChar { it.uppercase() }
        val capitalised = words.joinToString(" ")
        assertContentEquals(baseline, Bip39.decode(capitalised))
    }

    @Test
    fun testBip39Decode_invalidLength() {
        // 13 valid words: take a 12-word mnemonic and append another wordlist entry.
        val twelveWord = Bip39.generateMnemonic(16)
        val thirteenWord = "$twelveWord ${BIP39_ENGLISH_WORDLIST[0]}"

        val ex = assertFailsWith<Bip39InvalidMnemonicException.InvalidLength> {
            Bip39.decode(thirteenWord)
        }
        assertEquals(13, ex.wordCount)
    }

    @Test
    fun testBip39Decode_unknownWord() {
        val twelveWord = Bip39.generateMnemonic(16).split(" ").toMutableList()
        twelveWord[1] = "notaword"

        val ex = assertFailsWith<Bip39InvalidMnemonicException.UnknownWord> {
            Bip39.decode(twelveWord.joinToString(" "))
        }
        assertEquals("notaword", ex.word)
    }

    @Test
    fun testBip39Decode_checksumMismatch() {
        // Take the well-known all-zero 16-byte entropy mnemonic
        // ("abandon abandon ... abandon about") and replace the trailing "about" with
        // "abandon" — a deterministic checksum corruption. The previous "swap with any
        // different word" approach was flaky 1/16 of the time when the new word's last
        // 4 bits happened to match the new entropy's expected checksum.
        val canonical = Bip39.encode(ByteArray(16))           // all-zero entropy
        val words = canonical.split(" ").toMutableList()
        require(words.last() != "abandon") {
            "test vector drifted; last word was no longer 'about'"
        }
        words[words.lastIndex] = "abandon"

        assertFailsWith<Bip39InvalidMnemonicException.ChecksumMismatch> {
            Bip39.decode(words.joinToString(" "))
        }
    }

    /**
     * Canonical BIP-39 English test vector. All-zero entropy → the well-known
     * "abandon ... about" mnemonic. Guards against bit-order or checksum drift.
     */
    @Test
    fun testBip39Decode_knownVector_allZero16ByteEntropy() {
        val mnemonic =
            "abandon abandon abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon about"
        val decoded = Bip39.decode(mnemonic)
        assertEquals(16, decoded.size)
        decoded.forEach { byte ->
            assertEquals(0.toByte(), byte, "Expected all-zero entropy from canonical vector")
        }
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size, "Sizes differ")
        for (i in expected.indices) {
            assertEquals(
                expected[i],
                actual[i],
                "Byte at index $i differs (expected=${expected[i]}, actual=${actual[i]})"
            )
        }
    }
}
