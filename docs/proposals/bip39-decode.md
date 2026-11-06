# `Bip39.decode()` — Recovery Flow Primitive

**Status:** ⚠ this said "Not started." until 2026-09-15, long after `decode()` shipped
(`core-common/.../util/Bip39.kt`, tested by `CryptoTest`) and the recovery UI was wired on top
of it. **Phase 1 is built.** ⚠⚠ Its Step 9 below is the part that aged badly — see the
amendment there.
**Scope:** `:core-common` (`commonMain` + `commonTest`); a follow-up PR wires the result
into the recovery flow proper.
**Threat-model context:** closes the first bullet of
[`docs/sync/threat-model.md` §11a](../sync/threat-model.md). The §11.2 design
("raw entropy decoded from the mnemonic is fed directly into Argon2id per T-RC-02") and
the existing `Bip39.encode()` already specify everything `decode()` must invert. This
work does **not** touch any A1–A4 / A9 invariant, so no threat-model amendment is
required — just an `§11a` cleanup once the work lands.

This is sized as a focused, ≈half-day PR plus a follow-up of similar size that wires the
primitive into the actual recovery UI. Keep them separate; the cryptographic primitive
is the load-bearing piece and benefits from landing standalone with thorough tests.

---

## Phase 1 — The cryptographic primitive

This phase closes the `§11a` open item and unblocks the recovery flow without touching
any UI. After it lands, the recovery flow described in §11.1 is implementable end-to-end
in a future PR; today it cannot be.

### Step 1.1 — Add the typed exception

**File:** `core-common/src/commonMain/kotlin/org/tenshu/core/common/util/Bip39.kt`
(extend the existing file).

```kotlin
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
```

Two reasons for the sealed split:

- The UI surface in §11.1 needs to give different feedback for "you mistyped a word"
  vs. "the whole code is wrong". `UnknownWord` carries the offending token so the UI
  can highlight it.
- `ChecksumMismatch` is the case where every word is real but the user typed an
  inconsistent set — this is the "did you copy your code wrong?" message, distinct from
  "this isn't even a Tenshu / BIP-39 code".

### Step 1.2 — Add `decode()`

Append to `Bip39`:

```kotlin
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
```

Implementation notes for the reviewer:

- The bit-packing direction (`(index shr (10 - j)) and 1`) mirrors `encode()`'s
  `(entropy[i].toInt() shr (7 - j)) and 1`. If you change one, change both — they must
  invert.
- The `checksumSize` formula `totalBits / 33` is exact arithmetic; `12 * 11 = 132`,
  `132 / 33 = 4`, matching the BIP-39 spec for 16-byte entropy. Same for 24 words:
  `264 / 33 = 8`, matching 32-byte entropy.
- Lowercasing is intentional: the wordlist is all-lowercase, but a user typing on a
  phone may get auto-capitalisation on the first word. We accept that. We do **not**
  unicode-normalise — the English wordlist is ASCII-only by design, so any non-ASCII
  character will fail the wordlist lookup with `UnknownWord`, which is the right
  failure mode.
- We do **not** need constant-time wordlist lookup for security; the wordlist is public
  and the only timing-sensitive step (the checksum compare) is just a few bytes of
  hash output. For a recovery code that's typed once after a device loss, timing
  attacks aren't in scope.

### Step 1.3 — Update the file's KDoc

In the `Bip39` object's existing KDoc, replace the trailing TODO line:

```kotlin
 * TODO: implement `decode()` (mnemonic → entropy) so the recovery flow can accept user input.
```

with a forward pointer that keeps the design rationale visible:

```kotlin
 * `decode()` is the inverse of `encode()`; it parses a user-typed mnemonic back to the
 * raw entropy bytes that are then fed to Argon2id per `docs/sync/threat-model.md`
 * T-RC-02. Use the typed `Bip39InvalidMnemonicException` subtypes to drive UI feedback
 * (mistyped word vs. wrong code).
```

### Step 1.4 — Tests

**File:** `core-common/src/commonTest/kotlin/org/tenshu/core/common/util/CryptoTest.kt`
(extend the existing file).

Coverage tier:

1. **Round-trip — 12-word (16-byte entropy).** Generate random entropy via
   `nextSecureRandomBytes(16)`, encode, decode, assert equal. Repeat ~50 times.
