package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import org.tenshu.core.common.util.Bip39
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

// Smoke tests for `Recovery.claim` that exercise the client-side Bip39
// validation path — these never hit the relay because the typed
// `invalidMnemonic.reason` is decided locally before any network call.
//
// Doesn't cover the `noBackupOnRelay` / `kdfParametersOutOfDate` /
// `network` mappings (those need a real or mocked relay; deferred to a
// follow-up that introduces ktor MockEngine plumbing reusable across all
// onboarding wrappers).
class RecoveryTest : SdkRuntimeTest() {

    // 5 words is not in {12,15,18,21,24} → InvalidLength.
    @Test
    fun claim_invalidLength_returnsTypedInvalidMnemonic() = runTest {
        TenshuSdk.awaitReady().await()

        val result = Recovery.claim("one two three four five", "test-device").await()

        assertEquals(false, result.ok as Boolean)
        val error = result.error
        assertEquals("invalidMnemonic", error.kind as String)
        val reason = error.reason
        assertEquals("invalidLength", reason.kind as String)
        // 5 words.
        assertEquals(5, reason.wordCount as Int)
    }

    // "zzzz" is not in the BIP-39 wordlist → UnknownWord.
    @Test
    fun claim_unknownWord_returnsTypedInvalidMnemonic() = runTest {
        TenshuSdk.awaitReady().await()

        // 12 words including one bogus token. Padding with valid wordlist
        // entries to satisfy the length check.
        val mnemonic = "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon zzzz"

        val result = Recovery.claim(mnemonic, "test-device").await()

        assertEquals(false, result.ok as Boolean)
        val error = result.error
        assertEquals("invalidMnemonic", error.kind as String)
        val reason = error.reason
        assertEquals("unknownWord", reason.kind as String)
        assertEquals("zzzz", reason.word as String)
    }

    // 12 valid words but the last word's checksum bits don't match →
    // ChecksumMismatch. "abandon" * 12 is famously *valid* (the canonical
    // all-zeros entropy). Substituting any other valid word at the end
    // while keeping the first 11 makes the checksum bits wrong.
    @Test
    fun claim_checksumMismatch_returnsTypedInvalidMnemonic() = runTest {
        TenshuSdk.awaitReady().await()

        val mnemonic = "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon ability"

        val result = Recovery.claim(mnemonic, "test-device").await()

        assertEquals(false, result.ok as Boolean)
        val error = result.error
        assertEquals("invalidMnemonic", error.kind as String)
        val reason = error.reason
        assertEquals("checksumMismatch", reason.kind as String)
    }

    // ---------------------------------------------------------------- entropy

    // ⚠⚠ `currentEntropy` is process-global on the singleton runtime, so a leak
    // here would read as a pass in whichever suite ran next. Both hooks matter:
    // the @BeforeTest so these assertions cannot be satisfied by a value some
    // earlier suite left behind, the @AfterTest so they cannot hand one on.
    @BeforeTest
    fun clearEntropy() {
        JsRuntime.currentEntropy = null
    }

    @AfterTest
    fun dropEntropy() {
        JsRuntime.currentEntropy = null
    }

    // The regression guard for the fix, and it is a NEGATIVE one on purpose.
    //
    // `claim` decodes the phrase at step 1, long before it knows whether the
    // realm exists — the obvious place to seat the entropy, and the wrong one.
    // A browser that fails to claim has joined nothing, so a phrase it could
    // still reveal would be for a realm it is not a member of (and after
    // `noBackupOnRelay`, one that may not exist at all).
    //
    // The relay is the unreachable sink from [SdkRuntimeTest], so this reaches
    // the dispatch and fails there — past the validation the other tests stop
    // at, which is exactly the window where a premature assignment would sit.
    @Test
    fun claim_relayFailure_doesNotRetainTheEntropy() = runTest {
        TenshuSdk.awaitReady().await()

        // Valid 24-word phrase: BIP-39's published all-zeros vector. ⚠ The last
        // word is **art** (index 102), and it is not a choice — at 24 words the
        // final word is 3 entropy bits then 8 checksum bits, so for all-zero
        // entropy its index IS `sha256(zeros)[0]`, which is 0x66 == 102. `arrow`
        // one slot below is a `ChecksumMismatch`, and guessing cost this test a
        // round. Must pass step 1 or this proves nothing.
        val mnemonic = ("abandon ".repeat(23) + "art")

        val result = Recovery.claim(mnemonic, "test-device").await()

        assertEquals(
            false, result.ok as Boolean,
            "the sink relay must refuse the claim; if this ever passes the test below is vacuous",
        )
        // ⚠⚠ The guard that keeps this test honest. A failure is not enough —
        // an *invalid* phrase fails too, at step 1, never reaching the window a
        // premature assignment would live in. Assert the claim died at the
        // dispatch, so this cannot quietly become a second copy of
        // `claim_invalidMnemonic_doesNotRetainTheEntropy`.
        assertNotEquals(
            "invalidMnemonic", result.error.kind as String,
            "the phrase must be VALID and fail at the relay; got a validation error, so the " +
                "canonical 24-word vector above has stopped decoding",
        )
        assertNull(
            JsRuntime.currentEntropy,
            "a failed claim must leave no entropy behind — `revealPhrase` would hand back " +
                "a phrase for a realm this browser never joined",
        )
    }

    // ⚠⚠ Pins the PREMISE of the wrapper-length guard in `claim`: that a valid
    // phrase can yield entropy the v2 wrap blob cannot carry. `Bip39.decode`
    // accepts five word counts and only the longest gives the 32 bytes
    // `MasterKeyWrapper.wrap`'s entropy overload requires — so seating a short
    // one would make the next `registerPasskey` throw after persisting the
    // credential, instead of falling back to the v1 blob.
    //
    // ⚠ This pins the Bip39 half only. `MasterKeyWrapper.ENTROPY_SIZE` lives in a
    // private companion and cannot be read from here, so the 32 in `Recovery.kt`
    // is a hand-kept copy — if that constant ever moves, this test still passes.
    @Test
    fun aShorterValidPhraseYieldsEntropyTheWrapperCannotCarry() {
        val twelve = "abandon ".repeat(11) + "about"
        val twentyFour = "abandon ".repeat(23) + "art"

        assertEquals(
            32, Bip39.decode(twentyFour).size,
            "a 24-word phrase must give exactly the 32 bytes the v2 blob carries",
        )
        assertEquals(
            16, Bip39.decode(twelve).size,
            "a 12-word phrase is VALID and gives 16 bytes — the case the guard exists for",
        )
    }

    // The same guard one step earlier: rejected by local validation, never
    // dispatched. Cheap, and it pins the `return@promise` ahead of any write.
    @Test
    fun claim_invalidMnemonic_doesNotRetainTheEntropy() = runTest {
        TenshuSdk.awaitReady().await()

        Recovery.claim("one two three four five", "test-device").await()

        assertNull(JsRuntime.currentEntropy, "a rejected phrase must not seat entropy")
    }
}
