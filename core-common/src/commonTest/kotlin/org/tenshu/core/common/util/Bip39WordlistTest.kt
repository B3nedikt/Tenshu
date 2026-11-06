package org.tenshu.core.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ **The wordlist is consensus data, not a resource file.** A word's position is 11 bits of
 * recovery entropy; `DefaultSyncCryptoManager` runs Argon2id over that entropy and the realm's
 * `ownerId` falls out of it. Change one entry and every existing phrase silently derives
 * different keys — it still decodes, it just opens a different realm. So this suite pins the
 * list itself, not the round trip.
 *
 * ⚠⚠ **Why round-trip tests could not have caught the bug this replaced.** Until 2026-09-15 the
 * list had 2060 entries and diverged from the published BIP-39 English wordlist at index 33,
 * carrying 28 words the spec does not contain (`crocodile`, `facilitate`, `romp`, …). Every test
 * that existed was self-referential — `decode(generateMnemonic())`, `encode` then `decode` —
 * and all of them pass against *any* list of 2048+ distinct words. The defect survived five
 * months because nothing ever compared the list to the spec. [wordlistMatchesThePublishedFile]
 * is that comparison; the invariant tests below are what would have caught it even without the
 * published file to hand.
 */
class Bip39WordlistTest {

    @Test
    fun wordlistMatchesThePublishedFile() {
        // SHA-256 of bip-0039/english.txt — 2048 newline-terminated words, no trailing blank
        // line. Reproduce with:
        //   curl -s https://raw.githubusercontent.com/bitcoin/bips/master/bip-0039/english.txt \
        //     | sha256sum
        // ⚠ Recomputed over the list as Kotlin holds it, so a reordering or a stray edit fails
        // here even though the entry count is unchanged.
        val rendered = BIP39_ENGLISH_WORDLIST.joinToString(separator = "\n", postfix = "\n")
        assertEquals(
            "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda",
            sha256(rendered.encodeToByteArray()).toHexString(),
            "the wordlist no longer matches BIP-39's published english.txt — every phrase " +
                "minted under the old contents derives different keys, so this is never a " +
                "cosmetic diff",
        )
    }

    @Test
    fun wordlistHasExactlyTwoThousandAndFortyEightEntries() {
        // ⚠⚠ 2048 == 2^11 is the whole encoding. `decode` packs each index into 11 bits
        // (`index shr (10 - j)`), so entry 2048 and beyond cannot be addressed: `encode` can
        // never emit them, and `decode` masks them down onto early words. The old 2060-entry
        // list did exactly that — `zoo` at index 2059 decoded as `accident`.
        assertEquals(2048, BIP39_ENGLISH_WORDLIST.size)
        assertEquals(
            BIP39_ENGLISH_WORDLIST.size,
            BIP39_ENGLISH_WORDLIST.toSet().size,
            "duplicate entries make `indexOf` ambiguous and silently unreachable",
        )
    }

    @Test
    fun wordlistSatisfiesTheSpecsStructuralRules() {
        // The three properties BIP-39 guarantees for the English list. Each one independently
        // falsified the old list, so any of them alone would have caught it.
        assertEquals(
            BIP39_ENGLISH_WORDLIST.sorted(),
            BIP39_ENGLISH_WORDLIST,
            "the list must be sorted — binary search and diffability both assume it",
        )
        assertTrue(
            BIP39_ENGLISH_WORDLIST.all { it.length in 3..8 },
            "every word is 3-8 characters; offenders: " +
                BIP39_ENGLISH_WORDLIST.filter { it.length !in 3..8 },
        )
        assertTrue(
            BIP39_ENGLISH_WORDLIST.all { w -> w.all { it in 'a'..'z' } },
            "the English list is lowercase ASCII only; offenders: " +
                BIP39_ENGLISH_WORDLIST.filter { w -> !w.all { it in 'a'..'z' } },
        )
        // ⚠ The first four letters identify a word uniquely. Hardware wallets and abbreviated
        // entry rely on it, and the old list broke it (`fact`/`factor`/`factory`).
        val prefixes = BIP39_ENGLISH_WORDLIST.map { it.take(4) }
        assertEquals(
            BIP39_ENGLISH_WORDLIST.size,
            prefixes.toSet().size,
            "four-character prefixes must be unique; collisions: " +
                prefixes.groupBy { it }.filterValues { it.size > 1 }.keys,
        )
    }

    /**
     * ⚠⚠ The published vectors, which are the point of being on the real list at all: a phrase
     * this code mints must mean the same thing to every other BIP-39 implementation. These are
     * from `bip-0039/vectors.json` (the all-zero and all-`0x7f`/`0x80` entropies).
     */
    @Test
    fun officialVectorsRoundTripBothWays() {
        val vectors = listOf(
            "00000000000000000000000000000000" to
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon about",
            "80808080808080808080808080808080" to
                "letter advice cage absurd amount doctor acoustic avoid letter advice cage " +
                "above",
            "ffffffffffffffffffffffffffffffff" to
                "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
            "0000000000000000000000000000000000000000000000000000000000000000" to
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon art",
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to
                "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo " +
                "zoo zoo zoo zoo vote",
        )
        for ((hex, expected) in vectors) {
            val entropy = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertEquals(expected, Bip39.encode(entropy), "encode($hex)")
            assertEquals(
                hex,
                Bip39.decode(expected).toHexString(),
                "decode of the published mnemonic must give back $hex",
            )
        }
    }

    /**
     * ⚠ The regression this file exists for, stated as a test rather than a comment. `zoo` sat
     * at index 2059 in the old list, outside the 11 bits an index gets, so it decoded as
     * `accident` and `encode` could never produce it. The published all-ones vector above is
     * twelve `zoo`s, so it is also the direct check that the tail of the list is addressable.
     */
    @Test
    fun theLastWordIsAddressable() {
        assertEquals("zoo", BIP39_ENGLISH_WORDLIST[2047])
        assertEquals(2047, BIP39_ENGLISH_WORDLIST.indexOf("zoo"))
    }
}