2. **Round-trip — 24-word (32-byte entropy, the production default).** Same as above,
   `nextSecureRandomBytes(32)`. Repeat ~50 times. Must pass for the recovery flow to
   work in production.
3. **Round-trip via `generateMnemonic()`.** `Bip39.decode(Bip39.generateMnemonic())`
   must succeed and produce a 32-byte result.
4. **Whitespace tolerance.** Same valid mnemonic with extra leading/trailing spaces,
   tab characters, and double spaces between words must all decode identically.
5. **Case tolerance.** A valid mnemonic with the first word capitalised
   (`Abandon abandon ...`) must decode identically to the all-lowercase form.
6. **Negative — invalid length.** A 13-word string of valid words throws
   `Bip39InvalidMnemonicException.InvalidLength` with `wordCount == 13`.
7. **Negative — unknown word.** A 12-word string with the second word replaced by
   `"notaword"` throws `UnknownWord` with `word == "notaword"`.
8. **Negative — checksum mismatch.** Take a valid 12-word mnemonic, replace its last
   word with another *valid* wordlist entry (so length and lookup both succeed),
   assert `ChecksumMismatch`.
9. **Known-vector test (BIP-39 spec).** Use one published BIP-39 English test vector
   (the project does not need to ship the full vendor vector set — one vector is
   enough to catch endianness/bit-order regressions). Suggested:

   ```
   entropy = "00000000000000000000000000000000"
   mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon
               abandon abandon abandon about"
   ```

   Assert `Bip39.decode(mnemonic).toHex() == "00000000000000000000000000000000"`. This
   guards against accidental drift from the BIP-39 standard, which would silently
   diverge our recovery codes from any third-party implementation a user might cross-check
   against.

   ⚠⚠ **Amendment (2026-09-15): this vector cannot detect the drift it claims to guard
   against, and did not.** The all-zeros 12-word mnemonic uses indices 0 and 3 only, and the
   wordlist this repo shipped was correct for its first 33 entries — it diverged from the
   published `english.txt` from index 33 onward, ran to 2060 entries, and meant something
   different from the spec at 2015 of the 2048 addressable indices. Step 9 passed throughout.
   So did every other test here, because each one is self-referential: `encode` then `decode`
   round-trips, and `generateMnemonic` checked against the same list it was drawn from, all
   hold for *any* list whose first 2048 entries are distinct. The defect survived five months.
   ⚠⚠ **A round trip can never test a wordlist; only a comparison to the published file can.**
   `Bip39WordlistTest` is that comparison — a SHA-256 pin over the list as Kotlin holds it,
   plus the spec's structural invariants (2048 entries, sorted, 3–8 chars, unique four-character
   prefixes), each of which independently falsified the old list. The consequences of the swap
   are recorded in [`../sync/threat-model.md`](../sync/threat-model.md) §11.2.

Step 4–8 are the load-bearing ones for the recovery flow's UX. Step 9 is the
load-bearing one for the cryptographic correctness guarantee.

### Step 1.5 — Run

```bash
./gradlew :core-common:test
```

Sanity-check that `:core-common:assemble` still produces JS output too (the file is
`commonMain`, so it must compile to JS as part of the web track work
described in `:tenshu-sdk-js`):

```bash
./gradlew :core-common:compileKotlinJs
```

Both should pass without warnings.

### Step 1.6 — Close the threat-model open item

Edit `docs/sync/threat-model.md` §11a. Remove (or amend) this bullet:

```
- **`Bip39.decode()` for recovery input.** The current `Bip39` implementation only generates
  mnemonics; the inverse (mnemonic → entropy) is required so the user can type their recovery
  code back in. Until this exists, the recovery flow described in §11.1 cannot complete on a
  fresh install.
```

Replace with a tightened §11.2 acknowledgement (or simply delete the bullet, leaving the
"Recovery wordlist localisation" item as the only remaining §11a entry). The
"Implementation notes" paragraph in §11.2 (lines 547–555) already refers to
`Bip39.kt`; once `decode()` lands, drop the sentence
`The implementation currently only encodes (...); a decode() path that maps a user-typed
mnemonic back to entropy is required for the recovery flow and is tracked in §11a.`

