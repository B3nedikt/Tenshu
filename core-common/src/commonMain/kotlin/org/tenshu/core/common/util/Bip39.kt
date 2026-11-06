package org.tenshu.core.common.util

import org.tenshu.core.common.util.Bip39.encode


/**
 * Simplified BIP-39 implementation. Encodes entropy + checksum into a mnemonic word list using
 * the standard BIP-39 English wordlist. Used to generate the user's recovery code.
 *
 * Intentional simplifications:
 * - Default entropy size is 32 bytes (256 bits → 24 words), matching the
 *   threat model resolved decision (`docs/sync/threat-model.md` §11.2).
 * - The BIP-39 PBKDF2 *seed* derivation is **not** performed here. The recovery code is fed
 *   into Argon2id directly per `docs/sync/threat-model.md` T-RC-02. Adding the BIP-39 seed
 *   step would only weaken our parameters; do not "fix" this without revisiting that decision.
 * - English wordlist only. Other-language wordlists are a localisation TODO.
 *
 * `decode()` is the inverse of `encode()`; it parses a user-typed mnemonic back to the
 * raw entropy bytes that are then fed to Argon2id per `docs/sync/threat-model.md`
 * T-RC-02. Use the typed [Bip39InvalidMnemonicException] subtypes to drive UI feedback
 * (mistyped word vs. wrong code).
 */
object Bip39 {

    fun generateMnemonic(entropySize: Int = 32): String {
        val entropy = nextSecureRandomBytes(entropySize)
        return encode(entropy)
    }

    fun encode(entropy: ByteArray): String {
        val hash = sha256(entropy)
        val checksumSize = entropy.size * 8 / 32

        val bits = BooleanArray(entropy.size * 8 + checksumSize)
        for (i in entropy.indices) {
            for (j in 0 until 8) {
                bits[i * 8 + j] = (entropy[i].toInt() shr (7 - j)) and 1 != 0
            }
        }

        for (i in 0 until checksumSize) {
            bits[entropy.size * 8 + i] = (hash[i / 8].toInt() shr (7 - (i % 8))) and 1 != 0
        }

        val words = mutableListOf<String>()
        for (i in 0 until bits.size / 11) {
            var index = 0
            for (j in 0 until 11) {
                index = index shl 1
                if (bits[i * 11 + j]) index = index or 1
            }
            words.add(BIP39_ENGLISH_WORDLIST[index])
        }

        return words.joinToString(" ")
    }

    /**
     * Inverse of [encode]. Parses a user-typed BIP-39 English mnemonic back to the entropy
     * bytes it encodes.
     *
     * Input handling:
     * - Splits on whitespace runs (handles tabs, multiple spaces, trailing/leading whitespace).
     * - Lowercases each token before lookup; the BIP-39 English wordlist is all-lowercase.
     *
     * @throws Bip39InvalidMnemonicException.InvalidLength if the word count is not in
     *         {12, 15, 18, 21, 24}.
     * @throws Bip39InvalidMnemonicException.UnknownWord if a token is not in the wordlist.
     * @throws Bip39InvalidMnemonicException.ChecksumMismatch if the parsed checksum does
     *         not match `sha256(entropy)`.
     */
    fun decode(mnemonic: String): ByteArray {
        val words = mnemonic.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        val wordCount = words.size
        if (wordCount !in setOf(12, 15, 18, 21, 24)) {
            throw Bip39InvalidMnemonicException.InvalidLength(wordCount)
        }

        // Reverse the 11-bit indexing.
        val totalBits = wordCount * 11
        val checksumSize = totalBits / 33   // entropy.size * 8 / 32 == totalBits / 33
        val entropySize = (totalBits - checksumSize) / 8

        val bits = BooleanArray(totalBits)
        for ((i, raw) in words.withIndex()) {
            val word = raw.lowercase()
            val index = BIP39_ENGLISH_WORDLIST.indexOf(word)
            if (index < 0) throw Bip39InvalidMnemonicException.UnknownWord(raw)
            for (j in 0 until 11) {
                bits[i * 11 + j] = (index shr (10 - j)) and 1 != 0
            }
        }

        val entropy = ByteArray(entropySize)
        for (i in 0 until entropySize) {
            var b = 0
            for (j in 0 until 8) {
                b = b shl 1
                if (bits[i * 8 + j]) b = b or 1
            }
            entropy[i] = b.toByte()
        }

        val expectedHash = sha256(entropy)
        for (i in 0 until checksumSize) {
            val actual = bits[entropy.size * 8 + i]
            val expected = (expectedHash[i / 8].toInt() shr (7 - (i % 8))) and 1 != 0
            if (actual != expected) throw Bip39InvalidMnemonicException.ChecksumMismatch()
        }

        return entropy
    }
}

sealed class Bip39InvalidMnemonicException(message: String) : RuntimeException(message) {
    /** Input contained a token that is not in the BIP-39 English wordlist. */
    class UnknownWord(val word: String) :
        Bip39InvalidMnemonicException("Word '$word' is not in the BIP-39 English wordlist.")

    /** Input had a valid wordlist but the wrong number of words (must be 12, 15, 18, 21, or 24). */
    class InvalidLength(val wordCount: Int) :
        Bip39InvalidMnemonicException(
            "Mnemonic has $wordCount words; valid lengths are 12, 15, 18, 21, or 24."
        )

    /** Input parsed cleanly but the BIP-39 checksum did not match. */
    class ChecksumMismatch :
        Bip39InvalidMnemonicException("BIP-39 checksum mismatch — recovery code is invalid.")
}
