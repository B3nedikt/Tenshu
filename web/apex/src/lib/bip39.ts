// Thin re-exports + apex-side helpers around `@tenshu/sdk/core`'s
// `Bip39`. The wordlist + decode logic both live in Kotlin
// (`core-common/.../Bip39.kt` + `tenshu-sdk-js/.../Bip39.kt`); this
// file is purely about adapting the SDK surface to the shapes apex
// callers want (e.g. a Set for fast prefix-validation lookups).
import { Bip39 } from '@tenshu/sdk/core'

export const BIP39_WORDLIST: ReadonlyArray<string> = Bip39.wordlist

// Frozen Set computed once at module load. Used by the recovery-phrase
// UI's per-word "is this a recovery word?" check; final validation
// always goes through `Bip39.decode` on submit (which also catches
// checksum-mismatch and length errors).
export const BIP39_WORDSET: ReadonlySet<string> = new Set(BIP39_WORDLIST)