Also delete the `### Bip39.decode() Missing — Recovery Flow Blocker` entry from
`docs/tech-debt.md` (§"Sync, Pairing & Recovery"). Keep the BIP-39 wordlist (English
only) entry — it remains open.

---

## Phase 2 — Consumer wiring (separate PR)

Phase 1 unblocks the recovery flow but does not yet make it usable from any UI. Phase 2
wires the primitive into the recovery path. This is sized similarly to Phase 1 but
touches different layers, so keep it as a separate change.

### Step 2.1 — Add a "recovery code → master key" path

The threat model's T-RC-02 says raw entropy from `Bip39.decode(...)` is fed to Argon2id,
producing the master identity key. Today `SyncRepository.getRecoveryPhrase()` only
calls `Bip39.generateMnemonic()` (control-shared/.../SyncRepository.kt:90). Add a
counterpart:

```kotlin
override suspend fun restoreFromRecoveryPhrase(mnemonic: String): TResult<MasterIdentity>
```

Implementation:

1. Call `Bip39.decode(mnemonic)` inside a `try` block; map each
   `Bip39InvalidMnemonicException` subtype to a typed `TResult.Failure` whose error
   carries enough information for the UI (`UnknownWord` → highlight a word;
   `InvalidLength` → "expected 12 or 24 words"; `ChecksumMismatch` → "recovery code
   doesn't look right").
2. On success, hand the entropy to the existing Argon2id KDF used by signup (the same
   path that today derives the master key from a freshly generated phrase). Surface the
   resulting `MasterIdentity` through `TResult.Success`.
3. The KDF call is async (Argon2id is intentionally slow); make sure the `restoreFromRecoveryPhrase`
   suspend boundary is honest about that — no `runBlocking`.

### Step 2.2 — Wire into the control-app recovery screen

Out of scope for this doc — the recovery screen does not exist yet. Step 2.1's interface
is the seam the screen will plug into. Leave a follow-up tracked in `milestones.md`
("Recovery flow UX") that depends on Phase 1 + Phase 2.1.

### Step 2.3 — Integration test

End-to-end test in `:control-shared` (or wherever `SyncRepository` lives in test):

1. Generate a recovery phrase via `getRecoveryPhrase()`.
2. Call `restoreFromRecoveryPhrase(phrase)`.
3. Assert the resulting `MasterIdentity`'s public key matches the public key derived
   during signup with the same phrase.

This test is the real proof that recovery works end-to-end. It's worth running in CI as
a `:control-shared:test` unit, not buried in a one-off integration suite.

---

## Out of scope (deliberately)

- **Multi-language wordlists.** Tracked separately in §11a "Recovery wordlist
  localisation" and in `docs/tech-debt.md` §"BIP-39 Wordlist — English-Only". Until that
  decision is made, `decode()` is English-only — which is what `encode()` already is, so
  there is no asymmetry.
- **BIP-39 PBKDF2 seed derivation.** Explicitly excluded by `Bip39.kt`'s file KDoc and
  by threat-model §11.2. Adding the PBKDF2 step would weaken our parameters; do not
  "fix" this without first revisiting that decision.
- **Constant-time wordlist lookup.** Not a security requirement here (see §1.2 notes).
  Leave the linear `indexOf` alone.
- **Recovery-blob storage on the relay.** Unrelated; tracked in
  `docs/proposals/sync-backend-relay-plan.md` Phase 3.2.

---

## References

- [
  `core-common/src/commonMain/kotlin/org/tenshu/core/common/util/Bip39.kt`](../../core-common/src/commonMain/kotlin/org/tenshu/core/common/util/Bip39.kt)
  — the file being extended.
- [
  `core-common/src/commonTest/kotlin/org/tenshu/core/common/util/CryptoTest.kt`](../../core-common/src/commonTest/kotlin/org/tenshu/core/common/util/CryptoTest.kt)
  — the test file being extended.
- [`docs/sync/threat-model.md`](../sync/threat-model.md) §11.1 (recovery flow), §11.2
  (recovery code design), §11a (open items closed by this work), T-RC-02 (Argon2id
  KDF parameters).
- [`docs/tech-debt.md`](../tech-debt.md) §"`Bip39.decode()` Missing — Recovery Flow
  Blocker" — the entry this work removes.
- BIP-39 spec — `https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki` —
  for the canonical bit ordering and checksum rule.
