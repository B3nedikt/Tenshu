@file:OptIn(ExperimentalJsExport::class)

package app.tenshu.sdk

import org.tenshu.core.common.util.BIP39_ENGLISH_WORDLIST
import org.tenshu.core.common.util.Bip39InvalidMnemonicException
import org.tenshu.core.common.util.Bip39 as Impl

@JsExport
object Bip39 {

    fun generateMnemonic(entropySize: Int = 32): String =
        Impl.generateMnemonic(entropySize)

    fun encode(entropy: ByteArray): String =
        Impl.encode(entropy)

    /**
     * The BIP-39 English wordlist used by [generateMnemonic]/[encode]/[decode].
     * Exposed so the recovery-phrase UI can drive prefix autocomplete and
     * per-word "is this even a recovery word" feedback without duplicating
     * the list on the JS side. Initialised once at object construction;
     * callers should treat the returned array as read-only.
     */
    val wordlist: Array<String> = BIP39_ENGLISH_WORDLIST.toTypedArray()

    /**
     * Parses a user-typed BIP-39 English mnemonic back to its entropy bytes.
     *
     * Returns a discriminated result: `kind === "ok"` carries `entropy`; the
     * three failure kinds carry the relevant payload (`word` for unknownWord,
     * `wordCount` for invalidLength, no payload for checksumMismatch). The
     * recovery UI switches on `kind` to surface distinct messages — Kotlin's
     * sealed exception hierarchy doesn't translate cleanly through `@JsExport`
     * so we lower it to a flat result class instead of throwing across the
     * Kotlin/JS boundary.
     */
    fun decode(mnemonic: String): Bip39DecodeResult = try {
        val entropy = Impl.decode(mnemonic)
        Bip39DecodeResult("ok", entropy, null, 0)
    } catch (e: Bip39InvalidMnemonicException.UnknownWord) {
        Bip39DecodeResult("unknownWord", null, e.word, 0)
    } catch (e: Bip39InvalidMnemonicException.InvalidLength) {
        Bip39DecodeResult("invalidLength", null, null, e.wordCount)
    } catch (_: Bip39InvalidMnemonicException.ChecksumMismatch) {
        Bip39DecodeResult("checksumMismatch", null, null, 0)
    }
}

@JsExport
class Bip39DecodeResult internal constructor(
    /** "ok" | "invalidLength" | "unknownWord" | "checksumMismatch" */
    val kind: String,
    /** Set when `kind === "ok"`; null otherwise. */
    val entropy: ByteArray?,
    /** Set when `kind === "unknownWord"`; null otherwise. */
    val word: String?,
    /** Set when `kind === "invalidLength"`; 0 otherwise. */
    val wordCount: Int
)
